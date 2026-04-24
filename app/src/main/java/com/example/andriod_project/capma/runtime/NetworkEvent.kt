package com.example.andriod_project.capma.runtime

enum class SyntheticScenario {
    NONE,
    BURST_TRAFFIC,
    PERIODIC_BACKGROUND,
    NO_INTERACTION
}

data class NetworkEvent(
    val uid: Int,
    val destinationIp: String,
    val destinationDomain: String?,
    val timestampMillis: Long,
    val bytes: Int,
    val protocol: Int,
    val syntheticScenario: SyntheticScenario = SyntheticScenario.NONE
)
