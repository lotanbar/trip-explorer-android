package com.lotanbar.tripexplorer

import com.lotanbar.tripexplorer.data.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {
    @Test
    fun allowsOrdinaryNames() {
        assertNull(Names.check("Kastro cave"))
        assertNull(Names.check("Πορτάρα"))
        assertNull(Names.check("מערה", isPoi = true))
        assertNull(Names.check("Greece 2026", taken = listOf("Italy 2025")))
    }

    @Test
    fun refusesWindowsForbiddenCharacters() {
        for (c in "\\/:*?\"<>|") {
            val msg = Names.check("a${c}b")
            assertNotNull("expected refusal for $c", msg)
            assertEquals("$c isn't allowed — Windows can't use it in folder names.", msg)
        }
    }

    @Test
    fun refusesTrailingDotOrSpace() {
        assertNotNull(Names.check("name."))
        assertNotNull(Names.check("name "))
        assertNotNull(Names.check(""))
    }

    @Test
    fun refusesReservedNames() {
        assertNotNull(Names.check("CON"))
        assertNotNull(Names.check("com3"))
        assertNotNull(Names.check("LPT9"))
        assertNull(Names.check("CONSOLE"))
    }

    @Test
    fun refusesRecordingsForPoisOnly() {
        assertNotNull(Names.check("recordings", isPoi = true))
        assertNotNull(Names.check("Recordings", isPoi = true))
        assertNull(Names.check("recordings", isPoi = false))
    }

    @Test
    fun refusesDuplicatesCaseInsensitively() {
        assertNotNull(Names.check("portara", taken = listOf("Portara"), isPoi = true))
        assertNotNull(Names.check("Greece", taken = listOf("greece")))
    }
}
