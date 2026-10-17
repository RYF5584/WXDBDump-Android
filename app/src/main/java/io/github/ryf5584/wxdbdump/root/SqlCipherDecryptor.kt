package io.github.ryf5584.wxdbdump.root

import android.content.Context
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.crypto.SqlCipherPages
import java.io.File

/**
 * Root-mode database decryption.
 *
 * <p>Reads the encrypted database with {@code su}, then decrypts it page by page
 * using [SqlCipherPages]. The algorithm itself lives in {@code :payload} so the
 * Xposed module can reuse it as a fallback when WCDB refuses to open a database.
 *
 * <p>Neither SQLCipher nor WCDB is required: WeChat only differs from stock
 * SQLCipher in its page/IV layout, not in the primitives.
 */
object SqlCipherDecryptor {

    private const val TAG = "decrypt"

    /**
     * Copy [srcPath] into a private staging directory and decrypt it into [dst].
     *
     * @param key 7-byte ASCII key derived from the device id and the account uin
     */
    fun decryptTo(context: Context, srcPath: String, key: ByteArray, dst: File): Boolean {
        val staged = stage(context, srcPath) ?: return false
        return SqlCipherPages.decrypt(staged, dst, key)
    }

    /**
     * Copy the database and its -wal/-shm sidecars into the app cache.
     *
     * <p>Streamed, not buffered: EnMicroMsg.db can exceed 400 MB.
     */
    private fun stage(context: Context, srcPath: String): File? {
        val dir = File(context.cacheDir, "wxroot-staging").apply { mkdirs() }
        val dst = File(dir, srcPath.substringAfterLast('/'))
        val src = RootFileSource()

        val copied = runCatching {
            dst.outputStream().use { src.copyToStream(srcPath, it) }
        }.getOrElse { t ->
            Log.w(TAG, "copy failed: $srcPath", t)
            -1L
        }
        if (copied <= 0) {
            Log.w(TAG, "copy failed ($copied bytes): $srcPath")
            return null
        }
        for (suffix in listOf("-wal", "-shm")) {
            if (!RootShell.exists(srcPath + suffix)) continue
            runCatching {
                File(dst.absolutePath + suffix).outputStream()
                    .use { src.copyToStream(srcPath + suffix, it) }
            }
        }
        return dst
    }

    /** Remove staging files; they contain chat data. */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, "wxroot-staging").deleteRecursively() }
    }
}
