package com.tyranor.next.packager.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tyranor.next.packager.apk.ApkOutput
import com.tyranor.next.packager.ui.screen.BuildScreen
import com.tyranor.next.packager.ui.screen.HomeScreen
import com.tyranor.next.packager.ui.theme.TynPackagerTheme

/** 简易页面栈：首页 → 打包页。 */
private sealed interface Screen {
    data object Home : Screen
    data class Build(val gameUri: String) : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TynPackagerTheme {
                AppRoot(onRequestAllFilesAccess = ::requestAllFilesAccess)
            }
        }
    }

    /** 引导用户授予“所有文件访问权限”。 */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (Environment.isExternalStorageManager()) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
private fun AppRoot(onRequestAllFilesAccess: () -> Unit) {
    val homeVm: HomeViewModel = viewModel()
    val homeState by homeVm.state.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            state = homeState,
            onRefresh = homeVm::refresh,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onSelectGame = { game -> screen = Screen.Build(game.uri) },
        )

        is Screen.Build -> BuildRoute(
            gameUri = current.gameUri,
            homeState = homeState,
            onBack = { screen = Screen.Home },
        )
    }
}

@Composable
private fun BuildRoute(
    gameUri: String,
    homeState: HomeUiState,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val buildVm: BuildViewModel = viewModel(key = "build-$gameUri")
    val state by buildVm.state.collectAsStateWithLifecycle()

    // 首次进入时选中对应游戏
    LaunchedEffect(gameUri) {
        if (state.game == null) {
            homeState.games.firstOrNull { it.uri == gameUri }?.let(buildVm::selectGame)
        }
    }

    // 相册选图
    var pendingIcon by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingIcon?.invoke(uri)
        pendingIcon = null
    }

    BuildScreen(
        state = state,
        onBack = onBack,
        onPackageNameChange = buildVm::updatePackageName,
        onAppLabelChange = buildVm::updateAppLabel,
        onPickIcon = {
            pendingIcon = buildVm::selectIcon
            picker.launch("image/*")
        },
        onClearIcon = buildVm::clearIcon,
        onIconSizeChange = buildVm::setIconSize,
        onBuild = buildVm::build,
        onInstall = { apk ->
            ApkOutput.install(context, apk).onFailure {
                android.widget.Toast
                    .makeText(context, "无法安装：${it.message}", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        },
        onShare = { apk ->
            ApkOutput.share(context, apk).onFailure {
                android.widget.Toast
                    .makeText(context, "无法分享：${it.message}", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        },
    )
}
