package com.example.speedtest

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val gauge = findViewById<GaugeView>(R.id.gauge)
        val goBtn = findViewById<TextView>(R.id.goBtn)
        val speedText = findViewById<TextView>(R.id.speedText)
        val unitText = findViewById<TextView>(R.id.unitText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val pingValue = findViewById<TextView>(R.id.pingValue)
        val downValue = findViewById<TextView>(R.id.downValue)
        val upValue = findViewById<TextView>(R.id.upValue)

        goBtn.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            goBtn.text = "Testing..."
            scope.launch {
                try {
                    statusText.text = "Measuring ping"
                    speedText.text = "..."
                    unitText.text = "ms"
                    val ping = withContext(Dispatchers.IO) { measurePing() }
                    pingValue.text = "%.0f ms".format(ping)

                    statusText.text = "Testing download"
                    unitText.text = "Mbps"
                    val down = withContext(Dispatchers.IO) { measureDownload() }
                    gauge.setSpeed(down)
                    speedText.text = "%.1f".format(down)
                    downValue.text = "%.1f Mbps".format(down)

                    statusText.text = "Testing upload"
                    val up = withContext(Dispatchers.IO) { measureUpload() }
                    gauge.setSpeed(up)
                    speedText.text = "%.1f".format(up)
                    upValue.text = "%.1f Mbps".format(up)

                    statusText.text = "Test complete"
                } catch (e: Exception) {
                    statusText.text = "Error: ${e.message}"
                } finally {
                    running = false
                    goBtn.text = "Start test"
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
