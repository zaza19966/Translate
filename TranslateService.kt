package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------- إطار الشاشة العائمة (حدود + مقابض) ----------

private class ZoneFrameView(ctx: Context) : View(ctx) {
    var accent = 0xFF2ECC71.toInt()
    var editing = false
    var zoneEnabled = true
    var fine = false
    var onDrag: ((Int, Float, Float) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private var mode = 0 // 1 = تحريك، وإلا مجموع: 2 يسار، 4 أعلى، 8 يمين، 16 أسفل
    private var lx = 0f
    private var ly = 0f
    private val border = Paint().apply { style = Paint.Style.STROKE }
    private val fillP = Paint()
    private val tint = Paint().apply { color = 0x2AFFFFFF }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 40f || h < 40f) return
        val col = if (zoneEnabled) accent else 0xFF888888.toInt()
        border.color = col
        border.strokeWidth = if (editing) 6f else 3f
        if (editing) c.drawRect(0f, 0f, w, h, tint)
        c.drawRect(3f, 3f, w - 3f, h - 3f, border)
        if (editing) {
            fillP.color = col
            val mx = w / 2
            val my = h / 2
            val pts = listOf(0f to 0f, w to 0f, 0f to h, w to h, mx to 0f, mx to h, 0f to my, w to my)
            for (p in pts) {
                c.drawCircle(p.first.coerceIn(14f, w - 14f), p.second.coerceIn(14f, h - 14f), 14f, fillP)
            }
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val tol = 60f
        var m = 0
        if (x < tol) m = m or 2
        if (x > width - tol) m = m or 8
        if (y < tol) m = m or 4
        if (y > height - tol) m = m or 16
        return if (m != 0) m else 1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hit(e.x, e.y)
                lx = e.rawX; ly = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val s = if (fine) 0.25f else 1f
                val dx = (e.rawX - lx) * s
                val dy = (e.rawY - ly) * s
                lx = e.rawX; ly = e.rawY
                onDrag?.invoke(mode, dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDone?.invoke()
        }
        return true
    }
}

// ---------- الخدمة ----------

class TranslateService : Service() {

    private class Cand(val box: Rect, val src: String, val lines: Int)
    private class Tracked(val src: String, val rect: Rect, val view: TextView, var seen: Long)

    private class ZoneRt(var cfg: Zone, val container: FrameLayout) {
        val tracked = ArrayList<Tracked>()
        val inflight = HashSet<String>()
        var nextScan = 0L
        var lastValidation = 0L
        val rect = RectF()
        var editing = false
        var fine = false
        var frame: ZoneFrameView? = null
        var frameLp: WindowManager.LayoutParams? = null
        var strip: LinearLayout? = null
        var stripLp: WindowManager.LayoutParams? = null
        var cellName: TextView? = null
        var cellToggle: TextView? = null
        var cellEdit: TextView? = null
        var cellFine: TextView? = null
    }

    private val PAD = 6
    private val FLAGS_BASE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
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
    private var toggleCell: TextView? = null
    private lateinit var defaultRt: ZoneRt
    private val zonesRt = ArrayList<ZoneRt>()
    private var pendingEdit: String? = null

    private var started = false
    private var auto = false
    private var busy = false
    private var toastedDownload = false
    private var lastFg: String? = null
    private var autoByApp = false
    private var lastLogged = ""

