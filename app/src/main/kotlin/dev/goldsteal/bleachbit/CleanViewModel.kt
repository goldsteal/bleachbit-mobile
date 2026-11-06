/*
 * This file is part of BleachBit Mobile.
 *
 * BleachBit Mobile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BleachBit Mobile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.goldsteal.bleachbit

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.goldsteal.bleachbit.data.LastRun
import dev.goldsteal.bleachbit.data.RunLogStore
import dev.goldsteal.bleachbit.data.SettingsStore
import dev.goldsteal.bleachbit.device.AppSizeReader
import dev.goldsteal.bleachbit.device.AppSizeRow
import dev.goldsteal.bleachbit.device.ConsentBridge
import dev.goldsteal.bleachbit.device.DeviceActions
import dev.goldsteal.bleachbit.device.DeviceScanner
import dev.goldsteal.bleachbit.device.MediaIndex
import dev.goldsteal.bleachbit.device.ScanNote
import dev.goldsteal.bleachbit.engine.Assembly
import dev.goldsteal.bleachbit.engine.Catalog
import dev.goldsteal.bleachbit.engine.CleanExecutor
import dev.goldsteal.bleachbit.engine.CommandRunner
import dev.goldsteal.bleachbit.engine.CommandStatus
import dev.goldsteal.bleachbit.engine.DEFAULT_DUPLICATE_COLLECTIONS
import dev.goldsteal.bleachbit.engine.DEFAULT_LARGE_FILE_BYTES
import dev.goldsteal.bleachbit.engine.DuplicateGroup
import dev.goldsteal.bleachbit.engine.Duplicates
import dev.goldsteal.bleachbit.engine.Finding
import dev.goldsteal.bleachbit.engine.MediaCollection
import dev.goldsteal.bleachbit.engine.MediaItem
import dev.goldsteal.bleachbit.engine.NioPathLookup
import dev.goldsteal.bleachbit.engine.Operation
import dev.goldsteal.bleachbit.engine.OptionIds
import dev.goldsteal.bleachbit.engine.PackageSnapshot
import dev.goldsteal.bleachbit.engine.PlanAssembly
import dev.goldsteal.bleachbit.engine.PolicyContext
import dev.goldsteal.bleachbit.engine.PolicyError
import dev.goldsteal.bleachbit.engine.Privilege
import dev.goldsteal.bleachbit.engine.Report
import dev.goldsteal.bleachbit.engine.RunLog
import dev.goldsteal.bleachbit.engine.Whitelist
import dev.goldsteal.bleachbit.engine.WhitelistEntry
import dev.goldsteal.bleachbit.engine.defaultChecked
import dev.goldsteal.bleachbit.engine.largeFileFindings
import dev.goldsteal.bleachbit.engine.minus
import dev.goldsteal.bleachbit.engine.plus
import dev.goldsteal.bleachbit.engine.sortedByCache
import dev.goldsteal.bleachbit.engine.whitelistEntryFor
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    HOME, CLEAN, SCANNING, PREVIEW, DUPLICATES, APP_SIZES, RUNNING, RESULT, RUN_LOG, SETTINGS, WHITELIST, THRESHOLDS,
}

/** Which scan filled the review screen. */
enum class ScanKind { CLEAN, LARGE_FILES, DUPLICATES }

data class ScanProgress(val done: Int, val total: Int)

data class AppSizesState(
    val usageAccess: Boolean = false,
    val loading: Boolean = false,
    /** Sorted by cache, largest first. Null until loaded. */
    val rows: List<AppSizeRow>? = null,
    val showSystem: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val previous: Screen = Screen.HOME,
    val privilege: Privilege = Privilege.STANDARD,
    val showExpert: Boolean = false,
    val whitelist: Whitelist = Whitelist.EMPTY,
    val lastRun: LastRun? = null,
    val selectedOptions: Set<String> = emptySet(),
    val scanKind: ScanKind = ScanKind.CLEAN,
    val scanProgress: ScanProgress? = null,
    /** Rows the preview shows: scanned, present, not whitelisted. */
    val findings: List<Finding> = emptyList(),
    val policyErrors: List<PolicyError> = emptyList(),
    val checked: Set<String> = emptySet(),
    val notes: List<ScanNote> = emptyList(),
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    /** Duplicate copies the policy would allow deleting. The rest are shown as protected. */
    val markable: Set<String> = emptySet(),
    val confirming: Boolean = false,
    val report: Report? = null,
    val runLog: String = "",
    val appSizes: AppSizesState = AppSizesState(),
    val largeFileBytes: Long = DEFAULT_LARGE_FILE_BYTES,
    val duplicateCollections: Set<MediaCollection> = DEFAULT_DUPLICATE_COLLECTIONS,
)

class CleanViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val runLogStore = RunLogStore(app.noBackupFilesDir)
    private val scanner = DeviceScanner(app)
    private val media = MediaIndex(app)
    private val appSizeReader = AppSizeReader(app)
    val consent = ConsentBridge()
    private val actions = DeviceActions(app, consent) { AppForeground.started }
    private val assembler = PlanAssembly()

    // Standard privilege has no shell. Anything that reaches here is refused.
    private val executor = CleanExecutor(runner = CommandRunner { CommandStatus.ACCESS_DENIED }, local = actions)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Everything the last scan found, before policy and whitelist filtering. */
    private var rawFindings: List<Finding> = emptyList()
    private var scanUris: Set<String> = emptySet()
    private var scanJob: Job? = null
    private var appSizeJob: Job? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update {
                    it.copy(
                        showExpert = s.showExpert,
                        whitelist = s.whitelist,
                        lastRun = s.lastRun,
                        largeFileBytes = s.largeFileBytes,
                        duplicateCollections = s.duplicateCollections,
                    )
                }
            }
        }
    }

    /** Options shown on the Clean screen for this device and privilege. */
    fun availableOptions(state: UiState) = Catalog.visibleOptions(state.privilege, state.showExpert)
        .filter { it.id != OptionIds.MEDIA_TRASH || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R }

    fun go(screen: Screen) = _state.update { it.copy(screen = screen, previous = it.screen) }

    /** Returns false when back should leave the app. */
    fun back(): Boolean {
        val s = _state.value
        val target = when (s.screen) {
            Screen.HOME -> return false
            Screen.RUNNING -> return true
            Screen.SCANNING -> {
                cancelScan()
                return true
            }
            Screen.PREVIEW -> if (s.scanKind == ScanKind.CLEAN) Screen.CLEAN else Screen.HOME
            Screen.WHITELIST, Screen.THRESHOLDS -> Screen.SETTINGS
            Screen.RUN_LOG -> if (s.previous == Screen.RUN_LOG) Screen.HOME else s.previous
            Screen.CLEAN, Screen.SETTINGS, Screen.RESULT, Screen.DUPLICATES, Screen.APP_SIZES -> Screen.HOME
        }
        _state.update { it.copy(screen = target, previous = it.screen, confirming = false) }
        return true
    }

    fun toggleOption(id: String) = _state.update {
        it.copy(selectedOptions = if (id in it.selectedOptions) it.selectedOptions - id else it.selectedOptions + id)
    }

    fun setShowExpert(show: Boolean) {
        viewModelScope.launch { settings.setShowExpert(show) }
    }

    fun setLargeFileBytes(bytes: Long) {
        if (bytes <= 0) return
        viewModelScope.launch { settings.setLargeFileBytes(bytes) }
    }

    /** At least one location stays selected. */
    fun toggleDuplicateCollection(collection: MediaCollection) {
        val current = _state.value.duplicateCollections
        val next = if (collection in current) current - collection else current + collection
        if (next.isEmpty()) return
        _state.update { it.copy(duplicateCollections = next) }
        viewModelScope.launch { settings.setDuplicateCollections(next) }
    }

    // Scans

    fun scan(mediaReadGranted: Boolean) {
        val selected = _state.value.selectedOptions
        if (selected.isEmpty()) return
        startScan(ScanKind.CLEAN) {
            val result = scanner.scan(selected, mediaReadGranted)
            showPreview(ScanKind.CLEAN, result.findings, result.notes)
        }
    }

    fun findLargeFiles(mediaReadGranted: Boolean) {
        val threshold = _state.value.largeFileBytes
        startScan(ScanKind.LARGE_FILES) {
            val notes = mutableListOf(ScanNote.LARGE_FILES_MEDIA_ONLY)
            if (!mediaReadGranted) notes += ScanNote.MEDIA_READ_DENIED
            val items = listMedia(MediaCollection.entries.toSet(), threshold, notes)
            showPreview(ScanKind.LARGE_FILES, largeFileFindings(items, threshold), notes)
        }
    }

    fun findDuplicates(mediaReadGranted: Boolean) {
        val collections = _state.value.duplicateCollections
        startScan(ScanKind.DUPLICATES) {
            val notes = mutableListOf(ScanNote.DUPLICATES_MEDIA_ONLY)
            if (!mediaReadGranted) notes += ScanNote.MEDIA_READ_DENIED
            val items = listMedia(collections, minBytes = 1, notes)
            val groups = withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                Duplicates.find(
                    items,
                    hash = { item -> media.hash(item) { job.ensureActive() } },
                    onProgress = { done, total -> _state.update { it.copy(scanProgress = ScanProgress(done, total)) } },
                )
            }
            rawFindings = Duplicates.findings(groups)
            scanUris = mediaUrisOf(rawFindings)
            val assembly = assembleAll()
            val markable = visibleFindings(assembly).map { it.id }.toSet()
            coroutineContext.ensureActive()
            _state.update {
                it.copy(
                    screen = Screen.DUPLICATES,
                    previous = Screen.HOME,
                    scanProgress = null,
                    findings = rawFindings,
                    policyErrors = assembly.policyErrors,
                    duplicateGroups = groups,
                    markable = markable,
                    checked = Duplicates.defaultMarks(groups, markable),
                    notes = notes,
                    report = null,
                )
            }
        }
    }

    /** Stops a scan. A cancelled scan deletes nothing. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        val origin = if (_state.value.scanKind == ScanKind.CLEAN) Screen.CLEAN else Screen.HOME
        _state.update { it.copy(screen = origin, previous = Screen.HOME, scanProgress = null) }
    }

    private fun startScan(kind: ScanKind, block: suspend CoroutineScope.() -> Unit) {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(screen = Screen.SCANNING, previous = it.screen, scanKind = kind, scanProgress = null) }
        scanJob = viewModelScope.launch(block = block)
    }

    private suspend fun listMedia(
        collections: Set<MediaCollection>,
        minBytes: Long,
        notes: MutableList<ScanNote>,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            media.list(collections, minBytes)
        } catch (_: SecurityException) {
            notes += ScanNote.MEDIA_SCAN_FAILED
            emptyList()
        } catch (_: IllegalArgumentException) {
            notes += ScanNote.MEDIA_SCAN_FAILED
            emptyList()
        }
    }

    private suspend fun showPreview(kind: ScanKind, findings: List<Finding>, notes: List<ScanNote>) {
        rawFindings = findings
        scanUris = mediaUrisOf(findings)
        val assembly = assembleAll()
        val visible = visibleFindings(assembly)
        currentCoroutineContext().ensureActive()
        _state.update {
            it.copy(
                screen = Screen.PREVIEW,
                previous = if (kind == ScanKind.CLEAN) Screen.CLEAN else Screen.HOME,
                scanProgress = null,
                findings = visible,
                policyErrors = assembly.policyErrors,
                checked = visible.filter(::defaultChecked).map { f -> f.id }.toSet(),
                notes = notes,
                duplicateGroups = emptyList(),
                markable = emptySet(),
                report = null,
            )
        }
    }

    // Review

    fun toggleFinding(id: String) = _state.update {
        if (it.screen == Screen.DUPLICATES) {
            it.copy(checked = Duplicates.toggle(it.duplicateGroups, it.checked, it.markable, id))
        } else {
            it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
        }
    }

    fun canNeverClean(finding: Finding): Boolean = whitelistEntryFor(finding) != null

    /** Adds the row to the whitelist and drops it, and anything else it covers, from the preview. */
    fun neverClean(finding: Finding) {
        val entry = whitelistEntryFor(finding) ?: return
        val whitelist = _state.value.whitelist.plus(entry)
        _state.update { it.copy(whitelist = whitelist) }
        viewModelScope.launch {
            settings.setWhitelist(whitelist)
            val assembly = assembleAll()
            val visible = visibleFindings(assembly)
            val ids = visible.map { it.id }.toSet()
            _state.update { it.copy(findings = visible, policyErrors = assembly.policyErrors, checked = it.checked intersect ids) }
        }
    }

    fun removeWhitelistEntry(entry: WhitelistEntry) {
        val whitelist = _state.value.whitelist.minus(entry)
        _state.update { it.copy(whitelist = whitelist) }
        viewModelScope.launch { settings.setWhitelist(whitelist) }
    }

    fun askConfirm() {
        if (_state.value.checked.isNotEmpty()) _state.update { it.copy(confirming = true) }
    }

    fun dismissConfirm() = _state.update { it.copy(confirming = false) }

    fun clean() {
        val s = _state.value
        if (s.checked.isEmpty()) return
        // The duplicate picker already refuses this; check again before anything is deleted.
        if (s.screen == Screen.DUPLICATES && !Duplicates.keepsACopyOfEach(s.duplicateGroups, s.checked)) {
            dismissConfirm()
            return
        }
        _state.update { it.copy(confirming = false, screen = Screen.RUNNING, previous = s.screen) }
        val startedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        viewModelScope.launch {
            val findings = rawFindings
            val assembly = withContext(Dispatchers.IO) {
                assembler.assemble(findings, s.checked, context())
            }
            val mediaUris = assembly.plan.items.mapNotNull { (it.operation as? Operation.DeleteMedia)?.uri }
            actions.prepareMediaDeletes(mediaUris)
            val report = try {
                withContext(Dispatchers.IO) { executor.execute(assembly.plan) { context() } }
            } finally {
                actions.finishRun()
            }
            val log = RunLog.format(startedAt, findings, assembly, report)
            withContext(Dispatchers.IO) { runLogStore.append(log) }
            settings.recordRun(System.currentTimeMillis(), report.bytesFreed)
            _state.update { it.copy(screen = Screen.RESULT, previous = s.screen, report = report) }
        }
    }

    fun openRunLog() {
        go(Screen.RUN_LOG)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runLogStore.read() }
            _state.update { it.copy(runLog = text) }
        }
    }

    // App sizes

    fun openAppSizes() {
        go(Screen.APP_SIZES)
        refreshAppSizes(reload = false)
    }

    fun reloadAppSizes() = refreshAppSizes(reload = true)

    fun setShowSystemApps(show: Boolean) = _state.update { it.copy(appSizes = it.appSizes.copy(showSystem = show)) }

    /** Called when the activity resumes, such as after the user returns from the usage-access screen. */
    fun onResume() {
        if (_state.value.screen == Screen.APP_SIZES) refreshAppSizes(reload = false)
    }

    private fun refreshAppSizes(reload: Boolean) {
        val access = appSizeReader.hasUsageAccess()
        if (!access) {
            appSizeJob?.cancel()
            _state.update { it.copy(appSizes = it.appSizes.copy(usageAccess = false, loading = false, rows = null)) }
            return
        }
        val current = _state.value.appSizes
        _state.update { it.copy(appSizes = it.appSizes.copy(usageAccess = true)) }
        if (!reload && (current.rows != null || current.loading)) return
        appSizeJob?.cancel()
        _state.update { it.copy(appSizes = it.appSizes.copy(loading = true)) }
        appSizeJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val job = coroutineContext.job
                val read = appSizeReader.read { job.ensureActive() }
                val byPackage = read.associateBy { it.size.packageName }
                sortedByCache(read.map { it.size }).mapNotNull { byPackage[it.packageName] }
            }
            _state.update { it.copy(appSizes = it.appSizes.copy(loading = false, rows = rows)) }
        }
    }

    private suspend fun assembleAll(): Assembly = withContext(Dispatchers.IO) {
        assembler.assemble(rawFindings, rawFindings.map { it.id }.toSet(), context())
    }

    private fun visibleFindings(assembly: Assembly): List<Finding> {
        val planned = assembly.plan.items.map { it.findingId }.toSet()
        return rawFindings.filter { it.id in planned }
    }

    private fun mediaUrisOf(findings: List<Finding>): Set<String> =
        findings.mapNotNull { (it.operation as? Operation.DeleteMedia)?.uri }.toSet()

    private fun context() = PolicyContext(
        userId = Process.myUid() / 100_000,
        // Standard privilege never deletes package paths, so no package list is needed yet.
        packages = PackageSnapshot(emptySet()),
        whitelist = _state.value.whitelist,
        scanMediaUris = scanUris,
        lookup = NioPathLookup,
    )
}
