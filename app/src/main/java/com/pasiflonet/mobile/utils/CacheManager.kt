package com.pasiflonet.mobile.utils

import android.content.Context
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale

object CacheManager {

    // יעד: להשאיר את tdlib_files עד ~1.2GB כדי שלא יתנפח בלי סוף
    private const val TDLIB_FILES_CAP_BYTES: Long = 600L * 1024L * 1024L

    /** מציג גודל "אמיתי" (cache + externalCache + tdlib_files) */
    fun getCacheSize(context: Context): String {
        return try {
            val tdlibDir = File(context.filesDir, "tdlib_files")
            val total =
                getDirSize(context.cacheDir) +
                getDirSize(context.externalCacheDir) +
                getDirSize(tdlibDir)
            formatSize(total)
        } catch (_: Exception) {
            "0 MB"
        }
    }

    /**
     * הכפתור "נקה מטמון" קורא לזה בהרבה מקומות.
     * פה אנחנו הופכים את זה לניקוי עמוק — בלי למחוק התחברות:
     * ✅ מוחק: cacheDir / externalCacheDir / files/tdlib_files + תיקיות עיבוד שלנו
     * ❌ לא נוגע: files/tdlib_db (DB של TDLib, כולל auth)
     */
    fun clearAppCache(context: Context) {
        deepCleanNoLogout(context)
    }

    /** מוחק קבצי מדיה מקומיים של ההודעה (רק מה שמותר למחוק). */
    fun deleteTempForMessage(context: Context, msg: TdApi.Message) {
        val tdlibDir = File(context.filesDir, "tdlib_files")
        val allowed = listOfNotNull(context.cacheDir, context.externalCacheDir, tdlibDir)

        fun canDelete(f: File): Boolean {
            return try {
                val c = f.canonicalFile
                allowed.any { parent -> c.path.startsWith(parent.canonicalFile.path) }
            } catch (_: Exception) { false }
        }

        fun delPath(path: String?) {
            if (path.isNullOrBlank()) return
            try {
                val f = File(path)
                if (f.exists() && f.isFile && canDelete(f)) f.delete()
            } catch (_: Exception) {}
        }

        when (val c = msg.content) {
            is TdApi.MessagePhoto -> c.photo.sizes.forEach { ps -> delPath(ps.photo.local.path) }
            is TdApi.MessageVideo -> {
                delPath(c.video.video.local.path)
                delPath(c.video.thumbnail?.file?.local?.path)
            }
            is TdApi.MessageAnimation -> {
                delPath(c.animation.animation.local.path)
                delPath(c.animation.thumbnail?.file?.local?.path)
            }
            is TdApi.MessageDocument -> {
                delPath(c.document.document.local.path)
                delPath(c.document.thumbnail?.file?.local?.path)
            }
        }
    }

    /**
     * ניקוי קבצי temp ישנים מתוך cacheDir כדי שלא יגדל בלי סוף.
     * בנוסף: מאכף תקרת נפח לתיקיית tdlib_files (ככה האחסון לא יתנפח שוב).
     */
    fun pruneAppTempFiles(context: Context, keep: Int = 250) {
        try {
            val dir = context.cacheDir ?: return
            val prefixes = listOf("sent_", "safe_", "proc_", "processed_", "tmp_", "draft_")
            val files = dir.listFiles()?.filter { it.isFile && prefixes.any { p -> it.name.startsWith(p) } } ?: emptyList()
            if (files.size > keep) {
                val sorted = files.sortedByDescending { it.lastModified() }
                sorted.drop(keep).forEach { f -> try { f.delete() } catch (_: Exception) {} }
            }
        } catch (_: Exception) {}

        // מאוד חשוב: מאכף תקרה ל-tdlib_files כדי שלא יגיע לג׳יגות
        try { enforceTdlibFilesCap(context, TDLIB_FILES_CAP_BYTES) } catch (_: Exception) {}
    }

    /** ניקוי עמוק: cache + externalCache + tdlib_files + תיקיות עיבוד שלנו. */
    fun deepCleanNoLogout(context: Context) {
        // לא לגעת בזה:
        // val tdlibDb = File(context.filesDir, "tdlib_db")

        val cache = context.cacheDir
        val extCache = context.externalCacheDir
        val tdlibFiles = File(context.filesDir, "tdlib_files")

        // גם אם יש לך תיקיות עיבוד משלך
        val processedDirs = listOf(
            File(context.filesDir, "processed"),
            File(context.filesDir, "tmp"),
            File(context.filesDir, "out"),
        )

        // מוחקים את תוכן cache/externalCache
        try { cache?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        try { extCache?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}

        // מוחקים את תוכן tdlib_files (זה מה שתופס ג׳יגות), אבל לא נוגעים ב-tdlib_db
        try {
            if (tdlibFiles.exists()) tdlibFiles.listFiles()?.forEach { it.deleteRecursively() }
            tdlibFiles.mkdirs()
        } catch (_: Exception) {}

        // מוחקים תיקיות עיבוד שלנו (לא DB)
        processedDirs.forEach { d ->
            try { if (d.exists()) d.deleteRecursively() } catch (_: Exception) {}
        }

        // אחרי ניקוי עמוק, מאכפים תקרה (למקרה שיתחיל לרדת מחדש)
        try { enforceTdlibFilesCap(context, TDLIB_FILES_CAP_BYTES) } catch (_: Exception) {}
    }

    /** מוחק קבצים הכי ישנים מ-tdlib_files עד שהגודל יורד מתחת לתקרה. */
    private fun enforceTdlibFilesCap(context: Context, maxBytes: Long) {
        val dir = File(context.filesDir, "tdlib_files")
        if (!dir.exists() || !dir.isDirectory) return

        var total = getDirSize(dir)
        if (total <= maxBytes) return

        // מוחקים קבצים בלבד (לא תיקיות), מהישן לחדש
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedBy { it.lastModified() }

        for (f in files) {
            if (total <= maxBytes) break
            val len = try { f.length() } catch (_: Exception) { 0L }
            try { f.delete() } catch (_: Exception) {}
            total -= len
        }
    }

    private fun getDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var size = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                try { size += f.length() } catch (_: Exception) {}
            }
        }
        return size
    }

    private fun formatSize(size: Long): String {
        val mb = size.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
