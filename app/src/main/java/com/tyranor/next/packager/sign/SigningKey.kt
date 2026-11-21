package com.tyranor.next.packager.sign

import java.io.File
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
