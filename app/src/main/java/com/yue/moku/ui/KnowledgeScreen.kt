package com.yue.moku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yue.moku.AppViewModel
import com.yue.moku.data.KnowledgeEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(viewModel: AppViewModel) {
    val entries by viewModel.knowledge.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<KnowledgeEntity?>(null) }
    val filtered = remember(entries, search) {
        if (search.isBlank()) entries else entries.filter {
            listOf(it.title, it.content, it.tags, it.category).any { field -> field.contains(search, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("写作知识库", fontWeight = FontWeight.SemiBold) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; editorOpen = true }) {
                Icon(Icons.Default.Add, "添加知识")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("搜索要求、设定、人物或资料") },
                singleLine = true,
            )
            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(14.dp))
                    Text(if (search.isBlank()) "把以后还要遵守的要求放在这里" else "没有找到相关内容", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "固定条目会在每次对话中调用；普通条目会根据当前写作内容自动匹配。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        KnowledgeCard(
                            entry = entry,
                            onEdit = { editing = entry; editorOpen = true },
                            onDelete = { viewModel.deleteKnowledge(entry) },
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        KnowledgeEditor(
            original = editing,
            onDismiss = { editorOpen = false },
            onSave = { viewModel.saveKnowledge(it); editorOpen = false },
        )
    }
}

@Composable
private fun KnowledgeCard(entry: KnowledgeEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isPinned) {
                    Icon(Icons.Outlined.PushPin, "固定调用", Modifier.padding(end = 7.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!entry.isEnabled) Text("已停用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Text(entry.content, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onEdit, label = { Text(entry.category) })
                Spacer(Modifier.width(8.dp))
                Text("优先级 ${entry.priority}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.tags.isNotBlank()) {
                    Text(" · ${entry.tags}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除") }
            }
        }
    }
}

@Composable
private fun KnowledgeEditor(
    original: KnowledgeEntity?,
    onDismiss: () -> Unit,
    onSave: (KnowledgeEntity) -> Unit,
) {
    var title by remember(original) { mutableStateOf(original?.title.orEmpty()) }
    var content by remember(original) { mutableStateOf(original?.content.orEmpty()) }
    var tags by remember(original) { mutableStateOf(original?.tags.orEmpty()) }
    var category by remember(original) { mutableStateOf(original?.category ?: "要求") }
    var priority by remember(original) { mutableFloatStateOf((original?.priority ?: 3).toFloat()) }
    var pinned by remember(original) { mutableStateOf(original?.isPinned ?: false) }
    var enabled by remember(original) { mutableStateOf(original?.isEnabled ?: true) }
    val categories = listOf("要求", "设定", "人物", "资料")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "添加知识" else "编辑知识") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("类型", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { value ->
                            FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value) })
                        }
                    }
                }
                item {
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    OutlinedTextField(
                        content,
                        { content = it },
                        label = { Text(if (category == "要求") "具体要求（会原样提供给模型）" else "内容") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 10,
                    )
                }
                item {
                    OutlinedTextField(tags, { tags = it }, label = { Text("标签，用逗号分隔") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    Text("优先级 ${priority.toInt()}", style = MaterialTheme.typography.labelLarge)
                    Slider(value = priority, onValueChange = { priority = it }, valueRange = 1f..5f, steps = 3)
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("固定调用", style = MaterialTheme.typography.bodyLarge)
                            Text("每次发送都注入这条内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = pinned, onCheckedChange = { pinned = it })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("启用此条目", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave((original ?: KnowledgeEntity(title = "", content = "")).copy(
                        title = title.trim(),
                        content = content.trim(),
                        tags = tags.trim(),
                        category = category,
                        priority = priority.toInt(),
                        isPinned = pinned,
                        isEnabled = enabled,
                    ))
                },
                enabled = title.isNotBlank() && content.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
