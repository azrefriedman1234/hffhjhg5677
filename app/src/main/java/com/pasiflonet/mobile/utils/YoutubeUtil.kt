package com.pasiflonet.mobile.utils

import android.net.Uri

object YoutubeUtil {

    /**
     * Accepts common YouTube URL formats:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     * - https://www.youtube.com/embed/VIDEO_ID
     * - https://www.youtube.com/live/VIDEO_ID
     */
    fun extractVideoId(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null

        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase()

            // youtu.be/<id>
            if (host.contains("youtu.be")) {
                val seg = u.pathSegments
                return seg.firstOrNull()?.takeIf { it.isNotBlank() }
            }

            // youtube.com/watch?v=<id>
            val v = u.getQueryParameter("v")
            if (!v.isNullOrBlank()) return v

            // youtube.com/shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
            val seg = u.pathSegments
            fun idAfter(marker: String): String? {
                val idx = seg.indexOf(marker)
                return if (idx >= 0 && seg.size > idx + 1) seg[idx + 1] else null
            }

            idAfter("live")?.let { return it }
            idAfter("shorts")?.let { return it }
            idAfter("embed")?.let { return it }
            idAfter("v")?.let { return it }

            // last resort: if URL is like youtube.com/<id>
            val last = seg.lastOrNull()
            if (!last.isNullOrBlank() && last.length >= 11 && last != "watch") return last

            null
        } catch (_: Exception) {
            null
        }
    }

    fun buildEmbedUrl(videoId: String): String {
        // autoplay usually requires mute in many WebView policies
        // NOTE: don't force loop/playlist here (can break some live streams).
        return "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1&controls=1&fs=1&rel=0&modestbranding=1"
    }
}
