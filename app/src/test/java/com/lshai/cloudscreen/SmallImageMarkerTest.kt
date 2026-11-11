package com.lshai.cloudscreen

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SmallImageMarkerTest {
    @Test fun markerSurvivesNewInstanceAndExpiresForChangedImages() {
        val dir = Files.createTempDirectory("image-marker").toFile()
        try {
            val marker = dir.resolve("cover.small")
            val now = System.currentTimeMillis()
            assertFalse(SmallImageMarker.isFresh(marker, now))
            SmallImageMarker.remember(marker)
            assertTrue(SmallImageMarker.isFresh(java.io.File(marker.path), now + 1000))
            assertFalse(SmallImageMarker.isFresh(java.io.File(marker.path), now + TimeUnit.DAYS.toMillis(8)))
            assertFalse(marker.exists())
        } finally { dir.deleteRecursively() }
    }
}
