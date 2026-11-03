package com.lshai.cloudscreen

import org.json.JSONArray

/** Do not replace profiles if their encrypted data cannot be read or parsed. */
internal fun serverProfilesReadable(stored: String, decrypted: String): Boolean {
    if (stored.startsWith("enc:") && decrypted.isEmpty()) return false
    if (decrypted.isEmpty()) return true
    return runCatching { JSONArray(decrypted); true }.getOrDefault(false)
}
