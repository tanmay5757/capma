package com.example.andriod_project.capma.runtime

import com.example.andriod_project.capma.model.SignalSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object CapmaRuntimeBus {
    private val _networkEvents = MutableSharedFlow<NetworkEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _snapshots = MutableSharedFlow<SignalSnapshot>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _monitoringActive = MutableStateFlow(false)
    private val _debugStats = MutableStateFlow(RuntimeDebugStats())

    val networkEvents = _networkEvents.asSharedFlow()
    val snapshots = _snapshots.asSharedFlow()
    val monitoringActive = _monitoringActive.asStateFlow()
    val debugStats = _debugStats.asStateFlow()

    fun emitNetworkEvent(event: NetworkEvent) {
        _networkEvents.tryEmit(event)
    }

    fun emitSnapshot(snapshot: SignalSnapshot) {
        _snapshots.tryEmit(snapshot)
    }

    fun setMonitoringActive(active: Boolean) {
        _monitoringActive.value = active
    }

    fun updateDebugStats(transform: (RuntimeDebugStats) -> RuntimeDebugStats) {
        _debugStats.value = transform(_debugStats.value)
    }
}
