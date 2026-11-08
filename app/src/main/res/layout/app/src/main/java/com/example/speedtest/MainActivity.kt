package com.example.speedtest

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btn = findViewById<Button>(R.id.goBtn)
        val speedText = findViewById<TextView>(R.id.speedText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val pingText = findViewById<TextView>(R.id.pingText)
        val downText = findViewById<TextView>(R.id.downText)
        val upText = findViewById<TextView>(R.id.upText)

        btn.setOnClickListener {
            btn.isEnabled = false
            scope.launch {
                try {
                    statusText.text = "قياس البنق..."
                    val ping = withContext(Dispatchers.IO) { measurePing() }
                    pingText.text = "%.0f ms\nالبنق".format(ping)

                    statusText.text = "قياس التحميل..."
                    speedText.text = "..."
                    val down = withContext(Dispatchers.IO) { measureDownload() }
                    downText.text = "%.1f\nالتحميل".format(down)
                    speedText.text = "%.0f".format(down)

                    statusText.text = "قياس الرفع..."
                    val up = withContext(Dispatchers.IO) { measureUpload() }
                    upText.text = "%.1f\nالرفع".format(up)

                    statusText.text = "اكتمل الفحص ✓"
                } catch (e: Exception) {
                    statusText.text = "خطأ: ${e.message}"
                } finally {
                    btn.isEnabled = true
                }
            }
        }
    }

    private fun measurePing(): Double {
        val times = mutableListOf<Double>()
        repeat(5) {
            val start = System.nanoTime()
            val conn = URL("https://speed.cloudflare.com/__down?bytes=0")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            times.add((System.nanoTime() - start) / 1_000_000.0)
        }
        return times.minOrNull() ?: 0.0
    }

    private fun measureDownload(): Double {
        val bytes = 25_000_000
        val start = System.nanoTime()
        val conn = URL("https://speed.cloudflare.com/__down?bytes=$bytes")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        val total = conn.inputStream.use { it.readBytes().size.toLong() }
        conn.disconnect()
        val sec = (System.nanoTime() - start) / 1_000_000_000.0
        return (total * 8) / sec / 1_000_000
    }

    private fun measureUpload(): Double {
        val size = 5_000_000
        val start = System.nanoTime()
        val conn = URL("https://speed.cloudflare.com/__up")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.setFixedLengthStreamingMode(size)
        conn.outputStream.use { it.write(ByteArray(size)) }
        conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val sec = (System.nanoTime() - start) / 1_000_000_000.0
        return (size * 8) / sec / 1_000_000
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
