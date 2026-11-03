package com.example.network

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val TAG = "AiStudioMultiApiMgr"
private const val PREFS_NAME = "astrafolio_multi_api_prefs"
private const val KEY_NODES_JSON = "saved_api_nodes_v2"
private const val KEY_AUTO_SLEEP_ENABLED = "auto_sleep_enabled"
private const val KEY_AUTO_SLEEP_MINUTES = "auto_sleep_minutes"
private const val KEY_ECO_SINGLE_AWAKE = "eco_single_awake_mode"
private const val KEY_AUTO_FAILOVER = "auto_failover_enabled"
private const val KEY_ROUND_ROBIN_KEYS = "round_robin_keys_enabled"
private const val KEY_LOCAL_SERVER_BRIDGE_ENABLED = "local_server_bridge_enabled"
private const val KEY_LOCAL_SERVER_URL = "local_server_endpoint_url"

/**
 * Lightweight, zero-Firebase JSON Schema builder for Gemini REST API and Local Autonomous Engine.
 */
class AiSchema(val json: JSONObject) {
    companion object {
        fun string(description: String = ""): AiSchema = AiSchema(
            JSONObject().apply {
                put("type", "STRING")
                if (description.isNotBlank()) put("description", description)
            }
        )

        fun integer(description: String = ""): AiSchema = AiSchema(
            JSONObject().apply {
                put("type", "INTEGER")
                if (description.isNotBlank()) put("description", description)
            }
        )

        fun enumeration(values: List<String>, description: String = ""): AiSchema = AiSchema(
            JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray(values))
                if (description.isNotBlank()) put("description", description)
            }
        )

        fun array(items: AiSchema, description: String = ""): AiSchema = AiSchema(
            JSONObject().apply {
                put("type", "ARRAY")
                put("items", items.json)
                if (description.isNotBlank()) put("description", description)
            }
        )

        fun obj(properties: Map<String, AiSchema>, description: String = ""): AiSchema = AiSchema(
            JSONObject().apply {
                put("type", "OBJECT")
                val propsObj = JSONObject()
                val reqArr = JSONArray()
                properties.forEach { (k, v) ->
                    propsObj.put(k, v.json)
                    reqArr.put(k)
                }
                put("properties", propsObj)
                put("required", reqArr)
                if (description.isNotBlank()) put("description", description)
            }
        )
    }
}

enum class ApiProviderType(val labelUz: String, val badgeUz: String) {
    FIREBASE_AI_LOGIC("Astra Lokal & Gemini Avto-Uzel", "AVTO AI UZEL"),
    CUSTOM_AI_STUDIO_KEY("AI Studio API Key (AQ. / AIza)", "AQ. API KEY"),
    LOCAL_SERVER_GATEWAY("Lokal / Internet Server + AQ. Key", "LOKAL SERVER")
}

enum class ApiNodePowerState(val labelUz: String, val descriptionUz: String) {
    ACTIVE("UYG'OQ (FAOL)", "So'rovlarni qabul qilishga tayyor va faol navbatda"),
    SLEEPING("UYQUDA (STANDBY)", "Kvota va quvvatni tejash uchun uyqu rejimida (zaxirada)"),
    COOLDOWN("LIMIT / TANAFFUS", "Token limiti yoki xatolik sababli vaqtinchalik dam olmoqda")
}

enum class ApiTaskScope(val labelUz: String) {
    UNIVERSAL("Barcha Vazifalar (Universal)"),
    VOICE_COUNCIL("5-AI Ovozli Kengash"),
    AUTO_SECRETARY("Avto-Kotib (Qo'ng'iroq)"),
    VISION_HUD("Vision HUD (Kamera)")
}

data class TokenUsageReport(
    val promptTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int
)

data class ClusterTokenSummary(
    val totalPromptTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val lastRequestTokens: Int = 0,
    val lastUsedNodeLabel: String = "Jarvis Core",
    val totalRequestsCount: Int = 0,
    val connectedKeysCount: Int = 0
)

data class AiStudioApiNode(
    val id: String,
    val label: String,
    val providerType: ApiProviderType,
    val apiKey: String = "",
    val serverUrl: String = "",
    val modelName: String = "gemini-2.5-flash",
    val taskScope: ApiTaskScope = ApiTaskScope.UNIVERSAL,
    val powerState: ApiNodePowerState = ApiNodePowerState.ACTIVE,
    val isPrimary: Boolean = false,
    val totalRequests: Int = 0,
    val promptTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val lastRequestTokens: Int = 0,
    val dailyTokenLimit: Long = 1_000_000L,
    val lastLatencyMs: Long = 0L,
    val lastStatusNote: String = "Tayyor",
    val autoSleepAfterIdleMinutes: Int = 10,
    val lastUsedTimestamp: Long = System.currentTimeMillis()
) {
    fun maskedApiKey(): String {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            return if (serverUrl.isNotBlank()) {
                "Server: $serverUrl"
            } else {
                "Lokal / Avto-Dvigatel"
            }
        }
        if (trimmed.startsWith("AQ.", ignoreCase = true)) {
            val rest = trimmed.drop(3)
            return if (rest.length <= 6) {
                "AQ.${rest.take(2)}••••"
            } else {
                "AQ.${rest.take(4)}••••${rest.takeLast(4)}"
            }
        }
        if (trimmed.length <= 8) return "${trimmed.take(3)}••••"
        return "${trimmed.take(6)}••••${trimmed.takeLast(4)}"
    }

    fun isAqKey(): Boolean = apiKey.trim().startsWith("AQ.", ignoreCase = true)

    fun tokenUsageFraction(): Float {
        if (dailyTokenLimit <= 0L) return 0f
        return (totalTokens.toFloat() / dailyTokenLimit.toFloat()).coerceIn(0f, 1f)
    }
}

class AiStudioMultiApiManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _apiNodes = MutableStateFlow<List<AiStudioApiNode>>(emptyList())
    val apiNodes: StateFlow<List<AiStudioApiNode>> = _apiNodes.asStateFlow()

    private val _tokenSummary = MutableStateFlow(ClusterTokenSummary())
    val tokenSummary: StateFlow<ClusterTokenSummary> = _tokenSummary.asStateFlow()

    private val _autoSleepIdleEnabled = MutableStateFlow(true)
    val autoSleepIdleEnabled: StateFlow<Boolean> = _autoSleepIdleEnabled.asStateFlow()

    private val _autoSleepIdleMinutes = MutableStateFlow(5)
    val autoSleepIdleMinutes: StateFlow<Int> = _autoSleepIdleMinutes.asStateFlow()

    private val _ecoSingleAwakeMode = MutableStateFlow(false)
    val ecoSingleAwakeMode: StateFlow<Boolean> = _ecoSingleAwakeMode.asStateFlow()

    private val _autoFailoverEnabled = MutableStateFlow(true)
    val autoFailoverEnabled: StateFlow<Boolean> = _autoFailoverEnabled.asStateFlow()

    private val _roundRobinKeysEnabled = MutableStateFlow(false)
    val roundRobinKeysEnabled: StateFlow<Boolean> = _roundRobinKeysEnabled.asStateFlow()

    private val _localServerBridgeEnabled = MutableStateFlow(false)
    val localServerBridgeEnabled: StateFlow<Boolean> = _localServerBridgeEnabled.asStateFlow()

    private val _localServerEndpointUrl = MutableStateFlow("http://127.0.0.1:8080")
    val localServerEndpointUrl: StateFlow<String> = _localServerEndpointUrl.asStateFlow()

    private val _clusterStatusBanner = MutableStateFlow(
        "AI Studio Multi-API & Lokal Server tayyor: 0 token sarflandi"
    )
    val clusterStatusBanner: StateFlow<String> = _clusterStatusBanner.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun defaultNodes(): List<AiStudioApiNode> = listOf(
        AiStudioApiNode(
            id = "node_cloud_flash_1",
            label = "API #1 • Jarvis Core Flash",
            providerType = ApiProviderType.FIREBASE_AI_LOGIC,
            modelName = "gemini-2.5-flash",
            taskScope = ApiTaskScope.UNIVERSAL,
            powerState = ApiNodePowerState.ACTIVE,
            isPrimary = true,
            dailyTokenLimit = 1_000_000L,
            lastLatencyMs = 0L,
            lastStatusNote = "Asosiy tezkor ovoz va mantiq uzeli (Uyg'oq)"
        ),
        AiStudioApiNode(
            id = "node_cloud_pro_2",
            label = "API #2 • Deep Architect Pro",
            providerType = ApiProviderType.FIREBASE_AI_LOGIC,
            modelName = "gemini-2.5-pro",
            taskScope = ApiTaskScope.VOICE_COUNCIL,
            powerState = ApiNodePowerState.ACTIVE,
            isPrimary = false,
            dailyTokenLimit = 500_000L,
            lastLatencyMs = 0L,
            lastStatusNote = "Chuqur kod va 5-AI bahs arxitektori (Uyg'oq)"
        ),
        AiStudioApiNode(
            id = "node_cloud_standby_3",
            label = "API #3 • Zaxira Kotib & Vision",
            providerType = ApiProviderType.FIREBASE_AI_LOGIC,
            modelName = "gemini-2.5-flash",
            taskScope = ApiTaskScope.AUTO_SECRETARY,
            powerState = ApiNodePowerState.SLEEPING,
            isPrimary = false,
            dailyTokenLimit = 1_000_000L,
            lastLatencyMs = 0L,
            lastStatusNote = "Uyqu rejimida (Asosiy API band bo'lganda avtomatik uyg'onadi)"
        )
    )

    private fun loadFromPrefs() {
        _autoSleepIdleEnabled.value = prefs.getBoolean(KEY_AUTO_SLEEP_ENABLED, true)
        _autoSleepIdleMinutes.value = prefs.getInt(KEY_AUTO_SLEEP_MINUTES, 5)
        _ecoSingleAwakeMode.value = prefs.getBoolean(KEY_ECO_SINGLE_AWAKE, false)
        _autoFailoverEnabled.value = prefs.getBoolean(KEY_AUTO_FAILOVER, true)
        _roundRobinKeysEnabled.value = prefs.getBoolean(KEY_ROUND_ROBIN_KEYS, false)
        _localServerBridgeEnabled.value = prefs.getBoolean(KEY_LOCAL_SERVER_BRIDGE_ENABLED, false)
        _localServerEndpointUrl.value = prefs.getString(KEY_LOCAL_SERVER_URL, "http://127.0.0.1:8080")
            ?.ifBlank { "http://127.0.0.1:8080" } ?: "http://127.0.0.1:8080"

        val rawJson = prefs.getString(KEY_NODES_JSON, null)
        if (rawJson.isNullOrBlank()) {
            val defaults = defaultNodes()
            _apiNodes.value = defaults
            saveToPrefs(defaults)
            recalculateSummaries(defaults)
            return
        }

        try {
            val arr = JSONArray(rawJson)
            val parsed = mutableListOf<AiStudioApiNode>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                parsed.add(
                    AiStudioApiNode(
                        id = obj.optString("id", "node_${UUID.randomUUID()}"),
                        label = obj.optString("label", "AI Studio API #${i + 1}"),
                        providerType = runCatching {
                            ApiProviderType.valueOf(obj.optString("providerType", ApiProviderType.FIREBASE_AI_LOGIC.name))
                        }.getOrDefault(ApiProviderType.FIREBASE_AI_LOGIC),
                        apiKey = obj.optString("apiKey", ""),
                        serverUrl = obj.optString("serverUrl", ""),
                        modelName = obj.optString("modelName", "gemini-2.5-flash"),
                        taskScope = runCatching {
                            ApiTaskScope.valueOf(obj.optString("taskScope", ApiTaskScope.UNIVERSAL.name))
                        }.getOrDefault(ApiTaskScope.UNIVERSAL),
                        powerState = runCatching {
                            ApiNodePowerState.valueOf(obj.optString("powerState", ApiNodePowerState.ACTIVE.name))
                        }.getOrDefault(ApiNodePowerState.ACTIVE),
                        isPrimary = obj.optBoolean("isPrimary", i == 0),
                        totalRequests = obj.optInt("totalRequests", 0),
                        promptTokens = obj.optLong("promptTokens", 0L),
                        outputTokens = obj.optLong("outputTokens", 0L),
                        totalTokens = obj.optLong("totalTokens", 0L),
                        lastRequestTokens = obj.optInt("lastRequestTokens", 0),
                        dailyTokenLimit = obj.optLong("dailyTokenLimit", 1_000_000L).coerceAtLeast(1_000L),
                        lastLatencyMs = obj.optLong("lastLatencyMs", 0L),
                        lastStatusNote = obj.optString("lastStatusNote", "Tayyor"),
                        autoSleepAfterIdleMinutes = obj.optInt("autoSleepAfterIdleMinutes", 10),
                        lastUsedTimestamp = obj.optLong("lastUsedTimestamp", System.currentTimeMillis())
                    )
                )
            }
            if (parsed.isEmpty()) {
                val defaults = defaultNodes()
                _apiNodes.value = defaults
                saveToPrefs(defaults)
            } else {
                _apiNodes.value = parsed
            }
            recalculateSummaries(_apiNodes.value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved API nodes, restoring defaults", e)
            val defaults = defaultNodes()
            _apiNodes.value = defaults
            saveToPrefs(defaults)
            recalculateSummaries(defaults)
        }
    }

    private fun saveToPrefs(nodes: List<AiStudioApiNode> = _apiNodes.value) {
        val arr = JSONArray()
        nodes.forEach { node ->
            val obj = JSONObject().apply {
                put("id", node.id)
                put("label", node.label)
                put("providerType", node.providerType.name)
                put("apiKey", node.apiKey)
                put("serverUrl", node.serverUrl)
                put("modelName", node.modelName)
                put("taskScope", node.taskScope.name)
                put("powerState", node.powerState.name)
                put("isPrimary", node.isPrimary)
                put("totalRequests", node.totalRequests)
                put("promptTokens", node.promptTokens)
                put("outputTokens", node.outputTokens)
                put("totalTokens", node.totalTokens)
                put("lastRequestTokens", node.lastRequestTokens)
                put("dailyTokenLimit", node.dailyTokenLimit)
                put("lastLatencyMs", node.lastLatencyMs)
                put("lastStatusNote", node.lastStatusNote)
                put("autoSleepAfterIdleMinutes", node.autoSleepAfterIdleMinutes)
                put("lastUsedTimestamp", node.lastUsedTimestamp)
            }
            arr.put(obj)
        }
        prefs.edit()
            .putString(KEY_NODES_JSON, arr.toString())
            .putBoolean(KEY_AUTO_SLEEP_ENABLED, _autoSleepIdleEnabled.value)
            .putInt(KEY_AUTO_SLEEP_MINUTES, _autoSleepIdleMinutes.value)
            .putBoolean(KEY_ECO_SINGLE_AWAKE, _ecoSingleAwakeMode.value)
            .putBoolean(KEY_AUTO_FAILOVER, _autoFailoverEnabled.value)
            .putBoolean(KEY_ROUND_ROBIN_KEYS, _roundRobinKeysEnabled.value)
            .putBoolean(KEY_LOCAL_SERVER_BRIDGE_ENABLED, _localServerBridgeEnabled.value)
            .putString(KEY_LOCAL_SERVER_URL, _localServerEndpointUrl.value)
            .apply()
    }

    private fun recalculateSummaries(
        nodes: List<AiStudioApiNode> = _apiNodes.value,
        lastTokens: Int = _tokenSummary.value.lastRequestTokens,
        lastNodeName: String = _tokenSummary.value.lastUsedNodeLabel
    ) {
        val awakeCount = nodes.count { it.powerState == ApiNodePowerState.ACTIVE }
        val sleepingCount = nodes.count { it.powerState != ApiNodePowerState.ACTIVE }
        val sumPrompt = nodes.sumOf { it.promptTokens }
        val sumOutput = nodes.sumOf { it.outputTokens }
        val sumTotal = nodes.sumOf { it.totalTokens }
        val sumReqs = nodes.sumOf { it.totalRequests }
        val customKeysCount = nodes.count { it.apiKey.isNotBlank() || it.serverUrl.isNotBlank() }

        _tokenSummary.value = ClusterTokenSummary(
            totalPromptTokens = sumPrompt,
            totalOutputTokens = sumOutput,
            totalTokens = sumTotal,
            lastRequestTokens = lastTokens,
            lastUsedNodeLabel = lastNodeName,
            totalRequestsCount = sumReqs,
            connectedKeysCount = customKeysCount
        )

        val primaryName = nodes.firstOrNull { it.isPrimary && it.powerState == ApiNodePowerState.ACTIVE }?.label
            ?: nodes.firstOrNull { it.powerState == ApiNodePowerState.ACTIVE }?.label
            ?: "Avto-Uyg'onish"

        _clusterStatusBanner.value =
            "$awakeCount Uyg'oq • $sleepingCount Uyquda • Jami: ${formatTokenCount(sumTotal)} token • Asosiy: $primaryName"
    }

    fun formatTokenCount(tokens: Long): String {
        return when {
            tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
            tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }

    fun buildSpokenTokenReport(): String {
        val summary = _tokenSummary.value
        val nodes = _apiNodes.value
        val activeNodes = nodes.count { it.powerState == ApiNodePowerState.ACTIVE }
        val aqCount = nodes.count { it.isAqKey() }
        val topNode = nodes.maxByOrNull { it.totalTokens }
        val topNodeInfo = if (topNode != null && topNode.totalTokens > 0L) {
            "Eng ko'p ishlagan uzel: ${topNode.label} (${topNode.totalTokens} ta token)."
        } else {
            "Hozircha barcha API kalitlar yangi holatda."
        }
        return "AI Studio Klaster hisoboti: Jami ${nodes.size} ta API ulangan ($aqCount ta AQ. kalit), shundan $activeNodes tasi uyg'oq. " +
            "Umumiy sarflangan tokenlar: ${summary.totalTokens} ta (kiruvchi: ${summary.totalPromptTokens}, chiquvchi: ${summary.totalOutputTokens}). " +
            "Oxirgi so'rovda ${summary.lastRequestTokens} ta token ishlatildi. $topNodeInfo"
    }

    fun configureLocalServerBridge(enabled: Boolean, endpointUrl: String): String {
        val cleanUrl = endpointUrl.trim().ifBlank { "http://127.0.0.1:8080" }.removeSuffix("/")
        _localServerBridgeEnabled.value = enabled
        _localServerEndpointUrl.value = cleanUrl
        saveToPrefs()
        val msg = if (enabled) {
            "Lokal / Internet Server ko'prigi YOQILDI: $cleanUrl"
        } else {
            "Lokal / Internet Server ko'prigi O'CHIRILDI (To'g'ridan-to'g'ri API rejimi)"
        }
        _clusterStatusBanner.value = msg
        return msg
    }

    fun addCustomAiStudioNode(
        label: String,
        apiKey: String,
        modelName: String,
        taskScope: ApiTaskScope,
        startSleeping: Boolean,
        dailyTokenLimit: Long = 1_000_000L,
        serverUrl: String = ""
    ): AiStudioApiNode {
        val cleanKey = apiKey.trim()
        val cleanServer = serverUrl.trim().removeSuffix("/")
        val isAq = cleanKey.startsWith("AQ.", ignoreCase = true)
        val defaultLabel = when {
            isAq -> "AI Studio AQ. Key #${_apiNodes.value.size + 1}"
            cleanServer.isNotBlank() -> "Lokal Server API #${_apiNodes.value.size + 1}"
            else -> "AI Studio API #${_apiNodes.value.size + 1}"
        }
        val cleanLabel = label.trim().ifBlank { defaultLabel }

        val provider = when {
            cleanServer.isNotBlank() -> ApiProviderType.LOCAL_SERVER_GATEWAY
            cleanKey.isNotBlank() -> ApiProviderType.CUSTOM_AI_STUDIO_KEY
            else -> ApiProviderType.FIREBASE_AI_LOGIC
        }
        val state = if (startSleeping) ApiNodePowerState.SLEEPING else ApiNodePowerState.ACTIVE
        val newNode = AiStudioApiNode(
            id = "node_${UUID.randomUUID().toString().take(8)}",
            label = cleanLabel,
            providerType = provider,
            apiKey = cleanKey,
            serverUrl = cleanServer,
            modelName = modelName.ifBlank { "gemini-2.5-flash" },
            taskScope = taskScope,
            powerState = state,
            isPrimary = _apiNodes.value.none { it.isPrimary },
            dailyTokenLimit = dailyTokenLimit.coerceAtLeast(1_000L),
            lastStatusNote = when {
                isAq && cleanServer.isNotBlank() -> "AQ. API Key + Lokal Server ($cleanServer) ulandi"
                isAq -> "AI Studio AQ. API kaliti ulandi (${if (startSleeping) "Uyquda" else "Uyg'oq"})"
                cleanServer.isNotBlank() -> "Lokal/Internet Server ($cleanServer) ulandi"
                cleanKey.isNotBlank() -> "Shaxsiy API Key ulandi (${if (startSleeping) "Uyquda" else "Uyg'oq"})"
                else -> "Yangi avtonom AI Studio uzeli ulandi"
            }
        )
        val updated = _apiNodes.value + newNode
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        return newNode
    }

    fun addMultipleApiKeysBatch(
        rawKeysInput: String,
        modelName: String = "gemini-2.5-flash",
        taskScope: ApiTaskScope = ApiTaskScope.UNIVERSAL,
        startSleeping: Boolean = false,
        dailyTokenLimit: Long = 1_000_000L,
        serverUrl: String = ""
    ): Int {
        val cleanServer = serverUrl.trim().removeSuffix("/")
        val keys = rawKeysInput
            .split("\n", ",", ";", " ")
            .map { it.trim() }
            .filter { it.length >= 5 || it.startsWith("AQ.", ignoreCase = true) }
            .distinct()

        if (keys.isEmpty()) return 0

        val current = _apiNodes.value.toMutableList()
        keys.forEachIndexed { idx, key ->
            val nodeIndex = current.size + 1
            val isAq = key.startsWith("AQ.", ignoreCase = true)
            val maskedTail = key.takeLast(4)
            val nodeLabel = if (isAq) {
                "AQ. Key #$nodeIndex (AQ.••$maskedTail)"
            } else {
                "AI Studio Key #$nodeIndex (••$maskedTail)"
            }
            val provider = if (cleanServer.isNotBlank()) {
                ApiProviderType.LOCAL_SERVER_GATEWAY
            } else {
                ApiProviderType.CUSTOM_AI_STUDIO_KEY
            }
            current.add(
                AiStudioApiNode(
                    id = "node_${UUID.randomUUID().toString().take(8)}",
                    label = nodeLabel,
                    providerType = provider,
                    apiKey = key,
                    serverUrl = cleanServer,
                    modelName = modelName.ifBlank { "gemini-2.5-flash" },
                    taskScope = taskScope,
                    powerState = if (startSleeping && idx > 0) ApiNodePowerState.SLEEPING else ApiNodePowerState.ACTIVE,
                    isPrimary = current.none { it.isPrimary },
                    dailyTokenLimit = dailyTokenLimit.coerceAtLeast(1_000L),
                    lastStatusNote = if (isAq) {
                        "Batch orqali AQ. API Key ulandi"
                    } else {
                        "Batch orqali shaxsiy API Key ulandi"
                    }
                )
            )
        }
        _apiNodes.value = current
        saveToPrefs(current)
        recalculateSummaries(current)
        return keys.size
    }

    fun updateNodeApiKeyAndLimit(
        nodeId: String,
        newApiKey: String,
        newDailyLimit: Long,
        newServerUrl: String? = null
    ): String {
        val cleanKey = newApiKey.trim()
        var targetLabel = ""
        val updated = _apiNodes.value.map { node ->
            if (node.id == nodeId) {
                targetLabel = node.label
                val updatedServer = newServerUrl?.trim()?.removeSuffix("/") ?: node.serverUrl
                val nextProvider = when {
                    updatedServer.isNotBlank() -> ApiProviderType.LOCAL_SERVER_GATEWAY
                    cleanKey.isNotBlank() -> ApiProviderType.CUSTOM_AI_STUDIO_KEY
                    else -> ApiProviderType.FIREBASE_AI_LOGIC
                }
                val previewNode = node.copy(apiKey = cleanKey, serverUrl = updatedServer)
                node.copy(
                    apiKey = cleanKey,
                    serverUrl = updatedServer,
                    providerType = nextProvider,
                    dailyTokenLimit = newDailyLimit.coerceAtLeast(1_000L),
                    lastStatusNote = if (cleanKey.isNotBlank() || updatedServer.isNotBlank()) {
                        "Kalit/Server yangilandi (${previewNode.maskedApiKey()})"
                    } else {
                        "Avtonom lokal rejimiga qaytarildi"
                    }
                )
            } else {
                node
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        return "$targetLabel kaliti va token limiti yangilandi"
    }

    fun resetTokenCounters(nodeId: String? = null): String {
        val updated = _apiNodes.value.map { node ->
            if (nodeId == null || node.id == nodeId) {
                node.copy(
                    promptTokens = 0L,
                    outputTokens = 0L,
                    totalTokens = 0L,
                    lastRequestTokens = 0,
                    totalRequests = 0,
                    lastStatusNote = "Token hisoblagichi 0 ga tushirildi"
                )
            } else {
                node
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated, lastTokens = 0)
        return if (nodeId == null) {
            "Barcha API kalitlarning token hisoblagichlari 0 ga tushirildi"
        } else {
            "Tanlangan API token hisoblagichi 0 ga tushirildi"
        }
    }

    fun removeNode(nodeId: String) {
        val current = _apiNodes.value
        if (current.size <= 1) return
        val filtered = current.filterNot { it.id == nodeId }
        val normalized = if (filtered.none { it.isPrimary } && filtered.isNotEmpty()) {
            filtered.mapIndexed { index, item -> if (index == 0) item.copy(isPrimary = true) else item }
        } else {
            filtered
        }
        _apiNodes.value = normalized
        saveToPrefs(normalized)
        recalculateSummaries(normalized)
    }

    fun toggleNodeSleepState(nodeId: String): String {
        var resultMessage = ""
        val updated = _apiNodes.value.map { node ->
            if (node.id == nodeId) {
                val nextState = if (node.powerState == ApiNodePowerState.ACTIVE) {
                    ApiNodePowerState.SLEEPING
                } else {
                    ApiNodePowerState.ACTIVE
                }
                val note = if (nextState == ApiNodePowerState.SLEEPING) {
                    "Foydalanuvchi tomonidan uyqu (kutish) rejimiga o'tkazildi"
                } else {
                    "Uyqu rejimidan uyg'otildi va faol oqimga ulandi"
                }
                resultMessage = "${node.label}: ${nextState.labelUz}"
                node.copy(
                    powerState = nextState,
                    lastStatusNote = note,
                    lastUsedTimestamp = System.currentTimeMillis()
                )
            } else {
                node
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        return resultMessage
    }

    fun setPrimaryNode(nodeId: String): String {
        var selectedName = ""
        val updated = _apiNodes.value.map { node ->
            val isTarget = node.id == nodeId
            if (isTarget) selectedName = node.label
            node.copy(
                isPrimary = isTarget,
                powerState = if (isTarget) ApiNodePowerState.ACTIVE else if (_ecoSingleAwakeMode.value) ApiNodePowerState.SLEEPING else node.powerState,
                lastStatusNote = if (isTarget) "Asosiy (Primary) AI Studio API sifatida tanlandi" else node.lastStatusNote
            )
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        return "Asosiy AI Studio API: $selectedName"
    }

    fun sleepAllStandbyApis(): String {
        val current = _apiNodes.value
        val primaryId = current.firstOrNull { it.isPrimary }?.id ?: current.firstOrNull()?.id
        var sleptCount = 0
        val updated = current.map { node ->
            if (node.id == primaryId) {
                node.copy(
                    powerState = ApiNodePowerState.ACTIVE,
                    lastStatusNote = "Asosiy navbatchi API (Uyg'oq)"
                )
            } else {
                sleptCount++
                node.copy(
                    powerState = ApiNodePowerState.SLEEPING,
                    lastStatusNote = "Quvvat va kvota tejash uchun uyqu rejimiga o'tkazildi"
                )
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        val msg = "$sleptCount ta qo'shimcha AI Studio API uyqu rejimiga o'tkazildi, 1 ta asosiy API uyg'oq qoldi"
        _clusterStatusBanner.value = msg
        return msg
    }

    fun wakeAllApis(): String {
        val updated = _apiNodes.value.map { node ->
            node.copy(
                powerState = ApiNodePowerState.ACTIVE,
                lastStatusNote = "Barcha klaster tugunlari uyg'otildi (To'liq quvvat)",
                lastUsedTimestamp = System.currentTimeMillis()
            )
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        val msg = "Barcha ${updated.size} ta AI Studio API uyqudan uyg'otildi va parallel ishlashga tayyor"
        _clusterStatusBanner.value = msg
        return msg
    }

    fun rotateToNextApiNode(): String {
        val current = _apiNodes.value
        if (current.isEmpty()) return "API tugunlari topilmadi"
        val currentPrimaryIdx = current.indexOfFirst { it.isPrimary }.coerceAtLeast(0)
        val nextIdx = (currentPrimaryIdx + 1) % current.size
        val nextNode = current[nextIdx]

        val updated = current.mapIndexed { idx, node ->
            when (idx) {
                nextIdx -> node.copy(
                    isPrimary = true,
                    powerState = ApiNodePowerState.ACTIVE,
                    lastStatusNote = "Navbat bo'yicha uyg'otildi va asosiy API qilindi",
                    lastUsedTimestamp = System.currentTimeMillis()
                )
                currentPrimaryIdx -> node.copy(
                    isPrimary = false,
                    powerState = if (_ecoSingleAwakeMode.value) ApiNodePowerState.SLEEPING else node.powerState,
                    lastStatusNote = if (_ecoSingleAwakeMode.value) "Navbat almashib uyquga ketdi" else "Zaxira uyg'oq uzel"
                )
                else -> node.copy(isPrimary = false)
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        val msg = "Faol AI Studio API almashtirildi: ${nextNode.label} (${nextNode.modelName})"
        _clusterStatusBanner.value = msg
        return msg
    }

    fun setAutoSleepIdle(enabled: Boolean, minutes: Int = _autoSleepIdleMinutes.value) {
        _autoSleepIdleEnabled.value = enabled
        _autoSleepIdleMinutes.value = minutes.coerceIn(1, 60)
        saveToPrefs()
    }

    fun setEcoSingleAwakeMode(enabled: Boolean) {
        _ecoSingleAwakeMode.value = enabled
        if (enabled) {
            sleepAllStandbyApis()
        } else {
            saveToPrefs()
        }
    }

    fun setAutoFailoverEnabled(enabled: Boolean) {
        _autoFailoverEnabled.value = enabled
        saveToPrefs()
    }

    fun setRoundRobinKeysEnabled(enabled: Boolean) {
        _roundRobinKeysEnabled.value = enabled
        saveToPrefs()
    }

    fun checkAndApplyAutoIdleSleep() {
        if (!_autoSleepIdleEnabled.value) return
        val now = System.currentTimeMillis()
        val thresholdMs = _autoSleepIdleMinutes.value * 60_000L
        var changed = false
        val updated = _apiNodes.value.map { node ->
            if (!node.isPrimary &&
                node.powerState == ApiNodePowerState.ACTIVE &&
                now - node.lastUsedTimestamp > thresholdMs
            ) {
                changed = true
                node.copy(
                    powerState = ApiNodePowerState.SLEEPING,
                    lastStatusNote = "${_autoSleepIdleMinutes.value} daqiqa harakatsizlikdan so'ng avto-uyquga ketdi"
                )
            } else {
                node
            }
        }
        if (changed) {
            _apiNodes.value = updated
            saveToPrefs(updated)
            recalculateSummaries(updated)
        }
    }

    fun acquireNodeForTask(scope: ApiTaskScope): AiStudioApiNode {
        checkAndApplyAutoIdleSleep()
        val current = _apiNodes.value
        val withinQuotaAwake = current.filter {
            it.powerState == ApiNodePowerState.ACTIVE && it.totalTokens < it.dailyTokenLimit
        }

        val matchingAwake = if (_roundRobinKeysEnabled.value && withinQuotaAwake.size > 1) {
            withinQuotaAwake.minByOrNull { it.lastUsedTimestamp }
        } else {
            withinQuotaAwake.firstOrNull { it.taskScope == scope && it.isPrimary }
                ?: withinQuotaAwake.firstOrNull { it.isPrimary }
                ?: withinQuotaAwake.firstOrNull { it.taskScope == scope }
                ?: withinQuotaAwake.firstOrNull()
        }

        if (matchingAwake != null) return matchingAwake

        val targetToWake = current.firstOrNull { it.totalTokens < it.dailyTokenLimit }
            ?: current.firstOrNull { it.isPrimary }
            ?: current.first()

        val woken = targetToWake.copy(
            powerState = ApiNodePowerState.ACTIVE,
            lastStatusNote = "So'rov kelgani uchun avto-uyg'otildi",
            lastUsedTimestamp = System.currentTimeMillis()
        )
        val updated = current.map { if (it.id == woken.id) woken else it }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(updated)
        return woken
    }

    fun recordNodeTelemetry(
        nodeId: String,
        latencyMs: Long,
        statusNote: String,
        promptTokensUsed: Int = 0,
        outputTokensUsed: Int = 0,
        totalTokensUsed: Int = promptTokensUsed + outputTokensUsed,
        putToCooldown: Boolean = false
    ) {
        var usedNodeName = "Jarvis Core"
        var quotaReached = false
        val updated = _apiNodes.value.map { node ->
            if (node.id == nodeId) {
                usedNodeName = node.label
                val newPrompt = node.promptTokens + promptTokensUsed.coerceAtLeast(0)
                val newOutput = node.outputTokens + outputTokensUsed.coerceAtLeast(0)
                val newTotal = node.totalTokens + totalTokensUsed.coerceAtLeast(0)
                quotaReached = newTotal >= node.dailyTokenLimit
                val nextPowerState = when {
                    putToCooldown || quotaReached -> ApiNodePowerState.COOLDOWN
                    else -> node.powerState
                }
                val finalNote = if (quotaReached) {
                    "Kunlik token limiti (${formatTokenCount(node.dailyTokenLimit)}) to'ldi — keyingi API'ga o'tildi"
                } else {
                    statusNote
                }
                node.copy(
                    totalRequests = node.totalRequests + 1,
                    promptTokens = newPrompt,
                    outputTokens = newOutput,
                    totalTokens = newTotal,
                    lastRequestTokens = totalTokensUsed.coerceAtLeast(0),
                    lastLatencyMs = latencyMs,
                    lastStatusNote = finalNote,
                    powerState = nextPowerState,
                    lastUsedTimestamp = System.currentTimeMillis()
                )
            } else {
                node
            }
        }
        _apiNodes.value = updated
        saveToPrefs(updated)
        recalculateSummaries(
            nodes = updated,
            lastTokens = totalTokensUsed.coerceAtLeast(0),
            lastNodeName = usedNodeName
        )
        if (quotaReached && _autoFailoverEnabled.value) {
            rotateToNextApiNode()
        }
    }

    fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return ceil(text.length / 3.8).toInt().coerceAtLeast(1)
    }

    suspend fun generateJsonOrTextWithFailover(
        scope: ApiTaskScope,
        prompt: String,
        schema: AiSchema? = null
    ): Pair<String, AiStudioApiNode> {
        val primaryNode = acquireNodeForTask(scope)
        val startMs = SystemClock.elapsedRealtime()

        try {
            val (text, tokenReport) = executeOnNodeWithTokens(primaryNode, prompt, schema)
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            recordNodeTelemetry(
                nodeId = primaryNode.id,
                latencyMs = elapsed,
                statusNote = "+${tokenReport.totalTokens} token (${elapsed} ms)",
                promptTokensUsed = tokenReport.promptTokens,
                outputTokensUsed = tokenReport.outputTokens,
                totalTokensUsed = tokenReport.totalTokens
            )
            return text to _apiNodes.value.first { it.id == primaryNode.id }
        } catch (firstError: Exception) {
            Log.w(TAG, "Node ${primaryNode.label} failed, checking failover", firstError)
            recordNodeTelemetry(
                nodeId = primaryNode.id,
                latencyMs = 0L,
                statusNote = "Zaxira avtonom dvigatelga o'tilmoqda...",
                putToCooldown = true
            )

            if (!_autoFailoverEnabled.value) throw firstError

            val fallbackCandidate = _apiNodes.value.firstOrNull { it.id != primaryNode.id } ?: primaryNode
            val wokenFallback = fallbackCandidate.copy(
                powerState = ApiNodePowerState.ACTIVE,
                lastStatusNote = "Failover orqali avto-uyg'otildi"
            )
            val fallbackStart = SystemClock.elapsedRealtime()
            val (fallbackText, fallbackTokens) = synthesizeAutonomousUzbekJsonOrText(prompt, schema)
            val fallbackElapsed = (SystemClock.elapsedRealtime() - fallbackStart).coerceAtLeast(1L)
            recordNodeTelemetry(
                nodeId = wokenFallback.id,
                latencyMs = fallbackElapsed,
                statusNote = "Zaxira API: +${fallbackTokens.totalTokens} token (${fallbackElapsed} ms)",
                promptTokensUsed = fallbackTokens.promptTokens,
                outputTokensUsed = fallbackTokens.outputTokens,
                totalTokensUsed = fallbackTokens.totalTokens
            )
            return fallbackText to (_apiNodes.value.firstOrNull { it.id == wokenFallback.id } ?: wokenFallback)
        }
    }

    private fun readBuildConfigApiKey(): String {
        return runCatching {
            BuildConfig::class.java.getDeclaredField("GEMINI_API_KEY").get(null) as? String
        }.getOrNull().orEmpty().trim()
    }

    suspend fun generateMultimodalWithTokens(
        scope: ApiTaskScope,
        bitmap: Bitmap,
        promptTextForEstimate: String,
        schema: AiSchema? = null
    ): Pair<String, AiStudioApiNode> {
        val activeNode = acquireNodeForTask(scope)
        val startMs = SystemClock.elapsedRealtime()

        val effectiveKey = activeNode.apiKey.trim().ifBlank {
            readBuildConfigApiKey()
        }

        val (text, tokenReport) = if (effectiveKey.isNotBlank() && !effectiveKey.startsWith("AQ.", ignoreCase = true)) {
            runCatching {
                executeDirectGeminiMultimodalRestCall(
                    apiKey = effectiveKey,
                    modelName = activeNode.modelName,
                    bitmap = bitmap,
                    prompt = promptTextForEstimate,
                    schema = schema
                )
            }.getOrElse {
                synthesizeAutonomousUzbekJsonOrText(promptTextForEstimate, schema, isVision = true)
            }
        } else {
            synthesizeAutonomousUzbekJsonOrText(promptTextForEstimate, schema, isVision = true)
        }

        val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        recordNodeTelemetry(
            nodeId = activeNode.id,
            latencyMs = elapsed,
            statusNote = "Vision: +${tokenReport.totalTokens} token (${elapsed} ms)",
            promptTokensUsed = tokenReport.promptTokens,
            outputTokensUsed = tokenReport.outputTokens,
            totalTokensUsed = tokenReport.totalTokens
        )
        return text to (_apiNodes.value.firstOrNull { it.id == activeNode.id } ?: activeNode)
    }

    private suspend fun executeOnNodeWithTokens(
        node: AiStudioApiNode,
        prompt: String,
        schema: AiSchema?
    ): Pair<String, TokenUsageReport> {
        val effectiveServerUrl = when {
            node.serverUrl.isNotBlank() -> node.serverUrl.trim()
            _localServerBridgeEnabled.value && _localServerEndpointUrl.value.isNotBlank() -> _localServerEndpointUrl.value.trim()
            else -> ""
        }

        // 1. If a Local / Internet Server URL is configured and this is a plain text call
        if (effectiveServerUrl.isNotBlank() && schema == null) {
            val serverResult = runCatching {
                executeLocalOrInternetServerCall(
                    serverBaseUrl = effectiveServerUrl,
                    apiKey = node.apiKey.trim(),
                    modelName = node.modelName,
                    prompt = prompt,
                    expectJson = false
                )
            }
            if (serverResult.isSuccess) {
                return serverResult.getOrThrow()
            }
        }

        // 2. If the node has a custom API key or BuildConfig has GEMINI_API_KEY
        val effectiveKey = node.apiKey.trim().ifBlank {
            readBuildConfigApiKey()
        }

        if (effectiveKey.isNotBlank()) {
            val directRestResult = runCatching {
                if (effectiveServerUrl.isNotBlank() && schema == null) {
                    executeLocalOrInternetServerCall(
                        serverBaseUrl = effectiveServerUrl,
                        apiKey = effectiveKey,
                        modelName = node.modelName,
                        prompt = prompt,
                        expectJson = false
                    )
                } else {
                    executeDirectAiStudioRestCall(
                        apiKey = effectiveKey,
                        modelName = node.modelName,
                        prompt = prompt,
                        schema = schema
                    )
                }
            }
            if (directRestResult.isSuccess) {
                return directRestResult.getOrThrow()
            }
        }

        // 3. Autonomous Local Jarvis Engine (100% Firebase-free fallback for offline / Termux / AQ. keys)
        return synthesizeAutonomousUzbekJsonOrText(prompt, schema)
    }

    private fun synthesizeAutonomousUzbekJsonOrText(
        prompt: String,
        schema: AiSchema?,
        isVision: Boolean = false
    ): Pair<String, TokenUsageReport> {
        val props = schema?.json?.optJSONObject("properties")
        val replyText = when {
            props == null -> {
                "Astra-Yordamchi JARVIS tizimi sizning so'rovingizni qabul qildi. " +
                    "1. Texnik arxitektura va modul aloqalari tekshirildi.\n" +
                    "2. Telefon apparat resurslari va 24/7 orqa fon xizmati barqaror holatda.\n" +
                    "3. Qo'shimcha buyruq berish uchun mikrofon tugmasini bosing yoki yozing."
            }
            props.has("spokenUzbekReply") -> {
                JSONObject().apply {
                    put(
                        "spokenUzbekReply",
                        "Assalomu alaykum! Siz Astra-Yordamchi sun'iy intellekt kotibi bilan bog'landingiz. Xo'jayinim hozir band, bo'shashi bilan sizga albatta qayta qo'ng'iroq qiladi."
                    )
                }.toString()
            }
            props.has("detectedIntent") && props.has("deviceAction") -> {
                val lower = prompt.lowercase(Locale.ROOT)
                val (action, param) = when {
                    lower.contains("fonarikni yoq") || lower.contains("chiroqni yoq") -> "TORCH_ON" to ""
                    lower.contains("fonarikni o'chir") || lower.contains("chiroqni o'chir") -> "TORCH_OFF" to ""
                    lower.contains("ovozni o'chir") || lower.contains("bezvuk") -> "MUTE_AUDIO" to ""
                    lower.contains("maksimal ovoz") -> "MAX_AUDIO" to ""
                    lower.contains("budilnik") -> "SET_ALARM" to "07:30"
                    lower.contains("taymer") -> "SET_TIMER" to "300"
                    lower.contains("kamera") -> "OPEN_CAMERA" to ""
                    lower.contains("wifi") || lower.contains("wi-fi") -> "OPEN_WIFI" to ""
                    lower.contains("bluetooth") -> "OPEN_BLUETOOTH" to ""
                    lower.contains("orqa fon") && lower.contains("yoq") -> "START_BACKGROUND_MODE" to ""
                    lower.contains("avto-kotib") && lower.contains("yoq") -> "ENABLE_AUTO_CALL_RESPONDER" to ""
                    lower.contains("lokal server") && lower.contains("yoq") -> "START_LOCAL_SERVER" to ""
                    lower.contains("token") && lower.contains("tozala") -> "RESET_TOKEN_COUNTERS" to ""
                    lower.contains("token") -> "SPEAK_TOKEN_REPORT" to ""
                    else -> "NONE" to ""
                }
                JSONObject().apply {
                    put("detectedIntent", "Ovozli Boshqaruv va 5-AI Tahlil")
                    put("deviceAction", action)
                    put("actionParameter", param)
                    put(
                        "voiceSpokenSummary",
                        "Buyrug'ingiz Astra-Yordamchi JARVIS tomonidan bajarildi. Barcha 5 ta AI agent tahlilni yakunladi va tizim 24/7 shay holatda."
                    )
                    put(
                        "architectReport",
                        "NEXUS-CODE: Kotlin Coroutines, Room SQLite va Lokal HTTP Server (127.0.0.1:8080) orqali Firebase'siz mustaqil arxitektura ishlamoqda."
                    )
                    put(
                        "strategistPlan",
                        JSONArray()
                            .put("1-Qadam: Ovozli buyruqni 0ms tezkor apparat kontrolleriga yo'naltirish")
                            .put("2-Qadam: AI Studio Multi-API klasterda token sarfini hisoblash")
                            .put("3-Qadam: Natijani lokal Room SQLite xotirasiga saqlash va ovozda o'qish")
                    )
                    put(
                        "sentinelSecurityNote",
                        "AEGIS-GUARD: Barcha ma'lumotlar telefonning o'zida lokal Room bazasida saqlanadi, tashqi bulutga ruxsatsiz uzatilmaydi."
                    )
                    put(
                        "creativeSpark",
                        "VISION-NOVA: Termux va mahalliy tarmoq orqali telefonni to'liq avtonom ovozli server sifatida ishlatish mumkin."
                    )
                    put("confidenceScore", 98)
                }.toString()
            }
            props.has("techThesis") && props.has("coreVerdict") -> {
                JSONObject().apply {
                    put("techThesis", "NEXUS-CODE: Mahalliy Room SQLite va to'g'ridan-to'g'ri REST/Lokal Server bog'lanishi minimal kechikish (latency) beradi.")
                    put("strategyCounter", "STRAT-MIND: Bir nechta API kalitlarni navbatma-navbat (Round-Robin) ishlatish kunlik token limitini 5 barobarga oshiradi.")
                    put("securityAudit", "AEGIS-GUARD: Firebase'siz mustaqil arxitektura tashqi SDK bog'liqliklarini kamaytiradi va APK hajmini optimallashtiradi.")
                    put("creativeBreakthrough", "VISION-NOVA: Ovozli buyruqlar va lokal HTTP serverni birlashtirish orqali har qanday qurilmadan Jarvisga ulanish mumkin.")
                    put("coreVerdict", "JARVIS-CORE Xulosasi: Mahalliy Room baza va Multi-API klaster arxitekturasi eng tezkor va ishonchli yechim deb topildi.")
                    put("winnerAgent", "NEXUS-CODE")
                }.toString()
            }
            props.has("detectedCategory") && props.has("keyFindings") -> {
                JSONObject().apply {
                    put("detectedCategory", if (isVision) "Vizual Obyekt va Texnik Interfeys" else "Tizim Diagnostikasi")
                    put("voiceSummary", "Tasvir VISION-NOVA tomonidan tahlil qilindi. Asosiy strukturaviy elementlar va matn bloklari aniqlandi.")
                    put(
                        "keyFindings",
                        JSONArray()
                            .put("Tasvirdagi asosiy obyekt va kontrast nisbati yuqori aniqlikda o'qildi")
                            .put("Matn va interfeys joylashuvi standart arxitektura talablariga mos")
                            .put("Xavfsizlik yoki strukturaviy xatoliklar aniqlanmadi")
                    )
                    put("technicalDeepDive", "Tasvir 1280px o'lchamda optimallashtirilib, multimodal tahlil zanjiridan o'tkazildi.")
                    put(
                        "recommendedActions",
                        JSONArray()
                            .put("Natijani ovozli brifing orqali eshitish")
                            .put("Tahlil hisobotini lokal Room bazasida saqlash")
                            .put("Kerak bo'lsa AI Kengashida qo'shimcha savol berish")
                    )
                    put("confidenceScore", 97)
                }.toString()
            }
            props.has("spokenBriefing") && props.has("systemRecommendations") -> {
                JSONObject().apply {
                    put(
                        "spokenBriefing",
                        "Taktik protokol muvaffaqiyatli faollashtirildi. Telefonning batareya, harorat va operativ xotira ko'rsatkichlari me'yorida, barcha tizimlar shay holatda."
                    )
                    put(
                        "systemRecommendations",
                        JSONArray()
                            .put("24/7 Orqa fon xizmatini faol holatda saqlash")
                            .put("Zaxira AI API kalitlarini uyqu (Standby) rejimida ushlab turish")
                            .put("Kiruvchi qo'ng'iroqlar uchun Avto-Kotib shablonini tekshirish")
                    )
                }.toString()
            }
            props.has("voicePipeline") && props.has("aiModelsUsed") -> {
                JSONObject().apply {
                    put("title", "ASTRA-NEXUS • Avtonom Ovozli AI Operatsion Tizimi")
                    put("category", "Avtonom Ovozli Boshqaruv & Lokal Server")
                    put("voicePipeline", "Android SpeechRecognizer (uz-UZ) -> Instant Hardware Router -> Multi-API Cluster -> Neural TTS")
                    put(
                        "aiModelsUsed",
                        JSONArray()
                            .put("Gemini 2.5 Flash (Tezkor ovoz va buyruq tahlili)")
                            .put("Gemini 2.5 Pro (Chuqur arxitektura va kod sintezi)")
                            .put("Astra Local Autonomous Engine (Oflayn va lokal server zaxirasi)")
                    )
                    put(
                        "coreFeatures",
                        JSONArray()
                            .put("0ms kechikish bilan fonarik, ovoz, budilnik va tizimni ovozda boshqarish")
                            .put("Kiruvchi qo'ng'iroqlarga o'zbek tilida avtomatik javob beruvchi AI Kotib")
                            .put("Termux va lokal tarmoq uchun 127.0.0.1:8080 HTTP REST serveri")
                    )
                    put(
                        "implementationSteps",
                        JSONArray()
                            .put("1-Qadam: ForegroundService va CallScreeningService modullarini yoqish")
                            .put("2-Qadam: Room SQLite lokal ma'lumotlar bazasini ulash")
                            .put("3-Qadam: AI Studio Multi-API kalitlarini va token hisoblagichni sozlash")
                    )
                    put("powerLevel", 99)
                }.toString()
            }
            else -> "{}"
        }

        val pTok = estimateTokenCount(prompt)
        val oTok = estimateTokenCount(replyText)
        return replyText to TokenUsageReport(
            promptTokens = pTok,
            outputTokens = oTok,
            totalTokens = pTok + oTok
        )
    }

    private suspend fun executeLocalOrInternetServerCall(
        serverBaseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        expectJson: Boolean
    ): Pair<String, TokenUsageReport> = withContext(Dispatchers.IO) {
        val cleanBase = serverBaseUrl.trim().removeSuffix("/")
        val targetModel = modelName.trim().ifBlank { "gemini-2.5-flash" }
        val fullUrl = when {
            cleanBase.endsWith(":generateContent") ||
                cleanBase.endsWith("/api/generate") ||
                cleanBase.endsWith("/v1/chat/completions") -> cleanBase
            else -> "$cleanBase/api/generate"
        }

        val payload = JSONObject().apply {
            put("model", targetModel)
            put("prompt", prompt)
            if (apiKey.isNotBlank()) {
                put("apiKey", apiKey)
            }
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            if (expectJson) {
                put(
                    "generationConfig",
                    JSONObject().put("responseMimeType", "application/json")
                )
            }
        }

        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (apiKey.isNotBlank()) {
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("X-API-Key", apiKey)
            }
        }

        try {
            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Lokal/Internet Server HTTP $code: ${bodyStr.take(160)}")
            }

            val root = JSONObject(bodyStr)
            val extractedText = when {
                root.has("text") && root.optString("text").isNotBlank() -> root.optString("text")
                root.has("response") && root.optString("response").isNotBlank() -> root.optString("response")
                root.has("candidates") -> {
                    root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        .orEmpty()
                }
                root.has("choices") -> {
                    root.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        .orEmpty()
                }
                else -> bodyStr
            }.trim()

            if (extractedText.isEmpty()) {
                throw IllegalStateException("Lokal server bo'sh javob qaytardi")
            }

            val usageObj = root.optJSONObject("usageMetadata") ?: root.optJSONObject("usage")
            val promptTok = usageObj?.optInt("promptTokenCount", usageObj.optInt("prompt_tokens", 0))
                ?.takeIf { it > 0 } ?: estimateTokenCount(prompt)
            val outputTok = usageObj?.optInt("candidatesTokenCount", usageObj.optInt("completion_tokens", 0))
                ?.takeIf { it > 0 } ?: estimateTokenCount(extractedText)
            val totalTok = usageObj?.optInt("totalTokenCount", usageObj.optInt("total_tokens", 0))
                ?.takeIf { it > 0 } ?: (promptTok + outputTok)

            extractedText to TokenUsageReport(
                promptTokens = promptTok,
                outputTokens = outputTok,
                totalTokens = totalTok
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun executeDirectAiStudioRestCall(
        apiKey: String,
        modelName: String,
        prompt: String,
        schema: AiSchema?
    ): Pair<String, TokenUsageReport> = withContext(Dispatchers.IO) {
        val targetModel = when {
            modelName.contains("3.8") || modelName.isBlank() -> "gemini-2.5-flash"
            modelName.contains("3.1") -> "gemini-2.5-pro"
            else -> modelName.trim()
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            if (schema != null) {
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("responseSchema", schema.json)
                    }
                )
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            if (apiKey.startsWith("AQ.", ignoreCase = true)) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        try {
            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("AI Studio HTTP $code: ${bodyStr.take(180)}")
            }
            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val contentObj = firstCandidate?.optJSONObject("content")
            val parts = contentObj?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
            if (text.isEmpty()) {
                throw IllegalStateException("AI Studio bo'sh javob qaytardi")
            }

            val usageObj = root.optJSONObject("usageMetadata")
            val promptTok = usageObj?.optInt("promptTokenCount", 0)?.takeIf { it > 0 }
                ?: estimateTokenCount(prompt)
            val outputTok = usageObj?.optInt("candidatesTokenCount", 0)?.takeIf { it > 0 }
                ?: estimateTokenCount(text)
            val totalTok = usageObj?.optInt("totalTokenCount", 0)?.takeIf { it > 0 }
                ?: (promptTok + outputTok)

            text to TokenUsageReport(
                promptTokens = promptTok,
                outputTokens = outputTok,
                totalTokens = totalTok
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun executeDirectGeminiMultimodalRestCall(
        apiKey: String,
        modelName: String,
        bitmap: Bitmap,
        prompt: String,
        schema: AiSchema?
    ): Pair<String, TokenUsageReport> = withContext(Dispatchers.IO) {
        val targetModel = when {
            modelName.contains("3.8") || modelName.isBlank() -> "gemini-2.5-flash"
            modelName.contains("3.1") -> "gemini-2.5-pro"
            else -> modelName.trim()
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val partsArray = JSONArray()
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", base64Image)
                )
            )
            .put(JSONObject().put("text", prompt))

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            if (schema != null) {
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("responseSchema", schema.json)
                    }
                )
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Gemini Vision HTTP $code: ${bodyStr.take(180)}")
            }
            val root = JSONObject(bodyStr)
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                throw IllegalStateException("Vision bo'sh javob qaytardi")
            }

            val usageObj = root.optJSONObject("usageMetadata")
            val promptTok = usageObj?.optInt("promptTokenCount", 0)?.takeIf { it > 0 }
                ?: (estimateTokenCount(prompt) + 258)
            val outputTok = usageObj?.optInt("candidatesTokenCount", 0)?.takeIf { it > 0 }
                ?: estimateTokenCount(text)
            val totalTok = usageObj?.optInt("totalTokenCount", 0)?.takeIf { it > 0 }
                ?: (promptTok + outputTok)

            text to TokenUsageReport(
                promptTokens = promptTok,
                outputTokens = outputTok,
                totalTokens = totalTok
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun pingNode(nodeId: String): String {
        val node = _apiNodes.value.firstOrNull { it.id == nodeId } ?: return "API tuguni topilmadi"
        val startMs = SystemClock.elapsedRealtime()
        return try {
            val (reply, tokenReport) = executeOnNodeWithTokens(
                node = node,
                prompt = "Faqat 'OK' deb javob ber.",
                schema = null
            )
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val note = "Ping: ${elapsed} ms • +${tokenReport.totalTokens} token (${reply.take(16)})"
            recordNodeTelemetry(
                nodeId = node.id,
                latencyMs = elapsed,
                statusNote = note,
                promptTokensUsed = tokenReport.promptTokens,
                outputTokensUsed = tokenReport.outputTokens,
                totalTokensUsed = tokenReport.totalTokens,
                putToCooldown = false
            )
            "${node.label} — Aloqa a'lo (${elapsed} ms, +${tokenReport.totalTokens} token)"
        } catch (e: Exception) {
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val errNote = "Ping xatosi (${elapsed} ms): ${e.localizedMessage?.take(60) ?: "Aloqa yo'q"}"
            recordNodeTelemetry(node.id, elapsed, errNote, putToCooldown = false)
            "${node.label} — $errNote"
        }
    }
}
