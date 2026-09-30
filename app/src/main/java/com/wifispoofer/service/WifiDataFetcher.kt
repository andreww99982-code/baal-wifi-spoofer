package com.wifispoofer.service

import com.wifispoofer.location.Coordinates
import com.wifispoofer.model.FakeNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Получает Wi-Fi сети из открытых баз по координатам.
 * 1. Mylnikov  2. Wigle (нужен ключ)  3. OpenWifi  4. Fallback-генерация
 */
object WifiDataFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Wigle: base64("apiName:apiToken") */
    var wigleApiKey: String = ""

    suspend fun fetchNetworks(
        coords: Coordinates,
        count: Int = 8,
        radiusMeters: Int = 100
    ): List<FakeNetwork> = withContext(Dispatchers.IO) {

        try {
            val result = fetchFromMylnikov(coords, count)
            if (result.isNotEmpty()) return@withContext result
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (wigleApiKey.isNotBlank()) {
            try {
                val result = fetchFromWigle(coords, count, radiusMeters)
                if (result.isNotEmpty()) return@withContext result
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val result = fetchFromOpenWifi(coords, count)
            if (result.isNotEmpty()) return@withContext result
        } catch (e: Exception) {
            e.printStackTrace()
        }

        generateRealisticNetworks(coords, count)
    }

    private fun fetchFromMylnikov(coords: Coordinates, count: Int): List<FakeNetwork> {
        val networks = mutableListOf<FakeNetwork>()

        val url = "https://api.mylnikov.org/geolocation/wifi" +
                "?v=1.2&data=open" +
                "&search_lat=${coords.lat}" +
                "&search_lon=${coords.lon}" +
                "&search_radius=0.001"

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        if (json.optInt("result") == 200) {
            val wifiArr = json.optJSONObject("data")?.optJSONArray("wifi")
            if (wifiArr != null) {
                for (i in 0 until minOf(wifiArr.length(), count)) {
                    val wifi = wifiArr.getJSONObject(i)
                    networks.add(
                        FakeNetwork(
                            ssid = wifi.optString("ssid", "Network_${i + 1}"),
                            bssid = wifi.optString("bssid", generateRandomBssid()),
                            frequency = randomFrequency(),
                            level = Random.nextInt(-75, -35),
                            capabilities = "[WPA2-PSK-CCMP][ESS]"
                        )
                    )
                }
            }
        }
        return networks
    }

    private fun fetchFromWigle(coords: Coordinates, count: Int, radiusMeters: Int): List<FakeNetwork> {
        val networks = mutableListOf<FakeNetwork>()

        val latOffset = radiusMeters / 111111.0
        val lonOffset = radiusMeters / (111111.0 * Math.cos(Math.toRadians(coords.lat)))

        val url = "https://api.wigle.net/api/v2/network/search" +
                "?latrange1=${coords.lat - latOffset}" +
                "&latrange2=${coords.lat + latOffset}" +
                "&longrange1=${coords.lon - lonOffset}" +
                "&longrange2=${coords.lon + lonOffset}" +
                "&resultsPerPage=$count" +
                "&freenet=false&paynet=false"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Basic $wigleApiKey")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        if (json.optBoolean("success")) {
            val results = json.optJSONArray("results")
            if (results != null) {
                for (i in 0 until minOf(results.length(), count)) {
                    val net = results.getJSONObject(i)
                    networks.add(
                        FakeNetwork(
                            ssid = net.optString("ssid", ""),
                            bssid = net.optString("netid", generateRandomBssid()),
                            frequency = net.optInt("frequency", randomFrequency()),
                            level = Random.nextInt(-75, -35),
                            capabilities = mapEncryption(net.optString("encryption", "wpa2"))
                        )
                    )
                }
            }
        }
        return networks
    }

    private fun fetchFromOpenWifi(coords: Coordinates, count: Int): List<FakeNetwork> {
        val networks = mutableListOf<FakeNetwork>()
        val url = "https://openwifi.su/api/v1/bssids" +
                "?lat=${coords.lat}&lon=${coords.lon}&distance=100"

        try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data")

            if (data != null) {
                for (i in 0 until minOf(data.length(), count)) {
                    val item = data.getJSONObject(i)
                    networks.add(
                        FakeNetwork(
                            ssid = item.optString("ssid", "WiFi_$i"),
                            bssid = item.optString("bssid", generateRandomBssid()),
                            frequency = randomFrequency(),
                            level = Random.nextInt(-70, -40)
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return networks
    }

    /** Fallback — правдоподобные сети с реальными OUI производителей. */
    private fun generateRealisticNetworks(coords: Coordinates, count: Int): List<FakeNetwork> {
        val realOuis = listOf(
            "00:1A:2B", "00:1E:58", "00:22:6B", "00:25:9C",
            "04:F0:21", "08:36:C9", "0C:80:63", "10:FE:ED",
            "28:6C:07", "2C:56:DC", "30:B5:C2", "34:97:F6",
            "38:D5:47", "3C:37:86", "40:4A:03", "44:94:FC",
            "48:5B:39", "4C:ED:FB", "50:C7:BF", "54:04:A6",
            "58:D5:6E", "5C:49:79", "60:38:E0", "64:70:02",
            "68:FF:7B", "6C:72:20", "70:4F:57", "74:DA:38",
            "78:44:76", "7C:39:53", "80:26:89", "84:16:F9",
            "F8:1A:67", "EC:08:6B", "E8:48:B8", "DC:9F:DB"
        )

        val ssidPatterns = when {
            coords.lat in 35.0..72.0 && coords.lon in -10.0..40.0 -> listOf(
                "Vodafone-", "FRITZ!Box ", "SFR_", "Bbox-", "Livebox-",
                "TelenetWiFi-", "NETGEAR", "TP-Link_", "UPC", "Ziggo"
            )
            coords.lat in 25.0..50.0 && coords.lon in -130.0..-60.0 -> listOf(
                "NETGEAR", "xfinitywifi", "ATT", "Spectrum", "DIRECT-",
                "linksys", "HOME-", "MySpectrumWiFi", "CenturyLink", "Google Fiber"
            )
            coords.lat in 20.0..55.0 && coords.lon in 95.0..150.0 -> listOf(
                "HUAWEI-", "ChinaNet-", "CMCC-", "KT_GiGA_", "SK_WiFi",
                "SoftBank", "au_Wi-Fi", "JCOM", "Buffalo-", "elecom-"
            )
            else -> listOf(
                "WiFi-", "Home_", "NET_", "Wireless", "WLAN-",
                "Router_", "MyWiFi_", "TP-Link_", "NETGEAR", "Linksys_"
            )
        }

        val networks = mutableListOf<FakeNetwork>()
        val usedBssids = mutableSetOf<String>()

        repeat(count) {
            val oui = realOuis.random()
            var bssid: String
            do {
                bssid = "$oui:${randomHex()}:${randomHex()}:${randomHex()}"
            } while (bssid in usedBssids)
            usedBssids.add(bssid)

            networks.add(
                FakeNetwork(
                    ssid = "${ssidPatterns.random()}${Random.nextInt(1000, 9999)}",
                    bssid = bssid.uppercase(),
                    frequency = randomFrequency(),
                    level = Random.nextInt(-80, -30),
                    capabilities = listOf(
                        "[WPA2-PSK-CCMP][ESS]",
                        "[WPA2-PSK-CCMP][WPS][ESS]",
                        "[WPA-PSK-CCMP+TKIP][WPA2-PSK-CCMP+TKIP][ESS]",
                        "[WPA2-PSK-CCMP+TKIP][ESS]",
                        "[ESS]"
                    ).random()
                )
            )
        }
        return networks
    }

    private fun generateRandomBssid(): String = (1..6).joinToString(":") { randomHex() }

    private fun randomHex(): String = String.format("%02X", Random.nextInt(256))

    private fun randomFrequency(): Int =
        listOf(2412, 2417, 2422, 2427, 2432, 2437, 2442, 2447, 2452, 2457, 2462,
            5180, 5200, 5220, 5240, 5260, 5280, 5300, 5320, 5500, 5520).random()

    private fun mapEncryption(enc: String): String = when {
        enc.contains("wpa3", true) -> "[WPA3-SAE][ESS]"
        enc.contains("wpa2", true) -> "[WPA2-PSK-CCMP][ESS]"
        enc.contains("wpa", true) -> "[WPA-PSK-CCMP][ESS]"
        enc.contains("wep", true) -> "[WEP][ESS]"
        else -> "[ESS]"
    }
}
