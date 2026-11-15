package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** خدمة التلفزيون: تستقبل الترجمة من الهاتف وتعرضها فوق الشاشة */
class TvService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private lateinit var wm: WindowManager
    private var panel: TextView? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var token = ""
    private val hideRunnable = Runnable { showPanel(emptyList(), false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(WindowManager::class.java)
        token = Pairing.ensureToken(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("tv", "التلفزيون", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "tv")
            .setContentTitle("مترجم الألعاب: وضع التلفزيون")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (server == null) startServer()
        if (intent?.action == "test") {
            main.post { showPanel(listOf("ترجمة تجريبية: إذا رأيت هذا النص فاللوحة تعمل"), true) }
        }
        return START_STICKY
    }

    private fun startServer() {
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Pairing.PORT))
                server = ss
                TvState.serverUp = true
                while (!ss.isClosed) {
                    val c = ss.accept()
                    Thread { handleClient(c) }.start()
                }
            } catch (e: Exception) {
                TvState.lastError = e.message ?: ""
            }
            TvState.serverUp = false
            server = null
        }.start()
    }

    private fun handleClient(c: Socket) {
        try {
            val rd = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
            val wr = PrintWriter(OutputStreamWriter(c.getOutputStream(), Charsets.UTF_8), true)
            val hello = rd.readLine() ?: return
            if (hello.trim() != "HELLO $token") {
                wr.println("NO")
                c.close()
                return
            }
            wr.println("OK")
            synchronized(TvState) { TvState.clients++ }
            try {
                while (true) {
                    val line = rd.readLine() ?: break
                    try { onMessage(JSONObject(line)) } catch (e: Exception) { }
                }
            } finally {
                synchronized(TvState) { TvState.clients-- }
                if (TvState.clients <= 0) main.post { showPanel(emptyList(), false) }
            }
        } catch (e: Exception) {
        } finally {
            try { c.close() } catch (e: Exception) { }
        }
    }

    private fun onMessage(o: JSONObject) {
        when (o.optString("cmd")) {
            "show" -> {
                val arr = o.optJSONArray("lines")
                val list = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.optString(i))
                val bottom = o.optString("pos") == "bottom"
                main.post { showPanel(list, bottom) }
            }
            "clear" -> main.post { showPanel(emptyList(), false) }
        }
    }

    private var fallbackToast: Toast? = null

    private fun showToastFallback(text: String) {
        fallbackToast?.cancel()
        fallbackToast = Toast.makeText(this, text, Toast.LENGTH_LONG).also { it.show() }
    }

    private fun showPanel(lines: List<String>, bottom: Boolean) {
        TvState.lines = lines
        main.removeCallbacks(hideRunnable)
        if (!Settings.canDrawOverlays(this)) {
            // بدون إذن العرض فوق التطبيقات: نعرض الترجمة كرسالة نصية قصيرة (لا تحتاج إذناً)
            if (lines.isNotEmpty()) showToastFallback(lines.joinToString("\n"))
            return
        }
        if (lines.isEmpty()) {
            panel?.let { runCatching { wm.removeView(it) } }
            panel = null
            return
        }
        val grav = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        val existing = panel
        if (existing == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(40, 20, 40, 20)
                textDirection = View.TEXT_DIRECTION_RTL
                maxLines = 8
                text = lines.joinToString("\n")
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = grav
            panel = tv
            panelLp = lp
            wm.addView(tv, lp)
        } else {
            existing.text = lines.joinToString("\n")
            val lp = panelLp
            if (lp != null && lp.gravity != grav) {
                lp.gravity = grav
                wm.updateViewLayout(existing, lp)
            }
        }
        main.postDelayed(hideRunnable, 30000)
    }

    override fun onDestroy() {
        try { server?.close() } catch (e: Exception) { }
        main.removeCallbacksAndMessages(null)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        super.onDestroy()
    }
}
