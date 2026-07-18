package com.yue.moku.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yue.moku.AppViewModel.UpdateState

@Composable
fun UpdateBanner(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is UpdateState.Error -> MaterialTheme.colorScheme.errorContainer
                is UpdateState.Available -> MaterialTheme.colorScheme.tertiaryContainer
                is UpdateState.Ready -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            when (state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在检查更新…", style = MaterialTheme.typography.bodyMedium)
                }
                UpdateState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("已是最新版本", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                is UpdateState.Available -> {
                    Text("新版本可用：${state.release.tagName}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    if (state.release.body.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(state.release.body, style = MaterialTheme.typography.bodySmall, maxLines = 6)
                    }
                    if (state.release.apkUrl == null) {
                        Spacer(Modifier.height(8.dp))
                        Text("该 release 未附带 APK 文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onDownload, enabled = state.release.apkUrl != null) {
                            Icon(Icons.Outlined.CloudDownload, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("下载更新")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("稍后") }
                    }
                }
                is UpdateState.Downloading -> {
                    Text("下载中… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
                is UpdateState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("下载完成，正在打开安装器", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Error, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(onClick = onCheck) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}
