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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.engine.Finding
import dev.goldsteal.bleachbit.engine.Outcome
import dev.goldsteal.bleachbit.engine.Report

@Composable
fun ResultScreen(report: Report, findings: List<Finding>, onDone: () -> Unit, onRunLog: () -> Unit) {
    val labels = findings.associate { it.id to it.label }
    val deleted = report.rows.count { it.outcome == Outcome.DELETED }
    val others = report.rows.filter { it.outcome != Outcome.DELETED }
    Scaffold(
        topBar = { TopBar(stringResource(R.string.title_result), onDone) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
                OutlinedButton(onClick = onRunLog, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.title_run_log))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    pluralStringResource(R.plurals.result_summary, deleted, deleted, formatSize(report.bytesFreed)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (report.aborted) {
                item {
                    Text(
                        pluralStringResource(R.plurals.result_aborted, report.notRun, report.notRun),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(others) { row ->
                val label = labels[row.findingId] ?: row.findingId
                val outcome = stringResource(
                    when (row.outcome) {
                        Outcome.SKIPPED -> R.string.outcome_skipped
                        Outcome.ACCESS_DENIED -> R.string.outcome_access_denied
                        else -> R.string.outcome_failed
                    },
                )
                Column {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(outcome, row.detail).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
