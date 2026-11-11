package com.weather.aura

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

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
    @SerializedName("surface_pressure") val surfacePressure: Float? = null
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

// --- اینترفیس API هواشناسی و موتور جستجو ---
interface WeatherApi {
    @GET("v1/forecast?current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,surface_pressure&hourly=temperature_2m,weather_code,precipitation_probability&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=10&timezone=auto")
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

// --- الگوریتم تبدیل تاریخ میلادی به خورشیدی (شمسی) ---
object DateHelper {
    private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val g_d_m = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) (gy + 1) else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + g_d_m[gm - 1]
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
                if (isFa) "$jd ${persianMonths[jm - 1]}" else "$jd/${jm}"
            } else {
                "$y/$m/$d"
            }
        } catch (e: Exception) {
            isoDate
        }
    }
}

// --- تفسیر کدهای WMO و استایل‌های گرافیکی هوشمند ---
object WeatherThemeHelper {
    fun getWeatherDetails(code: Int?, isFa: Boolean): Pair<String, ImageVector> {
        return when (code ?: 0) {
            0 -> (if (isFa) "آسمان کاملاً صاف و آفتابی" else "Clear Sky") to Icons.Default.WbSunny
            1, 2 -> (if (isFa) "غالباً صاف و کمی ابری" else "Partly Cloudy") to Icons.Default.CloudQueue
            3 -> (if (isFa) "تمام ابری و متراکم" else "Overcast") to Icons.Default.Cloud
            45, 48 -> (if (isFa) "مه‌آلود و کاهش دید" else "Foggy") to Icons.Default.Dehaze
            51, 53, 55 -> (if (isFa) "نم‌نم باران پراکنده" else "Drizzle") to Icons.Default.Grain
            61, 63, 65 -> (if (isFa) "بارش باران" else "Rain") to Icons.Default.WaterDrop
            71, 73, 75, 77 -> (if (isFa) "بارش برف و کولاک" else "Snow") to Icons.Default.AcUnit
            80, 81, 82 -> (if (isFa) "رگبار شدید باران" else "Heavy Showers") to Icons.Default.Thunderstorm
            95, 96, 99 -> (if (isFa) "طوفان و رعد و برق" else "Thunderstorm") to Icons.Default.FlashOn
            else -> (if (isFa) "مساعد" else "Fair") to Icons.Default.WbSunny
        }
    }

    fun getBackgroundGradient(code: Int?): List<Color> {
        return when (code ?: 0) {
            0 -> listOf(Color(0xFF0284C7), Color(0xFF0369A1), Color(0xFF0F172A)) // آفتابی آبی درخشان
            1, 2, 3 -> listOf(Color(0xFF334155), Color(0xFF1E293B), Color(0xFF0F172A)) // ابری ملایم
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF172554)) // بارانی
            71, 73, 75, 77 -> listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF1E293B)) // برفی
            95, 96, 99 -> listOf(Color(0xFF3B0764), Color(0xFF1E1B4B), Color(0xFF0F172A)) // طوفانی بنفش
            else -> listOf(Color(0xFF0F172A), Color(0xFF1E1B4B))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF38BDF8))) {
                WeatherAppMain()
            }
        }
    }
}

