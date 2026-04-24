package com.example.andriod_project

import android.Manifest
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.andriod_project.capma.model.DetectionResult
import com.example.andriod_project.capma.runtime.CapmaRuntimeBus
import com.example.andriod_project.capma.runtime.CapmaVpnService
import com.example.andriod_project.capma.runtime.InteractionTracker
import com.example.andriod_project.capma.runtime.RuntimeDebugStats
import com.example.andriod_project.capma.ui.CapmaViewModel
import com.example.andriod_project.capma.ui.SoakTestState
import com.example.andriod_project.ui.theme.Andriod_projectTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CapmaViewModel>()
    private val tag = "CapmaMainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Andriod_projectTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CapmaScreen(
                        viewModel = viewModel,
                        onStartMonitoring = { prepareAndStartVpn() },
                        onStopMonitoring = { stopMonitoring() },
                        onGrantVpn = { prepareAndStartVpn() },
                        onGrantUsageAccess = { openUsageAccessSettings() },
                        hasVpnPermission = { VpnService.prepare(this) == null },
                        hasUsageAccess = { checkUsageAccessPermission() },
                        showDebugPanel = isDebugBuild(),
                        copyReportText = { copyReportToClipboard(it) },
                        shareReportText = { shareReport(it) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun prepareAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
                return
            }
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startMonitoring() else vpnPermissionLauncher.launch(intent)
    }

    private fun startMonitoring() {
        if (CapmaRuntimeBus.monitoringActive.value) return
        if (VpnService.prepare(this) != null) return
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, CapmaVpnService::class.java))
        }.onFailure {
            Log.e(tag, "vpn_start_failed ${it.message}")
        }
    }

    private fun stopMonitoring() {
        val stopIntent = Intent(this, CapmaVpnService::class.java).setAction(CapmaVpnService.ACTION_STOP_MONITORING)
        startService(stopIntent)
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun checkUsageAccessPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startMonitoring() else Log.w(tag, "vpn_permission_denied")
        }

    private fun isDebugBuild(): Boolean {
        return (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun copyReportToClipboard(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("CAPMA Soak Report", report))
    }

    private fun shareReport(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share CAPMA Soak Report"))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                prepareAndStartVpn()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
    }
}

@Composable
fun CapmaScreen(
    viewModel: CapmaViewModel,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onGrantVpn: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    hasVpnPermission: () -> Boolean,
    hasUsageAccess: () -> Boolean,
    showDebugPanel: Boolean,
    copyReportText: (String) -> Unit,
    shareReportText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val results by viewModel.results.collectAsState()
    val monitoringActive by viewModel.monitoringActive.collectAsState()
    val debugStats by viewModel.debugStats.collectAsState()
    val soakState by viewModel.soakState.collectAsState()
    var showDev by remember { mutableStateOf(false) }
    var soakUnexpectedStop by remember { mutableStateOf(false) }
    var soakSawMonitoringUp by remember { mutableStateOf(false) }
    val vpnPermission by produceState(initialValue = false, monitoringActive) {
        while (true) {
            value = hasVpnPermission()
            delay(1500)
        }
    }
    val usagePermission by produceState(initialValue = false, monitoringActive) {
        while (true) {
            value = hasUsageAccess()
            delay(1500)
        }
    }

    LaunchedEffect(soakState.running, monitoringActive) {
        if (!soakState.running) {
            soakSawMonitoringUp = false
            return@LaunchedEffect
        }
        if (monitoringActive) soakSawMonitoringUp = true else if (soakSawMonitoringUp) soakUnexpectedStop = true
    }
    LaunchedEffect(soakState.running) {
        if (!soakState.running) return@LaunchedEffect
        soakUnexpectedStop = false
        if (!monitoringActive) onStartMonitoring()
        delay(2 * CapmaViewModel.MINUTE_MS)
        repeat(8) { viewModel.injectNormalForegroundTraffic(); delay(15_000L) }
        repeat(8) { viewModel.injectPeriodicBackgroundTraffic(); delay(15_000L) }
        repeat(6) { viewModel.injectBurstTraffic(); delay(20_000L) }
        repeat(8) { viewModel.injectNoInteractionTraffic(); delay(15_000L) }
        repeat(4) {
            onStopMonitoring(); delay(8_000L); onStartMonitoring(); delay(20_000L)
        }
        viewModel.finishSoakTest(vpnStoppedUnexpectedly = soakUnexpectedStop)
    }
    LaunchedEffect(debugStats, soakState.running) {
        if (soakState.running) viewModel.recordSoakSample(debugStats)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable { InteractionTracker.onUserInteraction() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopStatusCard(
            monitoringActive = monitoringActive,
            onStart = onStartMonitoring,
            onStop = onStopMonitoring,
            onOpenDev = { showDev = !showDev },
            showDev = showDebugPanel
        )
        PermissionCompactRow(
            vpnGranted = vpnPermission,
            usageGranted = usagePermission,
            onGrantVpn = onGrantVpn,
            onGrantUsageAccess = onGrantUsageAccess
        )
        Text("App Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (results.isEmpty()) Text("No app results yet. Start monitoring and interact with apps.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(results) { result ->
                AppRiskCard(result)
            }
        }
        if (showDev && showDebugPanel) {
            DebugPanel(
                stats = debugStats,
                onInjectBurstTraffic = viewModel::injectBurstTraffic,
                onInjectPeriodicTraffic = viewModel::injectPeriodicBackgroundTraffic,
                onInjectNoInteraction = viewModel::injectNoInteractionTraffic,
                soakState = soakState,
                onStartSoakTest = viewModel::startSoakTest,
                onCopyReport = { copyReportText(viewModel.soakReportText()) },
                onShareReport = { shareReportText(viewModel.soakReportText()) }
            )
        }
    }
}

@Composable
private fun TopStatusCard(
    monitoringActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDev: () -> Unit,
    showDev: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (monitoringActive) Color(0xFF2E7D32) else Color(0xFFC62828),
                            shape = MaterialTheme.shapes.small
                        )
                )
                Text(
                    "CAPMA Status: ${if (monitoringActive) "Monitoring Active" else "Not Running"}",
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !monitoringActive) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = monitoringActive) { Text("Stop") }
                if (showDev) {
                    OutlinedButton(onClick = onOpenDev) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("Dev")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCompactRow(
    vpnGranted: Boolean,
    usageGranted: Boolean,
    onGrantVpn: () -> Unit,
    onGrantUsageAccess: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Permissions:", fontWeight = FontWeight.SemiBold)
            PermissionChip("VPN", vpnGranted, onGrantVpn)
            PermissionChip("Usage", usageGranted, onGrantUsageAccess)
        }
    }
}

