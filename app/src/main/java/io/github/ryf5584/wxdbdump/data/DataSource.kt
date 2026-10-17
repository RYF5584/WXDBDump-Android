package io.github.ryf5584.wxdbdump.data

import android.content.Context
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.Account
import io.github.ryf5584.wxdbdump.core.DbFile
import io.github.ryf5584.wxdbdump.payload.Log
import java.io.OutputStream

/** Export outcome. */
sealed interface ExportOutcome {
    data object Ok : ExportOutcome

    data class Skipped(val reason: String) : ExportOutcome

    data class Failed(val reason: String) : ExportOutcome
}

/**
 * Data source abstraction with one implementation per channel.
 *
 * <p>Both expose the same capabilities, so the ViewModel, the UI and the export pipeline
 * never branch on the mode.
 */
interface DataSource {
    /**
     * Cancels the transfer currently in flight, if the source has one.
     *
     * <p>Used as a watchdog: a blocking socket read ignores coroutine cancellation, so the
     * caller has to ask the source to tear the connection down.
     */
    fun abort() {}

    val label: String

    suspend fun listAccounts(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): List<Account>

    suspend fun listDatabases(
        account: Account,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<DbFile>

    /** Writes the plaintext of [db] into [sink]. */
    suspend fun exportTo(
        db: DbFile,
        account: Account,
        sink: OutputStream,
        onProgress: (Long) -> Unit,
    ): ExportOutcome

    /** Avatar PNG, which lives in WeChat's private directory and must be pulled out. */
    suspend fun fetchAvatar(account: Account): ByteArray?
}

/**
 * Data source for Xposed mode.
 *
 * <p>Decryption is left entirely to WeChat: the module uses its SQLCipher with the real
 * cipher spec object, so the app needs no parameters and a correct key always opens.
 * Ciphertext and plaintext share one stream protocol and differ only by command.
 */
class XposedDataSource(
    private val context: Context,
    private val client: LoopbackClient,
) : DataSource {

    /** Tears down the in-flight loopback connection so a stalled read fails at once. */
    override fun abort() = client.abortActive()


    override val label: String = "Xposed"

    override suspend fun listAccounts(onProgress: (Int, Int, String) -> Unit): List<Account> {
        Log.i("source", "→ ACCOUNTS")
        val list = client.listAccounts(onProgress)
        Log.i("source", "← ACCOUNTS 共 ${list.size} 个账号")
        return list
    }

    override suspend fun listDatabases(
        account: Account,
        onProgress: (Int, Int, String) -> Unit,
    ): List<DbFile> {
        Log.i("source", "→ DBS ${account.dirName}")
        val list = client.listDatabases(account.dirName, onProgress)
        Log.i("source", "← DBS 共 ${list.size} 个库")
        return list
    }

    override suspend fun exportTo(
        db: DbFile,
        account: Account,
        sink: OutputStream,
        onProgress: (Long) -> Unit,
    ): ExportOutcome {
        Log.i("source", "[xposed] 导出 ${db.name} encrypted=${db.encrypted} size=${db.sizeBytes}")

        // Plaintext databases are pulled verbatim
        if (!db.encrypted) {
            return if (client.fetchTo(db.path, sink, onProgress)) ExportOutcome.Ok
            else ExportOutcome.Failed(context.getString(R.string.export_failed_read))
        }

        // Encrypted databases are decrypted by the module and streamed back as plaintext
        if (client.dumpTo(db.path, sink, onProgress)) {
            Log.i("source", "[xposed] ${db.name} 明文导出成功")
            return ExportOutcome.Ok
        }
        return ExportOutcome.Failed(context.getString(R.string.export_failed_decrypt))
    }

    /** Pulls an account avatar PNG, which the app cannot read directly. */
    override suspend fun fetchAvatar(account: Account): ByteArray? {
        if (!account.hasAvatar) return null
        Log.i("source", "→ AVATAR ${account.dirName}")
        val bytes = client.fetchBytes("AVATAR", account.dirName)
        if (bytes == null) {
            Log.w("source", "← AVATAR 失败 ${account.dirName}")
            return null
        }
        Log.i("source", "← AVATAR ${bytes.size} 字节")
        return bytes
    }
}
