package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaUsageTest {
    @Test fun reopeningKeepsPersistedUsage() { assertEquals(300_000L, quotaUsage(true, 300_000, 0, false, 0)) }
    @Test fun inactiveTimeIsNotCharged() { assertEquals(300_000L, quotaUsage(true, 300_000, 120_000, false, 0)) }
    @Test fun browsingAndPlaybackShareOneInterval() { assertEquals(301_000L, quotaUsage(true, 300_000, 1000, true, 0)) }
    @Test fun midnightDiscardsYesterdayAndOnlyChargesToday() { assertEquals(250L, quotaUsage(false, 900_000, 1000, true, 250)) }
    @Test fun nextDayWhileInactiveStartsFresh() { assertEquals(0L, quotaUsage(false, 900_000, 86_400_000, false, 500)) }
    @Test fun elapsedClockResetDoesNotSubtractUsage() { assertEquals(300_000L, quotaUsage(true, 300_000, -100, true, 0)) }
}
