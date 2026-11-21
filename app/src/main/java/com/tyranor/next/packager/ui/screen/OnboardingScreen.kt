package com.tyranor.next.packager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次启动引导。
 *
 * 说明三项前提：Tyranor 安装、文件访问权限、输出位置。
 * 全部说明清楚再让用户继续，避免权限被拒后困惑。
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            Text("Tyn Packager", style = MaterialTheme.typography.headlineMedium)
            Text(
                "把 Tyranor 中已识别的 Kirikiri 游戏，制作成独立安装的 APK。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            StepCard(
                index = "1",
                title = "安装 Tyranor",
                body = "本应用从 Tyranor 读取已扫描的游戏清单，不会自行扫描，" +
                    "也不会修改 Tyranor 的任何数据。",
            )
            StepCard(
                index = "2",
                title = "授予文件访问权限",
                body = "制作直装包需要读取游戏目录中的文件（.xp3 等）并把它们写入新 APK。" +
                    "Android 11 起需要「所有文件访问权限」才能直接按路径读取。",
            )
            StepCard(
                index = "3",
                title = "选择游戏并生成",
                body = "设置应用名、包名、图标后即可生成。产物默认保存在应用内，" +
                    "可随时安装、分享或导出到下载目录。",
            )

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onRequestAllFilesAccess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("授予文件访问权限")
            }

            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("开始使用")
            }

            Text(
                "权限仅用于读取游戏文件与写出 APK，不收集任何个人信息。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepCard(index: String, title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Text(
                index,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
