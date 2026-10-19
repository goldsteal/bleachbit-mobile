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
package dev.goldsteal.bleachbit.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goldsteal.bleachbit.engine.Finding
import dev.goldsteal.bleachbit.engine.Operation
import dev.goldsteal.bleachbit.engine.Option
import dev.goldsteal.bleachbit.engine.Privilege
import dev.goldsteal.bleachbit.engine.Risk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val option = Option("test.option", "Test option", Privilege.STANDARD, Risk.SAFE)

    @Test
    fun scanIsDisabledUntilAnOptionIsSelected() {
        rule.setContent {
            CleanScreen(options = listOf(option), selected = emptySet(), onToggle = {}, onScan = {}, onBack = {})
        }
        rule.onNodeWithTag(TAG_SCAN).assertIsNotEnabled()
    }

    @Test
    fun scanIsEnabledWithASelection() {
        rule.setContent {
            CleanScreen(options = listOf(option), selected = setOf(option.id), onToggle = {}, onScan = {}, onBack = {})
        }
        rule.onNodeWithTag(TAG_SCAN).assertIsEnabled()
    }

    @Test
    fun tappingAPreviewRowTogglesIt() {
        val finding = Finding(
            id = "f1",
            optionId = option.id,
            label = "file.apk",
            sizeBytes = 1024,
            operation = Operation.ClearClipboard,
        )
        val toggled = mutableListOf<String>()
        rule.setContent {
            PreviewScreen(
                findings = listOf(finding),
                checked = emptySet(),
                policyErrors = emptyList(),
                notes = emptyList(),
                confirming = false,
                canNeverClean = { false },
                onToggle = { toggled += it },
                onNeverClean = {},
                onClean = {},
                onConfirm = {},
                onDismiss = {},
                onBack = {},
            )
        }
        rule.onNodeWithTag("finding:f1").performClick()
        assertEquals(listOf("f1"), toggled)
        rule.onNodeWithTag(TAG_CLEAN).assertIsNotEnabled()
    }
}
