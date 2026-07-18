package com.yue.moku

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yue.moku.data.ApiSettings
import com.yue.moku.data.ConversationEntity
import com.yue.moku.data.GitHubRelease
import com.yue.moku.data.KnowledgeEntity
import com.yue.moku.data.MessageEntity
import com.yue.moku.domain.ContextBuilder
import com.yue.moku.domain.KnowledgeRetriever
import com.yue.moku.domain.RetrievedKnowledge
import com.yue.moku.domain.ThinkParser
import com.yue.moku.domain.TokenEstimator
import com.yue.moku.domain.ApiMessage
import com.yue.moku.network.ModelDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StreamState(
    val messageId: Long = 0,
    val content: String = "",
    val reasoning: String = "",
    val tokensPerSecond: Double = 0.0,
    val elapsedMs: Long = 0L,
    val stopReason: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val container: AppContainer,
    private val appContext: android.content.Context,
) : ViewModel() {

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        object UpToDate : UpdateState()
        data class Available(val release: GitHubRelease) : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        data class Ready(val apkFile: java.io.File) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }
    private val conversationDao = container.database.conversationDao()
    private val messageDao = container.database.messageDao()
    private val knowledgeDao = container.database.knowledgeDao()

    private val _activeConversationId = MutableStateFlow(0L)
    val activeConversationId: StateFlow<Long> = _activeConversationId.asStateFlow()
    val conversations = conversationDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages = _activeConversationId.flatMapLatest { id ->
        if (id == 0L) flowOf(emptyList()) else messageDao.observeForConversation(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knowledge = knowledgeDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = container.settings.settings

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()
    private val _stream = MutableStateFlow(StreamState())
    val stream = _stream.asStateFlow()
    private val _retrieved = MutableStateFlow<List<RetrievedKnowledge>>(emptyList())
    val retrieved = _retrieved.asStateFlow()
    private val _modelDetails = MutableStateFlow<List<ModelDetail>>(emptyList())
    val modelDetails: StateFlow<List<ModelDetail>> = _modelDetails.asStateFlow()
    val models: StateFlow<List<String>> = _modelDetails
        .map { it.map(ModelDetail::id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _isLmStudio = MutableStateFlow(false)
    val isLmStudio: StateFlow<Boolean> = _isLmStudio.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()
    data class CompressionNotice(val summary: String, val messageId: Long)
    private val _compressionNotice = MutableStateFlow<CompressionNotice?>(null)
    val compressionNotice: StateFlow<CompressionNotice?> = _compressionNotice.asStateFlow()
    fun consumeCompressionNotice() { _compressionNotice.value = null }
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    fun consumeUpdateState() { _updateState.value = UpdateState.Idle }
    private val _lastUpdateCheck = MutableStateFlow(0L)
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            _activeConversationId.value = conversationDao.latest()?.id
                ?: conversationDao.insert(ConversationEntity(title = "新的写作"))
        }
    }

    fun selectConversation(id: Long) {
        if (!_isGenerating.value) {
            _activeConversationId.value = id
            _retrieved.value = emptyList()
        }
    }

    fun newConversation() = viewModelScope.launch {
        if (_isGenerating.value) stopGenerating()
        _activeConversationId.value = conversationDao.insert(ConversationEntity(title = "新的写作"))
        _retrieved.value = emptyList()
    }

    fun deleteConversation(value: ConversationEntity) = viewModelScope.launch {
        if (value.id == _activeConversationId.value && _isGenerating.value) stopGenerating()
        conversationDao.delete(value)
        _activeConversationId.value = conversationDao.latest()?.id
            ?: conversationDao.insert(ConversationEntity(title = "新的写作"))
    }

    fun deleteMessage(value: MessageEntity) = viewModelScope.launch {
        if (_stream.value.messageId == value.id) stopGenerating()
        messageDao.delete(value)
    }

    fun send(text: String) {
        val prompt = text.trim()
        val conversationId = _activeConversationId.value
        if (prompt.isEmpty() || conversationId == 0L || _isGenerating.value || _isCompressing.value) return
        generationJob = viewModelScope.launch {
            executeSend(prompt)
        }
    }

    private suspend fun executeSend(prompt: String) {
        autoCompressIfNeeded()
        com.yue.moku.service.GenerationService.start(appContext)
        val conversationId = _activeConversationId.value
        val currentSettings = settings.value
        val conversation = conversationDao.get(conversationId) ?: return
        messageDao.insert(MessageEntity(conversationId = conversationId, role = "user", content = prompt))
        conversationDao.update(conversation.copy(
            title = if (conversation.title == "新的写作") prompt.replace('\n', ' ').take(24) else conversation.title,
            updatedAt = System.currentTimeMillis(),
        ))

        val matches = if (currentSettings.autoRecall) {
            KnowledgeRetriever.retrieve(prompt, knowledgeDao.listEnabled(), currentSettings.recallCount)
        } else emptyList()
        _retrieved.value = matches
        val history = messageDao.listForConversation(conversationId)
        val context = ContextBuilder.build(
            systemPrompt = currentSettings.systemPrompt,
            memoryPrompt = KnowledgeRetriever.toPrompt(matches),
            history = history,
            contextWindow = currentSettings.contextWindow,
        )
        val startedAt = System.currentTimeMillis()
        val assistantId = messageDao.insert(MessageEntity(conversationId = conversationId, role = "assistant", content = ""))
        _stream.value = StreamState(messageId = assistantId)
        _isGenerating.value = true
        var rawContent = ""
        var rawReasoning = ""
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        try {
            container.api.chat(currentSettings, context.messages).collect { delta ->
                rawContent += delta.content
                rawReasoning += delta.reasoning
                promptTokens = delta.promptTokens ?: promptTokens
                completionTokens = delta.completionTokens ?: completionTokens
                val parsed = ThinkParser.parse(rawContent, rawReasoning)
                _stream.value = StreamState(assistantId, parsed.content, parsed.reasoning)
                val elapsedMs = System.currentTimeMillis() - startedAt
                val elapsedSec = elapsedMs / 1000.0
                val totalTokens = completionTokens
                    ?: TokenEstimator.estimate(rawContent + rawReasoning)
                val tps = if (elapsedSec > 0.1) totalTokens / elapsedSec else 0.0
                _stream.value = _stream.value.copy(tokensPerSecond = tps, elapsedMs = elapsedMs)
            }
            val parsed = ThinkParser.parse(rawContent, rawReasoning)
            val finalElapsed = System.currentTimeMillis() - startedAt
            val finalTokens = completionTokens
                ?: TokenEstimator.estimate(rawContent + rawReasoning)
            val finalTps = if (finalElapsed > 100) finalTokens / (finalElapsed / 1000.0) else 0.0
            _stream.value = _stream.value.copy(
                tokensPerSecond = finalTps,
                elapsedMs = finalElapsed,
                stopReason = "生成完成",
            )
            messageDao.update(MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                role = "assistant",
                content = parsed.content,
                reasoning = parsed.reasoning,
                promptTokens = promptTokens ?: context.estimatedPromptTokens,
                completionTokens = completionTokens,
                generationMs = finalElapsed,
                stopReason = "生成完成",
            ))
        } catch (t: Throwable) {
            if (generationJob?.isCancelled != true) {
                _stream.value = _stream.value.copy(stopReason = friendlyError(t))
                val message = friendlyError(t)
                val partialElapsed = System.currentTimeMillis() - startedAt
                messageDao.update(MessageEntity(
                    id = assistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = message,
                    reasoning = _stream.value.reasoning,
                    isError = true,
                    stopReason = message,
                    generationMs = partialElapsed,
                ))
                _notice.value = message
            }
        } finally {
            _isGenerating.value = false
            _stream.value = StreamState()
            com.yue.moku.service.GenerationService.stop(appContext)
        }
    }

    fun regenerateFromUserMessage(userMessage: MessageEntity, editedContent: String? = null) = viewModelScope.launch {
        if (_isGenerating.value) return@launch
        val effectiveText = editedContent?.trim().orEmpty().ifBlank { userMessage.content }
        if (effectiveText.isEmpty()) return@launch
        // 编辑模式：就地更新原 user message content
        if (editedContent != null && effectiveText != userMessage.content) {
            messageDao.update(userMessage.copy(content = effectiveText))
        }
        // 删除该 user 消息之后的所有消息
        messageDao.deleteAfter(userMessage.conversationId, userMessage.id)
        // 复用 send 逻辑
        executeSend(effectiveText)
    }

    fun regenerateFromAiMessage(aiMessage: MessageEntity) = viewModelScope.launch {
        if (_isGenerating.value) return@launch
        val history = messageDao.listForConversation(aiMessage.conversationId)
        val userMessage = history.lastOrNull { it.role == "user" && it.id < aiMessage.id } ?: return@launch
        messageDao.deleteAfter(aiMessage.conversationId, userMessage.id)
        executeSend(userMessage.content)
    }

    fun stopGenerating() {
        val partial = _stream.value
        _stream.value = _stream.value.copy(stopReason = "用户停止")
        generationJob?.cancel()
        generationJob = null
        _isGenerating.value = false
        com.yue.moku.service.GenerationService.stop(appContext)
        _stream.value = StreamState()
        if (partial.messageId != 0L) {
            viewModelScope.launch {
                messageDao.update(MessageEntity(
                    id = partial.messageId,
                    conversationId = _activeConversationId.value,
                    role = "assistant",
                    content = partial.content.ifBlank { "（生成已停止）" },
                    reasoning = partial.reasoning,
                    generationMs = partial.elapsedMs.takeIf { it > 0 },
                    stopReason = "用户停止",
                ))
            }
        }
    }

    fun saveKnowledge(value: KnowledgeEntity) = viewModelScope.launch { knowledgeDao.upsert(value.copy(updatedAt = System.currentTimeMillis())) }
    fun deleteKnowledge(value: KnowledgeEntity) = viewModelScope.launch { knowledgeDao.delete(value) }
    fun saveSettings(value: ApiSettings) {
        container.settings.save(value)
        _notice.value = "设置已保存"
    }

    fun testConnection(value: ApiSettings) = viewModelScope.launch {
        _notice.value = "正在测试连接…"
        _notice.value = runCatching { container.api.test(value) }.getOrElse { friendlyError(it) }
    }

    fun fetchModels(value: ApiSettings) = viewModelScope.launch {
        _notice.value = "正在获取模型列表…"
        val result = runCatching { container.api.listModels(value) }
        _modelDetails.value = result.getOrElse { emptyList() }
        _isLmStudio.value = _modelDetails.value.any { it.state != null }
        _notice.value = result.fold(
            onSuccess = { if (it.isEmpty()) "未发现可用模型" else "已获取 ${it.size} 个模型" },
            onFailure = { friendlyError(it) },
        )
    }

    fun testSelectedModel(value: ApiSettings) = viewModelScope.launch {
        _notice.value = "正在测试模型 ${value.model}…"
        _notice.value = runCatching { container.api.testModel(value) }.getOrElse { friendlyError(it) }
    }

    fun compressContext() = viewModelScope.launch {
        if (_isCompressing.value) return@launch
        val conversationId = _activeConversationId.value
        if (conversationId == 0L) return@launch
        if (_isGenerating.value) stopGenerating()
        _isCompressing.value = true
        try {
            val history = messageDao.listForConversation(conversationId)
            if (history.size < 8) {
                _notice.value = "消息太少，无需压缩"
                return@launch
            }
            val keepRecent = 6
            val toCompress = history.dropLast(keepRecent)
            if (toCompress.isEmpty()) {
                _notice.value = "消息太少，无需压缩"
                return@launch
            }
            val concat = toCompress.joinToString("\n\n") { "[${it.role}] ${it.content}" }
            val summaryPrompt = "请用中文将以下对话内容压缩为简洁摘要，保留关键信息、人物、情节走向、设定，300 字以内。\n\n$concat"
            val settingsNow = settings.value
            val summaryBuilder = StringBuilder()
            container.api.chat(
                settingsNow.copy(stream = false, temperature = 0f),
                listOf(ApiMessage("user", summaryPrompt)),
            ).collect { delta -> summaryBuilder.append(delta.content) }
            val summary = summaryBuilder.toString()
            if (summary.isBlank()) {
                _notice.value = "压缩失败：模型未返回内容"
                return@launch
            }
            val firstOldId = toCompress.first().id
            messageDao.deleteAfter(conversationId, firstOldId - 1)
            val summaryId = messageDao.insert(MessageEntity(
                conversationId = conversationId,
                role = "system",
                content = "[历史摘要]\n\n$summary",
            ))
            _compressionNotice.value = CompressionNotice(summary = summary, messageId = summaryId)
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        } finally {
            _isCompressing.value = false
        }
    }

    fun autoCompressIfNeeded() = viewModelScope.launch {
        val currentSettings = settings.value
        if (!currentSettings.autoCompress) return@launch
        val conversationId = _activeConversationId.value
        if (conversationId == 0L) return@launch
        val usedTokens = ContextBuilder.build(
            currentSettings.systemPrompt,
            "",
            messageDao.listForConversation(conversationId),
            currentSettings.contextWindow,
        ).estimatedPromptTokens
        if (usedTokens.toFloat() / currentSettings.contextWindow > currentSettings.compressThreshold) {
            compressContext()
        }
    }

    fun consumeNotice() { _notice.value = null }

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("Failed to connect", true) -> "连接不到 API，请检查地址、端口和服务是否允许局域网访问。"
            raw.contains("timeout", true) -> "API 连接超时，请检查网络或模型是否仍在加载。"
            raw.contains("中断", true) || raw.contains("解析", true) || raw.contains("canceled", true) ->
                "模型响应中断，可能是网络问题或 App 被切到后台。请重试。"
            raw.isNotBlank() -> raw
            else -> "请求失败，请检查 API 配置。"
        }
    }

    fun checkForUpdate(force: Boolean = false) = viewModelScope.launch {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - _lastUpdateCheck.value < 24L * 60 * 60 * 1000L) return@launch
        }
        _lastUpdateCheck.value = System.currentTimeMillis()
        _updateState.value = UpdateState.Checking
        runCatching { container.update.fetchLatest(com.yue.moku.BuildConfig.UPDATE_CHECK_URL) }
            .onSuccess { release ->
                val latestTag = release.tagName.removePrefix("v").removePrefix("V")
                val currentVer = com.yue.moku.BuildConfig.VERSION_NAME
                if (isNewer(latestTag, currentVer)) {
                    _updateState.value = UpdateState.Available(release)
                } else {
                    _updateState.value = UpdateState.UpToDate
                }
            }
            .onFailure { _updateState.value = UpdateState.Error(friendlyError(it)) }
    }

    fun downloadAndInstall() = viewModelScope.launch {
        val release = (_updateState.value as? UpdateState.Available)?.release ?: return@launch
        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrBlank()) {
            _updateState.value = UpdateState.Error("该 release 没有 APK 资源，无法直接下载")
            return@launch
        }
        _updateState.value = UpdateState.Downloading(0f)
        val outFile = java.io.File(appContext.cacheDir, "updates/moku-update.apk")
        runCatching {
            container.update.downloadApk(apkUrl, outFile) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                _updateState.value = UpdateState.Downloading(progress)
            }
        }
            .onSuccess { file -> _updateState.value = UpdateState.Ready(file) }
            .onFailure { _updateState.value = UpdateState.Error(friendlyError(it)) }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrNull(i) ?: 0
            val b = c.getOrNull(i) ?: 0
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    class Factory(
        private val container: AppContainer,
        private val appContext: android.content.Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container, appContext) as T
    }
}
