package com.pasiflonet.mobile.utils

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    fun install(app: Application) {
        // write "alive" marker on every start (proves logger is running)
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            saveToDownloads(app, "pasiflonet_logger_alive.txt", "ALIVE: $ts\n")
        } catch (_: Throwable) {}

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== UNCAUGHT EXCEPTION ===")
                pw.println("Thread: ${thread.name}")
                pw.println("Type: ${throwable::class.java.name}")
                pw.println("Message: ${throwable.message}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.flush()
                saveToDownloads(app, "pasiflonet_crash_last.txt", sw.toString())
            } catch (_: Throwable) {}

            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun saveToDownloads(app: Application, fileName: String, text: String) {
        // Primary: MediaStore to public Downloads (Android 10+)
        try {
            writeTextToDownloads(app.contentResolver, fileName, text, "Download/PasiflonetLogs/")
            return
        } catch (_: Throwable) {}

        // Fallback: app external files dir
        try {
            val dir = File(app.getExternalFilesDir("logs"), "")
            dir.mkdirs()
            File(dir, fileName).writeText(text)
        } catch (_: Throwable) {}
    }

    private fun writeTextToDownloads(resolver: ContentResolver, fileName: String, text: String, relPath: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = findExisting(resolver, collection, fileName, relPath) ?: run {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            resolver.insert(collection, values)
        } ?: throw IllegalStateException("Failed to create Downloads file")

        resolver.openOutputStream(uri, "w")?.use { os ->
            os.write(text.toByteArray(Charsets.UTF_8))
            os.flush()
        } ?: throw IllegalStateException("Failed to open output stream")

        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    private fun findExisting(resolver: ContentResolver, collection: Uri, fileName: String, relPath: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, relPath)
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
