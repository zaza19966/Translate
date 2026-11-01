package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

class TranslateService : Service() {

    private class Item(val box: Rect, val src: String, val lines: Int)

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var touching = false
    private var overlay: FrameLayout? = null

    private var auto = false
    private var busy = false
    private var ready = false
    private var lastSig = ""
    private val cache = HashMap<String, String>()

    // الفاصل بين كل فحص وآخر (بالمللي ثانية). قلّله لسرعة أكبر، وزِده لتقليل الوميض والاستهلاك
    private val interval = 1200L

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )

    private val tick = Runnable { if (auto && !busy) cycle() }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        wm = getSystemService(WindowManager::class.java)

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        setupCapture()
        addButton()

        translator.downloadModelIfNeeded()
            .addOnSuccessListener { ready = true; toast("المترجم جاهز") }
            .addOnFailureListener { toast("فشل تنزيل نموذج الترجمة، تأكد من الإنترنت") }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("t", "المترجم", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, "t")
            .setContentTitle("مترجم الألعاب يعمل")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .build()
    }

    // ---------- التقاط الشاشة ----------

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(0).getRealMetrics(dm)
        return dm
    }

    private fun setupCapture() {
        val dm = screen()
        w = dm.widthPixels; h = dm.heightPixels; dpi = dm.densityDpi
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay(
            "cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    // عند تدوير الشاشة
    private fun syncSize() {
        val dm = screen()
        if (dm.widthPixels != w || dm.heightPixels != h) {
            w = dm.widthPixels; h = dm.heightPixels
            reader?.close()
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd?.resize(w, h, dpi)
            vd?.surface = reader!!.surface
        }
    }

    // ---------- الحلقة التلقائية ----------

    private fun next() {
        busy = false
        if (auto) handler.postDelayed(tick, interval)
    }

    private fun setHidden(hide: Boolean) {
        overlay?.alpha = if (hide) 0f else 1f
    }

    private fun cycle() {
        busy = true
        if (!ready) { next(); return }
        syncSize()
        setHidden(true) // نخفي الترجمة لحظة الالتقاط حتى لا يقرأها الـ OCR
        handler.postDelayed({ grab(0) }, 130)
    }

    private fun grab(tries: Int) {
        val img = reader?.acquireLatestImage()
        if (img == null) {
            if (tries < 5) {
                handler.postDelayed({ grab(tries + 1) }, 100)
            } else {
                setHidden(false); next()
            }
            return
        }
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val bmp = Bitmap.createBitmap(
            img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(p.buffer)
        img.close()
        setHidden(false)

        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { handleText(it) }
            .addOnFailureListener { next() }
    }

    private fun buttonRect(): Rect? {
        val b = button ?: return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    private fun handleText(t: Text) {
        if (!auto) { busy = false; return }
        val btn = buttonRect()
        val items = ArrayList<Item>()
        for (b in t.textBlocks) {
            val box = b.boundingBox ?: continue
            val src = b.text.replace("\n", " ").trim()
            if (src.count { it.isLetter() } < 3) continue
            if (src.any { it in '\u0600'..'\u06FF' }) continue
            if (btn != null && Rect.intersects(btn, box)) continue
            items.add(Item(box, src, b.lines.size))
        }

        val sig = items.joinToString("|") { "${it.src}@${it.box.left / 40},${it.box.top / 40}" }
        if (sig == lastSig) { next(); return }
        lastSig = sig

        if (items.isEmpty()) { removeOverlay(); next(); return }

        val results = arrayOfNulls<String>(items.size)
        var pending = items.size
        fun done() {
            pending--
            if (pending == 0) { render(items, results); next() }
        }
        if (cache.size > 500) cache.clear()
        items.forEachIndexed { i, item ->
            val cached = cache[item.src]
            if (cached != null) {
                results[i] = cached
                done()
            } else {
                translator.translate(item.src)
                    .addOnSuccessListener { r -> cache[item.src] = r; results[i] = r; done() }
                    .addOnFailureListener { done() }
            }
        }
    }

    // ---------- العرض ----------

    private fun render(items: List<Item>, results: Array<String?>) {
        if (!auto) return
        val root = FrameLayout(this)
        for (i in items.indices) {
            val ar = results[i] ?: continue
            val box = items[i].box
            val tv = TextView(this).apply {
                text = ar
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                val px = (box.height() / max(1, items[i].lines)) * 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px.coerceAtLeast(26f))
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.END
                setPadding(10, 4, 10, 4)
            }
            val params = FrameLayout.LayoutParams(
                max(box.width(), 240), FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = box.left; topMargin = box.top }
            root.addView(tv, params)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val old = overlay
        overlay = root
        wm.addView(root, lp)
        old?.let { runCatching { wm.removeView(it) } }
        bringButtonToFront()
    }

    private fun bringButtonToFront() {
        val b = button ?: return
        val bp = buttonParams ?: return
        if (touching) return
        runCatching { wm.removeView(b) }
        runCatching { wm.addView(b, bp) }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- الزر العائم ----------

    private fun addButton() {
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        buttonParams = bp

        val b = TextView(this).apply {
            text = "ترجمة تلقائية"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1565C0.toInt())
            textSize = 16f
            setPadding(36, 24, 36, 24)
        }
        b.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touching = true
                        sx = bp.x; sy = bp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        if (moved) { bp.x = sx + dx; bp.y = sy + dy; wm.updateViewLayout(v, bp) }
                    }
                    MotionEvent.ACTION_UP -> {
                        touching = false
                        if (!moved) onTap()
                    }
                    MotionEvent.ACTION_CANCEL -> touching = false
                }
                return true
            }
        })
        button = b
        wm.addView(b, bp)
    }

    private fun onTap() {
        auto = !auto
        if (auto) {
            button?.text = "■ إيقاف"
            if (!ready) toast("انتظر تنزيل نموذج الترجمة")
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            lastSig = ""
            removeOverlay()
            button?.text = "ترجمة تلقائية"
        }
    }

    private fun toast(s: String) =
        handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        auto = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        button?.let { runCatching { wm.removeView(it) } }
        vd?.release()
        reader?.close()
        projection?.stop()
        translator.close()
        recognizer.close()
        super.onDestroy()
    }
}
