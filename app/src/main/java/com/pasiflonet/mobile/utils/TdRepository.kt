package com.pasiflonet.mobile.utils

object TdRepository {
    // Stub – כאן תממש TDLib Client עם tdjson
    suspend fun login(phone: String, code: String, password: String?) { /* TODO */ }
    fun getChats(): List<Any> = emptyList() // stub
    fun downloadFile(fileId: Int): String = "" // מחזיר path זמני
    fun sendFile(chatId: Long, path: String) { /* TODO */ }
}
