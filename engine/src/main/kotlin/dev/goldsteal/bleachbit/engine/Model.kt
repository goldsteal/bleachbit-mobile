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

enum class Privilege {
    STANDARD,
    USAGE_ACCESS,
    FOLDER_ACCESS,
    ALL_FILES,
    SHIZUKU,
    ROOT,
}

fun Privilege.displayName(): String = when (this) {
    Privilege.STANDARD -> "Standard"
    Privilege.USAGE_ACCESS -> "Usage access"
    Privilege.FOLDER_ACCESS -> "Folder access"
    Privilege.ALL_FILES -> "All files"
    Privilege.SHIZUKU -> "Shizuku"
    Privilege.ROOT -> "Root"
}

enum class Risk {
    SAFE,
    REVIEW,
    EXPERT,
}

data class Option(
    val id: String,
    val label: String,
    val privilege: Privilege,
    val risk: Risk,
    val warning: String? = null,
)

data class Cleaner(
    val id: String,
    val label: String,
    val description: String,
    val options: List<Option>,
)

/** How a path delete is allowed to look. The policy accepts nothing outside the matching shape. */
sealed class PathGrant {
    data object PackageCache : PathGrant()
    data object UninstalledPackage : PathGrant()
    data object ExactChild : PathGrant()
    data class Shared(val predicateMatched: Boolean) : PathGrant()
    data class Browser(val packageName: String, val relativePath: String) : PathGrant()
}

sealed class Operation {
    /** [path] is the file path MediaStore reported at scan time. It is used only for whitelist matching. */
    data class DeleteMedia(val uri: String, val path: String? = null) : Operation()
    data class DeleteDocument(val treeDocumentId: String, val documentId: String) : Operation()
    data class DeletePath(val path: String, val grant: PathGrant) : Operation()
    data class TruncatePath(val path: String, val grant: PathGrant.Browser) : Operation()
    data object ClearClipboard : Operation()
    data class TrimCaches(val targetFreeBytes: Long) : Operation()
    data object ClearLogcat : Operation()
}

fun Operation.subject(): String = when (this) {
    is Operation.DeleteMedia -> uri
    is Operation.DeleteDocument -> documentId
    is Operation.DeletePath -> path
    is Operation.TruncatePath -> path
    is Operation.ClearClipboard -> "clipboard"
    is Operation.TrimCaches -> "trim-caches:$targetFreeBytes"
    is Operation.ClearLogcat -> "logcat"
}

data class Finding(
    val id: String,
    val optionId: String,
    val label: String,
    val sizeBytes: Long,
    val operation: Operation,
)

data class PlannedWork(
    val findingId: String,
    val label: String,
    val sizeBytes: Long,
    val operation: Operation,
)

data class Plan(val items: List<PlannedWork>)

enum class Outcome {
    DELETED,
    SKIPPED,
    ACCESS_DENIED,
    FAILED,
}

data class RowResult(
    val findingId: String,
    val outcome: Outcome,
    val sizeBytes: Long,
    val detail: String? = null,
)

data class Report(
    val rows: List<RowResult>,
    val aborted: Boolean,
    val notRun: Int,
) {
    val bytesFreed: Long
        get() = rows.filter { it.outcome == Outcome.DELETED }.sumOf { it.sizeBytes }
}

data class PolicyError(
    val findingId: String,
    val subject: String,
    val reason: String,
)

data class Assembly(
    val plan: Plan,
    val policyErrors: List<PolicyError>,
)

data class PackageSnapshot(val installed: Set<String>) {
    fun isInstalled(packageName: String): Boolean = packageName in installed
}

data class Whitelist(
    val pathPrefixes: Set<String>,
    val packages: Set<String>,
) {
    fun blocks(path: String, packageName: String?): Boolean {
        if (packageName != null && packageName in packages) return true
        val normalized = lexicalNormalize(path) ?: return false
        return pathPrefixes.any { prefix ->
            val normalizedPrefix = lexicalNormalize(prefix) ?: return@any false
            normalized == normalizedPrefix || normalized.startsWith("$normalizedPrefix/")
        }
    }

    companion object {
        val EMPTY = Whitelist(emptySet(), emptySet())
    }
}
