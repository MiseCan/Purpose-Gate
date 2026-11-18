package com.purposegate

import android.app.usage.UsageEvents.Event
import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal val BEDTIME_CONFIRM_UNTIL: LocalTime = LocalTime.of(0, 30)
internal val BEDTIME_CHECK_AT: LocalTime = LocalTime.of(6, 0)

internal fun bedtimeConfirmationDate(now: ZonedDateTime): LocalDate? =
    now.toLocalDate().takeIf { now.toLocalTime().isBefore(BEDTIME_CONFIRM_UNTIL) }

internal fun latestCompletedBedtime(now: ZonedDateTime): LocalDate =
    now.toLocalDate().minusDays(if (now.toLocalTime().isBefore(BEDTIME_CHECK_AT)) 1 else 0)

internal object BedtimeCheck {
    @Synchronized
    fun settle(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val enabledSince = PurposeGateStore.bedtimeEnabledSince(context) ?: return
        val date = latestCompletedBedtime(now)
        val start = date.atTime(BEDTIME_CONFIRM_UNTIL).atZone(now.zone)
        val end = date.atTime(BEDTIME_CHECK_AT).atZone(now.zone)
        val startMs = start.toInstant().toEpochMilli()
        val endMs = end.toInstant().toEpochMilli()
        if (enabledSince > startMs || now.isBefore(end) ||
            PurposeGateStore.bedtimeEvaluated(context, date) ||
            date.toString() in PurposeGateStore.bedtimeConfirmations(context)
        ) return

        val events = querySystemUsageEvents(context, start.minusDays(1).toInstant().toEpochMilli(), endMs)
            ?: return
        val event = Event()
        val idle = wasPhoneIdle(startMs, endMs, sequence {
            while (events.getNextEvent(event)) yield(event.timeStamp to event.eventType)
        })
        if (ZoneId.systemDefault() == now.zone) {
            PurposeGateStore.finishBedtimeCheck(context, enabledSince, date, idle)
        }
    }
}

/** Require evidence the screen was off at the start. Missing history never counts as inactivity. */
internal fun wasPhoneIdle(start: Long, end: Long, events: Sequence<Pair<Long, Int>>): Boolean {
    var screenOff = false
    for ((time, type) in events) {
        if (time >= end) break
        if (time < start) {
            when (type) {
                Event.SCREEN_NON_INTERACTIVE -> screenOff = true
                Event.SCREEN_INTERACTIVE, Event.KEYGUARD_HIDDEN, Event.USER_INTERACTION,
                Event.DEVICE_STARTUP, Event.DEVICE_SHUTDOWN -> screenOff = false
            }
        } else {
            when (type) {
                Event.SCREEN_INTERACTIVE, Event.KEYGUARD_HIDDEN, Event.USER_INTERACTION,
                Event.DEVICE_STARTUP, Event.DEVICE_SHUTDOWN -> return false
                Event.SCREEN_NON_INTERACTIVE -> if (time == start) screenOff = true
            }
        }
    }
    return screenOff
}
