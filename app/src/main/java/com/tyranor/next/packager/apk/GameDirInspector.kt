package com.tyranor.next.packager.apk

import java.io.File

/** 游戏目录预检结果。 */
data class GameDirCheck(
    val valid: Boolean,
    val message: String,
    /** 找到的 xp3 文件数。 */
    val xp3Count: Int = 0,
    /** 目录总大小（字节）。 */
    val totalBytes: Long = 0,
    /** 是否找到启动入口（startup.tjs / config.tjs）。 */
    val hasStartupScript: Boolean = false,
)

/**
 * 游戏目录预检。
 *
 * Kirikiri 游戏的特征（与 Tyranor 的识别规则一致）：
 * 目录下存在 `.xp3` 归档，或 `startup.tjs`、`config.tjs` 脚本。
 *
 * 打包前检查可以避免「读到一半失败」——尤其是大目录。
 */
object GameDirInspector {

    /** 统计时最多遍历的条目数，防止超大目录卡死。 */
    private const val MAX_ENTRIES = 200_000

    fun inspect(dir: File): GameDirCheck {
        if (!dir.exists()) {
            return GameDirCheck(false, "游戏目录不存在：${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            return GameDirCheck(false, "路径不是目录：${dir.absolutePath}")
        }
        if (!dir.canRead()) {
            return GameDirCheck(false, "无权限读取该目录，请检查「所有文件访问权限」")
        }

        var xp3Count = 0
        var totalBytes = 0L
        var hasStartup = false
        var hasConfig = false
        var count = 0

        dir.walkTopDown()
            .onEnter { true }
            .forEach { file ->
                if (count++ > MAX_ENTRIES) return@forEach
                if (file.isFile) {
                    totalBytes += file.length()
                    val name = file.name.lowercase()
                    when {
                        name.endsWith(".xp3") -> xp3Count++
                        name == "startup.tjs" -> hasStartup = true
                        name == "config.tjs" -> hasConfig = true
                    }
                }
            }

        val hasScript = hasStartup || hasConfig

        return when {
            xp3Count > 0 -> GameDirCheck(
                valid = true,
                message = "检测到 $xp3Count 个 xp3 归档",
                xp3Count = xp3Count,
                totalBytes = totalBytes,
                hasStartupScript = hasScript,
            )
            hasScript -> GameDirCheck(
                valid = true,
                message = "检测到 Kirikiri 脚本（未打包为 xp3）",
                xp3Count = 0,
                totalBytes = totalBytes,
                hasStartupScript = true,
            )
            totalBytes == 0L -> GameDirCheck(false, "目录为空")
            else -> GameDirCheck(
                valid = false,
                message = "未检测到 Kirikiri 游戏文件（.xp3 / startup.tjs / config.tjs）",
                totalBytes = totalBytes,
            )
        }
    }
}
