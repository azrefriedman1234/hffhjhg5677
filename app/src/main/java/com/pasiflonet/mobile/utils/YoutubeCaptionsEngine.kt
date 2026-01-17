package com.pasiflonet.mobile.utils

import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class YoutubeCaptionsEngine(
    private val context: Context,
    private val onLine: (arabic: String, hebrew: String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private var translator: Translator? = null
    private var modelReady: Boolean = false

    private var lastEventStartMs: Long = -1L
    private var lastArabic: String = ""

    fun startFromYoutubeUrl(url: String) {
        stop()

        val videoId = YoutubeUtil.extractVideoId(url)
        if (videoId.isNullOrBlank()) {
            onStatus("קישור יוטיוב לא תקין")
            return
        }

        lastEventStartMs = -1L
        lastArabic = ""

        job = scope.launch {
            onStatus("טוען כתוביות מיוטיוב (ערבית)...")
            ensureTranslator()

            val baseUrl = withContext(Dispatchers.IO) {
                resolveCaptionBaseUrl(videoId, lang = "ar")
            }

            if (baseUrl.isNullOrBlank()) {
                onStatus("אין כתוביות בערבית בסרטון/לייב הזה")
                return@launch
            }

            onStatus("כתוביות פעילות ✅")

            while (isActive) {
                try {
                    val json = withContext(Dispatchers.IO) { fetchJson3(baseUrl) }
                    if (json != null) {
                        val newLines = extractNewCaptionLines(json)
                        for (ar in newLines) {
                            val he = translate(ar)
                            if (ar.isNotBlank() && he.isNotBlank()) onLine(ar, he)
                        }
                    }
                } catch (_: Exception) {
                    // keep polling
                }
                delay(1500)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onStatus("כתוביות עצורות")
    }

    fun release() {
        stop()
        try { translator?.close() } catch (_: Exception) {}
        translator = null
        modelReady = false
    }

    private suspend fun ensureTranslator() {
        if (translator == null) {
            val opt = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ARABIC)
                .setTargetLanguage(TranslateLanguage.HEBREW)
                .build()
            translator = Translation.getClient(opt)
        }
        val t = translator ?: return
        try {
            withContext(Dispatchers.IO) { t.downloadModelIfNeeded().await() }
            modelReady = true
        } catch (_: Exception) {
            modelReady = false
            onStatus("מודל תרגום לא ירד, fallback לרשת")
        }
    }

    private suspend fun translate(arabic: String): String {
        val clean = arabic.trim()
        if (clean.isEmpty()) return ""

        val t = translator
        if (t != null && modelReady) {
            return try {
                t.translate(clean).await()
            } catch (_: Exception) {
                TranslationManager.translateToHebrew(clean)
            }
        }
        return TranslationManager.translateToHebrew(clean)
    }

    private fun extractNewCaptionLines(json3: JSONObject): List<String> {
        val out = ArrayList<String>()
        val events = json3.optJSONArray("events") ?: JSONArray()

        var maxSeen = lastEventStartMs
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val tStart = ev.optLong("tStartMs", -1L)
            if (tStart <= lastEventStartMs) continue

            val segs = ev.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) {
                val seg = segs.optJSONObject(j) ?: continue
                sb.append(seg.optString("utf8", ""))
            }

            val text = sb.toString().replace("\n", " ").trim()
            if (text.isBlank()) continue
            if (text == lastArabic) continue

            lastArabic = text
            out.add(text)
            maxSeen = max(maxSeen, tStart)
        }

        if (maxSeen > lastEventStartMs) lastEventStartMs = maxSeen
        return out
    }

    private fun fetchJson3(baseUrl: String): JSONObject? {
        val url = appendParam(baseUrl, "fmt", "json3")
        val body = httpGet(url) ?: return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    private fun resolveCaptionBaseUrl(videoId: String, lang: String): String? {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val html = httpGet(watchUrl) ?: return null
        val playerJson = extractInitialPlayerResponseJson(html) ?: return null

        val root = try { JSONObject(playerJson) } catch (_: Exception) { return null }
        val captions = root.optJSONObject("captions") ?: return null
        val renderer = captions.optJSONObject("playerCaptionsTracklistRenderer") ?: return null
        val tracks = renderer.optJSONArray("captionTracks") ?: return null

        var best: JSONObject? = null
        var bestScore = -1

        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val code = t.optString("languageCode", "")
            if (code != lang) continue

            // prefer auto captions if available
            val kind = t.optString("kind", "")
            val score = if (kind == "asr") 2 else 1
            if (score > bestScore) {
                best = t
                bestScore = score
            }
        }

        val chosen = best ?: return null
        return chosen.optString("baseUrl", "").takeIf { it.isNotBlank() }
    }

    private fun httpGet(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true

        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

        val code = conn.responseCode
        if (code != 200) return null

        val br = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        while (true) {
            val line = br.readLine() ?: break
            sb.append(line).append('\n')
        }
        br.close()
        return sb.toString()
    }

    private fun appendParam(url: String, k: String, v: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return if (url.contains("$k=")) url else "$url$sep$k=$v"
    }

    private fun extractInitialPlayerResponseJson(html: String): String? {
        val marker = "ytInitialPlayerResponse"
        val idx = html.indexOf(marker)
        if (idx < 0) return null

        val start = html.indexOf('{', idx)
        if (start < 0) return null

        var i = start
        var depth = 0
        var inStr = false
        var esc = false

        while (i < html.length) {
            val ch = html[i]
            if (inStr) {
                if (esc) esc = false
                else {
                    if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                }
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
