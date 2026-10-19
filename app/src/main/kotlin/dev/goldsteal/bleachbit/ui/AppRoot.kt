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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.goldsteal.bleachbit.CleanViewModel
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.Screen
import dev.goldsteal.bleachbit.engine.OptionIds
import dev.goldsteal.bleachbit.engine.entries

/** Permissions needed to see other apps' trashed media. */
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.scan(mediaGranted())
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
            onSettings = { vm.go(Screen.SETTINGS) },
            onRunLog = vm::openRunLog,
        )
        Screen.CLEAN -> CleanScreen(
            options = vm.availableOptions(state),
            selected = state.selectedOptions,
            onToggle = vm::toggleOption,
            onScan = {
                if (OptionIds.MEDIA_TRASH in state.selectedOptions && !mediaGranted()) {
                    permissionLauncher.launch(MEDIA_READ_PERMISSIONS)
                } else {
                    vm.scan(mediaGranted())
                }
            },
            onBack = { vm.back() },
        )
        Screen.SCANNING -> ProgressScreen(stringResource(R.string.scanning))
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
            onBack = { vm.back() },
        )
        Screen.WHITELIST -> WhitelistScreen(state.whitelist, vm::removeWhitelistEntry) { vm.back() }
    }
}

/** A result screen with no report should not happen; go home rather than show nothing. */
@Composable
private fun HomeFallback(vm: CleanViewModel) {
    LaunchedEffect(Unit) { vm.go(Screen.HOME) }
}
