package com.example.andriod_project.capma.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.andriod_project.MainActivity
import com.example.andriod_project.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class CapmaVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val resolver = DomainResolver()
    private lateinit var signalProcessor: SignalProcessor
    private val tag = "CAPMA_VPN"

    override fun onCreate() {
        super.onCreate()
        runCatching {
            signalProcessor = SignalProcessor(applicationContext)
            signalProcessor.start()
        }.onFailure {
            Log.e(tag, "signal_processor_init_failed")
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            shutdownAndStop()
            return Service.START_NOT_STICKY
        }
        if (worker != null) {
            updateForegroundNotification(isRunning = true)
            return Service.START_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = true))
        vpnInterface = Builder()
            .setSession("CAPMA")
            .addAddress("10.11.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .establish()
        val tun = vpnInterface
        if (tun == null) {
            Log.e(tag, "vpn_establish_failed")
            updateForegroundNotification(isRunning = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            return Service.START_NOT_STICKY
        }
        CapmaRuntimeBus.setMonitoringActive(true)
        worker = scope.launch { readTun(tun) }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        shutdownInternal()
        super.onDestroy()
    }

    private suspend fun readTun(tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(32767)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            Log.e(tag, "connectivity_manager_unavailable")
            input.close()
            shutdownAndStop()
            return
        }
        while (currentCoroutineContext().isActive) {
            val length = input.read(buffer)
            if (length <= 0) continue
            val packet = parseIpv4Packet(buffer, length) ?: continue
            val uid = connectivity.getConnectionOwnerUid(
                packet.protocol,
                InetSocketAddress(packet.sourceIp, packet.sourcePort),
                InetSocketAddress(packet.destinationIp, packet.destinationPort)
            )
            if (uid <= 0) continue
            val domain = resolver.resolve(packet.destinationIp)
            CapmaRuntimeBus.emitNetworkEvent(
                NetworkEvent(
                    uid = uid,
                    destinationIp = packet.destinationIp,
                    destinationDomain = domain,
                    timestampMillis = System.currentTimeMillis(),
                    bytes = length,
                    protocol = packet.protocol
                )
            )
        }
        input.close()
    }

    private fun shutdownAndStop() {
        shutdownInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownInternal() {
        CapmaRuntimeBus.setMonitoringActive(false)
        worker?.cancel()
        worker = null
        runCatching { signalProcessor.stop() }
        vpnInterface?.close()
        vpnInterface = null
        updateForegroundNotification(isRunning = false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CAPMA Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active CAPMA VPN monitoring status."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateForegroundNotification(isRunning: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning))
    }

    private fun buildNotification(isRunning: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1002,
            Intent(this, CapmaVpnService::class.java).setAction(ACTION_STOP_MONITORING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = if (isRunning) "Status: running" else "Status: stopped"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CAPMA Monitoring Active")
            .setContentText(statusText)
            .setOngoing(isRunning)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop Monitoring", stopIntent)
            .build()
    }

    private fun parseIpv4Packet(data: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null
        val version = data[0].toInt() ushr 4
        if (version != 4) return null
        val ihl = (data[0].toInt() and 0x0F) * 4
        if (length < ihl + 4) return null
        val protocol = data[9].toInt() and 0xFF
        val src = ByteBuffer.wrap(data, 12, 4).int
        val dst = ByteBuffer.wrap(data, 16, 4).int
        val sourceIp = intToIp(src)
        val destinationIp = intToIp(dst)
        val sourcePort = ((data[ihl].toInt() and 0xFF) shl 8) or (data[ihl + 1].toInt() and 0xFF)
        val destinationPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        return ParsedPacket(protocol, sourceIp, destinationIp, sourcePort, destinationPort)
    }

    private fun intToIp(value: Int): String {
        return InetAddress.getByAddress(
            byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        ).hostAddress ?: "0.0.0.0"
    }

    private data class ParsedPacket(
        val protocol: Int,
        val sourceIp: String,
        val destinationIp: String,
        val sourcePort: Int,
        val destinationPort: Int
    )

    companion object {
        const val ACTION_STOP_MONITORING = "com.example.andriod_project.action.STOP_MONITORING"
        private const val CHANNEL_ID = "capma_monitoring_channel"
        private const val NOTIFICATION_ID = 4041
    }
}
