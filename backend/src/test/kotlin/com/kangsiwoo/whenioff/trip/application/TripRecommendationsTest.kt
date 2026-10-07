package com.kangsiwoo.whenioff.trip.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TripRecommendationsTest {
    @Test
    fun `versions compare numbers as numbers and ignore case, like the desktop compareVersions`() {
        val sorted = listOf("v10", "v2", "V1", "v1a", "baseline").sortedWith(TripRecommendations::compareVersions)
        assertEquals(listOf("baseline", "V1", "v1a", "v2", "v10"), sorted)
        assertEquals(0, TripRecommendations.compareVersions("v2", "V2"))
    }
}
