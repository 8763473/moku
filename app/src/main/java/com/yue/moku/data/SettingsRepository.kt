package com.yue.moku.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApiSettings(
    val baseUrl: String = "http://10.0.2.2:1234/v1",
    val apiKey: String = "",
    val model: String = "local-model",
    val contextWindow: Int = 32_768,
    val temperature: Float = 0.7f,
    val systemPrompt: String = "你是一位严谨、富有文学感的中文写作助手。尊重用户给出的设定、语气和格式要求。",
    val stream: Boolean = true,
    val thinkingMode: Boolean = true,
    val autoRecall: Boolean = true,
    val recallCount: Int = 6,
    val autoCompress: Boolean = true,
    val compressThreshold: Float = 0.8f,
    val allowAiWriteKnowledge: Boolean = false,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ApiSettings> = _settings.asStateFlow()

    fun save(value: ApiSettings) {
        prefs.edit {
            putString("base_url", value.baseUrl.trim().trimEnd('/'))
            putString("api_key", value.apiKey.trim())
            putString("model", value.model.trim())
            putInt("context_window", value.contextWindow.coerceIn(1024, 2_000_000))
            putFloat("temperature", value.temperature.coerceIn(0f, 2f))
            putString("system_prompt", value.systemPrompt)
            putBoolean("stream", value.stream)
            putBoolean("thinking_mode", value.thinkingMode)
            putBoolean("auto_recall", value.autoRecall)
            putInt("recall_count", value.recallCount.coerceIn(1, 20))
            putBoolean("auto_compress", value.autoCompress)
            putFloat("compress_threshold", value.compressThreshold)
            putBoolean("allow_ai_write_knowledge", value.allowAiWriteKnowledge)
        }
        _settings.value = load()
    }

    private fun load() = ApiSettings(
        baseUrl = prefs.getString("base_url", null) ?: ApiSettings().baseUrl,
        apiKey = prefs.getString("api_key", null) ?: "",
        model = prefs.getString("model", null) ?: ApiSettings().model,
        contextWindow = prefs.getInt("context_window", ApiSettings().contextWindow),
        temperature = prefs.getFloat("temperature", ApiSettings().temperature),
        systemPrompt = prefs.getString("system_prompt", null) ?: ApiSettings().systemPrompt,
        stream = prefs.getBoolean("stream", true),
        thinkingMode = prefs.getBoolean("thinking_mode", true),
        autoRecall = prefs.getBoolean("auto_recall", true),
        recallCount = prefs.getInt("recall_count", 6),
        autoCompress = prefs.getBoolean("auto_compress", true),
        compressThreshold = prefs.getFloat("compress_threshold", 0.8f),
        allowAiWriteKnowledge = prefs.getBoolean("allow_ai_write_knowledge", false),
    )
}
