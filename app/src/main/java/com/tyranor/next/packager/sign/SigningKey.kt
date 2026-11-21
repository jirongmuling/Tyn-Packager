package com.tyranor.next.packager.sign

import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** 一组可用于 APK 签名的密钥材料。 */
data class SigningKey(
    /** 显示名，写入 apksig 的 createdBy / signer 名。 */
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
) {
    init {
        require(certificates.isNotEmpty()) { "至少需要一张证书" }
    }
}

/** 签名方式。 */
sealed interface SigningSource {
    /** 测试签名：应用内生成的自签 debug 证书，无需用户提供任何文件。 */
    data object TestKey : SigningSource

    /**
     * 自定义签名：用户导入的 PKCS12 / JKS keystore。
     *
     * @param storePassword keystore 密码
     * @param keyPassword 私钥密码；null 表示与 [storePassword] 相同
     * @param alias 目标条目别名；null 表示取第一个带私钥的条目
     */
    data class Keystore(
        val file: File,
        val storePassword: CharArray,
        val keyPassword: CharArray? = null,
        val alias: String? = null,
    ) : SigningSource
}

/** 从 keystore 读取密钥材料。 */
object KeystoreReader {

    /**
     * 读取 [SigningSource.Keystore] 中的私钥与证书链。
     *
     * @throws IllegalArgumentException keystore 无法打开或找不到带私钥的条目
     */
    fun read(source: SigningSource.Keystore): SigningKey {
        val type = detectType(source.file)
        val store = KeyStore.getInstance(type).apply {
            source.file.inputStream().use { load(it, source.storePassword) }
        }

        val alias = source.alias
            ?: store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: throw IllegalArgumentException("keystore 中没有带私钥的条目")

        val keyPassword = source.keyPassword ?: source.storePassword
        val key = store.getKey(alias, keyPassword) as? PrivateKey
            ?: throw IllegalArgumentException("别名 $alias 不是私钥条目")

        val chain = store.getCertificateChain(alias)
            ?.filterIsInstance<X509Certificate>()
            .orEmpty()
            .ifEmpty {
                listOfNotNull(store.getCertificate(alias) as? X509Certificate)
            }
        require(chain.isNotEmpty()) { "别名 $alias 没有证书链" }

        return SigningKey(alias = alias, privateKey = key, certificates = chain)
    }

    /** 按扩展名/魔数判断 keystore 类型。 */
    private fun detectType(file: File): String = when (file.extension.lowercase()) {
        "p12", "pfx", "pkcs12" -> "PKCS12"
        "jks" -> "JKS"
        "bks" -> "BKS"
        else -> "PKCS12"
    }
}
