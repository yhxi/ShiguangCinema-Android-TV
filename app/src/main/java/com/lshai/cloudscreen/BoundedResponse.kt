package com.lshai.cloudscreen

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Limit responses even when the server omits or lies about Content-Length. */
internal fun readBounded(input: InputStream, limit: Int): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count > limit - total) throw IOException("片源返回数据过大")
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
