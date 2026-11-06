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

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsightTest {
    private fun item(
        id: Int,
        size: Long,
        modified: Long = id.toLong(),
        collection: String = "images",
        path: String? = "/storage/emulated/0/DCIM/$id.jpg",
    ) = MediaItem(
        uri = "content://media/external/$collection/media/$id",
        fileKey = "external_primary:$id",
        path = path,
        name = "$id.jpg",
        sizeBytes = size,
        modifiedMillis = modified,
    )

    /** Hash by a fixed content map, so tests choose which files are identical. */
    private fun hashes(vararg pairs: Pair<Int, String>): (MediaItem) -> String? {
        val map = pairs.toMap()
        return { map[it.name.removeSuffix(".jpg").toInt()] }
    }

    @Test
    fun toolsAreNotOnTheCleanList() {
        val visible = Catalog.visibleOptions(Privilege.ROOT, showExpert = true).map { it.id }
        assertFalse(OptionIds.STORAGE_LARGE_FILES in visible)
        assertFalse(OptionIds.STORAGE_DUPLICATES in visible)
        assertFalse(OptionIds.APPS_CACHE_REPORT in visible)
    }

    @Test
    fun largeFilesAndDuplicatesStartUnchecked() {
        val large = largeFileFindings(listOf(item(1, 200)), thresholdBytes = 100).single()
        assertFalse(defaultChecked(large))
        val copy = item(2, 5).toFinding(OptionIds.STORAGE_DUPLICATES)
        assertFalse(defaultChecked(copy))
    }

    @Test
    fun largeFilesAtOrAboveTheThresholdLargestFirst() {
        val items = listOf(item(1, 99), item(2, 100), item(3, 300))
        val found = largeFileFindings(items, thresholdBytes = 100)
        assertEquals(listOf("3.jpg", "2.jpg"), found.map { it.label })
        assertEquals(
            Operation.DeleteMedia("content://media/external/images/media/3", "/storage/emulated/0/DCIM/3.jpg"),
            found[0].operation,
        )
    }

    @Test
    fun nonPositiveThresholdFindsNothing() {
        assertTrue(largeFileFindings(listOf(item(1, 5)), thresholdBytes = 0).isEmpty())
    }

    @Test
    fun sameFileFromTwoCollectionsIsOneFile() {
        val asImage = item(7, 50, collection = "images")
        val asDownload = item(7, 50, collection = "downloads")
        assertEquals(listOf(asImage), distinctFiles(listOf(asImage, asDownload)))
        assertEquals(1, largeFileFindings(listOf(asImage, asDownload), 10).size)
    }

    @Test
    fun samePathUnderAnotherKeyIsOneFile() {
        val a = item(1, 50, path = "/storage/emulated/0/Download/x.jpg")
        val b = item(2, 50, path = "/storage/emulated/0/Download/./x.jpg")
        assertEquals(listOf(a), distinctFiles(listOf(a, b)))
    }

    @Test
    fun aFileSeenTwiceIsNeverItsOwnDuplicate() {
        val asImage = item(7, 50, collection = "images")
        val asDownload = item(7, 50, collection = "downloads")
        val groups = Duplicates.find(listOf(asImage, asDownload), hash = { "same" })
        assertTrue(groups.isEmpty())
    }

    @Test
    fun onlySharedSizesAreHashed() {
        val hashed = mutableListOf<String>()
        val items = listOf(item(1, 10), item(2, 10), item(3, 20))
        Duplicates.find(items, hash = { hashed += it.name; "h" })
        assertEquals(listOf("1.jpg", "2.jpg"), hashed)
    }

    @Test
    fun sameSizeDifferentContentIsNotAGroup() {
        val items = listOf(item(1, 10), item(2, 10))
        assertTrue(Duplicates.find(items, hashes(1 to "a", 2 to "b")).isEmpty())
    }

    @Test
    fun emptyFilesAreIgnored() {
        assertTrue(Duplicates.find(listOf(item(1, 0), item(2, 0)), hash = { "h" }).isEmpty())
    }

    @Test
    fun unreadableFilesAreLeftOut() {
        val items = listOf(item(1, 10), item(2, 10), item(3, 10))
        val group = Duplicates.find(items, hashes(1 to "a", 2 to "a")).single()
        assertEquals(listOf("2.jpg", "1.jpg"), group.copies.map { it.name })
    }

    @Test
    fun groupsAreNewestFirstAndLargestSavingsFirst() {
        val items = listOf(
            item(1, 10, modified = 100),
            item(2, 10, modified = 300),
            item(3, 10, modified = 200),
            item(4, 25),
            item(5, 25),
        )
        val groups = Duplicates.find(items, hashes(1 to "a", 2 to "a", 3 to "a", 4 to "b", 5 to "b"))
        assertEquals(listOf(25L, 10L), groups.map { it.sizeBytes })
        assertEquals(listOf("2.jpg", "3.jpg", "1.jpg"), groups[1].copies.map { it.name })
    }

    @Test
    fun progressCountsEveryHashedFile() {
        val seen = mutableListOf<Pair<Int, Int>>()
        Duplicates.find(listOf(item(1, 10), item(2, 10)), hash = { "h" }) { done, total -> seen += done to total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }

    @Test
    fun aThrowingHashCancelsTheScan() {
        class Stop : RuntimeException()
        assertThrows(Stop::class.java) {
            Duplicates.find(listOf(item(1, 10), item(2, 10)), hash = { throw Stop() })
        }
    }

    private fun threeCopies(): List<DuplicateGroup> = Duplicates.find(
        listOf(item(1, 10, modified = 1), item(2, 10, modified = 2), item(3, 10, modified = 3)),
        hash = { "h" },
    )

    @Test
    fun defaultKeepsTheNewest() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        val marks = Duplicates.defaultMarks(groups, ids.toSet())
        assertEquals(setOf(ids[1], ids[2]), marks)
        assertTrue(Duplicates.keepsACopyOfEach(groups, marks))
    }

    @Test
    fun theLastUnmarkedCopyCannotBeMarked() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        val all = ids.toSet()
        val marks = Duplicates.defaultMarks(groups, all)
        assertEquals(marks, Duplicates.toggle(groups, marks, all, ids[0]))
    }

    @Test
    fun markingEveryCopyOneByOneStopsAtTheLast() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        var marks = emptySet<String>()
        for (id in ids) marks = Duplicates.toggle(groups, marks, ids.toSet(), id)
        assertEquals(2, marks.size)
        assertTrue(Duplicates.keepsACopyOfEach(groups, marks))
    }

    @Test
    fun swappingWhichCopyIsKept() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        val all = ids.toSet()
        var marks = Duplicates.defaultMarks(groups, all)
        marks = Duplicates.toggle(groups, marks, all, ids[2])
        marks = Duplicates.toggle(groups, marks, all, ids[0])
        assertEquals(setOf(ids[0], ids[1]), marks)
    }

    @Test
    fun protectedCopiesCannotBeMarkedAndCountAsKept() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        val markable = setOf(ids[0], ids[1])
        val marks = Duplicates.defaultMarks(groups, markable)
        assertEquals(setOf(ids[1]), marks)
        assertEquals(marks, Duplicates.toggle(groups, marks, markable, ids[2]))
        val both = Duplicates.toggle(groups, marks, markable, ids[0])
        assertEquals(setOf(ids[0], ids[1]), both)
        assertTrue(Duplicates.keepsACopyOfEach(groups, both))
    }

    @Test
    fun keepCheckRejectsEmptiedGroupsAndStrangers() {
        val groups = threeCopies()
        val ids = groups.single().findingIds
        assertFalse(Duplicates.keepsACopyOfEach(groups, ids.toSet()))
        assertFalse(Duplicates.keepsACopyOfEach(groups, setOf("storage.duplicates:content://media/external/images/media/99")))
    }

    @Test
    fun sha256OfKnownInput() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun collectionCodecRoundTripsAndDropsJunk() {
        val text = MediaCollectionCodec.encode(DEFAULT_DUPLICATE_COLLECTIONS)
        assertEquals("IMAGES,DOWNLOADS", text)
        assertEquals(DEFAULT_DUPLICATE_COLLECTIONS, MediaCollectionCodec.decode(text))
        assertEquals(setOf(MediaCollection.AUDIO), MediaCollectionCodec.decode("AUDIO, junk,"))
    }

    @Test
    fun appSizesSortByCacheAndKeepDataApart() {
        val apps = listOf(
            AppSize("com.b", "Beta", appBytes = 1, dataBytes = 100, cacheBytes = 40),
            AppSize("com.a", "alpha", appBytes = 1, dataBytes = 10, cacheBytes = 40),
            AppSize("com.c", "Gamma", appBytes = 1, dataBytes = 5, cacheBytes = 90),
        )
        assertEquals(listOf("com.c", "com.a", "com.b"), sortedByCache(apps).map { it.packageName })
        assertEquals(60, apps[0].dataWithoutCacheBytes)
        assertEquals(0, AppSize("x.y", "x", 0, dataBytes = 5, cacheBytes = 9).dataWithoutCacheBytes)
    }
}
