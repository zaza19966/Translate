package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.widget.LinearLayout
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
    const val INTERVAL = "interval"  // فاصل الفحص (مللي ثانية)
    const val REFRESH = "refresh"    // فاصل التحقق من تغيّر النص (0 = إيقاف)
    const val TTL = "ttl"            // عمر الترجمة على الشاشة (0 = بلا حد)
    const val OPACITY = "opacity"    // عتمة الخلفية 40..100
    const val SMART = "smart"        // فلتر اللغة
    const val STRICT = "strict"      // فلتر لغة صارم
    const val CONF = "conf"          // حد ثقة القراءة 0..95
    const val SCALE = "scale"        // جودة الفحص 40..100
    const val GLOSSARY = "glossary"  // قاموس مخصص: نص=ترجمة
    // المناطق: "inc" = منطقة الترجمة، "exc" = منطقة الاستبعاد
    // مفاتيحها: inc_on, inc_l, inc_t, inc_r, inc_b (كنسب من 0 إلى 1)
}

class RegionPref(val on: Boolean, val l: Float, val t: Float, val r: Float, val b: Float)

data class AppSettings(
    val src: String,
    val tgt: String,
    val interval: Long,
    val refresh: Long,
    val ttl: Long,
    val opacity: Int,
    val smart: Boolean,
    val strict: Boolean,
    val conf: Int,
    val scale: Int,
    val inc: RegionPref,
    val exc: RegionPref,
    val glossary: Map<String, String>
)

private fun loadRegion(p: SharedPreferences, key: String) = RegionPref(
    p.getBoolean("${key}_on", false),
    p.getFloat("${key}_l", 0.2f), p.getFloat("${key}_t", 0.2f),
    p.getFloat("${key}_r", 0.8f), p.getFloat("${key}_b", 0.8f)
)

fun loadSettings(ctx: Context): AppSettings {
    val p = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    val gl = HashMap<String, String>()
    for (line in (p.getString(Prefs.GLOSSARY, "") ?: "").lines()) {
        val i = line.indexOf('=')
        if (i > 0) {
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) gl[k] = v
        }
    }
    return AppSettings(
        src = p.getString(Prefs.SRC, "en") ?: "en",
        tgt = p.getString(Prefs.TGT, "ar") ?: "ar",
        interval = p.getInt(Prefs.INTERVAL, 1000).toLong(),
        refresh = p.getInt(Prefs.REFRESH, 4000).toLong(),
        ttl = p.getInt(Prefs.TTL, 0).toLong(),
        opacity = p.getInt(Prefs.OPACITY, 90),
        smart = p.getBoolean(Prefs.SMART, true),
        strict = p.getBoolean(Prefs.STRICT, false),
        conf = p.getInt(Prefs.CONF, 70),
        scale = p.getInt(Prefs.SCALE, 100),
        inc = loadRegion(p, "inc"),
        exc = loadRegion(p, "exc"),
        glossary = gl
    )
}

// ---------- أداة تحديد المنطقة على الشاشة ----------

private class PickerView(
    ctx: Context, private val accent: Int, private val W: Int, private val H: Int
) : View(ctx) {
    var l = 0f; var t = 0f; var r = 0f; var b = 0f
    var fine = false
    private var mode = 0 // 1 = تحريك، وإلا مجموع: 2 يسار، 4 أعلى، 8 يمين، 16 أسفل
    private var lx = 0f
    private var ly = 0f
    private val MIN = 30f
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = accent
    }
    private val fill = Paint().apply { color = accent }
    private val txt = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }

    fun setRect(a: Float, b2: Float, c: Float, d: Float) { l = a; t = b2; r = c; b = d; invalidate() }

    fun fracs() = floatArrayOf(l / W, t / H, r / W, b / H)

    private fun hit(x: Float, y: Float): Int {
        val tol = 70f
        val inV = y > t - tol && y < b + tol
        val inH = x > l - tol && x < r + tol
        var m = 0
        if (inV && abs(x - l) < tol) m = m or 2
        if (inV && abs(x - r) < tol) m = m or 8
        if (inH && abs(y - t) < tol) m = m or 4
        if (inH && abs(y - b) < tol) m = m or 16
        if (m != 0) return m
        if (x in l..r && y in t..b) return 1
        return 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                mode = hit(e.x, e.y)
                if (mode == 0) { // رسم منطقة جديدة
                    l = e.x; t = e.y; r = e.x + 1; b = e.y + 1
                    mode = 8 or 16
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val s = if (fine) 0.25f else 1f
                val dx = (e.x - lx) * s
                val dy = (e.y - ly) * s
                lx = e.x; ly = e.y
                if (mode == 1) {
                    val w0 = r - l; val h0 = b - t
                    l = (l + dx).coerceIn(0f, W - w0)
                    t = (t + dy).coerceIn(0f, H - h0)
                    r = l + w0; b = t + h0
                } else {
                    if ((mode and 2) != 0) l += dx
                    if ((mode and 4) != 0) t += dy
                    if ((mode and 8) != 0) r += dx
                    if ((mode and 16) != 0) b += dy
                    l = l.coerceIn(0f, W.toFloat()); r = r.coerceIn(0f, W.toFloat())
                    t = t.coerceIn(0f, H.toFloat()); b = b.coerceIn(0f, H.toFloat())
                    if (r - l < MIN) { if ((mode and 2) != 0) l = r - MIN else r = l + MIN }
                    if (b - t < MIN) { if ((mode and 4) != 0) t = b - MIN else b = t + MIN }
                }
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = W.toFloat(); val h = H.toFloat()
        c.drawRect(0f, 0f, w, t, dim)
        c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim)
        c.drawRect(r, t, w, b, dim)
        c.drawRect(l, t, r, b, stroke)
        val mx = (l + r) / 2; val my = (t + b) / 2
        for (p in listOf(l to t, r to t, l to b, r to b, mx to t, mx to b, l to my, r to my)) {
            c.drawCircle(p.first, p.second, 14f, fill)
        }
        val info = "${(r - l).toInt()} × ${(b - t).toInt()} px   |   x=${l.toInt()}  y=${t.toInt()}"
        c.drawText(info, 30f, 190f, txt)
    }
}

