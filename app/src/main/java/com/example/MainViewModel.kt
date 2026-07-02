package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dev7.lib.v2ray.V2rayController
import dev.dev7.lib.v2ray.utils.V2rayConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // 🔥 Raw Config URL
    private val RAW_CONFIG_URL = "https://raw.githubusercontent.com/Galaxy-Tunnel/ONE-AGENT/refs/heads/main/servers.txt"

    private val _language = MutableStateFlow("my")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _textSize = MutableStateFlow("medium")
    val textSize: StateFlow<String> = _textSize.asStateFlow()

    private val _currentScreen = MutableStateFlow("servers")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    private val _connectionLogs = MutableStateFlow<List<String>>(emptyList())
    val connectionLogs: StateFlow<List<String>> = _connectionLogs.asStateFlow()

    fun addLog(message: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
        val timestamp = sdf.format(java.util.Date())
        _connectionLogs.value = _connectionLogs.value + "[$timestamp] $message"
    }

    fun clearLogs() {
        _connectionLogs.value = emptyList()
        addLog("Diagnostics cleared.")
    }

    private val _vpnState = MutableStateFlow("disconnected")
    val vpnState: StateFlow<String> = _vpnState.asStateFlow()

    private val _selectedServerIndex = MutableStateFlow(0)
    val selectedServerIndex: StateFlow<Int> = _selectedServerIndex.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _dlSpeed = MutableStateFlow("0.0")
    val dlSpeed: StateFlow<String> = _dlSpeed.asStateFlow()

    private val _ulSpeed = MutableStateFlow("0.0")
    val ulSpeed: StateFlow<String> = _ulSpeed.asStateFlow()

    private val _dlTraffic = MutableStateFlow("0.0")
    val dlTraffic: StateFlow<String> = _dlTraffic.asStateFlow()

    private val _ulTraffic = MutableStateFlow("0.0")
    val ulTraffic: StateFlow<String> = _ulTraffic.asStateFlow()

    private val _servers = MutableStateFlow<List<VpnNode>>(emptyList())
    val servers: StateFlow<List<VpnNode>> = _servers.asStateFlow()

    private var metricsJob: Job? = null

    init {
        val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
        _language.value = prefs.getString("lang", "my") ?: "my"
        _isDarkMode.value = prefs.getBoolean("dark_mode", true)
        _textSize.value = prefs.getString("font_size", "medium") ?: "medium"

        addLog("Galaxy Tunnel Initializing...")
        addLog("Raw Config URL: $RAW_CONFIG_URL")
        addLog("Default Language: ${_language.value}")
        addLog("Dark Mode: ${_isDarkMode.value}")

        // 🔥 Raw link ကနေ config fetch
        fetchConfigsFromRawLink()
    }

    // 🔥🔥🔥 RAW LINK FETCH - အဓိက function
    private fun fetchConfigsFromRawLink() {
        viewModelScope.launch {
            try {
                addLog("Fetching remote configs from GitHub...")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        if (language.value == "my") "Server list ရယူနေပါသည်..." else "Loading servers...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                val content = withContext(Dispatchers.IO) {
                    val url = URL(RAW_CONFIG_URL)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "Galaxy-Tunnel/1.0")
                    conn.setRequestProperty("Accept", "text/plain,application/json")
                    conn.inputStream.bufferedReader().use { it.readText() }
                }

                addLog("Downloaded ${content.length} bytes from remote")

                // Content type စစ်
                when {
                    content.trim().startsWith("[") -> {
                        // JSON Array format
                        addLog("Detected JSON format")
                        parseJsonConfigs(content)
                    }
                    content.trim().startsWith("vless://") ||
                    content.trim().startsWith("trojan://") ||
                    content.trim().startsWith("vmess://") ||
                    content.trim().startsWith("ss://") -> {
                        // Plain text URI list (one per line)
                        addLog("Detected plain text URI list")
                        parsePlainTextConfigs(content)
                    }
                    else -> {
                        // Base64 encoded subscription
                        addLog("Attempting Base64 decode...")
                        try {
                            val decoded = android.util.Base64.decode(
                                content.trim().replace("\n", "").replace("\r", ""),
                                android.util.Base64.DEFAULT
                            )
                            val decodedStr = String(decoded)
                            parsePlainTextConfigs(decodedStr)
                        } catch (e: Exception) {
                            addLog("Base64 decode failed, trying fallback...")
                            loadEmbeddedConfigs()
                        }
                    }
                }

            } catch (e: Exception) {
                addLog("Remote fetch failed: ${e.message}")
                addLog("Loading fallback embedded configs...")
                loadEmbeddedConfigs()
            }
        }
    }

    // 🔥 JSON Array format parse
    private fun parseJsonConfigs(json: String) {
        try {
            val jsonArray = org.json.JSONArray(json)
            val nodes = mutableListOf<VpnNode>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                nodes.add(
                    VpnNode(
                        id = obj.optInt("id", i + 1),
                        name = obj.optString("name", "Server ${i + 1}"),
                        description = obj.optString("description", ""),
                        location = obj.optString("location", "Unknown"),
                        vlessConfig = obj.getString("vlessConfig"),
                        pingUrl = obj.optString("pingUrl", extractHost(obj.getString("vlessConfig")))
                    )
                )
            }
            
            _servers.value = nodes
            addLog("Loaded ${nodes.size} servers from JSON")
            triggerAllPings()
            
        } catch (e: Exception) {
            addLog("JSON parse error: ${e.message}")
            loadEmbeddedConfigs()
        }
    }

    // 🔥 Plain text URI list parse (one URI per line)
    private fun parsePlainTextConfigs(text: String) {
        try {
            val lines = text.split("\n", "\r")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { 
                    it.startsWith("vless://") ||
                    it.startsWith("trojan://") ||
                    it.startsWith("vmess://") ||
                    it.startsWith("ss://")
                }

            val nodes = lines.mapIndexed { index, uri ->
                val name = extractNameFromUri(uri) ?: "Server ${index + 1}"
                VpnNode(
                    id = index + 1,
                    name = name,
                    description = "Remote Config",
                    location = extractHost(uri) ?: "Unknown",
                    vlessConfig = uri,
                    pingUrl = extractHost(uri)?.let { "https://$it" } ?: ""
                )
            }

            _servers.value = nodes
            addLog("Loaded ${nodes.size} servers from plain text")
            triggerAllPings()

        } catch (e: Exception) {
            addLog("Plain text parse error: ${e.message}")
            loadEmbeddedConfigs()
        }
    }

    // 🔥 URI ကနေ name ထုတ်
    private fun extractNameFromUri(uri: String): String? {
        // vless://uuid@host:port?params#NAME
        // trojan://pass@host:port?params#NAME
        val hashIndex = uri.lastIndexOf("#")
        return if (hashIndex != -1) {
            java.net.URLDecoder.decode(uri.substring(hashIndex + 1), "UTF-8")
        } else null
    }

    // 🔥 URI ကနေ host ထုတ်
    private fun extractHost(uri: String): String? {
        try {
            // vless://uuid@host:port...
            val atIndex = uri.indexOf("@")
            if (atIndex != -1) {
                val afterAt = uri.substring(atIndex + 1)
                val colonIndex = afterAt.indexOf(":")
                val questionIndex = afterAt.indexOf("?")
                val endIndex = if (questionIndex != -1) questionIndex else colonIndex
                return if (colonIndex != -1) afterAt.substring(0, colonIndex) else null
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    // 🔥 Fallback - embedded configs (raw link မရရင်)
    private fun loadEmbeddedConfigs() {
        val baseline = listOf(
            VpnNode(
                id = 1,
                name = "SERVER 01 (Galaxy Tunnel VLESS)",
                description = "Low Ping - Stable Connection (WS-TLS)",
                location = "Singapore / General",
                vlessConfig = "vless://19658493-1494-4766-994d-eb2801088064@galaxy.keyjansama.workers.dev:443?path=ed%3D%2F9000&security=tls&encryption=none&host=galaxy.keyjansama.workers.dev&fp=chrome&type=ws&sni=galaxy.keyjansama.workers.dev#Galaxy-Tunnelr%F0%9F%9A%80",
                pingUrl = "https://galaxy.keyjansama.workers.dev"
            ),
            VpnNode(
                id = 2,
                name = "SERVER 02 (Galaxy Planet WS)",
                description = "High Speed - Premium ALPN (WS-TLS)",
                location = "USA / Planet 5",
                vlessConfig = "vless://7777489c-9d5f-407d-81e9-3467cff92134@galaxy-5.gaxlayplanet.workers.dev:443?path=ed%3D%2F2680&security=tls&alpn=h3%2Ch2%2Chttp%2F1.1&encryption=none&host=galaxy-5.gaxlayplanet.workers.dev&fp=random&type=ws&sni=galaxy-5.gaxlayplanet.workers.dev#Galaxy-Tunnel",
                pingUrl = "https://galaxy-5.gaxlayplanet.workers.dev"
            ),
            VpnNode(
                id = 3,
                name = "SERVER 03 (Galaxy Sub CDN)",
                description = "Host: sub.galaxytunnel2026.workers.dev",
                location = "Global CDN",
                vlessConfig = "vless://8221a740-8218-4775-ab45-0bab948285ec@sub.galaxytunnel2026.workers.dev:443?security=tls&encryption=none&host=sub.galaxytunnel2026.workers.dev&type=ws&sni=sub.galaxytunnel2026.workers.dev#Galaxy-Tunnel",
                pingUrl = "https://sub.galaxytunnel2026.workers.dev"
            ),
            VpnNode(
                id = 4,
                name = "SERVER 04 (Galaxy Coca Node)",
                description = "Premium High Speed Node (WS-TLS)",
                location = "Multi-Region / Coca",
                vlessConfig = "vless://26fe5cdd-e772-4238-8adc-9bf53d4781fa@coca.nobless.workers.dev:443?path=%2F&security=tls&encryption=none&host=coca.nobless.workers.dev&type=ws&sni=coca.nobless.workers.dev#Galaxy-Tunnel",
                pingUrl = "https://coca.nobless.workers.dev"
            ),
            VpnNode(
                id = 5,
                name = "SERVER 05 (Clone Yatokami Trojan)",
                description = "Trojan Protocol - WS Transport",
                location = "Japan / Clone",
                vlessConfig = "trojan://5a733fcb-f724-45d5-9f6f-9cd96d812409@clone.yatokami.workers.dev:443?path=%2F&security=tls&alpn=h3%2Ch2%2Chttp%2F1.1&host=clone.yatokami.workers.dev&fp=chrome&type=ws&sni=clone.yatokami.workers.dev#clone",
                pingUrl = "https://clone.yatokami.workers.dev"
            ),
            VpnNode(
                id = 6,
                name = "SERVER 06 (Galaxy Tunnel Trojan Node)",
                description = "Trojan Protocol - Cloudflare Pages Edge",
                location = "Germany / Pages 2",
                vlessConfig = "trojan://18616960-5953-490c-a717-5462c9c63517@galaxy-2.pages.dev:443?path=%2F&security=tls&host=galaxy-2.pages.dev&fp=random&type=ws&sni=galaxy-2.pages.dev#Galaxy-Tunnel%E2%9A%A1",
                pingUrl = "https://galaxy-2.pages.dev"
            ),
            VpnNode(
                id = 7,
                name = "SERVER 07 (Galaxy Trojan Empire)",
                description = "Trojan High Speed - Z-Empire",
                location = "UK / Empire 3",
                vlessConfig = "trojan://3413d540-942c-4763-ad39-3854a3621a2e@galaxy-3.z-empire.workers.dev:443?path=%2F&security=tls&alpn=h3&host=galaxy-3.z-empire.workers.dev&type=ws&sni=galaxy-3.z-empire.workers.dev#Galaxy-Tunnel%2FTrojan%F0%9F%86%99",
                pingUrl = "https://galaxy-3.z-empire.workers.dev"
            )
        )

        _servers.value = baseline
        addLog("Fallback loaded: ${baseline.size} embedded servers")
        triggerAllPings()
    }

    // 🔥 Manual refresh function
    fun refreshConfigs() {
        addLog("Manual refresh triggered...")
        fetchConfigsFromRawLink()
    }

    fun updateV2rayStats(
        state: String,
        duration: String,
        dlSpeed: String,
        ulSpeed: String,
        dlTraffic: String,
        ulTraffic: String
    ) {
        _vpnState.value = state.lowercase()

        val parts = duration.split(":")
        if (parts.size == 3) {
            _durationSeconds.value = parts[0].toInt() * 3600 +
                    parts[1].toInt() * 60 +
                    parts[2].toInt()
        }

        _dlSpeed.value = dlSpeed
        _ulSpeed.value = ulSpeed
        _dlTraffic.value = dlTraffic
        _ulTraffic.value = ulTraffic

        addLog("V2ray state: $state | DL: $dlSpeed | UL: $ulSpeed")
    }

    fun importConfig(configUrl: String): Boolean {
        val trimmed = configUrl.trim()
        addLog("Attempting config import: ${if (trimmed.length > 30) trimmed.take(30) + "..." else trimmed}")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            addLog("Identified URL link input. Fetching configuration subscription...")
            fetchSubscription(trimmed)
            return true
        }

        if (!trimmed.startsWith("vless://") &&
            !trimmed.startsWith("vmess://") &&
            !trimmed.startsWith("trojan://") &&
            !trimmed.startsWith("ss://")) {
            addLog("Import failed: Invalid Configuration protocol schema!")
            return false
        }

        val parsedNode = parseConfigUrl(trimmed) ?: run {
            addLog("Import failed: Parsing error of node payload!")
            return false
        }

        val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
        val customConfigsSet = prefs.getStringSet("custom_configs", emptySet())?.toMutableSet() ?: mutableSetOf()
        customConfigsSet.add(trimmed)
        prefs.edit().putStringSet("custom_configs", customConfigsSet).apply()

        val currentList = _servers.value.toMutableList()
        currentList.add(parsedNode)
        _servers.value = currentList
        addLog("Imported Node successful! [${parsedNode.name}]")

        val newIndex = currentList.size - 1
        selectServerIndex(newIndex)

        viewModelScope.launch {
            updateNodePingResult(parsedNode.id, null)
            val result = runPingTest(parsedNode.pingUrl)
            updateNodePingResult(parsedNode.id, result)
        }

        return true
    }

    private fun fetchSubscription(urlStr: String) {
        viewModelScope.launch {
            try {
                addLog("Starting network subscription download from: $urlStr")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        if (language.value == "my") "Subscription ရယူနေပါသည်..." else "Fetching subscription...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                val content = withContext(Dispatchers.IO) {
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.inputStream.bufferedReader().use { it.readText() }
                }

                addLog("Downloaded raw subscription payload (length: ${content.length} characters).")

                val decoded = try {
                    val rawDec = content.trim().replace("\r", "").replace("\n", "").replace(" ", "")
                    val base64Decoded = android.util.Base64.decode(rawDec, android.util.Base64.DEFAULT)
                    addLog("Base64 subscription payload decoded successfully.")
                    String(base64Decoded)
                } catch (e: Exception) {
                    addLog("Payload is plain text configuration stream list.")
                    content
                }

                val lines = decoded.split("\n", "\r")
                var importCount = 0
                val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
                val customConfigsSet = prefs.getStringSet("custom_configs", emptySet())?.toMutableSet() ?: mutableSetOf()
                val currentList = _servers.value.toMutableList()

                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && (
                        trimmed.startsWith("vless://") ||
                        trimmed.startsWith("vmess://") ||
                        trimmed.startsWith("trojan://") ||
                        trimmed.startsWith("ss://")
                    )) {
                        parseConfigUrl(trimmed)?.let { node ->
                            customConfigsSet.add(trimmed)
                            currentList.add(node)
                            importCount++
                        }
                    }
                }

                if (importCount > 0) {
                    prefs.edit().putStringSet("custom_configs", customConfigsSet).apply()
                    _servers.value = currentList
                    addLog("Subscription import successful! Added $importCount nodes.")
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            if (language.value == "my") "Subscription ထည့်သွင်းပြီးပါပြီ ($importCount server)" else "Subscription imported ($importCount servers)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    triggerAllPings()
                } else {
                    addLog("Subscription import failed: No valid configs found.")
                }

            } catch (e: Exception) {
                addLog("Subscription fetch error: ${e.message}")
            }
        }
    }

    fun setLanguage(lang: String) {
        _language.value = lang
        val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("lang", lang).apply()
        addLog("Language switched to: ${lang.uppercase()}")
    }

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dark_mode", enabled).apply()
        addLog("Dark mode: ${if (enabled) "ON" else "OFF"}")
    }

    fun setTextSize(size: String) {
        _textSize.value = size
        val prefs = context.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("font_size", size).apply()
        addLog("Text size: ${size.uppercase()}")
    }

    fun setCurrentScreen(screen: String) {
        _currentScreen.value = screen
    }

    fun selectServerIndex(index: Int) {
        if (index in _servers.value.indices) {
            _selectedServerIndex.value = index
            val node = _servers.value[index]
            addLog("Selected server: [${node.name}]")
            triggerVibrate()
        }
    }

    private fun triggerVibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun toggleVpn() {
        val activeNode = if (_selectedServerIndex.value in _servers.value.indices) {
            _servers.value[_selectedServerIndex.value]
        } else null

        if (activeNode == null) {
            addLog("Connection aborted: No active node selected.")
            return
        }

        val currentState = V2rayController.getConnectionState()

        if (currentState == V2rayConstants.CONNECTION_STATES.DISCONNECTED) {
            addLog("Initiating V2ray tunnel...")
            addLog("Server: ${activeNode.name}")

            V2rayController.startV2ray(
                context,
                activeNode.name,
                activeNode.vlessConfig,
                null
            )

            _vpnState.value = "connecting"
            addLog("V2ray connection starting...")

        } else {
            addLog("Stopping V2ray...")
            V2rayController.stopV2ray(context)
            _vpnState.value = "disconnected"
            _dlSpeed.value = "0.0"
            _ulSpeed.value = "0.0"
            addLog("V2ray connection stopped.")
        }
    }

    fun triggerAllPings() {
        viewModelScope.launch {
            _servers.value = _servers.value.map {
                it.copy(isChecking = true, latencyMs = null, isOffline = false)
            }

            _servers.value.forEach { node ->
                viewModelScope.launch {
                    val delay = withContext(Dispatchers.IO) {
                        V2rayController.getV2rayServerDelay(node.vlessConfig)
                    }
                    updateNodePingResult(node.id, delay.toInt())
                }
            }
        }
    }

    private fun updateNodePingResult(nodeId: Int, delayMs: Int?) {
        _servers.value = _servers.value.map { node ->
            if (node.id == nodeId) {
                if (delayMs == null || delayMs <= 0 || delayMs > 5000) {
                    node.copy(isChecking = false, latencyMs = null, isOffline = true)
                } else {
                    node.copy(isChecking = false, latencyMs = delayMs, isOffline = false)
                }
            } else {
                node
            }
        }
    }

    private fun runPingTest(urlString: String): Int {
        if (urlString.isBlank()) return -1
        return try {
            val elapsed = measureTimeMillis {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                conn.connect()
                conn.responseCode
            }
            elapsed.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun parseConfigUrl(configUrl: String): VpnNode? {
        return try {
            val protocol = when {
                configUrl.startsWith("vless://") -> "VLESS"
                configUrl.startsWith("vmess://") -> "VMess"
                configUrl.startsWith("trojan://") -> "Trojan"
                configUrl.startsWith("ss://") -> "Shadowsocks"
                else -> "Unknown"
            }

            val name = extractNameFromUri(configUrl) ?: "$protocol Node"
            val host = extractHost(configUrl) ?: "unknown"

            VpnNode(
                id = System.currentTimeMillis().toInt(),
                name = name,
                description = "$protocol Protocol",
                location = host,
                vlessConfig = configUrl,
                pingUrl = "https://$host"
            )
        } catch (e: Exception) {
            null
        }
    }
}
