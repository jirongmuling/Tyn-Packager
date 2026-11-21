package com.tyranor.next.packager.apk

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 输出 APK 的分享与安装。
 *
 * 产物在应用私有目录，需要经 FileProvider 授权给系统安装器或分享目标。
 */
object ApkOutput {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * 调起系统安装器安装 APK。
     *
     * 需要 `REQUEST_INSTALL_PACKAGES` 权限，且用户可能还需在系统设置中
     * 允许本应用安装未知应用。
     */
    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        require(apk.isFile) { "APK 不存在：$apk" }
        val uri = uriFor(context, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 分享 APK 到其他应用（聊天、网盘等）。 */
    fun share(context: Context, apk: File): Result<Unit> = runCatching {
        require(apk.isFile) { "APK 不存在：$apk" }
        val uri = uriFor(context, apk)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享 APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 导出到系统「下载」目录，方便用文件管理器查找或传给别人。
     *
     * 使用 MediaStore，无需任何存储权限，Android 10+ 通用；
     * 同名文件会自动追加序号。
     *
     * @return 导出后的文件路径；失败返回 null
     */
    fun exportToDownloads(context: Context, apk: File): String? = runCatching {
        require(apk.isFile) { "APK 不存在" }

        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
            }
        }

        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            apk.inputStream().use { input -> input.copyTo(out) }
        } ?: return null

        uri.toString()
    }.getOrNull()

    private fun uriFor(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
}
