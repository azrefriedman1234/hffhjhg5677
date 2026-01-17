package com.pasiflonet.mobile.utils

import android.content.Context

/**
 * המשתמש ביקש: לא ליצור יותר קבצי לוגים.
 * לכן הכל כאן NO-OP.
 *
 * חשוב: יש כאן overloads + vararg כדי לא לשבור קומפילציה
 * מכל מיני קריאות שקיימות בפרויקט (ctx/tag/text/Throwable וכו').
 */
object DownloadLog {
    const val ENABLED: Boolean = false

    // info/error no-op (תופס כל חתימה)
    fun i(vararg args: Any?) {}
    fun e(vararg args: Any?) {}

    // ---- write: מחזיר String כדי לא לשבור קוד שמצפה להחזרה ----
    fun write(text: String): String = text
    fun write(ctx: Context, text: String): String = text
    fun write(tag: String, text: String): String = text
    fun write(ctx: Context, tag: String, text: String): String = text

    // catch-all לכל מקרה (למשל write(ctx, tag, text, throwable))
    fun write(vararg args: Any?): String = args.lastOrNull()?.toString() ?: ""

    // ---- writeWithTimestamp: אותו רעיון ----
    fun writeWithTimestamp(text: String): String = text
    fun writeWithTimestamp(ctx: Context, text: String): String = text
    fun writeWithTimestamp(tag: String, text: String): String = text
    fun writeWithTimestamp(ctx: Context, tag: String, text: String): String = text
    fun writeWithTimestamp(vararg args: Any?): String = write(*args)

    fun writeCrash(vararg args: Any?) {}
    fun writeFfmpeg(vararg args: Any?) {}
}
