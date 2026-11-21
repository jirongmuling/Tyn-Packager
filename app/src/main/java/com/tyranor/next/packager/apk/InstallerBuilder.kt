package com.tyranor.next.packager.apk

import com.tyranor.next.packager.sign.ApkSignerWrapper
import com.tyranor.next.packager.sign.SignResult
import com.tyranor.next.packager.sign.SigningKey
import java.io.File

/** 打包参数。 */
data class BuildRequest(
    /** 源 APK（Tyranor 本体）。 */
    val sourceApk: File,
    /** 游戏所在目录（真实路径）。 */
    val gameDir: File,
    /** 输出 APK。 */
    val outputApk: File,
    /** 新包名。 */
    val packageName: String,
    /** 应用显示名。 */
    val appLabel: String,
    /** 自定义封面 PNG 字节；null 表示不换图标。 */
    val iconPng: ByteArray? = null,
    /** 游戏资源在 APK 内的存放前缀。 */
    val assetPrefix: String = DEFAULT_ASSET_PREFIX,
    /** 签名密钥。 */
    val signingKey: SigningKey,
) {
    companion object {
        const val DEFAULT_ASSET_PREFIX = "assets/game"
    }
}

/** 打包阶段，用于 UI 展示进度。 */
enum class BuildStage(val display: String) {
    PREPARING("准备中"),
    REWRITING("改造 APK"),
    EMBEDDING("内置游戏资源"),
    SIGNING("签名"),
    DONE("完成"),
}

/** 打包结果。 */
sealed interface BuildResult {
    data class Success(val apk: File, val embeddedFiles: Int) : BuildResult
    data class Failure(val stage: BuildStage, val message: String, val cause: Throwable? = null) : BuildResult
}

/**
 * 直装包构建编排。
 *
 * 流程：读取源 APK → 改包名/名称/图标 → 内置游戏资源 → 输出未签名 APK → 签名。
 *
 * 说明：
 * - 采用**原地改包**而非 aapt 反编译重打包，最大限度保留引擎 native 库；
 * - 游戏资源以原始文件形式写入 APK 的 [BuildRequest.assetPrefix] 下，
 *   由运行时按需解出。
 */
class InstallerBuilder(
    private val onStage: (BuildStage) -> Unit = {},
) {

    fun build(request: BuildRequest): BuildResult {
        var rewriter: ApkRewriter? = null
        var unsigned: File? = null

        return try {
            onStage(BuildStage.PREPARING)
            require(request.sourceApk.isFile) { "源 APK 不存在：${request.sourceApk}" }
            require(request.gameDir.isDirectory) { "游戏目录不存在：${request.gameDir}" }
            ApkPackageRules.isValid(request.packageName) || error("包名非法")

            onStage(BuildStage.REWRITING)
            rewriter = ApkRewriter(request.sourceApk)
            rewriter.setPackageName(request.packageName)
            request.appLabel.takeIf { it.isNotBlank() }?.let { rewriter.setAppLabel(it) }
            request.iconPng?.let { rewriter.replaceLauncherIcon(it) }

            onStage(BuildStage.EMBEDDING)
            // 清理同前缀旧内容，避免重复打包时残留
            rewriter.removeDirectory(request.assetPrefix)
            val embedded = rewriter.putDirectory(request.assetPrefix, request.gameDir)

            unsigned = File(request.outputApk.parentFile, "unsigned.tmp.apk")
            rewriter.writeTo(unsigned)

            onStage(BuildStage.SIGNING)
            when (val signed = ApkSignerWrapper.sign(unsigned, request.outputApk, request.signingKey)) {
                is SignResult.Success -> {
                    onStage(BuildStage.DONE)
                    BuildResult.Success(signed.output, embedded)
                }
                is SignResult.Failure -> BuildResult.Failure(
                    BuildStage.SIGNING, signed.error, signed.cause,
                )
            }
        } catch (e: Exception) {
            BuildResult.Failure(BuildStage.DONE, e.message ?: e.javaClass.simpleName, e)
        } finally {
            runCatching { rewriter?.close() }
            runCatching { unsigned?.delete() }
        }
    }
}
