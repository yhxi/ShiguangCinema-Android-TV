package com.lshai.cloudscreen

/** The proxy is tried when direct bytes are missing or cannot be decoded. */
internal inline fun <T> loadImageWithFallback(
    direct: () -> ByteArray?, proxy: () -> ByteArray?, decode: (ByteArray) -> T?,
): T? {
    val directBytes = direct()
    if (directBytes != null) decode(directBytes)?.let { return it }
    return proxy()?.let(decode)
}
