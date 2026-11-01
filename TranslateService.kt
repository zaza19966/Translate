package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- الإعدادات المشتركة بين الشاشة والخدمة ----------

object Prefs {
    const val NAME = "settings"
    const val SRC = "src"            // "auto" أو رمز لغة
    const val TGT = "tgt"
    const val REGION = "region"      // 0 كامل، 1 النصف السفلي، 2 الثلث السفلي
    const val INTERVAL = "interval"  // مللي ثانية
    const val REFRESH = "refresh"    // مللي ثانية
    const val OPACITY = "opacity"    // 40..100
    const val SMART = "smart"        // فلتر اللغة الذكي
}

data class AppSettings(
    val src: String,
    val tgt: String,
    val region: Int,
    val interval: Long,
    val refresh: Long,
    val opacity: Int,
    val smart: Boolean
)

fun loadSettings(ctx: Context): AppSettings {
    val p = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    return AppSettings(
        src = p.getString(Prefs.SRC, "en") ?: "en",
        tgt = p.getString(Prefs.TGT, "ar") ?: "ar",
        region = p.getInt(Prefs.REGION, 0),
        interval = p.getInt(Prefs.INTERVAL, 1000).toLong(),
        refresh = p.getInt(Prefs.REFRESH, 4000).toLong(),
        opacity = p.getInt(Prefs.OPACITY, 90),
        smart = p.getBoolean(Prefs.SMART, true)
    )
}

// ---------- الخدمة ----------

class TranslateService : Service() {

    private class Cand(val box: Rect, val src: String, val lines: Int)
    private class Tracked(val src: String, val rect: Rect, val view: TextView)

    private val PAD = 6

    private lateinit var wm: WindowManager
    private lateinit var cfg: AppSettings
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    private var overlayRoot: FrameLayout? = null
    private var button: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private var auto = false
    private var busy = false
    private var lastValidation = 0L
    private var toastedDownload = false

