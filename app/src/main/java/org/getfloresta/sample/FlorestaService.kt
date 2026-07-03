package org.getfloresta.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.getfloresta.AssumeValidArg
import org.getfloresta.Config
import org.getfloresta.FlorestaFfiException
import org.getfloresta.Florestad
import org.getfloresta.Network
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class FlorestaService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()
    private lateinit var statusStore: SharedPreferences

    @Volatile
    private var node: Florestad? = null

    @Volatile
    private var starting = false

    override fun onCreate() {
        super.onCreate()
        statusStore = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateStatus("Starting Floresta")
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus()))

        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startNode()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val current = node
        node = null
        updateStatus("Stopping Floresta")

        executor.execute {
            try {
                current?.stop()
            } finally {
                current?.close()
                updateStatus("Floresta stopped")
                executor.shutdown()
            }
        }

        super.onDestroy()
    }

    private fun startNode() {
        if (node != null || starting) return
        starting = true

        executor.execute {
            var startedNode: Florestad? = null
            try {
                val selectedNetwork = selectedNetwork()
                val dataDir = File(filesDir, DATA_DIR_NAME).apply { mkdirs() }
                ensureHints(selectedNetwork, dataDir)
                val config = Config(
                    datadir = dataDir.absolutePath,
                    network = selectedNetwork.network,
                    assumeValid = AssumeValidArg.Hardcoded,
                    jsonRpcAddress = JSON_RPC_ADDRESS,
                    electrumAddress = ELECTRUM_ADDRESS,
                    logToFile = true,
                    logToStdout = true,
                    userAgent = "/floresta-android-sample:1.0/",
                )

                updateStatus("Starting ${selectedNetwork.displayName} with JSON-RPC on $JSON_RPC_ADDRESS")
                startedNode = Florestad.fromConfig(config)
                startedNode.start()
                node = startedNode
                startedNode = null
                notifyStatus("$STATUS_RUNNING_PREFIX${selectedNetwork.displayName} ($JSON_RPC_ADDRESS)")
            } catch (error: FlorestaFfiException.StartException) {
                startedNode?.close()
                notifyStatus("Floresta failed: ${error.details}")
            } catch (error: Throwable) {
                startedNode?.close()
                notifyStatus("Floresta failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                starting = false
            }
        }
    }

    private fun selectedNetwork(): SelectedNetwork {
        val selected = statusStore.getString(KEY_SELECTED_NETWORK, NETWORK_BITCOIN) ?: NETWORK_BITCOIN
        return when (selected) {
            NETWORK_SIGNET -> SelectedNetwork(
                displayName = "Signet",
                network = Network.SIGNET,
                hintsUrl = "$HINTS_BASE_URL/signet",
                hintsFile = "signet.hints",
            )
            else -> SelectedNetwork(
                displayName = "Bitcoin",
                network = Network.BITCOIN,
                hintsUrl = "$HINTS_BASE_URL/bitcoin",
                hintsFile = "bitcoin.hints",
            )
        }
    }

    private fun ensureHints(selectedNetwork: SelectedNetwork, dataDir: File) {
        val hintsFile = File(dataDir, selectedNetwork.hintsFile)
        if (hintsFile.isFile && hintsFile.length() > 0L) {
            updateHintsProgress(hintsFile.length(), hintsFile.length(), true)
            return
        }

        notifyStatus("Downloading ${selectedNetwork.hintsFile} before starting Floresta")
        updateHintsProgress(0L, -1L, false)
        val tempFile = File(dataDir, "${selectedNetwork.hintsFile}.tmp").apply { delete() }
        val request = Request.Builder().url(selectedNetwork.hintsUrl).build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download ${selectedNetwork.hintsFile}: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Failed to download ${selectedNetwork.hintsFile}: empty body")
            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val totalBytes = body.contentLength()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastUpdateBytes = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (downloadedBytes - lastUpdateBytes >= HINTS_PROGRESS_UPDATE_BYTES) {
                            updateHintsProgress(downloadedBytes, totalBytes, false)
                            updateStatus(downloadStatus(selectedNetwork.hintsFile, downloadedBytes, totalBytes))
                            lastUpdateBytes = downloadedBytes
                        }
                    }

                    updateHintsProgress(downloadedBytes, totalBytes, false)
                    updateStatus(downloadStatus(selectedNetwork.hintsFile, downloadedBytes, totalBytes))
                }
            }
        }

        if (tempFile.length() == 0L) {
            tempFile.delete()
            throw IOException("Downloaded ${selectedNetwork.hintsFile} is empty")
        }

        if (hintsFile.exists() && !hintsFile.delete()) {
            tempFile.delete()
            throw IOException("Failed to replace invalid ${selectedNetwork.hintsFile} in ${dataDir.absolutePath}")
        }

        if (!tempFile.renameTo(hintsFile)) {
            tempFile.delete()
            throw IOException("Failed to place ${selectedNetwork.hintsFile} in ${dataDir.absolutePath}")
        }

        updateHintsProgress(hintsFile.length(), hintsFile.length(), true)
    }

    private fun updateHintsProgress(downloadedBytes: Long, totalBytes: Long, complete: Boolean) {
        statusStore.edit()
            .putLong(KEY_HINTS_DOWNLOADED_BYTES, downloadedBytes)
            .putLong(KEY_HINTS_TOTAL_BYTES, totalBytes)
            .putBoolean(KEY_HINTS_COMPLETE, complete)
            .apply()
    }

    private fun downloadStatus(hintsFile: String, downloadedBytes: Long, totalBytes: Long): String {
        val progress = if (totalBytes > 0L) {
            " ${(downloadedBytes * 100.0 / totalBytes).roundToInt()}%"
        } else {
            ""
        }
        return "Downloading $hintsFile$progress"
    }

    private fun notifyStatus(text: String) {
        updateStatus(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateStatus(text: String) {
        statusStore.edit().putString(KEY_STATUS, text).apply()
    }

    private fun currentStatus(): String = statusStore.getString(KEY_STATUS, null) ?: "Starting Floresta"

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Floresta node")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Floresta node")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .build()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floresta node",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class SelectedNetwork(
        val displayName: String,
        val network: Network,
        val hintsUrl: String,
        val hintsFile: String,
    )

    companion object {
        const val JSON_RPC_ADDRESS = "127.0.0.1:8332"
        const val STATUS_PREFS = "floresta-service-status"
        const val KEY_STATUS = "status"
        const val KEY_SELECTED_NETWORK = "selected-network"
        const val KEY_HINTS_DOWNLOADED_BYTES = "hints-downloaded-bytes"
        const val KEY_HINTS_TOTAL_BYTES = "hints-total-bytes"
        const val KEY_HINTS_COMPLETE = "hints-complete"
        const val STATUS_RUNNING_PREFIX = "Floresta running on "
        const val NETWORK_BITCOIN = "bitcoin"
        const val NETWORK_SIGNET = "signet"
        private const val DATA_DIR_NAME = ".floresta"
        private const val HINTS_BASE_URL = "https://utxohints.store/hints"
        private const val HINTS_PROGRESS_UPDATE_BYTES = 512L * 1024L
        private const val ELECTRUM_ADDRESS = "127.0.0.1:50001"
        private const val ACTION_STOP = "org.getfloresta.sample.STOP"
        private const val CHANNEL_ID = "floresta-node"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FlorestaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
