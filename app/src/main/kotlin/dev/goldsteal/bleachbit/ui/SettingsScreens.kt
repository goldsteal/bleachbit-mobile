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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.engine.Whitelist
import dev.goldsteal.bleachbit.engine.WhitelistEntry
import dev.goldsteal.bleachbit.engine.entries

@Composable
fun SettingsScreen(
    showExpert: Boolean,
    whitelistSize: Int,
    onShowExpert: (Boolean) -> Unit,
    onWhitelist: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { TopBar(stringResource(R.string.title_settings), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(ScreenPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = showExpert, role = Role.Switch, onValueChange = onShowExpert)
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setting_show_expert), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.setting_show_expert_body), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = showExpert, onCheckedChange = null)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWhitelist)
                    .padding(vertical = 12.dp),
            ) {
                Text(stringResource(R.string.title_whitelist), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.whitelist_count, whitelistSize),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun WhitelistScreen(whitelist: Whitelist, onRemove: (WhitelistEntry) -> Unit, onBack: () -> Unit) {
    val entries = whitelist.entries()
    Scaffold(topBar = { TopBar(stringResource(R.string.title_whitelist), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding) {
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.whitelist_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            items(entries, key = { it.toString() }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (entry) {
                                is WhitelistEntry.Path -> entry.path
                                is WhitelistEntry.Package -> entry.packageName
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(
                                if (entry is WhitelistEntry.Path) R.string.whitelist_kind_path else R.string.whitelist_kind_package,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onRemove(entry) }) { Text(stringResource(R.string.action_remove)) }
                }
            }
        }
    }
}

@Composable
fun RunLogScreen(text: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopBar(stringResource(R.string.title_run_log), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
        ) {
            if (text.isBlank()) {
                Text(stringResource(R.string.run_log_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
