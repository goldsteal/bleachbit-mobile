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

enum class CommandStatus {
    OK,
    NOT_FOUND,
    ACCESS_DENIED,
    FAILED,

    /** The user said no in a system consent dialog. */
    DECLINED,
}

fun interface CommandRunner {
    fun run(argv: List<String>): CommandStatus
}

fun interface LocalActions {
    fun perform(operation: Operation): CommandStatus
}

/**
 * Argv for a root or shell operation.
 * Shared-storage files and platform actions return null and stay off the shell runner.
 * The returned list is the command. Callers must not join it into a shell string.
 */
fun shellArgv(operation: Operation): List<String>? = when (operation) {
    is Operation.DeletePath -> when (operation.grant) {
        is PathGrant.Shared -> null
        else -> {
            val path = lexicalNormalize(operation.path) ?: return null
            listOf("rm", "-rf", "--", path)
        }
    }
    is Operation.TrimCaches -> listOf("pm", "trim-caches", operation.targetFreeBytes.toString())
    Operation.ClearLogcat -> listOf("logcat", "-c")
    else -> null
}

class CleanExecutor(
    private val policy: PathPolicy = PathPolicy(),
    private val runner: CommandRunner,
    private val local: LocalActions = LocalActions { CommandStatus.OK },
) {
    fun execute(plan: Plan, context: () -> PolicyContext): Report {
        val rows = mutableListOf<RowResult>()
        var seen = 0
        var aborted = false
        for (item in plan.items) {
            seen += 1
            when (val decision = policy.decide(item.operation, context())) {
                is PolicyDecision.Rejected -> {
                    rows += RowResult(item.findingId, Outcome.FAILED, 0, decision.reason)
                    aborted = true
                    break
                }
                PolicyDecision.Absent -> rows += RowResult(item.findingId, Outcome.SKIPPED, 0, "absent")
                PolicyDecision.Whitelisted -> rows += RowResult(
                    item.findingId,
                    Outcome.SKIPPED,
                    0,
                    "whitelisted",
                )
                PolicyDecision.Approved -> rows += resultOf(item, dispatch(item.operation))
            }
        }
        return Report(rows, aborted = aborted, notRun = plan.items.size - seen)
    }

    private fun dispatch(operation: Operation): CommandStatus {
        val argv = shellArgv(operation)
        return if (argv != null) runner.run(argv) else local.perform(operation)
    }

    private fun resultOf(item: PlannedWork, status: CommandStatus): RowResult {
        return when (status) {
            CommandStatus.OK -> RowResult(item.findingId, Outcome.DELETED, item.sizeBytes)
            CommandStatus.NOT_FOUND -> RowResult(item.findingId, Outcome.SKIPPED, 0, "already gone")
            CommandStatus.ACCESS_DENIED -> RowResult(item.findingId, Outcome.ACCESS_DENIED, 0)
            CommandStatus.FAILED -> RowResult(item.findingId, Outcome.FAILED, 0, "command failed")
            CommandStatus.DECLINED -> RowResult(item.findingId, Outcome.SKIPPED, 0, "declined in system dialog")
        }
    }
}
