package com.example.andriod_project.capma.runtime

import android.content.Context
import android.util.Log
import com.example.andriod_project.capma.model.AppContext
import com.example.andriod_project.capma.model.SignalSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlin.math.abs

class SignalProcessor(private val context: Context) {
    private val appMapper = AppMapper(context)
    private val contextProvider = ContextSignalProvider(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffered = mutableMapOf<String, MutableList<NetworkEvent>>()
    private var collectorJob: Job? = null
    private var flushJob: Job? = null
    private val windowMillis = 2_000L
    private val maxTrackedApps = 128
    private val maxEventsPerApp = 200
    private val tag = "CAPMA_SIGNAL"
    private val eventTimes = ArrayDeque<Long>()

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            CapmaRuntimeBus.networkEvents.collectLatest { event ->
                runCatching {
                    val app = appMapper.fromUid(event.uid)
                    if (app == null) return@collectLatest
                    val key = app.packageName
                    if (!buffered.containsKey(key) && buffered.size >= maxTrackedApps) {
                        val oldestKey = buffered.keys.firstOrNull()
                        if (oldestKey != null) buffered.remove(oldestKey)
                        CapmaRuntimeBus.updateDebugStats { it.copy(droppedEventsCount = it.droppedEventsCount + 1) }
                    }
                    val list = buffered.getOrPut(key) { mutableListOf() }
                    if (list.size >= maxEventsPerApp) {
                        list.removeAt(0)
                        CapmaRuntimeBus.updateDebugStats { it.copy(droppedEventsCount = it.droppedEventsCount + 1) }
                    }
                    list += event
                    eventTimes.addLast(System.currentTimeMillis())
                    trimEventsPerSecondWindow()
                    updateLiveQueueStats()
                }.onFailure {
                    Log.w(tag, "collector_error")
                }
            }
        }
        flushJob = scope.launch {
            while (true) {
                delay(windowMillis)
                flushWindow()
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        flushJob?.cancel()
        collectorJob = null
        flushJob = null
        buffered.clear()
        eventTimes.clear()
        updateLiveQueueStats()
    }

    private fun flushWindow() {
        val batchStart = System.currentTimeMillis()
        val snapshotBuffer = buffered.toMap()
        buffered.clear()
        if (snapshotBuffer.isEmpty()) {
            CapmaRuntimeBus.updateDebugStats {
                it.copy(lastBatchSize = 0, lastBatchProcessingMs = System.currentTimeMillis() - batchStart)
            }
            updateLiveQueueStats()
            return
        }
        val batchSize = snapshotBuffer.values.sumOf { it.size }
        snapshotBuffer.forEach { (packageName, events) ->
            runCatching {
                val appInfo = appMapper.fromUid(events.first().uid) ?: return@forEach
                val now = System.currentTimeMillis()
                val interactionAge = if (events.any { it.syntheticScenario == SyntheticScenario.NO_INTERACTION }) {
                    120L
                } else {
                    InteractionTracker.interactionAgeSeconds(now)
                }
                val hasRecentInteraction = if (events.any { it.syntheticScenario == SyntheticScenario.NO_INTERACTION }) {
                    false
                } else {
                    InteractionTracker.hasRecentInteraction(now)
                }
                val isForeground = if (events.any { it.syntheticScenario == SyntheticScenario.PERIODIC_BACKGROUND }) {
                    false
                } else {
                    contextProvider.isForeground(packageName)
                }
                val domains = events.mapNotNull { it.destinationDomain }.toSet()
                val requestFrequency = events.size / (windowMillis / 1000f)
                val periodic = isPeriodic(events)
                val hasLocationSignal = domains.any { domain ->
                    val lowered = domain.lowercase(Locale.US)
                    lowered.contains("map") || lowered.contains("location") || lowered.contains("geo")
                }
                val snapshot = SignalSnapshot(
                    packageName = appInfo.packageName,
                    appLabel = appInfo.appLabel,
                    category = appInfo.category,
                    context = AppContext(
                        isForeground = isForeground,
                        hasRecentInteraction = hasRecentInteraction,
                        interactionAgeSeconds = interactionAge
                    ),
                    touchedLocationEndpoint = hasLocationSignal,
                    sentDeviceIdentifier = false,
                    repeatedTrafficInBackground = !isForeground && !hasRecentInteraction && periodic,
                    mediaApiTrigger = false,
                    highFrequencyCalls = requestFrequency >= 3f,
                    contactedDomains = domains.ifEmpty { events.map { it.destinationIp }.toSet() }
                )
                CapmaRuntimeBus.emitSnapshot(snapshot)
            }.onFailure {
                Log.w(tag, "batch_item_error")
            }
        }
        val processingMs = System.currentTimeMillis() - batchStart
        CapmaRuntimeBus.updateDebugStats { it.copy(lastBatchSize = batchSize, lastBatchProcessingMs = processingMs) }
        if (processingMs > 50L) {
            Log.w(tag, "processing_slow ms=$processingMs target=50")
        }
        updateLiveQueueStats()
    }

    private fun isPeriodic(events: List<NetworkEvent>): Boolean {
        if (events.size < 4) return false
        val sorted = events.sortedBy { it.timestampMillis }
        val intervals = sorted.zipWithNext { a, b -> (b.timestampMillis - a.timestampMillis).toFloat() }
        val average = intervals.average().toFloat()
        if (average <= 0f) return false
        val meanAbsDeviation = intervals.map { abs(it - average) }.average().toFloat()
        return (meanAbsDeviation / average) < 0.35f
    }

    private fun trimEventsPerSecondWindow() {
        val cutoff = System.currentTimeMillis() - 1_000L
        while (eventTimes.isNotEmpty() && eventTimes.first() < cutoff) {
            eventTimes.removeFirst()
        }
    }

    private fun updateLiveQueueStats() {
        val queueSize = buffered.values.sumOf { it.size }
        CapmaRuntimeBus.updateDebugStats {
            it.copy(
                eventsPerSecond = eventTimes.size.toFloat(),
                activeAppsTracked = buffered.size,
                queueSize = queueSize
            )
        }
    }
}
