package com.trainalarm.app.provider

import android.content.Context
import com.trainalarm.app.model.Station
import org.json.JSONArray

/**
 * Static UK station directory loaded from the bundled `assets/stations.json`
 * (NaPTAN, Open Government Licence v3.0 - see data/STATIONS-SOURCE.md).
 * [Station.id] is the CRS code.
 */
class StationDirectory private constructor(val stations: List<Station>) {
    private val byCrs: Map<String, Station> = stations.associateBy { it.id }

    fun station(crs: String): Station? = byCrs[crs.trim().uppercase()]

    /** Case-insensitive substring search; prefix matches first. Blank query returns nothing. */
    fun search(name: String): List<Station> {
        val query = name.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        val (prefix, rest) = stations
            .filter { it.name.lowercase().contains(query) }
            .partition { it.name.lowercase().startsWith(query) }
        return prefix.sortedBy { it.name } + rest.sortedBy { it.name }
    }

    companion object {
        fun parse(json: String): StationDirectory {
            val array = JSONArray(json)
            val stations = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Station(
                    id = o.getString("crs"),
                    name = o.getString("name"),
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon")
                )
            }
            val duplicate = stations.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
            require(duplicate == null) { "Duplicate CRS $duplicate" }
            return StationDirectory(stations)
        }

        fun load(context: Context): StationDirectory =
            parse(context.assets.open("stations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}
