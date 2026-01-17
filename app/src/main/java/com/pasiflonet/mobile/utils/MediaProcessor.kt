package com.pasiflonet.mobile.utils

import android.content.Context
import android.net.Uri

object MediaProcessor {

    fun processContent(
        context: Context,
        inputPath: String,
        outputPath: String,
        isVideo: Boolean,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float,
        onComplete: (Boolean) -> Unit
    ) {
        if (!isVideo) {
            onComplete(false)
            return
        }

        // Run video processing in isolated process to prevent app crash on native SIGSEGV
        IsolatedFfmpegRunner.run(
            context = context,
            inputPath = inputPath,
            outputPath = outputPath,
            blurRects = blurRects,
            logoUri = logoUri,
            logoRelX = logoRelX,
            logoRelY = logoRelY,
            logoRelW = logoRelW,
            onComplete = onComplete
        )
    }
}
