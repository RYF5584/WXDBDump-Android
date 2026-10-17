package io.github.ryf5584.wxdbdump.di

import android.content.Context
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.core.EnvironmentSnapshot
import io.github.ryf5584.wxdbdump.core.RunMode
import io.github.ryf5584.wxdbdump.core.XposedFailure
import io.github.ryf5584.wxdbdump.data.DataSource
import io.github.ryf5584.wxdbdump.data.LoopbackClient
import io.github.ryf5584.wxdbdump.data.RootDataSource
import io.github.ryf5584.wxdbdump.data.SettingsRepository
import io.github.ryf5584.wxdbdump.data.XposedDataSource
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.root.RootShell

/**
 * Hand-written dependency container. No Hilt, keeping the build light and fast.
 *
 * <p>Two extraction channels: root, which derives the key offline and decrypts in-app, and
 * Xposed, which lets WeChat decrypt. Both expose one interface, so screens do not branch.
 */
class AppContainer(val context: Context) {

    val settings = SettingsRepository(context)

    val loopback = LoopbackClient()

    val xposedSource = XposedDataSource(context, loopback)

    val rootSource = RootDataSource(context)

    /** Returns the data source for a mode. */
    fun sourceOf(mode: RunMode): DataSource = when (mode) {
        RunMode.ROOT -> rootSource
        RunMode.XPOSED -> xposedSource
    }

    /** Whether WeChat is installed, and its version. */
    fun wechatInfo(): Pair<Boolean, String> = runCatching {
        val pi = context.packageManager.getPackageInfo("com.tencent.mm", 0)
        true to (pi.versionName ?: "")
    }.getOrElse { t ->
        Log.w("detect", "查询微信安装状态失败（视为未安装）", t)
        false to ""
    }

    /** Whether WeChat is running, which separates an inactive module from a stopped app. */
    fun wechatRunning(): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.any { it.processName == "com.tencent.mm" } == true
    }.getOrElse { t ->
        Log.w("detect", "查询微信进程状态失败（视为未运行）", t)
        false
    }

    /**
     * Detects both channels.
     *
     * <p>Activation is proven by the HMAC challenge-response handshake rather than by reading
     * LSPosed's module list.
     *
     * <p>Root availability comes from running su once and checking the uid, which also
     * triggers the system grant dialog.
     */
    suspend fun detect(): EnvironmentSnapshot {
        val t0 = System.currentTimeMillis()
        Log.banner("DETECT START", "探测 Root 与 Xposed 两条通道")

        val (installed, version) = wechatInfo()
        val isRunning = wechatRunning()

        RootShell.available()
        val root = when (RootShell.state()) {
            RootShell.State.OK -> DetectionState.Available
            // Root without visibility of WeChat's directory needs a different fix from no root at all
            RootShell.State.BLOCKED ->
                DetectionState.Unavailable(context.getString(R.string.root_blocked))
            // A missing su binary means root was never granted, so point the user at the manager
            RootShell.State.NO_ROOT ->
                DetectionState.Unavailable(
                    context.getString(
                        if (RootShell.suHidden) R.string.root_not_granted else R.string.root_denied
                    ),
                )
            else -> DetectionState.Unavailable(context.getString(R.string.root_denied))
        }

        val x = loopback.probe()
        val xposed = x.state
        val failure = if (xposed is DetectionState.Available) {
            XposedFailure.NONE
        } else {
            when {
                !installed -> XposedFailure.WECHAT_NOT_INSTALLED
                x.failure == XposedFailure.TOKEN_MISMATCH -> XposedFailure.TOKEN_MISMATCH
                !isRunning -> XposedFailure.WECHAT_NOT_RUNNING
                x.failure == XposedFailure.CONNECT_FAILED -> XposedFailure.TIMEOUT
                else -> x.failure
            }
        }

        val snap = EnvironmentSnapshot(
            wechatInstalled = installed,
            wechatVersion = version,
            xposed = xposed,
            xposedFailure = failure,
            root = root,
        )

        Log.banner(
            "DETECT RESULT",
            "微信: installed=$installed version=$version running=$isRunning\n" +
                "root: ${root::class.simpleName}  可用=${root is DetectionState.Available}\n" +
                "xposed: ${xposed::class.simpleName} failure=$failure\n" +
                "可用模式: ${snap.availableModes}\n" +
                "耗时: ${System.currentTimeMillis() - t0}ms",
        )
        return snap
    }
}
