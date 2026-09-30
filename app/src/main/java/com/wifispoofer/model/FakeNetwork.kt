package com.wifispoofer.model

import org.json.JSONArray
import org.json.JSONObject

data class FakeNetwork(
    val ssid: String,
    val bssid: String,
    val frequency: Int = 2437,
    val level: Int = -55,
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
    val channelWidth: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("bssid", bssid)
        put("frequency", frequency)
        put("level", level)
        put("capabilities", capabilities)
        put("channelWidth", channelWidth)
    }

    companion object {
        fun fromJson(json: JSONObject): FakeNetwork = FakeNetwork(
            ssid = json.optString("ssid", "Unknown"),
            bssid = json.optString("bssid", "00:00:00:00:00:00"),
            frequency = json.optInt("frequency", 2437),
            level = json.optInt("level", -55),
            capabilities = json.optString("capabilities", "[WPA2-PSK-CCMP][ESS]"),
            channelWidth = json.optInt("channelWidth", 0)
        )

        fun listToJson(list: List<FakeNetwork>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString(2)
        }

        fun listFromJson(jsonStr: String): List<FakeNetwork> {
            if (jsonStr.isBlank()) return emptyList()
            val arr = JSONArray(jsonStr)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}
