package com.pasiflonet.mobile.utils

import android.net.Uri

object YoutubeUtil {
    fun extractVideoId(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null

        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase()

            if (host.contains("youtu.be")) {
                return u.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            }

            val v = u.getQueryParameter("v")
            if (!v.isNullOrBlank()) return v

            val seg = u.pathSegments
            fun idAfter(marker: String): String? {
                val idx = seg.indexOf(marker)
                return if (idx >= 0 && seg.size > idx + 1) seg[idx + 1] else null
            }

            idAfter("live")?.let { return it }
            idAfter("shorts")?.let { return it }
            idAfter("embed")?.let { return it }
            idAfter("v")?.let { return it }

            val last = seg.lastOrNull()
            if (!last.isNullOrBlank() && last.length >= 11 && last != "watch") return last

            null
        } catch (_: Exception) {
            null
        }
    }

    fun buildEmbedUrl(videoId: String): String {
        return "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1&controls=1&fs=1&rel=0&modestbranding=1"
    }
}
