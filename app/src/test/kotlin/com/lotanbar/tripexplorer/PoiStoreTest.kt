package com.lotanbar.tripexplorer

import com.lotanbar.tripexplorer.data.Categories
import com.lotanbar.tripexplorer.data.PoiStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PoiStoreTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val at = LocalDateTime.of(2026, 9, 14, 11, 32, 7).atZone(athens).toInstant().toEpochMilli()

    @Test
    fun folderIsTheTapsLocalTimeToTheSecond() {
        assertEquals("14.09.2026 11-32-07", PoiStore.folderName(at, athens))
    }

    @Test
    fun dataTxtHasCoordinatesDatetimeAndCategory() {
        assertEquals(
            "coordinates: 45.46420, 9.19000\ndatetime: 2026-09-14 11-32\ncategory: viewpoints\n",
            PoiStore.dataText(45.4642, 9.19, at, "viewpoints", athens),
        )
    }

    @Test
    fun coordinatesHaveFiveDecimalsAndADotWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "coordinates: -37.12346, 25.00000\ndatetime: 2026-09-14 11-32\ncategory: none\n",
                PoiStore.dataText(-37.123456, 25.0, at, Categories.NONE, athens),
            )
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun gridOrderAndKeysMatchTheSpec() {
        assertEquals(listOf("viewpoints", "religion", "food", "history", "oddity", "none"), Categories.all.map { it.key })
        assertNotNull(Categories.byKey("none"))
    }
}
