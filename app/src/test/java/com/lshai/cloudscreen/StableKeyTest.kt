package com.lshai.cloudscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableKeyTest {
    @Test fun knownDigestAndLegacyCollision() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", stableDigest(""))
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(stableDigest("Aa"), stableDigest("BB"))
    }
}
