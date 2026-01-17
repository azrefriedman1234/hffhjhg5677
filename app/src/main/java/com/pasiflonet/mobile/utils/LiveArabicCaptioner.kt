package com.pasiflonet.mobile.utils

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Live Arabic -> Hebrew captions without any API key.
 *
 * - Speech-to-text uses Android SpeechRecognizer (device's speech service).
 * - Translation prefers ML Kit on-device translator (no API key). If that fails,
 *   we fall back to TranslationManager (network, still keyless).
 */
class LiveArabicCaptioner(
    private val context: Context,
    private val onLine: (arabic: String, hebrew: String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var isRunning = false
    private var isTranslatorReady = false

    private val listenIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun start() {
        if (isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Speech recognition not available on this device")
            return
        }

        isRunning = true
        onStatus("Preparing translator...")
        ensureTranslator { 
            onStatus("Listening (Arabic)...")
            ensureRecognizer()
            restartListening(delayMs = 100)
        }
    }

    fun stop() {
        isRunning = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
        onStatus("Stopped")
    }

    fun release() {
        stop()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { translator?.close() } catch (_: Exception) {}
        translator = null
        isTranslatorReady = false
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (isRunning) restartListening(delayMs = 200)
                }

                override fun onError(error: Int) {
                    // Common errors: ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT.
                    if (!isRunning) return
                    restartListening(delayMs = 400)
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (text.isNotEmpty()) {
                        translateAndEmit(text)
                    }
                    if (isRunning) restartListening(delayMs = 200)
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    // Optional: could show live partial text.
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
    }

    private fun restartListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            try {
                recognizer?.cancel()
            } catch (_: Exception) {}
            try {
                recognizer?.startListening(listenIntent)
            } catch (e: Exception) {
                onStatus("Recognizer error: ${e.message}")
            }
        }, delayMs)
    }

    private fun ensureTranslator(onReady: () -> Unit) {
        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ARABIC)
                .setTargetLanguage(TranslateLanguage.HEBREW)
                .build()
            translator = Translation.getClient(options)
        }
        if (isTranslatorReady) {
            onReady()
            return
        }

        val t = translator ?: run {
            onStatus("Translator init failed")
            onReady()
            return
        }

        // Download model if needed (no API key). Once downloaded, works offline.
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                isTranslatorReady = true
                onReady()
            }
            .addOnFailureListener { e ->
                // Still allow start; we'll fallback to TranslationManager.
                onStatus("Translator download failed; fallback mode (${e.message})")
                isTranslatorReady = false
                onReady()
            }
    }

    private fun translateAndEmit(arabicText: String) {
        val t = translator
        if (t != null && isTranslatorReady) {
            t.translate(arabicText)
                .addOnSuccessListener { hebrew -> onLine(arabicText, hebrew) }
                .addOnFailureListener {
                    // Fallback to keyless network translation
                    fallbackTranslate(arabicText)
                }
            return
        }

        fallbackTranslate(arabicText)
    }

    private fun fallbackTranslate(arabicText: String) {
        onStatus("Translating...")
        // TranslationManager is suspend; run it on background thread
        Thread {
            val heb = try {
                // blocky but safe & simple here
                kotlinx.coroutines.runBlocking {
                    TranslationManager.translateToHebrew(arabicText)
                }
            } catch (_: Exception) {
                arabicText
            }
            mainHandler.post { onLine(arabicText, heb) }
        }.start()
    }
}