    private val cache = HashMap<String, String>()   // "" = تجاهل هذا النص
    private val translators = HashMap<String, Translator>()
    private val recognizers = HashMap<String, TextRecognizer>()
    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val tick = Runnable { if (auto && !busy) cycle() }
    private val watcher = object : Runnable {
        override fun run() {
            checkForeground()
            handler.postDelayed(this, 2000)
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.ZONES) syncZones()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        cfg = loadSettings(this)

        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        setupCapture()
        addOverlayRoot()
        defaultRt = ZoneRt(Zone("default", "", 0f, 0f, 1f, 1f, true, 0, "", "", 0L), newContainer())
        addBar()
        tts = TextToSpeech(this) { st -> ttsReady = (st == TextToSpeech.SUCCESS) }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        syncZones()
        handler.postDelayed(watcher, 2000)
        started = true
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
            clearAllTracked()
            for (z in zonesRt) {
                z.rect.set(z.cfg.l * w, z.cfg.t * h, z.cfg.r * w, z.cfg.b * h)
                applyRect(z)
            }
        }
    }

    // ---------- الحلقة التلقائية ----------

    private fun setAuto(on: Boolean) {
        auto = on
        toggleCell?.text = if (on) "■ إيقاف" else "▶ ترجمة"
        if (on) {
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            clearAllTracked()
        }
    }

    private fun refreshSettings() {
        val n = loadSettings(this)
        if (n.src != cfg.src || n.tgt != cfg.tgt || n.glossary != cfg.glossary ||
            n.cloud != cfg.cloud || n.model != cfg.model
        ) {
            clearAllTracked()
            cache.clear()
        }
        cfg = n
    }

    private fun effSrc(z: ZoneRt) = z.cfg.src.ifEmpty { cfg.src }
    private fun effTgt(z: ZoneRt) = z.cfg.tgt.ifEmpty { cfg.tgt }
    private fun intervalOf(z: ZoneRt) =
        max(200L, if (z.cfg.interval > 0) z.cfg.interval else cfg.interval)

    private fun activeZones(): List<ZoneRt> {
        val tz = zonesRt.filter { it.cfg.mode == 0 }
        if (tz.isEmpty()) return listOf(defaultRt)
        return tz.filter { it.cfg.enabled && !it.editing }
    }

    private fun cycle() {
        refreshSettings()
        syncSize()
        val now = SystemClock.uptimeMillis()
        val due = activeZones().filter { it.nextScan <= now }
        if (due.isEmpty()) {
            handler.postDelayed(tick, 100)
            return
        }
        busy = true
        if (cfg.ttl > 0) for (z in due) expireTtl(z, now)

        // فحص "نظيف" دوري: نخفي ترجمة تلك الشاشة لحظة ليرى الـ OCR النص الأصلي
        val clean = due.filter {
            cfg.refresh > 0 && it.tracked.isNotEmpty() && now - it.lastValidation >= cfg.refresh
        }
        if (clean.isNotEmpty()) {
            for (z in clean) { z.lastValidation = now; z.container.alpha = 0f }
            handler.postDelayed({ grab(0, due, clean) }, 130)
        } else {
            grab(0, due, clean)
        }
    }

    private fun finishCycle(due: List<ZoneRt>) {
        val now = SystemClock.uptimeMillis()
        for (z in due) z.nextScan = now + intervalOf(z)
        busy = false
        if (auto) handler.postDelayed(tick, 100)
    }

    private fun showContainers() {
        defaultRt.container.alpha = 1f
        for (z in zonesRt) z.container.alpha = 1f
    }

    private fun recognizer(src: String): TextRecognizer {
        val key = when (src) { "ja" -> "ja"; "ko" -> "ko"; "zh" -> "zh"; else -> "latin" }
        return recognizers.getOrPut(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun rectOf(z: Zone): Rect {
        val left = (z.l * w).toInt().coerceIn(0, w - 2)
        val top = (z.t * h).toInt().coerceIn(0, h - 2)
        val right = (z.r * w).toInt().coerceIn(left + 2, w)
        val bottom = (z.b * h).toInt().coerceIn(top + 2, h)
        return Rect(left, top, right, bottom)
    }

    private fun zoneRect(z: ZoneRt): Rect =
        if (z === defaultRt) Rect(0, 0, w, h) else rectOf(z.cfg)

    private fun excludeRects(): List<Rect> =
        zonesRt.filter { it.cfg.mode == 1 && it.cfg.enabled }.map { rectOf(it.cfg) }

    private fun grab(tries: Int, due: List<ZoneRt>, clean: List<ZoneRt>) {
        val img = reader?.acquireLatestImage()
        if (img == null) {
            // لا إطار جديد: في الفحص العادي معناها أن الشاشة ثابتة
            if (clean.isNotEmpty() && tries < 5) {
                handler.postDelayed({ grab(tries + 1, due, clean) }, 100)
            } else {
                showContainers()
                finishCycle(due)
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
        showContainers()
        runZones(bmp, due, 0, clean)
    }

    private fun runZones(bmp: Bitmap, due: List<ZoneRt>, i: Int, clean: List<ZoneRt>) {
        if (i >= due.size) { finishCycle(due); return }
        val z = due[i]
        val rc = zoneRect(z)
        val cx = min(rc.left, bmp.width - 2)
        val cy = min(rc.top, bmp.height - 2)
        val cw = max(2, min(rc.width(), bmp.width - cx))
        val ch = max(2, min(rc.height(), bmp.height - cy))
        var part = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
        val k = cfg.scale / 100f
        if (k < 0.99f) {
            part = Bitmap.createScaledBitmap(
                part, max(1, (cw * k).toInt()), max(1, (ch * k).toInt()), true
            )
        }
        val isClean = clean.contains(z)
        recognizer(effSrc(z)).process(InputImage.fromBitmap(part, 0))
            .addOnSuccessListener {
                handleText(z, it, isClean, cx, cy, k)
                runZones(bmp, due, i + 1, clean)
            }
            .addOnFailureListener { runZones(bmp, due, i + 1, clean) }
    }

    private fun viewRect(v: View?): Rect? {
        if (v == null || !v.isAttachedToWindow) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun uiRects(): List<Rect> {
        val out = ArrayList<Rect>()
        viewRect(bar)?.let { out.add(it) }
        for (z in zonesRt) viewRect(z.strip)?.let { out.add(it) }
        return out
    }

    private fun validText(src: String, s: String): Boolean {
        val minLetters = if (src in listOf("ja", "ko", "zh")) 1 else 3
        if (s.count { it.isLetter() } < minLetters) return false
        if (s.any { it in '\u0600'..'\u06FF' }) return false
        return true
    }

    private fun cacheKey(z: ZoneRt, text: String) = "${effSrc(z)}|${effTgt(z)}|$text"

    private fun grow(r: Rect): Rect = Rect(r).apply { inset(-PAD, -PAD) }

    private fun handleText(z: ZoneRt, t: Text, clean: Boolean, offX: Int, offY: Int, k: Float) {
        if (!auto) return
        val srcLang = effSrc(z)
        val ui = uiRects()
        val exc = excludeRects()
        val minConf = cfg.conf / 100f
        val cands = ArrayList<Cand>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            val box = Rect(
                (bb.left / k).toInt() + offX, (bb.top / k).toInt() + offY,
                (bb.right / k).toInt() + offX, (bb.bottom / k).toInt() + offY
            )
            val src = b.text.replace("\n", " ").trim()
            if (!validText(srcLang, src)) continue
            if (cache[cacheKey(z, src)] == "") continue
            if (ui.any { Rect.intersects(it, box) }) continue
            if (exc.any { coverage(box, it) > 0.3f }) continue
            if (b.lines.isNotEmpty()) {
                val conf = b.lines.map { it.confidence }.average().toFloat()
                if (conf > 0f && conf < minConf) continue
            }
            cands.add(Cand(box, src, b.lines.size))
        }

        val now = SystemClock.uptimeMillis()
        if (!clean) {
            val fresh = cands.filter { c -> z.tracked.none { coverage(c.box, it.rect) > 0.4f } }
            process(z, fresh)
        } else {
            val keep = BooleanArray(z.tracked.size)
            val toAdd = ArrayList<Cand>()
            for (c in cands) {
                val cr = grow(c.box)
                var m = -1
                for (i in z.tracked.indices) {
                    if (!keep[i] && iou(cr, z.tracked[i].rect) > 0.4f && similar(c.src, z.tracked[i].src)) {
                        m = i; break
                    }
                }
                if (m >= 0) { keep[m] = true; z.tracked[m].seen = now } else toAdd.add(c)
            }
            for (i in z.tracked.indices.reversed()) {
                if (!keep[i]) {
                    z.container.removeView(z.tracked[i].view)
                    z.tracked.removeAt(i)
                }
            }
            process(z, toAdd)
        }
    }

    private fun expireTtl(z: ZoneRt, now: Long) {
        for (i in z.tracked.indices.reversed()) {
            if (now - z.tracked[i].seen > cfg.ttl) {
                z.container.removeView(z.tracked[i].view)
                z.tracked.removeAt(i)
            }
        }
    }

    // ---------- الترجمة ----------

    private fun process(z: ZoneRt, list: List<Cand>) {
        val need = ArrayList<Cand>()
        val keys = ArrayList<String>()
        for (c in list) {
            val key = "${c.src}@${c.box.left / 50},${c.box.top / 50}"
            if (!z.inflight.add(key)) continue
            val g = cfg.glossary[c.src.lowercase().trim()]
            if (g != null) { z.inflight.remove(key); addTracked(z, c, g); continue }
            val cached = cache[cacheKey(z, c.src)]
            if (cached != null) {
                z.inflight.remove(key)
                if (cached != "") addTracked(z, c, cached)
                continue
            }
            need.add(c); keys.add(key)
        }
        if (need.isEmpty()) return

        if (cfg.cloud && cfg.apiKey.isNotBlank()) {
            Cloud.translate(cfg.apiKey, cfg.model, effSrc(z), effTgt(z), need.map { it.src }) { res ->
                if (res != null && res.size == need.size) {
                    for (i in need.indices) finishItem(z, need[i], keys[i], res[i])
                } else {
                    for (i in need.indices) mlkit(z, need[i], keys[i])
                }
            }
        } else {
            for (i in need.indices) mlkit(z, need[i], keys[i])
        }
    }

    private fun mlkit(z: ZoneRt, c: Cand, key: String) {
        translateAsync(effSrc(z), effTgt(z), c.src) { dst -> finishItem(z, c, key, dst) }
    }

    /** dst: نص مترجم، أو "" = تجاهل، أو null = فشل مؤقت */
    private fun finishItem(z: ZoneRt, c: Cand, key: String, dst: String?) {
        z.inflight.remove(key)
        if (dst == null) return
        val ck = cacheKey(z, c.src)
        if (cache.size > 800) cache.clear()
        if (dst.isBlank() || dst.trim().equals(c.src.trim(), true)) {
            cache[ck] = ""
        } else {
            cache[ck] = dst
            addTracked(z, c, dst)
        }
    }

    private fun translateAsync(src: String, tgt: String, text: String, cb: (String?) -> Unit) {
        val s = cfg

        fun doTranslate(from: String) {
            val a = TranslateLanguage.fromLanguageTag(from)
            val b = TranslateLanguage.fromLanguageTag(tgt)
            if (a == null || b == null || a == b) { cb(""); return }
            val tr = translators.getOrPut("$a>$b") {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(a).setTargetLanguage(b).build()
                )
            }
            tr.downloadModelIfNeeded()
                .addOnSuccessListener {
                    tr.translate(text)
                        .addOnSuccessListener { r -> cb(r) }
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

        if (src == "auto" || s.smart || s.strict) {
            identifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (src == "auto") {
                        if (code == "und" || code == tgt) cb("") else doTranslate(code)
                    } else if (s.strict) {
                        if (code != src) cb("") else doTranslate(src)
                    } else {
                        if (code != "und" && code != src) cb("") else doTranslate(src)
                    }
                }
                .addOnFailureListener { cb(null) }
        } else {
            doTranslate(src)
        }
    }

    // ---------- العرض ----------

    private fun isRtl(tgt: String) = tgt in setOf("ar", "fa", "ur", "he")

    private fun colorFor(c: Cand): Int {
        if (!cfg.colorize) return Color.WHITE
        return when {
            c.box.top > h * 0.6f && c.src.length >= 20 -> 0xFFFFE082.toInt()                  // حوار
            c.box.top < h * 0.35f && c.box.left < w * 0.5f && c.src.length >= 12 ->
                0xFFA5D6A7.toInt()                                                            // مهمة
            c.src.length < 18 -> Color.WHITE                                                  // قائمة
            else -> 0xFF80DEEA.toInt()
        }
    }

    private fun addTracked(z: ZoneRt, c: Cand, dst: String) {
        val rect = grow(c.box)
        if (z.tracked.any { coverage(c.box, it.rect) > 0.4f }) return
        val tgt = effTgt(z)
        val tv = TextView(this).apply {
            text = dst
            setTextColor(colorFor(c))
            setBackgroundColor(Color.argb(cfg.opacity * 255 / 100, 0, 0, 0))
            gravity = Gravity.CENTER
            textDirection = if (isRtl(tgt)) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
            setPadding(8, 2, 8, 2)
            setAutoSizeTextTypeUniformWithConfiguration(14, 64, 2, TypedValue.COMPLEX_UNIT_PX)
        }
        val lp = FrameLayout.LayoutParams(max(rect.width(), 140), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        z.container.addView(tv, lp)
        z.tracked.add(Tracked(c.src, rect, tv, SystemClock.uptimeMillis()))
        onNewTranslation(z, c, dst, tgt)
    }

    private fun onNewTranslation(z: ZoneRt, c: Cand, dst: String, tgt: String) {
        if (dst == lastLogged) return
        lastLogged = dst
        if (cfg.history) {
            try {
                val f = File(filesDir, "history.txt")
                if (f.length() > 400_000) f.writeText("")
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val name = if (z === defaultRt) "كامل الشاشة" else z.cfg.name
                f.appendText("$ts | $name | ${c.src} | $dst\n")
            } catch (e: Exception) {
            }
        }
        if (cfg.tts && (!cfg.ttsLong || c.src.length >= 20)) speak(dst, tgt)
    }

    private fun speak(text: String, tgt: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val r = t.setLanguage(Locale.forLanguageTag(tgt))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr")
    }

    private fun clearTracked(z: ZoneRt) {
        z.container.removeAllViews()
        z.container.alpha = 1f
        z.tracked.clear()
        z.inflight.clear()
    }

    private fun clearAllTracked() {
        if (!::defaultRt.isInitialized) return
        clearTracked(defaultRt)
        for (z in zonesRt) clearTracked(z)
    }

    private fun addOverlayRoot() {
        val root = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
        overlayRoot = root
    }

    private fun newContainer(): FrameLayout {
        val c = FrameLayout(this)
        overlayRoot?.addView(
            c, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return c
    }

    // ---------- الشاشات العائمة ----------

    private fun cell(t: String, click: (() -> Unit)? = null): TextView = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(22, 16, 22, 16)
        if (click != null) setOnClickListener { click() }
    }

    private fun saveAllZones() = saveZones(prefs, zonesRt.map { it.cfg })

    private fun syncZones() {
        if (!started && !::defaultRt.isInitialized) return
        val list = loadZones(prefs)
        for (rt in zonesRt.toList()) {
            if (list.none { it.id == rt.cfg.id }) removeZoneRt(rt)
        }
        for (z in list) {
            val rt = zonesRt.find { it.cfg.id == z.id }
            if (rt == null) {
                createZoneRt(z)
            } else {
                val o = rt.cfg
                val rectChanged = abs(z.l - o.l) > 0.0005f || abs(z.t - o.t) > 0.0005f ||
                    abs(z.r - o.r) > 0.0005f || abs(z.b - o.b) > 0.0005f
                val changed = z.src != o.src || z.tgt != o.tgt || z.mode != o.mode || !z.enabled
                rt.cfg = z
                if (rectChanged && !rt.editing) {
                    rt.rect.set(z.l * w, z.t * h, z.r * w, z.b * h)
                    applyRect(rt)
                    clearTracked(rt)
                }
                if (changed) clearTracked(rt)
                refreshZoneUi(rt)
            }
        }
    }

    private fun createZoneRt(z: Zone) {
        val rt = ZoneRt(z, newContainer())
        zonesRt.add(rt)
        buildZoneWindows(rt)
        if (pendingEdit == z.id) {
            pendingEdit = null
            setEditing(rt, true)
        }
    }

    private fun removeZoneRt(rt: ZoneRt) {
        rt.frame?.let { runCatching { wm.removeView(it) } }
        rt.strip?.let { runCatching { wm.removeView(it) } }
        overlayRoot?.removeView(rt.container)
        zonesRt.remove(rt)
    }

    private fun buildZoneWindows(rt: ZoneRt) {
        val fv = ZoneFrameView(this)
        val lp = WindowManager.LayoutParams(
            100, 100, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        rt.frame = fv
        rt.frameLp = lp
        rt.rect.set(rt.cfg.l * w, rt.cfg.t * h, rt.cfg.r * w, rt.cfg.b * h)
        fv.onDrag = { mode, dx, dy -> dragZone(rt, mode, dx, dy) }
        fv.onDone = { finishDrag(rt) }
        wm.addView(fv, lp)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6222222.toInt())
        }
        val cName = cell(rt.cfg.name)
        val cToggle = cell("■") { toggleZone(rt) }
        val cEdit = cell("✎") { setEditing(rt, !rt.editing) }
        val cFine = cell("دقة") { rt.fine = !rt.fine; refreshZoneUi(rt) }
        val cSet = cell("⚙") { openSettings() }
        val cDel = cell("✕") { deleteZone(rt) }
        for (c in listOf(cName, cToggle, cEdit, cFine, cSet, cDel)) row.addView(c)
        rt.strip = row
        rt.cellName = cName; rt.cellToggle = cToggle; rt.cellEdit = cEdit; rt.cellFine = cFine

        val slp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, FLAGS_BASE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        rt.stripLp = slp
        wm.addView(row, slp)
        applyRect(rt)
        refreshZoneUi(rt)
    }

    private fun applyRect(rt: ZoneRt) {
        val fv = rt.frame ?: return
        val lp = rt.frameLp ?: return
        lp.x = rt.rect.left.toInt()
        lp.y = rt.rect.top.toInt()
        lp.width = max(40, rt.rect.width().toInt())
        lp.height = max(40, rt.rect.height().toInt())
        runCatching { wm.updateViewLayout(fv, lp) }
        val st = rt.strip ?: return
        val slp = rt.stripLp ?: return
        slp.x = rt.rect.left.toInt()
        slp.y = max(0, rt.rect.top.toInt() - 90)
        runCatching { wm.updateViewLayout(st, slp) }
    }

    private fun refreshZoneUi(rt: ZoneRt) {
        val z = rt.cfg
        val fv = rt.frame ?: return
        fv.accent = if (z.mode == 0) 0xFF2ECC71.toInt() else 0xFFE74C3C.toInt()
        fv.editing = rt.editing
        fv.zoneEnabled = z.enabled
        fv.fine = rt.fine
        fv.invalidate()
        rt.cellName?.text = z.name
        rt.cellToggle?.text = if (z.enabled) "■" else "▶"
        rt.cellEdit?.text = if (rt.editing) "✔" else "✎"
        rt.cellFine?.setBackgroundColor(if (rt.fine) 0x88FFFFFF.toInt() else Color.TRANSPARENT)
        val lp = rt.frameLp ?: return
        lp.flags = if (rt.editing) FLAGS_BASE else FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(fv, lp) }
    }

    private fun setEditing(rt: ZoneRt, on: Boolean) {
        rt.editing = on
        if (on) clearTracked(rt)
        refreshZoneUi(rt)
    }

    private fun toggleZone(rt: ZoneRt) {
        rt.cfg.enabled = !rt.cfg.enabled
        if (!rt.cfg.enabled) clearTracked(rt)
        saveAllZones()
        refreshZoneUi(rt)
    }

    private fun deleteZone(rt: ZoneRt) {
        removeZoneRt(rt)
        saveAllZones()
    }

    private fun dragZone(rt: ZoneRt, mode: Int, dx: Float, dy: Float) {
        val r = rt.rect
        val minS = 80f
        if (mode == 1) {
            val ww = r.width()
            val hh = r.height()
            r.left = (r.left + dx).coerceIn(0f, max(0f, w - ww))
            r.top = (r.top + dy).coerceIn(0f, max(0f, h - hh))
            r.right = r.left + ww
            r.bottom = r.top + hh
        } else {
            if ((mode and 2) != 0) r.left = min(r.left + dx, r.right - minS)
            if ((mode and 8) != 0) r.right = max(r.right + dx, r.left + minS)
            if ((mode and 4) != 0) r.top = min(r.top + dy, r.bottom - minS)
            if ((mode and 16) != 0) r.bottom = max(r.bottom + dy, r.top + minS)
            r.left = r.left.coerceIn(0f, w.toFloat())
            r.right = r.right.coerceIn(0f, w.toFloat())
            r.top = r.top.coerceIn(0f, h.toFloat())
            r.bottom = r.bottom.coerceIn(0f, h.toFloat())
        }
        applyRect(rt)
    }

    private fun finishDrag(rt: ZoneRt) {
        rt.cfg.l = rt.rect.left / w
        rt.cfg.t = rt.rect.top / h
        rt.cfg.r = rt.rect.right / w
        rt.cfg.b = rt.rect.bottom / h
        saveAllZones()
    }

    private fun addZone(mode: Int) {
        val list = loadZones(prefs)
        val n = list.count { it.mode == mode } + 1
        val z = Zone(
            UUID.randomUUID().toString().take(8),
            if (mode == 0) "شاشة $n" else "استبعاد $n",
            0.3f, 0.35f, 0.7f, 0.65f, true, mode, "", "", 0L
        )
        pendingEdit = z.id
        list.add(z)
        saveZones(prefs, list)
        syncZones()
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
        val handle = cell("✥")
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = bp.x; sy = bp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        bp.x = sx + (e.rawX - tx).toInt()
                        bp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(row, bp)
                    }
                }
                return true
            }
        })
        val toggle = cell("▶ ترجمة") { autoByApp = false; setAuto(!auto) }
        toggleCell = toggle
        row.addView(handle)
        row.addView(toggle)
        row.addView(cell("+ شاشة") { addZone(0) })
        row.addView(cell("+ استبعاد") { addZone(1) })
        row.addView(cell("⚙") { openSettings() })
        bar = row
        wm.addView(row, bp)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- التشغيل التلقائي مع تطبيق Xbox ----------

    private fun checkForeground() {
        refreshSettings()
        if (!cfg.autostart) return
        val um = getSystemService(UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val ev = um.queryEvents(end - 15000, end) ?: return
        val e = UsageEvents.Event()
        var latest: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = e.packageName
        }
        if (latest != null && latest != packageName && latest != "com.android.systemui") lastFg = latest
        val inGame = lastFg == cfg.pkg
        if (inGame && !auto) {
            setAuto(true); autoByApp = true
        } else if (!inGame && auto && autoByApp) {
            setAuto(false); autoByApp = false
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
        if (started) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        for (z in zonesRt.toList()) {
            z.frame?.let { runCatching { wm.removeView(it) } }
            z.strip?.let { runCatching { wm.removeView(it) } }
        }
        overlayRoot?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        vd?.release()
        reader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        identifier.close()
        tts?.shutdown()
        super.onDestroy()
    }
}
