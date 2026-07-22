package com.yue.moku.network

import com.yue.moku.data.ApiSettings
import com.yue.moku.domain.ApiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ApiDelta(
    val content: String = "",
    val reasoning: String = "",
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    /** 增量工具参数 JSON 片段，需要调用方累加拼接 */
    val toolCallArgumentsChunk: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

internal data class ChatRequestPlan(
    val messages: List<ApiMessage>,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
)

internal fun planChatRequest(
    thinkingMode: Boolean,
    messages: List<ApiMessage>,
): ChatRequestPlan = if (thinkingMode) {
    ChatRequestPlan(messages = messages)
} else {
    ChatRequestPlan(
        messages = messages,
        reasoningEffort = "none",
        enableThinking = false,
    )
}

class ChatApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 非流式 API 请求（test/listModels/testModel）不应无限等待，
    // 使用独立的短超时客户端。
    private val shortTimeoutClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun chat(
        settings: ApiSettings,
        messages: List<ApiMessage>,
        tools: List<JSONObject>? = null,
        toolChoice: String? = null,
    ): Flow<ApiDelta> = callbackFlow {
        val requestPlan = planChatRequest(settings.thinkingMode, messages)
        val payload = JSONObject().apply {
            put("model", settings.model)
            put("temperature", settings.temperature.toDouble())
            put("stream", settings.stream)
            put("messages", JSONArray().apply {
                requestPlan.messages.forEach { message ->
                    put(JSONObject().put("role", message.role).put("content", message.content))
                }
            })
            if (!tools.isNullOrEmpty()) put("tools", JSONArray(tools))
            if (!tools.isNullOrEmpty() && toolChoice != null) put("tool_choice", toolChoice)
            requestPlan.reasoningEffort?.let { put("reasoning_effort", it) }
            // enable_thinking / chat_template_kwargs 仅对非 OpenAI 官方端点发送
            // （如 LM Studio / DashScope / vLLM），避免严格兼容的 API 拒绝未知参数。
            requestPlan.enableThinking?.let { enabled ->
                if (!isOpenAiEndpoint(settings.baseUrl)) {
                    put("enable_thinking", enabled)
                    put("chat_template_kwargs", JSONObject().put("enable_thinking", enabled))
                }
            }
        }
        val request = Request.Builder()
            .url(chatUrl(settings.baseUrl))
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .header("Accept", if (settings.stream) "text/event-stream" else "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string().orEmpty()
                        close(IOException("API ${it.code}: ${errorText(body)}"))
                        return
                    }
                    try {
                        if (settings.stream) {
                            val source = it.body?.source() ?: error("响应内容为空")
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") break
                                if (data.isNotBlank()) trySend(parseDelta(JSONObject(data)))
                            }
                        } else {
                            val body = it.body?.string().orEmpty()
                            trySend(parseFull(JSONObject(body)))
                        }
                        close()
                    } catch (t: Throwable) {
                        if (call.isCanceled()) {
                            close()
                        } else {
                            close(IOException("模型响应中断：${t.message ?: "未知原因"}", t))
                        }
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    suspend fun test(settings: ApiSettings): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsUrl(settings.baseUrl))
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .get()
            .build()
        shortTimeoutClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("API ${response.code}: ${errorText(body)}")
            val json = JSONObject(body)
            val count = json.optJSONArray("data")?.length()
            if (count != null) "连接成功，发现 $count 个模型" else "连接成功"
        }
    }

    /** 解析 /v1/models 的 data[].id 列表 */
    suspend fun listModels(settings: ApiSettings): List<ModelDetail> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsUrl(settings.baseUrl))
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .get()
            .build()
        shortTimeoutClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("API ${response.code}: ${errorText(body)}")
            val data = JSONObject(body).optJSONArray("data")
                ?: return@use emptyList<ModelDetail>()
            (0 until data.length()).mapNotNull { index ->
                val obj = data.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id").orEmpty()
                if (id.isBlank()) null else ModelDetail(
                    id = id,
                    state = obj.optString("state").takeIf { it.isNotBlank() && it != "null" },
                    maxContextLength = obj.optIntOrNull("max_context_length"),
                    loadedContextLength = obj.optIntOrNull("loaded_context_length"),
                    arch = obj.optString("arch").takeIf { it.isNotBlank() && it != "null" },
                    quant = obj.optString("quant").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
    }

    /** 用 settings.model 真发一条 1-token 聊天请求，验证模型可用 */
    suspend fun testModel(settings: ApiSettings): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            put("max_tokens", 1)
            put("stream", false)
            put("temperature", 0)
        }.toString()
        val request = Request.Builder()
            .url(chatUrl(settings.baseUrl))
            .header("Content-Type", "application/json")
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        shortTimeoutClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("API ${response.code}: ${errorText(body)}")
            val parsed = JSONObject(body)
            val err = parsed.optJSONObject("error")?.optString("message", "")
            if (err.isNullOrBlank()) "模型 ${settings.model} 可用" else "模型 ${settings.model} 响应异常：$err"
        }
    }

    private fun parseDelta(json: JSONObject): ApiDelta {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta") ?: JSONObject()
        val usage = json.optJSONObject("usage")
        val toolCall = delta.optJSONArray("tool_calls")?.optJSONObject(0)
        val toolFunction = toolCall?.optJSONObject("function")
        return ApiDelta(
            content = delta.optString("content", "").takeUnless { it == "null" }.orEmpty(),
            reasoning = reasoningText(delta),
            toolCallId = toolCall?.optString("id").takeUnless { it.isNullOrBlank() || it == "null" },
            toolCallName = toolFunction?.optString("name").takeUnless { it.isNullOrBlank() || it == "null" },
            toolCallArgumentsChunk = toolFunction?.optString("arguments", "")
                .takeUnless { it.isNullOrBlank() || it == "null" },
            promptTokens = usage?.optIntOrNull("prompt_tokens"),
            completionTokens = usage?.optIntOrNull("completion_tokens"),
        )
    }

    private fun parseFull(json: JSONObject): ApiDelta {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: JSONObject()
        val usage = json.optJSONObject("usage")
        val toolCall = message.optJSONArray("tool_calls")?.optJSONObject(0)
        val toolFunction = toolCall?.optJSONObject("function")
        return ApiDelta(
            content = message.optString("content", ""),
            reasoning = reasoningText(message),
            toolCallId = toolCall?.optString("id").takeUnless { it.isNullOrBlank() || it == "null" },
            toolCallName = toolFunction?.optString("name").takeUnless { it.isNullOrBlank() || it == "null" },
            toolCallArgumentsChunk = toolFunction?.optString("arguments", "")
                .takeUnless { it.isNullOrBlank() || it == "null" },
            promptTokens = usage?.optIntOrNull("prompt_tokens"),
            completionTokens = usage?.optIntOrNull("completion_tokens"),
        )
    }

    private fun reasoningText(json: JSONObject): String = sequenceOf("reasoning_content", "reasoning")
        .map { json.optString(it, "") }
        .firstOrNull { it.isNotBlank() && it != "null" }
        .orEmpty()

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    /** 读取 /v1/models 时会设置此值；仅对 LM Studio / DashScope / vLLM 等非 OpenAI 端点发送 enable_thinking */
    private fun isOpenAiEndpoint(baseUrl: String): Boolean {
        val netloc = Regex("https?://(?:[^@\n]+@)?([^:\n/?]{2,})").find(baseUrl.trim())?.groupValues?.get(1)
            ?: return false
        return netloc.endsWith("api.openai.com") ||
            netloc.endsWith("openai.azure.com")
    }

    private fun chatUrl(base: String): String {
        val clean = base.trim().trimEnd('/')
        return if (clean.endsWith("chat/completions")) clean else "$clean/chat/completions"
    }

    private fun modelsUrl(base: String): String {
        val clean = base.trim().trimEnd('/').removeSuffix("/chat/completions")
        return "$clean/models"
    }

    private fun errorText(body: String): String = runCatching {
        val error = JSONObject(body).opt("error")
        when (error) {
            is JSONObject -> error.optString("message", body)
            is String -> error
            else -> body
        }
    }.getOrDefault(body).take(500)

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
