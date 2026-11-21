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
import com.tyranor.next.packager.ui.screen.BatchScreen
import com.tyranor.next.packager.ui.screen.BuildScreen
import com.tyranor.next.packager.ui.screen.HistoryScreen
import com.tyranor.next.packager.ui.screen.HomeScreen
import com.tyranor.next.packager.ui.theme.TynPackagerTheme

/** 简易页面栈：首页 → 打包页。 */
private sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data object History : Screen
    data class Batch(val gameUris: List<String>) : Screen
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val onboarding = remember { com.tyranor.next.packager.data.OnboardingStore(context) }
    var screen by remember {
        mutableStateOf<Screen>(if (onboarding.isDone()) Screen.Home else Screen.Onboarding)
    }

    when (val current = screen) {
        is Screen.Onboarding -> com.tyranor.next.packager.ui.screen.OnboardingScreen(
            onDone = {
                onboarding.markDone()
                screen = Screen.Home
            },
            onRequestAllFilesAccess = onRequestAllFilesAccess,
        )

        is Screen.Home -> HomeScreen(
            state = homeState,
            onRefresh = homeVm::refresh,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onSelectGame = { game -> screen = Screen.Build(game.uri) },
            onOpenHistory = { screen = Screen.History },
            onBatchBuild = { uris -> screen = Screen.Batch(uris) },
        )

        is Screen.History -> HistoryRoute(onBack = { screen = Screen.Home })

        is Screen.Batch -> BatchRoute(
            gameUris = current.gameUris,
            homeState = homeState,
            onBack = { screen = Screen.Home },
        )

        is Screen.Build -> BuildRoute(
            gameUri = current.gameUri,
            homeState = homeState,
            onBack = { screen = Screen.Home },
        )
    }
}

@Composable
private fun BatchRoute(
    gameUris: List<String>,
    homeState: HomeUiState,
    onBack: () -> Unit,
) {
    val batchVm: BatchViewModel = viewModel()
    val state by batchVm.state.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(gameUris) {
        if (state.items.isEmpty()) {
            val games = homeState.games.filter { it.uri in gameUris }
            if (games.isNotEmpty()) {
                batchVm.prepare(games, tyranorSourceDir(context))
            }
        }
    }

    BatchScreen(
        state = state,
        onBack = onBack,
        onStart = batchVm::start,
    )
}

@Composable
private fun HistoryRoute(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val historyVm: HistoryViewModel = viewModel()
    val state by historyVm.state.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) { historyVm.refresh() }

    HistoryScreen(
        state = state,
        onBack = onBack,
        onRefresh = historyVm::refresh,
        onDelete = historyVm::delete,
        onClearAll = historyVm::clearAll,
        onInstall = { entry ->
            installWithCheck(context, entry.apkFile(context), entry.packageName)
        },
        onShare = { entry ->
            ApkOutput.share(context, entry.apkFile(context)).onFailure {
                android.widget.Toast
                    .makeText(context, "无法分享：${it.message}", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        },
        onExport = { entry -> exportApk(context, entry.apkFile(context)) },
    )
}

/** 安装前检查并提示，然后调起安装器。 */
private fun installWithCheck(
    context: android.content.Context,
    apk: java.io.File,
    packageName: String,
) {
    val check = com.tyranor.next.packager.apk.InstallChecker.check(context, apk, packageName)
    if (!check.canInstall) {
        android.app.AlertDialog.Builder(context)
            .setTitle("无法直接安装")
            .setMessage(check.message)
            .setPositiveButton("知道了", null)
            .show()
        return
    }
    com.tyranor.next.packager.apk.ApkOutput.install(context, apk).onFailure {
        android.widget.Toast
            .makeText(context, "无法安装：${it.message}", android.widget.Toast.LENGTH_LONG)
            .show()
    }
}

/** 取已安装 Tyranor 的 APK 路径。 */
private fun tyranorSourceDir(context: android.content.Context): String = runCatching {
    context.packageManager
        .getApplicationInfo(com.tyranor.next.packager.BuildConfig.TYRANOR_PACKAGE, 0)
        .sourceDir
}.getOrDefault("")

/** 导出到下载目录并提示结果。 */
private fun exportApk(context: android.content.Context, apk: java.io.File) {
    val ok = ApkOutput.exportToDownloads(context, apk) != null
    android.widget.Toast.makeText(
        context,
        if (ok) "已导出到下载目录" else "导出失败",
        android.widget.Toast.LENGTH_LONG,
    ).show()
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

    // keystore 选择：任意文件
    var pendingKeystore by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    val keystorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) pendingKeystore?.invoke(uri)
        pendingKeystore = null
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
        onFetchCover = buildVm::fetchCovers,
        onApplyCover = buildVm::applyCoverCandidate,
        onUseCustomSigning = buildVm::setUseCustomSigning,
        onPickKeystore = {
            pendingKeystore = { uri ->
                // 复制到应用私有目录，避免 SAF 授权失效
                val dst = java.io.File(
                    context.filesDir,
                    "keystore/" + (uri.lastPathSegment?.substringAfterLast('/') ?: "imported.p12"),
                )
                runCatching {
                    dst.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onSuccess { buildVm.setKeystoreFile(dst.absolutePath) }
                    .onFailure {
                        android.widget.Toast.makeText(
                            context, "无法读取 keystore", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
            }
            keystorePicker.launch("*/*")
        },
        onKeystorePasswordChange = buildVm::setKeystorePassword,
        onKeystoreAliasChange = buildVm::setKeystoreAlias,
        onVerifyKeystore = buildVm::verifyKeystore,
        onBuild = buildVm::build,
        onInstall = { apk -> installWithCheck(context, apk, state.packageName) },
        onShare = { apk ->
            ApkOutput.share(context, apk).onFailure {
                android.widget.Toast
                    .makeText(context, "无法分享：${it.message}", android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        },
        onExport = { apk -> exportApk(context, apk) },
    )
}
