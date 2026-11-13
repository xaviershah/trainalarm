package com.trainalarm.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationDirectoryNearestTest {
    private val json = """[{"crs":"WAT","name":"London Waterloo","lat":51.5031,"lon":-0.1132},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569},{"crs":"BSK","name":"Basingstoke","lat":51.2686,"lon":-1.0872}]"""

    @Test fun nearest_picksTheClosestStation() {
        val directory = StationDirectory.parse(json)
        assertEquals("WOK", directory.nearest(51.32, -0.56)?.id)
        assertEquals("WAT", directory.nearest(51.50, -0.12)?.id)
        assertEquals("BSK", directory.nearest(51.0, -1.5)?.id)
    }

    @Test fun nearest_ofAnEmptyDirectoryIsNull() = assertNull(StationDirectory.parse("[]").nearest(51.0, 0.0))

    @Test fun nearest_neverReturnsAStationWithoutCoordinates() {
        val directory = StationDirectory.parse("""[{"crs":"AAA","name":"Nowhere","lat":0,"lon":0},{"crs":"WOK","name":"Woking","lat":51.3189,"lon":-0.5569}]""")
        assertEquals("WOK", directory.nearest(0.1, 0.1)?.id)
    }
}
