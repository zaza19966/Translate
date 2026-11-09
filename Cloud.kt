package com.example.gametranslator

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** ترجمة سحابية دفعة واحدة عبر Claude API (اختيارية، تحتاج إنترنت ومفتاح API) */
object Cloud {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private const val SYSTEM =
        "You are the localization engine of a live game-screen translator. " +
            "You receive a JSON array of texts read by OCR from a video game screen " +
            "(subtitles, menus, mission text, signs). Translate each item to the target language: " +
            "natural, short, faithful, keeping names and numbers. " +
            "If an item is OCR noise, gibberish, or already in the target language, return an empty string for it. " +
            "Reply ONLY with a JSON array of strings with exactly the same length and order. No explanations."

    fun translate(
        key: String, model: String, src: String, tgt: String,
        texts: List<String>, cb: (List<String>?) -> Unit
    ) {
        exec.execute {
            var result: List<String>? = null
            try {
                val from = if (src == "auto") "auto-detect" else "language code '$src'"
                val prompt = "Source: $from\nTarget: language code '$tgt'\nTexts:\n" +
                    JSONArray(texts).toString()
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 2048)
                    .put("system", SYSTEM)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                    )
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val content = JSONObject(txt).getJSONArray("content")
                        .getJSONObject(0).getString("text")
                    val cleaned = content.trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val arr = JSONArray(cleaned)
                    if (arr.length() == texts.size) {
                        result = List(texts.size) { arr.optString(it, "") }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
            }
            main.post { cb(result) }
        }
    }
}
