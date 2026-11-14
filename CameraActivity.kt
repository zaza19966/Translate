package com.example.gametranslator

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private class GuideView(ctx: Context) : View(ctx) {
    var rect: RectF? = null
    private val p = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = 0xFF2ECC71.toInt()
    }

    override fun onDraw(c: Canvas) {
        rect?.let { c.drawRect(it, p) }
    }
}

/** ترجمة بكاميرا الهاتف لشاشة التلفزيون + الاقتران عبر QR */
class CameraActivity : ComponentActivity() {

    private class Cand(val box: Rect, val src: String, val lines: Int)

    private lateinit var sp: SharedPreferences
    private lateinit var cfg: AppSettings
    private lateinit var previewView: PreviewView
    private lateinit var guideView: GuideView
    private lateinit var arLayer: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var btnRun: TextView
    private lateinit var btnTv: TextView

    private val exec = Executors.newSingleThreadExecutor()
    private val link = TvLink()
    private val barcode = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    private val recognizers = HashMap<String, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val cache = HashMap<String, String>()   // "" = تجاهل

    @Volatile private var phase = 1       // 0 = مسح QR، 1 = ترجمة
    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var guideRect: RectF? = null
    private var tvEnabled = true
    private var lastRun = 0L
    private var lastSig = ""
    private var lastSent = ""
    private var lastSentAt = 0L
    private var lastTry = 0L
    private var toastedDownload = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                toast("الكاميرا مطلوبة لهذه الميزة")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        cfg = loadSettings(this)
        tvEnabled = sp.getBoolean(Prefs.TV_SEND, true)
        phase = if (sp.getString(Prefs.TV_IP, null) != null) 1 else 0

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val full = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(previewView, full)
        guideView = GuideView(this)
        root.addView(guideView, FrameLayout.LayoutParams(full))
        arLayer = FrameLayout(this)
        root.addView(arLayer, FrameLayout.LayoutParams(full))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(20, 10, 20, 10)
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
        }
        btnRun = barBtn("▶ ابدأ الترجمة") { toggleRun() }
        btnTv = barBtn("") { toggleTv() }
        bar.addView(btnRun)
        bar.addView(btnTv)
        bar.addView(barBtn("مسح QR") { startQrMode() })
        bar.addView(barBtn("بدون تلفزيون") { skipTv() })
        bar.addView(barBtn("خروج") { finish() })
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        setContentView(root)
        refreshButtons()

        if (phase == 0) {
            setStatus("وجّه الكاميرا إلى رمز QR على شاشة التلفزيون")
        } else {
            setStatus("ضع شاشة التلفزيون داخل الإطار الأخضر ثم اضغط «ابدأ الترجمة»")
            connectSaved(true)
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- الواجهة ----------

    private fun barBtn(t: String, click: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(26, 18, 26, 18)
        setOnClickListener { click() }
    }

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun refreshButtons() {
        btnRun.text = if (running) "■ إيقاف الترجمة" else "▶ ابدأ الترجمة"
        btnTv.text = if (tvEnabled) "التلفزيون: يعمل" else "التلفزيون: متوقف"
    }

    private fun toggleRun() {
        if (phase == 0) { toast("امسح رمز QR أولاً أو اضغط «بدون تلفزيون»"); return }
        running = !running
        if (!running) { arLayer.removeAllViews(); sendClear(); lastSig = "" }
        refreshButtons()
    }

    private fun toggleTv() {
        tvEnabled = !tvEnabled
        sp.edit().putBoolean(Prefs.TV_SEND, tvEnabled).apply()
        if (!tvEnabled) sendClear() else connectSaved(true)
        refreshButtons()
    }

    private fun startQrMode() {
        phase = 0
        running = false
        arLayer.removeAllViews()
        guideView.rect = null
        guideView.invalidate()
        refreshButtons()
        setStatus("وجّه الكاميرا إلى رمز QR على شاشة التلفزيون")
    }

    private fun skipTv() {
        phase = 1
        tvEnabled = false
        refreshButtons()
        setStatus("ضع شاشة التلفزيون داخل الإطار الأخضر ثم اضغط «ابدأ الترجمة»")
    }

    // ---------- الاتصال بالتلفزيون ----------

    private fun connectSaved(force: Boolean) {
        val ip = sp.getString(Prefs.TV_IP, null) ?: return
        val port = sp.getInt(Prefs.TV_PORT, Pairing.PORT)
        val token = sp.getString(Prefs.TV_TOKEN, "") ?: ""
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastTry < 5000) return
        lastTry = now
        link.connect(ip, port, token) { ok ->
            setStatus(if (ok) "✓ متصل بالتلفزيون ($ip)" else "✗ تعذّر الاتصال بالتلفزيون ($ip). تأكد أنه على نفس الشبكة")
        }
    }

    private fun onQr(raw: String) {
        if (phase != 0) return
        val parts = raw.split("|")
        if (parts.size < 4 || parts[0] != Pairing.PREFIX) return
        val port = parts[2].toIntOrNull() ?: return
        sp.edit().putString(Prefs.TV_IP, parts[1]).putInt(Prefs.TV_PORT, port)
            .putString(Prefs.TV_TOKEN, parts[3]).apply()
        phase = 1
        tvEnabled = true
        sp.edit().putBoolean(Prefs.TV_SEND, true).apply()
        refreshButtons()
        toast("تم الاقتران")
        connectSaved(true)
    }

    private fun sendClear() {
        lastSent = ""
        if (link.connected) link.send("{\"cmd\":\"clear\"}")
    }

    private fun sendTv(lines: List<String>) {
        if (!tvEnabled) return
        if (!link.connected) { connectSaved(false); return }
        val text = lines.take(6).joinToString("\n")
        val now = SystemClock.uptimeMillis()
        if (text == lastSent && now - lastSentAt < 10000) return
        lastSent = text
        lastSentAt = now
        if (lines.isEmpty()) {
            link.send("{\"cmd\":\"clear\"}")
        } else {
            val pos = if (sp.getInt(Prefs.TV_POS, 0) == 1) "bottom" else "top"
            link.send(
                JSONObject().put("cmd", "show").put("pos", pos)
                    .put("lines", JSONArray(lines.take(6))).toString()
            )
        }
    }

    // ---------- الكاميرا ----------

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val rs = ResolutionSelector.Builder()
                .setAspectRatioStrategy(ratio)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            val gap = if (phase == 0) 400L else max(300L, cfg.interval)
            val need = !busy && now - lastRun >= gap && (phase == 0 || running)
            if (!need) return
            lastRun = now
            busy = true
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp = if (rot != 0) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true
                )
            } else raw
            if (phase == 0) scanQr(bmp) else scanText(bmp)
        } catch (e: Exception) {
            busy = false
        } finally {
            proxy.close()
        }
    }

    private fun scanQr(bmp: Bitmap) {
        barcode.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { list ->
                busy = false
                val v = list.firstOrNull { it.rawValue?.startsWith(Pairing.PREFIX) == true }?.rawValue
                if (v != null) onQr(v)
            }
            .addOnFailureListener { busy = false }
    }

    private fun updateGuide(iw: Int, ih: Int, gx: Int, gy: Int, gw: Int, gh: Int): RectF? {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val s = min(vw / iw, vh / ih)
        val ox = (vw - iw * s) / 2f
        val oy = (vh - ih * s) / 2f
        val r = RectF(ox + gx * s, oy + gy * s, ox + (gx + gw) * s, oy + (gy + gh) * s)
        guideRect = r
        runOnUiThread { guideView.rect = r; guideView.invalidate() }
        return r
    }

    private fun scanText(bmp: Bitmap) {
        cfg = loadSettings(this)
        val iw = bmp.width
        val ih = bmp.height
        val gw = (iw * 0.92f).toInt()
        val gh = min((gw * 9f / 16f).toInt(), ih - 4)
        val gx = (iw - gw) / 2
        val gy = (ih - gh) / 2
        val g = updateGuide(iw, ih, gx, gy, gw, gh)
        if (g == null) { busy = false; return }

        var part = Bitmap.createBitmap(bmp, gx, gy, gw, gh)
        val k = cfg.scale / 100f
        if (k < 0.99f) {
            part = Bitmap.createScaledBitmap(part, max(1, (gw * k).toInt()), max(1, (gh * k).toInt()), true)
        }
        recognizer(cfg.src).process(InputImage.fromBitmap(part, 0))
            .addOnSuccessListener { handleText(it, g, gw, gh, k) }
            .addOnFailureListener { busy = false }
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

    private fun validText(s: String): Boolean {
        val minLetters = if (cfg.src in listOf("ja", "ko", "zh")) 1 else 3
        if (s.count { it.isLetter() } < minLetters) return false
        if (s.any { it in '\u0600'..'\u06FF' }) return false
        return true
    }

    private fun handleText(t: Text, g: RectF, gw: Int, gh: Int, k: Float) {
        if (!running) { busy = false; return }
        val minConf = cfg.conf / 100f
        val band = tvEnabled && link.connected
        val topPanel = sp.getInt(Prefs.TV_POS, 0) == 0
        val items = ArrayList<Cand>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            val box = Rect(
                (bb.left / k).toInt(), (bb.top / k).toInt(), (bb.right / k).toInt(), (bb.bottom / k).toInt()
            )
            val src = b.text.replace("\n", " ").trim()
            if (!validText(src)) continue
            // نتجاهل منطقة لوحة الترجمة على التلفزيون حتى لا تُقرأ ترجمتنا نفسها
            val cy = (box.top + box.bottom) / 2f / gh
            if (band && ((topPanel && cy < 0.22f) || (!topPanel && cy > 0.78f))) continue
            if (cache[ck(src)] == "") continue
            if (b.lines.isNotEmpty()) {
                val conf = b.lines.map { it.confidence }.average().toFloat()
                if (conf > 0f && conf < minConf) continue
            }
            items.add(Cand(box, src, b.lines.size))
        }
        items.sortWith(compareBy<Cand>({ it.box.top / 40 }, { it.box.left }))

        val sig = items.joinToString("|") { "${it.src}@${it.box.left / 40},${it.box.top / 40}" }
        if (sig == lastSig) { busy = false; return }
        if (items.isEmpty()) {
            lastSig = sig
            render(items, emptyList(), g, gw, gh)
            busy = false
            return
        }
        translateAll(items.map { it.src }) { res ->
            lastSig = sig
            render(items, res, g, gw, gh)
            busy = false
        }
    }

    // ---------- العرض فوق الكاميرا ----------

    private fun isRtl() = cfg.tgt in setOf("ar", "fa", "ur", "he")

    private fun render(items: List<Cand>, res: List<String>, g: RectF, gw: Int, gh: Int) {
        if (!running) return
        arLayer.removeAllViews()
        val sx = g.width() / gw
        val sy = g.height() / gh
        val lines = ArrayList<String>()
        for (i in items.indices) {
            val dst = res.getOrElse(i) { "" }
            if (dst.isEmpty()) continue
            val b = items[i].box
            val tv = TextView(this).apply {
                text = dst
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(cfg.opacity * 255 / 100, 0, 0, 0))
                gravity = Gravity.CENTER
                textDirection = if (isRtl()) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                setPadding(6, 2, 6, 2)
                setAutoSizeTextTypeUniformWithConfiguration(12, 60, 2, TypedValue.COMPLEX_UNIT_PX)
            }
            val lp = FrameLayout.LayoutParams(
                max(140, ((b.width() + 12) * sx).toInt()), max(30, ((b.height() + 12) * sy).toInt())
            ).apply {
                leftMargin = (g.left + (b.left - 6) * sx).toInt()
                topMargin = (g.top + (b.top - 6) * sy).toInt()
            }
            arLayer.addView(tv, lp)
            lines.add(dst)
        }
        sendTv(lines)
    }

    // ---------- الترجمة ----------

    private fun ck(s: String) = "${cfg.src}|${cfg.tgt}|$s"

    private fun translateAll(texts: List<String>, cb: (List<String>) -> Unit) {
        val out = Array(texts.size) { "" }
        val need = ArrayList<Int>()
        for (i in texts.indices) {
            val g = cfg.glossary[texts[i].lowercase().trim()]
            if (g != null) { out[i] = g; continue }
            val c = cache[ck(texts[i])]
            if (c != null) { out[i] = c; continue }
            need.add(i)
        }
        if (need.isEmpty()) { cb(out.toList()); return }

        var left = need.size
        fun done(i: Int, r: String?) {
            if (r != null) {
                if (cache.size > 800) cache.clear()
                cache[ck(texts[i])] = r
                out[i] = r
            }
            left--
            if (left == 0) cb(out.toList())
        }
        if (cfg.cloud && cfg.apiKey.isNotBlank()) {
            Cloud.translate(cfg.apiKey, cfg.model, cfg.src, cfg.tgt, need.map { texts[it] }) { res ->
                if (res != null && res.size == need.size) {
                    for (j in need.indices) done(need[j], res[j])
                } else {
                    for (i in need) mlkit(texts[i]) { done(i, it) }
                }
            }
        } else {
            for (i in need) mlkit(texts[i]) { done(i, it) }
        }
    }

    private fun mlkit(text: String, cb: (String?) -> Unit) {
        val s = cfg

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
                    tr.translate(text)
                        .addOnSuccessListener { r ->
                            cb(if (r.trim().equals(text.trim(), true)) "" else r)
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
            identifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (s.src == "auto") {
                        if (code == "und" || code == s.tgt) cb("") else doTranslate(code)
                    } else if (s.strict) {
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

    override fun onDestroy() {
        running = false
        sendClear()
        link.close()
        exec.shutdown()
        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        identifier.close()
        barcode.close()
        super.onDestroy()
    }
}
