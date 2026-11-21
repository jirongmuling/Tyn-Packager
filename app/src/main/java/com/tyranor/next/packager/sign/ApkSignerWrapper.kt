package com.tyranor.next.packager.sign

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.tyranor.next.packager.apk.ApkRewriter
import java.io.File

/** APK 签名结果。 */
sealed interface SignResult {
    data class Success(val output: File, val schemes: List<String>) : SignResult
    data class Failure(val error: String, val cause: Throwable? = null) : SignResult
}

/**
 * APK 签名器，基于 AOSP 官方 [ApkSigner]。
 *
 * ### 关键设计
 * 各签名方案的启用与否**取决于 APK 自身的 minSdkVersion**，而不是本机系统版本：
 * 用本机版本判断会导致在旧设备上给新 APK 签名时漏掉 v3，进而降低兼容性。
 *
 * - minSdk < 24：必须 v1（v2 从 Android 7.0 开始支持）
 * - minSdk >= 24：v2 即可，v1 也一并开启以兼容各类 ROM
 * - minSdk >= 28：额外开启 v3（支持密钥轮换）
 *
 * v4 需要伴随 `.idsig` 文件，用于增量安装；普通 sideload 不需要，故不启用。
 */
object ApkSignerWrapper {

    /**
     * 对 [input] 签名并写入 [output]。
     *
     * @param minSdk 源 APK 的 minSdkVersion；未知时传 null，将自动读取清单
     * @param createdBy 写入签名块的 createdBy 字段
     */
    fun sign(
        input: File,
        output: File,
        key: SigningKey,
        minSdk: Int? = null,
        createdBy: String = "Tyn Packager",
    ): SignResult {
        return runCatching {
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()

            // 无法确定 minSdk 时，三种方案全开，最大化兼容
            val effectiveMinSdk = minSdk ?: readMinSdk(input) ?: ALL_SCHEMES_MIN_SDK

            val config = ApkSigner.SignerConfig.Builder(
                key.alias,
                key.privateKey,
                key.certificates,
            ).build()

            val builder = ApkSigner.Builder(listOf(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setCreatedBy(createdBy)
                .setMinSdkVersion(effectiveMinSdk)
                // 保留库文件的页对齐（原生库 .so 需要 4K/16K 对齐才能被正确映射）
                .setLibraryPageAlignmentBytes(4096)

            // 方案选择完全依据 APK 的 minSdk
            builder.setV1SigningEnabled(effectiveMinSdk < 24 || effectiveMinSdk == ALL_SCHEMES_MIN_SDK)
            builder.setV2SigningEnabled(true)
            builder.setV3SigningEnabled(
                effectiveMinSdk >= 28 || effectiveMinSdk == ALL_SCHEMES_MIN_SDK,
            )
            builder.setV4SigningEnabled(false)

            builder.build().sign()

            val schemes = buildList {
                if (effectiveMinSdk < 24 || effectiveMinSdk == ALL_SCHEMES_MIN_SDK) add("v1")
                add("v2")
                if (effectiveMinSdk >= 28 || effectiveMinSdk == ALL_SCHEMES_MIN_SDK) add("v3")
            }
            SignResult.Success(output, schemes)
        }.getOrElse { e ->
            SignResult.Failure(describe(e), e)
        }
    }

    /** 校验已签名 APK 的签名方案，返回可读描述。 */
    fun verify(apk: File): String = runCatching {
        val result = ApkVerifier.Builder(apk).build().verify()
        if (!result.isVerified) {
            return "验签失败：${result.errors.firstOrNull()?.toString() ?: "未知原因"}"
        }
        val schemes = buildList {
            if (result.isVerifiedUsingV1Scheme) add("v1")
            if (result.isVerifiedUsingV2Scheme) add("v2")
            if (result.isVerifiedUsingV3Scheme) add("v3")
        }
        "签名有效（${schemes.joinToString("/")}）"
    }.getOrElse { "验签异常：${it.message}" }

    /** 从 APK 清单读取 minSdkVersion；失败返回 null。 */
    private fun readMinSdk(apk: File): Int? = runCatching {
        ApkRewriter(apk).use { rewriter -> rewriter.minSdkVersion() }
    }.getOrNull()

    /** 无法确定 minSdk 时的哨兵值：表示「三种方案全开」。 */
    private const val ALL_SCHEMES_MIN_SDK = -1

    /** 把异常转成可读提示。 */
    private fun describe(e: Throwable): String = when {
        e.message?.contains("DOES NOT VERIFY", ignoreCase = true) == true -> "签名校验未通过"
        e.message?.contains("signature", ignoreCase = true) == true -> "签名失败：${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
