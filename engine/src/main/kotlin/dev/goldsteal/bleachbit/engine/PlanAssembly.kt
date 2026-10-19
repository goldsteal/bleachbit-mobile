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

class PlanAssembly(
    private val policy: PathPolicy = PathPolicy(),
) {
    fun assemble(
        findings: List<Finding>,
        includedIds: Set<String>,
        context: PolicyContext,
    ): Assembly {
        val items = mutableListOf<PlannedWork>()
        val errors = mutableListOf<PolicyError>()
        for (finding in findings) {
            if (finding.id !in includedIds) continue
            when (val decision = policy.decide(finding.operation, context)) {
                PolicyDecision.Approved -> items += PlannedWork(
                    findingId = finding.id,
                    label = finding.label,
                    sizeBytes = finding.sizeBytes,
                    operation = finding.operation,
                )
                is PolicyDecision.Rejected -> errors += PolicyError(
                    findingId = finding.id,
                    subject = finding.operation.subject(),
                    reason = decision.reason,
                )
                PolicyDecision.Absent, PolicyDecision.Whitelisted -> Unit
            }
        }
        return Assembly(Plan(items), errors)
    }
}
