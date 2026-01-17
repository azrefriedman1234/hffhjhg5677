package com.pasiflonet.mobile.utils

import android.net.Uri

object YoutubeUtil {

    /**
     * Accepts common YouTube URL formats:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     * - https://www.youtube.com/embed/VIDEO_ID
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

            // youtube.com/shorts/<id> or /embed/<id>
            val seg = u.pathSegments
            val idxShorts = seg.indexOf("shorts")
            if (idxShorts >= 0 && seg.size > idxShorts + 1) return seg[idxShorts + 1]
            val idxEmbed = seg.indexOf("embed")
            if (idxEmbed >= 0 && seg.size > idxEmbed + 1) return seg[idxEmbed + 1]

            null
        } catch (_: Exception) {
            null
        }
    }

    fun buildEmbedUrl(videoId: String): String {
        // autoplay usually requires mute in many WebView policies
        // loop requires playlist=<id>
        return "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1&loop=1&playlist=$videoId"
    }
}
