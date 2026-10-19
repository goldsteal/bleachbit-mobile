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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.device.ScanNote
import dev.goldsteal.bleachbit.engine.Catalog
import dev.goldsteal.bleachbit.engine.Finding
import dev.goldsteal.bleachbit.engine.PolicyError

/** Shows what a clean would remove. Only checked rows are cleaned, and only after confirming. */
@Composable
fun PreviewScreen(
    findings: List<Finding>,
    checked: Set<String>,
    policyErrors: List<PolicyError>,
    notes: List<ScanNote>,
    confirming: Boolean,
    canNeverClean: (Finding) -> Boolean,
    onToggle: (String) -> Unit,
    onNeverClean: (Finding) -> Unit,
    onClean: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val selected = findings.filter { it.id in checked }
    val total = selected.sumOf { it.sizeBytes }
    Scaffold(
        topBar = { TopBar(stringResource(R.string.title_preview), onBack) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    pluralStringResource(R.plurals.selected_summary, selected.size, selected.size, formatSize(total)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onClean,
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TAG_CLEAN),
                ) { Text(stringResource(R.string.action_clean_selected)) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notes) { note ->
                Text(stringResource(note.text()), style = MaterialTheme.typography.bodySmall)
            }
            if (findings.isEmpty()) {
                item { Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodyLarge) }
            }
            items(findings, key = { it.id }) { finding ->
                FindingRow(
                    finding = finding,
                    checked = finding.id in checked,
                    canNeverClean = canNeverClean(finding),
                    onToggle = { onToggle(finding.id) },
                    onNeverClean = { onNeverClean(finding) },
                )
            }
            if (policyErrors.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.policy_errors_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(policyErrors) { e ->
                    Text(
                        stringResource(R.string.policy_error_line, e.subject, e.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Text(pluralStringResource(R.plurals.confirm_body, selected.size, selected.size, formatSize(total)))
            },
            confirmButton = {
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(TAG_CONFIRM)) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun FindingRow(
    finding: Finding,
    checked: Boolean,
    canNeverClean: Boolean,
    onToggle: () -> Unit,
    onNeverClean: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag("finding:${finding.id}"),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(finding.label, style = MaterialTheme.typography.bodyLarge)
                val option = Catalog.option(finding.optionId)?.label ?: finding.optionId
                val detail = if (finding.sizeBytes > 0) "$option · ${formatSize(finding.sizeBytes)}" else option
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (canNeverClean) {
            TextButton(onClick = onNeverClean) { Text(stringResource(R.string.action_never_clean)) }
        }
    }
}

fun ScanNote.text(): Int = when (this) {
    ScanNote.MEDIA_READ_DENIED -> R.string.note_media_read_denied
    ScanNote.DOWNLOADS_OWN_FILES_ONLY -> R.string.note_downloads_own_only
    ScanNote.TRASH_SCAN_FAILED -> R.string.note_trash_failed
    ScanNote.DOWNLOADS_SCAN_FAILED -> R.string.note_downloads_failed
}

const val TAG_CLEAN = "clean"
const val TAG_CONFIRM = "confirm"
