package com.tyranor.next.packager.apk

import java.io.File

/**
 * 打包资源过滤规则。
 *
 * 依据实际游戏目录结构制定：Tyranor 游戏目录里常混有**与运行无关**的内容，
 * 例如翻译补丁备份（`Galtransl-*`）、`.bak` 备份、日志等。
 * 这些一并打进直装包会让 APK 体积白白膨胀。
 *
 * 原则：**宁可少排除，不可误删**——只排除明确不影响运行的类别。
 */
object AssetFilter {

    /** 明确不参与运行的目录名前缀/包含关系。 */
    private val EXCLUDED_DIR_PATTERNS = listOf(
        "galtransl",          // 机翻补丁及其备份
        "backup",             // 各种备份目录
        "备份",
        ".git",
        ".trash",
    )

    /** 明确不参与运行的文件后缀。 */
    private val EXCLUDED_EXTENSIONS = listOf(
        ".bak", ".bak2", ".backup", ".orig", ".tmp", ".temp",
        ".log", ".ds_store",
    )

    /** 系统/编辑器产生的垃圾文件。 */
    private val EXCLUDED_NAMES = setOf(
        "thumbs.db", "desktop.ini", ".ds_store",
    )

    /**
     * 判断某个相对路径是否应被排除。
     *
     * @param relativePath 相对于游戏根目录的路径，使用 `/` 分隔
     */
    fun shouldExclude(relativePath: String): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        val lowerName = segments.last().lowercase()

        // 目录级排除
        val dirSegments = if (segments.size > 1) segments.dropLast(1) else emptyList()
        for (dir in dirSegments) {
            val lower = dir.lowercase()
            if (EXCLUDED_DIR_PATTERNS.any { lower.contains(it) }) return true
        }

        // 文件级排除
        if (lowerName in EXCLUDED_NAMES) return true
        if (EXCLUDED_EXTENSIONS.any { lowerName.endsWith(it) }) return true

        return false
    }

    /** 便捷重载：直接判断文件。 */
    fun shouldExclude(relativePath: String, file: File): Boolean =
        shouldExclude(relativePath)

    /**
     * 估算过滤后保留的体积。
     *
     * @return (保留字节数, 被排除字节数, 排除文件数)
     */
    fun estimate(dir: File): Triple<Long, Long, Int> {
        var kept = 0L
        var excluded = 0L
        var excludedCount = 0
        runCatching {
            dir.walkTopDown()
                .filter { it.isFile }
                .take(200_000)
                .forEach { file ->
                    val rel = file.relativeTo(dir).invariantSeparatorsPath
                    if (shouldExclude(rel, file)) {
                        excluded += file.length()
                        excludedCount++
                    } else {
                        kept += file.length()
                    }
                }
        }
        return Triple(kept, excluded, excludedCount)
    }
}
