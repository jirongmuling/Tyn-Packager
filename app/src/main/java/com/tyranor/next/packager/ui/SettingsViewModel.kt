package com.tyranor.next.packager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.data.BuildHistoryStore
import com.tyranor.next.packager.data.OnboardingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SettingsUiState(
    val loading: Boolean = true,
    /** 输出目录占用（字节）。 */
    val outputBytes: Long = 0,
    /** 输出 APK 数量。 */
    val outputCount: Int = 0,
    /** 缓存占用（字节）。 */
    val cacheBytes: Long = 0,
    /** 测试签名是否已存在。 */
    val testKeyExists: Boolean = false,
    /** Tyranor 包名。 */
    val tyranorPackage: String = "",
    /** 最近一次操作结果。 */
    val message: String? = null,
    /** 日志内容（未展开时不读）。 */
    val logContent: String? = null,
)

/** 设置页。 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { computeStats() }
            _state.value = s
        }
    }

    private fun computeStats(): SettingsUiState {
        val outDir = File(File(getApplication<Application>().filesDir, "packager"), "output")
        val files = outDir.listFiles()?.filter { it.isFile }.orEmpty()

        val cacheDir = getApplication<Application>().cacheDir
        val cacheBytes = cacheDir.walkTopDown()
            .filter { it.isFile }
            .take(5000)
            .sumOf { it.length() }

        val keyFile = File(File(getApplication<Application>().filesDir, "signing"), "test-signing.p12")

        return SettingsUiState(
            loading = false,
            outputBytes = files.sumOf { it.length() },
            outputCount = files.size,
            cacheBytes = cacheBytes,
            testKeyExists = keyFile.isFile,
            tyranorPackage = com.tyranor.next.packager.BuildConfig.TYRANOR_PACKAGE,
        )
    }

    /** 清理缓存（不动输出与签名）。 */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            refresh()
            _state.value = _state.value.copy(message = "缓存已清理")
        }
    }

    /** 删除全部输出 APK 与历史记录。 */
    fun clearOutputs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val store = BuildHistoryStore(app)
                store.load().forEach { store.removeWithFile(it.fileName) }
                File(File(app.filesDir, "packager"), "output").listFiles()
                    ?.forEach { it.deleteRecursively() }
            }
            refresh()
            _state.value = _state.value.copy(message = "输出已清空")
        }
    }

    /** 重置首次引导（下次启动重新显示）。 */
    fun resetOnboarding() {
        OnboardingStore(getApplication()).reset()
        _state.value = _state.value.copy(message = "引导已重置，下次启动生效")
    }

    /** 读取日志内容。 */
    fun loadLog() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                com.tyranor.next.packager.data.BuildLogger(getApplication<Application>()).read()
            }
            _state.value = _state.value.copy(logContent = text.ifBlank { "（暂无日志）" })
        }
    }

    /** 清空日志。 */
    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.tyranor.next.packager.data.BuildLogger(getApplication<Application>()).clear()
            }
            _state.value = _state.value.copy(logContent = null, message = "日志已清空")
        }
    }

    /** 日志文件路径，供分享使用。 */
    fun logFile(): java.io.File =
        com.tyranor.next.packager.data.BuildLogger(getApplication()).logFile()

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
