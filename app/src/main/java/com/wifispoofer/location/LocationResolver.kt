package com.wifispoofer.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class Coordinates(val lat: Double, val lon: Double)

/**
 * Определяет координаты:
 * A) По IP через ip-api.com
 * B) Через системный GPS (может быть подменён GPS-спуфером)
 * C) Ручной ввод (в MainActivity)
 */
object LocationResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getByIpApi(): Coordinates = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://ip-api.com/json/?fields=lat,lon,status,message")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from ip-api")
        val json = JSONObject(body)

        if (json.optString("status") == "fail") {
            throw Exception("ip-api error: ${json.optString("message")}")
        }

        Coordinates(lat = json.getDouble("lat"), lon = json.getDouble("lon"))
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    suspend fun getByGps(context: Context): Coordinates {
        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val last = withContext(Dispatchers.IO) {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.FUSED_PROVIDER
            )
            for (provider in providers) {
                try {
                    val loc = locManager.getLastKnownLocation(provider)
                    if (loc != null) return@withContext Coordinates(loc.latitude, loc.longitude)
                } catch (_: Exception) {
                    continue
                }
            }
            null
        }
        if (last != null) return last

        return suspendCancellableCoroutine { cont ->
            try {
                locManager.requestSingleUpdate(
                    LocationManager.GPS_PROVIDER,
                    { location ->
                        if (cont.isActive) cont.resume(Coordinates(location.latitude, location.longitude))
                    },
                    null
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Coordinates(0.0, 0.0))
            }
        }
    }
}
