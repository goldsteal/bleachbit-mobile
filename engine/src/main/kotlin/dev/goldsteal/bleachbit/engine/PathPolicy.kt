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

import java.net.URI
import java.net.URISyntaxException

data class PolicyContext(
    val userId: Int,
    val packages: PackageSnapshot,
    val whitelist: Whitelist,
    val scanMediaUris: Set<String>,
    val lookup: PathLookup,
)

sealed class PolicyDecision {
    data object Approved : PolicyDecision()
    data class Rejected(val reason: String) : PolicyDecision()
    data object Absent : PolicyDecision()
    data object Whitelisted : PolicyDecision()
}

/**
 * Decides whether one operation may run.
 * A whitelist hit can only drop an operation the grant already accepted.
 */
class PathPolicy {
    fun decide(operation: Operation, context: PolicyContext): PolicyDecision {
        val granted = when (operation) {
            Operation.ClearClipboard -> PolicyDecision.Approved
            Operation.ClearLogcat -> PolicyDecision.Approved
            is Operation.TrimCaches -> decideTrim(operation)
            is Operation.DeleteMedia -> decideMedia(operation, context)
            is Operation.DeleteDocument -> decideDocument(operation)
            is Operation.DeletePath -> decidePath(operation.path, operation.grant, context)
            is Operation.TruncatePath -> decidePath(operation.path, operation.grant, context)
        }
        if (granted !is PolicyDecision.Approved) return granted
        val place = whitelistPlace(operation) ?: return PolicyDecision.Approved
        return if (context.whitelist.blocks(place.path, place.packageName)) {
            PolicyDecision.Whitelisted
        } else {
            PolicyDecision.Approved
        }
    }

    private fun decideTrim(operation: Operation.TrimCaches): PolicyDecision {
        return if (operation.targetFreeBytes > 0) {
            PolicyDecision.Approved
        } else {
            PolicyDecision.Rejected("trim target must be a positive byte count")
        }
    }

    private fun decideMedia(operation: Operation.DeleteMedia, context: PolicyContext): PolicyDecision {
        val uri = try {
            URI(operation.uri)
        } catch (_: URISyntaxException) {
            return PolicyDecision.Rejected("media uri is not a uri")
        }
        if (uri.scheme != "content" || uri.authority != "media" || uri.userInfo != null) {
            return PolicyDecision.Rejected("media delete requires a content://media uri")
        }
        if (operation.uri !in context.scanMediaUris) {
            return PolicyDecision.Rejected("media uri is not in this scan")
        }
        return PolicyDecision.Approved
    }

    private fun decideDocument(operation: Operation.DeleteDocument): PolicyDecision {
        val tree = operation.treeDocumentId
        val document = operation.documentId
        if (tree.isEmpty() || document.isEmpty() || document.contains('\u0000')) {
            return PolicyDecision.Rejected("document id is empty")
        }
        if (!document.startsWith("$tree/")) {
            return PolicyDecision.Rejected("document is outside the granted folder")
        }
        val rest = document.removePrefix("$tree/")
        val unsafe = rest.split('/').any { segment ->
            segment.isEmpty() || segment == "." || segment == ".."
        }
        return if (unsafe) {
            PolicyDecision.Rejected("document id leaves the granted folder")
        } else {
            PolicyDecision.Approved
        }
    }

    private fun decidePath(path: String, grant: PathGrant, context: PolicyContext): PolicyDecision {
        val normalized = lexicalNormalize(path)
            ?: return PolicyDecision.Rejected("path is empty, relative, or escapes its root")
        return when (val walked = walk(normalized, context.lookup)) {
            Walk.Missing -> PolicyDecision.Absent
            is Walk.Rejected -> PolicyDecision.Rejected(walked.reason)
            is Walk.Ready -> matchGrant(normalized, walked.leaf, grant, context)
        }
    }

    private fun matchGrant(
        path: String,
        leaf: NodeKind,
        grant: PathGrant,
        context: PolicyContext,
    ): PolicyDecision {
        return when (grant) {
            PathGrant.PackageCache -> matchPackageCache(path, leaf, context)
            PathGrant.UninstalledPackage -> matchUninstalled(path, leaf, context)
            PathGrant.ExactChild -> matchExactChild(path, leaf)
            is PathGrant.Shared -> matchShared(path, leaf, grant, context)
            is PathGrant.Browser -> matchBrowser(path, leaf, grant, context)
        }
    }

    private fun matchPackageCache(path: String, leaf: NodeKind, context: PolicyContext): PolicyDecision {
        if (leaf != NodeKind.DIRECTORY) {
            return PolicyDecision.Rejected("package cache path is not a directory")
        }
        val packageName = packageCacheName(path, context.userId)
            ?: return PolicyDecision.Rejected("not a package cache directory")
        if (!context.packages.isInstalled(packageName)) {
            return PolicyDecision.Rejected("package is not installed")
        }
        return PolicyDecision.Approved
    }

