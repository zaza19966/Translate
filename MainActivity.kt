package com.example.gametranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private val REQ = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val start = Button(this).apply { text = "ابدأ المترجم"; textSize = 20f }
        val stop = Button(this).apply { text = "إيقاف"; textSize = 20f }
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)

        start.setOnClickListener { begin() }
        stop.setOnClickListener {
            stopService(Intent(this, TranslateService::class.java))
        }
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل إذن العرض فوق التطبيقات ثم ارجع", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslateService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true) // افتح تطبيق Xbox بعدها
        }
    }
}
