package com.pasiflonet.mobile.stream

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pasiflonet.mobile.R
import java.util.ArrayDeque

class StreamActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    private var player: ExoPlayer? = null
    private var muted = false

    private var transcriber: StreamArabicTranscriber? = null
    private var transcribing = false

    private val lines = ArrayDeque<String>() // שומר רק אחרונות

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        val et = findViewById<EditText>(R.id.etStreamUrl)
        val btnPlay = findViewById<Button>(R.id.btnPlay)
        val btnMute = findViewById<Button>(R.id.btnMute)
        val btnTr = findViewById<Button>(R.id.btnTranscribe)
        val pv = findViewById<PlayerView>(R.id.playerView)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tv = findViewById<TextView>(R.id.tvTranscript)
        val sv = findViewById<ScrollView>(R.id.svTranscript)

        // ניקוי תמלול בכל פתיחה
        lines.clear()
        tv.text = ""

        player = ExoPlayer.Builder(this).build()
        pv.player = player

        val saved = prefs.getString("stream_url", "")?.trim().orEmpty()
        et.setText(saved)

        fun appendLine(s: String) {
            if (s.isBlank()) return
            lines.addLast(s)
            while (lines.size > 200) lines.removeFirst()
            tv.text = lines.joinToString("\n\n")
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }

        fun play(url: String) {
            val p = player ?: return
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
            p.volume = if (muted) 0f else 1f
            tvStatus.text = "מנגן…"
        }

        btnPlay.setOnClickListener {
            val url = et.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(this, "שים קישור m3u8/mp3", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("stream_url", url).apply()
            play(url)
        }

        btnMute.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            btnMute.text = if (muted) "בטל השתק" else "השתק"
            // התמלול ממשיך כי הוא לא תלוי בווליום של הנגן
        }

        btnTr.setOnClickListener {
            val url = prefs.getString("stream_url", "")?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(this, "קודם שמור קישור שידור", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (transcribing) {
                transcribing = false
                transcriber?.stop()
                btnTr.text = "הפעל תמלול"
                tvStatus.text = "תמלול עצור"
                return@setOnClickListener
            }

            transcribing = true
            btnTr.text = "עצור תמלול"
            tvStatus.text = "תמלול מתחיל…"

            if (transcriber == null) {
                transcriber = StreamArabicTranscriber(
                    ctx = this,
                    onHebLine = { he -> appendLine(he) },
                    onStatus = { s -> tvStatus.text = s }
                )
            }
            transcriber?.start(url)
        }

        // auto play אם יש קישור
        if (saved.isNotBlank()) play(saved)
    }

    override fun onDestroy() {
        try { transcriber?.stop() } catch (_: Exception) {}
        transcriber = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }
}
