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

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.goldsteal.bleachbit.engine.MediaCollection
import dev.goldsteal.bleachbit.engine.MediaItem
import dev.goldsteal.bleachbit.engine.sha256
import java.io.IOException

/** Lists and reads shared-storage items through MediaStore. It never deletes. */
class MediaIndex(private val context: Context) {
    private val resolver get() = context.contentResolver

    /**
     * Items in [collections] of at least [minBytes]. Pending and trashed items are left out.
     * Throws SecurityException or IllegalArgumentException when a collection cannot be read.
     */
    fun list(collections: Set<MediaCollection>, minBytes: Long): List<MediaItem> =
        collections.sorted().flatMap { query(uriOf(it), minBytes) }

    /** SHA-256 of one item, or null when it cannot be read. [beforeChunk] may throw to cancel. */
    fun hash(item: MediaItem, beforeChunk: () -> Unit): String? = try {
        resolver.openInputStream(Uri.parse(item.uri))?.use { sha256(it, beforeChunk) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun uriOf(collection: MediaCollection): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL
        return when (collection) {
            MediaCollection.IMAGES -> MediaStore.Images.Media.getContentUri(volume)
            MediaCollection.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MediaCollection.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            MediaCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(volume)
        }
    }

    @Suppress("DEPRECATION") // DATA is still the only column that gives a full path.
    private fun query(collection: Uri, minBytes: Long): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATA,
        )
        val selection = "${MediaStore.MediaColumns.SIZE} >= ?"
        val cursor = resolver.query(collection, projection, selection, arrayOf(minBytes.toString()), null)
            ?: return emptyList()
        return cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val volCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val id = c.getLong(idCol)
                    val volume = c.getString(volCol)
                    add(
                        MediaItem(
                            uri = Uri.withAppendedPath(collection, id.toString()).toString(),
                            fileKey = "$volume:$id",
                            path = mediaPathOf(c.getString(dataCol), volume, c.getString(relCol), name),
                            name = name,
                            sizeBytes = c.getLong(sizeCol).coerceAtLeast(0),
                            // DATE_MODIFIED is in seconds.
                            modifiedMillis = c.getLong(modCol) * 1000,
                        ),
                    )
                }
            }
        }
    }
}

/** The path used for whitelist matching. Null when it cannot be known for sure. */
@Suppress("DEPRECATION")
internal fun mediaPathOf(data: String?, volume: String?, relative: String?, name: String): String? {
    if (!data.isNullOrBlank()) return data
    if (volume != MediaStore.VOLUME_EXTERNAL_PRIMARY || relative == null) return null
    val root = Environment.getExternalStorageDirectory().absolutePath
    return "$root/${relative.trimEnd('/')}/$name"
}
