package com.tyranor.next.packager.apk

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/** 安装前检查结果。 */
data class InstallCheck(
    /** 是否可以安装。 */
    val canInstall: Boolean,
    /** 已安装同包名应用的版本名。 */
    val installedVersionName: String? = null,
    /** 签名是否一致；null 表示无法判断。 */
    val signatureMatches: Boolean? = null,
    /** 面向用户的提示。 */
    val message: String,
)

/**
 * 安装前检查。
 *
 * 覆盖最常见的两类失败：
 * 1. 同包名已安装但签名不一致 → 必须卸载或换包名；
 * 2. 同包名已安装且签名一致 → 可覆盖升级。
 *
 * 只能通过 v1 签名块中的 META-INF 证书文件读取；仅有 v2/v3 的 APK
 * 无法在此比对，此时不阻止安装，交由系统校验。
 */
object InstallChecker {

    fun check(context: Context, apk: File, packageName: String): InstallCheck {
        if (!apk.isFile) return InstallCheck(false, message = "APK 文件不存在")

        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return InstallCheck(true, message = "未安装同包名应用，可以全新安装")

        val installedVersion = installed.versionName ?: "?"

        val installedHashes = runCatching {
            installed.signingInfo?.apkContentsSigners
                ?.map { it.toByteArray().contentHashCode() }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        val apkHashes = readApkSignatureHashes(apk)?.toSet().orEmpty()

        val matches: Boolean? = when {
            installedHashes.isEmpty() || apkHashes.isEmpty() -> null
            else -> installedHashes == apkHashes
        }

        return when (matches) {
            true -> InstallCheck(
                canInstall = true,
                installedVersionName = installedVersion,
                signatureMatches = true,
                message = "已安装同签名版本（$installedVersion），将覆盖升级",
            )
            false -> InstallCheck(
                canInstall = false,
                installedVersionName = installedVersion,
                signatureMatches = false,
                message = "已安装同包名应用（$installedVersion）但签名不同，无法覆盖。" +
                    "请先卸载原应用，或改用其他包名。",
            )
            null -> InstallCheck(
                canInstall = true,
                installedVersionName = installedVersion,
                signatureMatches = null,
                message = "已安装同包名应用（$installedVersion），安装时由系统校验签名",
            )
        }
    }

    /** 从 APK 的 v1 签名块读取证书摘要。 */
    private fun readApkSignatureHashes(apk: File): List<Int>? = runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        val hashes = mutableListOf<Int>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { e ->
                    val n = e.name.uppercase()
                    n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA"))
                }
                .forEach { entry ->
                    zip.getInputStream(entry).use { input ->
                        runCatching {
                            hashes.add(factory.generateCertificate(input).encoded.contentHashCode())
                        }
                    }
                }
        }
        hashes.ifEmpty { null }
    }.getOrNull()
}
