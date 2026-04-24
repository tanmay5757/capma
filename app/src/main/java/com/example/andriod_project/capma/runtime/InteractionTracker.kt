package com.example.andriod_project.capma.runtime

object InteractionTracker {
    @Volatile
    private var lastTouchMillis: Long = 0L

    fun onUserInteraction(nowMillis: Long = System.currentTimeMillis()) {
        lastTouchMillis = nowMillis
    }

    fun interactionAgeSeconds(nowMillis: Long = System.currentTimeMillis()): Long {
        if (lastTouchMillis == 0L) return Long.MAX_VALUE
        return ((nowMillis - lastTouchMillis).coerceAtLeast(0L)) / 1000L
    }

    fun hasRecentInteraction(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return interactionAgeSeconds(nowMillis) < 5L
    }
}
