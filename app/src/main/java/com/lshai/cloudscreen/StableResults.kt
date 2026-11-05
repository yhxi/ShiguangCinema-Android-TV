package com.lshai.cloudscreen

/** Preserve existing rows while appending results from slower sources. */
internal fun <T> keepExistingResultOrder(items: List<T>, previous: List<String>, key: (T) -> String): List<T> {
    if (previous.isEmpty()) return items
    val positions = previous.withIndex().associate { it.value to it.index }
    return items.sortedBy { positions[key(it)] ?: Int.MAX_VALUE }
}

/** Touch-mode GridView has no selected row; only restore a selection for D-pad navigation. */
internal fun searchSelectionToRestore(selected: String?, titles: List<String>, inTouchMode: Boolean): Int? {
    if (inTouchMode || selected == null) return null
    return titles.indexOf(selected).takeIf { it >= 0 }
}
