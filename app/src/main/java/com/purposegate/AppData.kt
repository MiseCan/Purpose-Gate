@file:OptIn(ExperimentalMaterial3Api::class)

package com.purposegate

/** 页面枚举、已安装应用清单、权限与偏好访问这些数据层代码。 */

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Page { HOME, PAUSE, APPS, CHECKS, SETTINGS, PRESETS, ABOUT, RULES, STATS, APPEARANCE }


/** The three navigation tabs are peers: only child pages have an in-app parent. */
internal fun Page.parent(): Page? = when (this) {
    Page.HOME, Page.PAUSE, Page.STATS -> null
    Page.SETTINGS -> Page.HOME
    Page.ABOUT, Page.PRESETS, Page.APPEARANCE, Page.CHECKS -> Page.SETTINGS
    Page.APPS, Page.RULES -> Page.PAUSE
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


internal fun hasUsageStatsAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
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
