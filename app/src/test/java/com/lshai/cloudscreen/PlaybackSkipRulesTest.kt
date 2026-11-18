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
        assertNull(safeOutroStart(540_000, 0, 0, 540_000))
    }
    @Test fun outroCanStartInLastFiveSeconds() {
        assertEquals(597_000L, safeOutroStart(567_000, 3_000, 570_000, 600_000))
    }
    @Test fun marksStaySeparateBySeasonAcrossTitleSpellings() {
        assertEquals(skipMarksSeasonKey("示例 第二季"), skipMarksSeasonKey("示例 第2季"))
        assertEquals(skipMarksSeasonKey("Example Season 2"), skipMarksSeasonKey("Example S02"))
        org.junit.Assert.assertNotEquals(skipMarksSeasonKey("Example S01"), skipMarksSeasonKey("Example S02"))
    }
    @Test fun bothMarksRejectSubstantiallyDifferentCuts() {
        assertEquals(45_000L, safeIntroEnd(SkipPoint(45_000, 1_200_000), 1_230_000))
        assertNull(safeIntroEnd(SkipPoint(45_000, 1_200_000), 1_320_000))
        assertNull(safeOutroStart(SkipPoint(1_170_000, 1_200_000), 1_320_000))
    }
    @Test fun adjustmentKeepsTheMarkInsideTheReferenceEpisode() {
        assertEquals(SkipPoint(1_000, 100_000), SkipPoint(6_000, 100_000).adjusted(-5_000))
        assertEquals(SkipPoint(99_999, 100_000), SkipPoint(99_000, 100_000).adjusted(5_000))
    }
    @Test fun outroMovesToNextEpisodeAndWaitsForIntroSkip() {
        assertEquals(NextEpisodeStart(0L, true), nextEpisodeStart(true, 150_000, 5_000, true, 60_000))
        assertEquals(NextEpisodeStart(0L, false), nextEpisodeStart(true, 150_000, 5_000, false, 60_000))
        assertEquals(NextEpisodeStart(150_000L, false), nextEpisodeStart(false, 150_000, 5_000, true, 60_000))
    }
}
