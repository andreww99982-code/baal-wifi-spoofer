package com.wifispoofer

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.wifispoofer.adapter.AppItem
import com.wifispoofer.adapter.AppSelectorAdapter
import com.wifispoofer.config.ConfigManager
import com.wifispoofer.location.Coordinates
import com.wifispoofer.location.LocationResolver
import com.wifispoofer.model.FakeNetwork
import com.wifispoofer.service.WifiDataFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvNetworkCount: TextView
    private lateinit var tvNetworks: TextView
    private lateinit var btnSync: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var rgSource: RadioGroup
    private lateinit var llManual: LinearLayout
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var rvApps: RecyclerView

    private lateinit var appAdapter: AppSelectorAdapter
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            100
        )

        initViews()
        setupSourceSelector()
        setupAppSelector()
        loadCachedState()
        setupButtons()
        checkXSharedPrefsStatus()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvCoords = findViewById(R.id.tvCoords)
        tvNetworkCount = findViewById(R.id.tvNetworkCount)
        tvNetworks = findViewById(R.id.tvNetworks)
        btnSync = findViewById(R.id.btnSync)
        btnClear = findViewById(R.id.btnClear)
        rgSource = findViewById(R.id.rgSource)
        llManual = findViewById(R.id.llManual)
        etLat = findViewById(R.id.etLat)
        etLon = findViewById(R.id.etLon)
        rvApps = findViewById(R.id.rvApps)
    }

    private fun setupSourceSelector() {
        rgSource.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbIpApi -> {
                    llManual.visibility = View.GONE
                    ConfigManager.saveLocationSource(this, "ip")
                }
                R.id.rbGps -> {
                    llManual.visibility = View.GONE
                    ConfigManager.saveLocationSource(this, "gps")
                }
                R.id.rbManual -> {
                    llManual.visibility = View.VISIBLE
                    ConfigManager.saveLocationSource(this, "manual")
                }
            }
        }

        when (ConfigManager.getLocationSource(this)) {
            "ip" -> rgSource.check(R.id.rbIpApi)
            "gps" -> rgSource.check(R.id.rbGps)
            "manual" -> rgSource.check(R.id.rbManual)
        }
    }

    private fun setupAppSelector() {
        val apps = loadInstalledApps()
        val savedScope = ConfigManager.loadScope(this)

        appAdapter = AppSelectorAdapter(apps) { selected ->
            ConfigManager.saveScope(this, selected)
        }
        appAdapter.setSelectedPackages(savedScope)

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = appAdapter
    }

    private fun loadInstalledApps(): MutableList<AppItem> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { AppItem(it.packageName, pm.getApplicationLabel(it).toString()) }
            .toMutableList()
    }

    private fun loadCachedState() {
        updateNetworkDisplay(ConfigManager.loadNetworks(this))
    }

    private fun setupButtons() {
        btnSync.setOnClickListener { startSync() }
        btnClear.setOnClickListener { clearCache() }
    }

    private fun startSync() {
        btnSync.isEnabled = false
        tvStatus.text = getString(R.string.status_fetching_location)

        mainScope.launch {
            try {
                val coords = resolveLocation()
                tvCoords.text = "Координаты: ${coords.lat}, ${coords.lon}"
                tvStatus.text = getString(R.string.status_fetching_wifi)

                val networks = WifiDataFetcher.fetchNetworks(coords, count = 8)

                ConfigManager.saveNetworks(this@MainActivity, networks)
                ConfigManager.saveConnectedIndex(this@MainActivity, 0)

                updateNetworkDisplay(networks)
                tvStatus.text = getString(R.string.status_done, networks.size)
            } catch (e: Exception) {
                tvStatus.text = getString(R.string.status_error, e.message ?: "Unknown")
            } finally {
                btnSync.isEnabled = true
            }
        }
    }

    private suspend fun resolveLocation(): Coordinates {
        return when (ConfigManager.getLocationSource(this)) {
            "gps" -> LocationResolver.getByGps(this)
            "manual" -> {
                val lat = etLat.text.toString().toDoubleOrNull()
                    ?: throw Exception("Введите Latitude")
                val lon = etLon.text.toString().toDoubleOrNull()
                    ?: throw Exception("Введите Longitude")
                ConfigManager.saveManualCoords(this, lat, lon)
                Coordinates(lat, lon)
            }
            else -> LocationResolver.getByIpApi()
        }
    }

    private fun updateNetworkDisplay(networks: List<FakeNetwork>) {
        tvNetworkCount.text = "Сетей в кэше: ${networks.size}"

        if (networks.isEmpty()) {
            tvNetworks.text = "Нет данных"
            return
        }

        tvNetworks.text = networks.mapIndexed { i, net ->
            val connected = if (i == 0) " ← connected" else ""
            "📶 ${net.ssid}\n   ${net.bssid} | ${net.level}dBm$connected"
        }.joinToString("\n\n")
    }

    private fun clearCache() {
        ConfigManager.clearNetworks(this)
        ConfigManager.saveScope(this, emptySet())
        updateNetworkDisplay(emptyList())
        tvStatus.text = getString(R.string.status_idle)
        tvCoords.text = "Координаты: —"
        appAdapter.setSelectedPackages(emptySet())
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    /**
     * Пишет пробное значение и сразу проверяет права доступа к файлу
     * prefs — если LSPosed не подключил MODE_WORLD_READABLE, файл будет
     * недоступен на чтение другим процессам, и хуки увидят пустой кэш.
     */
    @Suppress("DEPRECATION")
    private fun checkXSharedPrefsStatus() {
        try {
            val pref = ConfigManager.getPrefs(this)
            pref.edit().putLong("__probe", System.currentTimeMillis()).apply()

            val prefsFile = java.io.File(
                java.io.File(applicationInfo.dataDir, "shared_prefs"),
                "${ConfigManager.PREFS_NAME}.xml"
            )
            val worldReadable = prefsFile.exists() && prefsFile.canRead() &&
                (prefsFile.setReadable(true, false).let { prefsFile.canRead() })
            val perms = if (prefsFile.exists()) {
                (if (prefsFile.canRead()) "r" else "-") + (if (prefsFile.canWrite()) "w" else "-")
            } else "нет файла"

            android.util.Log.i(
                "WifiSpoofer",
                "prefs file: ${prefsFile.absolutePath}, exists=${prefsFile.exists()}, perms=$perms"
            )

            if (!prefsFile.exists()) {
                Toast.makeText(
                    this,
                    "⚠ Файл настроек не создан — модуль в LSPosed включён?",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("WifiSpoofer", "checkXSharedPrefsStatus error: ${e.message}")
        }
    }
}
