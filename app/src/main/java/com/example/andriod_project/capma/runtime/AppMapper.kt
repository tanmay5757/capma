package com.example.andriod_project.capma.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.andriod_project.capma.model.AppCategory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val packageName: String,
    val appLabel: String,
    val category: AppCategory
)

class AppMapper(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager
    private val uidCache = ConcurrentHashMap<Int, AppInfo>()
    private val categoryOverrides = mapOf(
        "com.google.android.apps.maps" to AppCategory.MAPS,
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.whatsapp" to AppCategory.SOCIAL
    )
    private val tag = "CAPMA_SIGNAL"

    fun fromUid(uid: Int): AppInfo? {
        return runCatching {
            uidCache[uid]?.let { return it }
            val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull()
            if (packageName == null) {
                CapmaRuntimeBus.updateDebugStats { it.copy(uidMappingFailures = it.uidMappingFailures + 1) }
                Log.w(tag, "uid_mapping_failed uid=$uid")
                return null
            }
            val label = runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrElse { packageName }
            AppInfo(packageName, label, resolveCategory(packageName)).also {
                uidCache[uid] = it
            }
        }.getOrElse {
            CapmaRuntimeBus.updateDebugStats { stats -> stats.copy(uidMappingFailures = stats.uidMappingFailures + 1) }
            Log.w(tag, "uid_mapping_exception uid=$uid")
            null
        }
    }

    private fun resolveCategory(packageName: String): AppCategory {
        categoryOverrides[packageName]?.let { return it }
        val pkg = packageName.lowercase(Locale.US)
        return when {
            pkg.contains("map") || pkg.contains("geo") -> AppCategory.MAPS
            pkg.contains("chat") || pkg.contains("social") || pkg.contains("insta") -> AppCategory.SOCIAL
            pkg.contains("game") -> AppCategory.GAME
            pkg.contains("tool") || pkg.contains("util") -> AppCategory.UTILITY
            else -> AppCategory.UTILITY
        }
    }
}
