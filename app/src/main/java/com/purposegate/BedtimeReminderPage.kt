@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.purposegate

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
internal fun BedtimeReminderPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PurposeGateStore.bedtimeEnabled(context)) }
    var canNotify by remember { mutableStateOf(BedtimeReminder.notificationsEnabled(context)) }
    var exact by remember { mutableStateOf(BedtimeReminder.canScheduleExactly(context)) }
    var usageAccess by remember { mutableStateOf(hasUsageStatsAccess(context)) }
    LifecycleResumeEffect(context) {
        canNotify = BedtimeReminder.notificationsEnabled(context)
        exact = BedtimeReminder.canScheduleExactly(context)
        usageAccess = hasUsageStatsAccess(context)
        onPauseOrDispose { }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("睡前提醒") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SwitchRow(Symbols.Timer, "每天提醒", "每天 00:00 提醒把手机留在床外", enabled) {
                    enabled = it
                    PurposeGateStore.setBedtimeEnabled(context, it)
                    if (it) {
                        BedtimeReminder.createChannel(context)
                        requestNotificationPermission(context)
                    }
                    BedtimeReminder.reschedule(context)
                }
            }
            item {
                Text(
                    "00:00–00:30 可在看板手动确认。若未确认，且系统记录显示 00:30–06:00 手机一直未使用，则自动记入月历。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (enabled && !usageAccess) {
                item {
                    EntryCard(Symbols.VerifiedUser, "允许使用情况访问", "自动确认需要读取系统的夜间使用记录", onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    })
                }
            }
            if (enabled && !canNotify) {
                item {
                    EntryCard(Symbols.VerifiedUser, "允许睡前通知", "在系统设置中开启通知", onClick = {
                        BedtimeReminder.createChannel(context)
                        context.openNotificationSettings()
                    })
                }
            }
            if (enabled && !exact) {
                item {
                    EntryCard(Symbols.Timer, "允许准时提醒", "未授权时，提醒可能会延迟", onClick = {
                        context.startActivity(Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)
                        ))
                    })
                }
            }
        }
    }

}
