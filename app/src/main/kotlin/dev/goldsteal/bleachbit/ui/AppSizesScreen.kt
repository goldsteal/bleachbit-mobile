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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.goldsteal.bleachbit.AppSizesState
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.device.AppSizeRow

/**
 * Android's own per-app storage figures, sorted by cache. Nothing is deleted here; a row opens
 * the system App info screen, where the user can clear that app's cache.
 */
@Composable
fun AppSizesScreen(
    state: AppSizesState,
    onGrant: () -> Unit,
    onReload: () -> Unit,
    onShowSystem: (Boolean) -> Unit,
    onOpenApp: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { TopBar(stringResource(R.string.title_app_sizes), onBack) }) { padding ->
        when {
            !state.usageAccess -> Column(
                Modifier.fillMaxSize().padding(padding).padding(ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.usage_access_explain), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().testTag(TAG_GRANT_USAGE)) {
                    Text(stringResource(R.string.action_grant_usage_access))
                }
            }
            state.rows == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val rows = if (state.showSystem) state.rows else state.rows.filter { !it.isSystem }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = ScreenPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(stringResource(R.string.app_sizes_explain), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = state.showSystem, role = Role.Switch, onValueChange = onShowSystem),
                        ) {
                            Text(
                                stringResource(R.string.show_system_apps),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = state.showSystem, onCheckedChange = null)
                        }
                    }
                    item {
                        TextButton(onClick = onReload, enabled = !state.loading) {
                            Text(stringResource(if (state.loading) R.string.loading else R.string.action_reload))
                        }
                    }
                    items(rows, key = { it.size.packageName }) { row -> AppRow(row) { onOpenApp(row.size.packageName) } }
                }
            }
        }
    }
}

@Composable
private fun AppRow(row: AppSizeRow, onClick: () -> Unit) {
    val size = row.size
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.open_app_info), onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("app:${size.packageName}"),
    ) {
        Text(size.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                R.string.app_size_line,
                formatSize(size.cacheBytes),
                formatSize(size.dataWithoutCacheBytes),
                formatSize(size.appBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

const val TAG_GRANT_USAGE = "grant_usage"
