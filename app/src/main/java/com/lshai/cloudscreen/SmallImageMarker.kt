package com.lshai.cloudscreen

import java.io.File

/** A bounded-lived marker avoids redownloading an image whose original is already small. */
internal object SmallImageMarker {
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    fun isFresh(file: File, now: Long): Boolean {
        if (!file.isFile) return false
        val age = now - file.lastModified()
        if (age < 0 || age > MAX_AGE_MS) { file.delete(); return false }
        return true
    }
    fun remember(file: File) { file.writeText("1") }
}
