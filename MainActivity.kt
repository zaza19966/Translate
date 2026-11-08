package com.example.gametranslator

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
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
import kotlin.math.max

class MainActivity : Activity() {
    private val REQ = 100
    private lateinit var sp: SharedPreferences

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
            text = "شغّل المترجم ثم افتح تطبيق Xbox. من الشريط العائم: ▶ للتشغيل، «منطقة» لتحديد مكان الترجمة، «استبعاد» لتحديد مكان يُهمَل، ⚙ للإعدادات."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        val start = Button(this).apply { text = "▶  تشغيل المترجم"; textSize = 20f }
        val stop = Button(this).apply { text = "■  إيقاف المترجم"; textSize = 16f }
        start.setOnClickListener { begin() }
        stop.setOnClickListener { stopService(Intent(this, TranslateService::class.java)) }
        col.addView(start)
        col.addView(stop)

        // ----- اللغات -----
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

        // ----- المناطق -----
        col.addView(label("المناطق (تُرسم من الشريط العائم فوق اللعبة)"))
        col.addView(switchRow("ترجمة منطقة محددة فقط بدل كامل الشاشة", "inc_on"))
        col.addView(switchRow("تجاهل منطقة محددة (مثل الخريطة المصغرة)", "exc_on"))

        // ----- التوقيت بالأرقام -----
        col.addView(label("التوقيت (اكتب الرقم بالثواني، ويقبل الكسور مثل 0.5)"))
        col.addView(numField("فاصل الفحص (أقل قيمة 0.2)", Prefs.INTERVAL, 1000, 200, false))
        col.addView(numField("التحقق من تغيّر النص كل (0 = إيقاف)", Prefs.REFRESH, 4000, 500, true))
        col.addView(numField("إخفاء الترجمة تلقائياً بعد (0 = تبقى)", Prefs.TTL, 0, 500, true))

        // ----- الدقة والأداء -----
        col.addView(label("الدقة والأداء"))
        col.addView(seek("حد ثقة القراءة (أعلى = أقل تشويشاً)", 0, 95, sp.getInt(Prefs.CONF, 70),
            { "$it%" }) { sp.edit().putInt(Prefs.CONF, it).apply() })
        col.addView(seek("جودة الفحص (أقل = أسرع)", 40, 100, sp.getInt(Prefs.SCALE, 100),
            { "$it%" }) { sp.edit().putInt(Prefs.SCALE, it).apply() })
        col.addView(seek("عتمة خلفية الترجمة", 40, 100, sp.getInt(Prefs.OPACITY, 90),
            { "$it%" }) { sp.edit().putInt(Prefs.OPACITY, it).apply() })

        col.addView(switchRow("فلتر ذكي: تجاهل النصوص بلغة غير لغة اللعبة", Prefs.SMART, true))
        col.addView(switchRow("فلتر صارم: لا يترجم إلا ما تأكد أنه بلغة اللعبة", Prefs.STRICT, false))

        // ----- القاموس -----
        col.addView(label("قاموس مخصص (سطر لكل كلمة بالشكل: النص=الترجمة)"))
        col.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            hint = "Resume game=استئناف اللعبة"
            setText(sp.getString(Prefs.GLOSSARY, ""))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    sp.edit().putString(Prefs.GLOSSARY, s?.toString() ?: "").apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        col.addView(TextView(this).apply {
            text = "\nتُطبَّق الإعدادات تلقائياً أثناء التشغيل. أول استخدام لكل لغة يحتاج إنترنت لتنزيل نموذجها."
            textSize = 12f
        })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(0xFF555555.toInt())
        setPadding(0, 32, 0, 8)
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

    private fun switchRow(title: String, key: String, def: Boolean = false) = Switch(this).apply {
        text = title
        isChecked = sp.getBoolean(key, def)
        setPadding(0, 16, 0, 16)
        setOnCheckedChangeListener { _, c -> sp.edit().putBoolean(key, c).apply() }
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

    private fun numField(title: String, key: String, defMs: Int, minMs: Int, allowZero: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        box.addView(TextView(this).apply { text = title })
        val cur = sp.getInt(key, defMs)
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText((cur / 1000f).toString().removeSuffix(".0"))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = normalize(s?.toString() ?: "").toFloatOrNull() ?: return
                    var ms = (v * 1000).toInt()
                    if (!(allowZero && ms == 0)) ms = max(ms, minMs)
                    sp.edit().putInt(key, ms).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(et)
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
