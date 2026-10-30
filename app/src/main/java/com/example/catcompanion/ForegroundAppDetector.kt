package com.example.catcompanion

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/** Requires user-granted usage access. No AccessibilityService and no network. */
internal class ForegroundAppDetector(private val context: Context) {
    private val usageManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastEventTime = 0L
    private var topPackage: String? = null
    private var lastRefreshElapsed = 0L
    private var cachedHomePackages = emptySet<String>()

    private fun homePackages(): Set<String> {
        if (cachedHomePackages.isNotEmpty()) return cachedHomePackages
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val infos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        cachedHomePackages = infos.map { it.activityInfo.packageName }.toSet()
        return cachedHomePackages
    }

    fun isHomeScreen(): Boolean {
        if (!PermissionChecks.usageAllowed(context)) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastRefreshElapsed < 750) return topPackage in homePackages()
        lastRefreshElapsed = nowElapsed
        val now = System.currentTimeMillis()
        // On the first poll, inspect a wider window; later polls only inspect new events.
        val start = if (lastEventTime == 0L) now - 30 * 60_000L else lastEventTime + 1L
        if (start <= now) {
            val events = usageManager.queryEvents(start, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    topPackage = event.packageName
                    lastEventTime = event.timeStamp
                }
            }
        }
        return topPackage in homePackages()
    }
}
