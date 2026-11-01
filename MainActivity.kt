package com.example.gametranslator

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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
    private val regions = listOf("كامل الشاشة", "النصف السفلي فقط", "الثلث السفلي فقط (الترجمات)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 80)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)

        col.addView(TextView(this).apply {
            text = "مترجم الألعاب"
            textSize = 28f
            setTextColor(0xFF1565C0.toInt())
        })
        col.addView(TextView(this).apply {
            text = "شغّل المترجم ثم افتح تطبيق Xbox، واضغط الزر الأزرق العائم لبدء الترجمة."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        val start = Button(this).apply { text = "▶  تشغيل المترجم"; textSize = 20f }
        val stop = Button(this).apply { text = "■  إيقاف المترجم"; textSize = 16f }
        start.setOnClickListener { begin() }
        stop.setOnClickListener { stopService(Intent(this, TranslateService::class.java)) }
        col.addView(start)
        col.addView(stop)

        // اللغات
        col.addView(label("لغة اللعبة (المصدر)"))
        col.addView(spinner(sources.map { it.second }, sources.indexOfFirst { it.first == sp.getString(Prefs.SRC, "en") }) {
            sp.edit().putString(Prefs.SRC, sources[it].first).apply()
        })
        col.addView(label("الترجمة إلى"))
        col.addView(spinner(targets.map { it.second }, targets.indexOfFirst { it.first == sp.getString(Prefs.TGT, "ar") }) {
            sp.edit().putString(Prefs.TGT, targets[it].first).apply()
        })

        // منطقة المسح
        col.addView(label("المنطقة التي تُترجم"))
        col.addView(spinner(regions, sp.getInt(Prefs.REGION, 0)) {
            sp.edit().putInt(Prefs.REGION, it).apply()
        })

        // المؤقتات والمظهر
        col.addView(label(""))
        col.addView(seek("سرعة الفحص: كل", 500, 3000, sp.getInt(Prefs.INTERVAL, 1000),
            { String.format("%.1f ث", it / 1000f) }) { sp.edit().putInt(Prefs.INTERVAL, it).apply() })
        col.addView(seek("التحقق من تغيّر النص: كل", 2000, 10000, sp.getInt(Prefs.REFRESH, 4000),
            { String.format("%.0f ث", it / 1000f) }) { sp.edit().putInt(Prefs.REFRESH, it).apply() })
        col.addView(seek("عتمة خلفية الترجمة", 40, 100, sp.getInt(Prefs.OPACITY, 90),
            { "$it%" }) { sp.edit().putInt(Prefs.OPACITY, it).apply() })

        col.addView(Switch(this).apply {
            text = "فلتر ذكي: تجاهل النصوص بلغة غير لغة اللعبة"
            isChecked = sp.getBoolean(Prefs.SMART, true)
            setPadding(0, 32, 0, 0)
            setOnCheckedChangeListener { _, c -> sp.edit().putBoolean(Prefs.SMART, c).apply() }
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

    private fun seek(
        title: String, lo: Int, hi: Int, cur: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
        }
        val tv = TextView(this).apply { text = "$title ${fmt(cur)}" }
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = cur - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = "$title ${fmt(v)}"
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
