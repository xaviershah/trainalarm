package com.trainalarm.app.provider

import org.json.JSONException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StationDirectoryTest {
    // Gradle runs unit tests with the module directory (android/app) as the working directory.
    private val bundled = File("src/main/assets/stations.json")
    private val canonical = File("../../data/stations.json")
    private val directory by lazy { StationDirectory.parse(bundled.readText(Charsets.UTF_8)) }

    private fun small(vararg names: String) = StationDirectory.parse(
        names.mapIndexed { i, n ->
            """{"crs":"A${('A' + i)}${('A' + i)}","name":"$n","lat":51.5,"lon":-0.1}"""
        }.joinToString(",", "[", "]")
    )

    @Test fun bundledCopyIsIdenticalToCanonicalFile() =
        assertArrayEquals(canonical.readBytes(), bundled.readBytes())

    @Test fun recordCountIsInExpectedRange() =
        assertTrue(directory.stations.size in 2600..2650)

    @Test fun everyCrsIsThreeUppercaseLettersAndUnique() {
        val ids = directory.stations.map { it.id }
        assertTrue(ids.all { Regex("[A-Z]{3}").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun everyCoordinateIsInsideTheUkBoundingBox() =
        assertTrue(directory.stations.all { it.latitude in 49.8..60.9 && it.longitude in -8.7..1.8 })

    @Test fun knownStationsResolve() {
        assertEquals("London Kings Cross", directory.station("KGX")?.name)
        assertEquals("Manchester Piccadilly", directory.station("MAN")?.name)
        assertEquals("Edinburgh", directory.station("EDB")?.name)
        assertEquals("Cardiff Central", directory.station("CDF")?.name)
        assertEquals(51.5309, directory.station("KGX")!!.latitude, 0.001)
        assertEquals(-0.1229, directory.station("KGX")!!.longitude, 0.001)
    }

    @Test fun lookupIgnoresCaseAndWhitespace() {
        assertEquals("KGX", directory.station("kgx")?.id)
        assertEquals("KGX", directory.station(" KGX ")?.id)
    }

    @Test fun unknownAndCoordinateLessCodesReturnNull() {
        assertNull(directory.station("ZZZ"))
        assertNull(directory.station("PDX")) // NaPTAN has no coordinates for PDX
    }

    @Test fun searchPutsPrefixMatchesBeforeOtherMatches() {
        val dir = small("Waltham Cross", "Gerrards Cross", "Cross Gates")
        assertEquals(listOf("Cross Gates", "Gerrards Cross", "Waltham Cross"), dir.search("cross").map { it.name })
    }

    @Test fun searchIsCaseInsensitiveAndFindsRealStations() {
        assertTrue(directory.search("KINGS CROSS").any { it.id == "KGX" })
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(directory.search("").isEmpty())
        assertTrue(directory.search("   ").isEmpty())
    }

    @Test fun ampersandsAndParenthesesAreSearchableAndNeverPatterns() {
        assertTrue(directory.search("harrow & wealdstone").any { it.id == "HRW" })
        assertTrue(directory.search("richmond (london)").any { it.id == "RMD" })
        directory.search("(") // must not throw
        directory.search("[a-")
    }

    @Test fun parseRejectsAMissingField() {
        assertThrows(JSONException::class.java) { StationDirectory.parse("""[{"crs":"KGX","name":"X","lat":51.5}]""") }
    }

    @Test fun parseRejectsANonArray() {
        assertThrows(JSONException::class.java) { StationDirectory.parse("""{"crs":"KGX"}""") }
    }

    @Test fun parseRejectsDuplicateCrs() {
        val dup = """[{"crs":"KGX","name":"A","lat":51.5,"lon":-0.1},{"crs":"KGX","name":"B","lat":51.5,"lon":-0.1}]"""
        assertThrows(IllegalArgumentException::class.java) { StationDirectory.parse(dup) }
    }
}
