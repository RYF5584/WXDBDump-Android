package io.github.ryf5584.wxdbdump.xposed

import android.app.Application
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.configs
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.bridge.event.YukiXposedEvent
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.proto.Channel

/**
 * YukiHookAPI entry point.
 *
 * Layering:
 *   the Kotlin layer in this file only decides how to start the engine inside WeChat;
 *   {@code payload} (pure Java) owns what to enumerate, what to hook and how to export.
 *
 * Logging: the logcat tag is fixed, so one {@code adb logcat -s WxDbExport:* *:S} captures
 * both WeChat's process and this app; process identity travels inside the message body.
 */
@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onInit() {
        // Attach the log channel here: the diagnostics in onHandleLoadPackage come after this,
        // and attaching later loses every message emitted during injection.
        Log.tryLogcat()
        Log.init("xposed-init")
        Log.banner(
            "MODULE ONINIT",
            "module=${BuildConfig.APPLICATION_ID}\nlogcatReady=${Log.logcatReady()}\n" +
                "tip: adb logcat -s WxDbExport:* *:S",
        )

        configs {
            isDebug = false
            // Resources, SharedPreferences and DataChannel are unused; turning them off limits side effects
            isEnableModuleAppResourcesCache = false
            isEnableHookSharedPreferences = false
            isEnableDataChannel = false
        }
    }

    /**
     * Diagnostics: confirms whether the module was injected at all, and shows the host loader.
     * This is the quickest signal when checking whether LSPosed is working.
     */
    override fun onXposedEvent() {
        YukiXposedEvent.onHandleLoadPackage { lpp ->
            Log.banner(
                "MODULE INJECTED",
                "pkg=${lpp.packageName}\nproc=${lpp.processName}\n" +
                    "loader=${lpp.classLoader?.javaClass?.name ?: "null"}",
            )
        }
    }

    override fun onHook() = encase(WxDbExportHooker())
}

/** Runs once per WeChat process. */
class WxDbExportHooker : YukiBaseHooker() {

    override fun onHook() {
        Log.banner("MODULE ONHOOK", "hooker loading (may run once in the zygote domain)")
        // Engine bootstrap, after Application.onCreate. This callback is only the entry point;
        // readiness is decided inside the engine (see startEngine()).
        onAppLifecycle {
            onCreate { startEngine() }
        }
    }

    /**
     * Starts the engine in WeChat's main process only:
     *   - only the main process opens those encrypted databases
     *   - the loopback port is global, so a second bind would fail
     */
    private fun startEngine() {
        try {
            Log.tryLogcat()
            val app = appContext ?: run {
                Log.e("boot", "appContext is null, cannot start the engine")
                return
            }
            val proc = XposedHost.currentProcessName(app)
            Log.init(proc)

            Log.banner(
                "ENGINE START",
                "proc=$proc\npid=${android.os.Process.myPid()}\n" +
                    "appClassLoader=${appClassLoader?.javaClass?.name}\n" +
                    "logcatReady=${Log.logcatReady()}",
            )

            installUncaughtHandler()

            if (WECHAT_MAIN_PROCESS != proc) {
                Log.i("boot", "not the main process ($proc), engine not started")
                return
            }

            val host = XposedHost(app, appClassLoader, proc)
            val engine = WxDbEngine(host)

            // Install hooks on the unobfuscated com.tencent.wcdb.* classes, watching for classes
// that have not loaded yet and retrying on a timer
            val report = engine.installHooks()
            Log.i("hook", "hook installation summary: $report")

            // Start the loopback server so the app can detect the module and pull data
            LoopbackServer(engine, Channel.PORT, Channel.TOKEN).start()
        } catch (t: Throwable) {
            // Never crash WeChat
            Log.banner("ENGINE FAILED", Log.stack(t, 20))
            Log.e("boot", "engine bootstrap failed; WeChat keeps running", t)
        }
    }

    /**
     * Last-resort handler. It must chain to the previous handler, or WeChat's own crash
     * reporting is swallowed. Background threads have no other trace.
     */
    private fun installUncaughtHandler() {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    Log.banner(
                        "UNCAUGHT EXCEPTION (wechat proc)",
                        "thread=${thread?.name}\n" + Log.stack(throwable, 24),
                    )
                } catch (ignored: Throwable) {
                }
                try {
                    prev?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                }
            }
            Log.i("boot", "uncaught handler installed (prev=${prev?.javaClass?.name})")
        } catch (t: Throwable) {
            Log.w("boot", "failed to install the uncaught handler (acceptable)", t)
        }
    }

    private companion object {
        const val WECHAT_MAIN_PROCESS = "com.tencent.mm"
    }
}
