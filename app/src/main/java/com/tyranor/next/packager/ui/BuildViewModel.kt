package com.tyranor.next.packager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.PackagerApp
import com.tyranor.next.packager.apk.ApkRewriter
import com.tyranor.next.packager.apk.BuildRequest
import com.tyranor.next.packager.apk.BuildResult
import com.tyranor.next.packager.apk.BuildStage
import com.tyranor.next.packager.apk.IconProcessor
import com.tyranor.next.packager.apk.IconSize
import com.tyranor.next.packager.apk.InstallerBuilder
import com.tyranor.next.packager.data.TyranorGame
import com.tyranor.next.packager.sign.TestSigningKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 打包页状态。 */
data class BuildUiState(
    val game: TyranorGame? = null,
    // 包名
    val packageName: String = "",
    val packageNameError: String? = null,
    // 应用名
    val appLabel: String = "",
    // 图标
    val iconUri: Uri? = null,
    val iconBytes: ByteArray? = null,
    val iconError: String? = null,
    // 源 APK
    val sourceApkPath: String = "",
    // 运行态
    val building: Boolean = false,
    val stage: BuildStage? = null,
    val result: BuildResult? = null,
) {
    /** 是否可以开始构建。 */
    val canBuild: Boolean
        get() = !building &&
            game != null &&
            packageNameError == null &&
            packageName.isNotBlank() &&
            sourceApkPath.isNotBlank() &&
            File(sourceApkPath).isFile
}

class BuildViewModel(app: Application) : AndroidViewModel(app) {

    private val packagerApp = app as PackagerApp

    private val _state = MutableStateFlow(BuildUiState())
    val state: StateFlow<BuildUiState> = _state.asStateFlow()

    /** 选中要打包的游戏，自动填入默认包名与应用名。 */
    fun selectGame(game: TyranorGame) {
        val defaultLabel = game.title.ifBlank { "Game" }
        _state.value = _state.value.copy(
            game = game,
            appLabel = defaultLabel,
            packageName = suggestPackageName(defaultLabel),
            packageNameError = null,
            result = null,
        )
        autoDetectSourceApk()
    }

    fun updatePackageName(value: String) {
        val trimmed = value.trim()
        val error = when {
            trimmed.isEmpty() -> "包名不能为空"
            !ApkPackageRules.isValid(trimmed) -> "包名格式非法（至少两段，字母开头）"
            else -> null
        }
        _state.value = _state.value.copy(packageName = trimmed, packageNameError = error)
    }

    fun updateAppLabel(value: String) {
        _state.value = _state.value.copy(appLabel = value)
    }

    fun updateSourceApk(path: String) {
        _state.value = _state.value.copy(sourceApkPath = path.trim())
    }

    /** 选择自定义封面。 */
    fun selectIcon(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                IconProcessor.loadAndResize(getApplication(), uri, IconSize.DEFAULT)
            }
            _state.value = if (bytes == null) {
                _state.value.copy(iconError = "无法读取该图片")
            } else {
                _state.value.copy(iconUri = uri, iconBytes = bytes, iconError = null)
            }
        }
    }

    fun clearIcon() {
        _state.value = _state.value.copy(iconUri = null, iconBytes = null, iconError = null)
    }

    /** 开始构建。 */
    fun build() {
        val s = _state.value
        val game = s.game ?: return
        if (s.building) return

        val gameDir = game.path?.let { File(it) }
        if (gameDir == null || !gameDir.isDirectory) {
            _state.value = s.copy(
                result = BuildResult.Failure(BuildStage.PREPARING, "游戏目录不可访问：${game.path ?: game.uri}"),
            )
            return
        }

        _state.value = s.copy(building = true, stage = BuildStage.PREPARING, result = null)

        viewModelScope.launch {
            val output = withContext(Dispatchers.IO) {
                val safeName = s.packageName.replace('.', '_')
                File(packagerApp.outputDir, "$safeName-${System.currentTimeMillis()}.apk")
            }

            val result = withContext(Dispatchers.IO) {
                val key = TestSigningKeyProvider.get(getApplication())
                InstallerBuilder { stage ->
                    _state.value = _state.value.copy(stage = stage)
                }.build(
                    BuildRequest(
                        sourceApk = File(s.sourceApkPath),
                        gameDir = gameDir,
                        outputApk = output,
                        packageName = s.packageName,
                        appLabel = s.appLabel,
                        iconPng = s.iconBytes,
                        signingKey = key,
                    ),
                )
            }

            _state.value = _state.value.copy(building = false, result = result)
        }
    }

    /**
     * 自动定位 Tyranor 的 APK 路径。
     *
     * 通过 PackageManager 取已安装 Tyranor 的 sourceDir，
     * 无需用户手动选择。
     */
    private fun autoDetectSourceApk() {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>()
                        .packageManager
                        .getApplicationInfo(
                            com.tyranor.next.packager.BuildConfig.TYRANOR_PACKAGE,
                            0,
                        )
                        .sourceDir
                }.getOrNull()
            }
            if (path != null) {
                _state.value = _state.value.copy(sourceApkPath = path)
            }
        }
    }

    /** 根据游戏名生成一个合法的默认包名。 */
    private fun suggestPackageName(label: String): String {
        val slug = label
            .lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .ifBlank { "game" }
        val safe = if (slug.first().isLetter()) slug else "g_$slug"
        return "com.tyn.games.$safe"
    }
}
