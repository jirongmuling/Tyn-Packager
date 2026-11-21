package com.tyranor.next.packager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.apk.ApkPackageRules
import com.tyranor.next.packager.apk.BuildRequest
import com.tyranor.next.packager.apk.BuildResult
import com.tyranor.next.packager.apk.BuildStage
import com.tyranor.next.packager.apk.InstallerBuilder
import com.tyranor.next.packager.data.BuildHistoryEntry
import com.tyranor.next.packager.data.BuildHistoryStore
import com.tyranor.next.packager.data.TyranorGame
import com.tyranor.next.packager.sign.SigningKey
import com.tyranor.next.packager.sign.TestSigningKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 批量打包中的单项状态。 */
data class BatchItem(
    val game: TyranorGame,
    val packageName: String,
    val status: BatchStatus = BatchStatus.Pending,
    val message: String = "",
)

enum class BatchStatus { Pending, Running, Success, Failed, Skipped }

data class BatchUiState(
    val items: List<BatchItem> = emptyList(),
    val running: Boolean = false,
    val currentIndex: Int = -1,
    val sourceApkPath: String = "",
) {
    val doneCount: Int get() = items.count { it.status != BatchStatus.Pending && it.status != BatchStatus.Running }
    val successCount: Int get() = items.count { it.status == BatchStatus.Success }
    val failedCount: Int get() = items.count { it.status == BatchStatus.Failed }
}

/**
 * 批量打包：为选中的多个游戏依次生成直装包。
 *
 * 包名按游戏标题自动生成，冲突时追加序号。
 * 使用与单项打包相同的签名方式（此处统一用测试签名，避免批量场景反复输入密码）。
 */
class BatchViewModel(app: Application) : AndroidViewModel(app) {

    private val packagerApp = app as com.tyranor.next.packager.PackagerApp

    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    /** 准备批量任务。 */
    fun prepare(games: List<TyranorGame>, sourceApk: String) {
        val used = HashSet<String>()
        val items = games.map { game ->
            BatchItem(
                game = game,
                packageName = uniquePackageName(game.title, used),
            )
        }
        _state.value = BatchUiState(items = items, sourceApkPath = sourceApk)
    }

    /** 更新某一项的包名。 */
    fun updatePackageName(index: Int, value: String) {
        val items = _state.value.items.toMutableList()
        val item = items.getOrNull(index) ?: return
        items[index] = item.copy(packageName = value.trim())
        _state.value = _state.value.copy(items = items)
    }

    /** 开始依次打包。 */
    fun start() {
        val s = _state.value
        if (s.running || s.items.isEmpty()) return

        _state.value = s.copy(running = true)

        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) {
                TestSigningKeyProvider.get(getApplication<Application>())
            }
            val history = BuildHistoryStore(getApplication<Application>())

            for ((index, item) in s.items.withIndex()) {
                updateItem(index) { it.copy(status = BatchStatus.Running, message = "") }
                _state.value = _state.value.copy(currentIndex = index)

                val result = withContext(Dispatchers.IO) {
                    buildOne(item, key)
                }

                when (result) {
                    is BuildResult.Success -> {
                        withContext(Dispatchers.IO) {
                            history.append(
                                BuildHistoryEntry(
                                    fileName = result.apk.name,
                                    packageName = item.packageName,
                                    appLabel = item.game.title,
                                    gameTitle = item.game.title,
                                    createdAt = System.currentTimeMillis(),
                                    sizeBytes = result.apk.length(),
                                    customSigned = false,
                                ),
                            )
                        }
                        updateItem(index) {
                            it.copy(status = BatchStatus.Success, message = "完成")
                        }
                    }
                    is BuildResult.Failure -> updateItem(index) {
                        it.copy(status = BatchStatus.Failed, message = result.message)
                    }
                }
            }

            _state.value = _state.value.copy(running = false, currentIndex = -1)
        }
    }

    private fun buildOne(item: BatchItem, key: SigningKey): BuildResult {
        val gameDir = item.game.path?.let { File(it) }
            ?: return BuildResult.Failure(BuildStage.PREPARING, "游戏目录未知")
        if (!gameDir.isDirectory) {
            return BuildResult.Failure(BuildStage.PREPARING, "游戏目录不可访问")
        }
        if (!ApkPackageRules.isValid(item.packageName)) {
            return BuildResult.Failure(BuildStage.PREPARING, "包名非法：${item.packageName}")
        }

        val safeName = item.packageName.replace('.', '_')
        val output = File(packagerApp.outputDir, "$safeName-${System.currentTimeMillis()}.apk")

        return runCatching {
            InstallerBuilder().build(
                BuildRequest(
                    sourceApk = File(_state.value.sourceApkPath),
                    gameDir = gameDir,
                    outputApk = output,
                    packageName = item.packageName,
                    appLabel = item.game.title.ifBlank { "Game" },
                    iconPng = null,
                    signingKey = key,
                ),
            )
        }.getOrElse { e ->
            BuildResult.Failure(BuildStage.DONE, e.message ?: "未知错误", e)
        }
    }

    private fun updateItem(index: Int, block: (BatchItem) -> BatchItem) {
        val items = _state.value.items.toMutableList()
        items.getOrNull(index)?.let { items[index] = block(it) }
        _state.value = _state.value.copy(items = items)
    }

    /** 由标题生成不重复的包名。 */
    private fun uniquePackageName(title: String, used: MutableSet<String>): String {
        val base = "com.tyn.games." + ApkPackageRules.toSegment(title)
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base$suffix"
            suffix++
        }
        return candidate
    }
}
