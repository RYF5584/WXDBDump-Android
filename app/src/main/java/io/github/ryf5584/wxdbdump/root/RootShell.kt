package io.github.ryf5584.wxdbdump.root

import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.paths.WeChatPaths
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Root channel.
 *
 * <h3>Why no persistent su session</h3>
 * For a while this tried to keep one long-lived `su ... -c sh` around, because forking a
 * new process per command costs 50 to 200 ms and enumerating one account reads hundreds
 * of directories. That approach deadlocks: with stdout piped, libc buffers 4 KB at a
 * time, so echo markers never arrive and the reader waits forever. One-shot processes
 * combined with batching and caching are used instead.
 *
 * <p>Large files also use one-shot processes: pushing hundreds of megabytes through a
 * shell text channel would inflate them by a third and buffer everything in memory.
 *
 * <h3>Why -M matters</h3>
 * Android gives every app its own mount namespace, and WeChat's data directory is masked
 * inside it. A naive su call reports No such file or directory rather than a permission
 * error, so the flag switches to the global namespace.
 * [available] tries four variants and judges them by whether WeChat's data directory is
 * visible, since every variant reports uid=0.
 *
 * <h3>Contract</h3>
 * Failures always return null, false or an empty collection, never an exception.
 */
object RootShell {

    private const val TAG = "root"
    private const val TIMEOUT_SEC = 30L

    /**
     * How su is invoked. Capabilities differ between implementations, so each is tried:
     * KernelSU and Magisk support -M, older Magisk and SuperSU only -c, and some require an
     * explicit target uid.
     */
    enum class SuMode { MOUNT_MASTER, MOUNT_MASTER_LONG, PLAIN, UID0 }

    /** Channel state. */
    enum class State { UNKNOWN, OK, NO_ROOT, BLOCKED }

    /** True when the su binary is not visible at all, i.e. root was never granted. */
    @Volatile var suHidden = false
        private set

    @Volatile private var state: State = State.UNKNOWN
    @Volatile private var mode: SuMode? = null

    private val dirCache = HashMap<String, Map<String, Entry>>()

    data class Entry(val name: String, val size: Long, val isDir: Boolean)

    fun state(): State = state

    /**
     * Script directory, injected by [RootHost] on construction.
     *
     * <p>Script files rather than su -c with a multi-line argument: combining quotes and
     * command substitution into one argument parses differently across su implementations,
     * whereas the same logic in a file runs identically everywhere.
     * Logic therefore always lands in a .sh file run as `sh <path> <args>`.
     */
    @Volatile var scriptDir: String? = null

    /**
     * Writes [content] to `<scriptDir>/<name>.sh` and runs it.
     *
     * <p>Arguments are passed positionally, never interpolated into the command.
     */
    fun execScript(name: String, content: String, vararg args: String): String? {
        val dir = scriptDir ?: return null
        val m = mode ?: return null
        return runCatching {
            val f = java.io.File(dir, "$name.sh")
            // Rewrite only on change, to avoid redundant IO
            if (!f.exists() || f.length() != content.toByteArray().size.toLong()) {
                f.writeText(content)
            }
            val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            oneShot(m, "sh '${f.absolutePath}' $quoted")
        }.getOrElse { t ->
            Log.w(TAG, "script failed: $name", t)
            null
        }
    }

    /** Builds the full argv for a su variant. */
    private fun argvFor(m: SuMode, cmd: String): List<String> = when (m) {
        SuMode.MOUNT_MASTER -> listOf("su", "-M", "-c", cmd)
        SuMode.MOUNT_MASTER_LONG -> listOf("su", "--mount-master", "-c", cmd)
        SuMode.PLAIN -> listOf("su", "-c", cmd)
        SuMode.UID0 -> listOf("su", "0", "-c", cmd)
    }

    // ------------------------------------------------------------------ Execution

