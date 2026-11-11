package com.weather.aura

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

// --- مدل‌های داده هواشناسی و ژئوکوودینگ ---
data class WeatherResponse(
    @SerializedName("current") val current: CurrentWeather? = null,
    @SerializedName("hourly") val hourly: HourlyWeather? = null,
    @SerializedName("daily") val daily: DailyWeather? = null
)

data class CurrentWeather(
    @SerializedName("temperature_2m") val temperature2m: Float? = null,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: Int? = null,
    @SerializedName("apparent_temperature") val apparentTemperature: Float? = null,
    @SerializedName("weather_code") val weatherCode: Int? = null,
    @SerializedName("wind_speed_10m") val windSpeed10m: Float? = null,
    @SerializedName("surface_pressure") val surfacePressure: Float? = null,
    @SerializedName("is_day") val isDay: Int? = 1 // 1: روز، 0: شب
)

data class HourlyWeather(
    @SerializedName("time") val time: List<String>? = null,
    @SerializedName("temperature_2m") val temperature2m: List<Float>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int>? = null,
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int>? = null
)

data class DailyWeather(
    @SerializedName("time") val time: List<String>? = null,
    @SerializedName("temperature_2m_max") val temperature2mMax: List<Float>? = null,
    @SerializedName("temperature_2m_min") val temperature2mMin: List<Float>? = null,
    @SerializedName("precipitation_probability_max") val precipitationProbabilityMax: List<Int>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int>? = null
)

data class GeocodingResponse(
    @SerializedName("results") val results: List<GeoCity>? = null
)

data class GeoCity(
    @SerializedName("name") val name: String = "",
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("country") val country: String? = "",
    @SerializedName("admin1") val admin1: String? = ""
)

// مدل بررسی آپدیت درون‌برنامه‌ای
data class AppUpdateInfo(
    @SerializedName("versionCode") val versionCode: Int = 1,
    @SerializedName("versionName") val versionName: String = "1.0",
    @SerializedName("changelog") val changelog: String = "",
    @SerializedName("apkUrl") val apkUrl: String = ""
)

// --- اینترفیس API هواشناسی و آپدیت ---
interface WeatherApi {
    @GET("v1/forecast?current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,surface_pressure,is_day&hourly=temperature_2m,weather_code,precipitation_probability&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=10&timezone=auto")
    suspend fun getForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double
    ): WeatherResponse

    @GET("https://geocoding-api.open-meteo.com/v1/search")
    suspend fun searchCity(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") lang: String = "fa",
        @Query("format") format: String = "json"
    ): GeocodingResponse

    @GET
    suspend fun checkUpdate(@Url jsonUrl: String): AppUpdateInfo
}

object RetrofitClient {
    val api: WeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}

// --- مدیریت حافظه (شهر پیش‌فرض و تاریخچه جستجو) ---
class StorageManager(context: Context) {
    private val prefs = context.getSharedPreferences("emin_weather_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveDefaultCity(city: GeoCity) {
        prefs.edit().putString("default_city", gson.toJson(city)).apply()
    }

    fun getDefaultCity(): GeoCity {
        val json = prefs.getString("default_city", null)
        return if (json != null) {
            try {
                gson.fromJson(json, GeoCity::class.java)
            } catch (e: Exception) {
                fallbackCity()
            }
        } else {
            fallbackCity()
        }
    }

    private fun fallbackCity() = GeoCity(
        name = "تهران",
        latitude = 35.6892,
        longitude = 51.3890,
        country = "ایران",
        admin1 = "Tehran"
    )

