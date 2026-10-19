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
package dev.goldsteal.bleachbit.data

import java.io.File

/** Appends run logs to one text file. It lives in noBackupFilesDir so it never leaves the device. */
class RunLogStore(directory: File, private val maxBytes: Int = 256 * 1024) {
    private val file = File(directory, "run-log.txt")

    @Synchronized
    fun append(entry: String) {
        file.appendText(entry + "\n")
        if (file.length() > maxBytes) trim()
    }

    @Synchronized
    fun read(): String = if (file.exists()) file.readText() else ""

    /** Keeps the newest part of the log, starting at a line boundary. */
    private fun trim() {
        val bytes = file.readBytes()
        var start = bytes.size - maxBytes / 2
        while (start < bytes.size && bytes[start - 1] != '\n'.code.toByte()) start++
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }
}
