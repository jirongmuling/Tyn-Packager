package com.tyranor.next.packager.sign

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** keystore 中的条目信息（用于让用户选择别名）。 */
data class KeystoreEntry(
    val alias: String,
    /** 证书主体，如 CN=xxx。 */
    val subject: String,
    /** 证书有效期结束时间（毫秒）。 */
    val notAfter: Long,
    /** 算法，如 RSA / EC。 */
    val keyAlgorithm: String,
)

/** keystore 读取结果。 */
sealed interface KeystoreProbe {
    data class Ok(
        val entries: List<KeystoreEntry>,
        /** 实际使用的 store 类型（PKCS12/JKS/BKS）。 */
        val storeType: String,
    ) : KeystoreProbe

    data class Error(val message: String, val cause: Throwable? = null) : KeystoreProbe
}

/**
 * keystore 读取与校验。
 *
 * 支持 PKCS12（.p12/.pfx）、JKS（.jks）、BKS。
 * 全部通过 JDK 内置实现，无需额外依赖。
 *
 * 说明：BKS 类型在 Android 上由 BouncyCastle 提供，若系统未注册会直接失败，
 * 此时给出明确提示而不是抛出堆栈。
 */
object KeystoreReader {

    /** 按扩展名推断类型；无法判断时默认 PKCS12。 */
    fun detectType(file: File): String = when (file.extension.lowercase()) {
        "jks" -> "JKS"
        "bks" -> "BKS"
        else -> "PKCS12"
    }

    /**
     * 打开 keystore 并列出所有可签名条目。
     *
     * @param password keystore 密码
     */
    fun probe(file: File, password: CharArray): KeystoreProbe {
        if (!file.isFile) return KeystoreProbe.Error("文件不存在：${file.name}")
        if (file.length() == 0L) return KeystoreProbe.Error("文件为空")

        val type = detectType(file)
        val store = runCatching {
            KeyStore.getInstance(type).apply {
                FileInputStream(file).use { load(it, password) }
            }
        }.getOrElse { e ->
            return KeystoreProbe.Error(
                when {
                    type == "BKS" -> "无法识别 BKS 格式，请改用 PKCS12 或 JKS"
                    else -> "打开失败，密码可能不正确"
                },
                e,
            )
        }

        val entries = runCatching {
            store.aliases().toList()
                .filter { store.isKeyEntry(it) }
                .mapNotNull { alias ->
                    val cert = store.getCertificate(alias) as? X509Certificate
                        ?: return@mapNotNull null
                    val key = runCatching { store.getKey(alias, password) }.getOrNull()
                        as? PrivateKey ?: return@mapNotNull null
                    KeystoreEntry(
                        alias = alias,
                        subject = cert.subjectX500Principal.name,
                        notAfter = cert.notAfter.time,
                        keyAlgorithm = key.algorithm,
                    )
                }
        }.getOrElse { emptyList() }

        return if (entries.isEmpty()) {
            KeystoreProbe.Error("该 keystore 中没有可用的私钥条目")
        } else {
            KeystoreProbe.Ok(entries, type)
        }
    }

    /**
     * 读取指定条目的签名密钥。
     *
     * @param alias 目标条目；null 表示取第一个
     * @param keyPassword 私钥密码；null 表示与 store 密码相同
     */
    fun read(
        file: File,
        storePassword: CharArray,
        alias: String?,
        keyPassword: CharArray? = null,
    ): Result<SigningKey> = runCatching {
        val type = detectType(file)
        val store = KeyStore.getInstance(type).apply {
            FileInputStream(file).use { load(it, storePassword) }
        }

        val target = alias
            ?: store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw IllegalArgumentException("keystore 中没有带私钥的条目")

        val password = keyPassword ?: storePassword
        val key = store.getKey(target, password) as? PrivateKey
            ?: throw IllegalArgumentException("无法读取私钥，密码可能不正确")

        val chain = store.getCertificateChain(target)
            ?.filterIsInstance<X509Certificate>()
            .orEmpty()
            .ifEmpty { listOfNotNull(store.getCertificate(target) as? X509Certificate) }

        require(chain.isNotEmpty()) { "条目 $target 没有证书" }

        SigningKey(alias = target, privateKey = key, certificates = chain)
    }
}
