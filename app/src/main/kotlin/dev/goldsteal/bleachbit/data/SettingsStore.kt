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
package dev.goldsteal.bleachbit.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.goldsteal.bleachbit.engine.DEFAULT_DUPLICATE_COLLECTIONS
import dev.goldsteal.bleachbit.engine.DEFAULT_LARGE_FILE_BYTES
import dev.goldsteal.bleachbit.engine.MediaCollection
import dev.goldsteal.bleachbit.engine.MediaCollectionCodec
import dev.goldsteal.bleachbit.engine.Whitelist
import dev.goldsteal.bleachbit.engine.WhitelistCodec
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class LastRun(val atMillis: Long, val bytesFreed: Long)

data class Settings(
    val whitelist: Whitelist,
    val showExpert: Boolean,
    val lastRun: LastRun?,
    val largeFileBytes: Long,
    val duplicateCollections: Set<MediaCollection>,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::toSettings)

    suspend fun setWhitelist(whitelist: Whitelist) {
        store.edit { it[WHITELIST] = WhitelistCodec.encode(whitelist) }
    }

    suspend fun setShowExpert(show: Boolean) {
        store.edit { it[SHOW_EXPERT] = show }
    }

    suspend fun setLargeFileBytes(bytes: Long) {
        store.edit { it[LARGE_FILE_BYTES] = bytes }
    }

    suspend fun setDuplicateCollections(collections: Set<MediaCollection>) {
        store.edit { it[DUPLICATE_COLLECTIONS] = MediaCollectionCodec.encode(collections) }
    }

    suspend fun recordRun(atMillis: Long, bytesFreed: Long) {
        store.edit {
            it[LAST_RUN_AT] = atMillis
            it[LAST_FREED] = bytesFreed
        }
    }

    private fun toSettings(prefs: Preferences): Settings {
        val at = prefs[LAST_RUN_AT]
        return Settings(
            whitelist = prefs[WHITELIST]?.let(WhitelistCodec::decode) ?: Whitelist.EMPTY,
            showExpert = prefs[SHOW_EXPERT] ?: false,
            lastRun = at?.let { LastRun(it, prefs[LAST_FREED] ?: 0) },
            largeFileBytes = prefs[LARGE_FILE_BYTES]?.takeIf { it > 0 } ?: DEFAULT_LARGE_FILE_BYTES,
            duplicateCollections = prefs[DUPLICATE_COLLECTIONS]?.let(MediaCollectionCodec::decode)
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_DUPLICATE_COLLECTIONS,
        )
    }

    private companion object {
        val WHITELIST = stringPreferencesKey("whitelist")
        val SHOW_EXPERT = booleanPreferencesKey("show_expert")
        val LAST_RUN_AT = longPreferencesKey("last_run_at")
        val LAST_FREED = longPreferencesKey("last_freed")
        val LARGE_FILE_BYTES = longPreferencesKey("large_file_bytes")
        val DUPLICATE_COLLECTIONS = stringPreferencesKey("duplicate_collections")
    }
}
