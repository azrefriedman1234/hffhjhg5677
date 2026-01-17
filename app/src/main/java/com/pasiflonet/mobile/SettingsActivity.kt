package com.pasiflonet.mobile

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pasiflonet.mobile.databinding.ActivitySettingsBinding
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : BaseActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val localFile = File(filesDir, "channel_logo.png")
                val outputStream = FileOutputStream(localFile)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()

                val localUri = Uri.fromFile(localFile).toString()
                prefs.edit().putString("logo_uri", localUri).apply()
                
                b.ivCurrentLogo.setImageURI(Uri.parse(localUri))
                Toast.makeText(this, "Logo Saved!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            b = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(b.root)

            prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

            // טעינת נתונים קיימים
            val currentTarget = prefs.getString("target_username", "")
            val currentYoutube = prefs.getString("youtube_url", "")
            val currentLogo = prefs.getString("logo_uri", "")

            if (!currentTarget.isNullOrEmpty()) b.etTargetUsername.setText(currentTarget)
            if (!currentYoutube.isNullOrEmpty()) b.etYoutubeUrl.setText(currentYoutube)
            if (!currentLogo.isNullOrEmpty()) {
                try { b.ivCurrentLogo.setImageURI(Uri.parse(currentLogo)) } catch (e: Exception) {}
            }

            b.btnSaveSettings.setOnClickListener {
                val target = b.etTargetUsername.text.toString().trim()
                val youtubeUrl = b.etYoutubeUrl.text.toString().trim()

                val editor = prefs.edit()
                // לא מבטלים ערכים קיימים אם השדות ריקים
                if (target.isNotEmpty()) editor.putString("target_username", target)
                if (youtubeUrl.isNotEmpty()) editor.putString("youtube_url", youtubeUrl)
                editor.apply()

                if (target.isEmpty() && youtubeUrl.isEmpty()) {
                    Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            b.btnSelectLogo.setOnClickListener { pickLogo.launch("image/*") }
            
            b.btnClearCache.setOnClickListener {
                try {
                    cacheDir.deleteRecursively()
                    Toast.makeText(this, "Cache Cleared", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
