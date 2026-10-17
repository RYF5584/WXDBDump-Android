package io.github.ryf5584.wxdbdump

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import io.github.ryf5584.wxdbdump.di.AppContainer
import io.github.ryf5584.wxdbdump.payload.Log

class DbExportApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // ---- 1. Attach this process's logcat channel ----
        // Without it the ready flag stays false, logcat writes become no-ops and the app is left
        // with System.out only, which is invisible on Android.
        Log.tryLogcat()
        Log.init("app")
        Log.banner("APP BOOT", buildInfo())

        // ---- 2. Crash handler, chained to the original ----
        installUncaughtHandler()

        // ---- 3. Verify the logcat channel really works ----
        Handler(Looper.getMainLooper()).post {
            Log.i("boot", "main looper ready · logcatReady=${Log.logcatReady()}")
        }

        container = AppContainer(this)

        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.getEmptyLocaleList(),
        )
        Log.i("boot", "Application 初始化完成")
    }

    private fun buildInfo(): String = buildString {
        append("app=").append(BuildConfig.VERSION_NAME)
        append("(").append(BuildConfig.VERSION_CODE).append(")")
        append(" debug=").append(BuildConfig.DEBUG).append('\n')
        append("android=").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(')')
        append(" model=").append(Build.MODEL).append(" brand=").append(Build.BRAND).append('\n')
        append("abis=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        append("pkg=").append(packageName).append(" pid=").append(android.os.Process.myPid())
    }

    /**
     * Last-resort uncaught exception handler.
     *
     * An uncaught exception on a background thread, whether a loopback connection, a key
     * worker or a coroutine, would otherwise leave no log at all.
     *
     * It must chain to the previous handler rather than replacing it.
     */
    private fun installUncaughtHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.banner(
                    "UNCAUGHT EXCEPTION",
                    "thread=${thread?.name}\n" + Log.stack(throwable, 24),
                )
            } catch (ignored: Throwable) {
            }
            try {
                prev?.uncaughtException(thread, throwable)
            } catch (ignored: Throwable) {
            }
        }
        Log.i("boot", "未捕获异常处理器已安装（prev=${prev?.javaClass?.name}）")
    }
}
