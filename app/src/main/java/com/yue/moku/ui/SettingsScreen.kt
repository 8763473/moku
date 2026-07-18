package com.yue.moku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yue.moku.AppViewModel
import com.yue.moku.data.ApiSettings
import com.yue.moku.domain.formatTokens
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val saved by viewModel.settings.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var contextText by remember(saved.contextWindow) { mutableStateOf(saved.contextWindow.toString()) }
    var recallText by remember(saved.recallCount) { mutableStateOf(saved.recallCount.toString()) }
    var showKey by remember { mutableStateOf(false) }
    val modelDetails by viewModel.modelDetails.collectAsStateWithLifecycle()
    val isLmStudio by viewModel.isLmStudio.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var modelMenuExpanded by remember { mutableStateOf(false) }

    fun normalized(): ApiSettings = draft.copy(
        contextWindow = contextText.toIntOrNull()?.coerceIn(1_024, 2_000_000) ?: saved.contextWindow,
        recallCount = recallText.toIntOrNull()?.coerceIn(1, 20) ?: saved.recallCount,
    )

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("模型与上下文", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionTitle("自定义 API")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = { Text("API Base URL") },
                    placeholder = { Text("http://192.168.1.10:1234/v1") },
                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    label = { Text("API Key（LM Studio 可留空）") },
                    leadingIcon = { Icon(Icons.Outlined.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏" else "显示")
                        }
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                ExposedDropdownMenuBox(
                    expanded = modelMenuExpanded,
                    onExpandedChange = { modelMenuExpanded = !modelMenuExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it) },
                        label = { Text("模型 ID") },
                        placeholder = { Text("qwen/qwen3-8b") },
                        leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.fetchModels(normalized()) },
                                    enabled = draft.baseUrl.isNotBlank(),
                                ) { Icon(Icons.Outlined.Refresh, "获取模型列表") }
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                            }
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        if (modelDetails.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isLmStudio) "未获取到模型，请检查 LM Studio 是否已开启 Local Server" else "点击右侧刷新以获取模型列表",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { modelMenuExpanded = false },
                                enabled = false,
                            )
                        } else {
                            modelDetails.forEach { detail ->
                                val label = buildString {
                                    if (isLmStudio && detail.state == "loaded") append("[已加载] ")
                                    append(detail.id)
                                    val ctx = detail.loadedContextLength?.takeIf { it > 0 } ?: detail.maxContextLength
                                    if (ctx != null) append(" · ${formatTokens(ctx)}")
                                    if (isLmStudio && !detail.arch.isNullOrBlank() && !detail.quant.isNullOrBlank()) {
                                        append("  ${detail.arch}/${detail.quant}")
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        draft = draft.copy(model = detail.id)
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.testSelectedModel(normalized()) },
                    enabled = draft.baseUrl.isNotBlank() && draft.model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Science, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("测试当前模型")
                }
            }

            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("连接 LM Studio", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "请在 LM Studio 中开启 Local Server 和局域网访问，手机填写电脑的局域网 IP，例如 http://192.168.1.10:1234/v1。模拟器可使用默认的 10.0.2.2。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle("生成参数")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = contextText,
                        onValueChange = { contextText = it.filter(Char::isDigit) },
                        label = { Text("模型上下文容量（tokens）") },
                        supportingText = { Text("应与模型实际加载时的 context length 一致") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    val suggested = modelDetails.firstOrNull {
                        it.id == draft.model && it.state == "loaded" && (it.loadedContextLength ?: 0) > 0
                    }?.loadedContextLength
                    if (suggested != null) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = { contextText = suggested.toString() },
                            label = { Text("使用 ${formatTokens(suggested)}") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Temperature · ${String.format(Locale.getDefault(), "%.2f", draft.temperature)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = draft.temperature, onValueChange = { draft = draft.copy(temperature = it) }, valueRange = 0f..2f)
                SettingSwitch(
                    title = "流式输出",
                    description = "边生成边显示正文和思考过程",
                    checked = draft.stream,
                    onChecked = { draft = draft.copy(stream = it) },
                )
                SettingSwitch(
                    title = "思考模式",
                    description = "显示模型的推理过程，关闭后只看正文",
                    checked = draft.thinkingMode,
                    onChecked = { draft = draft.copy(thinkingMode = it) },
                )
                SettingSwitch(
                    title = "自动压缩上下文",
                    description = "当占用超过阈值时自动生成早期对话的摘要",
                    checked = draft.autoCompress,
                    onChecked = { draft = draft.copy(autoCompress = it) },
                )
                if (draft.autoCompress) {
                    Text("压缩阈值 · ${(draft.compressThreshold * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = draft.compressThreshold,
                        onValueChange = { draft = draft.copy(compressThreshold = it) },
                        valueRange = 0.5f..0.95f,
                    )
                }
            }

            item {
                SectionTitle("知识库调用")
                Spacer(Modifier.height(8.dp))
                SettingSwitch(
                    title = "发送前自动检索",
                    description = "固定条目始终调用，其他条目按当前内容匹配",
                    checked = draft.autoRecall,
                    onChecked = { draft = draft.copy(autoRecall = it) },
                )
                OutlinedTextField(
                    value = recallText,
                    onValueChange = { recallText = it.filter(Char::isDigit) },
                    label = { Text("每次最多调用条目数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = draft.autoRecall,
                )
            }

            item {
                SectionTitle("系统提示词")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12,
                    label = { Text("长期生效的基础角色与写作原则") },
                )
            }

            item {
                Button(
                    onClick = { viewModel.saveSettings(normalized()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draft.baseUrl.isNotBlank() && draft.model.isNotBlank(),
                ) { Text("保存设置") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.compressContext() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Compress, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("立即压缩当前会话")
                }
                // 更新检查
                if (updateState !is AppViewModel.UpdateState.Idle) {
                    Spacer(Modifier.height(10.dp))
                    UpdateBanner(
                        state = updateState,
                        onCheck = { viewModel.checkForUpdate(force = true) },
                        onDownload = { viewModel.downloadAndInstall() },
                        onDismiss = { viewModel.consumeUpdateState() },
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.checkForUpdate(force = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.SystemUpdate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检查更新")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
