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

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.data.LastRun
import dev.goldsteal.bleachbit.engine.Privilege
import dev.goldsteal.bleachbit.engine.displayName

@Composable
fun HomeScreen(
    privilege: Privilege,
    lastRun: LastRun?,
    onClean: () -> Unit,
    onSettings: () -> Unit,
    onRunLog: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.privilege_line, privilege.displayName()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (lastRun == null) {
                    stringResource(R.string.no_clean_yet)
                } else {
                    val context = LocalContext.current
                    val at = DateUtils.formatDateTime(
                        context,
                        lastRun.atMillis,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
                    )
                    stringResource(R.string.last_run_line, at, formatSize(lastRun.bytesFreed))
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(R.string.home_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClean, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_clean))
            }
            OutlinedButton(onClick = onRunLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_run_log))
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.title_settings))
            }
        }
    }
}
