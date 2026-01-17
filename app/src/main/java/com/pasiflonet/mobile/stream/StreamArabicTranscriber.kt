package com.pasiflonet.mobile.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class StreamArabicTranscriber(
    private val ctx: Context,
    private val onHebLine: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var translator: Translator? = null

    // מודל ערבית (יורד פעם ראשונה לתוך app files)
    private val modelZipUrl = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
    private val modelRootDir = File(ctx.filesDir, "vosk")
    private val modelDir = File(modelRootDir, "vosk-model-ar-mgb2-0.4")

    private var ffmpegSessionId: Long? = null
    private var pipePath: String? = null

    fun start(streamUrl: String) {
        if (running.getAndSet(true)) return

        thread(name = "stream-transcriber") {
            try {
                post("מוריד/טוען תרגום (ML Kit)…")
                ensureTranslator()

                post("מוריד/טוען מודל תמלול (Vosk)…")
                ensureModel()

                val m = model ?: error("Model not ready")
                recognizer = Recognizer(m, 16000.0f)

                pipePath = FFmpegKitConfig.registerNewFFmpegPipe(ctx)
                val pipe = pipePath!!

                post("תמלול פעיל ✅")

                val cmd = listOf(
                    "-hide_banner","-loglevel","error",
                    "-reconnect","1","-reconnect_streamed","1","-reconnect_delay_max","5",
                    "-i", streamUrl sees,
                    "-vn",
                    "-ac","1","-ar","16000",
                    "-f","s16le",
                    pipe
                ).joinToString(" ")

                val session = FFmpegKit.executeAsync(cmd) { _ -> }
                ffmpegSessionId = session.sessionId

                FileInputStream(File(pipe)).use { fis ->
                    val buf = ByteArray(4096)
                    var last = ""
                    while (running.get()) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        val rec = recognizer ?: break
                        if (rec.acceptWaveForm(buf, n)) {
                            val ar = extractText(rec.result).trim()
                            if (ar.isNotBlank() && ar != last) {
                                last = ar
                                translateAndEmit(ar)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                post("שגיאה בתמלול: ${e.message ?: "?"}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running.set(false)
        try { ffmpegSessionId?.let { FFmpegKit.cancel(it) } } catch (_: Exception) {}
        ffmpegSessionId = null
        try { pipePath?.let { FFmpegKitConfig.closeFFmpegPipe(it) } } catch (_: Exception) {}
        pipePath = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        post("תמלול עצור")
    }

    private fun ensureTranslator() {
        if (translator != null) return
        val opt = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ARABIC)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        val tr = Translation.getClient(opt)
        Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        translator = tr
    }

    private fun ensureModel() {
        if (model != null) return
        modelRootDir.mkdirs()

        if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
            post("מוריד מודל Vosk… (פעם ראשונה)")
            val zip = File(ctx.cacheDir, "vosk_ar.zip")
            download(modelZipUrl, zip)
            unzip(zip, modelRootDir)
            zip.delete()
        }

        val dir = if (modelDir.exists()) modelDir else findModelDir(modelRootDir)
        model = Model(dir?.absolutePath ?: error("Model dir not found"))
    }

    private fun findModelDir(root: File): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && File(it,"am").exists() && File(it,"conf").exists() }

    private fun download(url: String, out: File) {
        URL(url).openStream().use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    if (!running.get()) break
                }
            }
        }
    }

    private fun unzip(zip: File, outDir: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(outDir, e.name)
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun extractText(json: String): String =
        try { JSONObject(json).optString("text","") } catch (_: Exception) { "" }

    private fun translateAndEmit(ar: String) {
        val tr = translator ?: return
        tr.translate(ar)
            .addOnSuccessListener { he ->
                if (he.isNotBlank()) main.post { onHebLine(he) }
            }
            .addOnFailureListener {
                // fallback: מציג ערבית אם תרגום נכשל
                main.post { onHebLine(ar) }
            }
    }

    private fun post(s: String) { main.post { onStatus(s) } }
}
