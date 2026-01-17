package com.pasiflonet.mobile.td

import android.content.Context
import android.os.Build
import com.pasiflonet.mobile.utils.CacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TdLibManager {

    private var client: Client? = null
    private var appContext: Context? = null
    private var isAuthorized: Boolean = false

    private const val MAX_MESSAGES = 100

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _currentMessages = MutableStateFlow<List<TdApi.Message>>(emptyList())
    val currentMessages: StateFlow<List<TdApi.Message>> = _currentMessages

    fun init(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        appContext = context.applicationContext

        Client.execute(TdApi.SetLogVerbosityLevel(0))

        client = Client.create({ update ->
            when (update) {
                is TdApi.UpdateAuthorizationState -> handleAuth(update.authorizationState, apiId, apiHash)
                is TdApi.UpdateNewMessage -> {
                    val current = _currentMessages.value.toMutableList()

                    // הוסף את ההודעה החדשה ואז מיין "מהחדש לישן"
                    current.add(update.message)
                    current.sortWith(compareByDescending<TdApi.Message> { it.date }.thenByDescending { it.id })

                    // הגבלה ל-100 הודעות + מחיקת קבצי temp של הישנות
                    while (current.size > MAX_MESSAGES) {
                        val removed = current.removeAt(current.size - 1) // oldest after sort
                        appContext?.let { ctx ->
                            try {
                                CacheManager.deleteTempForMessage(ctx, removed)
                                CacheManager.pruneAppTempFiles(ctx, 250)
                            } catch (_: Exception) {}
                        }
                    }

                    _currentMessages.value = current.toList()
                }
            }
        }, null, null)
    }

    private fun handleAuth(state: TdApi.AuthorizationState, apiId: Int, apiHash: String) {
        _authState.value = state

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val ctx = appContext ?: return
                val dbDir = File(ctx.filesDir, "tdlib_db").absolutePath
                val filesDir = File(ctx.filesDir, "tdlib_files").absolutePath

                // tdlib.aar אצלך לא כולל TdlibParameters(), אז משתמשים ב-SetTdlibParameters
                val p = TdApi.SetTdlibParameters(
                    false,
                    dbDir,
                    filesDir,
                    null,
                    true,
                    true,
                    true,
                    false,
                    apiId,
                    apiHash,
                    "en",
                    Build.MODEL ?: "Android",
                    Build.VERSION.RELEASE ?: "Android",
                    "Azretr"
                )

                client?.send(p) {}
            }

            is TdApi.AuthorizationStateReady -> isAuthorized = true
            is TdApi.AuthorizationStateClosed -> isAuthorized = false
        }
    }

    fun sendPhone(phone: String, onError: (String) -> Unit) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { r ->
            if (r is TdApi.Error) onError(r.message)
        }
    }

    fun sendCode(code: String, onError: (String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { r ->
            if (r is TdApi.Error) onError(r.message)
        }
    }

    fun sendPassword(password: String, onError: (String) -> Unit) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { r ->
            if (r is TdApi.Error) onError(r.message)
        }
    }

    fun downloadFile(fileId: Int) {
        client?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) {}
    }

    // ===== תאימות ל-DetailsActivity הישן: מחזיר String? (blocking קצר)
    fun getFilePath(fileId: Int): String? {
        if (fileId == 0) return null
        val c = client ?: return null

        val latch = CountDownLatch(1)
        var out: String? = null

        c.send(TdApi.GetFile(fileId)) { r ->
            if (r is TdApi.File) out = r.local?.path
            latch.countDown()
        }

        // מחכים מעט (כדי לא לתקוע UI יותר מדי)
        latch.await(1500, TimeUnit.MILLISECONDS)
        return out
    }

    // הגרסה האסינכרונית (חדשה)
    fun getFilePath(fileId: Int, onResult: (String?) -> Unit) {
        val c = client ?: run { onResult(null); return }
        c.send(TdApi.GetFile(fileId)) { r ->
            if (r is TdApi.File) onResult(r.local?.path) else onResult(null)
        }
    }

    // ===== שליחה: overload לתאימות אם מועבר Boolean
    fun sendFinalMessage(targetUsername: String, caption: String, filePath: String?, silent: Boolean) {
        sendFinalMessage(targetUsername, caption, filePath) { /* ignore */ }
    }

    fun sendFinalMessage(
        targetUsername: String,
        caption: String,
        filePath: String?,
        onError: (String) -> Unit = {}
    ) {
        if (!isAuthorized) { onError("Not authorized"); return }

        val username = targetUsername.trim().removePrefix("@")
        if (username.isBlank()) { onError("Target username is empty"); return }

        val c = client ?: run { onError("Client null"); return }

        c.send(TdApi.SearchPublicChat(username)) { chatRes ->
            when (chatRes) {
                is TdApi.Error -> { onError(chatRes.message); return@send }
                !is TdApi.Chat -> { onError("Chat not found"); return@send }
            }

            val chatId = (chatRes as? TdApi.Chat)?.id ?: return@send

            val content: TdApi.InputMessageContent =
                if (filePath.isNullOrBlank()) {
                    TdApi.InputMessageText(TdApi.FormattedText(caption, null), null, false)
                } else {
                    val f = File(filePath)
                    val input = TdApi.InputFileLocal(f.absolutePath)
                    val ft = TdApi.FormattedText(caption, null)

                    if (filePath.endsWith(".mp4", true)) {
                        TdApi.InputMessageVideo(
                            input,
                            null,
                            null,
                            0,
                            intArrayOf(),
                            0, 0, 0,
                            true,
                            ft,
                            false,
                            null,
                            false
                        )
                    } else {
                        TdApi.InputMessagePhoto(
                            input,
                            null,
                            intArrayOf(),
                            0, 0,
                            ft,
                            false,
                            null,
                            false
                        )
                    }
                }

            // חשוב: topicId אצלך הוא MessageTopic -> לכן שולחים null ולא 0
            c.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { r ->
                if (r is TdApi.Error) onError(r.message)
            }
        }
    }

    fun setOnline(online: Boolean) {
        val c = client ?: return
        try {
            c.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(online))) {}
            c.send(TdApi.SetOption("is_background", TdApi.OptionValueBoolean(!online))) {}
        } catch (_: Exception) {}
    }

}
