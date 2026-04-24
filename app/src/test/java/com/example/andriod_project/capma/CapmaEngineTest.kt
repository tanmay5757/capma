package com.example.andriod_project.capma

import com.example.andriod_project.capma.engine.CapmaEngine
import com.example.andriod_project.capma.model.AppCategory
import com.example.andriod_project.capma.model.AppContext
import com.example.andriod_project.capma.model.DataType
import com.example.andriod_project.capma.model.DetectionSeverity
import com.example.andriod_project.capma.model.SignalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapmaEngineTest {
    private val engine = CapmaEngine()

    @Test
    fun mapsInForegroundWithLocation_isNormal() {
        val result = engine.evaluate(
            SignalSnapshot(
                packageName = "com.maps.navigator",
                appLabel = "Map Navigator",
                category = AppCategory.MAPS,
                context = AppContext(isForeground = true, hasRecentInteraction = true, interactionAgeSeconds = 1),
                touchedLocationEndpoint = true
            )
        )

        assertEquals(DetectionSeverity.NORMAL, result.status)
        assertTrue(result.unexpectedUsage.isEmpty())
    }

    @Test
    fun backgroundRepeatedTrafficWithoutInteraction_isTracking() {
        val result = engine.evaluate(
            SignalSnapshot(
                packageName = "com.random.app",
                appLabel = "Random App",
                category = AppCategory.UNKNOWN,
                context = AppContext(isForeground = false, hasRecentInteraction = false, interactionAgeSeconds = 300),
                repeatedTrafficInBackground = true,
                sentDeviceIdentifier = true
            )
        )

        assertEquals(DetectionSeverity.TRACKING, result.status)
        assertTrue(result.unexpectedUsage.contains(DataType.DEVICE_IDENTITY))
    }
}
