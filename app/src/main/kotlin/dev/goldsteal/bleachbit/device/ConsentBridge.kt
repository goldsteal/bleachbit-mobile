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
package dev.goldsteal.bleachbit.device

import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hands a system consent dialog (such as a MediaStore delete request) to the UI and waits for
 * the answer. The UI launches [pending], calls [launched], then reports the result with [answered].
 */
class ConsentBridge {
    private val _pending = MutableStateFlow<IntentSenderRequest?>(null)
    val pending: StateFlow<IntentSenderRequest?> = _pending.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var answer: CompletableDeferred<Boolean>? = null

    /** Returns true when the user approved. */
    suspend fun ask(sender: IntentSender): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        _pending.value = IntentSenderRequest.Builder(sender).build()
        try {
            deferred.await()
        } finally {
            _pending.value = null
            answer = null
        }
    }

    /** Clears the request so a recreated activity does not show it twice. */
    fun launched() {
        _pending.value = null
    }

    fun answered(approved: Boolean) {
        answer?.complete(approved)
    }
}
