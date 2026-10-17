package io.github.ryf5584.wxdbdump.data

import android.content.Context
import io.github.ryf5584.wxdbdump.R
import io.github.ryf5584.wxdbdump.core.Account
import io.github.ryf5584.wxdbdump.core.DbFile
import io.github.ryf5584.wxdbdump.payload.Log
import io.github.ryf5584.wxdbdump.payload.Progress
import io.github.ryf5584.wxdbdump.payload.crypto.KeyDerivation
import io.github.ryf5584.wxdbdump.payload.identity.AccountIdentity
import io.github.ryf5584.wxdbdump.payload.discovery.AccountEnricher
import io.github.ryf5584.wxdbdump.payload.discovery.AccountEnumerator
import io.github.ryf5584.wxdbdump.payload.discovery.DatabaseEnumerator
import io.github.ryf5584.wxdbdump.payload.model.Account as PayloadAccount
import io.github.ryf5584.wxdbdump.payload.model.DbFile as PayloadDbFile
import io.github.ryf5584.wxdbdump.root.RootFileSource
import io.github.ryf5584.wxdbdump.root.RootHost
import io.github.ryf5584.wxdbdump.root.RootShell
import io.github.ryf5584.wxdbdump.root.SqlCipherDecryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Data source for root mode.
 *
 * <p>The flow matches Xposed mode exactly: account discovery, database discovery, purpose
 * text and source detection all reuse one payload implementation. Two things differ:
 * <ol>
 *   <li>files are read through su rather than from inside WeChat;</li>
 *   <li>decryption runs in the app with the key derived offline instead of captured
 *       from a hook.</li>
 * </ol>
 */
class RootDataSource(private val context: Context) : DataSource {

    override val label: String = "Root"

    private val host = RootHost(context)

    override suspend fun listAccounts(onProgress: (Int, Int, String) -> Unit): List<Account> =
        withContext(Dispatchers.IO) {
            RootShell.invalidate()
            Log.i("root", "→ 枚举账号（su 通道）")

            // Shares enumeration and enrichment with Xposed mode: uin, nickname, alias, avatar and
            // database list are identical, and only the key source differs.
            val raw = AccountEnumerator.enumerate(host, Progress { d, t, s -> onProgress(d, t, s) })
            val deviceId = KeyDerivation.deviceId(host)
            val prefs = AccountIdentity.read(host)
            val step = Progress { d, t, s -> onProgress(d, t, s) }

            raw.forEach { a ->
                AccountEnricher.enrichLight(host, a, prefs)
                // Decryptability in root mode means a key can be derived, which needs the device id and uin
                if (!a.decryptable) {
                    val ok = deviceId != null && a.uin > 0
                    a.decryptable = ok
                    a.note = when {
                        ok -> ""
                        deviceId == null -> context.getString(R.string.root_no_device_id)
                        else -> context.getString(R.string.root_no_uin)
                    }
                }
            }

            Log.i("root", "← 共 ${raw.size} 个账号，设备号可用=${deviceId != null}")
            raw.map { toApp(it) }
        }

    override suspend fun listDatabases(
        account: Account,
        onProgress: (Int, Int, String) -> Unit,
    ): List<DbFile> = withContext(Dispatchers.IO) {
        Log.i("root", "→ 枚举数据库 ${account.dirName}")
        val a = PayloadAccount(account.dirName, account.dir)
        a.uin = account.uin
        val list = DatabaseEnumerator.enumerate(host, a, Progress { d, t, s -> onProgress(d, t, s) })

        // Having a key means offline derivation succeeded.
        // Hardcoding false would mark every encrypted database as uncaptured.
        val keyOk = KeyDerivation.deviceId(host) != null && account.uin > 0
        Log.i("root", "← 共 ${list.size} 个库，密钥可推导=$keyOk")
        list.map { toApp(it, keyOk) }
    }

    override suspend fun exportTo(
        db: DbFile,
        account: Account,
        sink: OutputStream,
        onProgress: (Long) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        Log.i("root", "导出 ${db.name} encrypted=${db.encrypted} size=${db.sizeBytes}")

        // Plaintext databases are streamed out directly
        if (!db.encrypted) {
            val n = RootFileSource().copyToStream(db.path, sink)
            return@withContext if (n >= 0) ExportOutcome.Ok
            else ExportOutcome.Failed(context.getString(R.string.export_failed_read))
        }

        // Encrypted databases: derive the key offline, then decrypt locally
        val key = KeyDerivation.key(KeyDerivation.deviceId(host), account.uin)
        if (key == null) {
            return@withContext ExportOutcome.Failed(context.getString(R.string.root_no_key))
        }

        val plain = File(host.cacheDir(), "plain-" + db.name)
        val ok = SqlCipherDecryptor.decryptTo(context, db.path, key, plain)
        if (!ok) {
            // Decryption happens here, in the app. The shared string names the module, which
            // this mode does not use, so it would point the user at a component that is absent.
            return@withContext ExportOutcome.Failed(
                context.getString(R.string.export_failed_decrypt_root),
            )
        }

        try {
            var sent = 0L
            plain.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    onProgress(sent)
                }
            }
            Log.i("root", "${db.name} 明文导出成功，$sent 字节")
            ExportOutcome.Ok
        } finally {
            plain.delete()
        }
    }

    override suspend fun fetchAvatar(account: Account): ByteArray? = withContext(Dispatchers.IO) {
        if (!account.hasAvatar) return@withContext null
        RootFileSource().readAll(findAvatar(account))
    }

    /** Avatar files live under avatar/ in the account directory, sharded by wxid hash. */
    private fun findAvatar(account: Account): String {
        val wxid = account.wxid
        if (wxid.isEmpty()) return ""
        val md5 = io.github.ryf5584.wxdbdump.payload.Digest.md5Hex(wxid)
        return account.dir + "avatar/" + md5.substring(0, 2) + "/" + md5.substring(2, 4) +
            "/user_hd_" + md5 + ".png"
    }

    /** Removes temporary files after an export. */
    fun cleanup() {
        host.cleanup()
        SqlCipherDecryptor.cleanup(context)
    }

    // ---------------------------------------------------------------- Model mapping

    private fun toApp(a: PayloadAccount) = Account(
        dirName = a.dirName,
        dir = a.dir,
        uin = a.uin,
        uinSource = a.uinSource,
        wxid = a.wxid,
        nickname = a.nickname,
        alias = a.alias,
        mobile = a.mobile,
        hasAvatar = a.avatarPath.isNotEmpty(),
        dbCount = a.dbCount,
        totalBytes = a.totalBytes,
        decryptable = a.decryptable,
        note = a.note,
    )

    private fun toApp(d: PayloadDbFile, hasKey: Boolean) = DbFile(
        name = d.name,
        path = d.path,
        sizeBytes = d.sizeBytes,
        encrypted = d.encrypted,
        hasWal = d.hasWal,
        hasShm = d.hasShm,
        hasKey = hasKey,
        nonEssential = d.nonEssential,
        category = d.category?.name ?: "OTHER",
        categoryKey = d.categoryKey ?: "OTHER",
        description = d.description ?: "",
    )
}
