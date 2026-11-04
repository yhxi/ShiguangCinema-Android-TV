package com.lshai.cloudscreen

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BoundedResponseTest {
    @Test fun acceptsResponseAtLimit() {
        val data = ByteArray(8193) { it.toByte() }
        assertArrayEquals(data, readBounded(ByteArrayInputStream(data), data.size))
    }

    @Test(expected = IOException::class)
    fun rejectsResponseBeyondLimitEvenWithoutContentLength() {
        readBounded(ByteArrayInputStream(ByteArray(8194)), 8193)
    }
}
