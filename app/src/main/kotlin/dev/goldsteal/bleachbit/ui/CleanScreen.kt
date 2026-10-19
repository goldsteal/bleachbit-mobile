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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.engine.Catalog
import dev.goldsteal.bleachbit.engine.Option
import dev.goldsteal.bleachbit.engine.Risk

/** Lets the user pick which options to scan. Nothing starts selected. */
@Composable
fun CleanScreen(
    options: List<Option>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val available = options.map { it.id }.toSet()
    val groups = Catalog.cleaners
        .map { cleaner -> cleaner to cleaner.options.filter { it.id in available } }
        .filter { (_, opts) -> opts.isNotEmpty() }
    Scaffold(
        topBar = { TopBar(stringResource(R.string.title_clean), onBack) },
        bottomBar = {
            Button(
                onClick = onScan,
                enabled = selected.any { it in available },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag(TAG_SCAN),
            ) { Text(stringResource(R.string.action_scan)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (cleaner, opts) ->
                item(key = cleaner.id) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(cleaner.label, style = MaterialTheme.typography.titleMedium)
                        Text(cleaner.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(opts, key = { it.id }) { option ->
                    OptionRow(option, option.id in selected) { onToggle(option.id) }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: Option, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag("option:${option.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            if (option.risk != Risk.SAFE) {
                Text(
                    stringResource(if (option.risk == Risk.EXPERT) R.string.risk_expert else R.string.risk_review),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            option.warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

const val TAG_SCAN = "scan"
