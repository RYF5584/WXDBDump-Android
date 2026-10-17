package io.github.ryf5584.wxdbdump.core

import java.util.Locale

/** One exportable WeChat account. */
data class Account(
    val dirName: String,
    val dir: String,
    val uin: Int = 0,
    val uinSource: String = "",
    /** Real wxid when known, otherwise a masked uin. */
    val wxid: String = "",
    /** Nickname from global preferences or the account MMKV store. */
    val nickname: String = "",
    /** User-set alias. */
    val alias: String = "",
    /** Bound phone number. */
    val mobile: String = "",
    /** Whether a local avatar exists; the app fetches it on demand. */
    val hasAvatar: Boolean = false,
    val dbCount: Int = 0,
    val totalBytes: Long = 0L,
    val decryptable: Boolean = false,
    val note: String = "",
) {
    val shortId: String get() = dirName.take(8)

    /**
     * Export subdirectory name.
     *
     * <p>The real wxid is preferred, then the user-set alias, then the account directory name,
     * so every account ends up in a folder that is recognisable at a glance.
     * 
     */
    val folderName: String
        get() {
            val w = wxid
            if (w.startsWith("wxid_") && !w.contains('…') && w.length > 8) return w
            if (alias.isNotBlank()) return alias
            return dirName
        }

    /** Heading: nickname, then alias, then a short directory id. */
    val displayName: String
        get() = nickname.ifBlank { alias.ifBlank { wxid.ifBlank { "Account ${dirName.take(8)}" } } }

    /** Subtitle: confirmed facts only. Nothing is invented. */
    val subtitle: String
        get() = buildList {
            if (nickname.isNotBlank() && alias.isNotBlank()) add("Alias $alias")
            else if (wxid.isNotBlank()) add(wxid)
            if (mobile.isNotBlank()) add(mobile)
            if (uin != 0) add("uin $uin")
        }.joinToString(" · ")

    /** Fallback initial shown when the avatar has not loaded. */
    val initial: String
        get() = when {
            nickname.isNotBlank() -> nickname.take(1)
            alias.isNotBlank() -> alias.take(1)
            else -> dirName.take(1).uppercase(Locale.US)
        }

    /** Avatar colour index, hashed from the directory name so it stays stable. */
    val paletteIndex: Int get() = (dirName.hashCode().let { if (it == Int.MIN_VALUE) 0 else it } % 6 + 6) % 6
}

/** One database file. */
data class DbFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val encrypted: Boolean = false,
    val hasWal: Boolean = false,
    val hasShm: Boolean = false,
    val hasKey: Boolean = false,
    val category: String = "OTHER",
    /** Category key such as CHAT, SNS or FTS, used for labels and colours. */
    val categoryKey: String = "OTHER",
    /** Human-readable purpose of this database. */
    val description: String = "",
    /**
     * Cache / stats / rebuildable-index databases. A failed export of one of these loses
     * no data, so it is reported as skipped rather than failed.
     */
    val nonEssential: Boolean = false,
) {
    val readableSize: String get() = formatSize(sizeBytes)

    /** Search matches against both name and purpose. */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return name.lowercase().contains(q) || description.lowercase().contains(q)
    }
}

/** One file in an export run. */
data class ExportItem(
    val db: DbFile,
    val outputName: String = db.name,
    val status: Status = Status.QUEUED,
    val copiedBytes: Long = 0L,
    val stage: String = "",
    val reason: String = "",
    val verified: Boolean = false,
) {
    enum class Status { QUEUED, SNAPSHOTTING, RUNNING, DONE, SKIPPED, FAILED }

    val progressPercent: Int
        get() = when {
            status == Status.DONE -> 100
            db.sizeBytes <= 0 -> 0
            else -> (copiedBytes * 100 / db.sizeBytes).toInt().coerceIn(0, 100)
        }
}

/** Detection state machine. */
sealed interface DetectionState {
    data object Checking : DetectionState
    data object Available : DetectionState
    data class Unavailable(val reason: String = "") : DetectionState
    data class Error(val message: String) : DetectionState
}

/** Fine-grained reasons for an inactive module, so the user gets a precise next step. */
enum class XposedFailure {
    NONE,
    WECHAT_NOT_INSTALLED,
    WECHAT_NOT_RUNNING,
    CONNECT_FAILED,
    TOKEN_MISMATCH,
    TIMEOUT,
}

/** Extraction mode. Both channels produce identical output; only the key source differs. */
enum class RunMode { ROOT, XPOSED }

/** Environment snapshot; the two channels are detected independently. */
data class EnvironmentSnapshot(
    val wechatInstalled: Boolean = false,
    val wechatVersion: String = "",
    val xposed: DetectionState = DetectionState.Checking,
    val xposedFailure: XposedFailure = XposedFailure.NONE,
    val root: DetectionState = DetectionState.Checking,
) {
    /**
     * Available extraction modes, most recommended first.
     *
     * <p>Root comes first because it needs neither a running WeChat nor a restart.
     */
    val availableModes: List<RunMode>
        get() = buildList {
            if (root is DetectionState.Available) add(RunMode.ROOT)
            if (xposed is DetectionState.Available) add(RunMode.XPOSED)
        }

    /** At least one channel is usable. */
    val ready: Boolean get() = availableModes.isNotEmpty()

    /** Both channels finished detection, so the UI can decide whether to ask the user. */
    val settled: Boolean
        get() = xposed !is DetectionState.Checking && root !is DetectionState.Checking

    /** Picks the only usable channel without asking. */
    val singleMode: RunMode? get() = availableModes.singleOrNull()

    /** Both are usable, so the user chooses. */
    val needsChoice: Boolean get() = availableModes.size > 1
}

/** Enumeration progress, matching the `#P` frames the module pushes. */
data class LoadProgress(
    val done: Int = 0,
    val total: Int = 0,
    val stage: String = "",
) {
    /** 0f to 1f, or null when indeterminate. */
    val fraction: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** Size formatting in B, KB, MB or GB. The only implementation in the app. */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "--"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/** Duration formatting as mm:ss or hh:mm:ss. */
fun formatDuration(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
