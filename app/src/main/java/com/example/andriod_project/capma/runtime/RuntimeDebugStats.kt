package com.example.andriod_project.capma.runtime

data class RuntimeDebugStats(
    val eventsPerSecond: Float = 0f,
    val activeAppsTracked: Int = 0,
    val queueSize: Int = 0,
    val droppedEventsCount: Long = 0,
    val domainResolutionFailures: Long = 0,
    val uidMappingFailures: Long = 0,
    val lastBatchSize: Int = 0,
    val lastBatchProcessingMs: Long = 0L
)