@Composable
private fun PermissionChip(label: String, granted: Boolean, onGrant: () -> Unit) {
    FilterChip(
        selected = granted,
        onClick = { if (!granted) onGrant() },
        label = { Text("$label ${if (granted) "✓" else "Grant"}") },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFE8F5E9),
            selectedLabelColor = Color(0xFF1B5E20)
        )
    )
}

@Composable
private fun AppRiskCard(result: DetectionResult) {
    val statusColor = when (result.status.name) {
        "NORMAL" -> Color(0xFF2E7D32)
        "SUSPICIOUS" -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            setImageDrawable(
                                runCatching { ctx.packageManager.getApplicationIcon(result.packageName) }.getOrNull()
                            )
                        }
                    },
                    modifier = Modifier.size(30.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.appLabel, fontWeight = FontWeight.Bold)
                    Text("Risk Score: ${result.status.score}", style = MaterialTheme.typography.bodySmall)
                }
                Text(result.status.name, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Text("USED:", fontWeight = FontWeight.SemiBold)
            Text(result.actualUsage.joinToString("  •  ") { dataLabel(it.name) })
            Text("EXPECTED:", fontWeight = FontWeight.SemiBold)
            Text(result.expectedUsage.joinToString("  •  ").ifBlank { "None" })
            Text("UNEXPECTED:", fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))
            Text(
                result.unexpectedUsage.joinToString("  •  ") { dataLabel(it.name) }.ifBlank { "None" },
                color = Color(0xFFC62828)
            )
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF57F17))
                    Text(result.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun dataLabel(raw: String): String {
    return when (raw) {
        "LOCATION" -> "📍 Location"
        "CAMERA" -> "📷 Camera"
        "MICROPHONE" -> "🎤 Mic"
        "DEVICE_IDENTITY" -> "🧠 Device ID"
        "BEHAVIORAL" -> "👣 Behavioral"
        "NETWORK" -> "🌐 Network"
        else -> raw
    }
}

@Composable
private fun DebugPanel(
    stats: RuntimeDebugStats,
    onInjectBurstTraffic: () -> Unit,
    onInjectPeriodicTraffic: () -> Unit,
    onInjectNoInteraction: () -> Unit,
    soakState: SoakTestState,
    onStartSoakTest: () -> Unit,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Dev Tools", fontWeight = FontWeight.SemiBold)
            Text("events/sec: ${stats.eventsPerSecond}")
            Text("queue: ${stats.queueSize}, dropped: ${stats.droppedEventsCount}")
            if (soakState.running) {
                Text("Soak: ${soakState.phase}")
                Text("Remaining: ${formatMs(soakState.remainingMillis)}")
            } else if (soakState.report != null) {
                Text("STATUS: ${if (soakState.report.pass) "PASS" else "FAIL"}", fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInjectBurstTraffic) { Text("Burst") }
                Button(onClick = onInjectPeriodicTraffic) { Text("Periodic") }
                Button(onClick = onInjectNoInteraction) { Text("No Interaction") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartSoakTest, enabled = !soakState.running) { Text("Start Soak") }
                OutlinedButton(onClick = onCopyReport, enabled = soakState.report != null) { Text("Copy") }
                OutlinedButton(onClick = onShareReport, enabled = soakState.report != null) { Text("Share") }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSec / 60L, totalSec % 60L)
}

@Preview(showBackground = true)
@Composable
fun CapmaPreview() {
    Andriod_projectTheme {
        Text("CAPMA")
    }
}