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

/** Option ids used by the Phase 2 cleaners. */
object OptionIds {
    const val MEDIA_TRASH = "media.trash"
    const val DOWNLOADS_PARTIAL = "downloads.partial"
    const val DOWNLOADS_APK = "downloads.apk"
    const val CLIPBOARD_CURRENT = "clipboard.current"
}

object Catalog {
    val cleaners: List<Cleaner> = listOf(
        Cleaner(
            id = "media",
            label = "Media",
            description = "Photos, videos and audio already in the system trash.",
            options = listOf(
                Option(OptionIds.MEDIA_TRASH, "Trashed media", Privilege.STANDARD, Risk.SAFE),
            ),
        ),
        Cleaner(
            id = "downloads",
            label = "Downloads",
            description = "Leftovers in the Download folder.",
            options = listOf(
                Option(OptionIds.DOWNLOADS_PARTIAL, "Unfinished downloads", Privilege.STANDARD, Risk.SAFE),
                Option(
                    OptionIds.DOWNLOADS_APK,
                    "Installer files (APK)",
                    Privilege.STANDARD,
                    Risk.REVIEW,
                    warning = "You may want to keep an installer to reinstall an app later.",
                ),
            ),
        ),
        Cleaner(
            id = "clipboard",
            label = "Clipboard",
            description = "Whatever was last copied.",
            options = listOf(
                Option(OptionIds.CLIPBOARD_CURRENT, "Current clipboard", Privilege.STANDARD, Risk.SAFE),
            ),
        ),
    )

    val options: List<Option> = cleaners.flatMap { it.options }

    fun option(id: String): Option? = options.firstOrNull { it.id == id }

    /** Options a user at [privilege] can run. Expert options stay hidden unless [showExpert]. */
    fun visibleOptions(privilege: Privilege, showExpert: Boolean): List<Option> = options.filter {
        it.privilege.ordinal <= privilege.ordinal && (showExpert || it.risk != Risk.EXPERT)
    }
}

private val partialSuffixes = listOf(".crdownload", ".partial", ".download", ".tmp")
private val apkSuffixes = listOf(".apk", ".apks", ".xapk")

private fun hasSuffix(name: String, suffixes: List<String>): Boolean {
    val lower = name.lowercase()
    return suffixes.any { lower.endsWith(it) && lower.length > it.length }
}

fun isPartialDownload(displayName: String): Boolean = hasSuffix(displayName, partialSuffixes)

fun isApk(displayName: String): Boolean = hasSuffix(displayName, apkSuffixes)

/** Preview rows start checked only for Safe options. */
fun defaultChecked(finding: Finding): Boolean = Catalog.option(finding.optionId)?.risk == Risk.SAFE

sealed class WhitelistEntry {
    data class Path(val path: String) : WhitelistEntry()
    data class Package(val packageName: String) : WhitelistEntry()
}

/** What "Never clean this" adds for a preview row, or null when the row has no stable place. */
fun whitelistEntryFor(finding: Finding): WhitelistEntry? = when (val op = finding.operation) {
    is Operation.DeleteMedia -> op.path?.let(::lexicalNormalize)?.let { WhitelistEntry.Path(it) }
    is Operation.DeletePath -> when (val grant = op.grant) {
        is PathGrant.Browser -> WhitelistEntry.Package(grant.packageName)
        else -> lexicalNormalize(op.path)?.let { WhitelistEntry.Path(it) }
    }
    is Operation.TruncatePath -> WhitelistEntry.Package(op.grant.packageName)
    else -> null
}

fun Whitelist.plus(entry: WhitelistEntry): Whitelist = when (entry) {
    is WhitelistEntry.Path -> copy(pathPrefixes = pathPrefixes + entry.path)
    is WhitelistEntry.Package -> copy(packages = packages + entry.packageName)
}

fun Whitelist.minus(entry: WhitelistEntry): Whitelist = when (entry) {
    is WhitelistEntry.Path -> copy(pathPrefixes = pathPrefixes - entry.path)
    is WhitelistEntry.Package -> copy(packages = packages - entry.packageName)
}

fun Whitelist.entries(): List<WhitelistEntry> =
    pathPrefixes.sorted().map { WhitelistEntry.Path(it) } +
        packages.sorted().map { WhitelistEntry.Package(it) }

/** One entry per line: `path:<absolute path>` or `package:<name>`. Unknown lines are dropped. */
object WhitelistCodec {
    fun encode(whitelist: Whitelist): String = whitelist.entries().joinToString("\n") {
        when (it) {
            is WhitelistEntry.Path -> "path:${it.path}"
            is WhitelistEntry.Package -> "package:${it.packageName}"
        }
    }

    fun decode(text: String): Whitelist {
        val paths = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        for (line in text.lineSequence()) {
            when {
                line.startsWith("path:") -> lexicalNormalize(line.removePrefix("path:"))?.let { paths += it }
                line.startsWith("package:") -> line.removePrefix("package:").trim()
                    .takeIf { it.isNotEmpty() }?.let { packages += it }
            }
        }
        return Whitelist(paths, packages)
    }
}
