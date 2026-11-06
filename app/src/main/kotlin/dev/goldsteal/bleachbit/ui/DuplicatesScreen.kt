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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.device.ScanNote
import dev.goldsteal.bleachbit.engine.DuplicateGroup
import dev.goldsteal.bleachbit.engine.MediaItem

/**
 * Groups of identical files. A checked copy is deleted. The newest copy starts unchecked, and
 * [onToggle] refuses to check the last unchecked copy of a group.
 */
@Composable
fun DuplicatesScreen(
    groups: List<DuplicateGroup>,
    checked: Set<String>,
    markable: Set<String>,
    notes: List<ScanNote>,
    confirming: Boolean,
    onToggle: (String) -> Unit,
    onClean: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
) {
    val copies = groups.flatMap { group -> group.copies.map { DuplicateGroup.findingIdOf(it) to it } }
    val selected = copies.filter { (id, _) -> id in checked }
    val total = selected.sumOf { (_, item) -> item.sizeBytes }
    Scaffold(
        topBar = { TopBar(stringResource(R.string.title_duplicates), onBack) },
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
            if (groups.isEmpty()) {
                item { Text(stringResource(R.string.no_duplicates), style = MaterialTheme.typography.bodyLarge) }
            } else {
                item { Text(stringResource(R.string.duplicates_help), style = MaterialTheme.typography.bodyMedium) }
            }
            groups.forEach { group ->
                item(key = "group:${group.sha256}:${group.sizeBytes}") {
                    Column(Modifier.padding(top = 12.dp)) {
                        HorizontalDivider()
                        Text(
                            pluralStringResource(
                                R.plurals.duplicate_group_title,
                                group.copies.size,
                                group.copies.size,
                                formatSize(group.sizeBytes),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(group.copies, key = { DuplicateGroup.findingIdOf(it) }) { item ->
                    val id = DuplicateGroup.findingIdOf(item)
                    CopyRow(
                        item = item,
                        id = id,
                        checked = id in checked,
                        protected = id !in markable,
                        onToggle = { onToggle(id) },
                    )
                }
            }
        }
    }
    if (confirming) ConfirmDeleteDialog(selected.size, total, onConfirm, onDismiss)
}

@Composable
private fun CopyRow(item: MediaItem, id: String, checked: Boolean, protected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = !protected, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag("copy:$id"),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = !protected)
        Column(Modifier.padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            item.path?.substringBeforeLast('/')?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val modified = DateUtils.formatDateTime(
                context,
                item.modifiedMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
            )
            val status = when {
                protected -> stringResource(R.string.copy_protected)
                checked -> stringResource(R.string.copy_delete)
                else -> stringResource(R.string.copy_keep)
            }
            Text(stringResource(R.string.copy_detail, modified, status), style = MaterialTheme.typography.bodySmall)
        }
    }
}
