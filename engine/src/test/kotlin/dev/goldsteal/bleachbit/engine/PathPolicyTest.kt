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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathPolicyTest {
    private val policy = PathPolicy()

    @Test
    fun packageCacheOfInstalledAppIsAllowed() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/cache/")),
        )
        assertTrue(decision is PolicyDecision.Approved)
    }

    @Test
    fun codeCacheAndExternalCacheAreAllowed() {
        val code = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/code_cache", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/code_cache/")),
        )
        val external = policy.decide(
            Operation.DeletePath(
                "/storage/emulated/0/Android/data/com.foo/cache",
                PathGrant.PackageCache,
            ),
            context(lookup = fs("/storage/emulated/0/Android/data/com.foo/cache/")),
        )
        val volume = policy.decide(
            Operation.DeletePath(
                "/storage/ABCD-1234/Android/data/com.foo/code_cache",
                PathGrant.PackageCache,
            ),
            context(lookup = fs("/storage/ABCD-1234/Android/data/com.foo/code_cache/")),
        )
        assertTrue(code is PolicyDecision.Approved)
        assertTrue(external is PolicyDecision.Approved)
        assertTrue(volume is PolicyDecision.Approved)
    }

    @Test
    fun cacheEscapeIntoFilesIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache/../files", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/files/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun appFilesDirectoryIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/files", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/files/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun cacheExtraSuffixIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache-extra", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/cache-extra/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun cacheWithoutAPackageNameIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/cache", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/cache/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun cacheSymlinkIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
            context(lookup = fs("/data/user/0/com.foo/cache@")),
        )
        val rejected = decision as PolicyDecision.Rejected
        assertTrue(rejected.reason.contains("symlink"))
    }

    @Test
    fun cacheSymlinkOnDiskIsRejected(@TempDir root: Path) {
        val pkg = Files.createDirectories(root.resolve("com.foo"))
        val elsewhere = Files.createDirectories(root.resolve("elsewhere"))
        val cache = pkg.resolve("cache")
        Files.createSymbolicLink(cache, elsewhere)
        val decision = policy.decide(
            Operation.DeletePath(cache.toString(), PathGrant.PackageCache),
            context(lookup = NioPathLookup),
        )
        val rejected = decision as PolicyDecision.Rejected
        assertTrue(rejected.reason.contains("symlink"))
    }

    @Test
    fun realDirectoryThatIsNotACachePathIsNotASymlinkFailure(@TempDir root: Path) {
        val cache = Files.createDirectories(root.resolve("com.foo").resolve("cache"))
        val decision = policy.decide(
            Operation.DeletePath(cache.toString(), PathGrant.PackageCache),
            context(lookup = NioPathLookup),
        )
        val rejected = decision as PolicyDecision.Rejected
        assertFalse(rejected.reason.contains("symlink"))
    }

    @Test
    fun parentSymlinkOnDiskIsRejected(@TempDir root: Path) {
        val realPkg = Files.createDirectories(root.resolve("real"))
        Files.createDirectories(realPkg.resolve("cache"))
        val link = root.resolve("com.foo")
        Files.createSymbolicLink(link, realPkg)
        val decision = policy.decide(
            Operation.DeletePath(link.resolve("cache").toString(), PathGrant.PackageCache),
            context(lookup = NioPathLookup),
        )
        val rejected = decision as PolicyDecision.Rejected
        assertTrue(rejected.reason.contains("symlink"))
    }

    @Test
    fun tombstoneChildIsAllowedAndTheDirectoryItselfIsRejected() {
        val child = policy.decide(
            Operation.DeletePath("/data/tombstones/foo", PathGrant.ExactChild),
            context(lookup = fs("/data/tombstones/foo")),
        )
        val self = policy.decide(
            Operation.DeletePath("/data/tombstones", PathGrant.ExactChild),
            context(lookup = fs("/data/tombstones/")),
        )
        assertTrue(child is PolicyDecision.Approved)
        assertTrue(self is PolicyDecision.Rejected)
    }

    @Test
    fun tombstoneEscapeIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/tombstones/../system/foo", PathGrant.ExactChild),
            context(lookup = fs("/data/system/foo")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun externalAppFilesAreRejected() {
        val decision = policy.decide(
            Operation.DeletePath(
                "/storage/emulated/0/Android/data/com.foo/files",
                PathGrant.PackageCache,
            ),
            context(lookup = fs("/storage/emulated/0/Android/data/com.foo/files/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun sharedFileIsAllowedOnlyWhenTheScannerMatchedARegularFile() {
        val path = "/storage/emulated/0/Download/setup.apk"
        val lookup = fs(path)
        val allowed = policy.decide(
            Operation.DeletePath(path, PathGrant.Shared(predicateMatched = true)),
            context(lookup = lookup),
        )
        val unmatched = policy.decide(
            Operation.DeletePath(path, PathGrant.Shared(predicateMatched = false)),
            context(lookup = lookup),
        )
        val directory = policy.decide(
            Operation.DeletePath(
                "/storage/emulated/0/Download",
                PathGrant.Shared(predicateMatched = true),
            ),
            context(lookup = fs("/storage/emulated/0/Download/")),
        )
        assertTrue(allowed is PolicyDecision.Approved)
        assertTrue(unmatched is PolicyDecision.Rejected)
        assertTrue(directory is PolicyDecision.Rejected)
    }

    @Test
    fun sharedDeleteCannotEnterAndroidData() {
        val decision = policy.decide(
            Operation.DeletePath(
                "/storage/emulated/0/Android/data/com.foo/cache/file",
                PathGrant.Shared(predicateMatched = true),
            ),
            context(lookup = fs("/storage/emulated/0/Android/data/com.foo/cache/file")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun mediaUriMustComeFromThisScan() {
        val current = "content://media/external/file/1"
        val stale = "content://media/external/file/2"
        val base = context(scanMediaUris = setOf(current))
        assertTrue(
            policy.decide(Operation.DeleteMedia(current), base) is PolicyDecision.Approved,
        )
        assertTrue(
            policy.decide(Operation.DeleteMedia(stale), base) is PolicyDecision.Rejected,
        )
        assertTrue(
            policy.decide(
                Operation.DeleteMedia("content://downloads/1"),
                base.copy(scanMediaUris = setOf("content://downloads/1")),
            ) is PolicyDecision.Rejected,
        )
        assertTrue(
            policy.decide(
                Operation.DeleteMedia("file:///storage/emulated/0/Download/setup.apk"),
                base,
            ) is PolicyDecision.Rejected,
        )
    }

    @Test
    fun installedPackageIsRejectedAsAResidual() {
        val path = "/data/user/0/com.foo"
        val lookup = fs("$path/")
        val installed = policy.decide(
            Operation.DeletePath(path, PathGrant.UninstalledPackage),
            context(lookup = lookup),
        )
        val gone = policy.decide(
            Operation.DeletePath(path, PathGrant.UninstalledPackage),
            context(installed = emptySet(), lookup = lookup),
        )
        val nested = policy.decide(
            Operation.DeletePath("$path/cache", PathGrant.UninstalledPackage),
            context(installed = emptySet(), lookup = fs("$path/cache/")),
        )
        assertTrue(installed is PolicyDecision.Rejected)
        assertTrue(gone is PolicyDecision.Approved)
        assertTrue(nested is PolicyDecision.Rejected)
    }

    @Test
    fun otherUsersCacheIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
            context(userId = 10, lookup = fs("/data/user/0/com.foo/cache/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun missingPackageIsRejected() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
            context(installed = emptySet(), lookup = fs("/data/user/0/com.foo/cache/")),
        )
        assertTrue(decision is PolicyDecision.Rejected)
    }

    @Test
    fun whitelistDropsAnApprovedPathAndCannotSaveARejectedOne() {
        val cache = Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache)
        val files = Operation.DeletePath("/data/user/0/com.foo/files", PathGrant.PackageCache)
        val lookup = fs("/data/user/0/com.foo/cache/", "/data/user/0/com.foo/files/")
        val listed = context(
            whitelist = Whitelist(pathPrefixes = setOf("/data/user/0/com.foo"), packages = emptySet()),
            lookup = lookup,
        )
        assertTrue(policy.decide(cache, listed) is PolicyDecision.Whitelisted)
        assertTrue(policy.decide(files, listed) is PolicyDecision.Rejected)
    }

    @Test
    fun packageWhitelistDoesNotMatchALongerPackageName() {
        val decision = policy.decide(
            Operation.DeletePath("/data/user/0/com.foobar/cache", PathGrant.PackageCache),
            context(
                installed = setOf("com.foobar"),
                whitelist = Whitelist(emptySet(), setOf("com.foo")),
                lookup = fs("/data/user/0/com.foobar/cache/"),
            ),
        )
        assertTrue(decision is PolicyDecision.Approved)
    }

    @Test
    fun documentMustStayInsideTheGrantedTree() {
        val tree = "primary:Download"
        assertTrue(
            policy.decide(
                Operation.DeleteDocument(tree, "primary:Download/old.apk"),
                context(),
            ) is PolicyDecision.Approved,
        )
        assertTrue(
            policy.decide(Operation.DeleteDocument(tree, tree), context()) is PolicyDecision.Rejected,
        )
        assertTrue(
            policy.decide(
                Operation.DeleteDocument(tree, "primary:Download/../Secret"),
                context(),
            ) is PolicyDecision.Rejected,
        )
    }

    @Test
    fun trimCachesRequiresAPositiveTarget() {
        assertTrue(policy.decide(Operation.TrimCaches(1), context()) is PolicyDecision.Approved)
        assertTrue(policy.decide(Operation.TrimCaches(0), context()) is PolicyDecision.Rejected)
        assertTrue(policy.decide(Operation.TrimCaches(-5), context()) is PolicyDecision.Rejected)
    }

    @Test
    fun browserArtifactMustMatchTheTable() {
        val grant = PathGrant.Browser("com.foo", "cache2/entries/file")
        val path = "/data/user/0/com.foo/cache2/entries/file"
        val allowed = policy.decide(
            Operation.TruncatePath(path, grant),
            context(lookup = fs(path)),
        )
        val escaped = policy.decide(
            Operation.DeletePath(
                "/etc/passwd",
                PathGrant.Browser("com.foo", "../../etc/passwd"),
            ),
            context(lookup = fs("/etc/passwd")),
        )
        assertTrue(allowed is PolicyDecision.Approved)
        assertTrue(escaped is PolicyDecision.Rejected)
    }

    @Test
    fun privilegeNames() {
        assertTrue(Privilege.STANDARD.displayName() == "Standard")
    }
}
