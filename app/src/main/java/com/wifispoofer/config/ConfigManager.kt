package com.wifispoofer.config

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.wifispoofer.model.FakeNetwork
import org.json.JSONArray

private const val AUTHORITY = "com.wifispoofer.config"

/**
 * Управляет конфигурацией модуля.
 * Prefs пишутся с MODE_WORLD_READABLE; хуки читают их через XSharedPreferences
 * (LSPosed, meta-data xposedsharedprefs=true / new XSharedPreferences).
 * Если этот механизм недоступен — см. ConfigProvider как запасной канал.
 */
object ConfigManager {

    const val PREFS_NAME = "wifispoofer_prefs"
    private const val KEY_NETWORKS = "cached_networks"
    private const val KEY_SCOPE = "target_scope"
    private const val KEY_SOURCE = "location_source"
    private const val KEY_MANUAL_LAT = "manual_lat"
    private const val KEY_MANUAL_LON = "manual_lon"
    private const val KEY_CONNECTED_INDEX = "connected_index"

    /**
     * ВАЖНО: MODE_WORLD_READABLE обязателен, а не MODE_PRIVATE.
     * LSPosed перехватывает ContextImpl.checkMode() и разрешает этот режим
     * только когда модуль реально его запрашивает (см. meta-data
     * xposedsharedprefs в манифесте) — это и есть механизм "new
     * XSharedPreferences". С MODE_PRIVATE хук в другом процессе получит
     * пустой файл и вернёт пустой список сетей.
     */
    @Suppress("DEPRECATION")
    fun getPrefs(context: Context): SharedPreferences = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        // Значит LSPosed не подключил новый механизм (модуль выключен,
        // либо версия LSPosed его не поддерживает) — работаем локально,
        // но кэш тогда не будет виден хукам.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ===== Wi-Fi Networks =====

    fun saveNetworks(context: Context, networks: List<FakeNetwork>) {
        getPrefs(context).edit()
            .putString(KEY_NETWORKS, FakeNetwork.listToJson(networks))
            .apply()
    }

    fun loadNetworks(context: Context): List<FakeNetwork> {
        val json = getPrefs(context).getString(KEY_NETWORKS, "") ?: ""
        return FakeNetwork.listFromJson(json)
    }

    fun clearNetworks(context: Context) {
        getPrefs(context).edit().remove(KEY_NETWORKS).apply()
    }

    // ===== Scope =====

    fun saveScope(context: Context, packages: Set<String>) {
        val arr = JSONArray(packages.toList())
        getPrefs(context).edit().putString(KEY_SCOPE, arr.toString()).apply()
    }

    fun loadScope(context: Context): Set<String> =
        loadScopeFromPrefs(getPrefs(context))

    // ===== Location Source =====

    fun saveLocationSource(context: Context, source: String) {
        getPrefs(context).edit().putString(KEY_SOURCE, source).apply()
    }

    fun getLocationSource(context: Context): String =
        getPrefs(context).getString(KEY_SOURCE, "ip") ?: "ip"

    fun saveManualCoords(context: Context, lat: Double, lon: Double) {
        getPrefs(context).edit()
            .putString(KEY_MANUAL_LAT, lat.toString())
            .putString(KEY_MANUAL_LON, lon.toString())
            .apply()
    }

    fun getManualCoords(context: Context): Pair<Double, Double>? {
        val prefs = getPrefs(context)
        val lat = prefs.getString(KEY_MANUAL_LAT, null)?.toDoubleOrNull()
        val lon = prefs.getString(KEY_MANUAL_LON, null)?.toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    // ===== Connected Network Index =====

    fun saveConnectedIndex(context: Context, index: Int) {
        getPrefs(context).edit().putInt(KEY_CONNECTED_INDEX, index).apply()
    }

    fun getConnectedIndex(context: Context): Int =
        getPrefs(context).getInt(KEY_CONNECTED_INDEX, 0)

    // ===== Чтение из Xposed (XSharedPreferences) =====

    fun loadNetworksFromPrefs(prefs: SharedPreferences): List<FakeNetwork> {
        val json = prefs.getString(KEY_NETWORKS, "") ?: ""
        return FakeNetwork.listFromJson(json)
    }

    fun loadScopeFromPrefs(prefs: SharedPreferences): Set<String> {
        val json = prefs.getString(KEY_SCOPE, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun getConnectedIndexFromPrefs(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_CONNECTED_INDEX, 0)

    // ===== Запасной канал через ContentProvider (если XSharedPreferences =====
    // ===== недоступен в вашей версии LSPosed)                             =====

    private fun queryProvider(context: Context, segment: String): String? = try {
        context.contentResolver.query(
            Uri.parse("content://$AUTHORITY/$segment"), null, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun loadNetworksViaProvider(context: Context): List<FakeNetwork> {
        val json = queryProvider(context, "networks") ?: return emptyList()
        return FakeNetwork.listFromJson(json)
    }

    fun loadScopeViaProvider(context: Context): Set<String> {
        val json = queryProvider(context, "scope") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun getConnectedIndexViaProvider(context: Context): Int =
        queryProvider(context, "connected_index")?.toIntOrNull() ?: 0
}
