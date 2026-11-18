package com.purposegate

import android.app.usage.UsageEvents.Event
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeConfirmationTest {
    @Test fun confirmationOpensAtMidnightAndClosesAtHalfPast() {
        val start = ZonedDateTime.parse("2026-12-01T00:00:00+08:00[Asia/Shanghai]")
        assertNull(bedtimeConfirmationDate(start.minusSeconds(1)))
        assertEquals(start.toLocalDate(), bedtimeConfirmationDate(start))
        assertEquals(start.toLocalDate(), bedtimeConfirmationDate(start.plusMinutes(30).minusNanos(1)))
        assertNull(bedtimeConfirmationDate(start.plusMinutes(30)))
    }

    @Test fun currentNightCannotBeSettledBeforeSix() {
        val six = ZonedDateTime.parse("2026-12-01T06:00:00+08:00[Asia/Shanghai]")
        assertEquals(six.toLocalDate().minusDays(1), latestCompletedBedtime(six.minusNanos(1)))
        assertEquals(six.toLocalDate(), latestCompletedBedtime(six))
    }

    @Test fun idleRequiresKnownScreenOffThroughoutTheWindow() {
        val start = 100L
        val end = 200L
        val offBefore = 90L to Event.SCREEN_NON_INTERACTIVE
        assertTrue(wasPhoneIdle(start, end, sequenceOf(offBefore, end to Event.SCREEN_INTERACTIVE)))
        assertFalse(wasPhoneIdle(start, end, emptySequence()))
        assertFalse(wasPhoneIdle(start, end, sequenceOf(101L to Event.SCREEN_NON_INTERACTIVE)))
        assertFalse(wasPhoneIdle(start, end, sequenceOf(offBefore, start to Event.SCREEN_INTERACTIVE)))
        assertFalse(wasPhoneIdle(start, end, sequenceOf(offBefore, 150L to Event.USER_INTERACTION)))
        assertFalse(wasPhoneIdle(start, end, sequenceOf(offBefore, 150L to Event.DEVICE_STARTUP)))
    }
}
