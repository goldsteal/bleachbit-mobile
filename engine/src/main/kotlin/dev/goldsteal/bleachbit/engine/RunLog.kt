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

/** A plain-text record of one clean run. The app keeps it out of backups. */
object RunLog {
    fun format(
        startedAt: String,
        findings: List<Finding>,
        assembly: Assembly,
        report: Report?,
    ): String {
        val byId = findings.associateBy { it.id }
        val lines = mutableListOf<String>()
        lines += "BleachBit Mobile run $startedAt"
        lines += "Planned: ${assembly.plan.items.size} items, ${assembly.plan.items.sumOf { it.sizeBytes }} bytes"
        for (error in assembly.policyErrors) {
            lines += "POLICY ${error.findingId} ${error.subject}: ${error.reason}"
        }
        if (report == null) {
            lines += "Not run"
            return lines.joinToString("\n", postfix = "\n")
        }
        for (row in report.rows) {
            val subject = byId[row.findingId]?.operation?.subject() ?: row.findingId
            val detail = row.detail?.let { " ($it)" } ?: ""
            lines += "${row.outcome} ${row.sizeBytes} $subject$detail"
        }
        if (report.aborted) lines += "ABORTED, ${report.notRun} items not run"
        lines += "Freed: ${report.bytesFreed} bytes"
        return lines.joinToString("\n", postfix = "\n")
    }
}
