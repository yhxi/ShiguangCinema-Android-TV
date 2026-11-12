package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSkipRulesTest {
    @Test fun relativeOutroFollowsEpisodeDuration() {
        assertEquals(570_000L, safeOutroStart(540_000, 30_000, 570_000, 600_000))
    }
    @Test fun unusualDurationDoesNotSkip() {
        assertNull(safeOutroStart(540_000, 30_000, 570_000, 300_000))
    }
    @Test fun oldMarksRemainUsable() {
        assertEquals(540_000L, safeOutroStart(540_000, 0, 0, 600_000))
    }
}
