package com.weather.aura

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

// --- مدل‌های داده API با دقت بالا (Open-Meteo) ---
data class WeatherResponse(
    val current: CurrentWeather?,
    val daily: DailyWeather?
)

data class CurrentWeather(
    val temperature_2m: Float,
    val relative_humidity_2m: Int,
    val weather_code: Int,
    val wind_speed_10m: Float
)

data class DailyWeather(
    val time: List<String>,
    val temperature_2m_max: List<Float>,
    val temperature_2m_min: List<Float>,
    val precipitation_probability_max: List<Int>
)

interface WeatherApi {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
        @Query("daily") daily: String = "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        @Query("forecast_days") days: Int = 10,
        @Query("timezone") timezone: String = "auto"
    ): WeatherResponse
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    var cityName by remember { mutableStateOf("Tehran") }
    var lat by remember { mutableStateOf(35.6892) }
    var lon by remember { mutableStateOf(51.3890) }
    var weatherData by remember { mutableStateOf<WeatherResponse?>(null) }
    val scope = rememberCoroutineScope()

    val api = remember {
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }

    // لود داده‌ها
    fun fetchWeather() {
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { api.getForecast(lat, lon) }
                weatherData = data
            } catch (e: Exception) {
                e.printStackTrace()
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
                0 -> CurrentWeatherView(cityName, weatherData) {
                    // تغییر نمونه شهر به مشهد برای تست سرچ
                    cityName = "Mashhad"
                    lat = 36.2972
                    lon = 59.6067
                }
                1 -> ForecastView(weatherData)
                2 -> RadarView(lat, lon)
            }
        }
    }
}

// ۱. نمای وضعیت آب‌وهوای فعلی
@Composable
fun CurrentWeatherView(cityName: String, data: WeatherResponse?, onSearchClick: () -> Unit) {
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

        Spacer(modifier = Modifier.height(50.dp))

        data?.current?.let { current ->
            Text(text = "${current.temperature_2m.toInt()}°C", fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(text = "رطوبت: ${current.relative_humidity_2m}% | باد: ${current.wind_speed_10m} km/h", color = Color.LightGray, fontSize = 16.sp)
        } ?: CircularProgressIndicator(color = Color.Cyan)
    }
}

// ۲. نمای پیش‌بینی ۳، ۷ و ۱۰ روزه
@Composable
fun ForecastView(data: WeatherResponse?) {
    var selectedDays by remember { mutableStateOf(3) }

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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(selectedDays) { index ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x33FFFFFF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(daily.time.getOrNull(index) ?: "", color = Color.White)
                            Text("بارش: ${daily.precipitation_probability_max.getOrNull(index) ?: 0}%", color = Color.Cyan)
                            Text("${daily.temperature_2m_max.getOrNull(index) ?: 0}° / ${daily.temperature_2m_min.getOrNull(index) ?: 0}°", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ۳. نمای رادار زنده ماهواره‌ای بارش
@Composable
fun RadarView(lat: Double, lon: Double) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                // نقشه راداری زنده RainViewer
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
