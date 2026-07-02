package org.getfloresta.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private val ibdStore: SharedPreferences by lazy { getSharedPreferences(IBD_PREFS, MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private lateinit var output: TextView

    private val pollRpc = object : Runnable {
        override fun run() {
            refreshNodeInfo()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            text = "Starting Floresta foreground service..."
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    output,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        FlorestaService.start(this)
        handler.postDelayed(pollRpc, 1_500)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRpc)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun refreshNodeInfo() {
        val serviceStatus = currentServiceStatus()
        if (!serviceStatus.startsWith(FlorestaService.STATUS_RUNNING_PREFIX)) {
            output.text = buildStartupMessage(serviceStatus)
            return
        }

        if (!fetching.compareAndSet(false, true)) return

        executor.execute {
            try {
                val blockchainInfo = rpcObject("getblockchaininfo")
                val peerInfo = rpcArray("getpeerinfo")
                val formatted = formatNodeInfo(blockchainInfo, peerInfo)
                handler.post { output.text = formatted }
            } catch (error: Throwable) {
                handler.post {
                    output.text = buildWaitingMessage(error)
                }
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun rpcObject(method: String): JSONObject {
        val result = rpc(method)
        if (result is JSONObject) return result
        throw IOException("$method returned ${result.javaClass.simpleName}, expected object")
    }

    private fun rpcArray(method: String): JSONArray {
        val result = rpc(method)
        if (result is JSONArray) return result
        throw IOException("$method returned ${result.javaClass.simpleName}, expected array")
    }

    private fun rpc(method: String): Any {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", method)
            .put("method", method)
            .put("params", JSONArray())
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://${FlorestaService.JSON_RPC_ADDRESS}")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $responseBody")

            val json = JSONObject(responseBody)
            if (!json.isNull("error")) throw IOException(json.get("error").toString())
            return json.get("result")
        }
    }

    private fun buildWaitingMessage(error: Throwable): String {
        return buildString {
            appendLine("Waiting for Floresta JSON-RPC at http://${FlorestaService.JSON_RPC_ADDRESS}")
            appendLine()
            appendLine("Service status: ${currentServiceStatus()}")
            appendLine("RPC error: ${error.message ?: error.javaClass.simpleName}")
            appendLine()
            appendLine("If this keeps showing 'Failed to connect', Floresta did not bind the RPC port. Check the service status above for the startup error.")
        }
    }

    private fun buildStartupMessage(serviceStatus: String): String {
        return buildString {
            appendLine("Preparing Floresta")
            appendLine()
            appendLine("Service status: $serviceStatus")
            appendLine()
            appendLine("JSON-RPC polling will start after Floresta is running.")
        }
    }

    private fun currentServiceStatus(): String {
        return getSharedPreferences(FlorestaService.STATUS_PREFS, MODE_PRIVATE)
            .getString(FlorestaService.KEY_STATUS, "Service has not reported status yet")
            ?: "Service has not reported status yet"
    }

    private fun formatNodeInfo(blockchainInfo: JSONObject, peerInfo: JSONArray): String {
        val bestBlock = blockchainInfo.optString("bestblockhash", "unknown")
        val height = blockchainInfo.optLong("blocks", -1L)
        val headers = blockchainInfo.optLong("headers", -1L)
        val initialBlockDownload = blockchainInfo.optBoolean("initialblockdownload", false)
        val validationProgress = blockchainInfo.optDouble("verificationprogress", 0.0) * 100.0
        val ibdMetrics = updateIbdMetrics(initialBlockDownload, height, headers)
        val peerCount = peerInfo.length()
        val agents = buildList {
            for (index in 0 until peerInfo.length()) {
                val peer = peerInfo.optJSONObject(index) ?: continue
                add(peer.optString("user_agent", "unknown"))
            }
        }

        return buildString {
            appendLine("Current best block")
            appendLine("Height: $height")
            appendLine("Headers: $headers")
            appendLine("Hash: $bestBlock")
            appendLine()
            appendLine("Validation progress: ${String.format(Locale.US, "%.4f", validationProgress)}%")
            appendLine()
            appendLine("Initial block download")
            appendLine("Status: ${if (initialBlockDownload) "running" else "complete"}")
            appendLine("Running time: ${ibdMetrics.elapsedMillis?.let(::formatDuration) ?: "not recorded"}")
            appendLine("ETA: ${ibdMetrics.etaMillis?.let(::formatDuration) ?: "unavailable"}")
            appendLine("Blocks/s: ${ibdMetrics.blocksPerSecond?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable"}")
            appendLine()
            appendLine("Peer count: $peerCount")
            appendLine()
            appendLine("Peer user agents:")
            if (agents.isEmpty()) {
                appendLine("- none connected yet")
            } else {
                agents.forEach { appendLine("- $it") }
            }
        }
    }

    private fun updateIbdMetrics(inIbd: Boolean, height: Long, headers: Long): IbdMetrics {
        val now = System.currentTimeMillis()
        val safeHeight = height.takeIf { it >= 0L }
        var startTime = ibdStore.getLong(KEY_IBD_START_TIME, 0L)
        var startHeight = ibdStore.getLong(KEY_IBD_START_HEIGHT, -1L)
        var endTime = ibdStore.getLong(KEY_IBD_END_TIME, 0L)
        val editor = ibdStore.edit()

        if (inIbd) {
            if (startTime <= 0L || endTime > 0L) {
                startTime = now
                startHeight = safeHeight ?: -1L
                endTime = 0L
                editor.putLong(KEY_IBD_START_TIME, startTime)
                    .putLong(KEY_IBD_START_HEIGHT, startHeight)
                    .remove(KEY_IBD_END_TIME)
            } else if (startHeight < 0L && safeHeight != null) {
                startHeight = safeHeight
                editor.putLong(KEY_IBD_START_HEIGHT, startHeight)
            }
        } else if (startTime > 0L && endTime <= 0L) {
            endTime = now
            editor.putLong(KEY_IBD_END_TIME, endTime)
        }

        editor.apply()

        if (startTime <= 0L) return IbdMetrics(elapsedMillis = null, etaMillis = null, blocksPerSecond = null)

        val metricEndTime = if (inIbd || endTime <= 0L) now else endTime
        val elapsedMillis = (metricEndTime - startTime).coerceAtLeast(0L)
        val elapsedSeconds = (elapsedMillis / 1_000.0).coerceAtLeast(1.0)
        val blockDelta = if (safeHeight != null && startHeight >= 0L) {
            (safeHeight - startHeight).coerceAtLeast(0L)
        } else {
            0L
        }
        val blocksPerSecond = (blockDelta / elapsedSeconds).takeIf { it > 0.0 }
        val etaMillis = if (inIbd && blocksPerSecond != null && headers >= 0L && safeHeight != null) {
            val remainingBlocks = (headers - safeHeight).coerceAtLeast(0L)
            ((remainingBlocks / blocksPerSecond) * 1_000L).toLong()
        } else {
            null
        }

        return IbdMetrics(
            elapsedMillis = elapsedMillis,
            etaMillis = etaMillis,
            blocksPerSecond = blocksPerSecond,
        )
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return buildList {
            if (days > 0L) add("${days}d")
            if (hours > 0L) add("${hours}h")
            if (minutes > 0L) add("${minutes}m")
            if (seconds > 0L || isEmpty()) add("${seconds}s")
        }.joinToString(" ")
    }

    private data class IbdMetrics(
        val elapsedMillis: Long?,
        val etaMillis: Long?,
        val blocksPerSecond: Double?,
    )

    companion object {
        private const val IBD_PREFS = "ibd-metrics"
        private const val KEY_IBD_START_TIME = "start-time"
        private const val KEY_IBD_START_HEIGHT = "start-height"
        private const val KEY_IBD_END_TIME = "end-time"
    }
}
