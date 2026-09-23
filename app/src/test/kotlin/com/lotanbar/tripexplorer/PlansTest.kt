package com.lotanbar.tripexplorer

import com.lotanbar.tripexplorer.data.Plans
import org.junit.Assert.assertEquals
import org.junit.Test

class PlansTest {
    @Test
    fun parsesStopsInOrderKeepingCommasInNames() {
        val text = listOf(
            "45.46420, 9.19000, Castello Sforzesco",
            "",
            "not a stop",
            "37.1,25.4,Portara, Naxos",
            "91, 0, out of range",
            "37.0, 25.3",
        ).joinToString("\r\n")
        assertEquals(
            listOf(
                Plans.Stop(45.4642, 9.19, "Castello Sforzesco"),
                Plans.Stop(37.1, 25.4, "Portara, Naxos"),
                Plans.Stop(37.0, 25.3, "37.0, 25.3"),
            ),
            Plans.parse(text),
        )
    }

    @Test
    fun buildsTheWazeLink() {
        assertEquals("https://waze.com/ul?ll=37.110210,25.372300&navigate=yes", Plans.wazeUrl(Plans.Stop(37.11021, 25.3723, "Portara")))
    }
}