    private fun matchUninstalled(path: String, leaf: NodeKind, context: PolicyContext): PolicyDecision {
        if (leaf != NodeKind.DIRECTORY) {
            return PolicyDecision.Rejected("residual path is not a directory")
        }
        val packageName = uninstalledPackageName(path, context.userId)
            ?: return PolicyDecision.Rejected("not an uninstalled package directory")
        if (context.packages.isInstalled(packageName)) {
            return PolicyDecision.Rejected("package is installed")
        }
        return PolicyDecision.Approved
    }

    private fun matchExactChild(path: String, leaf: NodeKind): PolicyDecision {
        if (leaf != NodeKind.DIRECTORY && leaf != NodeKind.REGULAR_FILE) {
            return PolicyDecision.Rejected("fixed-directory child is not a file or directory")
        }
        val inside = fixedChildDirectories.any { root -> path.startsWith("$root/") }
        return if (inside) {
            PolicyDecision.Approved
        } else {
            PolicyDecision.Rejected("path is not inside a fixed directory")
        }
    }

    private fun matchShared(
        path: String,
        leaf: NodeKind,
        grant: PathGrant.Shared,
        context: PolicyContext,
    ): PolicyDecision {
        if (!grant.predicateMatched) {
            return PolicyDecision.Rejected("scanner predicate did not match")
        }
        if (!onSharedStorage(path, context.userId)) {
            return PolicyDecision.Rejected("path is outside shared storage")
        }
        if (underAndroidPrivateTree(path)) {
            return PolicyDecision.Rejected("shared delete cannot enter Android/data or Android/obb")
        }
        if (leaf != NodeKind.REGULAR_FILE) {
            return PolicyDecision.Rejected("shared delete requires a regular file")
        }
        return PolicyDecision.Approved
    }

    private fun matchBrowser(
        path: String,
        leaf: NodeKind,
        grant: PathGrant.Browser,
        context: PolicyContext,
    ): PolicyDecision {
        val expected = browserFilePath(context.userId, grant.packageName, grant.relativePath)
            ?: return PolicyDecision.Rejected("browser artifact is not a single file under the package")
        if (path != expected) {
            return PolicyDecision.Rejected("browser path does not match the artifact table")
        }
        if (leaf != NodeKind.REGULAR_FILE) {
            return PolicyDecision.Rejected("browser artifact is not a regular file")
        }
        return PolicyDecision.Approved
    }

    private data class WhitelistPlace(val path: String, val packageName: String?)

    private fun whitelistPlace(operation: Operation): WhitelistPlace? {
        return when (operation) {
            is Operation.DeletePath -> placeFor(operation.path, operation.grant)
            is Operation.TruncatePath -> placeFor(operation.path, operation.grant)
            is Operation.DeleteMedia -> operation.path?.let { path ->
                lexicalNormalize(path)?.let { WhitelistPlace(it, packageName = null) }
            }
            else -> null
        }
    }

    private fun placeFor(path: String, grant: PathGrant): WhitelistPlace? {
        val normalized = lexicalNormalize(path) ?: return null
        val packageName = when (grant) {
            PathGrant.PackageCache -> packageCacheName(normalized, userId = null)
            PathGrant.UninstalledPackage -> uninstalledPackageName(normalized, userId = null)
            is PathGrant.Browser -> grant.packageName
            PathGrant.ExactChild, is PathGrant.Shared -> null
        }
        return WhitelistPlace(normalized, packageName)
    }
}

private sealed class Walk {
    data object Missing : Walk()
    data class Rejected(val reason: String) : Walk()
    data class Ready(val leaf: NodeKind) : Walk()
}

private fun walk(normalized: String, lookup: PathLookup): Walk {
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return Walk.Rejected("path is the filesystem root")
    var current = ""
    var leaf = NodeKind.DIRECTORY
    for ((index, segment) in segments.withIndex()) {
        current += "/$segment"
        val stat = lookup.lstat(current) ?: return Walk.Missing
        if (stat.kind == NodeKind.SYMLINK) return Walk.Rejected("symlink at $current")
        val isLast = index == segments.lastIndex
        if (!isLast && stat.kind != NodeKind.DIRECTORY) {
            return Walk.Rejected("not a directory: $current")
        }
        if (isLast) leaf = stat.kind
    }
    return Walk.Ready(leaf)
}

private fun packageCacheName(path: String, userId: Int?): String? {
    userCachePattern.matchEntire(path)?.let { match ->
        if (userId != null && match.groupValues[1] != userId.toString()) return null
        return match.groupValues[2]
    }
    emulatedCachePattern.matchEntire(path)?.let { match ->
        if (userId != null && match.groupValues[1] != userId.toString()) return null
        return match.groupValues[2]
    }
    volumeCachePattern.matchEntire(path)?.let { match ->
        return match.groupValues[2]
    }
    return null
}

private fun uninstalledPackageName(path: String, userId: Int?): String? {
    userPackageDirPattern.matchEntire(path)?.let { match ->
        if (userId != null && match.groupValues[1] != userId.toString()) return null
        return match.groupValues[2]
    }
    emulatedPackageDirPattern.matchEntire(path)?.let { match ->
        if (userId != null && match.groupValues[1] != userId.toString()) return null
        return match.groupValues[2]
    }
    volumePackageDirPattern.matchEntire(path)?.let { match ->
        return match.groupValues[2]
    }
    return null
}
