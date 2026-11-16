package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaUsageTest {
    @Test fun reopeningKeepsPersistedUsage() { assertEquals(300_000L, quotaUsage(true, 300_000, 0, false, 0)) }
    @Test fun inactiveTimeIsNotCharged() { assertEquals(300_000L, quotaUsage(true, 300_000, 120_000, false, 0)) }
    @Test fun childPlaybackChargesOneInterval() { assertEquals(301_000L, quotaUsage(true, 300_000, 1000, true, 0)) }
    @Test fun onlyPlayingChildContentIsActive() {
        assertTrue(quotaPlaybackActive(true, true))
        assertFalse(quotaPlaybackActive(true, false))
        assertFalse(quotaPlaybackActive(false, true))
        assertFalse(quotaPlaybackActive(false, false))
    }
    @Test fun midnightDiscardsYesterdayAndOnlyChargesToday() { assertEquals(250L, quotaUsage(false, 900_000, 1000, true, 250)) }
    @Test fun nextDayWhileInactiveStartsFresh() { assertEquals(0L, quotaUsage(false, 900_000, 86_400_000, false, 500)) }
    @Test fun elapsedClockResetDoesNotSubtractUsage() { assertEquals(300_000L, quotaUsage(true, 300_000, -100, true, 0)) }
    @Test fun perTitleDetailAccumulatesAndResetsAtMidnight() {
        val details = mutableMapOf("小猪佩奇" to 20_000L)
        addQuotaDetail(details, "小猪佩奇", 15_000L, false)
        addQuotaDetail(details, "海底小纵队", 8_000L, false)
        assertEquals(35_000L, details["小猪佩奇"])
        assertEquals(8_000L, details["海底小纵队"])
        addQuotaDetail(details, "海底小纵队", 1_000L, true)
        assertFalse(details.containsKey("小猪佩奇"))
        assertEquals(1_000L, details["海底小纵队"])
    }
}
