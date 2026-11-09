package com.example.gametranslator

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max

class MainActivity : Activity() {
    private val REQ = 100
    private lateinit var sp: SharedPreferences
    private lateinit var zonesBox: LinearLayout
    private lateinit var profilesBox: LinearLayout

    private val sources = listOf(
        "auto" to "تلقائي (كشف اللغة)",
        "en" to "الإنجليزية", "tr" to "التركية", "fr" to "الفرنسية", "de" to "الألمانية",
        "es" to "الإسبانية", "it" to "الإيطالية", "pt" to "البرتغالية", "nl" to "الهولندية",
        "pl" to "البولندية", "sv" to "السويدية", "id" to "الإندونيسية", "vi" to "الفيتنامية",
        "ja" to "اليابانية", "ko" to "الكورية", "zh" to "الصينية"
    )
    private val targets = listOf(
        "ar" to "العربية", "en" to "الإنجليزية", "tr" to "التركية", "fr" to "الفرنسية",
        "de" to "الألمانية", "es" to "الإسبانية", "it" to "الإيطالية", "pt" to "البرتغالية",
        "ru" to "الروسية", "fa" to "الفارسية", "ur" to "الأردية", "hi" to "الهندية"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 80)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "مترجم الألعاب"
            textSize = 28f
            setTextColor(0xFF1565C0.toInt())
        })
        col.addView(TextView(this).apply {
            text = "شغّل المترجم ثم افتح تطبيق Xbox. من الشريط العائم: ▶ للتشغيل، «+ شاشة» لإضافة شاشة ترجمة، «+ استبعاد» لإضافة منطقة تُهمَل، ⚙ للإعدادات."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        val start = Button(this).apply { text = "▶  تشغيل المترجم"; textSize = 20f }
        val stop = Button(this).apply { text = "■  إيقاف المترجم"; textSize = 16f }
        start.setOnClickListener { begin() }
        stop.setOnClickListener { stopService(Intent(this, TranslateService::class.java)) }
        col.addView(start)
        col.addView(stop)

        // ----- اللغات الافتراضية -----
        col.addView(header("اللغات الافتراضية"))
        col.addView(label("لغة اللعبة (المصدر)"))
        col.addView(spinner(sources.map { it.second },
            sources.indexOfFirst { it.first == sp.getString(Prefs.SRC, "en") }) {
            sp.edit().putString(Prefs.SRC, sources[it].first).apply()
        })
        col.addView(label("الترجمة إلى"))
        col.addView(spinner(targets.map { it.second },
            targets.indexOfFirst { it.first == sp.getString(Prefs.TGT, "ar") }) {
            sp.edit().putString(Prefs.TGT, targets[it].first).apply()
        })

        // ----- الشاشات العائمة -----
        col.addView(header("الشاشات العائمة (لكل شاشة تحكم منفصل)"))
        zonesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(zonesBox)

        // ----- التوقيت -----
        col.addView(header("التوقيت (بالثواني، ويقبل الكسور مثل 0.5)"))
        col.addView(numField("فاصل الفحص (أقل قيمة 0.2)", sp.getInt(Prefs.INTERVAL, 1000), 200, false) {
            sp.edit().putInt(Prefs.INTERVAL, it).apply()
        })
        col.addView(numField("التحقق من تغيّر النص كل (0 = إيقاف)", sp.getInt(Prefs.REFRESH, 4000), 500, true) {
            sp.edit().putInt(Prefs.REFRESH, it).apply()
        })
        col.addView(numField("إخفاء الترجمة تلقائياً بعد (0 = تبقى)", sp.getInt(Prefs.TTL, 0), 500, true) {
            sp.edit().putInt(Prefs.TTL, it).apply()
        })

        // ----- الدقة والأداء -----
        col.addView(header("الدقة والأداء"))
        col.addView(seek("حد ثقة القراءة (أعلى = أقل تشويشاً)", 0, 95, sp.getInt(Prefs.CONF, 70),
            { "$it%" }) { sp.edit().putInt(Prefs.CONF, it).apply() })
        col.addView(seek("جودة الفحص (أقل = أسرع)", 40, 100, sp.getInt(Prefs.SCALE, 100),
            { "$it%" }) { sp.edit().putInt(Prefs.SCALE, it).apply() })
        col.addView(seek("عتمة خلفية الترجمة", 40, 100, sp.getInt(Prefs.OPACITY, 90),
            { "$it%" }) { sp.edit().putInt(Prefs.OPACITY, it).apply() })
        col.addView(switchRow("فلتر ذكي: تجاهل النصوص بلغة غير لغة اللعبة", Prefs.SMART, true))
        col.addView(switchRow("فلتر صارم: لا يترجم إلا ما تأكد أنه بلغة اللعبة", Prefs.STRICT, false))

        // ----- الميزات -----
        col.addView(header("الميزات (كل ميزة تُفعَّل وتُوقَف هنا)"))
        col.addView(switchRow("سجل الترجمات", Prefs.HISTORY, true))
        col.addView(Button(this).apply {
            text = "عرض سجل الترجمات"
            setOnClickListener { showHistory() }
        })
        col.addView(switchRow("قراءة الترجمة صوتياً", Prefs.TTS, false))
        col.addView(switchRow("القراءة للنصوص الطويلة فقط (الحوار)", Prefs.TTS_LONG, true))
        col.addView(switchRow("تلوين الترجمة حسب نوعها (حوار / مهمة / قائمة)", Prefs.COLORIZE, true))

        col.addView(switchRow("ترجمة سحابية بنموذج Claude (أدق)", Prefs.CLOUD, false))
        col.addView(TextView(this).apply {
            text = "تحتاج إنترنت ومفتاح API، وتُرسَل النصوص المقروءة إلى Anthropic وقد تُحتسب عليك تكلفة. عند الفشل تعود الترجمة تلقائياً للنموذج المحلي."
            textSize = 12f
        })
        col.addView(textField("مفتاح API", Prefs.API_KEY, "", true))
        col.addView(textField("اسم النموذج", Prefs.MODEL, Prefs.DEFAULT_MODEL, false))

        col.addView(Switch(this).apply {
            text = "تشغيل الترجمة تلقائياً عند فتح تطبيق اللعبة"
            isChecked = sp.getBoolean(Prefs.AUTOSTART, false)
            setPadding(0, 16, 0, 16)
            setOnCheckedChangeListener { _, c ->
                sp.edit().putBoolean(Prefs.AUTOSTART, c).apply()
                if (c && !hasUsageAccess()) {
                    Toast.makeText(this@MainActivity, "فعّل «الوصول إلى بيانات الاستخدام» لهذا التطبيق", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
        })
        col.addView(textField("اسم حزمة تطبيق اللعبة (Xbox افتراضياً)", Prefs.PKG, Prefs.DEFAULT_PKG, false))

        // ----- القاموس -----
        col.addView(header("قاموس مخصص (سطر لكل كلمة بالشكل: النص=الترجمة)"))
        col.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            hint = "Resume game=استئناف اللعبة"
            setText(sp.getString(Prefs.GLOSSARY, ""))
            addTextChangedListener(watch { sp.edit().putString(Prefs.GLOSSARY, it).apply() })
        })

        // ----- ملفات الإعداد -----
        col.addView(header("ملفات الإعداد (لكل لعبة)"))
        val nameField = EditText(this).apply { hint = "اسم الملف، مثل GTA" }
        col.addView(nameField)
        col.addView(Button(this).apply {
            text = "حفظ الإعدادات الحالية بهذا الاسم"
            setOnClickListener {
                val n = nameField.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this@MainActivity, "اكتب اسماً أولاً", Toast.LENGTH_SHORT).show()
                } else {
                    saveProfile(sp, n)
                    nameField.setText("")
                    fillProfiles()
                }
            }
        })
        profilesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(profilesBox)

        col.addView(TextView(this).apply {
            text = "\nتُطبَّق الإعدادات تلقائياً أثناء التشغيل. أول استخدام لكل لغة يحتاج إنترنت لتنزيل نموذجها."
            textSize = 12f
        })
    }

    override fun onResume() {
        super.onResume()
        if (::zonesBox.isInitialized) fillZones()
        if (::profilesBox.isInitialized) fillProfiles()
    }

    // ---------- الشاشات العائمة ----------

    private fun updateZone(id: String, f: (Zone) -> Unit) {
        val list = loadZones(sp)
        val z = list.find { it.id == id } ?: return
        f(z)
        saveZones(sp, list)
    }

    private fun fillZones() {
        zonesBox.removeAllViews()
        val list = loadZones(sp)
        if (list.isEmpty()) {
            zonesBox.addView(TextView(this).apply {
                text = "لا توجد شاشات عائمة. أضِفها من الشريط العائم بزر «+ شاشة». وبدونها يُترجم كامل الشاشة."
                textSize = 13f
            })
        }
        for (z in list) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 16) }
            zonesBox.addView(zoneCard(z), lp)
        }
    }

    private fun zoneCard(z: Zone): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0x15000000)
        }
        card.addView(EditText(this).apply {
            setText(z.name)
            addTextChangedListener(watch { s -> updateZone(z.id) { it.name = s } })
        })
        card.addView(Switch(this).apply {
            text = "مفعّلة"
            isChecked = z.enabled
            setOnCheckedChangeListener { _, c -> updateZone(z.id) { it.enabled = c } }
        })
        card.addView(label("النوع"))
        card.addView(spinner(listOf("ترجمة هذه المنطقة", "استبعاد (تجاهل هذه المنطقة)"), z.mode) { p ->
            updateZone(z.id) { it.mode = p }
        })
        card.addView(label("لغة هذه الشاشة"))
        val srcNames = listOf("الافتراضية") + sources.map { it.second }
        val srcSel = if (z.src.isEmpty()) 0 else 1 + sources.indexOfFirst { it.first == z.src }
        card.addView(spinner(srcNames, srcSel) { p ->
            updateZone(z.id) { it.src = if (p == 0) "" else sources[p - 1].first }
        })
        card.addView(label("الترجمة إلى (لهذه الشاشة)"))
        val tgtNames = listOf("الافتراضية") + targets.map { it.second }
        val tgtSel = if (z.tgt.isEmpty()) 0 else 1 + targets.indexOfFirst { it.first == z.tgt }
        card.addView(spinner(tgtNames, tgtSel) { p ->
            updateZone(z.id) { it.tgt = if (p == 0) "" else targets[p - 1].first }
        })
        card.addView(numField("فاصل الفحص لهذه الشاشة (0 = الافتراضي)", z.interval.toInt(), 200, true) { ms ->
            updateZone(z.id) { it.interval = ms.toLong() }
        })
        card.addView(Button(this).apply {
            text = "حذف هذه الشاشة"
            setOnClickListener {
                val list = loadZones(sp)
                list.removeAll { it.id == z.id }
                saveZones(sp, list)
                fillZones()
            }
        })
        return card
    }

    // ---------- ملفات الإعداد والسجل ----------

    private fun fillProfiles() {
        profilesBox.removeAllViews()
        for (n in profileNames(sp)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply { text = n; textSize = 16f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "تحميل"
                setOnClickListener {
                    loadProfile(sp, n)
                    Toast.makeText(this@MainActivity, "تم تحميل: $n", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            })
            row.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener { deleteProfile(sp, n); fillProfiles() }
            })
            profilesBox.addView(row)
        }
    }

    private fun showHistory() {
        val f = File(filesDir, "history.txt")
        val lines = if (f.exists()) f.readLines().takeLast(150).reversed() else emptyList()
        val tv = TextView(this).apply {
            text = if (lines.isEmpty()) "السجل فارغ" else lines.joinToString("\n\n")
            textSize = 14f
            setPadding(30, 30, 30, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("سجل الترجمات")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("مسح السجل") { _, _ -> runCatching { f.writeText("") } }
            .show()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ---------- عناصر الواجهة ----------

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTextColor(0xFF1565C0.toInt())
        setPadding(0, 56, 0, 8)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(0xFF555555.toInt())
        setPadding(0, 24, 0, 8)
    }

    private fun watch(onText: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun spinner(items: List<String>, selected: Int, onSel: (Int) -> Unit): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(if (selected < 0) 0 else selected)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return s
    }

    private fun switchRow(title: String, key: String, def: Boolean) = Switch(this).apply {
        text = title
        isChecked = sp.getBoolean(key, def)
        setPadding(0, 16, 0, 16)
        setOnCheckedChangeListener { _, c -> sp.edit().putBoolean(key, c).apply() }
    }

    private fun textField(title: String, key: String, def: String, secret: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        box.addView(TextView(this).apply { text = title })
        box.addView(EditText(this).apply {
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setText(sp.getString(key, def))
            addTextChangedListener(watch { sp.edit().putString(key, it.trim()).apply() })
        })
        return box
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                '\u066B', '\u066C', ',' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun numField(
        title: String, curMs: Int, minMs: Int, allowZero: Boolean, onSave: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        box.addView(TextView(this).apply { text = title })
        box.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText((curMs / 1000f).toString().removeSuffix(".0"))
            addTextChangedListener(watch { s ->
                val v = normalize(s).toFloatOrNull()
                if (v != null) {
                    var ms = (v * 1000).toInt()
                    if (!(allowZero && ms == 0)) ms = max(ms, minMs)
                    onSave(ms)
                }
            })
        })
        return box
    }

    private fun seek(
        title: String, lo: Int, hi: Int, cur: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }
        val tv = TextView(this).apply { text = "$title: ${fmt(cur)}" }
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = cur - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = "$title: ${fmt(v)}"
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb)
        return box
    }

    // ---------- التشغيل ----------

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
            moveTaskToBack(true)
        }
    }
}
