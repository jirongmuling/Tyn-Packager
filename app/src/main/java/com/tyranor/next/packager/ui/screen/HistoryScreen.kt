package com.tyranor.next.packager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tyranor.next.packager.data.BuildHistoryEntry
import com.tyranor.next.packager.ui.HistoryUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onInstall: (BuildHistoryEntry) -> Unit,
    onShare: (BuildHistoryEntry) -> Unit,
    onExport: (BuildHistoryEntry) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部记录？") },
            text = { Text("将删除所有打包记录及对应的 APK 文件，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("打包历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    if (state.entries.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            if (state.entries.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${state.entries.size} 个包 · 共 ${formatBytes(state.totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            when {
                state.loading -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                state.isEmpty -> EmptyHistory()
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.fileName }) { entry ->
                        HistoryItem(entry, onDelete, onInstall, onShare, onExport)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("还没有打包记录", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "生成直装包后会自动记录在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryItem(
    entry: BuildHistoryEntry,
    onDelete: (String) -> Unit,
    onInstall: (BuildHistoryEntry) -> Unit,
    onShare: (BuildHistoryEntry) -> Unit,
    onExport: (BuildHistoryEntry) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.appLabel.ifBlank { entry.packageName },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(entry.fileName) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                "${formatTime(entry.createdAt)} · ${formatBytes(entry.sizeBytes)}" +
                    if (entry.customSigned) " · 自定义签名" else " · 测试签名",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entry.gameTitle.isNotBlank()) {
                Text(
                    "游戏：${entry.gameTitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onInstall(entry) }) { Text("安装") }
                TextButton(onClick = { onShare(entry) }) { Text("分享") }
                TextButton(onClick = { onExport(entry) }) { Text("导出") }
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
