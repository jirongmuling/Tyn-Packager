package com.tyranor.next.packager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.PackagerApp
import com.tyranor.next.packager.data.BuildHistoryEntry
import com.tyranor.next.packager.data.BuildHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val loading: Boolean = true,
    val entries: List<BuildHistoryEntry> = emptyList(),
) {
    val totalSize: Long get() = entries.sumOf { it.sizeBytes }
    val isEmpty: Boolean get() = !loading && entries.isEmpty()
}

/** 打包历史。 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = BuildHistoryStore(app)

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新载入，并清理文件已丢失的记录。 */
    fun refresh() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { store.pruneMissing() }
            _state.value = HistoryUiState(loading = false, entries = entries)
        }
    }

    /** 删除单条记录及其 APK。 */
    fun delete(fileName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.removeWithFile(fileName) }
            refresh()
        }
    }

    /** 清空全部记录与产物。 */
    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.load().forEach { store.removeWithFile(it.fileName) }
            }
            refresh()
        }
    }
}
