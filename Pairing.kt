package com.example.gametranslator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.random.Random

/** أدوات الاقتران بين التلفزيون والهاتف عبر الشبكة المحلية */
object Pairing {
    const val PORT = 8765
    const val PREFIX = "GTRANS1"

    fun localIp(): String? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun ensureToken(ctx: Context): String {
        val sp = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val old = sp.getString(Prefs.TV_TOKEN, null)
        if (old != null) return old
        val t = (100000 + Random.nextInt(900000)).toString()
        sp.edit().putString(Prefs.TV_TOKEN, t).apply()
        return t
    }

    fun payload(ip: String, token: String) = "$PREFIX|$ip|$PORT|$token"

    fun qrBitmap(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}

/** حالة خدمة التلفزيون (يقرؤها شاشة التلفزيون لعرض الوضع) */
object TvState {
    @Volatile var clients = 0
    @Volatile var serverUp = false
    @Volatile var lastError = ""
    @Volatile var lines: List<String> = emptyList()
}

/** اتصال الهاتف بالتلفزيون: سطر JSON لكل رسالة */
class TvLink {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    @Volatile var connected = false

    fun connect(ip: String, port: Int, token: String, cb: (Boolean) -> Unit) {
        exec.execute {
            var ok = false
            try {
                closeNow()
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 3000)
                s.soTimeout = 3000
                val o = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                o.println("HELLO $token")
                if (r.readLine() == "OK") {
                    s.soTimeout = 0
                    socket = s
                    out = o
                    connected = true
                    ok = true
                } else {
                    s.close()
                }
            } catch (e: Exception) {
                connected = false
            }
            main.post { cb(ok) }
        }
    }

    fun send(line: String) {
        exec.execute {
            try {
                val o = out
                if (o != null && connected) {
                    o.println(line)
                    if (o.checkError()) connected = false
                }
            } catch (e: Exception) {
                connected = false
            }
        }
    }

    private fun closeNow() {
        connected = false
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        out = null
    }

    fun close() {
        exec.execute { closeNow() }
    }
}
