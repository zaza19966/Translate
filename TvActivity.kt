package com.example.gametranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** تظهر على التلفزيون: رمز الاقتران + حالة الاتصال. تُتحكم بالأسهم وزر موافق */
class TvActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var qr: ImageView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var warn: TextView
    private lateinit var grantBtn: Button
    private var shownPayload = ""

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 30, 60, 30)
            setBackgroundColor(0xFF101820.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(col)

        col.addView(TextView(this).apply {
            text = "اقتران التلفزيون بالهاتف"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "افتح التطبيق على الهاتف ← «ترجمة بالكاميرا» ← اقتران ← وجّه الكاميرا إلى الرمز"
            textSize = 18f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        })
        qr = ImageView(this)
        col.addView(qr, LinearLayout.LayoutParams(460, 460))
        info = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 4)
        }
        col.addView(info)
        status = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }
        col.addView(status)
        warn = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFCC80.toInt())
            gravity = Gravity.CENTER
        }
        col.addView(warn)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        grantBtn = Button(this).apply {
            text = "منح إذن العرض فوق التطبيقات"
            setOnClickListener { askOverlay() }
        }
        val testBtn = Button(this).apply {
            text = "ترجمة تجريبية"
            setOnClickListener {
                startService(Intent(this@TvActivity, TvService::class.java).setAction("test"))
            }
        }
        val stopBtn = Button(this).apply {
            text = "إيقاف الاستقبال"
            setOnClickListener {
                stopService(Intent(this@TvActivity, TvService::class.java))
                finish()
            }
        }
        row.addView(grantBtn)
        row.addView(testBtn)
        row.addView(stopBtn)
        col.addView(row)

        startForegroundService(Intent(this, TvService::class.java))
        testBtn.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun askOverlay() {
        val uri = Uri.parse("package:$packageName")
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
            Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // نجرّب المسار التالي
            }
        }
        Toast.makeText(
            this,
            "لا توجد صفحة الإذن في هذا التلفزيون. سيُعرض نص الترجمة كرسالة قصيرة بدلاً من اللوحة",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refresh() {
        val ip = Pairing.localIp()
        if (ip == null) {
            shownPayload = ""
            qr.setImageBitmap(null)
            info.text = "تأكد من اتصال التلفزيون بالشبكة (واي فاي أو كابل)"
        } else {
            val token = Pairing.ensureToken(this)
            val payload = Pairing.payload(ip, token)
            if (payload != shownPayload) {
                shownPayload = payload
                qr.setImageBitmap(Pairing.qrBitmap(payload, 460))
            }
            info.text = "العنوان: $ip   |   رمز الاقتران: $token"
        }
        if (TvState.clients > 0) {
            status.text = "✔ الهاتف متصل"
            status.setTextColor(0xFF81C784.toInt())
        } else {
            status.text = if (TvState.serverUp) "بانتظار اتصال الهاتف…" else "جارٍ تشغيل الاستقبال…"
            status.setTextColor(0xFFFFD54F.toInt())
        }
        val ok = Settings.canDrawOverlays(this)
        grantBtn.visibility = if (ok) View.GONE else View.VISIBLE
        warn.text = when {
            !ok -> "بدون إذن العرض فوق التطبيقات ستظهر الترجمة كرسالة نصية قصيرة بدل اللوحة"
            TvState.lastError.isNotEmpty() && !TvState.serverUp -> "خطأ: ${TvState.lastError}"
            else -> ""
        }
    }
}
