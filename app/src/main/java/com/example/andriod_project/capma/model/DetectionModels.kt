package com.example.andriod_project.capma.model

enum class DataType {
    LOCATION,
    CAMERA,
    MICROPHONE,
    DEVICE_IDENTITY,
    BEHAVIORAL,
    NETWORK
}

enum class AppCategory {
    MAPS,
    GAME,
    SOCIAL,
    UTILITY,
    UNKNOWN
}

data class AppContext(
    val isForeground: Boolean,
    val hasRecentInteraction: Boolean,
    val interactionAgeSeconds: Long
)

data class SignalSnapshot(
    val packageName: String,
    val appLabel: String,
    val category: AppCategory,
    val context: AppContext,
    val touchedLocationEndpoint: Boolean = false,
    val sentDeviceIdentifier: Boolean = false,
    val repeatedTrafficInBackground: Boolean = false,
    val mediaApiTrigger: Boolean = false,
    val highFrequencyCalls: Boolean = false,
    val contactedDomains: Set<String> = emptySet()
)

enum class DetectionSeverity(val score: Int) {
    NORMAL(10),
    SUSPICIOUS(50),
    TRACKING(90)
}

enum class DetectionType {
    FUNCTIONAL,
    ANALYTICS,
    ADS,
    TRACKING
}

data class DetectionResult(
    val packageName: String,
    val appLabel: String,
    val expectedUsage: Set<DataType>,
    val actualUsage: Set<DataType>,
    val unexpectedUsage: Set<DataType>,
    val status: DetectionSeverity,
    val type: DetectionType,
    val explanation: String,
    val domainHints: Set<String>
)
