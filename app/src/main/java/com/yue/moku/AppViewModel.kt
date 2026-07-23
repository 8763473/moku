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
import kotlinx.coroutines.CancellationException
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
    val activeConversation: StateFlow<ConversationEntity?> = conversations
        .map { list -> list.find { it.id == _activeConversationId.value } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
            try {
                _activeConversationId.value = conversationDao.latest()?.id
                    ?: conversationDao.insert(ConversationEntity(title = "新的写作"))
            } catch (t: Throwable) {
                _notice.value = "数据库初始化失败：${t.message}"
            }
        }
    }

    fun selectConversation(id: Long) {
        if (!_isGenerating.value) {
            _activeConversationId.value = id
            _retrieved.value = emptyList()
        }
    }

    fun newConversation() = viewModelScope.launch {
        try {
            if (_isGenerating.value) stopGenerating()
            _activeConversationId.value = conversationDao.insert(ConversationEntity(title = "新的写作"))
            _retrieved.value = emptyList()
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    fun deleteConversation(value: ConversationEntity) = viewModelScope.launch {
        try {
            val branches: List<ConversationEntity> = conversationDao.listBranches(value.id)
            if ((value.id == _activeConversationId.value ||
                branches.any { it.id == _activeConversationId.value }) &&
                _isGenerating.value) stopGenerating()
            // 级联删除所有子分支
            for (branch: ConversationEntity in branches) {
                conversationDao.delete(branch)
            }
            conversationDao.delete(value)
            if (value.id == _activeConversationId.value) {
                _activeConversationId.value = conversationDao.latest()?.id
                    ?: conversationDao.insert(ConversationEntity(title = "新的写作"))
            }
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    fun deleteMessage(value: MessageEntity) = viewModelScope.launch {
        try {
            if (_stream.value.messageId == value.id) stopGenerating()
            messageDao.delete(value)
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        val conversationId = _activeConversationId.value
        if (prompt.isEmpty() || conversationId == 0L || _isGenerating.value || _isCompressing.value) return
        // 先标记生成中再启动协程，消除 TOCTOU 竞态窗口
        _isGenerating.value = true
        generationJob = viewModelScope.launch {
            try {
                executeSend(prompt)
            } catch (t: Throwable) {
                _isGenerating.value = false
                _notice.value = friendlyError(t)
            }
        }
    }

    private suspend fun executeSend(prompt: String, existingUserMessage: MessageEntity? = null) {
        val conversationId = _activeConversationId.value
        val currentSettings = settings.value
        // 自动压缩放在 try 之前：如果压缩过程中 cancel 了当前协程，
        // 外部 send() 的 _isGenerating 标记需要回退。
        runCatching { maybeAutoCompress() }
        // 整个生成流程（含 DB 读写、Service 启动、API 调用）用统一 try-catch-finally 包裹，
        // 避免任何未捕获异常逃逸到 viewModelScope 导致进程崩溃。
        var assistantId = 0L
        var context: ContextBuilder.Result? = null
        var startedAt = 0L
        var assistantCreatedAt = 0L
        var rawContent = ""
        var rawReasoning = ""
        var toolCallName: String? = null
        var toolCallArgJson = StringBuilder()
        try {
            val conversation = conversationDao.get(conversationId) ?: run {
                _isGenerating.value = false
                return
            }
            com.yue.moku.service.GenerationService.start(appContext)
            // existingUserMessage 非 null 表示"重新发送/重新生成"：复用原 user 消息，避免新增一条
            if (existingUserMessage != null) {
                if (prompt != existingUserMessage.content) {
                    messageDao.update(existingUserMessage.copy(content = prompt))
                }
            } else {
                messageDao.insert(MessageEntity(conversationId = conversationId, role = "user", content = prompt))
            }
            conversationDao.update(conversation.copy(
                title = if (conversation.title == "新的写作") prompt.replace('\n', ' ').take(24) else conversation.title,
                updatedAt = System.currentTimeMillis(),
            ))

            val matches = if (currentSettings.autoRecall) {
                KnowledgeRetriever.retrieve(prompt, knowledgeDao.listEnabled(), currentSettings.recallCount)
            } else emptyList()
            _retrieved.value = matches
            val history = messageDao.listForConversation(conversationId)
            val isWriteIntent = currentSettings.allowAiWriteKnowledge &&
                com.yue.moku.domain.KnowledgeWriteIntent.isWriteRequest(prompt)
            val tools = if (isWriteIntent) listOf(com.yue.moku.domain.KnowledgeWriteIntent.buildToolSchema()) else null
            val systemPrompt = if (tools != null) {
                currentSettings.systemPrompt + "\n\n" + com.yue.moku.domain.KnowledgeWriteIntent.systemPromptHint()
            } else {
                currentSettings.systemPrompt
            }
            context = ContextBuilder.build(
                systemPrompt = systemPrompt,
                memoryPrompt = KnowledgeRetriever.toPrompt(matches),
                history = history,
                contextWindow = currentSettings.contextWindow,
            )
            startedAt = System.currentTimeMillis()
            assistantCreatedAt = System.currentTimeMillis()
            assistantId = messageDao.insert(MessageEntity(conversationId = conversationId, role = "assistant", content = "", createdAt = assistantCreatedAt))
            _stream.value = StreamState(messageId = assistantId)
            var promptTokens: Int? = null
            var completionTokens: Int? = null
            // 工具调用累加器：流式响应里 tool_calls.arguments 会分多片到达
            var lastStreamUpdate = 0L
            container.api.chat(currentSettings, context.messages, tools = tools).collect { delta ->
                rawContent += delta.content
                rawReasoning += delta.reasoning
                promptTokens = delta.promptTokens ?: promptTokens
                completionTokens = delta.completionTokens ?: completionTokens
                if (!delta.toolCallName.isNullOrBlank()) toolCallName = delta.toolCallName
                delta.toolCallArgumentsChunk?.let { toolCallArgJson.append(it) }
                // 节流：每 80ms 更新一次 UI，减少每个 token 触发 Compose 重组造成的卡顿
                val now = System.currentTimeMillis()
                if (now - lastStreamUpdate >= 80) {
                    lastStreamUpdate = now
                    val parsed = ThinkParser.parse(rawContent, rawReasoning)
                    _stream.value = StreamState(assistantId, parsed.content, parsed.reasoning)
                    val elapsedMs = now - startedAt
                    val elapsedSec = elapsedMs / 1000.0
                    val totalTokens = completionTokens
                        ?: TokenEstimator.estimate(rawContent + rawReasoning)
                    val tps = if (elapsedSec > 0.1) totalTokens / elapsedSec else 0.0
                    _stream.value = _stream.value.copy(tokensPerSecond = tps, elapsedMs = elapsedMs)
                }
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
            // 工具调用处理：模型只在用户明确要求写入时才会拿到 save_knowledge 工具
            if (toolCallName == "save_knowledge") {
                handleSaveKnowledgeToolCall(toolCallArgJson.toString())
            }
            messageDao.update(MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                role = "assistant",
                content = parsed.content,
                reasoning = parsed.reasoning,
                createdAt = assistantCreatedAt,
                promptTokens = promptTokens ?: context.estimatedPromptTokens,
                completionTokens = completionTokens,
                generationMs = finalElapsed,
                stopReason = "生成完成",
            ))
        } catch (t: CancellationException) {
            // 用户主动停止：如果 assistant 消息已插入，保存部分内容；否则仅清理
            val savedAssistantId = assistantId
            if (savedAssistantId > 0) {
                val parsed = ThinkParser.parse(rawContent, rawReasoning)
                val partialElapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
                messageDao.update(MessageEntity(
                    id = savedAssistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = parsed.content.ifBlank { "（生成已停止）" },
                    reasoning = parsed.reasoning,
                    createdAt = assistantCreatedAt,
                    generationMs = partialElapsed.takeIf { it > 0 },
                    stopReason = "用户停止",
                ))
            }
        } catch (t: Throwable) {
            val message = friendlyError(t)
            val savedAssistantId = assistantId
            if (savedAssistantId > 0) {
                val partialElapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
                messageDao.update(MessageEntity(
                    id = savedAssistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = message,
                    reasoning = rawReasoning,
                    createdAt = assistantCreatedAt,
                    isError = true,
                    stopReason = message,
                    generationMs = partialElapsed,
                ))
            }
            _stream.value = _stream.value.copy(stopReason = message)
            _notice.value = message
        } finally {
            _isGenerating.value = false
            _stream.value = StreamState()
            generationJob = null
            com.yue.moku.service.GenerationService.stop(appContext)
        }
    }

    /** 从用户消息分叉：创建新对话，复制分叉点之前的历史，然后重新发送 */
    fun regenerateFromUserMessage(userMessage: MessageEntity, editedContent: String? = null) = viewModelScope.launch {
        if (_isGenerating.value || _isCompressing.value) return@launch
        _isGenerating.value = true
        try {
            val effectiveText = editedContent?.trim().orEmpty().ifBlank { userMessage.content }
            if (effectiveText.isEmpty()) { _isGenerating.value = false; return@launch }
            copyMessagesUpTo(userMessage, effectiveText)
        } catch (t: Throwable) {
            _isGenerating.value = false
            _notice.value = friendlyError(t)
        }
    }

    /** 从 AI 消息分叉：以上一条 user 消息为分叉点创建新对话 */
    fun regenerateFromAiMessage(aiMessage: MessageEntity) = viewModelScope.launch {
        if (_isGenerating.value || _isCompressing.value) return@launch
        _isGenerating.value = true
        try {
            val history = messageDao.listForConversation(aiMessage.conversationId)
            val userMessage = history.lastOrNull { it.role == "user" && it.id < aiMessage.id } ?: run {
                _isGenerating.value = false
                return@launch
            }
            copyMessagesUpTo(userMessage, userMessage.content)
        } catch (t: Throwable) {
            _isGenerating.value = false
            _notice.value = friendlyError(t)
        }
    }

    /**
     * 分支操作：复制 [forkPoint] 之前（不含 forkPoint 自身）的所有消息到新对话，
     * 新对话的 parentBranchId 指向原对话。然后切换到新对话并发送 newPrompt。
     *
     * 注意：forkPoint 是分叉位置的 user 消息 (role == "user")，不应被复制到新
     * 对话中 — 否则 executeSend(newPrompt) 会再插入一条 user 消息导致重复。
     */
    private suspend fun copyMessagesUpTo(forkPoint: MessageEntity, newPrompt: String) {
        val oldConversationId = forkPoint.conversationId
        val oldConversation = conversationDao.get(oldConversationId) ?: run {
            _isGenerating.value = false
            return
        }
        val history = messageDao.listForConversation(oldConversationId)
        // 取 forkPoint 之前的所有消息（不含 forkPoint 自身），
        // forkPoint 是 user 消息，由 executeSend 重新插入。
        val messagesToCopy = history.filter { it.id < forkPoint.id && it.role != "system" }
        // 创建新对话作为分支
        val branchTitle = if (oldConversation.title == "新的写作") effectiveBranchTitle(history, forkPoint)
        else "${oldConversation.title} (分支)"
        val newConvId = conversationDao.insert(
            ConversationEntity(
                title = branchTitle,
                parentBranchId = oldConversationId,
                forkMessageId = forkPoint.id,
            )
        )
        // 复制分叉点之前的所有非 system 消息
        for (msg in messagesToCopy) {
            messageDao.insert(msg.copy(id = 0, conversationId = newConvId))
        }
        // 切换到此新对话并发送
        _activeConversationId.value = newConvId
        executeSend(newPrompt)
    }

    /** 从历史记录中推导分支标题 */
    private fun effectiveBranchTitle(history: List<MessageEntity>, forkPoint: MessageEntity): String {
        val userMessages = history.filter { it.role == "user" && it.id <= forkPoint.id }
        if (userMessages.isNotEmpty()) {
            val last = userMessages.last().content.trim()
            return last.take(24).replace('\n', ' ')
        }
        return "分支对话"
    }

    fun stopGenerating() {
        _stream.value = _stream.value.copy(stopReason = "用户停止")
        generationJob?.cancel()
        // finally 块统一清理 _isGenerating/stream/generationJob/Service；
        // 这里不再重复写，避免与 finally 竞态。
    }

    fun saveKnowledge(value: KnowledgeEntity) = viewModelScope.launch {
        try {
            knowledgeDao.upsert(value.copy(updatedAt = System.currentTimeMillis()))
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }
    fun deleteKnowledge(value: KnowledgeEntity) = viewModelScope.launch {
        try {
            knowledgeDao.delete(value)
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }
    fun saveSettings(value: ApiSettings) {
        container.settings.save(value)
        _notice.value = "设置已保存"
    }

    /** 主页快捷切换思考模式：开→允许并显示推理，关→请求模型直接输出正文。 */
    fun toggleThinkingMode() = viewModelScope.launch {
        try {
            val current = settings.value
            saveSettings(current.copy(thinkingMode = !current.thinkingMode))
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    /** 主页快捷切换模型，同时刷新模型列表以供后续切换。 */
    fun switchModel(targetModel: String) = viewModelScope.launch {
        try {
            val current = settings.value
            val trimmed = targetModel.trim()
            if (trimmed.isBlank() || trimmed == current.model) return@launch
            container.settings.save(current.copy(model = trimmed))
            _notice.value = "已切换到 $trimmed"
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    /** 将当前模型加入已保存列表（去重） */
    fun saveCurrentModel() = viewModelScope.launch {
        try {
            val current = settings.value
            val model = current.model.trim()
            if (model.isBlank()) return@launch
            val list = current.savedModels.toMutableList()
            if (list.contains(model)) {
                _notice.value = "$model 已在列表中"
                return@launch
            }
            list.add(model)
            saveSettings(current.copy(savedModels = list))
            _notice.value = "已加入 $model"
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    /** 从已保存列表中移除一个模型 */
    fun removeSavedModel(modelId: String) = viewModelScope.launch {
        try {
            val current = settings.value
            saveSettings(current.copy(savedModels = current.savedModels - modelId))
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
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

    private suspend fun maybeAutoCompress() {
        val currentSettings = settings.value
        if (!currentSettings.autoCompress) return
        val conversationId = _activeConversationId.value
        if (conversationId == 0L) return
        val usedTokens = ContextBuilder.build(
            currentSettings.systemPrompt,
            "",
            messageDao.listForConversation(conversationId),
            currentSettings.contextWindow,
        ).estimatedPromptTokens
        if (usedTokens.toFloat() / currentSettings.contextWindow <= currentSettings.compressThreshold) return
        try {
            performCompression()
        } catch (t: CancellationException) {
            // 压缩中断：已被取消的协程不需要回退
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    fun compressContext() = viewModelScope.launch {
        try {
            performCompression()
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        }
    }

    /**
     * 实际执行压缩的核心逻辑。由 maybeAutoCompress（发送前自动检查）或
     * compressContext（用户手动触发）调用。
     */
    private suspend fun performCompression() {
        if (_isCompressing.value) return
        val conversationId = _activeConversationId.value
        if (conversationId == 0L) return
        if (_isGenerating.value) stopGenerating()
        _isCompressing.value = true
        try {
            val history = messageDao.listForConversation(conversationId)
            if (history.size < 8) {
                _notice.value = "消息太少，无需压缩"
                return
            }
            val keepRecent = 6
            val toCompress = history.dropLast(keepRecent)
            if (toCompress.isEmpty()) {
                _notice.value = "消息太少，无需压缩"
                return
            }
            val concat = toCompress.joinToString("\n\n") { "[${it.role}] ${it.content}" }
            val summaryPrompt = "请用中文将以下对话内容压缩为简洁摘要，保留关键信息、人物、情节走向、设定，300 字以内。\n\n$concat"
            val settingsNow = settings.value
            val summaryBuilder = StringBuilder()
            // 使用 short timeout 的调用方式，避免非流式 API 无限等待
            container.api.chat(
                settingsNow.copy(stream = false, temperature = 0f),
                listOf(ApiMessage("user", summaryPrompt)),
            ).collect { delta ->
                val chunk = delta.content
                if (chunk.isNotBlank()) summaryBuilder.append(chunk)
            }
            val summary = summaryBuilder.toString()
            if (summary.isBlank()) {
                _notice.value = "压缩失败：模型未返回内容"
                return
            }
            val firstOldId = toCompress.first().id
            val lastOldId = toCompress.last().id
            messageDao.deleteRange(conversationId, firstOldId, lastOldId)
            val summaryId = messageDao.insert(MessageEntity(
                conversationId = conversationId,
                role = "system",
                content = "[历史摘要]\n\n$summary",
            ))
            _compressionNotice.value = CompressionNotice(summary = summary, messageId = summaryId)
        } catch (t: CancellationException) {
            // 压缩取消是正常场景（如用户同时在发消息），不需要错误提示
        } catch (t: Throwable) {
            _notice.value = friendlyError(t)
        } finally {
            _isCompressing.value = false
        }
    }

    fun consumeNotice() { _notice.value = null }

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("Failed to connect", true) -> "连接不到 API，请检查地址、端口和服务是否允许局域网访问。"
            raw.contains("timeout", true) -> "API 连接超时，请检查网络或模型是否仍在加载。"
            raw.startsWith("模型响应中断：") -> {
                val inner = raw.removePrefix("模型响应中断：").trim()
                if (inner.isNotBlank() && inner != "未知原因") inner
                else "模型响应中断，可能是网络问题或 App 被切到后台。请重试。"
            }
            raw.contains("canceled", true) -> "生成已取消"
            raw.isNotBlank() -> raw
            else -> "请求失败，请检查 API 配置。"
        }
    }

    /** 解析并执行 save_knowledge 工具调用。arguments 是模型给出的 JSON 字符串。 */
    private suspend fun handleSaveKnowledgeToolCall(argumentsJson: String) {
        try {
            if (argumentsJson.isBlank()) {
                _notice.value = "AI 试图写入知识库，但参数为空，已忽略"
                return
            }
            val args = try {
                org.json.JSONObject(argumentsJson)
            } catch (t: Throwable) {
                _notice.value = "AI 试图写入知识库但参数解析失败，已忽略"
                return
            }
            val title = args.optString("title", "").trim()
            val content = args.optString("content", "").trim()
            if (title.isEmpty() || content.isEmpty()) {
                _notice.value = "AI 试图写入知识库但缺少标题或内容，已忽略"
                return
            }
            val category = args.optString("category", "设定").takeUnless { it.isBlank() } ?: "设定"
            val tags = args.optString("tags", "").trim()
            val isPinned = args.optBoolean("isPinned", false)
            val now = System.currentTimeMillis()
            val entity = KnowledgeEntity(
                title = title.take(64),
                content = content.take(4000),
                category = category,
                tags = tags,
                isPinned = isPinned,
                updatedAt = now,
            )
            knowledgeDao.upsert(entity)
            _notice.value = "AI 已写入知识库：$title"
        } catch (t: Throwable) {
            _notice.value = "AI 写入知识库失败：${friendlyError(t)}"
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
        try {
            val file = container.update.downloadApk(apkUrl, outFile) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                _updateState.value = UpdateState.Downloading(progress)
            }
            _updateState.value = UpdateState.Ready(file)
        } catch (t: Throwable) {
            _updateState.value = UpdateState.Error(friendlyError(t))
        }
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