    fun getSearchHistory(): List<GeoCity> {
        val json = prefs.getString("search_history", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GeoCity>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addToSearchHistory(city: GeoCity) {
        val history = getSearchHistory().toMutableList()
        history.removeAll { it.name == city.name && it.latitude == city.latitude }
        history.add(0, city)
        if (history.size > 8) history.removeAt(history.lastIndex)
        prefs.edit().putString("search_history", gson.toJson(history)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("search_history").apply()
    }
}

// --- الگوریتم تبدیل تقویم میلادی به شمسی ---
object DateHelper {
    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) (gy + 1) else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += ((days - 1) / 365)
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }

    private val persianMonths = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun formatDate(isoDate: String, isJalali: Boolean, isFa: Boolean): String {
        return try {
            val dateOnly = isoDate.split("T")[0]
            val parts = dateOnly.split("-")
            val y = parts[0].toInt()
            val m = parts[1].toInt()
            val d = parts[2].toInt()

            if (isJalali) {
                val (_, jm, jd) = gregorianToJalali(y, m, d)
                if (isFa) "$jd ${persianMonths[jm - 1]}" else "$jd/$jm"
            } else {
                "$y/$m/$d"
            }
        } catch (e: Exception) {
            isoDate
        }
    }
}

// --- استایل هوشمند روز و شب و آیکون‌ها ---
object WeatherThemeHelper {
    fun getWeatherDetails(code: Int?, isDay: Boolean, isFa: Boolean): Pair<String, ImageVector> {
        val nightClearIcon = Icons.Default.Bedtime // جایگزین آیکون بدون خطای شب
        return when (code ?: 0) {
            0 -> {
                if (isDay) {
                    (if (isFa) "آسمان صاف و آفتابی" else "Clear Sky") to Icons.Default.WbSunny
                } else {
                    (if (isFa) "آسمان صاف و مهتابی" else "Clear Night") to nightClearIcon
                }
            }
            1, 2 -> {
                if (isDay) {
                    (if (isFa) "کمی تا قسمتی ابری" else "Partly Cloudy") to Icons.Default.CloudQueue
                } else {
                    (if (isFa) "شب نیمه ابری" else "Partly Cloudy Night") to Icons.Default.NightsStay
                }
            }
            3 -> (if (isFa) "تمام ابری و مه‌گرفته" else "Overcast") to Icons.Default.Cloud
            45, 48 -> (if (isFa) "مه‌آلود و کاهش دید" else "Foggy") to Icons.Default.Dehaze
            51, 53, 55 -> (if (isFa) "بارش نم‌نم باران" else "Drizzle") to Icons.Default.Grain
            61, 63, 65 -> (if (isFa) "باران ملایم تا شدید" else "Rain") to Icons.Default.WaterDrop
            71, 73, 75, 77 -> (if (isFa) "بارش برف و سرما" else "Snow") to Icons.Default.AcUnit
            80, 81, 82 -> (if (isFa) "رگبار تند باران" else "Heavy Showers") to Icons.Default.Thunderstorm
            95, 96, 99 -> (if (isFa) "طوفان و رعد و برق" else "Thunderstorm") to Icons.Default.FlashOn
            else -> (if (isFa) "آسمان آرام" else "Fair") to if (isDay) Icons.Default.WbSunny else nightClearIcon
        }
    }

    fun getBackgroundGradient(code: Int?, isDay: Boolean): List<Color> {
        return if (isDay) {
            when (code ?: 0) {
                0 -> listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF0369A1))
                1, 2, 3 -> listOf(Color(0xFF475569), Color(0xFF64748B), Color(0xFF334155))
                51, 53, 55, 61, 63, 65, 80, 81, 82 -> listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF0284C7))
                71, 73, 75, 77 -> listOf(Color(0xFF0284C7), Color(0xFF7DD3FC), Color(0xFFE0F2FE))
                95, 96, 99 -> listOf(Color(0xFF4C1D95), Color(0xFF2E1065), Color(0xFF0F172A))
                else -> listOf(Color(0xFF0369A1), Color(0xFF0F172A))
            }
        } else {
            when (code ?: 0) {
                0 -> listOf(Color(0xFF050B14), Color(0xFF0F172A), Color(0xFF1E1B4B))
                1, 2, 3 -> listOf(Color(0xFF0B0F19), Color(0xFF1E293B), Color(0xFF0F172A))
                51, 53, 55, 61, 63, 65, 80, 81, 82 -> listOf(Color(0xFF020617), Color(0xFF0F172A), Color(0xFF172554))
                71, 73, 75, 77 -> listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF38BDF8))
                95, 96, 99 -> listOf(Color(0xFF2E1065), Color(0xFF0F172A), Color(0xFF020617))
                else -> listOf(Color(0xFF020617), Color(0xFF0F172A))
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF38BDF8))) {
                EminWeatherApp()
            }
        }
    }
}

