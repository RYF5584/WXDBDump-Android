package io.github.ryf5584.wxdbdump.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ryf5584.wxdbdump.core.Account
import io.github.ryf5584.wxdbdump.core.DbFile
import io.github.ryf5584.wxdbdump.core.DetectionState
import io.github.ryf5584.wxdbdump.core.EnvironmentSnapshot
import io.github.ryf5584.wxdbdump.core.RunMode
import io.github.ryf5584.wxdbdump.root.RootShell
import io.github.ryf5584.wxdbdump.core.ExportItem
import io.github.ryf5584.wxdbdump.core.LoadProgress
import io.github.ryf5584.wxdbdump.core.formatSize
import io.github.ryf5584.wxdbdump.data.AppSettings
import io.github.ryf5584.wxdbdump.data.DataSource
import io.github.ryf5584.wxdbdump.data.ExportOutcome
import io.github.ryf5584.wxdbdump.platform.StoragePaths
import io.github.ryf5584.wxdbdump.data.ThemeChoice
import io.github.ryf5584.wxdbdump.di.AppContainer
import io.github.ryf5584.wxdbdump.payload.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One ViewModel holds the state of the whole flow.
 *
 * <p>Kept as a single VM on purpose: account, database and export form one linear flow
 * whose state is coupled, so splitting it would add cross-screen synchronisation.
 * Both channels look identical from above, so [mode] is the only extra dimension.
 */
private const val EXPORT_ITEM_TIMEOUT_MS = 3 * 60 * 1000L

class AppViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * Extraction mode currently in use.
     *
     * <p>Null until the user picks one. When only one channel is available it is chosen
     * automatically by [autoSelectIfSingle].
     */
    var mode by mutableStateOf<RunMode?>(null)
        private set

    /** Current source. Without a mode it defaults to Xposed for label display only. */
    private fun source(): DataSource = container.sourceOf(mode ?: RunMode.XPOSED)

    /** Channel name, for logs and diagnostics. */
    val modeLabel: String get() = mode?.let { source().label } ?: "not selected"

    /** A channel was selected. Re-selecting the same mode does not reload. */
    fun chooseMode(m: RunMode) {
        if (mode == m) return
        mode = m
        Log.i("vm", "mode selected: $m (${container.sourceOf(m).label})")
        accounts = emptyList()
        databases = emptyList()
        selectedAccount = null
        selectedPaths = emptySet()
        loadAccounts()
    }

    /**
     * Picks the only available channel automatically. Returns whether it did.
     *
     * <p>Both channels must have finished detection first. Otherwise a single channel that
     * resolves early would be mistaken for the only option and the picker would be skipped.
     */
    fun autoSelectIfSingle(): Boolean {
        if (!env.settled) return false
        val single = env.singleMode ?: return false
        chooseMode(single)
        return true
    }

    /**
     * Re-probes root when the app returns to the foreground.
     *
     * <p>Root grants are dynamic: a first refusal, a later grant in the manager or a late
     * dialog all have to be picked up, so failures are never cached and su is re-run.
     * Once confirmed available it is not probed again.
     */
    fun refreshRootIfNeeded() {
        if (env.root is DetectionState.Available) return
        viewModelScope.launch {
            RootShell.refresh()
            if (!RootShell.available()) return@launch
            Log.i("vm", "root grant detected after returning to the foreground")
            env = env.copy(root = DetectionState.Available)
            autoSelectIfSingle()
        }
    }

    /** Returns to the channel picker, used by the switch entries in settings and accounts. */
    fun clearMode() {
        mode = null
    }

    // ---------------- Environment detection ----------------

    var env by mutableStateOf(EnvironmentSnapshot())
        private set

    var detecting by mutableStateOf(false)
        private set

    var detectElapsedMs by mutableStateOf(0L)
        private set

    private var detectJob: Job? = null

    fun detect() {
        if (detecting) return
        detectJob?.cancel()
        detectJob = viewModelScope.launch {
            detecting = true
            env = EnvironmentSnapshot()
            detectElapsedMs = 0
            val started = System.currentTimeMillis()
            val ticker = launch {
                while (true) {
                    detectElapsedMs = System.currentTimeMillis() - started
                    kotlinx.coroutines.delay(100)
                }
            }
            val snap = runCatching { container.detect() }.getOrElse { t ->
                Log.e("vm", "detect() threw", t)
                EnvironmentSnapshot(xposed = DetectionState.Error(t.message ?: "detect failed"))
            }
            // Keep the checking state visible briefly to avoid flicker
            val spent = System.currentTimeMillis() - started
            if (spent < 420) kotlinx.coroutines.delay(420 - spent)
            ticker.cancel()
            env = snap
            detectElapsedMs = System.currentTimeMillis() - started
            detecting = false
            // Account loading waits for the channel choice; both channels read the same data, so
            // asking the user to confirm a single option adds nothing.
            autoSelectIfSingle()
        }
    }

    // ---------------- Accounts ----------------

    var accounts by mutableStateOf<List<Account>>(emptyList())
        private set
    var accountsLoading by mutableStateOf(false)
        private set
    var accountsError by mutableStateOf<String?>(null)
        private set
    var accountQuery by mutableStateOf("")

    /** Live loading progress for the account list, pushed as `#P` frames. */
    var accountsProgress by mutableStateOf(LoadProgress())
        private set

    val filteredAccounts: List<Account>
        get() = if (accountQuery.isBlank()) {
            accounts
        } else {
            accounts.filter {
                it.dirName.contains(accountQuery, true) ||
                    it.wxid.contains(accountQuery, true) ||
                    it.alias.contains(accountQuery, true) ||
                    it.nickname.contains(accountQuery, true)
            }
        }

    fun loadAccounts() {
        if (accountsLoading) return
        viewModelScope.launch {
            accountsLoading = true
            accountsError = null
            accountsProgress = LoadProgress(stage = "connecting to WeChat")
            Log.i("vm", "loadAccounts start (source=${source().label})")
            val t0 = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.IO) {
                    source().listAccounts { done, total, stage ->
                        accountsProgress = LoadProgress(done, total, stage)
                    }
                }
            }
                .onSuccess {
                    accounts = it
                    accountsProgress = LoadProgress(it.size, it.size, "loaded ${it.size} accounts")
                    Log.i("vm", "loadAccounts ok: ${it.size} accounts in ${System.currentTimeMillis() - t0}ms")
                    loadAvatars(it)
                }
                .onFailure {
                    accountsError = it.message ?: it.toString()
                    accountsProgress = LoadProgress()
                    Log.e("vm", "loadAccounts failed after ${System.currentTimeMillis() - t0}ms", it)
                }
            accountsLoading = false
        }
    }

    // ---------------- Avatars ----------------

    /** Account directory name to on-disk avatar file. Version drives recomposition. */
    private val avatarFiles = HashMap<String, File>()
    var avatarVersion by mutableStateOf(0)
        private set

    fun avatarFile(account: Account): File? = avatarFiles[account.dirName]

    private fun loadAvatars(list: List<Account>) {
        viewModelScope.launch {
            val dir = File(container.context.cacheDir, "avatar").apply { mkdirs() }
            for (a in list) {
                if (!a.hasAvatar) continue
                val f = File(dir, "${a.dirName}.png")
                if (f.exists() && f.length() > 0) {
                    avatarFiles[a.dirName] = f
                    avatarVersion++
                    continue
                }
                val bytes = runCatching { withContext(Dispatchers.IO) { source().fetchAvatar(a) } }
                    .getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    runCatching { f.writeBytes(bytes) }
                        .onSuccess {
                            avatarFiles[a.dirName] = f
                            avatarVersion++
                            Log.i("vm", "avatar cached: ${a.dirName} ${bytes.size} bytes")
                        }
                        .onFailure { Log.w("vm", "failed to write avatar ${a.dirName}", it) }
                }
            }
        }
    }

    // ---------------- Databases ----------------

    var selectedAccount by mutableStateOf<Account?>(null)
        private set
    var databases by mutableStateOf<List<DbFile>>(emptyList())
        private set
    var dbsLoading by mutableStateOf(false)
        private set
    var dbsError by mutableStateOf<String?>(null)
        private set
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Default sort is by size, largest first. */
    var sortBySize by mutableStateOf(true)
        private set

    var dbsProgress by mutableStateOf(LoadProgress())
        private set

    /** Search query matching both name and purpose text. */
    var dbQuery by mutableStateOf("")

    /** Databases after search and sorting. */
    val sortedDatabases: List<DbFile>
        get() {
            val base = databases.filter { it.matches(dbQuery) }
            return if (sortBySize) base.sortedByDescending { it.sizeBytes }
            else base.sortedBy { it.name }
        }

    val dbMatchCount: Int get() = sortedDatabases.size

    val selectedDatabases: List<DbFile>
        get() = databases.filter { it.path in selectedPaths }

    val selectedBytes: Long
        get() = selectedDatabases.sumOf { it.sizeBytes }

    fun openAccount(a: Account) {
        selectedAccount = a
        selectedPaths = emptySet()
        dbQuery = ""
        viewModelScope.launch {
            dbsLoading = true
            dbsError = null
            dbsProgress = LoadProgress(stage = "reading database list")
            Log.i("vm", "openAccount ${a.dirName} (uin=${a.uin} source=${a.uinSource})")
            val t0 = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.IO) {
                    source().listDatabases(a) { done, total, stage ->
                        dbsProgress = LoadProgress(done, total, stage)
                    }
                }
            }
                .onSuccess {
                    databases = it
                    dbsProgress = LoadProgress(it.size, it.size, "${it.size} databases")
                    Log.i("vm", "openAccount ${a.dirName} ok: ${it.size} databases in ${System.currentTimeMillis() - t0}ms")
                }
                .onFailure {
                    dbsError = it.message ?: it.toString()
                    dbsProgress = LoadProgress()
                    Log.e("vm", "openAccount ${a.dirName} failed after ${System.currentTimeMillis() - t0}ms", it)
                }
            dbsLoading = false
        }
    }

    fun refreshDatabases() {
        selectedAccount?.let { openAccount(it) }
    }

    fun togglePath(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun selectAll() {
        // Select-all while searching only takes the matching entries
        selectedPaths = sortedDatabases.map { it.path }.toSet()
    }

    fun clearSelection() {
        selectedPaths = emptySet()
    }

    fun toggleSort() {
        sortBySize = !sortBySize
    }

    // ---------------- Export directory ----------------

    /** Effective export root, which may be the fallback directory. */
    fun resolvedRoot(): Pair<File, Boolean> =
        StoragePaths.resolveRoot(container.context, settings.value.effectiveRoot)

    /** Export directory for the current account: `<root>/<accountId>/`. */
    fun exportDir(): File {
        val (root, _) = resolvedRoot()
        val key = selectedAccount?.folderName ?: "unknown"
        return StoragePaths.accountDir(root, key)
    }

    /** Absolute export path, for display. */
    fun exportDirLabel(): String = exportDir().absolutePath

    fun hasAllFilesAccess(): Boolean = StoragePaths.hasAllFilesAccess()

    fun requestAllFilesAccess() = StoragePaths.requestAllFilesAccess(container.context)

    fun openFolder(dir: File) = StoragePaths.openFolder(
        container.context,
        dir,
        container.context.getString(io.github.ryf5584.wxdbdump.R.string.open_folder_title),
    )

    // ---------------- Export ----------------

    var exportItems by mutableStateOf<List<ExportItem>>(emptyList())
        private set
    var exporting by mutableStateOf(false)
        private set
    var exportStartedAt by mutableStateOf(0L)
        private set
    var exportFinishedAt by mutableStateOf(0L)
        private set
    var showSummary by mutableStateOf(false)

    /** Directory of the last finished export, used by the open-folder button. */
    var lastExportDir by mutableStateOf<File?>(null)
        private set

    private var exportJob: Job? = null

    val exportDone: Int get() = exportItems.count { it.status == ExportItem.Status.DONE }
    val exportFailed: Int get() = exportItems.count { it.status == ExportItem.Status.FAILED }
    val exportPending: Int get() = exportItems.count {
        it.status == ExportItem.Status.QUEUED || it.status == ExportItem.Status.RUNNING ||
            it.status == ExportItem.Status.SNAPSHOTTING
    }

    val exportTotalBytes: Long get() = exportItems.sumOf { it.db.sizeBytes }
    val exportCopiedBytes: Long
        get() = exportItems.sumOf {
            when (it.status) {
                // Skipped files still count as handled so progress reaches 100%.
                ExportItem.Status.DONE, ExportItem.Status.SKIPPED -> it.db.sizeBytes
                else -> it.copiedBytes
            }
        }

    /**
     * Progress as a share of files handled, not bytes: a single 400 MB main database would
     * otherwise dominate the bar while the dozens of small ones barely move it.
     */
    val exportPercent: Int
        get() {
            val total = exportItems.size
            if (total == 0) return 0
            val handled = exportItems.count {
                it.status == ExportItem.Status.DONE ||
                    it.status == ExportItem.Status.SKIPPED ||
                    it.status == ExportItem.Status.FAILED
            }
            return (handled * 100 / total).coerceIn(0, 100)
        }

    /** Set when the export was blocked for lack of permission; the UI offers to grant it. */
    var exportPermissionBlocked by mutableStateOf(false)
        private set

    fun dismissPermissionBlocked() {
        exportPermissionBlocked = false
    }

    fun startExport() {
        if (exporting) {
            Log.w("vm", "startExport ignored: an export is already running")
            return
        }
        val account = selectedAccount ?: run {
            Log.w("vm", "startExport ignored: no account selected")
            return
        }
        val items = selectedDatabases
        if (items.isEmpty()) {
            Log.w("vm", "startExport ignored: no database selected")
            return
        }

        // Ask for All files access instead of failing on the write
        val dir = exportDir()
        if (StoragePaths.needsPermission(container.context, resolvedRoot().first)) {
            Log.w("vm", "export directory lacks permission, requesting access: ${resolvedRoot().first.absolutePath}")
            exportPermissionBlocked = true
            requestAllFilesAccess()
            return
        }
        lastExportDir = dir

        // Never let two runs overlap: a second run would replace exportItems while the
        // first one's workers still index into it, which both corrupts the list and
        // throws IndexOutOfBounds inside a worker — killing every sibling coroutine.
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            exporting = true
            exportStartedAt = System.currentTimeMillis()
            exportFinishedAt = 0
            exportItems = items.map { ExportItem(it) }
            setStatusAll(ExportItem.Status.QUEUED)

            val ds = source()
            val parallel = settings.value.parallel.coerceIn(1, 4)
            Log.banner(
                "EXPORT START",
                "source=${ds.label}\naccount=${account.dirName} wxid=${account.wxid}\n" +
                    "files=${items.size} total=${formatSize(items.sumOf { it.sizeBytes })}\n" +
                    "parallel=$parallel\ntarget=${dir.absolutePath}",
            )

            val queue = ArrayDeque(exportItems.indices.toList())

            suspend fun worker() {
                while (true) {
                    val idx = synchronized(queue) { if (queue.isEmpty()) -1 else queue.removeFirst() }
                    if (idx < 0) return
                    runOne(ds, account, dir, idx)
                }
            }
            // supervisorScope keeps one failing worker from cancelling the rest.
            supervisorScope {
                val workers = (1..parallel).map { launch { worker() } }
                workers.forEach { it.join() }
            }

            exporting = false
            exportFinishedAt = System.currentTimeMillis()
            Log.banner(
                "EXPORT FINISHED",
                "done=$exportDone failed=$exportFailed skipped=${exportItems.count { it.status == ExportItem.Status.SKIPPED }}\n" +
                    "written=${formatSize(exportCopiedBytes)} of ${formatSize(exportTotalBytes)}\n" +
                    "elapsed=${exportFinishedAt - exportStartedAt}ms\ntarget=${dir.absolutePath}",
            )
            showSummary = true
        }
    }

    private fun setStatusAll(s: ExportItem.Status) {
        exportItems = exportItems.map { it.copy(status = s) }
    }

    private fun updateItem(idx: Int, transform: (ExportItem) -> ExportItem) {
        exportItems = exportItems.toMutableList().also { it[idx] = transform(it[idx]) }
    }

    private suspend fun runOne(ds: DataSource, account: Account, dir: File, idx: Int) {
        val item = exportItems[idx]
        val t0 = System.currentTimeMillis()
        Log.i(
            "vm",
            "-> exporting [${idx + 1}/${exportItems.size}] ${item.db.name} (${item.db.readableSize}) encrypted=${item.db.encrypted}",
        )
        updateItem(idx) { it.copy(status = ExportItem.Status.RUNNING, stage = "preparing") }

        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                // Exports run concurrently, so the first task may create the directory while
                // the others see mkdirs() return false for an already-existing path.
                // Re-checking exists() keeps that from being reported as a failure.
                //
                // Only blame the permission when we genuinely do not hold it: mkdirs() also
                // fails transiently (FUSE busy, parent just created), and popping the settings
                // screen on those false positives interrupts a running export.
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    val granted = hasAllFilesAccess()
                    Log.w("vm", "cannot create export dir: ${dir.absolutePath} (allFiles=$granted)")
                    if (!granted) {
                        withContext(Dispatchers.Main) {
                            exportPermissionBlocked = true
                            requestAllFilesAccess()
                        }
                    }
                    return@withContext ExportOutcome.Failed(
                        container.context.getString(
                            if (granted) io.github.ryf5584.wxdbdump.R.string.export_failed_dir_io
                            else io.github.ryf5584.wxdbdump.R.string.export_failed_dir
                        ),
                    )
                }
                val f = File(dir, item.db.name)
                if (f.exists()) f.delete()
                var lastReport = 0L
                File(f.absolutePath).outputStream().buffered().use { out ->
                    // A single wedged database must not stall the whole run; the loopback
                    // socket has no read timeout of its own, so bound it here.
                    withTimeoutOrNull(EXPORT_ITEM_TIMEOUT_MS) {
                    ds.exportTo(item.db, account, out) { copied ->
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 120) {
                            lastReport = now
                            updateItem(idx) { it.copy(copiedBytes = copied, stage = "exporting") }
                        }
                    }
                    } ?: run {
                        // Cancelling the coroutine is not enough: the read is blocking, so the
                        // source has to be told to drop the connection before the worker can move on.
                        ds.abort()
                        return@withContext ExportOutcome.Failed(
                            container.context.getString(
                                io.github.ryf5584.wxdbdump.R.string.export_failed_timeout,
                            ),
                        )
                    }
                }
            }
        }.getOrElse { t ->
            // Cancellation must propagate: swallowing it here would turn a cancel into a
            // per-item failure and break structured concurrency.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e("vm", "runOne ${item.db.name} threw", t)
            ExportOutcome.Failed(t.message ?: t.toString())
        }

        val ms = System.currentTimeMillis() - t0

        // A disposable database (cache, stats buffer, rebuildable index) failing is not a
        // real failure: its content is derived from the main database, so report it as
        // skipped and keep the run at 100%.
        val effective = if (outcome is ExportOutcome.Failed && item.db.nonEssential) {
            Log.i("vm", "skip [${idx + 1}] ${item.db.name} optional database (${ms}ms): ${outcome.reason}")
            ExportOutcome.Skipped(
                container.context.getString(io.github.ryf5584.wxdbdump.R.string.export_skip_optional),
            )
        } else outcome

        // A skipped item may have left a partial (often zero-byte) file behind; drop it so
        // the export folder only ever contains databases that actually opened.
        if (effective !is ExportOutcome.Ok) {
            runCatching { File(dir, item.db.name).delete() }
        }

        when (effective) {
            is ExportOutcome.Ok -> Log.i("vm", "ok [${idx + 1}] ${item.db.name} exported (${ms}ms)")
            is ExportOutcome.Skipped -> Log.w("vm", "skip [${idx + 1}] ${item.db.name}: ${effective.reason} (${ms}ms)")
            is ExportOutcome.Failed -> Log.e("vm", "fail [${idx + 1}] ${item.db.name}: ${effective.reason} (${ms}ms)")
        }

        updateItem(idx) {
            when (effective) {
                is ExportOutcome.Ok -> it.copy(
                    status = ExportItem.Status.DONE,
                    copiedBytes = it.db.sizeBytes,
                    verified = true,
                    stage = "done",
                )

                is ExportOutcome.Skipped -> it.copy(
                    status = ExportItem.Status.SKIPPED,
                    reason = effective.reason,
                    stage = "skipped",
                )

                is ExportOutcome.Failed -> it.copy(
                    status = ExportItem.Status.FAILED,
                    reason = effective.reason,
                    stage = "failed",
                )
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        exporting = false
        exportItems = exportItems.map {
            if (it.status == ExportItem.Status.QUEUED || it.status == ExportItem.Status.RUNNING) {
                it.copy(status = ExportItem.Status.SKIPPED, reason = "cancelled")
            } else it
        }
    }

    fun resetExport() {
        exportItems = emptyList()
        showSummary = false
    }

    fun retryFailed() {
        val failed = exportItems.filter { it.status == ExportItem.Status.FAILED }
        if (failed.isEmpty()) return
        exportItems = exportItems.map {
            if (it.status == ExportItem.Status.FAILED) it.copy(
                status = ExportItem.Status.QUEUED, copiedBytes = 0, reason = "",
            ) else it
        }
        val account = selectedAccount ?: return
        val dir = lastExportDir ?: exportDir()
        // Never let two runs overlap: a second run would replace exportItems while the
        // first one's workers still index into it, which both corrupts the list and
        // throws IndexOutOfBounds inside a worker — killing every sibling coroutine.
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            exporting = true
            val ds = source()
            exportItems.forEachIndexed { idx, it ->
                if (it.status == ExportItem.Status.QUEUED) runOne(ds, account, dir, idx)
            }
            exporting = false
            exportFinishedAt = System.currentTimeMillis()
        }
    }

    // ---------------- Settings ----------------

    /** An empty string restores the default directory. */
    fun setOutputRoot(v: String) = viewModelScope.launch { container.settings.setOutputRoot(v) }

    fun resetOutputRoot() = viewModelScope.launch { container.settings.setOutputRoot("") }

    fun setTheme(v: ThemeChoice) = viewModelScope.launch { container.settings.setTheme(v) }

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { container.settings.setDynamicColor(v) }

    fun setReduceMotion(v: Boolean?) = viewModelScope.launch { container.settings.setReduceMotion(v) }

    fun setExportWal(v: Boolean) = viewModelScope.launch { container.settings.setExportWal(v) }

    fun setVerify(v: Boolean) = viewModelScope.launch { container.settings.setVerify(v) }

    fun setParallel(v: Int) = viewModelScope.launch { container.settings.setParallel(v) }

    fun resetSettings() = viewModelScope.launch { container.settings.resetAll() }

    // ---------------- Diagnostics: decryption self-test ----------------

    var selfTestRunning by mutableStateOf(false)
        private set
    var selfTestResult by mutableStateOf<String?>(null)

    /**
     * Decryption self-test: runs one encrypted database through the full export path to
     * confirm valid plaintext comes out.
     */
    fun runKeySelfTest() {
        if (selfTestRunning) return
        viewModelScope.launch {
            selfTestRunning = true
            selfTestResult = null
            val res = container.context.resources
            val sb = StringBuilder()
            try {
                val ds = source()
                val list = if (accounts.isNotEmpty()) accounts else ds.listAccounts()
                val account = selectedAccount ?: list.firstOrNull()
                if (account == null) {
                    selfTestResult = res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_no_account)
                    return@launch
                }
                val dbs = if (databases.isNotEmpty() && selectedAccount?.dirName == account.dirName) databases
                else ds.listDatabases(account)

                // Prefer the smallest encrypted database: a few hundred KB takes a second, while the
                // 400 MB main database would stall the whole flow.
                val enc = dbs.filter { it.encrypted && it.sizeBytes >= 4096 }
                    .minByOrNull { it.sizeBytes }
                if (enc == null) {
                    selfTestResult = res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_no_encrypted)
                    return@launch
                }
                sb.append(res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_account, account.displayName))
                    .append('\n')
                sb.append(
                    res.getString(
                        io.github.ryf5584.wxdbdump.R.string.selftest_target,
                        enc.name,
                        enc.readableSize,
                    ),
                ).append('\n')

                val tmp = File(container.context.cacheDir, "selftest_" + enc.name)
                val t0 = System.currentTimeMillis()
                val ok = withContext(Dispatchers.IO) {
                    // use{} closes the stream, otherwise small databases end up as empty files
                    tmp.outputStream().buffered().use { os -> ds.exportTo(enc, account, os) {} }
                }
                val ms = System.currentTimeMillis() - t0
                val size = tmp.length()
                val head = withContext(Dispatchers.IO) { headHex(tmp) }
                val plain = head.startsWith("53514c69746520666f726d61742033") // "SQLite format 3"

                when {
                    ok is ExportOutcome.Ok && plain -> {
                        sb.append(
                            res.getString(
                                io.github.ryf5584.wxdbdump.R.string.selftest_ok,
                                formatSize(size),
                                ms,
                            ),
                        ).append('\n')
                        sb.append(res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_ok_head, head))
                        Log.i("selftest", "self-test passed db=${enc.name} size=$size ${ms}ms")
                    }

                    ok is ExportOutcome.Ok -> {
                        sb.append(
                            res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_not_sqlite, head),
                        )
                        Log.w("selftest", "self-test abnormal db=${enc.name} head=$head")
                    }

                    ok is ExportOutcome.Skipped -> {
                        sb.append(res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_skipped, ok.reason))
                        Log.w("selftest", "self-test skipped db=${enc.name}: ${ok.reason}")
                    }

                    else -> {
                        val why = (ok as? ExportOutcome.Failed)?.reason ?: head
                        sb.append(res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_failed, why))
                        sb.append('\n')
                        sb.append(res.getString(io.github.ryf5584.wxdbdump.R.string.selftest_head, head))
                        Log.w("selftest", "self-test failed db=${enc.name} head=$head")
                    }
                }
                runCatching { tmp.delete() }
                selfTestResult = sb.toString()
            } catch (t: Throwable) {
                Log.e("selftest", "self-test threw", t)
                selfTestResult = res.getString(
                    io.github.ryf5584.wxdbdump.R.string.selftest_error,
                    t.toString(),
                )
            } finally {
                selfTestRunning = false
            }
        }
    }

    /** Hex of the first 16 bytes, for the self-test. A header is not sensitive. */
    private fun headHex(f: File): String = runCatching {
        if (!f.exists()) return@runCatching "<missing>"
        f.inputStream().use { ins ->
            val b = ByteArray(16)
            val n = ins.read(b)
            if (n <= 0) return@runCatching "<empty>"
            (0 until n).joinToString("") { "%02x".format(b[it]) }
        }
    }.getOrElse { "<read failed: $it>" }

    /** Diagnostic text shown on the settings screen. */
    fun diagText(): String = buildString {
        append("mode=").append(mode?.name ?: "none").append('\n')
        append("loopback=").append("127.0.0.1:")
            .append(io.github.ryf5584.wxdbdump.payload.proto.Channel.PORT).append('\n')
        append("xposed=").append(env.xposed::class.simpleName).append('\n')
        append("root=").append(env.root::class.simpleName)
            .append(" (available=").append(env.root is io.github.ryf5584.wxdbdump.core.DetectionState.Available)
            .append("）").append('\n')
        append("wechat=").append(env.wechatVersion).append('\n')
        append("exportRoot=").append(settings.value.effectiveRoot).append('\n')
        append("allFiles=").append(hasAllFilesAccess()).append('\n')
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container) as T
            }
    }
}
