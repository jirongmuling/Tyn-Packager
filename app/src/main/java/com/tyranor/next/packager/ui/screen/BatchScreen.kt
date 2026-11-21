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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tyranor.next.packager.ui.BatchItem
import com.tyranor.next.packager.ui.BatchStatus
import com.tyranor.next.packager.ui.BatchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    state: BatchUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批量打包") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.items.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "没有选中任何游戏。请在首页勾选后重试。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                return@Column
            }

            // 进度
            if (state.running || state.doneCount > 0) {
                val total = state.items.size
                Text(
                    "进度 ${state.doneCount}/$total · 成功 ${state.successCount}" +
                        if (state.failedCount > 0) " · 失败 ${state.failedCount}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { state.doneCount.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items) { item ->
                    BatchItemCard(item)
                }
            }

            Button(
                onClick = onStart,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.running) "打包中…" else "开始批量打包")
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BatchItemCard(item: BatchItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.game.title.ifBlank { "未命名" },
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    item.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.message.isNotBlank()) {
                    Text(
                        item.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (item.status) {
                            BatchStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                when (item.status) {
                    BatchStatus.Pending -> "等待"
                    BatchStatus.Running -> "进行中"
                    BatchStatus.Success -> "成功"
                    BatchStatus.Failed -> "失败"
                    BatchStatus.Skipped -> "跳过"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (item.status) {
                    BatchStatus.Success -> MaterialTheme.colorScheme.primary
                    BatchStatus.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
