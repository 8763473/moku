package com.yue.moku.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yue.moku.AppViewModel
import com.yue.moku.StreamState
import com.yue.moku.data.MessageEntity
import com.yue.moku.domain.ContextBuilder
import com.yue.moku.domain.KnowledgeRetriever
import com.yue.moku.domain.TokenEstimator
import com.yue.moku.domain.formatTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val activeConversation by viewModel.activeConversation.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val recalled by viewModel.retrieved.collectAsStateWithLifecycle()
    val modelDetails by viewModel.modelDetails.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var pendingRegenerate by remember { mutableStateOf<RegenerateTarget?>(null) }
    val compressing by viewModel.isCompressing.collectAsStateWithLifecycle()
    val compressionNotice by viewModel.compressionNotice.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var previewingSummary by remember { mutableStateOf<String?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 通过跳转 state 触发滚动，避免在 LazyColumn scope 内直接捕获 listState
    var scrollTarget by remember { mutableStateOf(-1) }
    LaunchedEffect(scrollTarget) {
        if (scrollTarget >= 0) {
            listState.scrollToItem(scrollTarget, Int.MAX_VALUE)
            scrollTarget = -1
        }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }

    val effectiveMessages = remember(messages, stream) {
        if (stream.messageId == 0L) messages
        else messages.map {
            if (it.id == stream.messageId) it.copy(content = stream.content, reasoning = stream.reasoning) else it
        }
    }
    val breakdown = remember(effectiveMessages, settings, recalled) {
        ContextBuilder.build(
            systemPrompt = settings.systemPrompt,
            memoryPrompt = KnowledgeRetriever.toPrompt(recalled),
            history = effectiveMessages,
            contextWindow = settings.contextWindow,
        )
    }
    val usedTokens = breakdown.estimatedPromptTokens
    // 自动跟随只在用户没有主动浏览历史时生效。最后一条消息可能比屏幕高，
    // 所以不能用“最后一项可见”判断是否在底部。
    var autoFollow by remember(activeId) { mutableStateOf(true) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val canScrollForward = listState.canScrollForward
    LaunchedEffect(isUserDragging, canScrollForward) {
        when {
            isUserDragging -> autoFollow = false
            !canScrollForward -> autoFollow = true
        }
    }
    LaunchedEffect(messages.size, activeId) {
        if (messages.isNotEmpty()) {
            autoFollow = true
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }
    // 流式生成时仅在新 token 到达时才触发自动滚动，避免每个 token 都重组
    LaunchedEffect(stream.content.length, stream.reasoning.length) {
        if (messages.isNotEmpty() && autoFollow && !isUserDragging) {
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(compressionNotice) {
        compressionNotice?.let { notice ->
            val result = snackbar.showSnackbar(
                message = "压缩已完成，点击预览",
                actionLabel = "预览",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                previewingSummary = notice.summary
            }
            viewModel.consumeCompressionNotice()
        }
    }
    LaunchedEffect(notice) {
        notice?.let { message ->
            snackbar.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.consumeNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("写作会话", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("所有内容仅保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        viewModel.newConversation()
                        scope.launch { drawerState.close() }
                    }) { Icon(Icons.Default.Add, "新建会话") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    // 按树形结构展示：根对话 + 其分支
                    val rootList = conversations.filter { it.parentBranchId == null }
                    val branchMap = conversations.filter { it.parentBranchId != null }.groupBy { it.parentBranchId!! }
                    rootList.forEach { root ->
                        item(key = root.id) {
                            NavigationDrawerItem(
                                selected = root.id == activeId,
                                onClick = {
                                    viewModel.selectConversation(root.id)
                                    scope.launch { drawerState.close() }
                                },
                                label = { Text(root.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                badge = {
                                    // 删除整棵对话树（含所有子分支）。子分支数量提示。
                                    val childCount = branchMap[root.id]?.size ?: 0
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (childCount > 0) {
                                            Text("$childCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(2.dp))
                                        }
                                        IconButton(onClick = { viewModel.deleteConversation(root) }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Default.DeleteOutline, "删除会话", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                },
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                        // 子分支（缩进显示），带独立删除按钮
                        branchMap[root.id]?.forEach { branch ->
                            item(key = branch.id) {
                                NavigationDrawerItem(
                                    selected = branch.id == activeId,
                                    onClick = {
                                        viewModel.selectConversation(branch.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.AutoMirrored.Filled.AltRoute, "分支", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(6.dp))
                                            Text(branch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    badge = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("分支", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(4.dp))
                                            IconButton(onClick = { viewModel.deleteConversation(branch) }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.DeleteOutline, "删除分支", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(start = 28.dp, top = 1.dp, bottom = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("墨库", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "会话列表") }
                    },
                    actions = {
                        // 模型切换：限制最大宽度，避免长名称覆盖左侧菜单按钮
                        BoxWithConstraints {
                            val actionAreaWidth = maxWidth - 200.dp
                            TextButton(
                                onClick = { modelMenuExpanded = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.widthIn(max = actionAreaWidth),
                            ) {
                                Text(
                                    settings.model.ifBlank { "未配置模型" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                            ) {
                                // 已保存的模型
                                if (settings.savedModels.isNotEmpty()) {
                                    Text(
                                        "已保存的模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                    settings.savedModels.forEach { saved ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    saved,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = if (saved == settings.model) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            onClick = {
                                                viewModel.switchModel(saved)
                                                modelMenuExpanded = false
                                            },
                                            trailingIcon = {
                                                if (saved == settings.model) {
                                                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                }
                                // 从 API 获取的模型列表
                                if (modelDetails.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "点击刷新以获取模型列表",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                        onClick = {
                                            viewModel.fetchModels(settings)
                                            modelMenuExpanded = false
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) },
                                    )
                                } else {
                                    Text(
                                        "服务器模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                    modelDetails.forEach { detail ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        detail.id,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        fontWeight = if (detail.id == settings.model) FontWeight.SemiBold else FontWeight.Normal,
                                                    )
                                                    if (detail.state == "loaded") {
                                                        Spacer(Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = MaterialTheme.colorScheme.primaryContainer,
                                                        ) {
                                                            Text(
                                                                "已加载",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                viewModel.switchModel(detail.id)
                                                modelMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                                HorizontalDivider()
                                // 操作区
                                DropdownMenuItem(
                                    text = { Text("保存当前模型到列表", style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        viewModel.saveCurrentModel()
                                        modelMenuExpanded = false
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("刷新模型列表", style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        viewModel.fetchModels(settings)
                                        modelMenuExpanded = false
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) },
                                )
                            }
                        }
                        // 思考模式开关
                        TextButton(
                            onClick = viewModel::toggleThinkingMode,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                "思考",
                                color = if (settings.thinkingMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (settings.thinkingMode) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        IconButton(onClick = viewModel::newConversation) { Icon(Icons.Default.Add, "新建写作") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding(),
            ) {
                ContextMeter(usedTokens, settings.contextWindow, breakdown, recalled.size)
                if (compressing) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在压缩上下文…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (recalled.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recalled) { hit ->
                            AssistChip(
                                onClick = {},
                                label = { Text(hit.item.title, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.Storage, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                if (messages.isEmpty()) {
                    if (imeVisible) Spacer(Modifier.weight(1f)) else EmptyChat(Modifier.weight(1f))
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            state = listState,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                                // 流式生成中仅正在拼接的消息需要实时更新，其余消息保持静态以避免不必要的重组
                                val shown = if (stream.messageId > 0L && message.id == stream.messageId) {
                                    message.copy(content = stream.content, reasoning = stream.reasoning)
                                } else message
                                // 稳定化 lambda 引用：避免每个 token 都创建新的 callback 实例
                                val isStreamingMessage = generating && stream.messageId > 0L && message.id == stream.messageId
                                val showTokensPerSec = if (isStreamingMessage) stream.tokensPerSecond else 0.0
                                val showElapsedMs = if (isStreamingMessage) stream.elapsedMs else 0L
                                val showStopReason = if (isStreamingMessage) stream.stopReason else null
                                MessageCard(
                                    shown,
                                    streaming = isStreamingMessage,
                                    showReasoning = settings.thinkingMode,
                                    onDelete = { pendingDelete = message },
                                    onEdit = if (!generating && message.role == "user") { -> editingMessage = message } else null,
                                    onRegenerate = if (!generating) { -> pendingRegenerate = if (message.role == "user") RegenerateTarget.User(message) else RegenerateTarget.Ai(message) } else null,
                                    onReasoningOpened = { scrollTarget = index },
                                    tokensPerSecond = showTokensPerSec,
                                    elapsedMs = showElapsedMs,
                                    stopReason = showStopReason,
                                )
                            }
                        }
                        if (!autoFollow && canScrollForward && messages.isNotEmpty()) {
                            SmallFloatingActionButton(
                                onClick = {
                                    autoFollow = true
                                    scope.launch { listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE) }
                                },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            ) { Icon(Icons.Default.KeyboardArrowDown, "回到最新") }
                        }
                    }
                }
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    generating = generating,
                    onSend = {
                        val sending = draft
                        draft = ""
                        viewModel.send(sending)
                    },
                    onStop = viewModel::stopGenerating,
                )
            }
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条消息？") },
            text = { Text("此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(target)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    editingMessage?.let { target ->
        var draftText by remember(target) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    minLines = 3,
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newText = draftText.trim()
                        if (newText.isNotBlank()) {
                            viewModel.regenerateFromUserMessage(target, editedContent = newText)
                        }
                        editingMessage = null
                    },
                    enabled = draftText.isNotBlank(),
                ) { Text("保存并重新发送") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("取消") }
            },
        )
    }

    pendingRegenerate?.let { target ->
        val title = if (target is RegenerateTarget.User) "重新发送（创建新分支）" else "重新生成（创建新分支）"
        val body = if (target is RegenerateTarget.User) {
            "将创建新的分支对话，保留原对话不删除。新对话会包含当前消息及之前的历史。继续？"
        } else {
            "将创建新的分支对话，保留原对话不删除。新对话会包含上一条用户消息及之前的历史，并重新生成。继续？"
        }
        AlertDialog(
            onDismissRequest = { pendingRegenerate = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        is RegenerateTarget.User -> viewModel.regenerateFromUserMessage(target.message, editedContent = null)
                        is RegenerateTarget.Ai -> viewModel.regenerateFromAiMessage(target.message)
                    }
                    pendingRegenerate = null
                }) { Text("继续", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRegenerate = null }) { Text("取消") }
            },
        )
    }

    previewingSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { previewingSummary = null },
            title = { Text("压缩摘要预览") },
            text = {
                SelectionContainer {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { previewingSummary = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ContextMeter(used: Int, total: Int, breakdown: ContextBuilder.Result, recalledCount: Int) {
    var expanded by remember { mutableStateOf(false) }
    val ratio = (used.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("上下文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${formatTokens(used)} / ${formatTokens(total)} · ${(ratio * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Text(if (expanded) "收起" else "明细", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("系统提示 · ${formatTokens(breakdown.systemTokens)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("知识库 · ${formatTokens(breakdown.memoryTokens)}（${recalledCount} 条）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("对话历史 · ${formatTokens(breakdown.historyTokens)}（${breakdown.historyMessageCount} 条）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("合计 · ${formatTokens(used)} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Psychology, null, Modifier.padding(18.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(18.dp))
            Text("从一个念头开始", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "写下章节、人物或修改要求。墨库会在发送前检索你的知识库，并显示模型的思考与上下文占用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: MessageEntity,
    streaming: Boolean,
    showReasoning: Boolean = true,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onReasoningOpened: (() -> Unit)? = null,
    tokensPerSecond: Double = 0.0,
    elapsedMs: Long = 0L,
    stopReason: String? = null,
) {
    if (message.role == "system") return
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
    var reasoningOpen by remember(message.id, streaming) { mutableStateOf(streaming) }
    LaunchedEffect(reasoningOpen) {
        if (reasoningOpen) onReasoningOpened?.invoke()
    }
    val context = LocalContext.current
    val markwon = rememberMarkwon(context)
    val linkColor = MaterialTheme.colorScheme.primary
    val reasonAnnotated = remember(message.reasoning) {
        if (message.reasoning.isNotBlank()) markwon.render(message.reasoning, linkColor) else AnnotatedString("")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && showReasoning && message.reasoning.isNotBlank()) {
            Card(
                onClick = { reasoningOpen = !reasoningOpen },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
                modifier = Modifier.fillMaxWidth(0.94f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(7.dp))
                        Text(if (streaming) "正在思考" else "思考过程", style = MaterialTheme.typography.labelLarge)
                        if (streaming) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (reasoningOpen) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                    }
                    AnimatedVisibility(reasoningOpen) {
                        SelectionContainer {
                            Text(
                                reasonAnnotated,
                                modifier = Modifier.padding(top = 9.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
            ),
            elevation = CardDefaults.cardElevation(if (isUser) 0.dp else 1.dp),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 1f),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                if (message.content.isBlank() && streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("等待模型响应…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    val markdownAnnotated = remember(message.content) {
                        if (message.content.isNotBlank()) markwon.render(message.content, linkColor) else AnnotatedString("")
                    }
                    SelectionContainer {
                        Text(markdownAnnotated, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (!isUser && message.content.isNotBlank()) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val usage = listOfNotNull(
                                message.promptTokens?.let { "输入 ${formatTokens(it)}" },
                                message.completionTokens?.let { "输出 ${formatTokens(it)}" },
                            ).joinToString(" · ")
                            if (usage.isNotEmpty()) Text(usage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            if (onRegenerate != null) {
                                TextButton(
                                    onClick = onRegenerate,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Outlined.AutoAwesome, "重新生成", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("分叉", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.ContentCopy, "复制", Modifier.size(17.dp))
                            }
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, "删除", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        val showTps = streaming && tokensPerSecond > 0
                        val showElapsed = streaming || message.generationMs != null
                        val historicalTps: Double? = if ((message.generationMs ?: 0) > 100) {
                            val tokens = message.completionTokens
                                ?: TokenEstimator.estimate(message.content + message.reasoning)
                            if (tokens > 0) tokens.toDouble() / ((message.generationMs ?: 1000) / 1000.0) else null
                        } else null
                        val tpsToShow = if (streaming) tokensPerSecond else (historicalTps ?: 0.0)
                        val elapsedMsFinal = if (streaming) elapsedMs else (message.generationMs ?: 0L)
                        if (tpsToShow > 0 || message.completionTokens != null || elapsedMsFinal > 0) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (tpsToShow > 0) MetricChip(Icons.Outlined.Speed, "%.2f token/s".format(tpsToShow))
                                if (message.completionTokens != null) MetricChip(Icons.Outlined.Token, "${message.completionTokens} token")
                                if (elapsedMsFinal > 0) MetricChip(Icons.Outlined.Schedule, "%.2fs".format(elapsedMsFinal / 1000.0))
                            }
                        }
                        val finalStopReason = stopReason ?: message.stopReason
                        if (finalStopReason != null) {
                            Spacer(Modifier.height(2.dp))
                            Text("停止原因：$finalStopReason", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (isUser && message.content.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Edit, "编辑", Modifier.size(17.dp))
                            }
                        }
                        if (onRegenerate != null) {
                            TextButton(
                                onClick = onRegenerate,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Outlined.Refresh, "分叉重新发送", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("分叉发送", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, "删除", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private sealed class RegenerateTarget {
    data class User(val message: MessageEntity) : RegenerateTarget()
    data class Ai(val message: MessageEntity) : RegenerateTarget()
}

@Composable
private fun MetricChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("写下内容或要求…") },
                minLines = 1,
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
                enabled = !generating,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = if (generating) onStop else onSend,
                enabled = generating || draft.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(if (generating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (generating) "停止" else "发送")
            }
        }
    }
}
