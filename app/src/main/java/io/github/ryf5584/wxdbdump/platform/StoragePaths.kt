package io.github.ryf5584.wxdbdump.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.ryf5584.wxdbdump.payload.Log
import java.io.File

/**
 * Resolves and opens the export directory.
 *
 * Layout: files land in `<root>/<accountId>/xxx.db`.
 * The default root is WeChat-DB-Dump under internal storage, and /Download also works.
 */
object StoragePaths {

    const val DEFAULT_DIR_NAME = "WeChat-DB-Dump"

    private const val EXT_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    fun defaultRoot(): String = File(externalRoot(), DEFAULT_DIR_NAME).absolutePath

    /** Normalizes user input, accepting forms like /Download and Download. */
    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return defaultRoot()
        if (t.startsWith("/")) return t.trimEnd('/')
        return File(externalRoot(), t).absolutePath.trimEnd('/')
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            true
        }

    /** Opens system settings so the user can grant All files access. */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            // Some ROMs lack the per-app entry; fall back to the general list
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { e -> Log.w("storage", "cannot open the permission settings page", e) }
        }
    }

    fun fallbackRoot(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Whether writing to this directory needs All files access. */
    fun needsPermission(context: Context, dir: File): Boolean {
        if (hasAllFilesAccess()) return false
        return !dir.absolutePath.startsWith(fallbackRoot(context).absolutePath)
    }

    /**
     * Resolves the root directory that is actually usable.
     * @return the directory and whether it fell back to app-private storage
     */
    fun resolveRoot(context: Context, configured: String): Pair<File, Boolean> {
        val want = File(normalize(configured))
        if (hasAllFilesAccess()) return want to false
        val fb = fallbackRoot(context)
        return if (want.absolutePath.startsWith(fb.absolutePath)) want to false else fb to true
    }

    fun accountDir(root: File, accountKey: String): File = File(root, accountKey.trim('/'))

    // ---------------------------------------------------------------- Open folder

    /**
     * Shows the system chooser so the user picks an app to open the directory with.
     *
     * Several intent shapes that can open a directory are probed and deduplicated by package,
     * because each file manager declares a different one.
     */
    fun openFolder(context: Context, dir: File, title: String) {
        if (!dir.exists()) {
            Toast.makeText(context, dir.absolutePath, Toast.LENGTH_LONG).show()
            return
        }
        val pm = context.packageManager
        val seen = LinkedHashSet<String>()
        val extras = ArrayList<Intent>()
        var base: Intent? = null

        for (probe in probes(context, dir)) {
            val targets = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
            for (ri in targets) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) continue
                if (!seen.add(pkg)) continue
                val i = Intent(probe).apply { setClassName(pkg, ri.activityInfo.name) }
                if (base == null) base = i else extras.add(i)
            }
        }

        if (base == null) {
            Log.w("storage", "no app can open this directory")
            Toast.makeText(context, dir.absolutePath, Toast.LENGTH_LONG).show()
            return
        }
        Log.i("storage", "apps that can open the directory: $seen")

        val chooser = Intent.createChooser(base, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (extras.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
            }
        }
        runCatching { context.startActivity(chooser) }.onFailure { e ->
            Log.w("storage", "failed to launch the chooser", e)
            Toast.makeText(context, dir.absolutePath, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Candidate intents, in chooser priority order.
     *
     * Two things to note:
     * 1. Do not add read-permission flags to a system document provider URI: the grant is
     *    not held, so the system rejects it. DocumentsUI has its own MANAGE_DOCUMENTS
     *    permission and navigates into the directory regardless.
     * 2. A directory MIME alone misses apps that only declare a wildcard MIME, so the same
     *    URI is offered again with a wildcard.
     */
    private fun probes(context: Context, dir: File): List<Intent> {
        val out = ArrayList<Intent>(4)
        val dirMime = DocumentsContract.Document.MIME_TYPE_DIR

        docUri(dir)?.let { u ->
            out += Intent(Intent.ACTION_VIEW).setDataAndType(u, dirMime)
            out += Intent(Intent.ACTION_VIEW).setDataAndType(u, "*/*")
        }
        providerUri(context, dir)?.let { u ->
            // Our own FileProvider handles paths outside internal storage and needs the grant flags
            out += Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, dirMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return out
    }

    /** Directory URI from the external storage document provider, recognised by common file managers. */
    private fun docUri(dir: File): Uri? {
        val base = externalRoot().absolutePath
        if (!dir.absolutePath.startsWith(base)) return null
        val rel = dir.absolutePath.removePrefix(base).trimStart('/')
        return DocumentsContract.buildDocumentUri(EXT_STORAGE_AUTHORITY, "primary:$rel")
    }

    private fun providerUri(context: Context, dir: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
    }.getOrNull()
}
