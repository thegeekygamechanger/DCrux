package app.dcrux.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import app.dcrux.data.AppJson
import app.dcrux.data.Place
import app.dcrux.data.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Something worth knowing before stepping out, worked out from the day's forecast. */
data class Advisory(val title: String, val body: String, val severe: Boolean = false)

/**
 * Weather for the home header. Data comes from Open-Meteo (free, no key, CC BY 4.0).
 * Only a city-level position is ever used: coordinates are rounded to about a kilometre
 * before they are stored or sent anywhere.
 */
class WeatherRepo(private val context: Context) {
    private val headers = mapOf("User-Agent" to Http.APP_UA)

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's approximate position turned into a city. Null when the phone cannot say where it is. */
    suspend fun locate(): Place? {
        if (!hasLocationPermission()) return null
        val loc = position() ?: return null
        val lat = round2(loc.latitude)
        val lon = round2(loc.longitude)
        val named = cityFromGeocoder(lat, lon) ?: cityFromWeb(lat, lon)
        return Place(named?.first ?: "My location", lat, lon, named?.second.orEmpty())
    }

    @SuppressLint("MissingPermission")
    private suspend fun position(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        // A fix from the last day is plenty for "which city".
        if (last != null && System.currentTimeMillis() - last.time < 24 * 3_600_000L) return last
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).firstOrNull { it in providers }
            ?: return last
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
        return fresh ?: last
    }

    @Suppress("DEPRECATION")
    private suspend fun cityFromGeocoder(lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching null
            val city = a.locality ?: a.subAdminArea ?: a.adminArea ?: return@runCatching null
            city to a.adminArea.orEmpty()
        }.getOrNull()
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

    suspend fun fetch(place: Place): Weather = coroutineScope {
        // Air quality is a separate service, asked at the same time; the weather still shows when it is unavailable.
        val airJob = async {
            runCatching {
                AppJson.parseToJsonElement(
                    Http.getText("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${place.lat}&longitude=${place.lon}&current=us_aqi,pm2_5", headers)
                ).jsonObject["current"] as? JsonObject
            }.getOrNull()
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
        Weather(
            at = System.currentTimeMillis(),
            temp = temp,
            feels = cur["apparent_temperature"]?.jsonPrimitive?.doubleOrNull ?: temp,
            code = cur["weather_code"]?.jsonPrimitive?.intOrNull ?: 0,
            max = day("temperature_2m_max") ?: temp,
            min = day("temperature_2m_min") ?: temp,
            rain = day("precipitation_sum") ?: 0.0,
            uv = day("uv_index_max") ?: 0.0,
            dayCode = day("weather_code")?.toInt() ?: 0,
            aqi = air?.get("us_aqi")?.jsonPrimitive?.intOrNull ?: -1,
            pm25 = air?.get("pm2_5")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            isDay = cur["is_day"]?.jsonPrimitive?.intOrNull ?: -1,
        )
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

        fun aqiWord(aqi: Int): String = when {
            aqi < 0 -> ""
            aqi <= 50 -> "Good"
            aqi <= 100 -> "Moderate"
            aqi <= 150 -> "Unhealthy for sensitive people"
            aqi <= 200 -> "Unhealthy"
            aqi <= 300 -> "Very unhealthy"
            else -> "Hazardous"
        }

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
                w.aqi > 300 -> add(Advisory("Hazardous air", "Air quality index ${w.aqi}. Stay indoors as far as possible and wear an N95 mask outside.", severe = true))
                w.aqi > 200 -> add(Advisory("Very unhealthy air", "Air quality index ${w.aqi}. Avoid outdoor exercise and wear a mask outside.", severe = true))
                w.aqi > 150 -> add(Advisory("Unhealthy air", "Air quality index ${w.aqi}. Cut down time outdoors, more so with asthma or a cough."))
            }
            if (w.uv >= 11) add(Advisory("Extreme UV", "UV index ${w.uv.roundToInt()}. Cover up and avoid the midday sun."))
            else if (w.uv >= 8 && w.max >= 30) add(Advisory("Strong sun", "UV index ${w.uv.roundToInt()}. Cover your head between 11 am and 3 pm."))
        }
    }
}
