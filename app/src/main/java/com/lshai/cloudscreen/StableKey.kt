package com.lshai.cloudscreen

import java.security.MessageDigest

/** Stable key across app releases; unlike String.hashCode it is collision-resistant. */
internal fun stableDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val n = byte.toInt() and 0xff
            append(hex[n ushr 4])
            append(hex[n and 15])
        }
    }
}
