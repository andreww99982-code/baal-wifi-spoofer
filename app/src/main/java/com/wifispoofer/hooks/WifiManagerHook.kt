package com.wifispoofer.hooks

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.wifispoofer.XposedEntry
import com.wifispoofer.config.ConfigManager
import com.wifispoofer.model.FakeNetwork
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Хуки WifiManager: getScanResults, getWifiState, isWifiEnabled,
 * startScan, getConnectionInfo.
 */
object WifiManagerHook {

    private const val WM = "android.net.wifi.WifiManager"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetScanResults(lpparam)
        hookGetWifiState(lpparam)
        hookIsWifiEnabled(lpparam)
        hookStartScan(lpparam)
        hookGetConnectionInfo(lpparam)
    }

    private fun hookGetScanResults(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(WM, lpparam.classLoader, "getScanResults",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Эта строка появляется КАЖДЫЙ РАЗ при реальном вызове getScanResults()
                        // тем самым процессом. Если её нет вообще при открытии/сканировании в
                        // приложении — значит хук физически не срабатывает на этом вызове
                        // (процесс не был перезапущен после включения scope, либо приложение
                        // получает данные не через этот метод).
                        XposedEntry.log("getScanResults() CALLED in process ${android.os.Process.myPid()}")
                        try {
                            val networks = loadCachedNetworks()
                            if (networks.isEmpty()) {
                                XposedEntry.log("getScanResults: no cached networks, passing through")
                                return
                            }
                            val fake = buildScanResults(networks)
                            param.result = fake
                            XposedEntry.log("getScanResults: injected ${fake.size} fake networks (запрошено ${networks.size})")
                        } catch (e: Throwable) {
                            XposedEntry.log("getScanResults hook error: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                })
            XposedEntry.log("✓ Hooked getScanResults()")
        } catch (e: Exception) {
            XposedEntry.log("✗ Failed to hook getScanResults: ${e.message}")
        }
    }

    private fun hookGetWifiState(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(WM, lpparam.classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (loadCachedNetworks().isNotEmpty()) {
                            param.result = WifiManager.WIFI_STATE_ENABLED
                        }
                    }
                })
            XposedEntry.log("✓ Hooked getWifiState()")
        } catch (e: Exception) {
            XposedEntry.log("✗ Failed to hook getWifiState: ${e.message}")
        }
    }

    private fun hookIsWifiEnabled(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(WM, lpparam.classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (loadCachedNetworks().isNotEmpty()) {
                            param.result = true
                        }
                    }
                })
            XposedEntry.log("✓ Hooked isWifiEnabled()")
        } catch (e: Exception) {
            XposedEntry.log("✗ Failed to hook isWifiEnabled: ${e.message}")
        }
    }

    private fun hookStartScan(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(WM, lpparam.classLoader, "startScan",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (loadCachedNetworks().isNotEmpty()) {
                            param.result = true
                        }
                    }
                })
            XposedEntry.log("✓ Hooked startScan()")
        } catch (e: Exception) {
            XposedEntry.log("⚠ startScan hook skipped: ${e.message}")
        }
    }

    private fun hookGetConnectionInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(WM, lpparam.classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val networks = loadCachedNetworks()
                            if (networks.isEmpty()) return

                            val connected = networks[getConnectedIndex().coerceIn(0, networks.size - 1)]
                            val wifiInfo = param.result ?: return

                            XposedHelpers.setObjectField(wifiInfo, "mBSSID", connected.bssid)
                            try {
                                XposedHelpers.setObjectField(wifiInfo, "mWifiSsid",
                                    createWifiSsid(connected.ssid, lpparam.classLoader))
                            } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(wifiInfo, "mFrequency", connected.frequency) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(wifiInfo, "mRssi", connected.level) } catch (_: Throwable) {}
                            try {
                                val stateClass = XposedHelpers.findClass(
                                    "android.net.wifi.SupplicantState", lpparam.classLoader)
                                val completed = XposedHelpers.getStaticObjectField(stateClass, "COMPLETED")
                                XposedHelpers.setObjectField(wifiInfo, "mSupplicantState", completed)
                            } catch (_: Throwable) {}

                            param.result = wifiInfo
                        } catch (e: Exception) {
                            XposedEntry.log("getConnectionInfo hook error: ${e.message}")
                        }
                    }
                })
            XposedEntry.log("✓ Hooked getConnectionInfo()")
        } catch (e: Exception) {
            XposedEntry.log("✗ Failed to hook getConnectionInfo: ${e.message}")
        }
    }

    // ===== Builders =====

    private fun buildScanResults(networks: List<FakeNetwork>): List<ScanResult> {
        return networks.mapNotNull { network ->
            try {
                val scanResult = instantiateScanResult()
                if (scanResult == null) {
                    XposedEntry.log("buildScanResults: не удалось создать экземпляр ScanResult ни одним из известных способов")
                    return@mapNotNull null
                }

                setFieldSafely(scanResult, "SSID", network.ssid)
                setFieldSafely(scanResult, "BSSID", network.bssid)
                setFieldSafely(scanResult, "frequency", network.frequency)
                setFieldSafely(scanResult, "level", network.level)
                setFieldSafely(scanResult, "capabilities", network.capabilities)
                setFieldSafely(scanResult, "timestamp", System.currentTimeMillis() * 1000)
                setFieldSafely(scanResult, "channelWidth", network.channelWidth)

                // Читаем поле назад — если реально не применилось, будет видно в логе
                val readBackSsid = try {
                    XposedHelpers.getObjectField(scanResult, "SSID")
                } catch (_: Throwable) { "<нет поля SSID>" }
                XposedEntry.log("buildScanResults: создан ScanResult ssid=$readBackSsid bssid=${network.bssid}")

                scanResult
            } catch (e: Throwable) {
                XposedEntry.log("Failed to create ScanResult for ${network.ssid}: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /**
     * Пытается создать пустой ScanResult несколькими способами, потому что
     * на разных версиях Android набор конструкторов отличается: иногда нет
     * настоящего no-arg конструктора, только приватные с параметрами.
     */
    private fun instantiateScanResult(): ScanResult? {
        val cls = ScanResult::class.java

        // Способ 1: честный no-arg конструктор
        try {
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            return ctor.newInstance()
        } catch (e: Throwable) {
            XposedEntry.log("instantiateScanResult: no-arg ctor не сработал: ${e.message}")
        }

        // Способ 2: Unsafe.allocateInstance — создаёт объект вообще без вызова конструктора
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val f = unsafeClass.getDeclaredField("theUnsafe")
            f.isAccessible = true
            val unsafe = f.get(null)
            val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
            @Suppress("UNCHECKED_CAST")
            return allocate.invoke(unsafe, cls) as ScanResult
        } catch (e: Throwable) {
            XposedEntry.log("instantiateScanResult: Unsafe.allocateInstance не сработал: ${e.message}")
        }

        // Способ 3: любой доступный конструктор с нулевыми/null аргументами
        try {
            val ctor = cls.declaredConstructors.minByOrNull { it.parameterCount } ?: return null
            ctor.isAccessible = true
            val args = ctor.parameterTypes.map { type ->
                when {
                    type == Int::class.javaPrimitiveType -> 0
                    type == Long::class.javaPrimitiveType -> 0L
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }.toTypedArray()
            @Suppress("UNCHECKED_CAST")
            return ctor.newInstance(*args) as ScanResult
        } catch (e: Throwable) {
            XposedEntry.log("instantiateScanResult: параметризованный ctor не сработал: ${e.message}")
        }

        return null
    }

    private fun setFieldSafely(target: Any, fieldName: String, value: Any) {
        try {
            when (value) {
                is Int -> XposedHelpers.setIntField(target, fieldName, value)
                is Long -> XposedHelpers.setLongField(target, fieldName, value)
                else -> XposedHelpers.setObjectField(target, fieldName, value)
            }
        } catch (e: Throwable) {
            XposedEntry.log("setFieldSafely: поле '$fieldName' не установлено (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun createWifiSsid(ssid: String, classLoader: ClassLoader): Any? {
        return try {
            val cls = XposedHelpers.findClass("android.net.wifi.WifiSsid", classLoader)
            try {
                XposedHelpers.callStaticMethod(cls, "fromUtf8Text", ssid as CharSequence)
            } catch (_: Throwable) {
                XposedHelpers.callStaticMethod(cls, "fromBytes", ssid.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Throwable) {
            XposedEntry.log("Failed to create WifiSsid: ${e.message}")
            null
        }
    }

    // ===== Data Loading =====

    /**
     * ПОРЯДОК ИЗМЕНЁН: сначала ContentProvider (не зависит от сломанного/
     * удаляемого в новых версиях LSPosed механизма new XSharedPreferences),
     * XSharedPreferences — как резерв на случай других устройств, где он
     * ещё работает.
     */
    private fun loadCachedNetworks(): List<FakeNetwork> {
        try {
            val ctx = de.robv.android.xposed.AndroidAppHelper.currentApplication()
            if (ctx != null) {
                val viaProvider = ConfigManager.loadNetworksViaProvider(ctx)
                if (viaProvider.isNotEmpty()) {
                    XposedEntry.log("loadCachedNetworks: получено через ContentProvider (${viaProvider.size})")
                    return viaProvider
                }
            } else {
                XposedEntry.log("loadCachedNetworks: AndroidAppHelper.currentApplication() == null")
            }
        } catch (e: Throwable) {
            XposedEntry.log("loadCachedNetworks: ContentProvider не сработал: ${e.javaClass.simpleName}: ${e.message}")
        }

        return try {
            XposedEntry.prefs.reload()
            val viaPrefs = ConfigManager.loadNetworksFromPrefs(XposedEntry.prefs)
            if (viaPrefs.isNotEmpty()) {
                XposedEntry.log("loadCachedNetworks: получено через XSharedPreferences (${viaPrefs.size})")
            }
            viaPrefs
        } catch (e: Exception) {
            XposedEntry.log("loadCachedNetworks: XSharedPreferences тоже не сработал: ${e.message}")
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
