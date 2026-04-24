package com.example.andriod_project.capma.runtime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

class ContextSignalProvider(private val context: Context) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)

    fun isForeground(packageName: String): Boolean {
        return getForegroundPackageName() == packageName
    }

    private fun getForegroundPackageName(): String? {
        if (!hasUsageStatsPermission()) return null
        val manager = usageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - 15_000L, now)
        val event = UsageEvents.Event()
        var foregroundPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.packageName
            }
        }
        return foregroundPackage
    }

    private fun hasUsageStatsPermission(): Boolean {
        val ops = appOpsManager ?: return false
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}
