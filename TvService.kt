package com.example.gametranslator

import android.util.TypedValue
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.max

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
                if (TvState.clients <= 0) main.post { showPanel(emptyList(), false); clearBoxes() }
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
                main.post { clearBoxes(); showPanel(list, bottom) }
            }
            "boxes" -> main.post { showBoxes(o) }
            "clear" -> main.post { showPanel(emptyList(), false); clearBoxes() }
        }
    }

    private var fallbackToast: Toast? = null
    private var toastText = ""
    private var toastBottom = true
    private var toastUntil = 0L
    private val toastLoop = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() < toastUntil && toastText.isNotEmpty()) {
                drawToast()
                main.postDelayed(this, 3300)
            } else {
                fallbackToast?.cancel()
                fallbackToast = null
            }
        }
    }

    /** بديل اللوحة عند غياب إذن العرض: رسالة منسّقة تتجدد كل ثواني قليلة */
    private fun showToastFallback(lines: List<String>, bottom: Boolean) {
        toastText = lines.joinToString("\n")
        toastBottom = bottom
        toastUntil = SystemClock.uptimeMillis() + 12000
        main.removeCallbacks(toastLoop)
        main.post(toastLoop)
    }

    private fun stopToast() {
        toastUntil = 0
        toastText = ""
        main.removeCallbacks(toastLoop)
        fallbackToast?.cancel()
        fallbackToast = null
    }

    @Suppress("DEPRECATION")
    private fun drawToast() {
        fallbackToast?.cancel()
        // من أندرويد 11 وهدف 30 فأعلى تُمنع الرسائل المخصصة من الخلفية، فنستعمل نصاً عادياً
        if (applicationInfo.targetSdkVersion >= 30) {
            fallbackToast = Toast.makeText(this, toastText, Toast.LENGTH_LONG).also { it.show() }
            return
        }
        val dm = resources.displayMetrics
        val tv = TextView(this).apply {
            text = toastText
            setTextColor(0xFFFFE082.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(36, 20, 36, 20)
            maxWidth = (dm.widthPixels * 0.8f).toInt()
            background = GradientDrawable().apply {
                setColor(0xE6000000.toInt())
                cornerRadius = 24f
            }
        }
        val t = Toast(this)
        t.view = tv
        t.duration = Toast.LENGTH_LONG
        t.setGravity((if (toastBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL, 0, 80)
        t.show()
        fallbackToast = t
    }

    // ---------- الترجمة فوق النص الأصلي (مواضع نسبية من الهاتف) ----------

    private var boxRoot: FrameLayout? = null
    private val boxHide = Runnable { clearBoxes() }

    private fun ensureBoxRoot(): FrameLayout? {
        boxRoot?.let { return it }
        if (!Settings.canDrawOverlays(this)) return null
        val root = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(root, lp) } catch (e: Exception) { return null }
        boxRoot = root
        return root
    }

    private fun clearBoxes() {
        main.removeCallbacks(boxHide)
        boxRoot?.removeAllViews()
    }

    private fun showBoxes(o: JSONObject) {
        val arr = o.optJSONArray("items")
        if (arr == null || arr.length() == 0) { clearBoxes(); return }
        val root = ensureBoxRoot() ?: return
        showPanel(emptyList(), false)          // نخفي اللوحة النصية في هذا الوضع
        root.removeAllViews()
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val bg = o.optInt("bg", 80).coerceIn(0, 100)
        val scale = o.optInt("txt", 100).coerceIn(40, 200) / 100f
        val rtl = o.optBoolean("rtl", true)
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val x = (b.optDouble("x") * sw).toInt()
            val y = (b.optDouble("y") * sh).toInt()
            val w = max(60, (b.optDouble("w") * sw).toInt())
            val h = max(28, (b.optDouble("h") * sh).toInt())
            val tv = TextView(this).apply {
                text = b.optString("t")
                setTextColor(b.optInt("c", Color.WHITE))
                gravity = Gravity.CENTER
                textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                setPadding(6, 2, 6, 2)
                setBackgroundColor(Color.argb(bg * 255 / 100, 0, 0, 0))
                if (bg < 50) setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setAutoSizeTextTypeUniformWithConfiguration(
                    10, max(24, (64 * scale).toInt()), 2, TypedValue.COMPLEX_UNIT_PX
                )
            }
            root.addView(tv, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = x
                topMargin = y
            })
        }
        main.removeCallbacks(boxHide)
        main.postDelayed(boxHide, 20000)
    }

    private fun showPanel(lines: List<String>, bottom: Boolean) {
        TvState.lines = lines
        main.removeCallbacks(hideRunnable)
        if (!Settings.canDrawOverlays(this)) {
            // بدون إذن العرض فوق التطبيقات: نعرض الترجمة كرسالة نصية قصيرة (لا تحتاج إذناً)
            if (lines.isEmpty()) stopToast() else showToastFallback(lines, bottom)
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
        boxRoot?.let { runCatching { wm.removeView(it) } }
        boxRoot = null
        super.onDestroy()
    }
}
