package org.getfloresta.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
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
        val serviceStatus = getSharedPreferences(FlorestaService.STATUS_PREFS, MODE_PRIVATE)
            .getString(FlorestaService.KEY_STATUS, "Service has not reported status yet")

        return buildString {
            appendLine("Waiting for Floresta JSON-RPC at http://${FlorestaService.JSON_RPC_ADDRESS}")
            appendLine()
            appendLine("Service status: $serviceStatus")
            appendLine("RPC error: ${error.message ?: error.javaClass.simpleName}")
            appendLine()
            appendLine("If this keeps showing 'Failed to connect', Floresta did not bind the RPC port. Check the service status above for the startup error.")
        }
    }

    private fun formatNodeInfo(blockchainInfo: JSONObject, peerInfo: JSONArray): String {
        val bestBlock = blockchainInfo.optString("bestblockhash", "unknown")
        val height = blockchainInfo.optLong("blocks", -1L)
        val headers = blockchainInfo.optLong("headers", -1L)
        val validationProgress = blockchainInfo.optDouble("verificationprogress", 0.0) * 100.0
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
}
