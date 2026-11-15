package app.dcrux.net

import app.dcrux.data.AppJson
import app.dcrux.data.Place
import app.dcrux.data.Weather
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Something worth knowing before stepping out, worked out from the day's forecast. */
data class Advisory(val title: String, val body: String, val severe: Boolean = false)

/**
 * Weather for the home header. Data comes from Open-Meteo (free, no key, CC BY 4.0).
 * Only a city-level position is ever used: coordinates are rounded to about a kilometre
 * before they are stored or sent anywhere.
 */
class WeatherRepo(private val platform: app.dcrux.Platform, private val service: app.dcrux.ai.ClaudeClient? = null) {
    private val headers = mapOf("User-Agent" to Http.APP_UA)

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    fun hasLocationPermission(): Boolean = platform.hasLocationPermission()

    /** The device's approximate position turned into a city. Null when the device cannot say where it is. */
    suspend fun locate(): Place? {
        if (!hasLocationPermission()) return null
        val (rawLat, rawLon) = platform.position() ?: return null
        val lat = round2(rawLat)
        val lon = round2(rawLon)
        val named = platform.cityName(lat, lon) ?: cityFromWeb(lat, lon)
        return Place(named?.first ?: "My location", lat, lon, named?.second.orEmpty())
    }

    /** For phones without a built-in geocoder. */
    private suspend fun cityFromWeb(lat: Double, lon: Double): Pair<String, String>? = runCatching {
        val o = AppJson.parseToJsonElement(
            Http.getText("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en", headers)
        ).jsonObject
        val city = listOf("city", "locality").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content?.takeIf { v -> v.isNotBlank() } } ?: return@runCatching null
        city to o["principalSubdivision"]?.jsonPrimitive?.content.orEmpty()
    }.getOrNull()

    /** Cities matching what the user typed, for those who would rather not share a location. */
    suspend fun search(name: String): List<Place> {
        val q = name.trim()
        if (q.length < 2) return emptyList()
        val root = AppJson.parseToJsonElement(
            Http.getText("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=8&language=en&format=json", headers)
        ).jsonObject
        return root["results"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val region = listOfNotNull(o["admin1"]?.jsonPrimitive?.content, o["country"]?.jsonPrimitive?.content).joinToString(", ")
            Place(o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null, round2(lat), round2(lon), region)
        }.sortedByDescending { it.region.endsWith("India") }
    }

    /** [previous] is the last reading for this place, whose official air-quality figure is reused while it is fresh. */
    suspend fun fetch(place: Place, previous: Weather? = null): Weather = coroutineScope {
        // Air quality is a separate service, asked at the same time; the weather still shows when it is unavailable.
        val airJob = async {
            runCatching {
                AppJson.parseToJsonElement(
                    Http.getText("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}&current=pm2_5,pm10", headers)
                ).jsonObject["current"] as? JsonObject
            }.getOrNull()
        }
        // India's official monitors (CPCB), through the DCrux service, for cities that have them.
        val officialJob = async {
            when {
                !place.region.contains("India") -> null
                // The monitors report once an hour: a reading from the last 50 minutes is still the current one.
                previous?.aqiSource == "cpcb" && System.currentTimeMillis() - previous.aqiAt < 50 * 60_000L -> previous
                else -> runCatching { official(place) }.getOrNull()
            }
        }
        val w = AppJson.parseToJsonElement(
            Http.getText(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,uv_index_max,weather_code&timezone=auto&forecast_days=1",
                headers,
            )
        ).jsonObject
        val cur = w["current"] as? JsonObject ?: throw IllegalStateException("No weather for this place")
        val daily = w["daily"] as? JsonObject
        fun day(key: String) = daily?.get(key)?.jsonArray?.firstOrNull()?.jsonPrimitive?.doubleOrNull
        val temp = cur["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: throw IllegalStateException("No weather for this place")
        val air = airJob.await()
        val pm25 = air?.get("pm2_5")?.jsonPrimitive?.doubleOrNull ?: -1.0
        val pm10 = air?.get("pm10")?.jsonPrimitive?.doubleOrNull ?: -1.0
        // India's own index, from the particle levels right now. An estimate: the levels come from a forecast model, not a monitor.
        val estimate = indiaAqi(pm25, pm10)
        val official = officialJob.await()
        val modelled = Weather(
            at = System.currentTimeMillis(),
            temp = temp,
            feels = cur["apparent_temperature"]?.jsonPrimitive?.doubleOrNull ?: temp,
            code = cur["weather_code"]?.jsonPrimitive?.intOrNull ?: 0,
            max = day("temperature_2m_max") ?: temp,
            min = day("temperature_2m_min") ?: temp,
            rain = day("precipitation_sum") ?: 0.0,
            uv = day("uv_index_max") ?: 0.0,
            dayCode = day("weather_code")?.toInt() ?: 0,
            aqi = estimate, aqiSource = if (estimate >= 0) "estimate" else "",
            pm25 = pm25, pm10 = pm10,
            isDay = cur["is_day"]?.jsonPrimitive?.intOrNull ?: -1,
        )
        // Official monitors, when the city has them, replace the estimate.
        val o = official
        if (o == null) modelled else modelled.copy(
            aqi = o.aqi, aqiSource = "cpcb", aqiStations = o.aqiStations, aqiWorstStation = o.aqiWorstStation,
            aqiWorst = o.aqiWorst, aqiPeak = o.aqiPeak, aqiUpdated = o.aqiUpdated, aqiAt = o.aqiAt,
        )
    }

    /**
     * The city's AQI from CPCB's monitors. The DCrux service either answers itself or, when it is hosted
     * outside India (where data.gov.in does not answer), hands back the request to make, and this device
     * fetches the readings directly. Null when the city has no monitor or nothing could be reached.
     */
    private suspend fun official(place: Place): Weather? {
        val reply = service?.serviceGet("/v1/aqi?city=${URLEncoder.encode(place.name, "UTF-8")}&lat=${place.lat}&lon=${place.lon}") ?: return null
        val o = AppJson.parseToJsonElement(reply).jsonObject
        val blank = Weather(at = 0, temp = 0.0, feels = 0.0, code = 0, max = 0.0, min = 0.0, rain = 0.0, uv = 0.0, dayCode = 0)
        o["aqi"]?.jsonPrimitive?.intOrNull?.let { aqi ->
            return blank.copy(
                aqi = aqi, aqiStations = o["stations"]?.jsonPrimitive?.intOrNull ?: 0,
                aqiWorstStation = (o["worst"] as? JsonObject)?.get("station")?.jsonPrimitive?.content.orEmpty(),
                aqiWorst = (o["worst"] as? JsonObject)?.get("aqi")?.jsonPrimitive?.intOrNull ?: -1,
                aqiPeak = o["peak"]?.jsonPrimitive?.intOrNull ?: -1, aqiUpdated = o["updated"]?.jsonPrimitive?.content.orEmpty(),
                aqiAt = System.currentTimeMillis(),
            )
        }
        val url = o["fetch"]?.jsonPrimitive?.content ?: return null
        val records = AppJson.parseToJsonElement(Http.getText(url, headers)).jsonObject["records"]?.jsonArray ?: return null
        // Each station's index is its worst pollutant's sub-index; the city's is the average of its stations (CPCB's method).
        class Station(val name: String, val lat: Double, val lon: Double) { var aqi = -1.0; var peak = -1.0; var hasPm = false; var updated = "" }
        val stations = LinkedHashMap<String, Station>()
        for (el in records) {
            val r = el.jsonObject
            fun text(k: String) = r[k]?.jsonPrimitive?.content.orEmpty()
            val value = text("avg_value").toDoubleOrNull() ?: continue
            val st = stations.getOrPut(text("station").ifBlank { continue }) {
                Station(text("station").replace(Regex("\\s*-\\s*[A-Z]{3,6}$"), "").trim(), text("latitude").toDoubleOrNull() ?: Double.NaN, text("longitude").toDoubleOrNull() ?: Double.NaN)
            }
            if (value > st.aqi) st.aqi = value
            text("max_value").toDoubleOrNull()?.let { if (it > st.peak) st.peak = it }
            if (text("pollutant_id") == "PM2.5" || text("pollutant_id") == "PM10") st.hasPm = true
            st.updated = text("last_update")
        }
        // A city name can be shared by places far apart; only monitors within reach of this city count.
        val near = stations.values.filter { it.hasPm && it.aqi >= 0 && (it.lat.isNaN() || km(place.lat, place.lon, it.lat, it.lon) <= 60.0) }
        if (near.isEmpty()) return null
        val worst = near.maxBy { it.aqi }
        return blank.copy(
            aqi = near.map { it.aqi }.average().roundToInt(), aqiStations = near.size, aqiWorstStation = worst.name, aqiWorst = worst.aqi.roundToInt(),
            aqiPeak = near.maxOf { it.peak }.roundToInt(), aqiUpdated = near.first().updated, aqiAt = System.currentTimeMillis(),
        )
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lat2 - lat1); val y = Math.toRadians(lon2 - lon1)
        val h = Math.sin(x / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(y / 2).let { it * it }
        return 2 * 6371.0 * Math.asin(Math.sqrt(h))
    }

    companion object {
        /** WMO weather codes, in plain words. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 66 -> "Light rain"
            63 -> "Rain"
            65, 67 -> "Heavy rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            80 -> "Showers"
            81, 82 -> "Heavy showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Weather"
        }

/** India's National Air Quality Index bands (CPCB). */
        fun aqiWord(aqi: Int): String = when {
            aqi < 0 -> ""
            aqi <= 50 -> "Good"
            aqi <= 100 -> "Satisfactory"
            aqi <= 200 -> "Moderate"
            aqi <= 300 -> "Poor"
            aqi <= 400 -> "Very poor"
            else -> "Severe"
        }

        private fun subIndex(c: Double, bands: List<Pair<Double, Int>>): Int {
            if (c < 0) return -1
            var loC = 0.0
            var loI = 0
            for ((hiC, hiI) in bands) {
                if (c <= hiC) return (loI + (c - loC) * (hiI - loI) / (hiC - loC)).roundToInt()
                loC = hiC; loI = hiI
            }
            return 500
        }

        /**
         * India's index from particle concentrations (µg/m³), using CPCB's breakpoints: the index is the
         * worse of the PM2.5 and PM10 sub-indices, which decide it on all but rare days.
         */
        fun indiaAqi(pm25: Double, pm10: Double): Int = maxOf(
            subIndex(pm25, listOf(30.0 to 50, 60.0 to 100, 90.0 to 200, 120.0 to 300, 250.0 to 400, 380.0 to 500)),
            subIndex(pm10, listOf(50.0 to 50, 100.0 to 100, 250.0 to 200, 350.0 to 300, 430.0 to 400, 510.0 to 500)),
        )

        /**
         * Advisories derived from the forecast numbers with fixed thresholds (rain bands follow the
         * India Meteorological Department's definitions). These are not official warnings.
         */
        fun advisories(w: Weather): List<Advisory> = buildList {
            val storm = w.code in listOf(95, 96, 99) || w.dayCode in listOf(95, 96, 99)
            if (storm) add(Advisory("Thunderstorm likely", "Lightning is possible today. Stay indoors during the storm and away from open fields, trees and water.", severe = true))
            when {
                w.rain >= 115.6 -> add(Advisory("Very heavy rain", "About ${w.rain.roundToInt()} mm is forecast today. Expect waterlogging; avoid travel if you can and keep your phone charged.", severe = true))
                w.rain >= 64.5 -> add(Advisory("Heavy rain", "About ${w.rain.roundToInt()} mm is forecast today. Roads may flood; leave early for an exam centre or class.", severe = true))
                w.rain >= 15.6 -> add(Advisory("Rain today", "About ${w.rain.roundToInt()} mm is forecast. Carry an umbrella and keep books and admit cards in a cover."))
            }
            when {
                w.max >= 45 || w.feels >= 47 -> add(Advisory("Severe heat", "Up to ${w.max.roundToInt()}° today. Stay indoors from noon to 4 pm, drink water often and study in the cooler hours.", severe = true))
                w.max >= 40 || w.feels >= 42 -> add(Advisory("Heat", "Up to ${w.max.roundToInt()}° today. Drink water often and avoid the afternoon sun."))
            }
            when {
                w.min <= 4 -> add(Advisory("Severe cold", "Down to ${w.min.roundToInt()}° today. Dress in layers, especially for early-morning travel.", severe = true))
                w.min <= 8 -> add(Advisory("Cold morning", "Down to ${w.min.roundToInt()}° today. Dress warmly for early classes."))
            }
            if (w.code in listOf(45, 48) || w.dayCode in listOf(45, 48)) add(Advisory("Fog", "Visibility may be poor. Trains and buses can run late; leave with time in hand."))
            when {
                w.aqi > 400 -> add(Advisory("Severe air pollution", "Air quality index ${w.aqi}. Stay indoors as far as possible, keep windows shut and wear an N95 mask outside.", severe = true))
                w.aqi > 300 -> add(Advisory("Very poor air", "Air quality index ${w.aqi}. Avoid outdoor exercise and wear an N95 mask outside.", severe = true))
                w.aqi > 200 -> add(Advisory("Poor air", "Air quality index ${w.aqi}. Cut down time outdoors, more so with asthma or a cough."))
            }
            if (w.uv >= 11) add(Advisory("Extreme UV", "UV index ${w.uv.roundToInt()}. Cover up and avoid the midday sun."))
            else if (w.uv >= 8 && w.max >= 30) add(Advisory("Strong sun", "UV index ${w.uv.roundToInt()}. Cover your head between 11 am and 3 pm."))
        }
    }
}
