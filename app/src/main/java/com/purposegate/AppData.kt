@file:OptIn(ExperimentalMaterial3Api::class)

package com.purposegate

/** 页面枚举、已安装应用清单、权限与偏好访问这些数据层代码。 */

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

// STATS remains an alias for HOME so saved state from older versions opens the dashboard.
internal enum class Page { HOME, PAUSE, APPS, CHECKS, SETTINGS, PRESETS, ABOUT, RULES, RULE_EDITOR, STATS, APPEARANCE, BEDTIME, FEATURE_TESTS }


/** Dashboard and focus are peer tabs. STATS is only a legacy saved-state alias. */
internal fun Page.parent(): Page? = when (this) {
    Page.HOME, Page.PAUSE, Page.STATS -> null
    Page.SETTINGS -> Page.HOME
    Page.ABOUT, Page.PRESETS, Page.APPEARANCE, Page.CHECKS, Page.BEDTIME, Page.FEATURE_TESTS -> Page.SETTINGS
    Page.APPS, Page.RULES -> Page.PAUSE
    Page.RULE_EDITOR -> Page.RULES
}


internal enum class AppFilter(val label: String) {
    ALL("全部"),
    SYSTEM("系统"),
    USER("用户"),
    SELECTED("已选中")
}


internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: ImageBitmap,
    val lastUsed: Long
)


internal val sectionSpacing = 12.dp

internal val contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)


internal fun launcherApplications(context: Context): List<ApplicationInfo> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, 0)
    }
    return results.map { it.activityInfo.applicationInfo }
}


internal fun loadInstalledApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val lastUsedTimes = recentUsageTimes(context)
    return launcherApplications(context).asSequence()
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .map {
            InstalledApp(
                packageName = it.packageName,
                label = it.loadLabel(packageManager).toString(),
                isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                // Keep enough source pixels for 48dp icons on high-density screens.
                icon = it.loadIcon(packageManager).toBitmap(192, 192).asImageBitmap(),
                lastUsed = lastUsedTimes[it.packageName] ?: 0L
            )
        }
        .sortedWith(compareByDescending<InstalledApp> { it.lastUsed }.thenBy { it.label.lowercase() })
        .toList()
}


internal fun recentUsageTimes(context: Context): Map<String, Long> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyMap()
    val end = System.currentTimeMillis()
    val start = end - 30L * 24L * 60L * 60L * 1000L
    return runCatching {
        buildMap {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end).forEach { stats ->
                val previous = get(stats.packageName) ?: 0L
                if (stats.lastTimeUsed > previous) put(stats.packageName, stats.lastTimeUsed)
            }
        }
    }.getOrDefault(emptyMap())
}


/** Reads system foreground events and counts only the portion after local midnight. */
internal fun todaySystemUsage(context: Context, now: Long): Map<String, Long>? {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val previousMidnight = day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val events = querySystemUsageEvents(context, previousMidnight, now) ?: return null
    val event = UsageEvents.Event()
    val started = mutableMapOf<String, Long>()
    val active = mutableMapOf<String, MutableSet<String?>>()
    val totals = mutableMapOf<String, Long>()
    fun finish(pkg: String, at: Long) {
        val start = started.remove(pkg) ?: return
        totals[pkg] = totals.getOrDefault(pkg, 0L) + (at - maxOf(start, midnight)).coerceAtLeast(0L)
    }
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val pkg = event.packageName
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg != null) {
                active.getOrPut(pkg) { mutableSetOf() }.add(event.className)
                started.putIfAbsent(pkg, event.timeStamp)
            }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg != null) {
                val activities = active[pkg]
                if (activities != null && activities.remove(event.className) && activities.isEmpty()) {
                    active.remove(pkg)
                    finish(pkg, event.timeStamp)
                }
            }
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> {
                started.keys.toList().forEach { finish(it, event.timeStamp) }
                active.clear()
            }
        }
    }
    started.keys.toList().forEach { finish(it, now) }
    return totals
}

internal fun querySystemUsageEvents(context: Context, start: Long, end: Long): UsageEvents? {
    if (!hasUsageStatsAccess(context)) return null
    return try {
        context.getSystemService(UsageStatsManager::class.java)?.queryEvents(start, end)
    } catch (_: SecurityException) {
        null
    }
}

internal fun hasUsageStatsAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    return mode == AppOpsManager.MODE_ALLOWED
}

internal fun Context.appDisplayName(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

internal fun formatUsageDuration(usedMs: Long, showBothUnits: Boolean = false): String {
    val minutes = usedMs / 60_000L
    return when {
        showBothUnits -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        usedMs in 1L until 60_000L -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60L == 0L -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}

internal fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}




internal fun requestNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        (context as? ComponentActivity)?.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }
}


internal const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4102


internal fun Set<String>.setMembership(value: String, present: Boolean): Set<String> =
    if (present) this + value else this - value


internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, PurposeAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return enabled?.split(':')?.any { it.equals(expected, true) } == true
}
