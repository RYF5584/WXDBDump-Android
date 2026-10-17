package io.github.ryf5584.wxdbdump.root

import android.util.Base64
import io.github.ryf5584.wxdbdump.payload.EngineHost
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.discovery.DbScan
import io.github.ryf5584.wxdbdump.payload.crypto.SqlCipherHeader
import java.io.File
import java.io.OutputStream

/**
 * Root implementation of {@code EngineHost.FileSource}: reads WeChat's private directory
 *
 * <p>The only difference from the Xposed version is how files are read; enumeration,
 * schema handling and source detection all reuse one payload implementation.
 */
class RootFileSource : EngineHost.FileSource {

    override fun exists(path: String): Boolean = RootShell.exists(path)

    override fun list(dir: String): Array<String> =
        RootShell.statDir(dir)?.keys?.toTypedArray() ?: emptyArray()

    override fun size(path: String): Long = RootShell.size(path)

    /** Uses the batch cache: one command fetches the headers of a whole directory. */
    override fun readHead(path: String, n: Int): ByteArray? = RootShell.readHead(path, n)

    override fun readAll(path: String): ByteArray? =
        RootShell.execBytes("cat '$path'")

    override fun copyTo(src: String, dst: String): Boolean {
        val bytes = RootShell.execBytes("cat '$src'") ?: return false
        return runCatching {
            File(dst).parentFile?.mkdirs()
            File(dst).outputStream().use { it.write(bytes) }
            true
        }.getOrElse { false }
    }

    /** Streams a copy for large databases, avoiding a full in-memory buffer. */
    fun copyToStream(src: String, out: OutputStream): Long =
        RootShell.streamTo("cat '$src'", out)

    /**
     * Scan script.
     *
     * The `root` placeholder is substituted before execution; see SCAN_SCRIPT.
     */
    private companion object {

        /**
         * Scans the whole tree in one go. Positional arguments: account directory and max depth.
         *
         * Three output sections: size lines, sidecar lines, and a final base64 blob holding every
         * file header in the multi-file head format.
         */
        val SCAN_SCRIPT = """
#!/system/bin/sh
# positional: $1 account directory, $2 max depth
ROOT="${'$'}1"
DEPTH="${'$'}{2:-3}"
cd "${'$'}ROOT" 2>/dev/null || exit 0
files=${'$'}(find . -maxdepth "${'$'}DEPTH" -type f -name '*.db' 2>/dev/null)
[ -n "${'$'}files" ] || exit 0
echo "${'$'}files" | tr '\n' '\0' | xargs -0 stat -c 'S|%s|%n' 2>/dev/null
find . -maxdepth "${'$'}DEPTH" -type f \( -name '*.db-wal' -o -name '*.db-shm' -o -name '*.db-journal' \) 2>/dev/null | sed 's|^|A|'
echo "${'$'}files" | tr '\n' '\0' | xargs -0 head -c 16 2>/dev/null | base64 | tr -d '\n'
echo
exit 0
""".trimIndent()
    }

    /**
     * Enumerates every database in the tree with a single call.
     *
     * <h3>Why this shape</h3>
     * One su process costs tens to hundreds of milliseconds, and an account holds dozens of
     * directories reaching into the thousands when walked three levels deep. Spawning a
     * process per directory or file takes minutes and looks like a hang.
     *
     * <h3>How it fits in three processes</h3>
     * Measured on an account with 47 databases:
     * <pre>
     *   stat per file          about 700 ms
     *   head and base64 per file   about 1365 ms, the dominant cost
     *   stat through xargs, all sizes at once   82 ms
     *   multi-file head, all headers at once with separators   one process
     * </pre>
     * So find plus stat plus head, one process each, lands around 0.3 seconds.
     *
     * <p>Three output sections:
     * <pre>
     *   size lines, one per database
     *   sidecar lines for wal, shm and journal files
     *   a base64 blob holding every file header
     * </pre>
     */
    override fun scanDatabases(root: String, maxDepth: Int): List<DbScan>? {
        // Without a confirmed su variant there is no fast path; the caller falls back to a per-directory scan which logs failures explicitly
        if (RootShell.state() != RootShell.State.OK) {
            Log.w("root", "fast path skipped, su state=${RootShell.state()}")
            return null
        }

        val started = System.currentTimeMillis()
        // Run from a script file rather than su -c; see RootShell.execScript
        val script = SCAN_SCRIPT
        val out = RootShell.execScript(
            "scan",
            script,
            root.trimEnd('/'),
            maxDepth.toString(),
        )
        if (out == null) {
            Log.w("root", "single-pass scan failed, falling back to per-directory scan (root=$root)")
            return null
        }

        val sizes = HashMap<String, Long>()
        val aux = HashSet<String>()
        var headBlobB64 = ""

        for (line in out.lineSequence()) {
            when {
                line.startsWith("S|") -> {
                    val parts = line.split('|')
                    if (parts.size >= 3) {
                        val sz = parts[1].toLongOrNull() ?: continue
                        sizes[parts.subList(2, parts.size).joinToString("|")] = sz
                    }
                }
                line.startsWith("A|") -> aux.add(line.substring(2))
                line.length > 200 -> headBlobB64 = line
            }
        }
        if (sizes.isEmpty()) return emptyList()

        val heads = parseHeads(headBlobB64)
        val base = if (root.endsWith("/")) root else "$root/"

        val list = ArrayList<DbScan>(sizes.size)
        for ((rel0, size) in sizes) {
            val rel = rel0.removePrefix("./")
            val head = heads[rel0] ?: heads["./$rel"] ?: heads[rel]
            list.add(
                DbScan(
                    base + rel,
                    rel.substringAfterLast('/'),
                    size,
                    aux.contains(rel0 + "-wal"),
                    aux.contains(rel0 + "-shm"),
                    aux.contains(rel0 + "-journal"),
                    SqlCipherHeader.isEncrypted(head),
                ),
            )
        }
        Log.i(
            "root",
            "single scan done: ${list.size} databases in ${System.currentTimeMillis() - started}ms (3 su calls)",
        )
        return list
    }

    /**
     * Parses the base64 output of a multi-file {@code head -c 16} call.
     *
     * <p>toybox prefixes each section with a `==> path <==` line followed by the 16 bytes and
     * a blank line, so splitting on the markers is enough.
     */
    private fun parseHeads(b64: String): Map<String, ByteArray> {
        if (b64.isEmpty()) return emptyMap()
        val blob = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, ByteArray>()

        var i = 0
        while (i + 4 < blob.size) {
            if (!(blob[i] == '='.code.toByte() && blob[i + 1] == '='.code.toByte() &&
                    blob[i + 2] == '>'.code.toByte() && blob[i + 3] == ' '.code.toByte())
            ) {
                i++
                continue
            }
            val nameStart = i + 4
            var j = nameStart
            while (j + 3 < blob.size &&
                !(blob[j] == ' '.code.toByte() && blob[j + 1] == '<'.code.toByte() &&
                    blob[j + 2] == '='.code.toByte() && blob[j + 3] == '='.code.toByte())
            ) {
                j++
            }
            if (j + 3 >= blob.size) break
            val name = String(blob, nameStart, j - nameStart, Charsets.UTF_8)
            var p = j + 4
            if (p < blob.size && blob[p] == '\n'.code.toByte()) p++
            val head = blob.copyOfRange(p, minOf(p + 16, blob.size))
            out[name] = head
            i = minOf(p + 16, blob.size)
        }
        return out
    }

    override fun walk(root: String): Array<String> = RootShell.walk(root).toTypedArray()
}
