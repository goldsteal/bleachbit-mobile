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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogTest {
    private val download = "/storage/emulated/0/Download"

    private fun media(id: String, optionId: String, name: String) = Finding(
        id = id,
        optionId = optionId,
        label = name,
        sizeBytes = 10,
        operation = Operation.DeleteMedia("content://media/external/file/$id", "$download/$name"),
    )

    @Test
    fun phaseTwoOptionsAreAllStandard() {
        val ids = Catalog.options.map { it.id }.toSet()
        assertEquals(
            setOf(
                OptionIds.MEDIA_TRASH,
                OptionIds.DOWNLOADS_PARTIAL,
                OptionIds.DOWNLOADS_APK,
                OptionIds.CLIPBOARD_CURRENT,
            ),
            ids,
        )
        assertTrue(Catalog.options.all { it.privilege == Privilege.STANDARD })
        assertEquals(ids.size, Catalog.options.size)
    }

    @Test
    fun expertOptionsHiddenByDefault() {
        assertTrue(Catalog.visibleOptions(Privilege.ROOT, showExpert = false).none { it.risk == Risk.EXPERT })
    }

    @Test
    fun filenamePredicates() {
        assertTrue(isPartialDownload("movie.mp4.crdownload"))
        assertTrue(isPartialDownload("A.PARTIAL"))
        assertTrue(isPartialDownload("x.tmp"))
        assertFalse(isPartialDownload(".tmp"))
        assertFalse(isPartialDownload("tmp.txt"))
        assertTrue(isApk("app.apk"))
        assertTrue(isApk("Bundle.XAPK"))
        assertTrue(isApk("split.apks"))
        assertFalse(isApk("apk.zip"))
        assertFalse(isApk(".apk"))
    }

    @Test
    fun reviewRowsStartUnchecked() {
        assertTrue(defaultChecked(media("1", OptionIds.DOWNLOADS_PARTIAL, "a.crdownload")))
        assertFalse(defaultChecked(media("2", OptionIds.DOWNLOADS_APK, "a.apk")))
        assertFalse(defaultChecked(media("3", "unknown.option", "a")))
    }

    @Test
    fun neverCleanThisDropsTheRowFromTheNextAssembly() {
        val keep = media("1", OptionIds.DOWNLOADS_APK, "keep.apk")
        val other = media("2", OptionIds.DOWNLOADS_APK, "other.apk")
        val entry = whitelistEntryFor(keep)
        assertEquals(WhitelistEntry.Path("$download/keep.apk"), entry)
        val whitelist = Whitelist.EMPTY.plus(entry!!)
        val result = PlanAssembly().assemble(
            findings = listOf(keep, other),
            includedIds = setOf("1", "2"),
            context = context(
                whitelist = whitelist,
                scanMediaUris = setOf(keep.operation.subject(), other.operation.subject()),
            ),
        )
        assertEquals(listOf("2"), result.plan.items.map { it.findingId })
        assertTrue(result.policyErrors.isEmpty())
    }

    @Test
    fun whitelistedFolderCoversMediaInside() {
        val apk = media("1", OptionIds.DOWNLOADS_APK, "sub/x.apk")
        val decision = PathPolicy().decide(
            apk.operation,
            context(
                whitelist = Whitelist(setOf("$download/sub"), emptySet()),
                scanMediaUris = setOf(apk.operation.subject()),
            ),
        )
        assertEquals(PolicyDecision.Whitelisted, decision)
    }

    @Test
    fun clipboardHasNoWhitelistEntry() {
        assertNull(whitelistEntryFor(Finding("c", OptionIds.CLIPBOARD_CURRENT, "Clipboard", 0, Operation.ClearClipboard)))
    }

    @Test
    fun browserRowsWhitelistThePackage() {
        val finding = Finding(
            "b",
            "x",
            "b",
            0,
            Operation.TruncatePath("/data/user/0/org.mozilla.firefox/x", PathGrant.Browser("org.mozilla.firefox", "x")),
        )
        assertEquals(WhitelistEntry.Package("org.mozilla.firefox"), whitelistEntryFor(finding))
    }

    @Test
    fun whitelistCodecRoundTrips() {
        val whitelist = Whitelist(setOf("/storage/emulated/0/Download/keep.apk", "/a/b"), setOf("com.foo"))
        val text = WhitelistCodec.encode(whitelist)
        assertEquals("path:/a/b\npath:/storage/emulated/0/Download/keep.apk\npackage:com.foo", text)
        assertEquals(whitelist, WhitelistCodec.decode(text))
    }

    @Test
    fun whitelistCodecDropsJunk() {
        val decoded = WhitelistCodec.decode("garbage\npath:relative\npath:/x/../y\npackage:\n\npackage:com.bar")
        assertEquals(Whitelist(setOf("/y"), setOf("com.bar")), decoded)
    }

    @Test
    fun removingAnEntry() {
        val whitelist = Whitelist(setOf("/a"), setOf("com.foo"))
        assertEquals(Whitelist(emptySet(), setOf("com.foo")), whitelist.minus(WhitelistEntry.Path("/a")))
    }
}
