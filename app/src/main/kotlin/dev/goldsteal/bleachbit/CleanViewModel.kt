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
import dev.goldsteal.bleachbit.device.ConsentBridge
import dev.goldsteal.bleachbit.device.DeviceActions
import dev.goldsteal.bleachbit.device.DeviceScanner
import dev.goldsteal.bleachbit.device.ScanNote
import dev.goldsteal.bleachbit.engine.Assembly
import dev.goldsteal.bleachbit.engine.Catalog
import dev.goldsteal.bleachbit.engine.CleanExecutor
import dev.goldsteal.bleachbit.engine.CommandRunner
import dev.goldsteal.bleachbit.engine.CommandStatus
import dev.goldsteal.bleachbit.engine.Finding
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
import dev.goldsteal.bleachbit.engine.minus
import dev.goldsteal.bleachbit.engine.plus
import dev.goldsteal.bleachbit.engine.whitelistEntryFor
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, CLEAN, SCANNING, PREVIEW, RUNNING, RESULT, RUN_LOG, SETTINGS, WHITELIST }

data class UiState(
    val screen: Screen = Screen.HOME,
    val previous: Screen = Screen.HOME,
    val privilege: Privilege = Privilege.STANDARD,
    val showExpert: Boolean = false,
    val whitelist: Whitelist = Whitelist.EMPTY,
    val lastRun: LastRun? = null,
    val selectedOptions: Set<String> = emptySet(),
    /** Rows the preview shows: scanned, present, not whitelisted. */
    val findings: List<Finding> = emptyList(),
    val policyErrors: List<PolicyError> = emptyList(),
    val checked: Set<String> = emptySet(),
    val notes: List<ScanNote> = emptyList(),
    val confirming: Boolean = false,
    val report: Report? = null,
    val runLog: String = "",
)

class CleanViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val runLogStore = RunLogStore(app.noBackupFilesDir)
    private val scanner = DeviceScanner(app)
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

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(showExpert = s.showExpert, whitelist = s.whitelist, lastRun = s.lastRun) }
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
            Screen.RUNNING, Screen.SCANNING -> return true
            Screen.PREVIEW -> Screen.CLEAN
            Screen.WHITELIST -> Screen.SETTINGS
            Screen.RUN_LOG -> if (s.previous == Screen.RUN_LOG) Screen.HOME else s.previous
            Screen.CLEAN, Screen.SETTINGS, Screen.RESULT -> Screen.HOME
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

    fun scan(mediaReadGranted: Boolean) {
        val selected = _state.value.selectedOptions
        if (selected.isEmpty()) return
        go(Screen.SCANNING)
        viewModelScope.launch {
            val result = scanner.scan(selected, mediaReadGranted)
            rawFindings = result.findings
            scanUris = result.mediaUris
            val assembly = assembleAll()
            val visible = visibleFindings(assembly)
            _state.update {
                it.copy(
                    screen = Screen.PREVIEW,
                    previous = Screen.CLEAN,
                    findings = visible,
                    policyErrors = assembly.policyErrors,
                    checked = visible.filter(::defaultChecked).map { f -> f.id }.toSet(),
                    notes = result.notes,
                    report = null,
                )
            }
        }
    }

    fun toggleFinding(id: String) = _state.update {
        it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
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
        _state.update { it.copy(confirming = false, screen = Screen.RUNNING, previous = Screen.PREVIEW) }
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
            _state.update { it.copy(screen = Screen.RESULT, previous = Screen.PREVIEW, report = report) }
        }
    }

    fun openRunLog() {
        go(Screen.RUN_LOG)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runLogStore.read() }
            _state.update { it.copy(runLog = text) }
        }
    }

    private suspend fun assembleAll(): Assembly = withContext(Dispatchers.IO) {
        assembler.assemble(rawFindings, rawFindings.map { it.id }.toSet(), context())
    }

    private fun visibleFindings(assembly: Assembly): List<Finding> {
        val planned = assembly.plan.items.map { it.findingId }.toSet()
        return rawFindings.filter { it.id in planned }
    }

    private fun context() = PolicyContext(
        userId = Process.myUid() / 100_000,
        // Standard privilege never deletes package paths, so no package list is needed yet.
        packages = PackageSnapshot(emptySet()),
        whitelist = _state.value.whitelist,
        scanMediaUris = scanUris,
        lookup = NioPathLookup,
    )
}