    /**
     * Never use a persistent su session.
     *
     * It looks attractive because it avoids a fork per command, but it deadlocks: with a
     * piped stdout, libc buffers 4 KB instead of line buffering, so echo markers stay in the
     * buffer and the reader waits forever.
     * Working around it needs a PTY, which is not worth the cost. One-shot processes plus
     * batching and caching keep the process count down instead.
     */
    private fun oneShot(m: SuMode, cmd: String): String? = runCatching {
        val p = ProcessBuilder(argvFor(m, cmd)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "command timed out, killed: $cmd")
            return null
        }
        if (p.exitValue() != 0) {
            Log.d(TAG, "exit ${p.exitValue()}: $cmd")
            return null
        }
        out
    }.getOrElse { t ->
        Log.w(TAG, "command failed: $cmd", t)
        null
    }

    /** Text command. */
    fun exec(cmd: String): String? {
        val m = mode ?: return null
        return oneShot(m, cmd)
    }

    /** Contents of a small file. Use [streamTo] for large ones. */
    fun execBytes(cmd: String, limit: Long = 4L * 1024 * 1024): ByteArray? {
        val m = mode ?: return null
        return runCatching {
            val p = ProcessBuilder(argvFor(m, "$cmd 2>/dev/null")).redirectErrorStream(false).start()
            val bytes = p.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (out.size() < limit) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            bytes
        }.getOrElse { t ->
            Log.w(TAG, "failed to read bytes: $cmd", t)
            null
        }
    }

    // ------------------------------------------------------------------ Probing

    /**
     * Probes root. Only success is cached, because grants are dynamic: a refusal followed by
     * a later grant in the manager must be picked up.
     */
    fun available(): Boolean {
        if (state == State.OK && mode != null) return true

        // 1. Is su present? One-shot processes per variant, so a hung session cannot block
        val hasRoot = SuMode.entries.any { oneShot(it, "id")?.contains("uid=0") == true }
        if (!hasRoot) {
            state = State.NO_ROOT
            mode = null
                        Log.w(TAG, "no root this round; the result is not cached and will be retried")
            return false
        }

        // 2. Pick a variant that can actually see WeChat's directory
        mode = SuMode.entries.firstOrNull {
            oneShot(it, "test -d '${WeChatPaths.DATA_ROOT}' && echo y")?.trim() == "y"
        }
        state = if (mode != null) State.OK else State.BLOCKED
        when (state) {
            State.OK -> Log.i(TAG, "root available (su variant=$mode)")
            State.BLOCKED -> Log.w(
                TAG,
                "root works but WeChat's directory ${WeChatPaths.DATA_ROOT} is not visible; " +
                    "check the KernelSU or Magisk scope settings",
            )
            else -> Unit
        }
        return state == State.OK
    }

    /** Forces a re-probe, used by settings and when the app returns to the foreground. */
    fun refresh() {
        suHidden = false
        state = State.UNKNOWN
        mode = null
        invalidate()
    }

    // ------------------------------------------------------------------ Execution

    /**
     * Streams a file into [out] for large databases. Returns the byte count, or -1.
     *
     * <p>stderr must be captured separately: it is a binary stream and any error text mixed
     * into it corrupts the data.
     */
    fun streamTo(cmd: String, out: OutputStream): Long {
        val m = mode ?: return -1L
        return runCatching {
            val p = ProcessBuilder(argvFor(m, cmd)).redirectErrorStream(false).start()
            Thread { runCatching { p.errorStream.readBytes() } }.apply { isDaemon = true; start() }
            var total = 0L
            p.inputStream.use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                return -1L
            }
            if (p.exitValue() != 0) return -1L
            total
        }.getOrElse { t ->
            Log.w(TAG, "stream read failed: $cmd", t)
            -1L
        }
    }

    // ------------------------------------------------------------------ Directories

    fun invalidate() {
        dirCache.clear()
        headCache.clear()
    }

    /**
     * Lists a directory with name, size and type, and caches the result.
     *
     * <p>stat rather than ls -l: the latter varies by platform and locale, and mishandles
     *
     * <p>The trailing `|| true` matters: a glob for hidden files does not expand in a
     * directory without any, so stat fails with a non-zero exit code while stdout is still
     * correct.
     *
     * @return null when the read failed and must not be cached; an empty map means empty
     */
    fun statDir(dir: String): Map<String, Entry>? {
        dirCache[dir]?.let { return it }
        val out = exec("stat -c '%s|%F|%n' '$dir'/* '$dir'/.[!.]* 2>/dev/null || true") ?: return null
        val map = LinkedHashMap<String, Entry>()
        for (line in out.lineSequence()) {
            val parts = line.split('|')
            if (parts.size < 3) continue
            val size = parts[0].toLongOrNull() ?: continue
            val type = parts[1]
            val full = parts.subList(2, parts.size).joinToString("|")
            val name = full.substringAfterLast('/')
            if (name.isEmpty() || name == "." || name == "..") continue
            map[name] = Entry(name, size, type.contains("directory"))
        }
        dirCache[dir] = map
        return map
    }

    /** Cache of 16-byte headers, keyed by directory then file name. */
    private val headCache = HashMap<String, Map<String, ByteArray>>()

    /**
     * Fetches the first 16 bytes of every file in one directory with a single call.
     *
     * <p>Encryption detection needs one header per database and an account holds dozens, so
     * one command fetches them all and later reads hit the cache.
     */
    private fun heads(dir: String): Map<String, ByteArray> {
        headCache[dir]?.let { return it }
        val out = exec(
            "for f in '$dir'/*; do [ -f \"\$f\" ] || continue; " +
                "printf '%s|' \"\${f##*/}\"; head -c 16 \"\$f\" 2>/dev/null | base64 | tr -d '\\n'; echo; done",
        ) ?: return emptyMap()
        val map = HashMap<String, ByteArray>()
        for (line in out.lineSequence()) {
            val i = line.indexOf('|')
            if (i <= 0) continue
            val name = line.substring(0, i)
            val b64 = line.substring(i + 1).trim()
            if (b64.isEmpty()) continue
            runCatching { map[name] = android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
        }
        headCache[dir] = map
        return map
    }

    /**
     * Reads the first n bytes, served from the batch cache for n up to 16.
     */
    fun readHead(path: String, n: Int): ByteArray? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        if (n <= 16) {
            heads(parent)[name]?.let { return it }
        }
        return execBytes("head -c $n '$path'", n.toLong())
    }

    /**
     * Whether a path exists.
     *
     * <p>The parent directory cache answers first. Without it, enumeration would run one
     * command per database and per sidecar file. The cached listing is authoritative because
     * it is built from globs covering both regular and hidden files.
     */
    fun exists(path: String): Boolean {
        val p = path.trimEnd('/')
        val parent = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        if (parent.isEmpty() || name.isEmpty()) return false
        statDir(parent)?.let { return it.containsKey(name) }
        return exec("[ -e '$p' ] && echo y")?.trim() == "y"
    }

    /** File size, or -1 when absent. */
    fun size(path: String): Long {
        val p = path.trimEnd('/')
        val parent = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        if (parent.isEmpty() || name.isEmpty()) return -1L
        statDir(parent)?.let { return it[name]?.size ?: -1L }
        return exec("stat -c '%s' '$p' 2>/dev/null")?.trim()?.toLongOrNull() ?: -1L
    }

    /** Recursively lists the absolute paths of all regular files. */
    fun walk(root: String): List<String> =
        exec("find '$root' -type f 2>/dev/null")
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList()
            ?: emptyList()
}
