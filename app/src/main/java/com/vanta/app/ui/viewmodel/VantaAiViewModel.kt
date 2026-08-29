package com.vanta.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.app.data.GemmaAiAnalysis
import com.vanta.app.data.AiProvider
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.data.baseline.AdaptiveBaselineManager
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.worker.DailyRolloverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface VantaAiUiState {
    data object Loading : VantaAiUiState
    data class Success(val analysis: GemmaAiAnalysis) : VantaAiUiState
    data class Error(val message: String) : VantaAiUiState
}

/** State of the optional cloud AI API. */
sealed interface AiApiUiState {
    data object NotConfigured : AiApiUiState
    data object Configured : AiApiUiState
}

/**
 * Production MVVM ViewModel for Vanta's AI Coach & 7-Day Baseline.
 * Feeds exact live telemetry (steps, active calories, distance, HR) into the AI engine.
 */
class VantaAiViewModel(application: Application) : AndroidViewModel(application) {

    private val gemmaEngine = VantaGemmaEngine(application)
    private val healthConnectManager = HealthConnectManager(application)
    private val baselineManager = AdaptiveBaselineManager(application)
    private val rolloverManager = DailyRolloverManager.getInstance(application)
    private val settingsPrefs = application.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)

    /** Guards against overlapping analyses (e.g. step-change triggers while a slow API call runs). */
    @Volatile
    private var analysisRunning = false

    private val _uiState = MutableStateFlow<VantaAiUiState>(VantaAiUiState.Loading)
    val uiState: StateFlow<VantaAiUiState> = _uiState.asStateFlow()

    private val _userBaseline = MutableStateFlow(UserBaseline.Default)
    val userBaseline: StateFlow<UserBaseline> = _userBaseline.asStateFlow()

    private val _historicalRecords = MutableStateFlow<List<DailyMetricRecord>>(emptyList())
    val historicalRecords: StateFlow<List<DailyMetricRecord>> = _historicalRecords.asStateFlow()

    /** The user's onboarding profile (name, biometrics, goal) — feeds greetings & AI calibration. */
    private val _userProfile = MutableStateFlow<com.vanta.app.data.db.UserProfileRecord?>(null)
    val userProfile: StateFlow<com.vanta.app.data.db.UserProfileRecord?> = _userProfile.asStateFlow()

    /** Most recent live Health Connect telemetry snapshot (exposed for detail pages). */
    private val _liveTelemetry = MutableStateFlow<HealthConnectTelemetry?>(null)
    val liveTelemetry: StateFlow<HealthConnectTelemetry?> = _liveTelemetry.asStateFlow()

    private val _isStepsMeasured = MutableStateFlow(true)
    val isStepsMeasured: StateFlow<Boolean> = _isStepsMeasured.asStateFlow()

    private val _isCaloriesMeasured = MutableStateFlow(true)
    val isCaloriesMeasured: StateFlow<Boolean> = _isCaloriesMeasured.asStateFlow()

    private val _isDistanceMeasured = MutableStateFlow(true)
    val isDistanceMeasured: StateFlow<Boolean> = _isDistanceMeasured.asStateFlow()

    private val _recentWorkouts = MutableStateFlow<List<com.vanta.app.data.VantaWorkoutSession>>(emptyList())
    val recentWorkouts: StateFlow<List<com.vanta.app.data.VantaWorkoutSession>> = _recentWorkouts.asStateFlow()

    val onDeviceLlmManager = com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(getApplication())
    val modelDownloadManager = com.vanta.app.data.ai.ModelDownloadManager.getInstance(getApplication())

    val onDeviceState: StateFlow<com.vanta.app.data.ai.OnDeviceLlmManager.ModelState> = onDeviceLlmManager.state
    val downloadProgress: StateFlow<com.vanta.app.data.ai.ModelDownloadManager.Progress> = modelDownloadManager.progress

    private val _apiUiState = MutableStateFlow<AiApiUiState>(
        if (selectedProvider() == AiProvider.ON_DEVICE_LITERT) {
            if (onDeviceLlmManager.isModelDownloaded()) AiApiUiState.Configured else AiApiUiState.NotConfigured
        } else {
            if (savedApiKey(selectedProvider()).isNotBlank()) AiApiUiState.Configured else AiApiUiState.NotConfigured
        }
    )
    val apiUiState: StateFlow<AiApiUiState> = _apiUiState.asStateFlow()

    companion object {
        const val PROVIDER_PREF = "vanta_ai_provider"
        const val ANALYSIS_PROVIDER_PREF = "vanta_ai_analysis_provider"
        const val CHAT_PROVIDER_PREF = "vanta_ai_chat_provider"
        private fun keyPref(provider: AiProvider) = "api_key_${provider.name.lowercase()}"
    }

    /** The currently selected provider (Gemini, DeepSeek, OpenRouter, or On-Device LiteRT-LM). */
    fun selectedProvider(): AiProvider = selectedAnalysisProvider()

    fun selectedAnalysisProvider(): AiProvider {
        val name = settingsPrefs.getString(ANALYSIS_PROVIDER_PREF, null)
            ?: settingsPrefs.getString(PROVIDER_PREF, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        return runCatching { AiProvider.valueOf(name) }.getOrDefault(AiProvider.GEMINI)
    }

    fun selectAnalysisProvider(provider: AiProvider) {
        settingsPrefs.edit().putString(ANALYSIS_PROVIDER_PREF, provider.name).putString(PROVIDER_PREF, provider.name).apply()
        gemmaEngine.clearCache()
        _apiUiState.value = if (provider == AiProvider.ON_DEVICE_LITERT) {
            if (onDeviceLlmManager.isModelDownloaded()) AiApiUiState.Configured else AiApiUiState.NotConfigured
        } else {
            if (savedApiKey(provider).isNotBlank()) AiApiUiState.Configured else AiApiUiState.NotConfigured
        }
    }

    fun selectedChatProvider(): AiProvider {
        val name = settingsPrefs.getString(CHAT_PROVIDER_PREF, null)
            ?: settingsPrefs.getString(PROVIDER_PREF, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        return runCatching { AiProvider.valueOf(name) }.getOrDefault(AiProvider.GEMINI)
    }

    fun selectChatProvider(provider: AiProvider) {
        settingsPrefs.edit().putString(CHAT_PROVIDER_PREF, provider.name).apply()
    }

    fun isRamSufficientForOnDevice(): Boolean =
        onDeviceLlmManager.isRamSufficientForOnDevice(getApplication())

    fun getTotalRamGb(): Double =
        onDeviceLlmManager.getTotalRamGb(getApplication())

    fun getModelStorageLocation(): com.vanta.app.data.ai.OnDeviceLlmManager.ModelStorageLocation =
        onDeviceLlmManager.getModelStorageLocation()

    fun setModelStorageLocation(location: com.vanta.app.data.ai.OnDeviceLlmManager.ModelStorageLocation) =
        onDeviceLlmManager.setModelStorageLocation(location)

    fun startModelDownload(customUrl: String? = null, hfToken: String? = null) {
        if (!isRamSufficientForOnDevice()) return
        modelDownloadManager.startDownload(customUrl, hfToken)
    }

    fun cancelModelDownload() {
        modelDownloadManager.cancelDownload()
    }

    fun deleteOnDeviceModel() {
        modelDownloadManager.deleteModel()
        com.vanta.app.data.ai.AiDetailInsightCache(getApplication()).clearAll()
        _apiUiState.value = AiApiUiState.NotConfigured
    }

    /** Whether THIS provider has its own saved API key (no cross-provider fallback). */
    fun hasOwnApiKey(provider: AiProvider): Boolean =
        provider == AiProvider.ON_DEVICE_LITERT ||
            settingsPrefs.getString(keyPref(provider), "")?.trim().isNullOrBlank().not()

    /* A provider whose key was saved under this exact provider (no fallback). */
    fun cloudProviderWithOwnKey(): AiProvider? =
        listOf(AiProvider.GEMINI, AiProvider.DEEPSEEK, AiProvider.MISTRAL, AiProvider.OPENROUTER)
            .firstOrNull { hasOwnApiKey(it) }

    /** The currently-selected cloud provider (analysis → chat → any provider with own key). */
    fun selectedCloudProvider(): AiProvider =
        selectedAnalysisProvider().takeIf { it != AiProvider.ON_DEVICE_LITERT }
            ?: selectedChatProvider().takeIf { it != AiProvider.ON_DEVICE_LITERT }
            ?: cloudProviderWithOwnKey() ?: AiProvider.GEMINI

    /** The saved API key for the given provider (empty string if none). */
    fun savedApiKey(provider: AiProvider = selectedProvider()): String {
        val direct = if (provider != AiProvider.ON_DEVICE_LITERT) {
            settingsPrefs.getString(keyPref(provider), "")?.trim() ?: ""
        } else ""
        if (direct.isNotBlank()) return direct
        // Fallback: If user saved a key under another cloud provider, use that key
        for (p in listOf(AiProvider.GEMINI, AiProvider.DEEPSEEK, AiProvider.MISTRAL, AiProvider.OPENROUTER)) {
            val key = settingsPrefs.getString(keyPref(p), "")?.trim() ?: ""
            if (key.isNotBlank()) return key
        }
        return ""
    }

    fun saveApiKey(key: String, provider: AiProvider = selectedProvider()) {
        val cleanKey = key.trim()
        settingsPrefs.edit().putString(keyPref(provider), cleanKey).apply()
        if (cleanKey.isNotBlank()) {
            settingsPrefs.edit()
                .putBoolean("ai_daily_analysis_enabled", true)
                .putBoolean("ai_chat_enabled", true)
                .putBoolean("ai_detailed_coach_enabled", true)
                .apply()
            _isDailyAnalysisEnabled.value = true
            _isAiChatEnabled.value = true
            _isDetailedCoachEnabled.value = true
        }
        com.vanta.app.data.ai.AiDetailInsightCache(getApplication()).clearAll()
        _apiUiState.value = if (cleanKey.isNotBlank()) AiApiUiState.Configured else AiApiUiState.NotConfigured
    }

    fun clearApiKey(provider: AiProvider = selectedProvider()) {
        settingsPrefs.edit().remove(keyPref(provider)).apply()
        com.vanta.app.data.ai.AiDetailInsightCache(getApplication()).clearAll()
        _apiUiState.value = AiApiUiState.NotConfigured
    }

    // ── Dedicated AI Daily Analysis (Home Screen Vanta Coach Briefing) ────────
    private val _isDailyAnalysisEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("ai_daily_analysis_enabled", false)
    )
    val isDailyAnalysisEnabled: StateFlow<Boolean> = _isDailyAnalysisEnabled.asStateFlow()

    fun setDailyAnalysisEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("ai_daily_analysis_enabled", enabled).apply()
        _isDailyAnalysisEnabled.value = enabled
        if (enabled) {
            runAnalysis(forceFresh = true)
        }
    }

    fun isDailyAnalysisAvailable(): Boolean {
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        val isConfigured = hasKey || hasOnDevice
        if (!isConfigured) return false
        return _isDailyAnalysisEnabled.value
    }

    val isDailyAnalysisAvailable: StateFlow<Boolean> = combine(
        _isDailyAnalysisEnabled,
        _apiUiState,
        onDeviceState
    ) { enabled, _, _ ->
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        (hasKey || hasOnDevice) && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isDailyAnalysisAvailable())

    // ── Dedicated AI Chat Management ───────────────────────────────────────────
    val chatManager = com.vanta.app.data.ai.VantaChatManager.getInstance(getApplication())
    val currentChatSession = chatManager.currentSession
    val chatSessions = chatManager.sessions

    private val _isAiChatEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("ai_chat_enabled", false)
    )
    val isAiChatEnabled: StateFlow<Boolean> = _isAiChatEnabled.asStateFlow()

    fun setAiChatEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("ai_chat_enabled", enabled).apply()
        _isAiChatEnabled.value = enabled
    }

    fun isAiChatAvailable(): Boolean {
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        val isConfigured = hasKey || hasOnDevice
        if (!isConfigured) return false
        return _isAiChatEnabled.value
    }

    val isAiChatAvailable: StateFlow<Boolean> = combine(
        _isAiChatEnabled,
        _apiUiState,
        onDeviceState
    ) { enabled, _, _ ->
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        (hasKey || hasOnDevice) && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isAiChatAvailable())

    // ── Dedicated Detailed Coach Management (Strain, Recovery, Energy pages) ──
    private val _isDetailedCoachEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("ai_detailed_coach_enabled", false)
    )
    val isDetailedCoachEnabled: StateFlow<Boolean> = _isDetailedCoachEnabled.asStateFlow()

    fun setDetailedCoachEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("ai_detailed_coach_enabled", enabled).apply()
        _isDetailedCoachEnabled.value = enabled
    }

    fun isDetailedCoachAvailable(): Boolean {
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        val isConfigured = hasKey || hasOnDevice
        if (!isConfigured) return false
        return _isDetailedCoachEnabled.value
    }

    val isDetailedCoachAvailable: StateFlow<Boolean> = combine(
        _isDetailedCoachEnabled,
        _apiUiState,
        onDeviceState
    ) { enabled, _, _ ->
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        (hasKey || hasOnDevice) && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isDetailedCoachAvailable())

    // ── Dedicated VANTIX AI Insight Toggle (AdaptiveCore screen) ──────────────
    private val _isVantixEnabled = MutableStateFlow(
        settingsPrefs.getBoolean("ai_vantix_enabled", false)
    )
    val isVantixEnabled: StateFlow<Boolean> = _isVantixEnabled.asStateFlow()

    fun setVantixEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("ai_vantix_enabled", enabled).apply()
        _isVantixEnabled.value = enabled
        if (enabled) {
            loadVantixInsight()
        }
    }

    fun isVantixAvailable(): Boolean {
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        if (!hasKey && !hasOnDevice) return false
        return _isVantixEnabled.value
    }

    val isVantixAvailable: StateFlow<Boolean> = combine(
        _isVantixEnabled,
        _apiUiState,
        onDeviceState
    ) { enabled, _, _ ->
        val hasKey = savedApiKey().isNotBlank()
        val hasOnDevice = onDeviceLlmManager.isModelDownloaded()
        (hasKey || hasOnDevice) && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isVantixAvailable())

    private val _isChatGenerating = MutableStateFlow(false)
    val isChatGenerating: StateFlow<Boolean> = _isChatGenerating.asStateFlow()

    private var generationJob: Job? = null
    private val _aiTypingTick = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val aiTypingTick: SharedFlow<Unit> = _aiTypingTick.asSharedFlow()

    fun sendChatMessage(userText: String, imageUri: String? = null) {
        val text = userText.trim()
        if ((text.isBlank() && imageUri.isNullOrBlank()) || _isChatGenerating.value) return

        val displayContent = if (text.isNotBlank()) text else "📷 Food / Exercise Image Analysis"
        chatManager.addMessage(role = "user", content = displayContent, imageUri = imageUri)
        generationJob = viewModelScope.launch {
            _isChatGenerating.value = true
            var tokenCount = 0
            try {
                val curSession = chatManager.currentSession.value
                val telemetry = _liveTelemetry.value ?: HealthConnectTelemetry()
                val baseline = _userBaseline.value
                val chatProv = selectedChatProvider()
                val isOnDeviceDownloaded = onDeviceLlmManager.isModelDownloaded()
                val provider = if (chatProv == AiProvider.ON_DEVICE_LITERT && !isOnDeviceDownloaded) {
                    selectedAnalysisProvider().takeIf { it != AiProvider.ON_DEVICE_LITERT } ?: AiProvider.GEMINI
                } else {
                    chatProv
                }
                val apiKey = savedApiKey(provider).takeIf { it.isNotBlank() } ?: savedApiKey()
                val profile = _userProfile.value
                val history = _historicalRecords.value

                // Process multimodal image bytes if an image is attached
                var imageBase64: String? = null
                var imageMimeType: String? = null
                // OpenRouter (and its free fallbacks) are text-only — never send images.
                if (!imageUri.isNullOrBlank() && provider != AiProvider.OPENROUTER) {
                    val uri = android.net.Uri.parse(imageUri)
                    val processed = com.vanta.app.ui.utils.ImageUtils.processImageUri(getApplication(), uri)
                    if (processed != null) {
                        imageBase64 = processed.first
                        imageMimeType = processed.second
                    }
                }

                // Add empty assistant bubble and stream tokens live from GPU / Cloud API
                chatManager.addMessage(role = "assistant", content = "")
                val currentText = StringBuilder()

                gemmaEngine.generateChatResponseStreaming(
                    historyMessages = curSession.messages.dropLast(1),
                    userQuery = if (text.isNotBlank()) text else "Analyze this food or workout image and provide nutritional breakdown / guidance.",
                    telemetry = telemetry,
                    baseline = baseline,
                    apiKey = apiKey,
                    provider = provider,
                    profile = profile,
                    history = history,
                    imageBase64 = imageBase64,
                    imageMimeType = imageMimeType
                ).collect { tokenChunk ->
                    currentText.append(tokenChunk)
                    chatManager.updateLastAssistantMessage(currentText.toString())
                    tokenCount++
                    // Light "AI is typing" haptic on the first token, then throttled ticks.
                    if (tokenCount == 1 || tokenCount % 12 == 0) {
                        _aiTypingTick.tryEmit(Unit)
                    }
                }

                // Post-clean the finished message (strip stray JSON/fences from the stream).
                val cleanedFinal = com.vanta.app.data.ai.CoachChatPromptSystem.sanitizeOutput(currentText.toString())
                if (cleanedFinal != currentText.toString()) {
                    chatManager.updateLastAssistantMessage(cleanedFinal)
                }
            } catch (e: Throwable) {
                android.util.Log.e("VantaAiViewModel", "Chat generation failed", e)
                val errMsg = "Unable to reach coach right now. Please check your network connection or API key in Settings."
                chatManager.updateLastAssistantMessage(errMsg)
            } finally {
                chatManager.commitCurrentSession() // persist ONCE at the end, not per token
                _isChatGenerating.value = false
                generationJob = null
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _isChatGenerating.value = false
        chatManager.commitCurrentSession()
    }

    fun startNewChatSession() {
        chatManager.startNewSession()
    }

    fun selectChatSession(sessionId: String) {
        chatManager.loadSession(sessionId)
    }

    fun deleteChatSession(sessionId: String) {
        chatManager.deleteSession(sessionId)
        if (chatManager.sessions.value.isEmpty()) {
            com.vanta.app.data.ai.CoachMemoryStore.getInstance(getApplication()).clearMemory()
        }
    }

    fun clearAllChatSessions() {
        chatManager.clearAllSessions()
        com.vanta.app.data.ai.CoachMemoryStore.getInstance(getApplication()).clearMemory()
    }

    fun enterChatSession() {
        if (chatManager.currentSession.value.messages.isNotEmpty()) {
            chatManager.startNewSession()
        }
        if (selectedChatProvider() == AiProvider.ON_DEVICE_LITERT) {
            onDeviceLlmManager.preloadModelAsync()
            // Preload the llama.cpp weights in the background so the first on-device reply
            // isn't blocked by a ~1.1 GB load from disk.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { com.vanta.app.data.ai.VantaLllamaEngine(getApplication()).init() }
            }
        }
    }

    fun leaveChatSession() {
        if (selectedChatProvider() == AiProvider.ON_DEVICE_LITERT) {
            onDeviceLlmManager.unloadEngine()
        }
    }

    /**
     * Non-blocking async detail insight fetch (Recovery, Strain, Energy).
     * Returns Pair<InsightText, IsAiGenerated>.
     */
    suspend fun getDetailInsight(
        metric: com.vanta.app.ui.screens.PhysiologyMetric,
        context: android.content.Context
    ): Pair<String, Boolean> {
        val telemetry = _liveTelemetry.value ?: HealthConnectTelemetry()
        val baseline = _userBaseline.value
        val provider = selectedAnalysisProvider()
        val apiKey = savedApiKey(provider).takeIf { it.isNotBlank() } ?: savedApiKey()

        return gemmaEngine.generateDetailInsight(
            targetMetric = metric,
            telemetry = telemetry,
            baseline = baseline,
            apiKey = apiKey.takeIf { it.isNotBlank() },
            provider = provider,
            context = context,
            profile = _userProfile.value,
            history = _historicalRecords.value
        )
    }

    /**
     * Non-blocking async Vanta Coach deep-dive insight fetch (3-5 sentences).
     * Returns Pair<DeepInsightText, IsAiGenerated>.
     */
    suspend fun getVantaCoachInsight(
        metric: com.vanta.app.ui.screens.PhysiologyMetric,
        context: android.content.Context
    ): Pair<String, Boolean> {
        val telemetry = _liveTelemetry.value ?: HealthConnectTelemetry()
        val baseline = _userBaseline.value
        val provider = selectedAnalysisProvider()
        val apiKey = savedApiKey(provider).takeIf { it.isNotBlank() } ?: savedApiKey()

        return gemmaEngine.generateVantaCoachDeepInsight(
            targetMetric = metric,
            telemetry = telemetry,
            baseline = baseline,
            apiKey = apiKey.takeIf { it.isNotBlank() },
            provider = provider,
            context = context,
            profile = _userProfile.value,
            history = _historicalRecords.value
        )
    }

    /**
     * Streams Vanta Coach deep-dive insight in real time word-by-word.
     */
    fun streamVantaCoachInsight(
        metric: com.vanta.app.ui.screens.PhysiologyMetric,
        context: android.content.Context
    ): kotlinx.coroutines.flow.Flow<String> {
        val telemetry = _liveTelemetry.value ?: HealthConnectTelemetry()
        val baseline = _userBaseline.value
        val provider = selectedAnalysisProvider()
        val apiKey = savedApiKey(provider).takeIf { it.isNotBlank() } ?: savedApiKey()

        return gemmaEngine.streamVantaCoachDeepInsight(
            targetMetric = metric,
            telemetry = telemetry,
            baseline = baseline,
            apiKey = apiKey.takeIf { it.isNotBlank() },
            provider = provider,
            context = context,
            profile = _userProfile.value,
            history = _historicalRecords.value
        )
    }

    /**
     * Explicitly queries Health Connect for recent workouts and updates the StateFlow.
     */
    suspend fun fetchWorkouts(days: Int = 7): List<com.vanta.app.data.VantaWorkoutSession> {
        val workouts = healthConnectManager.fetchWorkouts(days)
        _recentWorkouts.value = workouts
        return workouts
    }

    /**
     * Retrieves previously cached Vanta Coach deep insight if valid against current telemetry.
     */
    fun getCachedVantaCoachInsight(metric: com.vanta.app.ui.screens.PhysiologyMetric): String? {
        val cache = com.vanta.app.data.ai.AiDetailInsightCache(getApplication())
        val entry = cache.load("DEEP_${metric.name}") ?: return null
        if (!entry.isAiGenerated) return null

        val telemetry = _liveTelemetry.value ?: HealthConnectTelemetry()
        val baseline = _userBaseline.value
        val det = com.vanta.app.data.VantaDeterministicPhysiologyEngine(getApplication())
            .calculatePhysiology(telemetry, baseline)
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

        val isValid = cache.isValid(
            entry = entry,
            targetRecovery = det.recovery,
            targetEnergy = det.energy,
            targetStrain = det.strain,
            targetWorkoutMinutes = telemetry.exerciseMinutes,
            currentDate = today,
            currentHour = currentHour
        )
        if (!isValid) return null

        val cleanText = com.vanta.app.data.ai.PhysiologyInsightPromptSystem.sanitizeInsightText(entry.text)
        val validated = com.vanta.app.data.ai.PhysiologyTemplateSelector.cleanAndValidateAiResponse(cleanText, maxWords = 120, maxSentences = 5)
        if (validated == null || validated.length < 20 || validated.startsWith("and ", ignoreCase = true) || validated.contains("{")) {
            cache.save("DEEP_${metric.name}", "", 0, 0, 0, 0.0, 0, "", false)
            return null
        }
        return validated
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // One-time data cleanup: pre-fix builds fabricated resting HR (daytime
            // minima or a hardcoded 60) into the history. Those rows are untrustworthy,
            // so clear legacy RHR once — genuine overnight readings (gated by sleep
            // tracking) repopulate the history going forward.
            if (!settingsPrefs.getBoolean("legacy_rhr_cleaned_v1", false)) {
                com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                    .dailyMetricsDao().clearLegacyRestingBpm()
                settingsPrefs.edit().putBoolean("legacy_rhr_cleaned_v1", true).apply()
            }

            // One-time data cleanup: purge any record older than today once,
            // then rebuild history from the user's REAL Health Connect past-day data.
            if (!settingsPrefs.getBoolean("simulated_history_cleaned_v1", false)) {
                com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                    .dailyMetricsDao().deleteRecordsBefore(
                        java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
                    )
                rolloverManager.rebuildHistoryFromHealthConnect()
                settingsPrefs.edit().putBoolean("simulated_history_cleaned_v1", true).apply()
            }

            // Daily gap-fill: Health Connect often syncs days late, so a day that
            // was missing/empty at rebuild time would be lost forever. Once per day,
            // backfill any missing past-day records from Health Connect's real data.
            val todayKey = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
            if (settingsPrefs.getString("history_fill_date", "") != todayKey) {
                rolloverManager.fillMissingHistoryFromHealthConnect()
                settingsPrefs.edit().putString("history_fill_date", todayKey).apply()
            }

            // One-time model update: recompute stored records through the current
            // intensity-aware strain formula (peak-HR aware, time-weighted) so history
            // and today's values reflect the corrected math, not stale outputs.
            if (!settingsPrefs.getBoolean("strain_model_v2_recomputed", false)) {
                rolloverManager.recomputeStoredRecords()
                settingsPrefs.edit().putBoolean("strain_model_v2_recomputed", true).apply()
            }

            // One-time calorie repair: refill past-day calories (real aggregate or the
            // distance+HR estimate) so history no longer shows 0 kcal.
            if (!settingsPrefs.getBoolean("calories_restored_v1", false)) {
                rolloverManager.refreshHistoricalCalories()
                settingsPrefs.edit().putBoolean("calories_restored_v1", true).apply()
            }

            // One-time step fix: re-fetch history with source-overlap dedup so the
            // phone + watch step records aren't double-counted into inflated totals.
            if (!settingsPrefs.getBoolean("steps_deduped_v1", false)) {
                rolloverManager.refreshHistoricalRecordsFromHealthConnect()
                settingsPrefs.edit().putBoolean("steps_deduped_v1", true).apply()
            }

            // One-time model update: recompute all stored records through the tuned
            // strain algorithm (diminishing-returns steps, workout-driven HR impulse)
            // so history and today reflect the corrected global math.
            if (!settingsPrefs.getBoolean("strain_model_v3", false)) {
                rolloverManager.recomputeStoredRecords()
                settingsPrefs.edit().putBoolean("strain_model_v3", true).apply()
            }

            // One-time HR display fix: days with no valid HR samples must never carry a
            // stale baseline-filled avg HR. Clears ONLY the HR columns — existing strain
            // values are preserved exactly as the user requested.
            if (!settingsPrefs.getBoolean("hr_display_fixed_v1", false)) {
                com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                    .dailyMetricsDao().clearAvgHrWhereNoHr()
                settingsPrefs.edit().putBoolean("hr_display_fixed_v1", true).apply()
            }

            // Check for midnight rollover catch-up
            rolloverManager.checkAndPerformRolloverIfNeeded()
            rolloverManager.scheduleMidnightWork()
            rolloverManager.schedulePeriodicTelemetrySync()

            // Load the user's onboarding profile (name, biometrics, goal) so the
            // greeting, coach text and calibration use their real data.
            _userProfile.value = runCatching {
                com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                    .userProfileDao().getUserProfile()
            }.getOrNull()

            refreshBaselineAndHistory()
            runAnalysis()
        }

        // Collect DB baseline updates
        viewModelScope.launch(Dispatchers.IO) {
            baselineManager.baselineFlow.collect { baseline ->
                _userBaseline.value = baseline
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                .userProfileDao().getUserProfileFlow().collect { profile ->
                    _userProfile.value = profile
                }
        }
    }

    /** Re-reads the user's profile from Room (e.g. right after onboarding completes). */
    fun refreshUserProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            _userProfile.value = runCatching {
                com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                    .userProfileDao().getUserProfile()
            }.getOrNull()
        }
    }

    suspend fun refreshBaselineAndHistory() {
        val b = baselineManager.getCurrentBaseline()
        val h = baselineManager.getHistoricalRecords()
        _userBaseline.value = b
        _historicalRecords.value = h
    }

    /**
     * Instantly synchronizes live steps from StepsScreen or HealthConnect.
     * Guarantees monotonic increase and updates Room DB + UI state with 0 lag.
     */
    fun updateLiveSteps(steps: Long, calories: Long, distanceKm: Double) {
        val current = _liveTelemetry.value ?: HealthConnectTelemetry()
        val higherSteps = maxOf(current.steps, steps)
        val higherCalories = maxOf(current.calories, calories)
        val higherDistance = maxOf(current.distanceKm, distanceKm)

        if (higherSteps > current.steps || higherCalories > current.calories || higherDistance > current.distanceKm) {
            val updated = current.copy(
                steps = higherSteps,
                calories = higherCalories,
                distanceKm = higherDistance
            )
            _liveTelemetry.value = updated
            _isStepsMeasured.value = true
            _isCaloriesMeasured.value = true
            _isDistanceMeasured.value = true

            viewModelScope.launch(Dispatchers.IO) {
                val todayDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
                val db = com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                val baseline = baselineManager.getCurrentBaseline()
                val detResult = com.vanta.app.data.VantaDeterministicPhysiologyEngine(getApplication())
                    .calculatePhysiology(updated, baseline)

                db.dailyMetricsDao().insertOrUpdate(
                    com.vanta.app.data.db.DailyMetricRecord.fromPhysiology(
                        date = todayDate,
                        timestamp = System.currentTimeMillis(),
                        restingBpm = detResult.rhrToday,
                        avgBpm = updated.avgBpm,
                        maxBpm = updated.peakBpm,
                        steps = updated.steps,
                        calories = updated.calories,
                        distanceKm = updated.distanceKm,
                        workoutDurationMin = updated.exerciseMinutes,
                        phys = detResult
                    )
                )
                refreshBaselineAndHistory()
            }
        }
    }

    fun reAnalyze() {
        if (analysisRunning) return
        android.util.Log.d("VantaAI", "RE-SYNC: Syncing telemetry and refreshing overview (reword if steps only, regenerate if physiology changed)")
        viewModelScope.launch(Dispatchers.Default) {
            runAnalysis(skipCloudCall = false, forceFresh = false)
        }
    }

    fun clearAiCache() {
        gemmaEngine.clearCache()
        _uiState.value = VantaAiUiState.Loading
        viewModelScope.launch(Dispatchers.Default) {
            runAnalysis(skipCloudCall = false, forceFresh = true)
        }
    }

    fun runAnalysis(skipCloudCall: Boolean = false, forceFresh: Boolean = false) {
        if (analysisRunning) return // skip if an analysis is already in flight
        analysisRunning = true
        if (forceFresh) {
            gemmaEngine.clearCache()
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Guarantee any completed day is archived and caches reset if midnight rolled over
                rolloverManager.checkAndPerformRolloverIfNeeded()
                refreshBaselineAndHistory()

                val baseline = baselineManager.getCurrentBaseline()
                _userBaseline.value = baseline

                // ── Instant-load path: paint today's last-persisted state from Room (0ms delay) ──
                val todayDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
                val db = com.vanta.app.data.db.VantaDatabase.getInstance(getApplication())
                val todayRecord = runCatching { db.dailyMetricsDao().getRecordForDate(todayDate) }.getOrNull()
                if (todayRecord != null) {
                    val det = com.vanta.app.data.VantaDeterministicPhysiologyEngine(getApplication())
                        .calculatePhysiology(todayRecord.toTelemetry(), baseline)
                    _liveTelemetry.value = todayRecord.toTelemetry()
                    val cachedAi = gemmaEngine.instantCachedAnalysis(todayRecord.toTelemetry(), det, baseline)
                    // Instantly show cached AI or instant deterministic insight immediately (<5ms)
                    _uiState.value = VantaAiUiState.Success(
                        cachedAi ?: gemmaEngine.generateDeterministicInsights(todayRecord.toTelemetry(), det, baseline)
                    )
                }

                // ── Full refresh: fetch Health Connect in one single parallel wave ──
                val snapshot = healthConnectManager.fetchTodayTelemetrySnapshot(_userProfile.value, forceFresh = forceFresh)
                val rawTelemetry = snapshot.telemetry

                val existingSteps = _liveTelemetry.value?.steps ?: todayRecord?.steps ?: 0L
                val existingCalories = _liveTelemetry.value?.calories ?: todayRecord?.calories ?: 0L
                val existingDistance = _liveTelemetry.value?.distanceKm ?: todayRecord?.distanceKm ?: 0.0

                val telemetry = rawTelemetry.copy(
                    steps = if (rawTelemetry.steps > 0) rawTelemetry.steps else maxOf(rawTelemetry.steps, existingSteps),
                    calories = if (rawTelemetry.calories > 0) rawTelemetry.calories else maxOf(rawTelemetry.calories, existingCalories),
                    distanceKm = if (rawTelemetry.distanceKm > 0.0) rawTelemetry.distanceKm else maxOf(rawTelemetry.distanceKm, existingDistance)
                )

                _isStepsMeasured.value = snapshot.stepsMeasured || existingSteps > 0
                _isCaloriesMeasured.value = snapshot.caloriesMeasured || existingCalories > 0
                _isDistanceMeasured.value = snapshot.distanceMeasured || existingDistance > 0
                _liveTelemetry.value = telemetry

                // Persist current day's telemetry, Strain, Recovery, and Energy into Room DB.
                val detResult = com.vanta.app.data.VantaDeterministicPhysiologyEngine(getApplication())
                    .calculatePhysiology(telemetry, baseline)
                val hasRealData = telemetry.steps > 100 ||
                    telemetry.avgBpm > 0 ||
                    telemetry.peakBpm > 0 ||
                    telemetry.exerciseMinutes > 0 ||
                    telemetry.calories > 50 ||
                    telemetry.distanceKm > 0.05

                // Fetch genuine workouts from Health Connect
                val workouts = healthConnectManager.fetchWorkouts(7)
                _recentWorkouts.value = workouts

                // Guarantee today's calculated metrics are saved into Room
                withContext(Dispatchers.IO) {
                    if (hasRealData) {
                        db.dailyMetricsDao().insertOrUpdate(
                            com.vanta.app.data.db.DailyMetricRecord.fromPhysiology(
                                date = todayDate,
                                timestamp = System.currentTimeMillis(),
                                restingBpm = detResult.rhrToday,
                                avgBpm = telemetry.avgBpm,
                                maxBpm = telemetry.peakBpm,
                                steps = telemetry.steps,
                                calories = telemetry.calories,
                                distanceKm = telemetry.distanceKm,
                                workoutDurationMin = telemetry.exerciseMinutes,
                                phys = detResult
                            )
                        )
                    }
                }

                // Foreground notification pass.
                val notificationDecision = com.vanta.app.data.notification.AiNotificationEngine(getApplication())
                    .evaluate(telemetry = telemetry, det = detResult, baseline = baseline)
                if (notificationDecision != null) {
                    com.vanta.app.data.notification.NotificationPoster.post(getApplication(), notificationDecision)
                }

                val hasChanged = gemmaEngine.hasDataChangedSinceCache(telemetry, detResult)
                val cachedAi = if (forceFresh) null else gemmaEngine.instantCachedAnalysis(telemetry, detResult, baseline)

                // Guarantee the UI always has live calculated insights immediately (0ms visual lag)
                if (_uiState.value !is VantaAiUiState.Success || cachedAi != null) {
                    _uiState.value = VantaAiUiState.Success(
                        cachedAi ?: gemmaEngine.generateDeterministicInsights(telemetry, detResult, baseline)
                    )
                }

                // ── Background AI overview upgrade (Gemini / DeepSeek / On-Device) ─────────────
                if (!skipCloudCall) {
                    if (forceFresh || hasChanged || cachedAi == null) {
                        val prov = selectedAnalysisProvider()
                        val result = gemmaEngine.analyzeHealthTelemetry(
                            telemetry,
                            baseline,
                            apiKey = savedApiKey(prov).takeIf { it.isNotBlank() },
                            provider = prov
                        )
                        _uiState.value = VantaAiUiState.Success(result)
                    } else {
                        _uiState.value = VantaAiUiState.Success(cachedAi)
                        android.util.Log.d("VantaAI", "Cached overview is valid — kept stable without calling AI")
                    }
                }

                // Keep history (incl. today's freshly-persisted row) in sync so detail
                // pages chart the newest numbers without an extra refresh.
                refreshBaselineAndHistory()
            } catch (e: Exception) {
                e.printStackTrace()
                // Never clobber instant content with an error if only the refresh failed.
                if (_uiState.value !is VantaAiUiState.Success) {
                    _uiState.value = VantaAiUiState.Error(e.message ?: "Failed to generate AI analysis")
                }
            } finally {
                analysisRunning = false
            }
        }
    }

    fun seedDevProfileAndPast3DaysData() {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverManager.seedDevProfileAndPast3DaysData()
            refreshBaselineAndHistory()
            runAnalysis()
        }
    }

    fun simulateMidnightRollover() {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverManager.performMidnightRollover()
            refreshBaselineAndHistory()
            runAnalysis()
        }
    }

    fun resetAllHistoricalData() {
        viewModelScope.launch(Dispatchers.IO) {
            rolloverManager.resetAllHistoricalData()
            refreshBaselineAndHistory()
            runAnalysis()
        }
    }

    // ── VANTIX AI Insight ─────────────────────────────────────────────────────

    private val _vantixInsight = MutableStateFlow<String?>(null)
    val vantixInsight: StateFlow<String?> = _vantixInsight.asStateFlow()

    private val _vantixInsightLoading = MutableStateFlow(false)
    val vantixInsightLoading: StateFlow<Boolean> = _vantixInsightLoading.asStateFlow()

    /**
     * Fetches the VANTIX AI insight for the current AdaptiveCoreResult.
     * Safe to call on every screen entry — the engine handles caching internally
     * (6-hour TTL + fingerprint invalidation) so it never makes redundant API calls.
     * No-op if core == null (still calibrating).
     */
    fun loadVantixInsight() {
        val core = _userBaseline.value.adaptiveCore ?: return
        // Respect the Vantix AI toggle: no AI call when the feature is disabled.
        if (!isVantixAvailable()) return
        if (_vantixInsightLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _vantixInsightLoading.value = true
            try {
                val provider = selectedAnalysisProvider()
                val apiKey = savedApiKey(provider).ifBlank { null }
                val insight = gemmaEngine.generateVantixInsight(core, apiKey, provider)
                _vantixInsight.value = insight
            } catch (e: Exception) {
                android.util.Log.w("VantixAI", "loadVantixInsight error", e)
            } finally {
                _vantixInsightLoading.value = false
            }
        }
    }
}

/** Reconstructs a telemetry snapshot from a persisted daily record (instant-load path). */
private fun com.vanta.app.data.db.DailyMetricRecord.toTelemetry() = com.vanta.app.data.HealthConnectTelemetry(
    steps = steps,
    calories = calories,
    distanceKm = distanceKm,
    currentBpm = 0,
    avgBpm = avgBpm,
    peakBpm = maxBpm,
    restingBpm = restingBpm,
    spo2Percent = 98.5,
    bodyTempCelsius = 36.6,
    exerciseMinutes = workoutDurationMin
)
