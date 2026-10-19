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
package dev.goldsteal.bleachbit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.goldsteal.bleachbit.ui.AppRoot
import dev.goldsteal.bleachbit.ui.BleachBitTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CleanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BleachBitTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForeground.started = true
    }

    override fun onStop() {
        AppForeground.started = false
        super.onStop()
    }
}
