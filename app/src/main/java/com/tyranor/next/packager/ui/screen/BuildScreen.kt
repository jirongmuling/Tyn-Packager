package com.tyranor.next.packager.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tyranor.next.packager.apk.BuildResult
import com.tyranor.next.packager.apk.IconSize
import com.tyranor.next.packager.cover.CoverCandidate
import com.tyranor.next.packager.apk.BuildStage
import com.tyranor.next.packager.ui.BuildUiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    state: BuildUiState,
    onBack: () -> Unit,
    onPackageNameChange: (String) -> Unit,
    onAppLabelChange: (String) -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onIconSizeChange: (IconSize) -> Unit,
    onFetchCover: () -> Unit,
    onApplyCover: (CoverCandidate) -> Unit,
    onUseCustomSigning: (Boolean) -> Unit,
    onPickKeystore: () -> Unit,
    onKeystorePasswordChange: (String) -> Unit,
    onKeystoreAliasChange: (String) -> Unit,
    onVerifyKeystore: () -> Unit,
    onBuild: () -> Unit,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit,
    onExport: (File) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.game?.title ?: "制作直装包") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            GameInfoCard(state)
            BasicInfoCard(state, onPackageNameChange, onAppLabelChange)
            IconCard(
                state = state,
                onPickIcon = onPickIcon,
                onClearIcon = onClearIcon,
                onIconSizeChange = onIconSizeChange,
                onFetchCover = onFetchCover,
                onApplyCover = onApplyCover,
            )
            SigningCard(
                state = state,
                onUseCustom = onUseCustomSigning,
                onPickKeystore = onPickKeystore,
                onPasswordChange = onKeystorePasswordChange,
                onAliasChange = onKeystoreAliasChange,
                onVerify = onVerifyKeystore,
            )

            OutlinedTextField(
                value = state.sourceApkPath,
                onValueChange = {},
                readOnly = true,
                label = { Text("源 APK（Tyranor 本体）") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )

            if (state.building) {
                BuildingCard(state.stage)
            }

            Button(
                onClick = onBuild,
                enabled = state.canBuild,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.building) "制作中…" else "生成直装包")
            }

            state.result?.let { ResultCard(it, onInstall, onShare, onExport, state.installCheck) }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GameInfoCard(state: BuildUiState) {
    val game = state.game ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("游戏", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(game.title.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = game.path ?: "路径未知",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (game.launchTarget.isNotBlank()) {
                Text(
                    text = "启动文件：${game.launchTarget}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.gameDirSize > 0) {
                Text(
                    text = "目录大小：${formatSize(state.gameDirSize)}（内嵌后包体接近该值）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.dirCheck?.let { check ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = check.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (check.valid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun BasicInfoCard(
    state: BuildUiState,
    onPackageNameChange: (String) -> Unit,
    onAppLabelChange: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("应用信息", style = MaterialTheme.typography.labelMedium)

            OutlinedTextField(
                value = state.appLabel,
                onValueChange = onAppLabelChange,
                label = { Text("应用名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.packageName,
                onValueChange = onPackageNameChange,
                label = { Text("包名") },
                singleLine = true,
                isError = state.packageNameError != null,
                supportingText = {
                    Text(state.packageNameError ?: "安装后与其他版本共存需不同包名")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IconCard(
    state: BuildUiState,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onIconSizeChange: (IconSize) -> Unit,
    onFetchCover: () -> Unit,
    onApplyCover: (CoverCandidate) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("图标", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bytes = state.iconBytes
                    if (bytes != null) {
                        val bitmap = remember(bytes) {
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "自定义图标",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    } else {
                        Text("默认", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.size(16.dp))
                Column {
                    TextButton(onClick = onPickIcon) { Text("选择图片") }
                    TextButton(onClick = onFetchCover, enabled = !state.fetchingCover) {
                        Text(if (state.fetchingCover) "抓取中…" else "按游戏名抓取")
                    }
                    if (state.iconBytes != null) {
                        TextButton(onClick = onClearIcon) { Text("恢复默认") }
                    }
                }
            }
            state.iconError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.coverError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.coverCandidates.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("抓取结果（点击选用）", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                state.coverCandidates.take(5).forEach { candidate ->
                    OutlinedButton(
                        onClick = { onApplyCover(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                candidate.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            if (candidate.developer.isNotBlank()) {
                                Text(
                                    "${candidate.developer} · ${candidate.released}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("尺寸", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconSize.entries.forEach { size ->
                    val selected = state.iconSize == size
                    if (selected) {
                        Button(onClick = { onIconSizeChange(size) }) {
                            Text("${size.px}px", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        OutlinedButton(onClick = { onIconSizeChange(size) }) {
                            Text("${size.px}px", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "图片会自动居中裁剪为正方形并缩放；尺寸越大越清晰，同时也会增大 APK。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SigningCard(
    state: BuildUiState,
    onUseCustom: (Boolean) -> Unit,
    onPickKeystore: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onAliasChange: (String) -> Unit,
    onVerify: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("签名", style = MaterialTheme.typography.labelMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.useCustomSigning) {
                    Button(onClick = { onUseCustom(false) }) { Text("测试签名") }
                } else {
                    OutlinedButton(onClick = { onUseCustom(false) }) { Text("测试签名") }
                }
                if (state.useCustomSigning) {
                    Button(onClick = { onUseCustom(true) }) { Text("自定义签名") }
                } else {
                    OutlinedButton(onClick = { onUseCustom(true) }) { Text("自定义签名") }
                }
            }

            if (!state.useCustomSigning) {
                Text(
                    "应用内生成的自签证书，保存在应用私有目录。同一设备多次打包签名一致，可正常覆盖安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(onClick = onPickKeystore, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.keystorePath.isBlank()) "选择 keystore 文件（.p12 / .jks）"
                        else state.keystorePath.substringAfterLast('/'),
                    )
                }

                OutlinedTextField(
                    value = state.keystorePassword,
                    onValueChange = onPasswordChange,
                    label = { Text("keystore 密码") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = onVerify,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.keystorePath.isNotBlank() && state.keystorePassword.isNotEmpty(),
                ) {
                    Text(if (state.keystoreVerified) "已验证（点击重新校验）" else "校验 keystore")
                }

                state.keystoreError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (state.keystoreEntries.isNotEmpty()) {
                    Text("签名条目", style = MaterialTheme.typography.labelSmall)
                    state.keystoreEntries.forEach { entry ->
                        val selected = state.keystoreAlias == entry.alias
                        OutlinedButton(
                            onClick = { onAliasChange(entry.alias) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    (if (selected) "✓ " else "") + entry.alias,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "${entry.keyAlgorithm} · ${entry.subject.take(48)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildingCard(stage: BuildStage?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(14.dp))
            Text(stage?.display ?: "准备中", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultCard(
    result: BuildResult,
    onInstall: (File) -> Unit,
    onShare: (File) -> Unit,
    onExport: (File) -> Unit,
    installCheck: com.tyranor.next.packager.apk.InstallCheck?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (result) {
                is BuildResult.Success -> {
                    Text("制作完成", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已内置 ${result.embeddedFiles} 个游戏文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        result.apk.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "大小 ${formatSize(result.apk.length())}" +
                            if (result.signingSchemes.isNotEmpty()) {
                                " · 签名 ${result.signingSchemes.joinToString("/")}"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    installCheck?.let { check ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            check.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (check.canInstall) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onInstall(result.apk) }) { Text("安装") }
                        OutlinedButton(onClick = { onShare(result.apk) }) { Text("分享") }
                        OutlinedButton(onClick = { onExport(result.apk) }) { Text("导出") }
                    }
                }
                is BuildResult.Failure -> {
                    Text("制作失败", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "阶段：${result.stage.display}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
