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

package dev.goldsteal.bleachbit.engine

import java.io.InputStream
import java.security.MessageDigest

/** One item a MediaStore query returned. */
data class MediaItem(
    val uri: String,
    /** Identifies the file across collections, such as `<volume>:<_ID>`. */
    val fileKey: String,
    /** The file path when MediaStore reports it. Used for the whitelist and to spot repeats. */
    val path: String?,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/** The MediaStore collections the duplicate scan can read. */
enum class MediaCollection { IMAGES, VIDEO, AUDIO, DOWNLOADS }

val DEFAULT_DUPLICATE_COLLECTIONS: Set<MediaCollection> = setOf(MediaCollection.IMAGES, MediaCollection.DOWNLOADS)

object MediaCollectionCodec {
    fun encode(collections: Set<MediaCollection>): String = collections.sorted().joinToString(",") { it.name }

    /** Unknown names are dropped. */
    fun decode(text: String): Set<MediaCollection> = text.split(',')
        .mapNotNull { name -> MediaCollection.entries.firstOrNull { it.name == name.trim() } }
        .toSet()
}

/**
 * Keeps the first of each file. A photo in Download/ is listed by both the Images and the
 * Downloads collection under different URIs; treating those as two copies would delete the only one.
 */
fun distinctFiles(items: List<MediaItem>): List<MediaItem> {
    val keys = HashSet<String>()
    val paths = HashSet<String>()
    return items.filter { item ->
        val path = item.path?.let(::lexicalNormalize)
        val newKey = keys.add(item.fileKey)
        val newPath = path == null || paths.add(path)
        newKey && newPath
    }
}

fun MediaItem.toFinding(optionId: String): Finding = Finding(
    id = "$optionId:$uri",
    optionId = optionId,
    label = name,
    sizeBytes = sizeBytes,
    operation = Operation.DeleteMedia(uri, path),
)

private const val MB = 1_000_000L

const val DEFAULT_LARGE_FILE_BYTES: Long = 100 * MB

/** The thresholds the settings screen offers. */
val LARGE_FILE_THRESHOLDS: List<Long> = listOf(10 * MB, 50 * MB, 100 * MB, 250 * MB, 500 * MB, 1000 * MB)

/** Files at or above [thresholdBytes], largest first. */
fun largeFileFindings(items: List<MediaItem>, thresholdBytes: Long): List<Finding> {
    if (thresholdBytes <= 0) return emptyList()
    return distinctFiles(items)
        .filter { it.sizeBytes >= thresholdBytes }
        .sortedWith(compareByDescending<MediaItem> { it.sizeBytes }.thenBy { it.uri })
        .map { it.toFinding(OptionIds.STORAGE_LARGE_FILES) }
}

/** Files with the same size and SHA-256. [copies] is newest first. */
data class DuplicateGroup(
    val sizeBytes: Long,
    val sha256: String,
    val copies: List<MediaItem>,
) {
    val findingIds: List<String> get() = copies.map { findingIdOf(it) }

    companion object {
        fun findingIdOf(item: MediaItem): String = "${OptionIds.STORAGE_DUPLICATES}:${item.uri}"
    }
}

object Duplicates {
    private val newestFirst = compareByDescending<MediaItem> { it.modifiedMillis }.thenBy { it.uri }

    /**
     * Groups by size, then hashes only sizes shared by two or more files. [hash] returns null for a
     * file it could not read; that file is left out. [hash] may throw to cancel.
     * Groups come back with the most reclaimable bytes first.
     */
    fun find(
        items: List<MediaItem>,
        hash: (MediaItem) -> String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DuplicateGroup> {
        val sameSize = distinctFiles(items)
            .filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size >= 2 }
        val total = sameSize.sumOf { it.size }
        var done = 0
        onProgress(done, total)
        val groups = mutableListOf<DuplicateGroup>()
        for (candidates in sameSize) {
            val byHash = LinkedHashMap<String, MutableList<MediaItem>>()
            for (item in candidates) {
                val digest = hash(item)
                done += 1
                onProgress(done, total)
                if (digest != null) byHash.getOrPut(digest) { mutableListOf() } += item
            }
            for ((digest, copies) in byHash) {
                if (copies.size >= 2) groups += DuplicateGroup(copies[0].sizeBytes, digest, copies.sortedWith(newestFirst))
            }
        }
        return groups.sortedWith(
            compareByDescending<DuplicateGroup> { it.sizeBytes * (it.copies.size - 1) }.thenBy { it.sha256 },
        )
    }

    fun findings(groups: List<DuplicateGroup>): List<Finding> = groups.flatMap { group ->
        group.copies.map { it.toFinding(OptionIds.STORAGE_DUPLICATES) }
    }

    /** Marks every copy except the newest. Only ids in [markable] can be marked. */
    fun defaultMarks(groups: List<DuplicateGroup>, markable: Set<String>): Set<String> = groups.flatMap { group ->
        group.findingIds.drop(1).filter { it in markable }
    }.toSet()

    /**
     * Flips one copy. Marking is refused when it would leave its group with no unmarked copy,
     * or when [findingId] is not in [markable].
     */
    fun toggle(groups: List<DuplicateGroup>, marked: Set<String>, markable: Set<String>, findingId: String): Set<String> {
        if (findingId in marked) return marked - findingId
        if (findingId !in markable) return marked
        val group = groups.firstOrNull { findingId in it.findingIds } ?: return marked
        val after = marked + findingId
        return if (group.findingIds.any { it !in after }) after else marked
    }

    /** True when [marked] only names copies from [groups] and every group keeps at least one copy. */
    fun keepsACopyOfEach(groups: List<DuplicateGroup>, marked: Set<String>): Boolean {
        val known = groups.flatMap { it.findingIds }.toSet()
        if (!known.containsAll(marked)) return false
        return groups.all { group -> group.findingIds.any { it !in marked } }
    }
}

/** Lowercase hex SHA-256 of everything [input] yields. The caller closes the stream. */
fun sha256(input: InputStream, beforeChunk: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        beforeChunk()
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Android's storage figures for one app. Data includes cache, as `StorageStats` reports it. */
data class AppSize(
    val packageName: String,
    val label: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    /** Data that is not cache: logins, databases, settings, downloads inside the app. */
    val dataWithoutCacheBytes: Long get() = (dataBytes - cacheBytes).coerceAtLeast(0)
}

fun sortedByCache(apps: List<AppSize>): List<AppSize> = apps.sortedWith(
    compareByDescending<AppSize> { it.cacheBytes }
        .thenBy { it.label.lowercase() }
        .thenBy { it.packageName },
)
