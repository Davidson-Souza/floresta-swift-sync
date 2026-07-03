package org.getfloresta.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
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
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private val ibdStore: SharedPreferences by lazy { getSharedPreferences(IBD_PREFS, MODE_PRIVATE) }
    private val serviceStore: SharedPreferences by lazy { getSharedPreferences(FlorestaService.STATUS_PREFS, MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private lateinit var statusPill: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var hintsStatus: TextView
    private lateinit var hintsSize: TextView
    private lateinit var hintsProgress: ProgressBar
    private lateinit var hintsProgressLabel: TextView
    private lateinit var ibdStatus: TextView
    private lateinit var ibdProgress: ProgressBar
    private lateinit var ibdProgressLabel: TextView
    private lateinit var ibdRuntime: TextView
    private lateinit var ibdEta: TextView
    private lateinit var ibdSpeed: TextView
    private lateinit var heightValue: TextView
    private lateinit var headersValue: TextView
    private lateinit var peersValue: TextView
    private lateinit var hashValue: TextView
    private lateinit var peerAgents: TextView

    private val pollRpc = object : Runnable {
        override fun run() {
            refreshNodeInfo()
            handler.postDelayed(this, 5_000)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = COLOR_BACKGROUND
            window.navigationBarColor = Color.WHITE
        }

        if (!hasSelectedNetwork()) {
            setContentView(buildNetworkPicker())
            return
        }

        startDashboard()
    }

    private fun startDashboard() {
        setContentView(buildContent())
        renderStartup(currentServiceStatus())
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

    private fun buildNetworkPicker(): View {
        val networks = listOf("Bitcoin", "Signet")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                networks,
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(COLOR_BACKGROUND)

            addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addSpacer(14)
            addView(
                Button(this@MainActivity).apply {
                    text = "Start"
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_GREEN)
                    setOnClickListener {
                        val network = if (spinner.selectedItemPosition == 1) {
                            FlorestaService.NETWORK_SIGNET
                        } else {
                            FlorestaService.NETWORK_BITCOIN
                        }
                        saveSelectedNetwork(network)
                        startDashboard()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun buildContent(): View {
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(20), dp(20), dp(28))
                    addView(headerCard())
                    addSpacer(16)
                    addView(hintsCard())
                    addSpacer(16)
                    addView(ibdCard())
                    addSpacer(16)
                    addView(nodeCard())
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun headerCard(): View {
        return card().apply {
            orientation = LinearLayout.VERTICAL

            addView(
                LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL

                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_floresta)
                            setBackgroundColor(Color.TRANSPARENT)
                        },
                        LinearLayout.LayoutParams(dp(48), dp(48)),
                    )

                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(14), 0, 0, 0)
                            addView(label("Floresta Swift Sync", 24f, COLOR_TEXT, Typeface.BOLD))
                            addView(label("${selectedNetworkDisplay()} sync with UTXO hints", 14f, COLOR_MUTED, Typeface.NORMAL))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
            )

            addSpacer(18)
            statusPill = label("Preparing", 13f, COLOR_GREEN, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(COLOR_GREEN_SOFT, dp(999).toFloat())
            }
            addView(statusPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addSpacer(10)
            serviceStatus = label("Service has not reported status yet", 14f, COLOR_MUTED, Typeface.NORMAL)
            addView(serviceStatus)
        }
    }

    private fun hintsCard(): View {
        return card().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Hints file"))
            addSpacer(12)

            hintsProgress = progressBar(COLOR_ORANGE)
            addView(hintsProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            addSpacer(10)

            addView(
                LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    hintsStatus = label("Waiting", 15f, COLOR_TEXT, Typeface.BOLD)
                    addView(hintsStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    hintsProgressLabel = label("0%", 15f, COLOR_ORANGE, Typeface.BOLD)
                    addView(hintsProgressLabel)
                },
            )
            hintsSize = label("Download starts before the daemon", 13f, COLOR_MUTED, Typeface.NORMAL)
            addSpacer(4)
            addView(hintsSize)
        }
    }

    private fun ibdCard(): View {
        return card().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Initial block download"))
            addSpacer(12)

            ibdProgress = progressBar(COLOR_GREEN)
            addView(ibdProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
            addSpacer(10)

            addView(
                LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    ibdStatus = label("Waiting for daemon", 15f, COLOR_TEXT, Typeface.BOLD)
                    addView(ibdStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    ibdProgressLabel = label("0%", 15f, COLOR_GREEN, Typeface.BOLD)
                    addView(ibdProgressLabel)
                },
            )

            addSpacer(14)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    ibdRuntime = metricChip("Running", "not recorded")
                    ibdEta = metricChip("ETA", "unavailable")
                    ibdSpeed = metricChip("Blocks/s", "unavailable")
                    addView(ibdRuntime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addSpacer(8, horizontal = true)
                    addView(ibdEta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addSpacer(8, horizontal = true)
                    addView(ibdSpeed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                },
            )
        }
    }

    private fun nodeCard(): View {
        return card().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Node status"))
            addSpacer(12)

            heightValue = statRow("Height", "-")
            headersValue = statRow("Headers", "-")
            peersValue = statRow("Peers", "-")
            hashValue = statRow("Best block", "-")
            addView(heightValue)
            addView(headersValue)
            addView(peersValue)
            addView(hashValue)

            addSpacer(12)
            addView(label("Peer user agents", 13f, COLOR_MUTED, Typeface.BOLD))
            addSpacer(4)
            peerAgents = label("none connected yet", 13f, COLOR_TEXT, Typeface.NORMAL).apply {
                typeface = Typeface.MONOSPACE
            }
            addView(peerAgents)
        }
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
        val status = currentServiceStatus()
        renderStartup(status)
        if (!status.startsWith(FlorestaService.STATUS_RUNNING_PREFIX)) return

        if (!fetching.compareAndSet(false, true)) return

        executor.execute {
            try {
                val blockchainInfo = rpcObject("getblockchaininfo")
                val peerInfo = rpcArray("getpeerinfo")
                val metrics = runCatching { metrics() }.getOrNull()
                handler.post { renderNodeInfo(blockchainInfo, peerInfo, metrics) }
            } catch (error: Throwable) {
                handler.post { renderRpcError(error) }
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun metrics(): PrometheusMetrics {
        val request = Request.Builder()
            .url("http://$METRICS_ADDRESS/")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Metrics HTTP ${response.code}: $responseBody")

            return PrometheusMetrics(
                blockHeight = prometheusNumber(responseBody, "block_height", "block_heigth")?.toLong(),
            )
        }
    }

    private fun prometheusNumber(body: String, vararg names: String): Double? {
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            for (name in names) {
                if (!trimmed.startsWith(name)) continue

                val next = trimmed.getOrNull(name.length)
                if (next != null && !next.isWhitespace() && next != '{') continue

                return trimmed.substringAfterLast(' ').toDoubleOrNull()
            }
        }

        return null
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

    private fun renderStartup(status: String) {
        serviceStatus.text = status
        renderHintsProgress()

        if (status.startsWith(FlorestaService.STATUS_RUNNING_PREFIX)) {
            statusPill.text = "Daemon online"
            statusPill.setTextColor(COLOR_GREEN)
            statusPill.background = rounded(COLOR_GREEN_SOFT, dp(999).toFloat())
            return
        }

        val failed = status.startsWith("Floresta failed")
        statusPill.text = if (failed) "Startup failed" else "Preparing daemon"
        statusPill.setTextColor(if (failed) COLOR_ORANGE else COLOR_GREEN)
        statusPill.background = rounded(if (failed) COLOR_ORANGE_SOFT else COLOR_GREEN_SOFT, dp(999).toFloat())
        ibdStatus.text = "Waiting for daemon"
        ibdProgressLabel.text = "0%"
        ibdProgress.isIndeterminate = false
        ibdProgress.progress = 0
    }

    private fun renderHintsProgress() {
        val downloaded = serviceStore.getLong(FlorestaService.KEY_HINTS_DOWNLOADED_BYTES, 0L)
        val total = serviceStore.getLong(FlorestaService.KEY_HINTS_TOTAL_BYTES, -1L)
        val complete = serviceStore.getBoolean(FlorestaService.KEY_HINTS_COMPLETE, false)

        hintsProgress.isIndeterminate = total <= 0L && downloaded == 0L && !complete
        if (complete) {
            hintsProgress.visibility = View.GONE
            hintsProgress.progress = hintsProgress.max
            hintsStatus.text = "Ready"
            hintsProgressLabel.text = "100%"
        } else if (total > 0L) {
            hintsProgress.visibility = View.VISIBLE
            val progress = (downloaded.toDouble() / total).coerceIn(0.0, 1.0)
            hintsProgress.progress = (progress * hintsProgress.max).roundToInt()
            hintsStatus.text = "Downloading"
            hintsProgressLabel.text = "${(progress * 100.0).roundToInt()}%"
        } else {
            hintsProgress.visibility = View.VISIBLE
            hintsProgress.progress = 0
            hintsStatus.text = "Waiting"
            hintsProgressLabel.text = "-"
        }

        hintsSize.text = if (total > 0L) {
            "${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else if (downloaded > 0L) {
            "${formatBytes(downloaded)} downloaded"
        } else {
            "Download starts before the daemon"
        }
    }

    private fun renderNodeInfo(blockchainInfo: JSONObject, peerInfo: JSONArray, metrics: PrometheusMetrics?) {
        renderStartup(currentServiceStatus())

        val bestBlock = blockchainInfo.optString("bestblockhash", "unknown")
        val rpcHeight = blockchainInfo.optLong("blocks", -1L)
        val height = metrics?.blockHeight ?: rpcHeight
        val headers = blockchainInfo.optLong("headers", -1L)
        val initialBlockDownload = blockchainInfo.optBoolean("initialblockdownload", false)
        val validationProgress = if (height >= 0L && headers > 0L) {
            (height.toDouble() / headers.toDouble()).coerceIn(0.0, 1.0)
        } else {
            blockchainInfo.optDouble("verificationprogress", 0.0).coerceIn(0.0, 1.0)
        }
        val ibdMetrics = updateIbdMetrics(initialBlockDownload, height, headers)
        val peerCount = peerInfo.length()
        val agents = buildList {
            for (index in 0 until peerInfo.length()) {
                val peer = peerInfo.optJSONObject(index) ?: continue
                add(peer.optString("user_agent", "unknown"))
            }
        }

        ibdProgress.isIndeterminate = false
        ibdProgress.progress = (validationProgress * ibdProgress.max).roundToInt()
        ibdProgressLabel.text = String.format(Locale.US, "%.2f%%", validationProgress * 100.0)
        ibdStatus.text = when {
            initialBlockDownload && height <= 0L -> "Waiting for first validated block"
            initialBlockDownload -> "Syncing blocks"
            else -> "Complete"
        }
        ibdRuntime.text = metricText("Running", ibdMetrics.elapsedMillis?.let(::formatDuration) ?: "not recorded")
        ibdEta.text = metricText("ETA", ibdMetrics.etaMillis?.let(::formatDuration) ?: "unavailable")
        ibdSpeed.text = metricText("Blocks/s", ibdMetrics.blocksPerSecond?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable")

        heightValue.text = rowText("Height", if (metrics?.blockHeight != null) "$height (metrics)" else height.toString())
        headersValue.text = rowText("Headers", headers.toString())
        peersValue.text = rowText("Peers", peerCount.toString())
        hashValue.text = rowText("Best block", bestBlock)
        peerAgents.text = if (agents.isEmpty()) "none connected yet" else agents.joinToString(separator = "\n") { "- $it" }
    }

    private fun renderRpcError(error: Throwable) {
        serviceStatus.text = "RPC warming up: ${error.message ?: error.javaClass.simpleName}"
        statusPill.text = "RPC warming up"
        statusPill.setTextColor(COLOR_ORANGE)
        statusPill.background = rounded(COLOR_ORANGE_SOFT, dp(999).toFloat())
    }

    private fun currentServiceStatus(): String {
        return serviceStore.getString(FlorestaService.KEY_STATUS, "Service has not reported status yet")
            ?: "Service has not reported status yet"
    }

    private fun hasSelectedNetwork(): Boolean = serviceStore.contains(FlorestaService.KEY_SELECTED_NETWORK)

    private fun saveSelectedNetwork(network: String) {
        serviceStore.edit()
            .putString(FlorestaService.KEY_SELECTED_NETWORK, network)
            .putString(FlorestaService.KEY_STATUS, "Starting Floresta")
            .remove(FlorestaService.KEY_HINTS_DOWNLOADED_BYTES)
            .remove(FlorestaService.KEY_HINTS_TOTAL_BYTES)
            .remove(FlorestaService.KEY_HINTS_COMPLETE)
            .apply()
        ibdStore.edit().clear().apply()
    }

    private fun selectedNetworkDisplay(): String {
        return when (serviceStore.getString(FlorestaService.KEY_SELECTED_NETWORK, FlorestaService.NETWORK_BITCOIN)) {
            FlorestaService.NETWORK_SIGNET -> "Signet"
            else -> "Bitcoin"
        }
    }

    private fun updateIbdMetrics(inIbd: Boolean, height: Long, headers: Long): IbdMetrics {
        val now = System.currentTimeMillis()
        val safeHeight = height.takeIf { it >= 0L }
        var startTime = ibdStore.getLong(KEY_IBD_START_TIME, 0L)
        var startHeight = ibdStore.getLong(KEY_IBD_START_HEIGHT, -1L)
        var endTime = ibdStore.getLong(KEY_IBD_END_TIME, 0L)
        val editor = ibdStore.edit()

        if (inIbd && safeHeight != null && safeHeight > 0L) {
            if (startTime <= 0L || endTime > 0L) {
                startTime = now
                startHeight = safeHeight
                endTime = 0L
                editor.putLong(KEY_IBD_START_TIME, startTime)
                    .putLong(KEY_IBD_START_HEIGHT, startHeight)
                    .remove(KEY_IBD_END_TIME)
            } else if (startHeight < 0L) {
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

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.WHITE, dp(24).toFloat())
            elevation = dp(2).toFloat()
        }
    }

    private fun progressBar(color: Int): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10_000
            progress = 0
            progressDrawable.setTint(color)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_TRACK)
        }
    }

    private fun sectionTitle(text: String): TextView = label(text, 18f, COLOR_TEXT, Typeface.BOLD)

    private fun label(text: String, size: Float, color: Int, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            includeFontPadding = true
        }
    }

    private fun metricChip(title: String, value: String): TextView {
        return label(metricText(title, value), 13f, COLOR_TEXT, Typeface.NORMAL).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(COLOR_BACKGROUND, dp(16).toFloat())
        }
    }

    private fun statRow(title: String, value: String): TextView {
        return label(rowText(title, value), 14f, COLOR_TEXT, Typeface.NORMAL).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun metricText(title: String, value: String): String = "$title\n$value"

    private fun rowText(title: String, value: String): String = "$title: $value"

    private fun LinearLayout.addSpacer(size: Int, horizontal: Boolean = false) {
        addView(
            View(this@MainActivity),
            LinearLayout.LayoutParams(
                if (horizontal) dp(size) else 1,
                if (horizontal) 1 else dp(size),
            ),
        )
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
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

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble().coerceAtLeast(0.0)
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.roundToInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class IbdMetrics(
        val elapsedMillis: Long?,
        val etaMillis: Long?,
        val blocksPerSecond: Double?,
    )

    private data class PrometheusMetrics(
        val blockHeight: Long?,
    )

    companion object {
        private const val METRICS_ADDRESS = "127.0.0.1:3333"
        private const val IBD_PREFS = "ibd-metrics"
        private const val KEY_IBD_START_TIME = "start-time"
        private const val KEY_IBD_START_HEIGHT = "start-height"
        private const val KEY_IBD_END_TIME = "end-time"
        private const val COLOR_GREEN = 0xFF346C60.toInt()
        private const val COLOR_GREEN_SOFT = 0xFFE7F0ED.toInt()
        private const val COLOR_ORANGE = 0xFFF17E22.toInt()
        private const val COLOR_ORANGE_SOFT = 0xFFFFEEE1.toInt()
        private const val COLOR_BACKGROUND = 0xFFF6F4EE.toInt()
        private const val COLOR_TRACK = 0xFFE3DED3.toInt()
        private const val COLOR_TEXT = 0xFF17201D.toInt()
        private const val COLOR_MUTED = 0xFF68736F.toInt()
    }
}
