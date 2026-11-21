package com.tyranor.next.packager.sign

import android.os.Build
import com.android.apksig.ApkSigner
import java.io.File

/** APK 签名结果。 */
sealed interface SignResult {
    data class Success(val output: File) : SignResult
    data class Failure(val error: String, val cause: Throwable? = null) : SignResult
}

/**
 * APK 签名器，基于 AOSP 官方 [ApkSigner]。
 *
 * 默认开启 v1/v2/v3（按系统版本），并自动 `zipalign`（apksig 内部处理对齐）。
 */
object ApkSignerWrapper {

    /**
     * 对 [input] 签名并写入 [output]。
     *
     * @param input 待签名 APK
     * @param output 输出 APK（会被覆盖）
     * @param key 签名密钥
     * @param createdBy 写入签名块的 createdBy 字段
     */
    fun sign(
        input: File,
        output: File,
        key: SigningKey,
        createdBy: String = "Tyn Packager",
    ): SignResult {
        return runCatching {
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()

            val config = ApkSigner.SignerConfig.Builder(
                key.alias,
                key.privateKey,
                key.certificates,
            ).build()

            val builder = ApkSigner.Builder(listOf(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setCreatedBy(createdBy)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setMinSdkVersion(-1) // -1 = 按 manifest 推导

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setV3SigningEnabled(true)
            }

            builder.build().sign()
            SignResult.Success(output)
        }.getOrElse { e ->
            SignResult.Failure(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
