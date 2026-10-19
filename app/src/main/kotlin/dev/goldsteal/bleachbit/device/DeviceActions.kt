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

import android.app.RecoverableSecurityException
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.goldsteal.bleachbit.engine.CommandStatus
import dev.goldsteal.bleachbit.engine.LocalActions
import dev.goldsteal.bleachbit.engine.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Runs the operations that need Android APIs rather than a shell. The executor calls [perform]
 * from a background thread, after the policy has approved the operation.
 */
class DeviceActions(
    private val context: Context,
    private val consent: ConsentBridge,
    private val inForeground: () -> Boolean,
) : LocalActions {
    private val resolver get() = context.contentResolver

    /** URI -> whether the user approved the system delete dialog for it. */
    @Volatile
    private var systemAnswer: Map<String, Boolean> = emptyMap()

    /**
     * On Android 11+ asks the system to delete all [uris] behind one consent dialog. Items the
     * request does not cover fall back to a direct delete in [perform].
     */
    suspend fun prepareMediaDeletes(uris: List<String>) {
        systemAnswer = emptyMap()
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val request = try {
            MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse))
        } catch (_: IllegalArgumentException) {
            return
        }
        val approved = consent.ask(request.intentSender)
        systemAnswer = uris.associateWith { approved }
    }

    fun finishRun() {
        systemAnswer = emptyMap()
    }

    override fun perform(operation: Operation): CommandStatus = when (operation) {
        Operation.ClearClipboard -> clearClipboard()
        is Operation.DeleteMedia -> {
            val uri = Uri.parse(operation.uri)
            when (systemAnswer[operation.uri]) {
                true -> if (stillPresent(uri)) CommandStatus.FAILED else CommandStatus.OK
                false -> CommandStatus.DECLINED
                null -> deleteDirect(uri, mayAsk = true)
            }
        }
        // Nothing at Standard privilege produces other local operations yet.
        else -> CommandStatus.FAILED
    }

    private fun clearClipboard(): CommandStatus {
        // Android limits clipboard access to the app in front.
        if (!inForeground()) return CommandStatus.ACCESS_DENIED
        return runBlocking(Dispatchers.Main.immediate) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return@runBlocking CommandStatus.FAILED
            try {
                clipboard.clearPrimaryClip()
                CommandStatus.OK
            } catch (_: SecurityException) {
                CommandStatus.ACCESS_DENIED
            }
        }
    }

    private fun deleteDirect(uri: Uri, mayAsk: Boolean): CommandStatus {
        return try {
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.delete(uri, includeTrashed())
            } else {
                resolver.delete(uri, null, null)
            }
            if (count > 0) CommandStatus.OK else CommandStatus.NOT_FOUND
        } catch (e: RecoverableSecurityException) {
            // Android 10: another app's item. The system can ask the user for this one file.
            if (!mayAsk) return CommandStatus.ACCESS_DENIED
            val approved = runBlocking { consent.ask(e.userAction.actionIntent.intentSender) }
            if (approved) deleteDirect(uri, mayAsk = false) else CommandStatus.DECLINED
        } catch (_: SecurityException) {
            CommandStatus.ACCESS_DENIED
        }
    }

    private fun stillPresent(uri: Uri): Boolean {
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) includeTrashed() else null
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count > 0 } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun includeTrashed(): Bundle = Bundle().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
    }
}
