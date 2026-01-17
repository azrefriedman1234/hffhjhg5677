package com.pasiflonet.mobile.ffmpeg

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.utils.BlurRect
import com.pasiflonet.mobile.utils.DownloadLog
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class FfmpegIsolatedService : Service() {

    companion object {
        const val MSG_RUN = 1
        const val MSG_RESULT = 2
        private const val OUT_W = 720 // we always scale video to 720 width
    }

    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        val ctx = applicationContext
        val smartOk = try {
            Class.forName("com.arthenica.smartexception.java.Exceptions")
            true
        } catch (_: Throwable) { false }

        DownloadLog.write(
            ctx,
            "ffmpeg_service_alive.txt",
            "SERVICE ALIVE\nsmartException=$smartOk\nabis=${Build.SUPPORTED_ABIS.joinToString()}\n"
        )
    }

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_RUN) {
            val ctx = applicationContext
            DownloadLog.write(ctx, "ffmpeg_service_alive.txt", "SERVICE ALIVE\nRECEIVED RUN\n")

            val data = msg.data
            val reply = msg.replyTo

            val inputPath = data.getString("inputPath") ?: return@Handler true
            val outputPath = data.getString("outputPath") ?: return@Handler true
            val logoUriStr = data.getString("logoUri")
            val logoUri = if (logoUriStr.isNullOrBlank()) null else Uri.parse(logoUriStr)

            val logoRelX = data.getFloat("logoRelX", 0f)
            val logoRelY = data.getFloat("logoRelY", 0f)
            val logoRelW = data.getFloat("logoRelW", 0.2f)

            val rectArr = data.getFloatArray("blurRects") ?: FloatArray(0)
            val rects = mutableListOf<BlurRect>()
            var i = 0
            while (i + 3 < rectArr.size) {
                rects.add(BlurRect(rectArr[i], rectArr[i + 1], rectArr[i + 2], rectArr[i + 3]))
                i += 4
            }

            exec.execute { runFfmpeg(inputPath, outputPath, rects, logoUri, logoRelX, logoRelY, logoRelW, reply) }
            true
        } else false
    }

    private val messenger = Messenger(handler)
    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runFfmpeg(
        inputPath: String,
        outputPath: String,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float,
        reply: Messenger
    ) {
        val ctx = applicationContext
        val runLog = DownloadLog.writeWithTimestamp(
            ctx,
            "ffmpeg_run",
            "START\ninput=$inputPath\noutput=$outputPath\nblurRects=${blurRects.size}\nlogoUri=${logoUri ?: "null"}\n"
        )

        try {
            val inFile = File(inputPath)
            if (!inFile.exists() || !inFile.canRead()) {
                DownloadLog.write(ctx, runLog, "FAILED: input missing/unreadable\n")
                send(reply, false, runLog); return
            }
            File(outputPath).parentFile?.mkdirs()

            val logoPath = prepareLogoIfAny(logoUri)
            val filter = buildFilterComplex(blurRects, logoPath != null, logoRelX, logoRelY, logoRelW)

            val cmd1 = buildCmd(inputPath, logoPath, filter, outputPath, copyAudio = true)
            DownloadLog.write(ctx, runLog, "CMD(copy-audio):\n$cmd1\nBEFORE EXECUTE(copy-audio)\n")
            val s1 = FFmpegKit.execute(cmd1)
            DownloadLog.write(ctx, runLog, "AFTER EXECUTE(copy-audio)\nRC=${s1.returnCode}\n")
            val ok1 = ReturnCode.isSuccess(s1.returnCode) && File(outputPath).exists() && File(outputPath).length() > 0
            if (ok1) { send(reply, true, runLog); return }

            DownloadLog.write(ctx, runLog, "FAIL(copy-audio)\nOUTPUT:\n${s1.output ?: ""}\nLOGS:\n${s1.allLogsAsString}\n")

            val cmd2 = buildCmd(inputPath, logoPath, filter, outputPath, copyAudio = false)
            DownloadLog.write(ctx, runLog, "CMD(aac):\n$cmd2\nBEFORE EXECUTE(aac)\n")
            val s2 = FFmpegKit.execute(cmd2)
            DownloadLog.write(ctx, runLog, "AFTER EXECUTE(aac)\nRC=${s2.returnCode}\n")
            val ok2 = ReturnCode.isSuccess(s2.returnCode) && File(outputPath).exists() && File(outputPath).length() > 0
            send(reply, ok2, runLog)

        } catch (t: Throwable) {
            DownloadLog.write(ctx, runLog, "THROWABLE:\n${t::class.java.name}: ${t.message}\n$t\n")
            send(reply, false, runLog)
        }
    }

    private fun send(reply: Messenger, ok: Boolean, runLog: String) {
        try {
            val b = Bundle().apply {
                putBoolean("ok", ok)
                putString("runLogName", runLog)
            }
            reply.send(Message.obtain(null, MSG_RESULT).apply { data = b })
        } catch (_: Throwable) {}
    }

    private fun buildCmd(inputPath: String, logoPath: String?, filter: String, outputPath: String, copyAudio: Boolean): String {
        fun q(p: String) = "\"" + p.replace("\"", "\\\"") + "\""
        val sb = StringBuilder()
        sb.append("-y -hide_banner -loglevel error ")
        sb.append("-i ").append(q(inputPath)).append(' ')
        if (logoPath != null) sb.append("-i ").append(q(logoPath)).append(' ')
        sb.append("-filter_complex ").append(q(filter)).append(' ')
        sb.append("-map \"[vout]\" -map 0:a? ")
        sb.append("-c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p -movflags +faststart ")
        if (copyAudio) sb.append("-c:a copy ") else sb.append("-c:a aac -b:a 128k ")
        sb.append(q(outputPath))
        return sb.toString()
    }

    // IMPORTANT: no scale2ref (it can crash on some devices). We scale logo based on OUT_W=720.
    private fun buildFilterComplex(blurRects: List<BlurRect>, hasLogo: Boolean, logoRelX: Float, logoRelY: Float, logoRelW: Float): String {
        fun f(v: Float) = String.format(Locale.US, "%.6f", v)
        fun clamp01(v: Float) = v.coerceIn(0f, 1f)

        val safeLogoX = clamp01(logoRelX)
        val safeLogoY = clamp01(logoRelY)
        val safeLogoW = logoRelW.coerceIn(0.05f, 0.8f)

        val rects = blurRects.mapNotNull { r ->
            val l = clamp01(minOf(r.left, r.right))
            val rr = clamp01(maxOf(r.left, r.right))
            val t = clamp01(minOf(r.top, r.bottom))
            val bb = clamp01(maxOf(r.top, r.bottom))
            val w = rr - l
            val h = bb - t
            if (w < 0.005f || h < 0.005f) null else BlurRect(l, t, rr, bb)
        }

        val g = StringBuilder()
        g.append("[0:v]scale=").append(OUT_W).append(":-2,format=rgba[v0];")
        var cur = "[v0]"

        for (i in rects.indices) {
            val r = rects[i]
            val relW = r.right - r.left
            val relH = r.bottom - r.top

            g.append(cur).append("split=2[vmain").append(i).append("][vtmp").append(i).append("];")
            g.append("[vtmp").append(i).append("]")
                .append("crop=w='max(iw*").append(f(relW)).append(",2)':")
                .append("h='max(ih*").append(f(relH)).append(",2)':")
                .append("x='iw*").append(f(r.left)).append("':")
                .append("y='ih*").append(f(r.top)).append("',")
                .append("boxblur=12:1[blur").append(i).append("];")

            g.append("[vmain").append(i).append("][blur").append(i).append("]")
                .append("overlay=")
                .append("x='main_w*").append(f(r.left)).append("':")
                .append("y='main_h*").append(f(r.top)).append("':")
                .append("format=auto[v").append(i + 1).append("];")

            cur = "[v${i + 1}]"
        }

        if (hasLogo) {
            val logoPxExpr = "trunc(${OUT_W}*${f(safeLogoW)}/2)*2"
            g.append("[1:v]format=rgba,scale=w='").append(logoPxExpr).append("':h=-2[logoS];")
            g.append(cur).append("[logoS]")
                .append("overlay=")
                .append("x='min(max(main_w*").append(f(safeLogoX)).append(",0),main_w-overlay_w)':")
                .append("y='min(max(main_h*").append(f(safeLogoY)).append(",0),main_h-overlay_h)'")
                .append("[vout];")
        } else {
            g.append(cur).append("format=yuv420p[vout];")
        }

        // ensure output format
        g.append("[vout]format=yuv420p[vout]")
        return g.toString()
    }

    private fun prepareLogoIfAny(logoUri: Uri?): String? {
        if (logoUri == null) return null
        return try {
            val inStream = contentResolver.openInputStream(logoUri) ?: return null
            val outFile = File(cacheDir, "v_logo.png")
            outFile.outputStream().use { out -> inStream.use { it.copyTo(out) } }
            if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
        } catch (_: Throwable) { null }
    }
}
