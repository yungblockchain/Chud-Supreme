package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/* -------------------------------------------------------------------------------------------------
 * Weather and clock: a small strip on Home with the time, the temperature and the sky. Open-Meteo
 * gives the forecast (no key); the town comes from the network's address once, or is typed in
 * Settings. Nothing identifies the person: one coarse location lookup, then plain forecasts.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class WeatherNow(
    val city: String,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val high: Double?,
    val low: Double?,
    val fetchedAt: Long,
)

@Singleton
class WeatherStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var fahrenheit: Boolean
        get() = prefs.getBoolean(KEY_FAHRENHEIT, false)
        set(value) = prefs.edit().putBoolean(KEY_FAHRENHEIT, value).apply()

    /** A town typed in Settings; blank means "work it out from the network". */
    var town: String
        get() = prefs.getString(KEY_TOWN, null).orEmpty()
        set(value) = prefs.edit().putString(KEY_TOWN, value.trim().take(60)).remove(KEY_LAT).remove(KEY_LON).apply()

    var place: Triple<Double, Double, String>?
        get() {
            val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
            return Triple(lat, lon, prefs.getString(KEY_CITY, null).orEmpty())
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAT).remove(KEY_LON).remove(KEY_CITY)
                else putString(KEY_LAT, value.first.toString()).putString(KEY_LON, value.second.toString()).putString(KEY_CITY, value.third)
            }.apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_FAHRENHEIT = "fahrenheit"
        const val KEY_TOWN = "town"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_CITY = "city"
    }
}

object WeatherClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where the network says this box is (town-level), via a keyless lookup. */
    suspend fun locateByNetwork(): Triple<Double, Double, String>? {
        val root = fetch("http://ip-api.com/json/?fields=status,lat,lon,city") ?: return null
        if (root.text("status") != "success") return null
        val lat = root.double("lat") ?: return null
        val lon = root.double("lon") ?: return null
        return Triple(lat, lon, root.text("city").orEmpty())
    }

    /** The first match for a typed town. */
    suspend fun locateByName(name: String): Triple<Double, Double, String>? {
        val root = fetch("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=1&language=${Locale.getDefault().language}")
            ?: return null
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = first.double("latitude") ?: return null
        val lon = first.double("longitude") ?: return null
        return Triple(lat, lon, first.text("name") ?: name)
    }

    suspend fun forecast(lat: Double, lon: Double, city: String): WeatherNow? {
        val root = fetch(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
        ) ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val daily = root["daily"] as? JsonObject
        return WeatherNow(
            city = city,
            temperature = current.double("temperature_2m") ?: return null,
            code = current.int("weather_code") ?: 0,
            isDay = (current.int("is_day") ?: 1) == 1,
            high = ((daily?.get("temperature_2m_max") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull,
            low = ((daily?.get("temperature_2m_min") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** A sky for a WMO weather code. */
    fun symbol(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private suspend fun fetch(url: String): JsonObject? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}

@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val store: WeatherStore,
) : ViewModel() {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now.asStateFlow()
    private val _enabled = MutableStateFlow(store.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _fahrenheit = MutableStateFlow(store.fahrenheit)
    val fahrenheit: StateFlow<Boolean> = _fahrenheit.asStateFlow()
    private val _town = MutableStateFlow(store.town)
    val town: StateFlow<String> = _town.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                if (_enabled.value) refresh()
                delay(REFRESH_MS)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        store.enabled = value
        _enabled.value = value
        if (value) viewModelScope.launch { refresh() } else _now.value = null
    }

    fun setFahrenheit(value: Boolean) {
        store.fahrenheit = value
        _fahrenheit.value = value
    }

    fun setTown(value: String) {
        store.town = value
        _town.value = store.town
        _now.value = null
        viewModelScope.launch { refresh(force = true) }
    }

    private suspend fun refresh(force: Boolean = false) {
        val current = _now.value
        if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < REFRESH_MS) return
        val place = store.place ?: run {
            val found = if (store.town.isNotBlank()) WeatherClient.locateByName(store.town) else WeatherClient.locateByNetwork()
            found?.also { store.place = it }
        } ?: return
        _now.value = runCatching { WeatherClient.forecast(place.first, place.second, place.third) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: _now.value
    }

    private companion object {
        const val REFRESH_MS = 20 * 60_000L
    }
}

/** The clock and the weather, top right of Home. */
@Composable
fun ClockWeatherStrip(now: WeatherNow?, fahrenheit: Boolean, modifier: Modifier = Modifier) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
        while (true) {
            time = format.format(Date())
            // Wake on the minute, so the clock never lags.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TvColors.Background.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = time,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 26.sp,
        )
        if (now != null) {
            Text(text = WeatherClient.symbol(now.code, now.isDay), fontSize = 22.sp)
            Column {
                Text(
                    text = "${degrees(now.temperature, fahrenheit)}°",
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                val range = listOfNotNull(now.high?.let { degrees(it, fahrenheit) }, now.low?.let { degrees(it, fahrenheit) })
                Text(
                    text = listOfNotNull(now.city.takeIf { it.isNotBlank() }, range.takeIf { it.size == 2 }?.let { "${it[0]}° / ${it[1]}°" })
                        .joinToString("  ·  "),
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun degrees(celsius: Double, fahrenheit: Boolean): Int =
    if (fahrenheit) Math.round(celsius * 9 / 5 + 32).toInt() else Math.round(celsius).toInt()


/** The weather rows in Settings: on/off, units, the town. */
@Composable
fun WeatherRows(viewModel: WeatherViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val fahrenheit by viewModel.fahrenheit.collectAsStateWithLifecycle()
    val town by viewModel.town.collectAsStateWithLifecycle()
    var draft by remember(town) { mutableStateOf(town) }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(
            label = stringResource(R.string.dial_weather_show),
            value = if (enabled) on else off,
            onClick = { viewModel.setEnabled(!enabled) },
        )
        SettingRow(
            label = stringResource(R.string.dial_weather_units),
            value = stringResource(if (fahrenheit) R.string.dial_weather_f else R.string.dial_weather_c),
            onClick = { viewModel.setFahrenheit(!fahrenheit) },
        )
        Box(Modifier.widthIn(max = 820.dp)) {
            DialTextField(
                label = stringResource(R.string.dial_weather_town),
                value = draft,
                onValueChange = { draft = it },
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                readOnly = false,
                placeholder = stringResource(R.string.dial_weather_town_hint),
                onDone = { viewModel.setTown(draft) },
            )
        }
    }
}

/** Today's public holiday for the screensaver. The forecast above is unchanged. */
suspend fun holidayToday(country: String = java.util.Locale.getDefault().country): String? =
    PublicHolidays.today(country)

