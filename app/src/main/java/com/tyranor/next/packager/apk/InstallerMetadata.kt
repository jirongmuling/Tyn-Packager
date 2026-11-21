package com.tyranor.next.packager.apk

import org.json.JSONObject

/**
 * 直装包内置元数据。
 *
 * 打包时写入 APK 的 `assets/tyn_packager/game.json`，
 * 供直装包运行时读取游戏信息（无需依赖 Tyranor 数据库）。
 *
 * 字段与 Tyranor 的 `ScanGame` 对齐，便于直接构造启动参数。
 */
data class InstallerMetadata(
    val title: String,
    val engine: String,
    val launchTarget: String,
    val packageName: String,
    /** 资源在 APK 内的前缀，如 `assets/game`。 */
    val assetPrefix: String,
    /** 构建时间戳。 */
    val builtAt: Long,
    /** 游戏目录原始路径，仅供参考与排错。 */
    val originalPath: String? = null,
    /** 内置文件数量。 */
    val embeddedFiles: Int = 0,
) {

    /** 序列化为 JSON 字节。 */
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("title", title)
        put("engine", engine)
        put("launchTarget", launchTarget)
        put("packageName", packageName)
        put("assetPrefix", assetPrefix)
        put("builtAt", builtAt)
        put("embeddedFiles", embeddedFiles)
        originalPath?.let { put("originalPath", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** 元数据格式版本；不兼容变更时递增。 */
        const val SCHEMA_VERSION = 1

        /** APK 内的元数据路径。 */
        const val ASSET_PATH = "assets/tyn_packager/game.json"

        /** 从 JSON 解析。 */
        fun fromJson(json: String): InstallerMetadata? = runCatching {
            val o = JSONObject(json)
            InstallerMetadata(
                title = o.optString("title"),
                engine = o.optString("engine", "KIRIKIRI"),
                launchTarget = o.optString("launchTarget"),
                packageName = o.optString("packageName"),
                assetPrefix = o.optString("assetPrefix", "assets/game"),
                builtAt = o.optLong("builtAt"),
                originalPath = o.optString("originalPath").ifBlank { null },
                embeddedFiles = o.optInt("embeddedFiles"),
            )
        }.getOrNull()
    }
}
