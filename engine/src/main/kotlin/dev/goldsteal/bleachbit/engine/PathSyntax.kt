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

internal val packageNamePattern =
    Regex("^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

private val packageSegment = "[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+"

internal val userCachePattern =
    Regex("^/data/user/(\\d+)/($packageSegment)/(cache|code_cache)$")

internal val emulatedCachePattern =
    Regex("^/storage/emulated/(\\d+)/Android/data/($packageSegment)/(cache|code_cache)$")

internal val volumeCachePattern =
    Regex("^/storage/([0-9A-F]{4}-[0-9A-F]{4})/Android/data/($packageSegment)/(cache|code_cache)$")

internal val userPackageDirPattern =
    Regex("^/data/user/(\\d+)/($packageSegment)$")

internal val emulatedPackageDirPattern =
    Regex("^/storage/emulated/(\\d+)/Android/data/($packageSegment)$")

internal val volumePackageDirPattern =
    Regex("^/storage/([0-9A-F]{4}-[0-9A-F]{4})/Android/data/($packageSegment)$")

internal val fixedChildDirectories = listOf(
    "/data/tombstones",
    "/data/anr",
    "/data/system/dropbox",
    "/data/local/tmp",
)

/**
 * Collapse `.` and `..` without looking at the filesystem.
 * Returns null for an empty path, a relative path, a NUL, or a `..` that leaves the root.
 */
fun lexicalNormalize(path: String): String? {
    if (path.isEmpty() || path.contains('\u0000') || !path.startsWith("/")) return null
    val kept = ArrayDeque<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (kept.isEmpty()) return null else kept.removeLast()
            else -> kept.addLast(segment)
        }
    }
    if (kept.isEmpty()) return null
    return "/" + kept.joinToString("/")
}

internal fun browserFilePath(userId: Int, packageName: String, relativePath: String): String? {
    if (!packageNamePattern.matches(packageName)) return null
    if (relativePath.isEmpty() || relativePath.startsWith("/") || relativePath.contains('\u0000')) {
        return null
    }
    if (relativePath.any { it == '*' || it == '?' || it == '[' }) return null
    val normalizedRelative = lexicalNormalize("/$relativePath") ?: return null
    val relative = normalizedRelative.removePrefix("/")
    if (relative.isEmpty()) return null
    return "/data/user/$userId/$packageName/$relative"
}

internal fun underAndroidPrivateTree(path: String): Boolean {
    val parts = path.split('/')
    for (index in 0 until parts.lastIndex) {
        if (parts[index] == "Android" && (parts[index + 1] == "data" || parts[index + 1] == "obb")) {
            return true
        }
    }
    return false
}

internal fun onSharedStorage(path: String, userId: Int): Boolean {
    if (path.startsWith("/storage/emulated/$userId/")) return true
    return Regex("^/storage/[0-9A-F]{4}-[0-9A-F]{4}/.+").matches(path)
}