@Composable
fun WeatherAppMain() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var cityName by remember { mutableStateOf("تهران (Tehran)") }
    var lat by remember { mutableDoubleStateOf(35.6892) }
    var lon by remember { mutableDoubleStateOf(51.3890) }

    // تنظیمات کاربر
    var isPersian by remember { mutableStateOf(true) }
    var isJalali by remember { mutableStateOf(true) }
    var isFahrenheit by remember { mutableStateOf(false) }

    var weatherData by remember { mutableStateOf<WeatherResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // دریافت داده‌ها
    LaunchedEffect(lat, lon, refreshKey) {
        isLoading = true
        errorMessage = null
        try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.api.getForecast(lat, lon)
            }
            weatherData = response
        } catch (e: Exception) {
            errorMessage = if (isPersian) "خطا در اتصال به سرور آب‌وهوا" else "Connection error"
        } finally {
            isLoading = false
        }
    }

    val currentCode = weatherData?.current?.weatherCode
    val backgroundColors = WeatherThemeHelper.getBackgroundGradient(currentCode)

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xCC0F172A)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.WbSunny, null) },
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
                    label = { Text(if (isPersian) "رادار" else "Radar") }
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
                .background(Brush.verticalGradient(backgroundColors))
        ) {
            when (selectedTab) {
                0 -> CurrentWeatherScreen(
                    cityName = cityName,
                    data = weatherData,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    isPersian = isPersian,
                    isFahrenheit = isFahrenheit,
                    onSearchClick = { showSearchDialog = true },
                    onRetry = { refreshKey++ }
                )
                1 -> ForecastScreen(
                    data = weatherData,
                    isPersian = isPersian,
                    isJalali = isJalali,
                    isFahrenheit = isFahrenheit
                )
                2 -> LiveRadarScreen(lat = lat, lon = lon, isPersian = isPersian)
                3 -> SettingsScreen(
                    isPersian = isPersian,
                    isJalali = isJalali,
                    isFahrenheit = isFahrenheit,
                    onToggleLanguage = { isPersian = !isPersian },
                    onToggleCalendar = { isJalali = !isJalali },
                    onToggleUnit = { isFahrenheit = !isFahrenheit },
                    onRefresh = { refreshKey++ }
                )
            }

            if (showSearchDialog) {
                CitySearchDialog(
                    isPersian = isPersian,
                    onDismiss = { showSearchDialog = false },
                    onCitySelected = { city ->
                        cityName = "${city.name} (${city.country ?: ""})"
                        lat = city.latitude
                        lon = city.longitude
                        showSearchDialog = false
                    }
                )
            }
        }
    }
}

// --- صفحه اصلی و پیش‌بینی ساعتی ---
@Composable
fun CurrentWeatherScreen(
    cityName: String,
    data: WeatherResponse?,
    isLoading: Boolean,
    errorMessage: String?,
    isPersian: Boolean,
    isFahrenheit: Boolean,
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
        // نوار بالا
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
                    text = cityName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Icon(Icons.Default.Search, null, tint = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))

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
                val (conditionText, icon) = WeatherThemeHelper.getWeatherDetails(current.weatherCode, isPersian)

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(80.dp)
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

                Spacer(modifier = Modifier.height(24.dp))

                // کارت اطلاعات تکمیلی (باد، رطوبت، فشار، حس واقعی)
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

                Spacer(modifier = Modifier.height(24.dp))

                // پیش‌بینی ساعتی ۲۴ ساعته
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
                            val (_, hourIcon) = WeatherThemeHelper.getWeatherDetails(c, isPersian)

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

// --- صفحه پیش‌بینی ۱۰ روزه ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    data: WeatherResponse?,
    isPersian: Boolean,
    isJalali: Boolean,
    isFahrenheit: Boolean
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
                    val (_, dIcon) = WeatherThemeHelper.getWeatherDetails(codes.getOrNull(index), isPersian)

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

// --- صفحه رادار آنلاین و ماهواره‌ای ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveRadarScreen(lat: Double, lon: Double, isPersian: Boolean) {
    var radarEngine by remember { mutableStateOf("windy") } // windy or rainviewer
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
                Text(if (isPersian) "رادار زنده Windy" else "Windy Live Radar")
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
                        Text(if (isPersian) "در حال بارگذاری رادار ماهواره‌ای..." else "Loading Radar Map...", color = Color.White)
                    }
                }
            }
        }
    }
}

// --- دیالوگ جستجوی پیشرفته شهرهای جهان ---
@Composable
fun CitySearchDialog(
    isPersian: Boolean,
    onDismiss: () -> Unit,
    onCitySelected: (GeoCity) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoCity>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Text(
                if (isPersian) "جستجوی پیشرفته شهر" else "Search City",
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
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(results) { city ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCitySelected(city) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(city.name, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${city.admin1 ?: ""}, ${city.country ?: ""}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Divider(color = Color(0x22FFFFFF))
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

// --- صفحه تنظیمات کامل ---
@Composable
fun SettingsScreen(
    isPersian: Boolean,
    isJalali: Boolean,
    isFahrenheit: Boolean,
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
            text = if (isPersian) "تنظیمات برنامه" else "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(20.dp))

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

        // نوع تقویم
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
                    Text(if (isPersian) "نوع گاه‌شمار و تقویم" else "Calendar Type", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (isJalali) "گاه‌شمار خورشیدی (شمسی)" else "Gregorian (میلادی)", color = Color.Cyan, fontSize = 12.sp)
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

        Spacer(modifier = Modifier.height(12.dp))

        // دکمه به‌روزرسانی دستی
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isPersian) "به‌روزرسانی داده‌های هواشناسی" else "Refresh Weather Data")
        }
    }
}
