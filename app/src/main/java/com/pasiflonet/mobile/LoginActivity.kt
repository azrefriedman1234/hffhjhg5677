package com.pasiflonet.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pasiflonet.mobile.utils.TdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI for phone, code, 2FA
        CoroutineScope(Dispatchers.IO).launch {
            TdRepository.login("phone", "code", "password")
        }
    }
}
