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

class MapLookup(private val nodes: Map<String, NodeKind>) : PathLookup {
    override fun lstat(path: String): NodeStat? = nodes[path]?.let { NodeStat(it) }
}

/**
 * Each entry is a file path. A trailing `/` is a directory. A trailing `@` is a symlink.
 * Missing parents are filled in as real directories.
 */
fun fs(vararg entries: String): MapLookup {
    val nodes = linkedMapOf<String, NodeKind>()
    for (entry in entries) {
        val (path, kind) = when {
            entry.endsWith("@") -> entry.removeSuffix("@") to NodeKind.SYMLINK
            entry.endsWith("/") -> entry.removeSuffix("/") to NodeKind.DIRECTORY
            else -> entry to NodeKind.REGULAR_FILE
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        var current = ""
        for ((index, part) in parts.withIndex()) {
            current += "/$part"
            if (index == parts.lastIndex) {
                nodes[current] = kind
            } else {
                nodes.putIfAbsent(current, NodeKind.DIRECTORY)
            }
        }
    }
    return MapLookup(nodes)
}

fun context(
    userId: Int = 0,
    installed: Set<String> = setOf("com.foo"),
    whitelist: Whitelist = Whitelist.EMPTY,
    scanMediaUris: Set<String> = emptySet(),
    lookup: PathLookup = MapLookup(emptyMap()),
): PolicyContext = PolicyContext(
    userId = userId,
    packages = PackageSnapshot(installed),
    whitelist = whitelist,
    scanMediaUris = scanMediaUris,
    lookup = lookup,
)
