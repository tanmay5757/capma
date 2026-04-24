package com.example.andriod_project.capma.engine

import com.example.andriod_project.capma.model.AppCategory
import com.example.andriod_project.capma.model.DataType
import com.example.andriod_project.capma.model.DetectionResult
import com.example.andriod_project.capma.model.DetectionSeverity
import com.example.andriod_project.capma.model.DetectionType
import com.example.andriod_project.capma.model.SignalSnapshot
import com.example.andriod_project.capma.runtime.DomainKnowledge

class CapmaEngine(
    private val inferer: ActualUsageInferer = ActualUsageInferer(),
    private val expectedPolicy: ExpectedUsagePolicy = ExpectedUsagePolicy(),
    private val domainClassifier: DomainClassifier = DomainClassifier(),
    private val mismatchEngine: MismatchEngine = MismatchEngine()
) {
    fun evaluate(snapshot: SignalSnapshot): DetectionResult {
        val actual = inferer.infer(snapshot)
        val expected = expectedPolicy.resolve(snapshot.category, snapshot.context.isForeground, snapshot.context.hasRecentInteraction)
        val unexpected = actual - expected
        val domainHints = domainClassifier.classify(snapshot.contactedDomains)
        val classification = mismatchEngine.classify(snapshot, unexpected, domainHints)

        return DetectionResult(
            packageName = snapshot.packageName,
            appLabel = snapshot.appLabel,
            expectedUsage = expected,
            actualUsage = actual,
            unexpectedUsage = unexpected,
            status = classification.first,
            type = classification.second,
            explanation = buildExplanation(snapshot, unexpected, classification.first),
            domainHints = domainHints
        )
    }

    private fun buildExplanation(
        snapshot: SignalSnapshot,
        unexpected: Set<DataType>,
        status: DetectionSeverity
    ): String {
        val state = if (snapshot.context.isForeground) "foreground" else "background"
        val interactionState = if (snapshot.context.hasRecentInteraction) {
            "recent user interaction (${snapshot.context.interactionAgeSeconds}s ago)"
        } else {
            "no recent user interaction"
        }
        val behavior = when {
            snapshot.repeatedTrafficInBackground -> "repeated network pattern detected"
            snapshot.highFrequencyCalls -> "high-frequency calls observed"
            else -> "normal traffic cadence"
        }
        val unexpectedText = if (unexpected.isEmpty()) "none" else unexpected.joinToString()
        return "App in $state, $interactionState, $behavior. Unexpected usage: $unexpectedText. Classified as $status."
    }
}

class ActualUsageInferer {
    fun infer(snapshot: SignalSnapshot): Set<DataType> {
        val usage = mutableSetOf(DataType.NETWORK)
        if (snapshot.touchedLocationEndpoint) usage += DataType.LOCATION
        if (snapshot.sentDeviceIdentifier) usage += DataType.DEVICE_IDENTITY
        if (snapshot.repeatedTrafficInBackground || snapshot.highFrequencyCalls) usage += DataType.BEHAVIORAL
        if (snapshot.mediaApiTrigger) {
            usage += DataType.CAMERA
            usage += DataType.MICROPHONE
        }
        return usage
    }
}

class ExpectedUsagePolicy {
    fun resolve(category: AppCategory, isForeground: Boolean, hasRecentInteraction: Boolean): Set<DataType> {
        val expected = mutableSetOf(DataType.NETWORK)
        when (category) {
            AppCategory.MAPS -> if (isForeground) expected += DataType.LOCATION
            AppCategory.GAME -> if (isForeground && hasRecentInteraction) expected += DataType.BEHAVIORAL
            AppCategory.SOCIAL -> if (hasRecentInteraction) expected += DataType.BEHAVIORAL
            AppCategory.UTILITY -> Unit
            AppCategory.UNKNOWN -> Unit
        }
        return expected
    }
}

class DomainClassifier {
    fun classify(domains: Set<String>): Set<String> {
        return DomainKnowledge.classify(domains)
    }
}

class MismatchEngine {
    fun classify(
        snapshot: SignalSnapshot,
        unexpectedUsage: Set<DataType>,
        domainHints: Set<String>
    ): Pair<DetectionSeverity, DetectionType> {
        if (!snapshot.context.isForeground &&
            !snapshot.context.hasRecentInteraction &&
            snapshot.repeatedTrafficInBackground
        ) {
            return DetectionSeverity.TRACKING to DetectionType.TRACKING
        }

        if (unexpectedUsage.isNotEmpty()) {
            return DetectionSeverity.TRACKING to typeFromDomainHints(domainHints, fallback = DetectionType.TRACKING)
        }

        if (snapshot.highFrequencyCalls || domainHints.contains("analytics")) {
            return DetectionSeverity.SUSPICIOUS to DetectionType.ANALYTICS
        }

        return DetectionSeverity.NORMAL to DetectionType.FUNCTIONAL
    }

    private fun typeFromDomainHints(hints: Set<String>, fallback: DetectionType): DetectionType {
        return when {
            hints.contains("ads") -> DetectionType.ADS
            hints.contains("analytics") -> DetectionType.ANALYTICS
            else -> fallback
        }
    }
}
