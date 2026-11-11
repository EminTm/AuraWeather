package com.weather.aura

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

// --- مدل‌های داده API با پشتیبانی کامل از Null-Safety ---
data class WeatherResponse(
    @SerializedName("current") val current: CurrentWeather? = null,
    @SerializedName("daily") val daily: DailyWeather? = null
)

data class CurrentWeather(
    @SerializedName("temperature_2m") val temperature2m: Float? = 0f,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: Int? = 0,
    @SerializedName("weather_code") val weatherCode: Int? = 0,
    @SerializedName("wind_speed_10m") val windSpeed10m: Float? = 0f
)

data class DailyWeather(
    @SerializedName("time") val time: List<String> = emptyList(),
    @SerializedName("temperature_2m_max") val temperature2mMax: List<Float> = emptyList(),
    @SerializedName("temperature_2m_min") val temperature2mMin: List<Float> = emptyList(),
    @SerializedName("precipitation_probability_max") val precipitationProbabilityMax: List<Int> = emptyList()
)

// اصلاح اینترفیس Retrofit
interface WeatherApi {
    @GET("v1/forecast?current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=10&timezone=auto")
    suspend fun getForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double
    ): WeatherResponse
}

// کلاینت Retrofit به صورت سه‌قلوی Singleton
object RetrofitClient {
    val api: WeatherApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeatherAppTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var cityName by remember { mutableStateOf("تهران") }
    var lat by remember { mutableDoubleStateOf(35.6892) }
    var lon by remember { mutableDoubleStateOf(51.3890) }

    var weatherData by remember { mutableStateOf<WeatherResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun fetchWeather() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val data = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getForecast(lat, lon)
                }
                weatherData = data
            } catch (e: Exception) {
                errorMessage = "خطا در برقراری ارتباط با سرور: ${e.localizedMessage ?: "مشکل ناشناخته"}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(lat, lon) {
        fetchWeather()
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1E293B)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.WbSunny, contentDescription = null) },
                    label = { Text("هواشناسی") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("پیش‌بینی") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Radar, contentDescription = null) },
                    label = { Text("رادار آنلاین") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF1E1B4B))
                    )
                )
        ) {
            when (selectedTab) {
                0 -> CurrentWeatherView(
                    cityName = cityName,
                    data = weatherData,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onSearchClick = {
                        cityName = "مشهد"
                        lat = 36.2972
                        lon = 59.6067
                    },
                    onRetry = { fetchWeather() }
                )
                1 -> ForecastView(weatherData)
                2 -> RadarView(lat, lon)
            }
        }
    }
}

@Composable
fun CurrentWeatherView(
    cityName: String,
    data: WeatherResponse?,
    isLoading: Boolean,
    errorMessage: String?,
    onSearchClick: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = cityName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(60.dp))

        when {
            isLoading -> {
                CircularProgressIndicator(color = Color.Cyan)
            }
            errorMessage != null -> {
                Text(text = errorMessage, color = Color.Red, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onRetry) {
                    Text("تلاش مجدد")
                }
            }
            data?.current != null -> {
                val current = data.current
                val tempText = current.temperature2m?.let { "${it.toInt()}°C" } ?: "--°C"
                val humidity = current.relativeHumidity2m ?: 0
                val windSpeed = current.windSpeed10m ?: 0f

                Text(
                    text = tempText,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "رطوبت: $humidity% | باد: $windSpeed km/h",
                    color = Color.LightGray,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun ForecastView(data: WeatherResponse?) {
    var selectedDays by remember { mutableIntStateOf(3) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("پیش‌بینی مدل ECMWF/GFS", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 7, 10).forEach { days ->
                FilterChip(
                    selected = selectedDays == days,
                    onClick = { selectedDays = days },
                    label = { Text("$days روزه") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        data?.daily?.let { daily ->
            val totalAvailable = daily.time.size
            val itemsCount = minOf(selectedDays, totalAvailable)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(count = itemsCount) { index ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(daily.time.getOrNull(index) ?: "", color = Color.White)
                            Text(
                                "بارش: ${daily.precipitationProbabilityMax.getOrNull(index) ?: 0}%",
                                color = Color.Cyan
                            )
                            Text(
                                "${daily.temperature2mMax.getOrNull(index) ?: 0}° / ${daily.temperature2mMin.getOrNull(index) ?: 0}°",
                                color = Color.White
                            )
                        }
                    }
                }
            }
        } ?: run {
            Text("اطلاعاتی دریافت نشد.", color = Color.Gray)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RadarView(lat: Double, lon: Double) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                loadUrl("https://www.rainviewer.com/map.html?loc=$lat,$lon,7&oFa=1&oC=1&oU=0&oCl=1")
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun WeatherAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF38BDF8),
            background = Color(0xFF0F172A)
        ),
        content = content
    )
}
