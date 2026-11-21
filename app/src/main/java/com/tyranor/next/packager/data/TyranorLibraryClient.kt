package com.tyranor.next.packager.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tyranor 游戏库只读客户端。
 *
 * 通过 Tyranor 导出的 [ContentProvider] 读取已扫描识别的游戏，
 * **不修改任何数据**，也不触发 Tyranor 重新扫描。
 *
 * 接口契约见 Tyranor 仓库 `docs/GAME_LIBRARY_PROVIDER.md`。
 */
class TyranorLibraryClient(private val context: Context) {

    /** Tyranor 是否已安装。 */
    fun isTyranorInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(tyranorPackage, 0)
        true
    }.getOrDefault(false)

    /**
     * 读取可制作直装版的游戏（Kirikiri）。
     *
     * 走 `games/krkr` 预过滤视图，Tyranor 侧已按引擎筛过。
     */
    suspend fun loadKirikiriGames(): List<TyranorGame> = withContext(Dispatchers.IO) {
        query(Uri.parse("content://$authority/games/krkr"))
    }

    /** 读取全部游戏。 */
    suspend fun loadAllGames(): List<TyranorGame> = withContext(Dispatchers.IO) {
        query(Uri.parse("content://$authority/games"))
    }

    private fun query(uri: Uri): List<TyranorGame> {
        val resolver = context.contentResolver
        val cursor = runCatching { resolver.query(uri, PROJECTION, null, null, null) }
            .getOrElse { return emptyList() }
            ?: return emptyList()

        return cursor.use {
            val idx = PROJECTION.associateWith { name -> cursor.getColumnIndex(name) }
            fun s(name: String): String? = idx[name]?.takeIf { it >= 0 }?.let {
                if (cursor.isNull(it)) null else cursor.getString(it)
            }
            fun l(name: String): Long = idx[name]?.takeIf { it >= 0 }?.let {
                if (cursor.isNull(it)) 0L else cursor.getLong(it)
            } ?: 0L

            buildList {
                while (cursor.moveToNext()) {
                    val gameUri = s(COL_URI) ?: continue
                    add(
                        TyranorGame(
                            uri = gameUri,
                            path = s(COL_PATH),
                            rootUri = s(COL_ROOT_URI),
                            rootPath = s(COL_ROOT_PATH),
                            title = s(COL_TITLE).orEmpty(),
                            engine = s(COL_ENGINE).orEmpty(),
                            engineDisplay = s(COL_ENGINE_DISPLAY).orEmpty(),
                            launchTarget = s(COL_LAUNCH_TARGET).orEmpty(),
                            launchFile = s(COL_LAUNCH_FILE),
                            coverUri = s(COL_COVER_URI),
                            coverSource = s(COL_COVER_SOURCE),
                            metadataTitle = s(COL_METADATA_TITLE),
                            discoveredAt = l(COL_DISCOVERED_AT),
                            updatedAt = l(COL_UPDATED_AT),
                            lastOpenedAt = l(COL_LAST_OPENED_AT),
                            isManual = l(COL_IS_MANUAL) == 1L,
                        ),
                    )
                }
            }
        }
    }

    /** 诊断信息，用于设置页显示“接口是否可用”。 */
    fun diagnose(): LibraryDiagnosis {
        val installed = isTyranorInstalled()
        if (!installed) return LibraryDiagnosis(false, false, "未安装 Tyranor")
        val providerAvailable = runCatching {
            context.packageManager.resolveContentProvider(authority, 0) != null
        }.getOrDefault(false)
        if (!providerAvailable) {
            return LibraryDiagnosis(true, false, "Tyranor 版本过旧，缺少游戏库共享接口")
        }
        val granted = context.checkSelfPermission(PERMISSION_READ) == PackageManager.PERMISSION_GRANTED
        return LibraryDiagnosis(
            tyranorInstalled = true,
            providerAvailable = true,
            message = if (granted) "接口可用" else "缺少读取权限",
        )
    }

    data class LibraryDiagnosis(
        val tyranorInstalled: Boolean,
        val providerAvailable: Boolean,
        val message: String,
    )

    companion object {
        /** Tyranor 包名；可用 Gradle 属性覆盖（-PTYRANOR_PACKAGE=...）。 */
        val tyranorPackage: String = com.tyranor.next.packager.BuildConfig.TYRANOR_PACKAGE

        val authority: String get() = "$tyranorPackage.gamelibrary"
        val PERMISSION_READ: String get() = "$tyranorPackage.permission.READ_GAME_LIBRARY"

        // 列名，与 Tyranor 侧契约保持一致
        private const val COL_URI = "uri"
        private const val COL_PATH = "path"
        private const val COL_ROOT_URI = "root_uri"
        private const val COL_ROOT_PATH = "root_path"
        private const val COL_TITLE = "title"
        private const val COL_ENGINE = "engine"
        private const val COL_ENGINE_DISPLAY = "engine_display"
        private const val COL_LAUNCH_TARGET = "launch_target"
        private const val COL_LAUNCH_FILE = "launch_file"
        private const val COL_COVER_URI = "cover_uri"
        private const val COL_COVER_SOURCE = "cover_source"
        private const val COL_METADATA_TITLE = "metadata_title"
        private const val COL_DISCOVERED_AT = "discovered_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_LAST_OPENED_AT = "last_opened_at"
        private const val COL_IS_MANUAL = "is_manual"

        private val PROJECTION = arrayOf(
            COL_URI, COL_PATH, COL_ROOT_URI, COL_ROOT_PATH, COL_TITLE,
            COL_ENGINE, COL_ENGINE_DISPLAY, COL_LAUNCH_TARGET, COL_LAUNCH_FILE,
            COL_COVER_URI, COL_COVER_SOURCE, COL_METADATA_TITLE,
            COL_DISCOVERED_AT, COL_UPDATED_AT, COL_LAST_OPENED_AT, COL_IS_MANUAL,
        )
    }
}
