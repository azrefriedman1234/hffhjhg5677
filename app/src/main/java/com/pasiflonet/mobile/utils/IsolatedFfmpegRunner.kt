package com.pasiflonet.mobile.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import com.pasiflonet.mobile.ffmpeg.FfmpegIsolatedService

object IsolatedFfmpegRunner {

    fun run(
        context: Context,
        inputPath: String,
        outputPath: String,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float,
        onComplete: (Boolean) -> Unit
    ) {
        val appCtx = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        var finished = false
        lateinit var conn: ServiceConnection

        var remoteBinder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        fun safeUnbind() {
            try { appCtx.unbindService(conn) } catch (_: Throwable) {}
        }

        fun finish(ok: Boolean, msg: String) {
            if (finished) return
            finished = true

            // best effort: log to Downloads
            try { DownloadLog.write(appCtx, "ffmpeg_client_last.txt", msg) } catch (_: Throwable) {}

            // unlink death recipient
            try {
                val b = remoteBinder
                val dr = deathRecipient
                if (b != null && dr != null) b.unlinkToDeath(dr, 0)
            } catch (_: Throwable) {}

            safeUnbind()
            onComplete(ok)
        }

        // pack blur rects into float array
        val rectArr = FloatArray(blurRects.size * 4)
        var i = 0
        blurRects.forEach { r ->
            rectArr[i++] = r.left
            rectArr[i++] = r.top
            rectArr[i++] = r.right
            rectArr[i++] = r.bottom
        }

        val runBundle = Bundle().apply {
            putString("inputPath", inputPath)
            putString("outputPath", outputPath)
            putFloatArray("blurRects", rectArr)
            putString("logoUri", logoUri?.toString())
            putFloat("logoRelX", logoRelX)
            putFloat("logoRelY", logoRelY)
            putFloat("logoRelW", logoRelW)
        }

        val replyHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == FfmpegIsolatedService.MSG_RESULT) {
                    val ok = msg.data.getBoolean("ok", false)
                    val runLog = msg.data.getString("runLogName") ?: "unknown"
                    finish(ok, "RESULT ok=$ok runLog=$runLog\n")
                }
            }
        }
        val replyMessenger = Messenger(replyHandler)

        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    finish(false, "FAILED: onServiceConnected with null binder\n")
                    return
                }

                remoteBinder = service

                // detect remote-process crash immediately
                val dr = IBinder.DeathRecipient {
                    finish(false, "BINDER DIED: isolated ffmpeg process crashed (native crash suspected)\n")
                }
                deathRecipient = dr
                try { service.linkToDeath(dr, 0) } catch (_: Throwable) {}

                try {
                    val remote = Messenger(service)
                    val msg = Message.obtain(null, FfmpegIsolatedService.MSG_RUN).apply {
                        data = runBundle
                        replyTo = replyMessenger
                    }
                    try { DownloadLog.write(appCtx, "ffmpeg_client_last.txt", "SENT RUN to isolated service\n") } catch (_: Throwable) {}
                    remote.send(msg)
                } catch (t: Throwable) {
                    finish(false, "FAILED send: ${t.message}\n")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                finish(false, "DISCONNECTED: isolated service disconnected (native crash suspected)\n")
            }
        }

        // timeout 10 minutes
        mainHandler.postDelayed({
            if (!finished) {
                finish(false, "TIMEOUT: isolated service did not respond within 10 minutes\n")
            }
        }, 10 * 60 * 1000L)

        val intent = Intent(appCtx, com.pasiflonet.mobile.ffmpeg.FfmpegIsolatedService::class.java)
        val bound = appCtx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            finish(false, "FAILED: bindService returned false\n")
        }
    }
}
