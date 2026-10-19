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
package dev.goldsteal.bleachbit.device

import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import dev.goldsteal.bleachbit.R
import dev.goldsteal.bleachbit.engine.Finding
import dev.goldsteal.bleachbit.engine.Operation
import dev.goldsteal.bleachbit.engine.OptionIds
import dev.goldsteal.bleachbit.engine.isApk
import dev.goldsteal.bleachbit.engine.isPartialDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScanNote {
    MEDIA_READ_DENIED,
    DOWNLOADS_OWN_FILES_ONLY,
    TRASH_SCAN_FAILED,
    DOWNLOADS_SCAN_FAILED,
}

data class ScanResult(
    val findings: List<Finding>,
    val mediaUris: Set<String>,
    val notes: List<ScanNote>,
)

/** Finds what the Standard-privilege cleaners can remove. It only reads; it never deletes. */
class DeviceScanner(private val context: Context) {
    private val resolver get() = context.contentResolver

    suspend fun scan(optionIds: Set<String>, mediaReadGranted: Boolean): ScanResult {
        val findings = mutableListOf<Finding>()
        val notes = mutableListOf<ScanNote>()
        withContext(Dispatchers.IO) {
            if (OptionIds.MEDIA_TRASH in optionIds && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!mediaReadGranted) notes += ScanNote.MEDIA_READ_DENIED
                guarded(notes, ScanNote.TRASH_SCAN_FAILED) { findings += scanTrash() }
            }
            val partial = OptionIds.DOWNLOADS_PARTIAL in optionIds
            val apk = OptionIds.DOWNLOADS_APK in optionIds
            if (partial || apk) {
                notes += ScanNote.DOWNLOADS_OWN_FILES_ONLY
                guarded(notes, ScanNote.DOWNLOADS_SCAN_FAILED) { findings += scanDownloads(partial, apk) }
            }
        }
        if (OptionIds.CLIPBOARD_CURRENT in optionIds && hasClip()) {
            findings += Finding(
                id = OptionIds.CLIPBOARD_CURRENT,
                optionId = OptionIds.CLIPBOARD_CURRENT,
                label = context.getString(R.string.clipboard_finding),
                sizeBytes = 0,
                operation = Operation.ClearClipboard,
            )
        }
        val uris = findings.mapNotNull { (it.operation as? Operation.DeleteMedia)?.uri }.toSet()
        return ScanResult(findings, uris, notes)
    }

    private inline fun guarded(notes: MutableList<ScanNote>, onFailure: ScanNote, block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            notes += onFailure
        } catch (_: IllegalArgumentException) {
            notes += onFailure
        } catch (_: IllegalStateException) {
            notes += onFailure
        }
    }

    /** Checks for a clip without reading it, so Android shows no paste notice. */
    private suspend fun hasClip(): Boolean = withContext(Dispatchers.Main) {
        context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun scanTrash(): List<Finding> {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        val volume = MediaStore.VOLUME_EXTERNAL
        return listOf(
            MediaStore.Images.Media.getContentUri(volume),
            MediaStore.Video.Media.getContentUri(volume),
            MediaStore.Audio.Media.getContentUri(volume),
        ).flatMap { collection -> query(collection, args) { OptionIds.MEDIA_TRASH } }
    }

    private fun scanDownloads(partial: Boolean, apk: Boolean): List<Finding> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return query(collection, null) { name ->
            when {
                partial && isPartialDownload(name) -> OptionIds.DOWNLOADS_PARTIAL
                apk && isApk(name) -> OptionIds.DOWNLOADS_APK
                else -> null
            }
        }
    }

    @Suppress("DEPRECATION") // DATA is still the only column that gives a full path.
    private fun query(collection: Uri, args: Bundle?, optionFor: (String) -> String?): List<Finding> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATA,
        )
        val cursor = resolver.query(collection, projection, args, null) ?: return emptyList()
        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val volCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val optionId = optionFor(name) ?: continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                    val path = pathOf(c.getString(dataCol), c.getString(volCol), c.getString(relCol), name)
                    add(
                        Finding(
                            id = "$optionId:$uri",
                            optionId = optionId,
                            label = name,
                            sizeBytes = c.getLong(sizeCol).coerceAtLeast(0),
                            operation = Operation.DeleteMedia(uri, path),
                        ),
                    )
                }
            }
        }
    }

    /** The path used for whitelist matching. Null when it cannot be known for sure. */
    @Suppress("DEPRECATION")
    private fun pathOf(data: String?, volume: String?, relative: String?, name: String): String? {
        if (!data.isNullOrBlank()) return data
        if (volume != MediaStore.VOLUME_EXTERNAL_PRIMARY || relative == null) return null
        val root = Environment.getExternalStorageDirectory().absolutePath
        return "$root/${relative.trimEnd('/')}/$name"
    }
}
