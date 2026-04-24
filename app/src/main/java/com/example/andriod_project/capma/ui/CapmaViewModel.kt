package com.example.andriod_project.capma.ui

import android.app.Application
import android.os.Process
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.andriod_project.capma.engine.CapmaEngine
import com.example.andriod_project.capma.model.DetectionResult
import com.example.andriod_project.capma.runtime.CapmaRuntimeBus
import com.example.andriod_project.capma.runtime.NetworkEvent
import com.example.andriod_project.capma.runtime.RuntimeDebugStats
import com.example.andriod_project.capma.runtime.SyntheticScenario
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CapmaViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = CapmaEngine()
    private val latestByPackage = linkedMapOf<String, DetectionResult>()
    private val tag = "CAPMA_ENGINE"
    private val _results = MutableStateFlow<List<DetectionResult>>(emptyList())
    val results: StateFlow<List<DetectionResult>> = _results.asStateFlow()
    val monitoringActive: StateFlow<Boolean> = CapmaRuntimeBus.monitoringActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val debugStats: StateFlow<RuntimeDebugStats> = CapmaRuntimeBus.debugStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RuntimeDebugStats())
    private val _soakState = MutableStateFlow(SoakTestState())
    val soakState: StateFlow<SoakTestState> = _soakState.asStateFlow()
    private var soakTickerJob: Job? = null
    private val soakMetrics = mutableListOf<RuntimeDebugStats>()
    private var slowBatchHits = 0

    init {
        viewModelScope.launch {
            CapmaRuntimeBus.snapshots.collectLatest { snapshot ->
                runCatching {
                    val result = engine.evaluate(snapshot)
                    latestByPackage[result.packageName] = result
                    _results.value = latestByPackage.values.toList()
                }.onFailure {
                    Log.w(tag, "evaluation_error")
                }
            }
        }
    }

    fun refresh() {
        _results.value = latestByPackage.values.toList()
    }

    fun startSoakTest() {
        if (_soakState.value.running) return
        soakMetrics.clear()
        slowBatchHits = 0
        soakTickerJob?.cancel()
        val startedAt = System.currentTimeMillis()
        _soakState.value = SoakTestState(
            running = true,
            startedAtMillis = startedAt,
            remainingMillis = SOAK_TOTAL_MS,
            phase = "Idle monitoring (no interaction)",
            report = null
        )
        Log.i("CAPMA_SOAK", "soak_test_started duration_ms=$SOAK_TOTAL_MS")
        soakTickerJob = viewModelScope.launch {
            while (_soakState.value.running) {
                val elapsed = System.currentTimeMillis() - startedAt
                val remaining = (SOAK_TOTAL_MS - elapsed).coerceAtLeast(0L)
                _soakState.value = _soakState.value.copy(
                    remainingMillis = remaining,
                    phase = phaseForElapsed(elapsed)
                )
                delay(1_000L)
            }
        }
    }

    fun recordSoakSample(stats: RuntimeDebugStats) {
        if (!_soakState.value.running) return
        soakMetrics += stats
        if (stats.lastBatchProcessingMs > 50L) slowBatchHits++
    }

    fun finishSoakTest(vpnStoppedUnexpectedly: Boolean) {
        if (!_soakState.value.running) return
        soakTickerJob?.cancel()
        val sampleCount = soakMetrics.size.coerceAtLeast(1)
        val avgEventsPerSec = soakMetrics.sumOf { it.eventsPerSecond.toDouble() }.toFloat() / sampleCount
        val maxQueue = soakMetrics.maxOfOrNull { it.queueSize } ?: 0
        val maxDropped = soakMetrics.maxOfOrNull { it.droppedEventsCount } ?: 0L
        val maxDomainFailures = soakMetrics.maxOfOrNull { it.domainResolutionFailures } ?: 0L
        val maxUidFailures = soakMetrics.maxOfOrNull { it.uidMappingFailures } ?: 0L
        val avgBatchMs = soakMetrics.sumOf { it.lastBatchProcessingMs.toDouble() } / sampleCount
        val maxBatchMs = soakMetrics.maxOfOrNull { it.lastBatchProcessingMs } ?: 0L
        val queueUnbounded = maxQueue > 4_000
        val droppedTooHigh = maxDropped > 800
        val tooManySlowBatches = slowBatchHits > 8
        val monitoringUnexpectedlyStopped = vpnStoppedUnexpectedly
        val pass = !queueUnbounded && !droppedTooHigh && !tooManySlowBatches && !monitoringUnexpectedlyStopped

        val observations = buildList {
            if (tooManySlowBatches) add("Processing time exceeded 50ms repeatedly.")
            if (queueUnbounded) add("Queue grew beyond bounded threshold.")
            if (droppedTooHigh) add("Dropped events exceeded threshold.")
            if (monitoringUnexpectedlyStopped) add("VPN stopped unexpectedly during soak.")
            if (isEmpty()) add("Stable runtime observed across all scripted phases.")
        }

        val report = SoakTestReport(
            pass = pass,
            avgEventsPerSecond = avgEventsPerSec,
            maxQueueSize = maxQueue,
            droppedEvents = maxDropped,
            domainResolutionFailures = maxDomainFailures,
            uidMappingFailures = maxUidFailures,
            avgBatchProcessingMs = avgBatchMs,
            maxBatchProcessingMs = maxBatchMs,
            observations = observations
        )
        _soakState.value = _soakState.value.copy(
            running = false,
            remainingMillis = 0L,
            phase = "Completed",
            report = report
        )
        Log.i("CAPMA_SOAK", report.toLogText())
    }

    fun phaseForElapsed(elapsedMs: Long): String {
        return when {
            elapsedMs < 2 * MINUTE_MS -> "Idle monitoring (no interaction)"
            elapsedMs < 4 * MINUTE_MS -> "Normal foreground usage simulation"
            elapsedMs < 6 * MINUTE_MS -> "Background + periodic traffic simulation"
            elapsedMs < 8 * MINUTE_MS -> "Burst traffic stress"
            elapsedMs < 10 * MINUTE_MS -> "Screen off / background simulation"
            elapsedMs < 12 * MINUTE_MS -> "Rapid start/stop cycles"
            else -> "Completed"
        }
    }

    fun soakReportText(): String {
        val report = _soakState.value.report ?: return "Soak report not available."
        return report.toLogText()
    }

    fun injectBurstTraffic() {
        val now = System.currentTimeMillis()
        repeat(180) { index ->
            CapmaRuntimeBus.emitNetworkEvent(
                NetworkEvent(
                    uid = Process.myUid(),
                    destinationIp = "8.8.8.8",
                    destinationDomain = if (index % 3 == 0) "google-analytics.com" else null,
                    timestampMillis = now + index,
                    bytes = 600,
                    protocol = 6,
                    syntheticScenario = SyntheticScenario.BURST_TRAFFIC
                )
            )
        }
    }

    fun injectPeriodicBackgroundTraffic() {
        val now = System.currentTimeMillis()
        repeat(12) { index ->
            CapmaRuntimeBus.emitNetworkEvent(
                NetworkEvent(
                    uid = Process.myUid(),
                    destinationIp = "1.1.1.1",
                    destinationDomain = "doubleclick.net",
                    timestampMillis = now + (index * 500L),
                    bytes = 420,
                    protocol = 17,
                    syntheticScenario = SyntheticScenario.PERIODIC_BACKGROUND
                )
            )
        }
    }

    fun injectNormalForegroundTraffic() {
        val now = System.currentTimeMillis()
        repeat(40) { index ->
            CapmaRuntimeBus.emitNetworkEvent(
                NetworkEvent(
                    uid = Process.myUid(),
                    destinationIp = "142.250.182.14",
                    destinationDomain = "googleapis.com",
                    timestampMillis = now + (index * 120L),
                    bytes = 280,
                    protocol = 6,
                    syntheticScenario = SyntheticScenario.NONE
                )
            )
        }
    }

    fun injectNoInteractionTraffic() {
        val now = System.currentTimeMillis()
        repeat(20) { index ->
            CapmaRuntimeBus.emitNetworkEvent(
                NetworkEvent(
                    uid = Process.myUid(),
                    destinationIp = "9.9.9.9",
                    destinationDomain = "segment.io",
                    timestampMillis = now + (index * 80L),
                    bytes = 350,
                    protocol = 6,
                    syntheticScenario = SyntheticScenario.NO_INTERACTION
                )
            )
        }
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val SOAK_TOTAL_MS = 12 * MINUTE_MS
    }
}

data class SoakTestState(
    val running: Boolean = false,
    val startedAtMillis: Long = 0L,
    val remainingMillis: Long = 0L,
    val phase: String = "Not started",
    val report: SoakTestReport? = null
)

data class SoakTestReport(
    val pass: Boolean,
    val avgEventsPerSecond: Float,
    val maxQueueSize: Int,
    val droppedEvents: Long,
    val domainResolutionFailures: Long,
    val uidMappingFailures: Long,
    val avgBatchProcessingMs: Double,
    val maxBatchProcessingMs: Long,
    val observations: List<String>
) {
    fun toLogText(): String {
        return buildString {
            appendLine("STATUS: ${if (pass) "PASS" else "FAIL"}")
            appendLine("avg_events_per_sec=$avgEventsPerSecond")
            appendLine("max_queue_size=$maxQueueSize")
            appendLine("dropped_events=$droppedEvents")
            appendLine("domain_resolution_failures=$domainResolutionFailures")
            appendLine("uid_mapping_failures=$uidMappingFailures")
            appendLine("avg_batch_processing_ms=$avgBatchProcessingMs")
            appendLine("max_batch_processing_ms=$maxBatchProcessingMs")
            appendLine("observations=${observations.joinToString(" | ")}")
        }
    }
}
