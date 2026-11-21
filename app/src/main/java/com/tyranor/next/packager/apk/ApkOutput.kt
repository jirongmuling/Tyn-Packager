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

    private fun uriFor(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
}
