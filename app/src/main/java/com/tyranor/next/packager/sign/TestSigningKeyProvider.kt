package com.tyranor.next.packager.sign

import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 测试签名生成器。
 *
 * 生成一张自签的 RSA 2048 证书（等效于 Android debug keystore），
 * 供“测试签名”模式使用，用户无需提供任何文件。
 *
 * 结果会缓存在应用私有目录，保证**同一台设备多次打包签名一致**——
 * 签名不一致会导致升级安装失败，因此不能用随机密钥每次重生成。
 */
object TestSigningKeyProvider {

    private const val KEY_ALIAS = "tynpackager-test"
    private const val KEYSTORE_FILE = "test-signing.p12"
    private const val PASSWORD = "tynpackager"

    @Volatile
    private var cached: SigningKey? = null

    /** 取得（必要时创建）测试签名密钥。 */
    @Synchronized
    fun get(storeDir: java.io.File): SigningKey {
        cached?.let { return it }

        val file = java.io.File(storeDir, KEYSTORE_FILE)
        val key = if (file.isFile) readExisting(file) else create(file)
        cached = key
        return key
    }

    private fun readExisting(file: java.io.File): SigningKey? = runCatching {
        val store = KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, PASSWORD.toCharArray()) }
        }
        val key = store.getKey(KEY_ALIAS, PASSWORD.toCharArray()) as java.security.PrivateKey
        val cert = store.getCertificate(KEY_ALIAS) as X509Certificate
        SigningKey(alias = KEY_ALIAS, privateKey = key, certificates = listOf(cert))
    }.getOrNull()

    private fun create(file: java.io.File): SigningKey {
        val generator = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val pair = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val cert = CertificateBuilder.build(
            keyPair = pair,
            subject = "Tyn Packager Test",
            serial = CertificateBuilder.randomSerial(),
            notBefore = Date(now - TimeUnit.DAYS.toMillis(1)),
            // Android 要求证书有效期覆盖安装期；给足 25 年（UTCTime 上限 2049）
            notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 25)),
        )

        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(KEY_ALIAS, pair.private, PASSWORD.toCharArray(), arrayOf(cert))
        file.parentFile?.mkdirs()
        file.outputStream().use { store.store(it, PASSWORD.toCharArray()) }

        return SigningKey(
            alias = KEY_ALIAS,
            privateKey = pair.private,
            certificates = listOf(cert),
        )
    }

    /** 清空缓存（调试用）。 */
    fun invalidate() {
        cached = null
    }
}
