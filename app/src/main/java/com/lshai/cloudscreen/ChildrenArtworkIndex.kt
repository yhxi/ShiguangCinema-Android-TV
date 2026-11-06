package com.lshai.cloudscreen

import android.content.Context
import org.json.JSONObject

/** URL-only manifest: no bitmap assets or runtime metadata-search dependency. */
internal object ChildrenArtworkIndex {
    @Volatile private var entries: Map<String, List<String>>? = null
    // Called from Dispatchers.IO; disk reads never block card construction.
    fun urls(context: Context, title: String): List<String> {
        val cached = entries ?: synchronized(this) {
            entries ?: runCatching {
                context.assets.open("children_artwork.json").bufferedReader().use { parseChildrenArtworkIndex(it.readText()) }
            }.getOrDefault(emptyMap()).also { entries = it }
        }
        return cached[title].orEmpty()
    }
}

internal fun parseChildrenArtworkIndex(text: String): Map<String, List<String>> {
    val json = JSONObject(text)
    val result = linkedMapOf<String, List<String>>()
    json.keys().forEach { title ->
        val array = json.optJSONObject(title)?.optJSONArray("urls") ?: return@forEach
        val urls = (0 until array.length()).map { array.optString(it).trim() }
            .filter { it.startsWith("https://") && it.length > 8 }.distinct()
        if (title.isNotBlank() && urls.isNotEmpty()) result[title] = urls
    }
    return result
}
