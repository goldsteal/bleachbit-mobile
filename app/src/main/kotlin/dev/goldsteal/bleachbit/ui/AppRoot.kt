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
package dev.goldsteal.bleachbit.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.goldsteal.bleachbit.CleanViewModel
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.ScanKind
import dev.goldsteal.bleachbit.Screen
import dev.goldsteal.bleachbit.device.AppSizeReader
import dev.goldsteal.bleachbit.engine.OptionIds
import dev.goldsteal.bleachbit.engine.entries

/** Permissions needed to see other apps' media, trashed or not. */
val MEDIA_READ_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun AppRoot(vm: CleanViewModel) {
    val state by vm.state.collectAsState()
    val activity = LocalActivity.current
    val context = LocalContext.current

    BackHandler { if (!vm.back()) activity?.finish() }

    fun mediaGranted() = MEDIA_READ_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // The scan runs whatever the answer; without the permission it only sees this app's items.
    var afterPermission by rememberSaveable { mutableStateOf(ScanKind.CLEAN) }
    fun runScan(kind: ScanKind) = when (kind) {
        ScanKind.CLEAN -> vm.scan(mediaGranted())
        ScanKind.LARGE_FILES -> vm.findLargeFiles(mediaGranted())
        ScanKind.DUPLICATES -> vm.findDuplicates(mediaGranted())
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runScan(afterPermission)
    }
    fun scanWithMedia(kind: ScanKind) {
        if (mediaGranted()) {
            runScan(kind)
        } else {
            afterPermission = kind
            permissionLauncher.launch(MEDIA_READ_PERMISSIONS)
        }
    }

    fun open(intent: Intent, fallback: Intent? = null) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            fallback?.let { open(it) }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.consent.answered(it.resultCode == Activity.RESULT_OK)
    }
    val pending by vm.consent.pending.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            vm.consent.launched()
            consentLauncher.launch(it)
        }
    }

    when (state.screen) {
        Screen.HOME -> HomeScreen(
            privilege = state.privilege,
            lastRun = state.lastRun,
            onClean = { vm.go(Screen.CLEAN) },
            onLargeFiles = { scanWithMedia(ScanKind.LARGE_FILES) },
            onDuplicates = { scanWithMedia(ScanKind.DUPLICATES) },
            onAppSizes = vm::openAppSizes,
            onSettings = { vm.go(Screen.SETTINGS) },
            onRunLog = vm::openRunLog,
        )
        Screen.CLEAN -> CleanScreen(
            options = vm.availableOptions(state),
            selected = state.selectedOptions,
            onToggle = vm::toggleOption,
            onScan = {
                if (OptionIds.MEDIA_TRASH in state.selectedOptions) scanWithMedia(ScanKind.CLEAN) else runScan(ScanKind.CLEAN)
            },
            onBack = { vm.back() },
        )
        Screen.SCANNING -> ProgressScreen(
            message = stringResource(if (state.scanKind == ScanKind.DUPLICATES) R.string.finding_duplicates else R.string.scanning),
            progress = state.scanProgress,
            onCancel = vm::cancelScan,
        )
        Screen.RUNNING -> ProgressScreen(stringResource(R.string.cleaning))
        Screen.PREVIEW -> PreviewScreen(
            findings = state.findings,
            checked = state.checked,
            policyErrors = state.policyErrors,
            notes = state.notes,
            confirming = state.confirming,
            canNeverClean = vm::canNeverClean,
            onToggle = vm::toggleFinding,
            onNeverClean = vm::neverClean,
            onClean = vm::askConfirm,
            onConfirm = vm::clean,
            onDismiss = vm::dismissConfirm,
            onBack = { vm.back() },
            title = stringResource(if (state.scanKind == ScanKind.LARGE_FILES) R.string.title_large_files else R.string.title_preview),
        )
        Screen.DUPLICATES -> DuplicatesScreen(
            groups = state.duplicateGroups,
            checked = state.checked,
            markable = state.markable,
            notes = state.notes,
            confirming = state.confirming,
            onToggle = vm::toggleFinding,
            onClean = vm::askConfirm,
            onConfirm = vm::clean,
            onDismiss = vm::dismissConfirm,
            onBack = { vm.back() },
        )
        Screen.APP_SIZES -> AppSizesScreen(
            state = state.appSizes,
            onGrant = { open(AppSizeReader.usageAccessIntent(context), AppSizeReader.usageAccessListIntent()) },
            onReload = vm::reloadAppSizes,
            onShowSystem = vm::setShowSystemApps,
            onOpenApp = { open(AppSizeReader.appInfoIntent(it)) },
            onBack = { vm.back() },
        )
        Screen.RESULT -> state.report?.let { report ->
            ResultScreen(
                report = report,
                findings = state.findings,
                onDone = { vm.go(Screen.HOME) },
                onRunLog = vm::openRunLog,
            )
        } ?: HomeFallback(vm)
        Screen.RUN_LOG -> RunLogScreen(state.runLog) { vm.back() }
        Screen.SETTINGS -> SettingsScreen(
            showExpert = state.showExpert,
            whitelistSize = state.whitelist.entries().size,
            onShowExpert = vm::setShowExpert,
            onWhitelist = { vm.go(Screen.WHITELIST) },
            onThresholds = { vm.go(Screen.THRESHOLDS) },
            onBack = { vm.back() },
        )
        Screen.WHITELIST -> WhitelistScreen(state.whitelist, vm::removeWhitelistEntry) { vm.back() }
        Screen.THRESHOLDS -> ThresholdsScreen(
            largeFileBytes = state.largeFileBytes,
            duplicateCollections = state.duplicateCollections,
            onLargeFileBytes = vm::setLargeFileBytes,
            onToggleCollection = vm::toggleDuplicateCollection,
            onBack = { vm.back() },
        )
    }
}

/** A result screen with no report should not happen; go home rather than show nothing. */
@Composable
private fun HomeFallback(vm: CleanViewModel) {
    LaunchedEffect(Unit) { vm.go(Screen.HOME) }
}
