package com.pasiflonet.mobile.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TranslationManager {

    suspend fun translateToHebrew(text: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // שימוש ב-he במקום iw (התקן המודרני)
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=he&dt=t&q=$encodedText"
                
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                
                // --- התיקון הגדול: התחזות לדפדפן כרום ---
                // בלי השורה הזו, גוגל חוסם את האפליקציה ומחזיר את דף השגיאה שקיבלת
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
                
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    // פענוח ה-JSON של גוגל
                    parseGoogleResponse(response.toString())
                } else {
                    // במקרה של שגיאה, מחזירים את המקור כדי לא לקרוס
                    text 
                }
            } catch (e: Exception) {
                e.printStackTrace()
                text // במקרה של תקלה ברשת, מחזירים את הטקסט המקורי
            }
        }
    }

    private fun parseGoogleResponse(jsonStr: String): String {
        try {
            // המבנה של גוגל הוא מערך בתוך מערך
            // [[["שלום","Hello",null,null,1]],...]
            val jsonArray = JSONArray(jsonStr)
            val firstBlock = jsonArray.getJSONArray(0)
            
            val result = StringBuilder()
            // טקסט ארוך מחולק למספר חלקים, מחברים אותם
            for (i in 0 until firstBlock.length()) {
                val sentence = firstBlock.getJSONArray(i)
                result.append(sentence.getString(0))
            }
            return result.toString()
        } catch (e: Exception) {
            return jsonStr // אם הפענוח נכשל, נחזיר מה שיש
        }
    }
}
