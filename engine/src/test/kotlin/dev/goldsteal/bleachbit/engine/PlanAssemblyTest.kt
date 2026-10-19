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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanAssemblyTest {
    private val assembly = PlanAssembly()

    @Test
    fun uncheckedAndWhitelistedFindingsStayOutOfThePlan() {
        val cache = finding(
            "cache",
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
        )
        val files = finding(
            "files",
            Operation.DeletePath("/data/user/0/com.foo/files", PathGrant.PackageCache),
        )
        val result = assembly.assemble(
            findings = listOf(cache, files),
            includedIds = setOf("cache", "files"),
            context = context(
                whitelist = Whitelist(pathPrefixes = setOf("/data"), packages = emptySet()),
                lookup = fs(
                    "/data/user/0/com.foo/cache/",
                    "/data/user/0/com.foo/files/",
                ),
            ),
        )
        assertTrue(result.plan.items.isEmpty())
        assertEquals(1, result.policyErrors.size)
        assertEquals("files", result.policyErrors.single().findingId)
    }

    @Test
    fun approvedFindingBecomesOnePlanItem() {
        val cache = finding(
            "cache",
            Operation.DeletePath("/data/user/0/com.foo/cache", PathGrant.PackageCache),
            sizeBytes = 40,
        )
        val result = assembly.assemble(
            findings = listOf(cache),
            includedIds = setOf("cache"),
            context = context(lookup = fs("/data/user/0/com.foo/cache/")),
        )
        assertTrue(result.policyErrors.isEmpty())
        assertEquals(40, result.plan.items.single().sizeBytes)
    }

    private fun finding(id: String, operation: Operation, sizeBytes: Long = 1): Finding {
        return Finding(
            id = id,
            optionId = "apps.cache",
            label = id,
            sizeBytes = sizeBytes,
            operation = operation,
        )
    }
}
