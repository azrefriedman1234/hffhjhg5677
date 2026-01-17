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
    private var isListening = false
    private var isTranslatorReady = false

    private var restartRunnable: Runnable? = null

    private val listenIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar")
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Try to keep sessions longer; we still restart on end/errors to simulate continuous captions.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
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
            // Kick off the first listening session.
            scheduleRestart(delayMs = 100)
        }
    }

    fun stop() {
        isRunning = false
        isListening = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
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
                    isListening = false
                    if (isRunning) scheduleRestart(delayMs = 250)
                }

                override fun onError(error: Int) {
                    if (!isRunning) return
                    isListening = false

                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                        SpeechRecognizer.ERROR_NO_MATCH -> 450L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 450L
                        else -> 650L
                    }
                    scheduleRestart(delayMs = delay)
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (text.isNotEmpty()) translateAndEmit(text)

                    isListening = false
                    if (isRunning) scheduleRestart(delayMs = 300)
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (!isRunning) return@Runnable
            startListeningSafely()
        }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun startListeningSafely() {
        if (!isRunning) return
        if (isListening) return
        isListening = true
        try {
            recognizer?.startListening(listenIntent)
        } catch (e: Exception) {
            isListening = false
            onStatus("Recognizer error: ${e.message}")
            scheduleRestart(delayMs = 900)
        }
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

        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                isTranslatorReady = true
                onReady()
            }
            .addOnFailureListener { e ->
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
                .addOnFailureListener { fallbackTranslate(arabicText) }
            return
        }
        fallbackTranslate(arabicText)
    }

    private fun fallbackTranslate(arabicText: String) {
        onStatus("Translating...")
        Thread {
            val heb = try {
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
