package com.example.speedtest

import android.os.Bundle
import android.content.SharedPreferences
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var testJob: Job? = null

    private val servers = listOf("https://speed.cloudflare.com")
    private val parallelStreams = 4
    private val phaseMs = 10_000L
    private val chunk = 64 * 1024

    private lateinit var gauge: GaugeView
    private lateinit var goBtn: TextView
    private lateinit var speedText: TextView
    private lateinit var unitText: TextView
    private lateinit var statusText: TextView
    private lateinit var pingValue: TextView
    private lateinit var downValue: TextView
    private lateinit var upValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gauge = findViewById(R.id.gauge)
        goBtn = findViewById(R.id.goBtn)
        speedText = findViewById(R.id.speedText)
        unitText = findViewById(R.id.unitText)
        statusText = findViewById(R.id.statusText)
        pingValue = findViewById(R.id.pingValue)
        downValue = findViewById(R.id.downValue)
        upValue = findViewById(R.id.upValue)

        val prefs = getSharedPreferences("speedtest", MODE_PRIVATE)
        prefs.getString("last", null)?.let { statusText.text = "Last: $it" }

        goBtn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            false
        }

        goBtn.setOnClickListener {
            val current = testJob
            if (current != null && current.isActive) {
                current.cancel()
                return@setOnClickListener
            }
            testJob = scope.launch { runTest(prefs) }
        }
    }

    private suspend fun runTest(prefs: SharedPreferences) {
        goBtn.text = "Cancel"
        try {
            statusText.text = "Finding best server..."
            gauge.setSpeed(0.0)
            speedText.text = "--"
            unitText.text = "ms"

            val (server, pingResult) = withContext(Dispatchers.IO) { pickServer() }
            val (pingMin, jitter) = pingResult
            pingValue.text = "%.0f ms".format(pingMin)
            statusText.text = "Ping %.0f ms · Jitter %.1f ms".format(pingMin, jitter)

            statusText.text = "Testing download..."
            unitText.text = "Mbps"
            val down = runPhase { counter, deadline, alive ->
                downloadWorker(server, counter, deadline, alive)
            }
            gauge.setSpeed(down)
            speedText.text = "%.1f".format(down)
            downValue.text = "%.1f Mbps".format(down)

            statusText.text = "Testing upload..."
            val up = runPhase { counter, deadline, alive ->
                uploadWorker(server, counter, deadline, alive)
            }
            gauge.setSpeed(up)
            speedText.text = "%.1f".format(up)
            upValue.text = "%.1f Mbps".format(up)

            statusText.text = "Test complete"
            val summary = "↓ %.1f  ↑ %.1f Mbps  ·  %.0f ms".format(down, up, pingMin)
            prefs.edit().putString("last", summary).apply()
        } catch (e: CancellationException) {
            statusText.text = "Cancelled"
            gauge.setSpeed(0.0)
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        } finally {
            goBtn.text = "Start test"
        }
    }

    private suspend fun runPhase(
        worker: (AtomicLong, Long, () -> Boolean) -> Unit
    ): Double = coroutineScope {
        val counter = AtomicLong(0)
        val deadline = System.currentTimeMillis() + phaseMs
        val start = System.nanoTime()

        val ticker = launch(Dispatchers.Main) {
            var lastBytes = 0L
            var lastTime = System.nanoTime()
            while (true) {
                delay(250)
                val now = System.nanoTime()
                val bytes = counter.get()
                val seconds = (now - lastTime) / 1e9
                val mbps = if (seconds > 0) (bytes - lastBytes) * 8.0 / seconds / 1e6 else 0.0
                lastBytes = bytes
                lastTime = now
                gauge.setSpeed(mbps)
                speedText.text = "%.1f".format(mbps)
            }
        }

        withContext(Dispatchers.IO) {
            coroutineScope {
                repeat(parallelStreams) {
                    launch {
                        worker(counter, deadline) { isActive }
                    }
                }
            }
        }

        ticker.cancel()
        val seconds = (System.nanoTime() - start) / 1e9
        counter.get() * 8.0 / seconds / 1e6
    }

    private fun downloadWorker(
        server: String,
        counter: AtomicLong,
        deadline: Long,
        alive: () -> Boolean
    ) {
        val buf = ByteArray(chunk)
        while (alive() && System.currentTimeMillis() < deadline) {
            val conn = URL("$server/__down?bytes=25000000").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                conn.inputStream.use { input ->
                    while (alive() && System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        counter.addAndGet(n.toLong())
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun uploadWorker(
        server: String,
        counter: AtomicLong,
        deadline: Long,
        alive: () -> Boolean
    ) {
        val buf = ByteArray(chunk)
        while (alive() && System.currentTimeMillis() < deadline) {
            val conn = URL("$server/__up").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setChunkedStreamingMode(chunk)
            try {
                conn.outputStream.use { out ->
                    while (alive() && System.currentTimeMillis() < deadline) {
                        out.write(buf)
                        counter.addAndGet(buf.size.toLong())
                    }
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun measurePing(server: String): Pair<Double, Double> {
        val times = mutableListOf<Double>()
        repeat(10) {
            val start = System.nanoTime()
            val conn = URL("$server/__down?bytes=0").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
            times.add((System.nanoTime() - start) / 1e6)
        }
        val min = times.minOrNull() ?: 0.0
        val diffs = times.zipWithNext { a, b -> abs(b - a) }
        val jitter = if (diffs.isEmpty()) 0.0 else diffs.average()
        return min to jitter
    }

    private fun pickServer(): Pair<String, Pair<Double, Double>> {
        val results = servers.map { it to measurePing(it) }
        return results.minByOrNull { it.second.first }!!
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
