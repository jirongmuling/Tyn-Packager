package com.tyranor.next.packager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyranor.next.packager.PackagerApp
import com.tyranor.next.packager.data.TyranorGame
import com.tyranor.next.packager.data.TyranorLibraryClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页状态。 */
data class HomeUiState(
    val loading: Boolean = true,
    val diagnosis: TyranorLibraryClient.LibraryDiagnosis? = null,
    val games: List<TyranorGame> = emptyList(),
    val error: String? = null,
    /** 搜索关键词。 */
    val query: String = "",
    /** 只看最近玩过的。 */
    val onlyRecent: Boolean = false,
) {
    /** 可制作直装包的游戏（已应用搜索与筛选）。 */
    val buildableGames: List<TyranorGame>
        get() {
            var list = games.filter { it.canBuildInstaller }
            if (query.isNotBlank()) {
                list = list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        (it.metadataTitle?.contains(query, ignoreCase = true) == true)
                }
            }
            if (onlyRecent) {
                list = list.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }
            }
            return list
        }

    /** 总可打包数量（不受搜索影响）。 */
    val totalBuildable: Int get() = games.count { it.canBuildInstaller }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val library = (app as PackagerApp).libraryClient

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 更新搜索关键词。 */
    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    /** 切换“只看最近玩过”。 */
    fun toggleOnlyRecent() {
        _state.value = _state.value.copy(onlyRecent = !_state.value.onlyRecent)
    }

    /** 重新读取 Tyranor 游戏库。 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val diagnosis = library.diagnose()
            if (!diagnosis.providerAvailable) {
                _state.value = HomeUiState(
                    loading = false,
                    diagnosis = diagnosis,
                    error = diagnosis.message,
                )
                return@launch
            }
            runCatching { library.loadKirikiriGames() }
                .onSuccess { games ->
                    _state.value = _state.value.copy(
                        loading = false,
                        diagnosis = diagnosis,
                        games = games,
                        error = null,
                    )
                }
                .onFailure { e ->
                    _state.value = HomeUiState(
                        loading = false,
                        diagnosis = diagnosis,
                        error = e.message ?: "读取游戏库失败",
                    )
                }
        }
    }
}
