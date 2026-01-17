package com.pasiflonet.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.utils.MediaProcessor
import com.pasiflonet.mobile.utils.ImageUtils
import com.pasiflonet.mobile.utils.TranslationManager
import com.pasiflonet.mobile.utils.BlurRect
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import android.graphics.drawable.BitmapDrawable
import java.util.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DetailsActivity : BaseActivity() {
    private lateinit var b: ActivityDetailsBinding
    private var rawMediaPath: String? = null
    private var isVideo = false
    private var fileId = 0
    private var thumbId = 0
    private var imageBounds = RectF()
    private var logoRelX = 0.5f; private var logoRelY = 0.5f; private var savedLogoRelW = 0.2f

    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("logo_uri", uri.toString()).apply()
            loadLogoFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        isVideo = intent.getBooleanExtra("IS_VIDEO", false)
        fileId = intent.getIntExtra("FILE_ID", 0)
        thumbId = intent.getIntExtra("THUMB_ID", 0)
        
        val passedThumbPath = intent.getStringExtra("THUMB_PATH")
        b.etCaption.setText(intent.getStringExtra("CAPTION") ?: "")

        if (passedThumbPath != null && File(passedThumbPath).exists()) {
            loadPreview(passedThumbPath)
        } else if (thumbId != 0) {
            startThumbHunter(thumbId)
        }

        if (fileId != 0) startFullMediaHunter(fileId)

        setupTools()
    }

    private fun loadPreview(path: String) {
        if (isFinishing || isDestroyed) return
        b.ivPreview.load(File(path)) {
            listener(onSuccess = { _, _ -> 
                b.ivPreview.post { 
                    if (!isFinishing) {
                        calculateMatrixBounds() 
                        if (b.ivDraggableLogo.visibility == View.VISIBLE) restoreLogoPosition()
                    }
                } 
            })
        }
    }

    private fun startThumbHunter(tId: Int) {
        TdLibManager.downloadFile(tId)
        lifecycleScope.launch(Dispatchers.IO) {
            for (i in 0..10) {
                if (isFinishing) break
                val path = TdLibManager.getFilePath(tId)
                if (path != null && File(path).exists()) {
                    withContext(Dispatchers.Main) { loadPreview(path) }
                    break
                }
                delay(500)
            }
        }
    }

    private fun startFullMediaHunter(fId: Int) {
        TdLibManager.downloadFile(fId)
        lifecycleScope.launch(Dispatchers.IO) {
            for (i in 0..60) {
                if (isFinishing) break
                val path = TdLibManager.getFilePath(fId)
                if (path != null && File(path).exists()) {
                    val file = File(path)
                    if (file.length() > 50000 || !isVideo) {
                        rawMediaPath = path
                        if (!isVideo) withContext(Dispatchers.Main) { loadPreview(path) }
                        break
                    }
                }
                delay(1000)
            }
        }
    }

    private fun setupTools() {
        b.btnTranslate.setOnClickListener {
            val originalText = b.etCaption.text.toString()
            if (originalText.isNotEmpty()) {
                safeToast("Translating...")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val translated = TranslationManager.translateToHebrew(originalText)
                        withContext(Dispatchers.Main) {
                            if (!isFinishing) b.etCaption.setText(translated)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { safeToast("Translation failed") }
                    }
                }
            }
        }

        b.btnModeBlur.setOnClickListener {
            b.drawingView.visibility = View.VISIBLE
            b.drawingView.bringToFront()
            b.drawingView.isBlurMode = true
            b.ivDraggableLogo.alpha = 0.5f
            calculateMatrixBounds()
        }

        b.btnModeLogo.setOnClickListener {
            b.drawingView.isBlurMode = false
            b.ivDraggableLogo.visibility = View.VISIBLE
            b.ivDraggableLogo.alpha = 1.0f
            b.ivDraggableLogo.bringToFront() 

            val uriStr = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("logo_uri", null)
            if (uriStr != null) { loadLogoFromUri(Uri.parse(uriStr)) } 
            else { pickLogoLauncher.launch("image/*") }
        }
        
        b.btnModeLogo.setOnLongClickListener { pickLogoLauncher.launch("image/*"); true }

        var dX = 0f; var dY = 0f
        b.ivDraggableLogo.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY }
                android.view.MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX; var newY = event.rawY + dY
                    if (imageBounds.width() > 0) {
                        newX = newX.coerceIn(imageBounds.left, imageBounds.right - v.width)
                        newY = newY.coerceIn(imageBounds.top, imageBounds.bottom - v.height)
                        logoRelX = (newX - imageBounds.left) / imageBounds.width()
                        logoRelY = (newY - imageBounds.top) / imageBounds.height()
                    }
                    v.x = newX; v.y = newY
                }
            }
            true
        }

        
        // --- Logo size slider (works for both image+video) ---
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        savedLogoRelW = prefs.getFloat("logo_rel_w", 0.2f).coerceIn(0.05f, 0.8f)

        // map relW(0.05..0.8) <-> progress(0..100)
        val prog = (((savedLogoRelW - 0.05f) / (0.8f - 0.05f)) * 100f).toInt().coerceIn(0, 100)
        b.sbLogoSize.progress = prog

        b.sbLogoSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val relW = (0.05f + (progress / 100f) * (0.8f - 0.05f)).coerceIn(0.05f, 0.8f)
                savedLogoRelW = relW
                prefs.edit().putFloat("logo_rel_w", relW).apply()
                if (b.ivDraggableLogo.visibility == View.VISIBLE) {
                    applyLogoSize(relW)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

b.btnSend.setOnClickListener { performStrictSend() }
        b.btnCancel.setOnClickListener { finish() }
    }
    
    private fun loadLogoFromUri(uri: Uri) {
        if (isFinishing) return
        b.ivDraggableLogo.load(uri) {
            listener(onSuccess = { _, _ ->
                if (!isFinishing) {
                    b.ivDraggableLogo.visibility = View.VISIBLE
                    b.ivDraggableLogo.bringToFront()
                    restoreLogoPosition()
                }
            })
        }
    }

    private fun calculateMatrixBounds() {
        if (isFinishing) return
        val d = b.ivPreview.drawable ?: return
        val v = FloatArray(9); b.ivPreview.imageMatrix.getValues(v)
        val w = d.intrinsicWidth * v[Matrix.MSCALE_X]; val h = d.intrinsicHeight * v[Matrix.MSCALE_Y]
        imageBounds.set(v[Matrix.MTRANS_X], v[Matrix.MTRANS_Y], v[Matrix.MTRANS_X] + w, v[Matrix.MTRANS_Y] + h)
        b.drawingView.setValidBounds(imageBounds)
    }

    private fun restoreLogoPosition() {
        if (imageBounds.width() > 0) {
            b.ivDraggableLogo.x = imageBounds.left + (logoRelX * imageBounds.width())
            b.ivDraggableLogo.y = imageBounds.top + (logoRelY * imageBounds.height())
        }
    }


    private fun applyLogoSize(relW: Float) {
        // must have imageBounds
        if (imageBounds.width() <= 0) calculateMatrixBounds()
        if (imageBounds.width() <= 0) return

        val d = b.ivDraggableLogo.drawable ?: return
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else b.ivDraggableLogo.width
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else b.ivDraggableLogo.height
        val ratio = if (iw > 0) (ih.toFloat() / iw.toFloat()) else 1f

        val targetW = (imageBounds.width() * relW).toInt().coerceIn(48, 2000)
        val targetH = (targetW * ratio).toInt().coerceIn(48, 2000)

        val lp = b.ivDraggableLogo.layoutParams
        lp.width = targetW
        lp.height = targetH
        b.ivDraggableLogo.layoutParams = lp

        // keep it inside bounds after size change
        restoreLogoPosition()
    }
    private fun performStrictSend() {
        val rawPath = rawMediaPath
        if (rawPath == null || !File(rawPath).exists()) {

            // ✅ TEXT-ONLY message: no media ids + not video -> send text immediately
            if (!isVideo && fileId == 0 && thumbId == 0) {
                val textToSend = b.etCaption.text?.toString()?.trim().orEmpty()
                if (textToSend.isEmpty()) {
                    safeToast("אין טקסט לשליחה")
                    return
                }

                val target = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .getString("target_username", "") ?: ""

                b.btnSend.isEnabled = false
                try { b.loadingOverlay.visibility = View.GONE } catch (_: Exception) {}

                safeToast("שולח טקסט…")

                // go back to main table immediately
                try {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                } catch (_: Exception) {}

                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    // TdLibManager already supports filePath=null -> InputMessageText
                    TdLibManager.sendFinalMessage(target, textToSend, null, false)
                }
                return
            }

            safeToast("Wait, downloading media...")
            return
        }

        // Snapshot ALL UI data now (so we can immediately go back to main)
        val rects = ArrayList<BlurRect>()
        for (r in b.drawingView.rects) rects.add(BlurRect(r.left, r.top, r.right, r.bottom))

        val caption = b.etCaption.text?.toString() ?: ""
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val target = prefs.getString("target_username", "") ?: ""

        // --- logo params snapshot (POSITION FROM UI, not old prefs) ---
        var logoRelX = prefs.getFloat("logo_rel_x", 0.02f)
        var logoRelY = prefs.getFloat("logo_rel_y", 0.02f)
        var logoRelW = prefs.getFloat("logo_rel_w", savedLogoRelW).coerceIn(0.05f, 0.8f)

        // make sure imageBounds are valid
        if (imageBounds.width() <= 0f || imageBounds.height() <= 0f) calculateMatrixBounds()

        if (b.ivDraggableLogo.visibility == View.VISIBLE && imageBounds.width() > 0f && imageBounds.height() > 0f) {
            val lx = b.ivDraggableLogo.x
            val ly = b.ivDraggableLogo.y

            logoRelX = ((lx - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
            logoRelY = ((ly - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
            logoRelW = (b.ivDraggableLogo.width.toFloat() / imageBounds.width()).coerceIn(0.05f, 0.8f)

            prefs.edit()
                .putFloat("logo_rel_x", logoRelX)
                .putFloat("logo_rel_y", logoRelY)
                .putFloat("logo_rel_w", logoRelW)
                .apply()
        }
var logoUri: Uri? = null
        var relW = logoRelW

        if (b.ivDraggableLogo.visibility == View.VISIBLE) {
            try {
                val d = b.ivDraggableLogo.drawable
                if (d is BitmapDrawable) {
                    val f = File(applicationContext.cacheDir, "temp_logo.png")
                    FileOutputStream(f).use { out -> d.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    logoUri = Uri.fromFile(f)
                }
                if (imageBounds.width() > 0) {
                    relW = logoRelW
                }
            } catch (_: Exception) {}
        }

        // No loading overlay anymore
        try { b.loadingOverlay.visibility = View.GONE } catch (_: Exception) {}
        b.btnSend.isEnabled = false

        // Jump immediately to main table
        try {
            safeToast("שולח ברקע…")
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (_: Exception) {}

        // Continue processing + send in background (not tied to lifecycleScope)
        val appCtx = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val safeInputFile = File(appCtx.cacheDir, "safe_input.${if (isVideo) "mp4" else "jpg"}")
                File(rawPath).copyTo(safeInputFile, overwrite = true)
                val safeInputPath = safeInputFile.absolutePath

                val outPath = File(appCtx.cacheDir, "safe_out_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}").absolutePath

                val success = if (isVideo) {
                    try {
                        suspendCoroutine { cont ->
                            MediaProcessor.processContent(appCtx, safeInputPath, outPath, isVideo, rects,
                                logoUri, logoRelX, logoRelY, relW
                            ) { cont.resume(it) }
                        }
                    } catch (_: Exception) { false }
                } else {
                    ImageUtils.processImage(
                        appCtx, safeInputPath, outPath, rects,
                        logoUri, logoRelX, logoRelY, relW
                    )
                }

                if (success && File(outPath).exists() && File(outPath).length() > 0) {
                    TdLibManager.sendFinalMessage(target, caption, outPath, isVideo)
                    safeToast("✅ נשלח")
                    runOnUiThread { try { finish() } catch (_: Exception) {} }
                } else {
                    safeToast("❌ Edit Failed. Not sent.")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) b.btnSend.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                safeToast("Error: ${e.message}")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) b.btnSend.isEnabled = true
                }
            }
        }
    }

    // שימוש ב-applicationContext ליתר ביטחון
    private fun safeToast(msg: String) { 
        runOnUiThread { 
            try { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() } catch(e: Exception) {} 
        } 
    }
}
