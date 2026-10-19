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
import org.junit.jupiter.api.Test

class RunLogTest {
    @Test
    fun formatsPolicyErrorsRowsAndTotals() {
        val finding = Finding("1", OptionIds.DOWNLOADS_APK, "a.apk", 5, Operation.DeleteMedia("content://media/external/file/1"))
        val assembly = Assembly(
            Plan(listOf(PlannedWork("1", "a.apk", 5, finding.operation))),
            listOf(PolicyError("2", "/bad", "outside grant")),
        )
        val report = Report(listOf(RowResult("1", Outcome.DELETED, 5)), aborted = false, notRun = 0)
        val text = RunLog.format("2026-10-19T10:00", listOf(finding), assembly, report)
        assertEquals(
            """
            BleachBit Mobile run 2026-10-19T10:00
            Planned: 1 items, 5 bytes
            POLICY 2 /bad: outside grant
            DELETED 5 content://media/external/file/1
            Freed: 5 bytes

            """.trimIndent(),
            text,
        )
    }
}
