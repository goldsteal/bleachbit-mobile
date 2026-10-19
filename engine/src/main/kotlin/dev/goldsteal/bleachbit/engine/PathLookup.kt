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

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

enum class NodeKind {
    REGULAR_FILE,
    DIRECTORY,
    SYMLINK,
    OTHER,
}

data class NodeStat(val kind: NodeKind)

/** lstat. A symlink is reported as [NodeKind.SYMLINK] and is not followed. */
fun interface PathLookup {
    fun lstat(path: String): NodeStat?
}

object NioPathLookup : PathLookup {
    override fun lstat(path: String): NodeStat? {
        val candidate = try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            return null
        }
        return try {
            if (Files.isSymbolicLink(candidate)) return NodeStat(NodeKind.SYMLINK)
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null
            val kind = when {
                Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) -> NodeKind.DIRECTORY
                Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) -> NodeKind.REGULAR_FILE
                else -> NodeKind.OTHER
            }
            NodeStat(kind)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