@Composable
fun EminWeatherApp() {
    val context = LocalContext.current
    val storageManager = remember { StorageManager(context) }
    var currentCity by remember { mutableStateOf(storageManager.getDefaultCity()) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var isPersian by remember { mutableStateOf(true) }
    var isJalali by remember { mutableStateOf(true) }
    var isFahrenheit by remember { mutableStateOf(false) }

    var weatherData by remember { mutableStateOf<WeatherResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun checkAppUpdate(showToastIfLatest: Boolean = false) {
        coroutineScope.launch {
            try {
                // آدرس فایل JSON آپدیت برنامه روی مخزن گیت‌هاب شما
                val updateUrl = "https://raw.githubusercontent.com/EminTm/AuraWeather/main/update.json"
                val info = withContext(Dispatchers.IO) {
                    RetrofitClient.api.checkUpdate(updateUrl)
                }
                val currentVersionCode = 1
                if (info.versionCode > currentVersionCode) {
                    availableUpdate = info
                    showUpdateDialog = true
                } else if (showToastIfLatest) {
                    Toast.makeText(context, if (isPersian) "برنامه به آخرین نسخه بروز است" else "App is up to date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (showToastIfLatest) {
                    Toast.makeText(context, if (isPersian) "سرور بروزرسانی در دسترس نیست" else "Update server unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(currentCity, refreshKey) {
        isLoading = true
        errorMessage = null
        try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.api.getForecast(currentCity.latitude, currentCity.longitude)
            }
            weatherData = response
        } catch (e: Exception) {
            errorMessage = if (isPersian) "خطا در اتصال به سرور آب‌وهوا" else "Connection error"
        } finally {
            isLoading = false
        }
    }

    val isDay = (weatherData?.current?.isDay ?: 1) == 1
    val currentCode = weatherData?.current?.weatherCode
    val targetColors = WeatherThemeHelper.getBackgroundGradient(currentCode, isDay)

    val color1 by animateColorAsState(targetColors[0], animationSpec = tween(800), label = "")
    val color2 by animateColorAsState(targetColors[1], animationSpec = tween(800), label = "")

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xE60F172A)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(if (isDay) Icons.Default.WbSunny else Icons.Default.Bedtime, null) },
                    label = { Text(if (isPersian) "امروز" else "Today") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.DateRange, null) },
                    label = { Text(if (isPersian) "پیش‌بینی" else "Forecast") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Radar, null) },
                    label = { Text(if (isPersian) "رادار زنده" else "Radar") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(if (isPersian) "تنظیمات" else "Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Brush.verticalGradient(listOf(color1, color2)))
        ) {
            when (selectedTab) {
                0 -> CurrentWeatherScreen(
                    city = currentCity,
                    data = weatherData,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    isPersian = isPersian,
                    isFahrenheit = isFahrenheit,
                    isDay = isDay,
                    onSearchClick = { showSearchDialog = true },
                    onRetry = { refreshKey++ }
                )
                1 -> ForecastScreen(
                    data = weatherData,
                    isPersian = isPersian,
                    isJalali = isJalali,
                    isFahrenheit = isFahrenheit,
                    isDay = isDay
                )
                2 -> LiveRadarScreen(lat = currentCity.latitude, lon = currentCity.longitude, isPersian = isPersian)
                3 -> SettingsScreen(
                    isPersian = isPersian,
                    isJalali = isJalali,
                    isFahrenheit = isFahrenheit,
                    currentCity = currentCity,
                    onSaveAsDefault = {
                        storageManager.saveDefaultCity(currentCity)
                        Toast.makeText(context, if (isPersian) "شهر پیش‌فرض با موفقیت ذخیره شد" else "Default city saved", Toast.LENGTH_SHORT).show()
                    },
                    onCheckUpdate = { checkAppUpdate(showToastIfLatest = true) },
                    onToggleLanguage = { isPersian = !isPersian },
                    onToggleCalendar = { isJalali = !isJalali },
                    onToggleUnit = { isFahrenheit = !isFahrenheit },
                    onRefresh = { refreshKey++ }
                )
            }

            if (showSearchDialog) {
                CitySearchDialog(
                    isPersian = isPersian,
                    storageManager = storageManager,
                    onDismiss = { showSearchDialog = false },
                    onCitySelected = { selected ->
                        currentCity = selected
                        storageManager.addToSearchHistory(selected)
                        showSearchDialog = false
                    }
                )
            }

            if (showUpdateDialog && availableUpdate != null) {
                UpdateDialog(
                    update = availableUpdate!!,
                    isPersian = isPersian,
                    onDismiss = { showUpdateDialog = false },
                    onDownload = {
                        downloadAndInstallApk(context, availableUpdate!!.apkUrl)
                        showUpdateDialog = false
                    }
                )
            }
        }
    }
}

// --- صفحه آب و هوای امروز ---
@Composable
fun CurrentWeatherScreen(
    city: GeoCity,
    data: WeatherResponse?,
    isLoading: Boolean,
    errorMessage: String?,
    isPersian: Boolean,
    isFahrenheit: Boolean,
    isDay: Boolean,
    onSearchClick: () -> Unit,
    onRetry: () -> Unit
) {
    fun formatTemp(c: Float?): String {
        if (c == null) return "--"
        val value = if (isFahrenheit) (c * 1.8f + 32) else c
        val unit = if (isFahrenheit) "°F" else "°C"
        return "${value.toInt()}$unit"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x33FFFFFF))
                .clickable { onSearchClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = Color.Cyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${city.name} (${city.country ?: ""})",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Icon(Icons.Default.Search, null, tint = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            }
            errorMessage != null -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage, color = Color.Red, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onRetry) { Text(if (isPersian) "تلاش مجدد" else "Retry") }
                    }
                }
            }
            data?.current != null -> {
                val current = data.current
                val (conditionText, icon) = WeatherThemeHelper.getWeatherDetails(current.weatherCode, isDay, isPersian)

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDay) Color(0xFFFDE047) else Color(0xFFE2E8F0),
                    modifier = Modifier.size(86.dp)
                )

                Text(
                    text = formatTemp(current.temperature2m),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Text(
                    text = conditionText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Cyan
                )

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x2AFFFFFF)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        InfoItem(Icons.Default.Thermostat, if (isPersian) "حس واقعی" else "Feels", formatTemp(current.apparentTemperature))
                        InfoItem(Icons.Default.WaterDrop, if (isPersian) "رطوبت" else "Humidity", "${current.relativeHumidity2m ?: 0}%")
                        InfoItem(Icons.Default.Air, if (isPersian) "سرعت باد" else "Wind", "${current.windSpeed10m ?: 0} km/h")
                        InfoItem(Icons.Default.Speed, if (isPersian) "فشار هوا" else "Pressure", "${current.surfacePressure?.toInt() ?: 0} hPa")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (isPersian) "پیش‌بینی ساعتی" else "Hourly Forecast",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                data.hourly?.let { hourly ->
                    val times = hourly.time ?: emptyList()
                    val temps = hourly.temperature2m ?: emptyList()
                    val codes = hourly.weatherCode ?: emptyList()
                    val pops = hourly.precipitationProbability ?: emptyList()

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val count = minOf(24, times.size)
                        items(count) { index ->
                            val rawTime = times.getOrNull(index) ?: ""
                            val hourOnly = rawTime.split("T").getOrNull(1)?.take(5) ?: rawTime
                            val t = temps.getOrNull(index)
                            val c = codes.getOrNull(index)
                            val p = pops.getOrNull(index) ?: 0
                            val (_, hourIcon) = WeatherThemeHelper.getWeatherDetails(c, isDay, isPersian)

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(hourOnly, color = Color.White, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Icon(hourIcon, null, tint = Color.Cyan, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(formatTemp(t), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("$p%", color = Color(0xFF38BDF8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.Cyan, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.LightGray, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// --- صفحه پیش‌بینی روزهای آینده ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    data: WeatherResponse?,
    isPersian: Boolean,
    isJalali: Boolean,
    isFahrenheit: Boolean,
    isDay: Boolean
) {
    var selectedDays by remember { mutableIntStateOf(7) }

    fun formatTemp(c: Float?): String {
        if (c == null) return "--"
        val v = if (isFahrenheit) (c * 1.8f + 32) else c
        val u = if (isFahrenheit) "°F" else "°C"
        return "${v.toInt()}$u"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (isPersian) "پیش‌بینی چند روزه" else "Multi-Day Forecast",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 7, 10).forEach { days ->
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { selectedDays = days },
                    label = { Text(if (isPersian) "$days روزه" else "$days Days") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        data?.daily?.let { daily ->
            val times = daily.time ?: emptyList()
            val maxs = daily.temperature2mMax ?: emptyList()
            val mins = daily.temperature2mMin ?: emptyList()
            val pops = daily.precipitationProbabilityMax ?: emptyList()
            val codes = daily.weatherCode ?: emptyList()
            val count = minOf(selectedDays, times.size)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(count) { index ->
                    val rawDate = times.getOrNull(index) ?: ""
                    val formattedDate = DateHelper.formatDate(rawDate, isJalali, isPersian)
                    val (_, dIcon) = WeatherThemeHelper.getWeatherDetails(codes.getOrNull(index), isDay, isPersian)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formattedDate, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(dIcon, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("بارش: ${pops.getOrNull(index) ?: 0}%", color = Color.Cyan, fontSize = 13.sp)
                            }
                            Text(
                                "${formatTemp(maxs.getOrNull(index))} / ${formatTemp(mins.getOrNull(index))}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        } ?: run {
            Text(if (isPersian) "داده‌ای موجود نیست." else "No data available.", color = Color.Gray)
        }
    }
}

// --- صفحه رادار زنده ماهواره‌ای ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveRadarScreen(lat: Double, lon: Double, isPersian: Boolean) {
    var radarEngine by remember { mutableStateOf("windy") }
    var isMapLoading by remember { mutableStateOf(true) }

    val windyUrl = "https://embed.windy.com/embed.html?type=map&location=coordinates&metricRain=mm&metricTemp=°C&metricWind=km/h&zoom=6&overlay=radar&product=radar&level=surface&lat=$lat&lon=$lon"
    val rainViewerUrl = "https://www.rainviewer.com/map.html?loc=$lat,$lon,6&oFa=1&oC=1&oU=0&oCl=1"
    val currentUrl = if (radarEngine == "windy") windyUrl else rainViewerUrl

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { radarEngine = "windy" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (radarEngine == "windy") Color(0xFF0284C7) else Color(0x33FFFFFF)
                )
            ) {
                Text(if (isPersian) "رادار Windy" else "Windy Live Radar")
            }
            Button(
                onClick = { radarEngine = "rainviewer" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (radarEngine == "rainviewer") Color(0xFF0284C7) else Color(0x33FFFFFF)
                )
            ) {
                Text(if (isPersian) "نقشه RainViewer" else "RainViewer Map")
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isMapLoading = true
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isMapLoading = false
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl) {
                        webView.loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isMapLoading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(if (isPersian) "در حال دریافت رادار..." else "Loading Radar...", color = Color.White)
                    }
                }
            }
        }
    }
}

// --- دیالوگ جستجوی شهرها همراه با تاریخچه ---
@Composable
fun CitySearchDialog(
    isPersian: Boolean,
    storageManager: StorageManager,
    onDismiss: () -> Unit,
    onCitySelected: (GeoCity) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoCity>>(emptyList()) }
    var history by remember { mutableStateOf(storageManager.getSearchHistory()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Text(
                if (isPersian) "جستجوی شهر" else "Search City",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (isPersian) "مثال: تبریز، شیراز، London..." else "e.g. Tehran, Paris...") },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (query.isNotBlank()) {
                                scope.launch {
                                    isSearching = true
                                    try {
                                        val res = withContext(Dispatchers.IO) {
                                            RetrofitClient.api.searchCity(name = query.trim())
                                        }
                                        results = res.results ?: emptyList()
                                    } catch (e: Exception) {
                                        results = emptyList()
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Search, null, tint = Color.Cyan)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Cyan)
                    }
                } else if (results.isNotEmpty()) {
                    Text(
                        if (isPersian) "نتایج جستجو:" else "Search Results:",
                        color = Color.Cyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(results) { city ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCitySelected(city) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(city.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${city.admin1 ?: ""}, ${city.country ?: ""}", color = Color.Gray, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = Color(0x22FFFFFF))
                        }
                    }
                } else if (history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isPersian) "تاریخچه جستجوها:" else "Recent Searches:",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                        TextButton(onClick = {
                            storageManager.clearHistory()
                            history = emptyList()
                        }) {
                            Text(if (isPersian) "پاک کردن" else "Clear", color = Color.Red, fontSize = 12.sp)
                        }
                    }

                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                        items(history) { oldCity ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCitySelected(oldCity) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(oldCity.name, color = Color.White)
                                }
                                Text(oldCity.country ?: "", color = Color.Gray, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = Color(0x15FFFFFF))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isPersian) "بستن" else "Close", color = Color.Cyan)
            }
        }
    )
}

