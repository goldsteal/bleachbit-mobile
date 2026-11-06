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

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import dev.goldsteal.bleachbit.engine.AppSize
import java.io.IOException

/** One app in the size report. */
data class AppSizeRow(val size: AppSize, val isSystem: Boolean)

/** Reads Android's per-app storage figures. Needs usage access. It never deletes. */
class AppSizeReader(private val context: Context) {
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Every installed app the stats service will report on. [beforeEach] may throw to cancel. */
    @Suppress("DEPRECATION") // The flags overload needs API 33; flag 0 means the same on every version.
    fun read(beforeEach: () -> Unit): List<AppSizeRow> {
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val user = Process.myUserHandle()
        return pm.getInstalledApplications(0).mapNotNull { info ->
            beforeEach()
            try {
                val s = stats.queryStatsForPackage(info.storageUuid, info.packageName, user)
                AppSizeRow(
                    size = AppSize(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        appBytes = s.appBytes,
                        dataBytes = s.dataBytes,
                        cacheBytes = s.cacheBytes,
                    ),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    companion object {
        /** Opens this app's usage-access switch where the system supports it. */
        fun usageAccessIntent(context: Context): Intent =
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))

        fun usageAccessListIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        fun appInfoIntent(packageName: String): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }
}
