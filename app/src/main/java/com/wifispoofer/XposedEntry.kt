package com.wifispoofer

import com.wifispoofer.config.ConfigManager
import com.wifispoofer.hooks.WifiInfoHook
import com.wifispoofer.hooks.WifiManagerHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Точка входа Xposed-модуля (LSPosed). */
class XposedEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "WifiSpoofer"
        const val PACKAGE = "com.wifispoofer"

        lateinit var prefs: XSharedPreferences
            private set

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == PACKAGE) return

        // XSharedPreferences больше не блокирует запуск: если конструктор
        // бросит исключение, просто работаем без него — основной канал
        // теперь ContentProvider (см. loadScope/hooks), не зависящий от
        // этого механизма LSPosed.
        try {
            prefs = XSharedPreferences(PACKAGE, ConfigManager.PREFS_NAME)
            if (!prefs.file.canRead()) {
                log("⚠ XSharedPreferences недоступен (${prefs.file.absolutePath}), используем ContentProvider")
            }
        } catch (e: Exception) {
            log("⚠ XSharedPreferences не создан: ${e.message}, используем ContentProvider")
        }

        val scope = loadScope()
        if (scope.isNotEmpty() && lpparam.packageName !in scope) return

        log("🎯 Hooking package: ${lpparam.packageName} (pid=${android.os.Process.myPid()}, process=${lpparam.processName})")

        try {
            WifiManagerHook.hook(lpparam)
            WifiInfoHook.hook(lpparam)
            log("✅ All hooks applied for ${lpparam.packageName}")
        } catch (e: Exception) {
            log("❌ Hook failed for ${lpparam.packageName}: ${e.message}")
        }
    }

    private fun loadScope(): Set<String> {
        try {
            val ctx = de.robv.android.xposed.AndroidAppHelper.currentApplication()
            if (ctx != null) {
                val viaProvider = ConfigManager.loadScopeViaProvider(ctx)
                if (viaProvider.isNotEmpty()) {
                    log("loadScope: получено через ContentProvider: $viaProvider")
                    return viaProvider
                }
            }
        } catch (e: Throwable) {
            log("loadScope: ContentProvider не сработал: ${e.message}")
        }

        return try {
            prefs.reload()
            val viaPrefs = ConfigManager.loadScopeFromPrefs(prefs)
            if (viaPrefs.isNotEmpty()) log("loadScope: получено через XSharedPreferences: $viaPrefs")
            viaPrefs
        } catch (e: Exception) {
            emptySet()
        }
    }
}
