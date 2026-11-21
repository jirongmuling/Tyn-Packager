package com.tyranor.next.packager.sign

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 测试签名生成器。
 *
 * 生成一张自签的 RSA 2048 证书（等效于 Android debug keystore），
 * 供“测试签名”模式使用，用户无需提供任何文件。
 *
 * 密钥保存在**应用私有 `filesDir`**，保证：
 * - 同一台设备多次打包签名一致（签名变化会导致升级安装失败）；
 * - 系统清理缓存不会丢失。
 *
 * 历史版本曾存放在 `cacheDir`，[get] 会自动迁移到新位置，避免签名发生变化。
 */
object TestSigningKeyProvider {

    private const val KEY_ALIAS = "tynpackager-test"
    private const val KEYSTORE_FILE = "test-signing.p12"
    private const val PASSWORD = "tynpackager"

    @Volatile
    private var cached: SigningKey? = null

    /** 当前密钥库位置：`filesDir/signing/test-signing.p12`。 */
    fun keystoreFile(context: Context): File =
        File(File(context.filesDir, "signing"), KEYSTORE_FILE)

    /** 旧版本缓存位置，仅用于迁移。 */
    private fun legacyKeystoreFile(context: Context): File =
        File(context.cacheDir, KEYSTORE_FILE)

    /** 取得（必要时创建）测试签名密钥。 */
    @Synchronized
    fun get(context: Context): SigningKey {
        cached?.let { return it }

        val target = keystoreFile(context)
        migrateFromCache(context, target)

        val key = when {
            !target.isFile -> create(target)
            // 文件损坏时重建，保证打包流程不中断
            else -> readExisting(target) ?: create(target)
        }
        cached = key
        return key
    }

    /** 旧版本存在缓存目录时搬到 filesDir。 */
    private fun migrateFromCache(context: Context, target: File) {
        if (target.isFile) return
        val legacy = legacyKeystoreFile(context)
        if (!legacy.isFile) return
        runCatching {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = true)
            legacy.delete()
        }
    }

    private fun readExisting(file: File): SigningKey? = runCatching {
        val store = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(file).use { load(it, PASSWORD.toCharArray()) }
        }
        val privateKey = store.getKey(KEY_ALIAS, PASSWORD.toCharArray()) as? PrivateKey
            ?: return@runCatching null
        val cert = store.getCertificate(KEY_ALIAS) as? X509Certificate
            ?: return@runCatching null
        SigningKey(alias = KEY_ALIAS, privateKey = privateKey, certificates = listOf(cert))
    }.getOrNull()

    private fun create(file: File): SigningKey {
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
            // UTCTime 上限为 2049 年，给足 25 年
            notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 25)),
        )

        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(KEY_ALIAS, pair.private, PASSWORD.toCharArray(), arrayOf(cert))

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { store.store(it, PASSWORD.toCharArray()) }

        return SigningKey(
            alias = KEY_ALIAS,
            privateKey = pair.private,
            certificates = listOf(cert),
        )
    }

    /** 清空内存缓存（调试用，不影响磁盘密钥）。 */
    fun invalidate() {
        cached = null
    }
}