    private val tracked = ArrayList<Tracked>()
    private val inflight = HashSet<String>()
    private val ignore = HashSet<String>()
    private val cache = HashMap<String, String>()
    private val translators = HashMap<String, Translator>()
    private val recognizers = HashMap<String, TextRecognizer>()
    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )

    private val tick = Runnable { if (auto && !busy) cycle() }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        wm = getSystemService(WindowManager::class.java)
        cfg = loadSettings(this)

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        setupCapture()
        addOverlayRoot()   // أولاً، ليبقى الزر فوقه
        addButton()
        toast("جاهز. اضغط الزر العائم لبدء الترجمة")
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

    private fun syncSize() {
        val dm = screen()
        if (dm.widthPixels != w || dm.heightPixels != h) {
            w = dm.widthPixels; h = dm.heightPixels
            reader?.close()
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd?.resize(w, h, dpi)
            vd?.surface = reader!!.surface
            removeAllTracked()
        }
    }

    // ---------- الحلقة التلقائية ----------

    private fun next() {
        busy = false
        if (auto) handler.postDelayed(tick, cfg.interval)
    }

    private fun refreshSettings() {
        val n = loadSettings(this)
        if (n.src != cfg.src || n.tgt != cfg.tgt) {
            removeAllTracked()
            cache.clear(); ignore.clear(); inflight.clear()
        }
        cfg = n
    }

    private fun cycle() {
        busy = true
        refreshSettings()
        syncSize()
        val now = SystemClock.uptimeMillis()
        // فحص "نظيف" دوري: نخفي الترجمة لحظة ليرى الـ OCR النص الأصلي ونتأكد أنه لم يتغير
        val clean = tracked.isNotEmpty() && now - lastValidation >= cfg.refresh
        if (clean) {
            lastValidation = now
            overlayRoot?.alpha = 0f
            handler.postDelayed({ grab(0, true) }, 130)
        } else {
            grab(0, false)
        }
    }

    private fun recognizer(): TextRecognizer {
        val key = when (cfg.src) { "ja" -> "ja"; "ko" -> "ko"; "zh" -> "zh"; else -> "latin" }
        return recognizers.getOrPut(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun grab(tries: Int, clean: Boolean) {
        val img = reader?.acquireLatestImage()
        if (img == null) {
            // لا إطار جديد: في الفحص العادي معناها أن الشاشة ثابتة، فلا داعي للعمل
            if (clean && tries < 5) {
                handler.postDelayed({ grab(tries + 1, true) }, 100)
            } else {
                overlayRoot?.alpha = 1f
                next()
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
        overlayRoot?.alpha = 1f

        val top = when (cfg.region) {
            1 -> bmp.height / 2
            2 -> bmp.height * 2 / 3
            else -> 0
        }
        val part = if (top > 0) Bitmap.createBitmap(bmp, 0, top, bmp.width, bmp.height - top) else bmp

        recognizer().process(InputImage.fromBitmap(part, 0))
            .addOnSuccessListener { handleText(it, clean, top) }
            .addOnFailureListener { next() }
    }

    private fun buttonRect(): Rect? {
        val b = button ?: return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    private fun validText(s: String): Boolean {
        val minLetters = if (cfg.src in listOf("ja", "ko", "zh")) 1 else 3
        if (s.count { it.isLetter() } < minLetters) return false
        if (s.any { it in '\u0600'..'\u06FF' }) return false // نص عربي (غالباً من الترجمة نفسها)
        return true
    }

    private fun grow(r: Rect): Rect = Rect(r).apply { inset(-PAD, -PAD) }

    private fun handleText(t: Text, clean: Boolean, offY: Int) {
        if (!auto) { busy = false; return }
        val btn = buttonRect()
        val cands = ArrayList<Cand>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            val box = Rect(bb).apply { offset(0, offY) }
            val src = b.text.replace("\n", " ").trim()
            if (!validText(src)) continue
            if (ignore.contains(src.lowercase())) continue
            if (btn != null && Rect.intersects(btn, box)) continue
            cands.add(Cand(box, src, b.lines.size))
        }

        if (!clean) {
            // فحص عادي: نبحث فقط عن نص جديد لم يُغطَّ بترجمة بعد
            val fresh = cands.filter { c -> tracked.none { coverage(c.box, it.rect) > 0.4f } }
            process(fresh)
        } else {
            // فحص نظيف: نطابق ما نراه مع ما نعرضه، ونزيل ما اختفى، ونضيف الجديد
            val keep = BooleanArray(tracked.size)
            val toAdd = ArrayList<Cand>()
            for (c in cands) {
                val cr = grow(c.box)
                var m = -1
                for (i in tracked.indices) {
                    if (!keep[i] && iou(cr, tracked[i].rect) > 0.4f && similar(c.src, tracked[i].src)) {
                        m = i; break
                    }
                }
                if (m >= 0) keep[m] = true else toAdd.add(c)
            }
            for (i in tracked.indices.reversed()) {
                if (!keep[i]) {
                    overlayRoot?.removeView(tracked[i].view)
                    tracked.removeAt(i)
                }
            }
            process(toAdd)
        }
        next()
    }

    // ---------- الترجمة ----------

    private fun process(list: List<Cand>) {
        for (c in list) {
            val key = "${c.src}@${c.box.left / 50},${c.box.top / 50}"
            if (!inflight.add(key)) continue
            translateAsync(c.src) { dst ->
                inflight.remove(key)
                if (dst == "") ignore.add(c.src.lowercase())
                else if (dst != null && auto) addTracked(c, dst)
            }
        }
    }

    /** النتيجة: نص مترجم، أو "" = تجاهل هذا النص نهائياً، أو null = فشل مؤقت */
    private fun translateAsync(src: String, cb: (String?) -> Unit) {
        val s = cfg
        val key = "${s.src}|${s.tgt}|$src"
        cache[key]?.let { cb(it); return }

        fun doTranslate(from: String) {
            val a = TranslateLanguage.fromLanguageTag(from)
            val b = TranslateLanguage.fromLanguageTag(s.tgt)
            if (a == null || b == null || a == b) { cb(""); return }
            val tr = translators.getOrPut("$a>$b") {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build()
                )
            }
            tr.downloadModelIfNeeded()
                .addOnSuccessListener {
                    tr.translate(src)
                        .addOnSuccessListener { r ->
                            val out = if (r.trim().equals(src.trim(), true)) "" else r
                            if (cache.size > 800) cache.clear()
                            cache[key] = out
                            cb(out)
                        }
                        .addOnFailureListener { cb(null) }
                }
                .addOnFailureListener {
                    if (!toastedDownload) {
                        toastedDownload = true
                        toast("تعذّر تنزيل نموذج اللغة، تأكد من الإنترنت")
                    }
                    cb(null)
                }
        }

        if (s.src == "auto" || s.smart) {
            identifier.identifyLanguage(src)
                .addOnSuccessListener { code ->
                    if (s.src == "auto") {
                        if (code == "und" || code == s.tgt) cb("") else doTranslate(code)
                    } else {
                        // فلتر ذكي: تجاهل النص إذا كان بلغة أخرى غير لغة اللعبة
                        if (code != "und" && code != s.src) cb("") else doTranslate(s.src)
                    }
                }
                .addOnFailureListener { cb(null) }
        } else {
            doTranslate(s.src)
        }
    }

    // ---------- العرض ----------

    private fun isRtl() = cfg.tgt in setOf("ar", "fa", "ur", "he")

    private fun addTracked(c: Cand, dst: String) {
        val root = overlayRoot ?: return
        val rect = grow(c.box)
        if (tracked.any { coverage(c.box, it.rect) > 0.4f }) return
        val tv = TextView(this).apply {
            text = dst
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(cfg.opacity * 255 / 100, 0, 0, 0))
            gravity = Gravity.CENTER
            textDirection = if (isRtl()) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
            setPadding(8, 2, 8, 2)
            setAutoSizeTextTypeUniformWithConfiguration(14, 64, 2, TypedValue.COMPLEX_UNIT_PX)
        }
        val lp = FrameLayout.LayoutParams(max(rect.width(), 140), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        root.addView(tv, lp)
        tracked.add(Tracked(c.src, rect, tv))
    }

    private fun removeAllTracked() {
        overlayRoot?.removeAllViews()
        overlayRoot?.alpha = 1f
        tracked.clear()
    }

    private fun addOverlayRoot() {
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
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
        overlayRoot = root
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
                        sx = bp.x; sy = bp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        if (moved) { bp.x = sx + dx; bp.y = sy + dy; wm.updateViewLayout(v, bp) }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap()
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
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            inflight.clear()
            removeAllTracked()
            button?.text = "ترجمة تلقائية"
        }
    }

    // ---------- أدوات مساعدة ----------

    private fun inter(a: Rect, b: Rect): Long {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        return if (r > l && bt > t) (r - l).toLong() * (bt - t) else 0L
    }

    private fun area(a: Rect): Long = max(1L, a.width().toLong() * a.height())

    private fun coverage(c: Rect, t: Rect): Float = inter(c, t).toFloat() / area(c)

    private fun iou(a: Rect, b: Rect): Float {
        val i = inter(a, b)
        return i.toFloat() / (area(a) + area(b) - i)
    }

    private fun similar(a: String, b: String): Boolean {
        val x = a.lowercase().trim(); val y = b.lowercase().trim()
        if (x == y) return true
        val n = max(x.length, y.length)
        if (n == 0) return true
        return editDistance(x, y).toFloat() / n <= 0.25f
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun toast(s: String) =
        handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        auto = false
        handler.removeCallbacksAndMessages(null)
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        button?.let { runCatching { wm.removeView(it) } }
        vd?.release()
        reader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        identifier.close()
        super.onDestroy()
    }
}
