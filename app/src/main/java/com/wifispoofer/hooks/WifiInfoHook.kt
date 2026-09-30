package com.wifispoofer.hooks

import com.wifispoofer.XposedEntry
import com.wifispoofer.config.ConfigManager
import com.wifispoofer.model.FakeNetwork
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Хуки WifiInfo: getBSSID, getSSID, getFrequency, getRssi,
 * getNetworkId, getSupplicantState + ConnectivityManager.getActiveNetworkInfo.
 */
object WifiInfoHook {

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookWifiInfoGetters(lpparam)
        hookNetworkInfo(lpparam)
    }

    private fun hookGetter(
        lpparam: XC_LoadPackage.LoadPackageParam,
        method: String,
        value: (FakeNetwork) -> Any?
    ) {
        try {
            XposedHelpers.findAndHookMethod("android.net.wifi.WifiInfo", lpparam.classLoader, method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val net = getConnectedNetwork() ?: return
                        value(net)?.let { param.result = it }
                    }
                })
            XposedEntry.log("✓ Hooked WifiInfo.$method()")
        } catch (e: Throwable) {
            XposedEntry.log("✗ Failed to hook $method: ${e.message}")
        }
    }

    private fun hookWifiInfoGetters(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetter(lpparam, "getBSSID") { it.bssid }
        hookGetter(lpparam, "getSSID") { "\"${it.ssid}\"" }
        hookGetter(lpparam, "getFrequency") { it.frequency }
        hookGetter(lpparam, "getRssi") { it.level }
        hookGetter(lpparam, "getNetworkId") { 1 }
        hookGetter(lpparam, "getSupplicantState") {
            try {
                val cls = XposedHelpers.findClass("android.net.wifi.SupplicantState", lpparam.classLoader)
                XposedHelpers.getStaticObjectField(cls, "COMPLETED")
            } catch (_: Throwable) { null }
        }
    }

    private fun hookNetworkInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod("android.net.ConnectivityManager", lpparam.classLoader,
                "getActiveNetworkInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (loadCachedNetworks().isEmpty()) return
                        val result = param.result ?: return
                        try {
                            XposedHelpers.setIntField(result, "mNetworkType", 1) // TYPE_WIFI
                            XposedHelpers.callMethod(result, "setIsAvailable", true)
                        } catch (_: Throwable) {}
                    }
                })
            XposedEntry.log("✓ Hooked ConnectivityManager.getActiveNetworkInfo()")
        } catch (e: Throwable) {
            XposedEntry.log("⚠ NetworkInfo hook skipped: ${e.message}")
        }
    }

    // ===== Helpers =====

    private fun getConnectedNetwork(): FakeNetwork? {
        val networks = loadCachedNetworks()
        if (networks.isEmpty()) return null
        return networks[getConnectedIndex().coerceIn(0, networks.size - 1)]
    }

    private fun loadCachedNetworks(): List<FakeNetwork> {
        try {
            val ctx = de.robv.android.xposed.AndroidAppHelper.currentApplication()
            if (ctx != null) {
                val viaProvider = ConfigManager.loadNetworksViaProvider(ctx)
                if (viaProvider.isNotEmpty()) return viaProvider
            }
        } catch (e: Throwable) {}
        return try {
            XposedEntry.prefs.reload()
            ConfigManager.loadNetworksFromPrefs(XposedEntry.prefs)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getConnectedIndex(): Int {
        try {
            val ctx = de.robv.android.xposed.AndroidAppHelper.currentApplication()
            if (ctx != null) return ConfigManager.getConnectedIndexViaProvider(ctx)
        } catch (_: Throwable) {}
        return try {
            XposedEntry.prefs.reload()
            ConfigManager.getConnectedIndexFromPrefs(XposedEntry.prefs)
        } catch (_: Exception) { 0 }
    }
}
