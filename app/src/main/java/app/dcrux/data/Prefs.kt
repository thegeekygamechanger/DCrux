package app.dcrux.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

data class Settings(
    val themeMode: String = "system",          // system | light | dark
    val dynamicColor: Boolean = false,
    val exams: Set<String> = setOf("UPSC CSE"),
    val language: String = "English",
    val preferDetailed: Boolean = false,
    val autoModel: Boolean = true,
    val modelId: String = "",
    val models: List<ModelInfo> = emptyList(),
    val dailySync: Boolean = true,
    val wifiOnly: Boolean = false,
    val aiCurate: Boolean = true,
    val pageFlip: Boolean = true,
    val showShorts: Boolean = false,
    val pageSize: Int = 5,
    val newsSource: String = "hindu",
    val enabledSources: Set<String> = setOf("hindu", "ie", "pib"),
    val customSources: List<CustomSource> = emptyList(),
    val lockEnabled: Boolean = false,
    val biometric: Boolean = true,
    val lastSync: Long = 0,
    val seeded: Boolean = false,
    val readerScale: Float = 1f,
    val blockAds: Boolean = true,
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val tourDone: Boolean = false,
    /** The city the home header shows weather for; null until the user picks one or allows location. */
    val place: Place? = null,
    val weather: Weather? = null,
    /** When this device last exchanged data with the person's other devices; 0 when never. */
    val cloudLastSync: Long = 0,
)

