package io.github.ryf5584.wxdbdump.root

import android.content.Context
import io.github.ryf5584.wxdbdump.payload.EngineHost
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.platform.Hooks
import java.io.File

/**
 * Root implementation of {@code EngineHost}.
 *
 * <p>It lets root mode reuse the payload enumeration code, since only file access differs
 * between the two channels.
 *
 * <p>The MMKV root is returned as-is rather than staged: the app's uid cannot read
 * WeChat's private directory, and copying every file costs one su process each.
 */
class RootHost(private val context: Context) : EngineHost {

    private val source = RootFileSource()

    init {
        // Multi-line commands run from script files, never from su -c
        RootShell.scriptDir = File(context.filesDir, "rootsh")
            .apply { mkdirs() }
            .absolutePath
    }

    override fun pid(): Int = android.os.Process.myPid()

    override fun processName(): String = context.packageName

    override fun loader(): ClassLoader = javaClass.classLoader ?: ClassLoader.getSystemClassLoader()

    override fun globalContext(): Any = context

    /** In root mode this is the app's own files directory; nothing is written to WeChat. */
    override fun filesDir(): String = context.filesDir.absolutePath

    /** Temporary files go to the app cache and are removed right after use. */
    override fun cacheDir(): String = File(context.cacheDir, "wxroot").apply { mkdirs() }.absolutePath

    /** Root mode has no hook channel. */
    override fun hooks(): Hooks? = null

    override fun files(): EngineHost.FileSource = source

    /**
     * Root path of the account-level MMKV store.
     *
     * <p>No staging copy is made. Copying the whole directory with su cost one process per
     * file, around sixty per account, and MMKV probing still failed because the app's copy
     * cannot open files WeChat wrote.
     * 
     * Returning the original path makes probing fail fast and the uin falls back to prefs.
     */
    override fun mmkvOpenRoot(accountDir: String): String = accountDir + "mmkv_private"

    /** Removes temporary files after an export. */
    fun cleanup() {
        runCatching { File(cacheDir()).deleteRecursively() }
    }
}
