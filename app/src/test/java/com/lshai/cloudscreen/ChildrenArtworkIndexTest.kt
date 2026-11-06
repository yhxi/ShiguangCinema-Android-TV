package com.lshai.cloudscreen

import org.junit.Assert.*
import org.junit.Test

class ChildrenArtworkIndexTest {
    @Test fun ignoresMalformedRowsAndUnsafeLinks() {
        val text = """{"甲":{"urls":["https://example.com/a.jpg","https://example.com/a.jpg","http://example.com/b.jpg","javascript:bad"]},"乙":{},"丙":{"urls":[""]}}"""
        assertEquals(mapOf("甲" to listOf("https://example.com/a.jpg")), parseChildrenArtworkIndex(text))
    }
    @Test fun bundledManifestOnlyReferencesCatalogTitles() {
        val file = java.io.File("src/main/assets/children_artwork.json")
        val index = parseChildrenArtworkIndex(file.readText())
        val catalog = ChildrenCatalog.preloadOrder().toSet()
        assertTrue(index.size >= 80)
        assertTrue(index.keys.all { it in catalog })
        assertTrue(index.values.all { it.isNotEmpty() && it.distinct() == it })
    }
}