class Prefs(private val store: DataStore<Preferences>) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic")
        val exams = stringSetPreferencesKey("exams")
        val language = stringPreferencesKey("language")
        val detailed = booleanPreferencesKey("detailed")
        val autoModel = booleanPreferencesKey("auto_model")
        val modelId = stringPreferencesKey("model_id")
        val models = stringPreferencesKey("models")
        val dailySync = booleanPreferencesKey("daily_sync")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val aiCurate = booleanPreferencesKey("ai_curate")
        val pageFlip = booleanPreferencesKey("page_flip")
        val shorts = booleanPreferencesKey("shorts")
        val pageSize = intPreferencesKey("page_size")
        val newsSource = stringPreferencesKey("news_source")
        val sources = stringSetPreferencesKey("sources")
        val custom = stringPreferencesKey("custom_sources")
        val lock = booleanPreferencesKey("lock")
        val biometric = booleanPreferencesKey("biometric")
        val lastSync = longPreferencesKey("last_sync")
        val seeded = booleanPreferencesKey("seeded")
        val readerScale = intPreferencesKey("reader_scale")
        val blockAds = booleanPreferencesKey("block_ads")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val tourDone = booleanPreferencesKey("tour_done")
        val place = stringPreferencesKey("place")
        val weather = stringPreferencesKey("weather")
        val cloudLastSync = longPreferencesKey("cloud_last_sync")
    }

    val flow: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            themeMode = p[K.theme] ?: d.themeMode,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            exams = p[K.exams]?.takeIf { it.isNotEmpty() } ?: d.exams,
            language = p[K.language] ?: d.language,
            preferDetailed = p[K.detailed] ?: d.preferDetailed,
            autoModel = p[K.autoModel] ?: d.autoModel,
            modelId = p[K.modelId] ?: d.modelId,
            models = p[K.models]?.let { runCatching { AppJson.decodeFromString<List<ModelInfo>>(it) }.getOrNull() } ?: d.models,
            dailySync = p[K.dailySync] ?: d.dailySync,
            wifiOnly = p[K.wifiOnly] ?: d.wifiOnly,
            aiCurate = p[K.aiCurate] ?: d.aiCurate,
            pageFlip = p[K.pageFlip] ?: d.pageFlip,
            showShorts = p[K.shorts] ?: d.showShorts,
            pageSize = p[K.pageSize] ?: d.pageSize,
            newsSource = p[K.newsSource] ?: d.newsSource,
            enabledSources = p[K.sources] ?: d.enabledSources,
            customSources = p[K.custom]?.let { runCatching { AppJson.decodeFromString<List<CustomSource>>(it) }.getOrNull() } ?: d.customSources,
            lockEnabled = p[K.lock] ?: d.lockEnabled,
            biometric = p[K.biometric] ?: d.biometric,
            lastSync = p[K.lastSync] ?: d.lastSync,
            seeded = p[K.seeded] ?: d.seeded,
            readerScale = (p[K.readerScale] ?: 100) / 100f,
            blockAds = p[K.blockAds] ?: d.blockAds,
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
            lastUpdateCheck = p[K.lastUpdateCheck] ?: d.lastUpdateCheck,
            tourDone = p[K.tourDone] ?: d.tourDone,
            place = p[K.place]?.let { runCatching { AppJson.decodeFromString<Place>(it) }.getOrNull() },
            weather = p[K.weather]?.let { runCatching { AppJson.decodeFromString<Weather>(it) }.getOrNull() },
            cloudLastSync = p[K.cloudLastSync] ?: d.cloudLastSync,
        )
    }

    suspend fun get(): Settings = flow.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun setTheme(v: String) = edit { it[K.theme] = v }
    suspend fun setDynamic(v: Boolean) = edit { it[K.dynamic] = v }
    suspend fun setExams(v: Set<String>) = edit { it[K.exams] = v }
    suspend fun setLanguage(v: String) = edit { it[K.language] = v }
    suspend fun setPreferDetailed(v: Boolean) = edit { it[K.detailed] = v }
    suspend fun setAutoModel(v: Boolean) = edit { it[K.autoModel] = v }
    suspend fun setModelId(v: String) = edit { it[K.modelId] = v }
    suspend fun setModels(v: List<ModelInfo>) = edit { it[K.models] = AppJson.encodeToString(v) }
    suspend fun setDailySync(v: Boolean) = edit { it[K.dailySync] = v }
    suspend fun setWifiOnly(v: Boolean) = edit { it[K.wifiOnly] = v }
    suspend fun setAiCurate(v: Boolean) = edit { it[K.aiCurate] = v }
    suspend fun setPageFlip(v: Boolean) = edit { it[K.pageFlip] = v }
    suspend fun setShowShorts(v: Boolean) = edit { it[K.shorts] = v }
    suspend fun setPageSize(v: Int) = edit { it[K.pageSize] = v }
    suspend fun setNewsSource(v: String) = edit { it[K.newsSource] = v }
    suspend fun setEnabledSources(v: Set<String>) = edit { it[K.sources] = v }
    suspend fun setCustomSources(v: List<CustomSource>) = edit { it[K.custom] = AppJson.encodeToString(v) }
    suspend fun setLock(v: Boolean) = edit { it[K.lock] = v }
    suspend fun setBiometric(v: Boolean) = edit { it[K.biometric] = v }
    suspend fun setLastSync(v: Long) = edit { it[K.lastSync] = v }
    suspend fun setSeeded(v: Boolean) = edit { it[K.seeded] = v }
    suspend fun setBlockAds(v: Boolean) = edit { it[K.blockAds] = v }
    suspend fun setAutoUpdate(v: Boolean) = edit { it[K.autoUpdate] = v }
    suspend fun setLastUpdateCheck(v: Long) = edit { it[K.lastUpdateCheck] = v }
    suspend fun setCloudLastSync(v: Long) = edit { it[K.cloudLastSync] = v }
    suspend fun setTourDone(v: Boolean) = edit { it[K.tourDone] = v }
    suspend fun setPlace(v: Place?) = edit { if (v == null) { it.remove(K.place); it.remove(K.weather) } else { it[K.place] = AppJson.encodeToString(v); it.remove(K.weather) } }
    suspend fun setWeather(v: Weather) = edit { it[K.weather] = AppJson.encodeToString(v) }
    suspend fun setReaderScale(v: Float) = edit { it[K.readerScale] = (v * 100).toInt() }

    suspend fun wipe() {
        store.edit { it.clear() }
    }
}

/** Exams a user can pick; relevance and tests are computed for the selected ones. */
val ExamCatalog: List<Pair<String, List<String>>> = listOf(
    "Union" to listOf("UPSC CSE", "UPSC CAPF", "UPSC CDS", "UPSC EPFO", "SSC CGL", "RBI Grade B", "IBPS / SBI PO"),
    "State PSC" to listOf(
        "BPSC (Bihar)", "UPPSC (Uttar Pradesh)", "MPPSC (Madhya Pradesh)", "RPSC RAS (Rajasthan)",
        "MPSC (Maharashtra)", "WBPSC WBCS (West Bengal)", "JPSC (Jharkhand)", "OPSC (Odisha)",
        "CGPSC (Chhattisgarh)", "HPSC (Haryana)", "PPSC (Punjab)", "UKPSC (Uttarakhand)",
        "HPPSC (Himachal Pradesh)", "GPSC (Gujarat)", "KPSC KAS (Karnataka)", "Kerala PSC",
        "TNPSC (Tamil Nadu)", "APPSC (Andhra Pradesh)", "TGPSC (Telangana)", "APSC (Assam)", "JKPSC (J&K)",
    ),
)
