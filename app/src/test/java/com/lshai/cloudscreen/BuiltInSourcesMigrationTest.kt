package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test

class BuiltInSourcesMigrationTest {
    @Test fun upgradePreservesDisabledAndCustomSourcesAndDoesNotDuplicate() {
        val current = BuiltInSources.ALL.take(10).map { it.copy(enabled = false) } +
            SourceConfig("自定义", "https://example.com/api") +
            SourceConfig("自定义猫眼", "https://api.maoyanapi.top/api.php/provide/vod/", enabled = false)
        val merged = supplementBuiltInSources(current)
        assertEquals(current, merged.take(current.size))
        assertEquals(current.size + 3, merged.size)
        assertEquals(merged, supplementBuiltInSources(merged))
    }
    @Test fun customOnlyAndEmptyConfigurationsAreNotOptedIntoBuiltIns() {
        val custom = listOf(SourceConfig("自定义", "https://example.com/api"))
        assertEquals(custom, supplementBuiltInSources(custom))
        assertTrue(supplementBuiltInSources(emptyList()).isEmpty())
    }
}
