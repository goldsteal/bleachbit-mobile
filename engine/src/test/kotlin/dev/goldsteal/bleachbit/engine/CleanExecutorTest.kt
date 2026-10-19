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

class CleanExecutorTest {
    @Test
    fun policyRejectionRunsNoCommandAndAbortsTheRest() {
        val runner = ScriptedRunner(List(3) { CommandStatus.OK })
        val nasty = "/data/tombstones/report;rm -rf x"
        val plan = Plan(
            listOf(
                work("ok", nasty, 10),
                work("bad", "/data/user/0/com.foo/files", 20, PathGrant.PackageCache),
                work("later", "/data/tombstones/next", 30),
            ),
        )
        val lookup = fs(nasty, "/data/user/0/com.foo/files/", "/data/tombstones/next")
        val report = CleanExecutor(runner = runner).execute(plan) { context(lookup = lookup) }
        assertEquals(listOf("rm", "-rf", "--", nasty), runner.calls.single())
        assertEquals(4, runner.calls.single().size)
        assertTrue(report.aborted)
        assertEquals(1, report.notRun)
        assertEquals(10, report.bytesFreed)
        assertEquals(Outcome.FAILED, report.rows[1].outcome)
    }

    @Test
    fun accessDeniedContinuesAndAlreadyGoneAddsNoBytes() {
        val runner = ScriptedRunner(listOf(CommandStatus.ACCESS_DENIED, CommandStatus.NOT_FOUND))
        val plan = Plan(
            listOf(
                work("denied", "/data/tombstones/a", 5),
                work("gone", "/data/tombstones/b", 9),
            ),
        )
        val report = CleanExecutor(runner = runner).execute(plan) {
            context(lookup = fs("/data/tombstones/a", "/data/tombstones/b"))
        }
        assertEquals(2, runner.calls.size)
        assertEquals(Outcome.ACCESS_DENIED, report.rows[0].outcome)
        assertEquals(Outcome.SKIPPED, report.rows[1].outcome)
        assertEquals(0, report.bytesFreed)
        assertTrue(!report.aborted)
    }

    @Test
    fun clipboardAndTrimDoNotBecomeAShellString() {
        val runner = ScriptedRunner(listOf(CommandStatus.OK))
        val local = RecordingLocal()
        val plan = Plan(
            listOf(
                PlannedWork("clip", "Clipboard", 0, Operation.ClearClipboard),
                PlannedWork("trim", "Trim", 0, Operation.TrimCaches(2048)),
            ),
        )
        val report = CleanExecutor(runner = runner, local = local).execute(plan) { context() }
        assertEquals(listOf(Operation.ClearClipboard), local.calls)
        assertEquals(listOf("pm", "trim-caches", "2048"), runner.calls.single())
        assertEquals(0, report.bytesFreed)
        assertTrue(!report.aborted)
    }

    @Test
    fun declinedMediaDeleteIsSkippedAndTheRunContinues() {
        val uris = listOf("content://media/external/file/1", "content://media/external/file/2")
        var calls = 0
        val local = LocalActions { if (calls++ == 0) CommandStatus.DECLINED else CommandStatus.OK }
        val plan = Plan(uris.mapIndexed { i, uri -> PlannedWork("m$i", "m$i", 7, Operation.DeleteMedia(uri)) })
        val report = CleanExecutor(runner = ScriptedRunner(emptyList()), local = local)
            .execute(plan) { context(scanMediaUris = uris.toSet()) }
        assertEquals(listOf(Outcome.SKIPPED, Outcome.DELETED), report.rows.map { it.outcome })
        assertEquals("declined in system dialog", report.rows[0].detail)
        assertEquals(7, report.bytesFreed)
    }

    private fun work(
        id: String,
        path: String,
        size: Long,
        grant: PathGrant = PathGrant.ExactChild,
    ): PlannedWork {
        return PlannedWork(id, id, size, Operation.DeletePath(path, grant))
    }
}

private class ScriptedRunner(private val statuses: List<CommandStatus>) : CommandRunner {
    val calls = mutableListOf<List<String>>()

    override fun run(argv: List<String>): CommandStatus {
        calls += argv
        return statuses[calls.lastIndex]
    }
}

private class RecordingLocal : LocalActions {
    val calls = mutableListOf<Operation>()

    override fun perform(operation: Operation): CommandStatus {
        calls += operation
        return CommandStatus.OK
    }
}
