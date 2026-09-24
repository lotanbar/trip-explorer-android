package com.lotanbar.tripexplorer

import com.lotanbar.tripexplorer.data.Plans
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun readsTheVisitedTick() {
        assertEquals(
            listOf(Plans.Stop(37.1, 25.4, "Portara, Naxos", visited = true), Plans.Stop(37.0, 25.3, "Beach")),
            Plans.parse("37.1, 25.4, Portara, Naxos, Visited\n37.0, 25.3, Beach\n"),
        )
    }

    @Test
    fun ticksOneStopLeavingTheOtherLinesAsTheyWere() {
        val file = File.createTempFile("plan", ".txt").apply { deleteOnExit() }
        val text = "# my plan\r\n45.4642, 9.19, Castello Sforzesco\r\n\r\n37.1,25.4,Portara, Naxos"
        file.writeText(text)
        assertTrue(Plans.setVisited(file, 1, true))
        assertEquals("# my plan\r\n45.4642, 9.19, Castello Sforzesco\r\n\r\n37.1,25.4,Portara, Naxos, visited", file.readText())
        assertTrue(Plans.setVisited(file, 1, false))
        assertEquals(text, file.readText())
        assertTrue(Plans.setVisited(file, 0, true))
        assertEquals("# my plan\r\n45.4642, 9.19, Castello Sforzesco, visited\r\n\r\n37.1,25.4,Portara, Naxos", file.readText())
    }

    @Test
    fun buildsTheWazeLink() {
        assertEquals("https://waze.com/ul?ll=37.110210,25.372300&navigate=yes", Plans.wazeUrl(Plans.Stop(37.11021, 25.3723, "Portara")))
    }
}
