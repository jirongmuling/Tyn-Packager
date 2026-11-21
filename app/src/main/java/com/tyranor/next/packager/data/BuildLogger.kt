package com.tyranor.next.packager.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志。
 *
 * 目的：打包失败时用户能导出可读的日志用于排查，而不是只有一句「失败」。
 *
 * 设计取舍：
 * - 单文件追加，超过上限自动截断，避免无限增长；
 * - 只记录打包相关事件，不记录任何个人数据；
 * - 写操作静默失败（日志本身不应影响主流程）。
 */
class BuildLogger(context: Context) {

    private val file: File
        get() = File(File(context.filesDir, "packager"), "build.log")

    /** 追加一条日志。 */
    fun log(tag: String, message: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > MAX_BYTES) {
                // 截断：只保留后半部分
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(keep)
            }
            val line = "${now()} [$tag] $message\n"
            file.appendText(line)
        }
    }

    /** 记录一次失败的完整上下文。 */
    fun logFailure(
        packageName: String,
        gameTitle: String,
        stage: String,
        error: String,
        cause: Throwable?,
    ) {
        log(
            "FAIL",
            "pkg=$packageName game=$gameTitle stage=$stage error=$error" +
                (cause?.let { " cause=${it.javaClass.simpleName}: ${it.message}" } ?: ""),
        )
    }

    /** 读取全部日志。 */
    fun read(): String = runCatching {
        if (file.isFile) file.readText() else ""
    }.getOrDefault("")

    /** 日志文件（用于分享）。 */
    fun logFile(): File = file

    /** 清空日志。 */
    fun clear() {
        runCatching { file.delete() }
    }

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val MAX_BYTES = 256 * 1024
    }
}
