package com.example.gametranslator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    const val NAME = "settings"
    const val SRC = "src"
    const val TGT = "tgt"
    const val INTERVAL = "interval"   // فاصل الفحص (مللي ثانية)
    const val REFRESH = "refresh"     // فاصل التحقق من تغيّر النص (0 = إيقاف)
    const val TTL = "ttl"             // عمر الترجمة (0 = تبقى)
    const val OPACITY = "opacity"
    const val SMART = "smart"
    const val STRICT = "strict"
    const val CONF = "conf"
    const val SCALE = "scale"
    const val GLOSSARY = "glossary"
    const val HISTORY = "history"
    const val TTS = "tts"
    const val TTS_LONG = "tts_long"
    const val COLORIZE = "colorize"
    const val CLOUD = "cloud"
    const val API_KEY = "apikey"
    const val MODEL = "model"
    const val AUTOSTART = "autostart"
    const val PKG = "pkg"
    const val ZONES = "zones"
    const val PROFILES = "profiles"

    const val DEFAULT_PKG = "com.microsoft.xboxone.smartglass"
    const val DEFAULT_MODEL = "claude-haiku-5-5"
}

/** شاشة عائمة: mode 0 = ترجمة، 1 = استبعاد. الإحداثيات نسب من 0 إلى 1 */
class Zone(
    val id: String,
    var name: String,
    var l: Float, var t: Float, var r: Float, var b: Float,
    var enabled: Boolean,
    var mode: Int,
    var src: String,        // "" = اللغة الافتراضية
    var tgt: String,        // "" = اللغة الافتراضية
    var interval: Long      // 0 = الفاصل الافتراضي
)

fun loadZones(p: SharedPreferences): MutableList<Zone> {
    val out = ArrayList<Zone>()
    try {
        val arr = JSONArray(p.getString(Prefs.ZONES, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Zone(
                    o.getString("id"), o.optString("name", "شاشة"),
                    o.optDouble("l", 0.3).toFloat(), o.optDouble("t", 0.35).toFloat(),
                    o.optDouble("r", 0.7).toFloat(), o.optDouble("b", 0.65).toFloat(),
                    o.optBoolean("enabled", true), o.optInt("mode", 0),
                    o.optString("src", ""), o.optString("tgt", ""), o.optLong("interval", 0L)
                )
            )
        }
    } catch (e: Exception) {
    }
    return out
}

fun saveZones(p: SharedPreferences, list: List<Zone>) {
    val arr = JSONArray()
    for (z in list) {
        arr.put(
            JSONObject()
                .put("id", z.id).put("name", z.name)
                .put("l", z.l.toDouble()).put("t", z.t.toDouble())
                .put("r", z.r.toDouble()).put("b", z.b.toDouble())
                .put("enabled", z.enabled).put("mode", z.mode)
                .put("src", z.src).put("tgt", z.tgt).put("interval", z.interval)
        )
    }
    p.edit().putString(Prefs.ZONES, arr.toString()).apply()
}

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
    val glossary: Map<String, String>,
    val history: Boolean,
    val tts: Boolean,
    val ttsLong: Boolean,
    val colorize: Boolean,
    val cloud: Boolean,
    val apiKey: String,
    val model: String,
    val autostart: Boolean,
    val pkg: String
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
        glossary = gl,
        history = p.getBoolean(Prefs.HISTORY, true),
        tts = p.getBoolean(Prefs.TTS, false),
        ttsLong = p.getBoolean(Prefs.TTS_LONG, true),
        colorize = p.getBoolean(Prefs.COLORIZE, true),
        cloud = p.getBoolean(Prefs.CLOUD, false),
        apiKey = p.getString(Prefs.API_KEY, "") ?: "",
        model = (p.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL) ?: Prefs.DEFAULT_MODEL)
            .ifBlank { Prefs.DEFAULT_MODEL },
        autostart = p.getBoolean(Prefs.AUTOSTART, false),
        pkg = (p.getString(Prefs.PKG, Prefs.DEFAULT_PKG) ?: Prefs.DEFAULT_PKG)
            .ifBlank { Prefs.DEFAULT_PKG }
    )
}

// ---------- ملفات الإعداد (لكل لعبة) ----------

fun profileNames(p: SharedPreferences): List<String> {
    return try {
        JSONObject(p.getString(Prefs.PROFILES, "{}") ?: "{}").keys().asSequence().toList()
    } catch (e: Exception) {
        emptyList()
    }
}

fun saveProfile(p: SharedPreferences, name: String) {
    val snap = JSONObject()
    for ((k, v) in p.all) {
        if (k == Prefs.PROFILES || k == Prefs.API_KEY) continue
        val o = JSONObject()
        when (v) {
            is Boolean -> o.put("t", "b").put("v", v)
            is Int -> o.put("t", "i").put("v", v)
            is Long -> o.put("t", "l").put("v", v)
            is Float -> o.put("t", "f").put("v", v.toDouble())
            is String -> o.put("t", "s").put("v", v)
            else -> continue
        }
        snap.put(k, o)
    }
    val all = JSONObject(p.getString(Prefs.PROFILES, "{}") ?: "{}")
    all.put(name, snap)
    p.edit().putString(Prefs.PROFILES, all.toString()).apply()
}

fun loadProfile(p: SharedPreferences, name: String): Boolean {
    val all = JSONObject(p.getString(Prefs.PROFILES, "{}") ?: "{}")
    val snap = all.optJSONObject(name) ?: return false
    val ed = p.edit()
    for (k in p.all.keys) {
        if (k != Prefs.PROFILES && k != Prefs.API_KEY) ed.remove(k)
    }
    val keys = snap.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val o = snap.getJSONObject(k)
        when (o.getString("t")) {
            "b" -> ed.putBoolean(k, o.getBoolean("v"))
            "i" -> ed.putInt(k, o.getInt("v"))
            "l" -> ed.putLong(k, o.getLong("v"))
            "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
            "s" -> ed.putString(k, o.getString("v"))
        }
    }
    ed.apply()
    return true
}

fun deleteProfile(p: SharedPreferences, name: String) {
    val all = JSONObject(p.getString(Prefs.PROFILES, "{}") ?: "{}")
    all.remove(name)
    p.edit().putString(Prefs.PROFILES, all.toString()).apply()
}