// ---------- الخدمة ----------

class TranslateService : Service() {

    private class Cand(val box: Rect, val src: String, val lines: Int)
    private class Tracked(val src: String, val rect: Rect, val view: TextView, var seen: Long)

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
    private var bar: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var toggleView: TextView? = null
    private var pickerRoot: View? = null
    private var pickerOpen = false

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

    private val tick = Runnable { if (auto && !busy && !pickerOpen) cycle() }

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
        addOverlayRoot()   // أولاً، ليبقى الشريط فوقه
        addBar()
        toast("جاهز. اضغط ▶ في الشريط العائم لبدء الترجمة")
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
        if (auto) handler.postDelayed(tick, max(200L, cfg.interval))
    }

    private fun sameRegion(a: RegionPref, b: RegionPref) =
        a.on == b.on && a.l == b.l && a.t == b.t && a.r == b.r && a.b == b.b

    private fun refreshSettings() {
        val n = loadSettings(this)
        if (n.src != cfg.src || n.tgt != cfg.tgt ||
            !sameRegion(n.inc, cfg.inc) || !sameRegion(n.exc, cfg.exc) || n.glossary != cfg.glossary
        ) {
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

        // انتهاء عمر الترجمة (إن حدّدت ذلك)
        if (cfg.ttl > 0) {
            for (i in tracked.indices.reversed()) {
                if (now - tracked[i].seen > cfg.ttl) {
                    overlayRoot?.removeView(tracked[i].view)
                    tracked.removeAt(i)
                }
            }
        }

        // فحص "نظيف" دوري: نخفي الترجمة لحظة ليرى الـ OCR النص الأصلي
        val clean = cfg.refresh > 0 && tracked.isNotEmpty() && now - lastValidation >= cfg.refresh
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

    /** يحوّل نسب المنطقة المحفوظة إلى بكسلات الشاشة */
    private fun rectOf(r: RegionPref): Rect {
        val left = (r.l * w).toInt().coerceIn(0, w - 2)
        val top = (r.t * h).toInt().coerceIn(0, h - 2)
        val right = (r.r * w).toInt().coerceIn(left + 2, w)
        val bottom = (r.b * h).toInt().coerceIn(top + 2, h)
        return Rect(left, top, right, bottom)
    }

    private fun grab(tries: Int, clean: Boolean) {
        val img = reader?.acquireLatestImage()
        if (img == null) {
            // لا إطار جديد: في الفحص العادي معناها أن الشاشة ثابتة
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

        val inc = if (cfg.inc.on) rectOf(cfg.inc) else Rect(0, 0, w, h)
        val cx = min(inc.left, bmp.width - 2)
        val cy = min(inc.top, bmp.height - 2)
        val cw = max(2, min(inc.width(), bmp.width - cx))
        val ch = max(2, min(inc.height(), bmp.height - cy))
        var part = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
        val k = cfg.scale / 100f
        if (k < 0.99f) {
            part = Bitmap.createScaledBitmap(
                part, max(1, (cw * k).toInt()), max(1, (ch * k).toInt()), true
            )
        }

        recognizer().process(InputImage.fromBitmap(part, 0))
            .addOnSuccessListener { handleText(it, clean, cx, cy, k) }
            .addOnFailureListener { next() }
    }

    private fun barRect(): Rect? {
        val b = bar ?: return null
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

    private fun handleText(t: Text, clean: Boolean, offX: Int, offY: Int, k: Float) {
        if (!auto) { busy = false; return }
        val btn = barRect()
        val exc = if (cfg.exc.on) rectOf(cfg.exc) else null
        val minConf = cfg.conf / 100f
        val cands = ArrayList<Cand>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            val box = Rect(
                (bb.left / k).toInt() + offX, (bb.top / k).toInt() + offY,
                (bb.right / k).toInt() + offX, (bb.bottom / k).toInt() + offY
            )
            val src = b.text.replace("\n", " ").trim()
            if (!validText(src)) continue
            if (ignore.contains(src.lowercase())) continue
            if (btn != null && Rect.intersects(btn, box)) continue
            if (exc != null && coverage(box, exc) > 0.3f) continue
            // ثقة القراءة: نتجاهل النصوص المشوّشة (رسوم الجدران والأشكال العشوائية)
            if (b.lines.isNotEmpty()) {
                val conf = b.lines.map { it.confidence }.average().toFloat()
                if (conf > 0f && conf < minConf) continue
            }
            cands.add(Cand(box, src, b.lines.size))
        }

        val now = SystemClock.uptimeMillis()
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
                if (m >= 0) { keep[m] = true; tracked[m].seen = now } else toAdd.add(c)
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
        // القاموس المخصص أولاً
        s.glossary[src.lowercase().trim()]?.let { cb(it); return }

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

        if (s.src == "auto" || s.smart || s.strict) {
            identifier.identifyLanguage(src)
                .addOnSuccessListener { code ->
                    if (s.src == "auto") {
                        if (code == "und" || code == s.tgt) cb("") else doTranslate(code)
                    } else if (s.strict) {
                        // صارم: لا نترجم إلا إذا تأكد الكشف أن النص بلغة اللعبة
                        if (code != s.src) cb("") else doTranslate(s.src)
                    } else {
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
        tracked.add(Tracked(c.src, rect, tv, SystemClock.uptimeMillis()))
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

    // ---------- الشريط العائم ----------

    private fun addBar() {
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        barParams = bp

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD1565C0.toInt())
        }
        fun cell(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(26, 20, 26, 20)
        }
        val toggle = cell("▶ ترجمة")
        toggleView = toggle
        row.addView(toggle)
        row.addView(cell("منطقة"))
        row.addView(cell("استبعاد"))
        row.addView(cell("⚙"))

        row.setOnTouchListener(object : View.OnTouchListener {
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
                    MotionEvent.ACTION_UP -> if (!moved) {
                        val x = e.x.toInt()
                        for (i in 0 until row.childCount) {
                            val c = row.getChildAt(i)
                            if (x >= c.left && x < c.right) { onBarAction(i); break }
                        }
                    }
                }
                return true
            }
        })
        bar = row
        wm.addView(row, bp)
    }

    private fun onBarAction(i: Int) {
        when (i) {
            0 -> onTap()
            1 -> openPicker("inc")
            2 -> openPicker("exc")
            3 -> startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun onTap() {
        auto = !auto
        if (auto) {
            toggleView?.text = "■ إيقاف"
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            inflight.clear()
            removeAllTracked()
            toggleView?.text = "▶ ترجمة"
        }
    }

    // ---------- تحديد المنطقة ----------

    private fun openPicker(key: String) {
        if (pickerOpen) return
        pickerOpen = true
        removeAllTracked()
        val dm = screen()
        val accent = if (key == "inc") 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        val saved = if (key == "inc") cfg.inc else cfg.exc
        val pv = PickerView(this, accent, dm.widthPixels, dm.heightPixels)
        pv.setRect(
            saved.l * dm.widthPixels, saved.t * dm.heightPixels,
            saved.r * dm.widthPixels, saved.b * dm.heightPixels
        )

        val root = FrameLayout(this)
        root.addView(pv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        fun btn(t: String, bg: Int, click: () -> Unit) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(30, 20, 30, 20)
            setBackgroundColor(bg)
            setOnClickListener { click() }
        }
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val fineBtn = btn("دقة عالية: لا", 0xFF555555.toInt()) {}
        fineBtn.setOnClickListener {
            pv.fine = !pv.fine
            fineBtn.text = if (pv.fine) "دقة عالية: نعم" else "دقة عالية: لا"
        }
        val items = listOf(
            btn("✓ تأكيد", 0xFF2E7D32.toInt()) {
                val f = pv.fracs()
                prefs.edit().putBoolean("${key}_on", true)
                    .putFloat("${key}_l", f[0]).putFloat("${key}_t", f[1])
                    .putFloat("${key}_r", f[2]).putFloat("${key}_b", f[3]).apply()
                closePicker()
            },
            btn("✕ إلغاء", 0xFF555555.toInt()) { closePicker() },
            btn("إزالة المنطقة", 0xFF8E2A2A.toInt()) {
                prefs.edit().putBoolean("${key}_on", false).apply()
                closePicker()
            },
            fineBtn
        )
        for (v in items) {
            row.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(6, 0, 6, 0) })
        }
        root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 60 })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
        pickerRoot = root
        toast(if (key == "inc") "اسحب لرسم منطقة الترجمة" else "اسحب لرسم منطقة الاستبعاد")
    }

    private fun closePicker() {
        pickerRoot?.let { runCatching { wm.removeView(it) } }
        pickerRoot = null
        pickerOpen = false
        cfg = loadSettings(this)
        if (auto) handler.post(tick)
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
        pickerRoot?.let { runCatching { wm.removeView(it) } }
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        vd?.release()
        reader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        identifier.close()
        super.onDestroy()
    }
}
