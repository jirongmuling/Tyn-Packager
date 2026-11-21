package com.tyranor.next.packager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.PackagerApp
import com.tyranor.next.packager.apk.ApkPackageRules
import com.tyranor.next.packager.apk.BuildRequest
import com.tyranor.next.packager.apk.BuildResult
import com.tyranor.next.packager.apk.BuildStage
import com.tyranor.next.packager.apk.IconProcessor
import com.tyranor.next.packager.apk.IconSize
import com.tyranor.next.packager.apk.InstallerBuilder
import com.tyranor.next.packager.cover.CoverCandidate
import com.tyranor.next.packager.cover.CoverFetcher
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
    val iconSize: IconSize = IconSize.DEFAULT,
    // 自动抓取
    val fetchingCover: Boolean = false,
    val coverCandidates: List<CoverCandidate> = emptyList(),
    val coverError: String? = null,
    // 源 APK
    val sourceApkPath: String = "",
    // 游戏目录体积（字节）；-1 表示尚未计算
    val gameDirSize: Long = -1L,
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
        computeGameDirSize(game)
    }

    /** 统计游戏目录体积，用于提示最终包大小。 */
    private fun computeGameDirSize(game: TyranorGame) {
        val dir = game.path?.let { java.io.File(it) } ?: return
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching {
                    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(-1L)
            }
            _state.value = _state.value.copy(gameDirSize = size)
        }
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
            val size = _state.value.iconSize
            val bytes = withContext(Dispatchers.IO) {
                IconProcessor.loadAndResize(getApplication<Application>(), uri, size)
            }
            _state.value = if (bytes == null) {
                _state.value.copy(iconError = "无法读取该图片")
            } else {
                _state.value.copy(iconUri = uri, iconBytes = bytes, iconError = null)
            }
        }
    }

    /** 切换图标尺寸；已有图片时按新尺寸重新处理。 */
    fun setIconSize(size: IconSize) {
        val uri = _state.value.iconUri
        _state.value = _state.value.copy(iconSize = size)
        if (uri != null) selectIcon(uri)
    }

    fun clearIcon() {
        _state.value = _state.value.copy(iconUri = null, iconBytes = null, iconError = null)
    }

    /**
     * 按游戏名自动抓取封面候选。
     *
     * 优先用游戏标题，其次用目录名；VNDB 未命中时给出明确提示，
     * 由用户回退到手动选图。
     */
    fun fetchCovers() {
        val game = _state.value.game ?: return
        if (_state.value.fetchingCover) return

        val queries = buildList {
            game.title.takeIf { it.isNotBlank() }?.let(::add)
            game.path?.let { java.io.File(it).name }?.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        if (queries.isEmpty()) {
            _state.value = _state.value.copy(coverError = "缺少可用于搜索的游戏名")
            return
        }

        _state.value = _state.value.copy(fetchingCover = true, coverError = null, coverCandidates = emptyList())

        viewModelScope.launch {
            var found: List<CoverCandidate> = emptyList()
            for (q in queries) {
                found = CoverFetcher.search(q)
                if (found.isNotEmpty()) break
            }
            _state.value = _state.value.copy(
                fetchingCover = false,
                coverCandidates = found,
                coverError = if (found.isEmpty()) "未找到匹配的封面，可改用本地图片" else null,
            )
        }
    }

    /** 选中一张抓取到的封面并转为图标字节。 */
    fun applyCoverCandidate(candidate: CoverCandidate) {
        viewModelScope.launch {
            val size = _state.value.iconSize
            val bytes = withContext(Dispatchers.IO) {
                val tmp = File(getApplication<Application>().cacheDir, "cover_dl.png")
                val file = CoverFetcher.download(candidate.coverUrl, tmp) ?: return@withContext null
                IconProcessor.loadAndResize(getApplication<Application>(), android.net.Uri.fromFile(file), size)
            }
            _state.value = if (bytes == null) {
                _state.value.copy(iconError = "封面下载失败")
            } else {
                _state.value.copy(iconBytes = bytes, iconUri = null, iconError = null, coverCandidates = emptyList())
            }
        }
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
                val key = TestSigningKeyProvider.get(getApplication<Application>())
                InstallerBuilder(
                    onStage = { stage: BuildStage ->
                        _state.value = _state.value.copy(stage = stage)
                    },
                    onEmbedProgress = { count: Int, _: String ->
                        if (count % 25 == 0) {
                            _state.value = _state.value.copy(stage = BuildStage.EMBEDDING)
                        }
                    },
                ).build(
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