// --- دیالوگ اطلاع‌رسانی نسخه جدید ---
@Composable
fun UpdateDialog(
    update: AppUpdateInfo,
    isPersian: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, tint = Color.Cyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPersian) "نسخه جدید ${update.versionName}" else "New Version ${update.versionName}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = if (isPersian) "تغییرات نسخه جدید:" else "What's new:",
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = update.changelog, color = Color.White)
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text(if (isPersian) "دانلود و بروزرسانی" else "Download & Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isPersian) "بعداً" else "Later", color = Color.Gray)
            }
        }
    )
}

// متد امن دانلود با مرورگر یا DownloadManager (بدون نیاز به تنظیمات پیچیده پرمیشن در اندروید ۱۴)
fun downloadAndInstallApk(context: Context, apkUrl: String) {
    try {
        if (apkUrl.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val downloadUri = Uri.parse(apkUrl)
            val request = DownloadManager.Request(downloadUri).apply {
                setTitle("Emin Weather Update")
                setDescription("Downloading new version...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(context, "در حال دانلود آپدیت...", Toast.LENGTH_SHORT).show()
        } catch (err: Exception) {
            Toast.makeText(context, "خطا در دانلود: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// --- صفحه تنظیمات کامل ---
@Composable
fun SettingsScreen(
    isPersian: Boolean,
    isJalali: Boolean,
    isFahrenheit: Boolean,
    currentCity: GeoCity,
    onSaveAsDefault: () -> Unit,
    onCheckUpdate: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleCalendar: () -> Unit,
    onToggleUnit: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = if (isPersian) "تنظیمات Emin Weather" else "Emin Weather Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(18.dp))

        // ثبت شهر پیش‌فرض
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isPersian) "شهر پیش‌فرض باز شدن" else "Default City", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(currentCity.name, color = Color.Cyan, fontSize = 13.sp)
                }
                Button(onClick = onSaveAsDefault) {
                    Text(if (isPersian) "ثبت این شهر" else "Set Default")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // تغییر زبان
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (isPersian) "زبان برنامه" else "Language", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (isPersian) "فارسی / English" else "English / Persian", color = Color.LightGray, fontSize = 12.sp)
                }
                Button(onClick = onToggleLanguage) {
                    Text(if (isPersian) "English" else "فارسی")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // گاه‌شمار
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (isPersian) "گاه‌شمار" else "Calendar", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (isJalali) "شمسی" else "میلادی", color = Color.Cyan, fontSize = 12.sp)
                }
                Button(onClick = onToggleCalendar) {
                    Text(if (isJalali) "میلادی" else "شمسی")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // واحد دما
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (isPersian) "واحد نمایش دما" else "Temperature Unit", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (isFahrenheit) "فارنهایت (°F)" else "سلسیوس (°C)", color = Color.Cyan, fontSize = 12.sp)
                }
                Button(onClick = onToggleUnit) {
                    Text(if (isFahrenheit) "°C" else "°F")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // دکمه بررسی آپدیت آنلاین
        OutlinedButton(
            onClick = onCheckUpdate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CloudDownload, null, tint = Color.Cyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPersian) "بررسی نسخه جدید برنامه (آپدیت)" else "Check for Updates", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // دکمه ریفرش
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPersian) "به‌روزرسانی اطلاعات آب‌وهوا" else "Refresh Weather Data")
        }
    }
}
