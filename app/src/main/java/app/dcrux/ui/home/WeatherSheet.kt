package app.dcrux.ui.home

import app.dcrux.LocalGraph
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.dcrux.data.Dates
import app.dcrux.data.Place
import app.dcrux.data.Settings
import app.dcrux.net.WeatherRepo
import app.dcrux.ui.components.CruxLoader
import app.dcrux.ui.components.CruxSheet
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Rounded.WbSunny
    2 -> Icons.Rounded.WbCloudy
    3 -> Icons.Rounded.Cloud
    45, 48 -> Icons.Rounded.Dehaze
    71, 73, 75, 77, 85, 86 -> Icons.Rounded.AcUnit
    95, 96, 99 -> Icons.Rounded.Thunderstorm
    else -> Icons.Rounded.Grain
}

/** Weather, the day's advisories and the choice of city, sliding up from the home header. */
@Composable
fun WeatherSheet(settings: Settings, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val place = settings.place
    val w = settings.weather
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var choosing by remember { mutableStateOf(place == null) }

    // Saving the city is all that happens here. The home screen fetches the weather for whatever city
    // is saved, so it arrives even if this sheet is closed straight away.
    fun choose(p: Place) {
        g.scope.launch { g.prefs.setPlace(p) }
        message = null; choosing = false; results = null; query = ""
    }
    // If the weather has not arrived after a while, say so instead of loading for ever.
    var slow by remember(place) { mutableStateOf(false) }
    LaunchedEffect(place, w) { slow = false; if (place != null && w == null) { delay(12_000); slow = true } }

    fun useLocation() {
        scope.launch {
            busy = "Finding your city"; message = null
            val found = runCatching { g.weather.locate() }.getOrNull()
            busy = null
            if (found == null) message = "The phone could not tell where it is. Turn on Location, or type your city below."
            else choose(found)
        }
    }

    val askLocation = g.platform.rememberLocationAsk { granted ->
        if (granted) useLocation() else message = "Location was not allowed. You can type your city instead."
    }

    CruxSheet(if (place != null && !choosing) place.name else "Your city", onDismiss, Icons.Rounded.LocationOn) {
        Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy != null) CruxLoader(busy.orEmpty())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            if (place != null && !choosing && busy == null) {
                if (place.region.isNotBlank()) Text(place.region, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (w == null) {
                    if (slow) Text("The weather has not loaded. Check the internet, then pull down on the home screen to try again.", style = MaterialTheme.typography.bodyMedium)
                    else CruxLoader("Getting the weather for ${place.name}")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(weatherIcon(w.code), null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("${w.temp.roundToInt()}°C", style = MaterialTheme.typography.displaySmall)
                            Text(
                                "${WeatherRepo.describe(w.code)} · feels like ${w.feels.roundToInt()}°",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("High / low", "${w.max.roundToInt()}° / ${w.min.roundToInt()}°", Modifier.weight(1f))
                        Stat("Rain today", if (w.rain < 0.1) "None" else "${"%.1f".format(w.rain)} mm", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("UV index", "${w.uv.roundToInt()}", Modifier.weight(1f))
                        Stat("Air quality", if (w.aqi < 0) "Not available" else "${w.aqi} · ${WeatherRepo.aqiWord(w.aqi)}", Modifier.weight(1f))
                    }

                    if (w.aqi >= 0) Text(
                        if (w.aqiSource == "cpcb")
                            "India's AQI from ${w.aqiStations} official ${if (w.aqiStations == 1) "monitor" else "monitors"} (CPCB)" +
                                (if (w.aqiUpdated.isNotBlank()) ", reported ${w.aqiUpdated.substringAfter(' ').take(5)}" else "") + ". " +
                                (if (w.aqiWorst >= 0 && w.aqiStations > 1) "Worst: ${w.aqiWorstStation}, ${w.aqiWorst}. " else "") +
                                (if (w.aqiPeak >= 0) "Highest reading today: ${w.aqiPeak}." else "")
                        else "India's AQI scale, estimated from modelled particle levels for this area (PM2.5 ${w.pm25.roundToInt()}, PM10 ${w.pm10.roundToInt()} µg/m³). " +
                            "Official monitors can read higher or lower; check CPCB's Sameer app for the station nearest you.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val alerts = remember(w) { WeatherRepo.advisories(w) }
                    Text(if (alerts.isEmpty()) "No alerts today" else "Today's alerts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    val x = LocalExtra.current
                    alerts.forEach { a ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(x.mid).padding(14.dp)) {
                            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(20.dp), tint = x.onMid)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(a.title, style = MaterialTheme.typography.titleSmall, color = x.onMid)
                                Text(a.body, style = MaterialTheme.typography.bodySmall, color = x.onMid)
                            }
                        }
                    }
                    Text(
                        "Updated ${Dates.ago(w.at)}. Weather data by Open-Meteo.com. Alerts are worked out by DCrux from the forecast; " +
                            "they are not official warnings. For those, follow the India Meteorological Department and your district administration.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { choosing = true }) { Text("Change city") }
                    TextButton(onClick = { scope.launch { g.prefs.setPlace(null) }; onDismiss() }) { Text("Remove") }
                }
            }

            if (choosing && busy == null) {
                Text(
                    "DCrux shows the temperature and any weather alerts for your city. Only the city's position, rounded to about a kilometre, is used; it stays on this phone and is sent only to the weather service.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { if (g.weather.hasLocationPermission()) useLocation() else askLocation() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use my location")
                }
                fun search() {
                    keyboard?.hide()
                    scope.launch {
                        busy = "Looking up $query"; message = null
                        results = runCatching { g.weather.search(query) }.getOrElse { message = "Could not search. Check the internet and try again."; null }
                        busy = null
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), shape = CircleShape, singleLine = true,
                    placeholder = { Text("Or type your city") },
                    trailingIcon = { IconButton(onClick = { search() }, enabled = query.trim().length >= 2) { Icon(Icons.Rounded.Search, "Find city") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.trim().length >= 2) search() }),
                )
                results?.let { list ->
                    if (list.isEmpty()) Text("No city found by that name.", style = MaterialTheme.typography.bodyMedium)
                    list.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { choose(p) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                if (p.region.isNotBlank()) Text(p.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (place != null) TextButton(onClick = { choosing = false }) { Text("Keep ${place.name}") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
