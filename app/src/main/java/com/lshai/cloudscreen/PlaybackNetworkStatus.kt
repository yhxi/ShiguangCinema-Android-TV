package com.lshai.cloudscreen

import android.content.Context
import android.net.ConnectivityManager

/** Only report offline when Android explicitly has no active network; IPTV may use a private LAN. */
internal fun hasActiveNetwork(context: Context): Boolean = try {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    manager == null || manager.activeNetwork != null
} catch (_: Exception) { true }

internal fun nextLiveLine(currentLine: Int, lineCount: Int, online: Boolean): Int? =
    if (online && currentLine >= 0 && currentLine + 1 < lineCount) currentLine + 1 else null

internal fun preferredLiveLineOrder(lineCount: Int, preferredIndex: Int): List<Int> =
    (0 until lineCount).sortedBy { it != preferredIndex }
