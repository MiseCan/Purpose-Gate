package com.purposegate

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

internal object BedtimeReminder {
    const val CHANNEL = "bedtime_reminder"
    const val ALARM_ACTION = "com.purposegate.BEDTIME_REMINDER"
    private const val CHECK_ACTION = "com.purposegate.BEDTIME_CHECK"
    private const val NOTIFICATION_ID = 2721
    private const val TEST_NOTIFICATION_ID = 2722

    fun canScheduleExactly(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized
    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        fun pending(action: String) = PendingIntent.getBroadcast(
            context, 0, Intent(context, BedtimeReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )
        val reminder = pending(ALARM_ACTION)
        val check = pending(CHECK_ACTION)
        if (PurposeGateStore.bedtimeEnabledSince(context) == null) {
            alarm.cancel(reminder)
            alarm.cancel(check)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            return
        }

        val now = ZonedDateTime.now()
        val today = now.toLocalDate()
        if (hasUsageStatsAccess(context)) {
            val todayCheck = today.atTime(BEDTIME_CHECK_AT).atZone(now.zone)
            val nextCheck = if (now.isBefore(todayCheck)) todayCheck else today.plusDays(1).atTime(BEDTIME_CHECK_AT).atZone(now.zone)
            alarm.set(AlarmManager.RTC, nextCheck.toInstant().toEpochMilli(), check)
        } else {
            alarm.cancel(check)
        }

        val catchUp = bedtimeConfirmationDate(now) != null && notificationsEnabled(context) &&
            !PurposeGateStore.bedtimeDelivered(context, today.toEpochDay()) &&
            today.toString() !in PurposeGateStore.bedtimeConfirmations(context)
        val trigger = if (catchUp) now.toInstant().toEpochMilli()
            else today.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        if (alarm.canScheduleExactAlarms()) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reminder)
                return
            } catch (_: SecurityException) {
                // Access can be revoked between the check and scheduling.
            }
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reminder)
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "睡前提醒", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun deliver(context: Context) {
        if (!PurposeGateStore.bedtimeEnabled(context)) return
        val now = ZonedDateTime.now()
        val date = bedtimeConfirmationDate(now) ?: return
        val today = date.toEpochDay()
        if (PurposeGateStore.bedtimeDelivered(context, today) ||
            date.toString() in PurposeGateStore.bedtimeConfirmations(context)) return
        val expiresAt = date.atTime(BEDTIME_CONFIRM_UNTIL).atZone(now.zone).toInstant().toEpochMilli()
        if (postNotification(context, NOTIFICATION_ID, "睡前提醒", expiresAt - now.toInstant().toEpochMilli())) {
            PurposeGateStore.markBedtimeDelivered(context, today)
        }
    }

    fun sendTestNotification(context: Context): Boolean =
        postNotification(context, TEST_NOTIFICATION_ID, "睡前提醒 · 测试")

    private fun postNotification(context: Context, id: Int, title: String, timeoutMs: Long = 0): Boolean {
        createChannel(context)
        if (!notificationsEnabled(context)) return false
        val openApp = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_purposegate)
            .setContentTitle(title)
            .setContentText("准备上床了，把手机留在床外吧。")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMs)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        return true
    }
}

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BedtimeReminder.ALARM_ACTION) BedtimeReminder.deliver(context)
        BedtimeReminder.reschedule(context)
        if (!PurposeGateStore.bedtimeEnabled(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BedtimeCheck.settle(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
