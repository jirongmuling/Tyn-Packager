package com.tyranor.next.packager.sign

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 自签 X.509 证书构造（不依赖 BouncyCastle）。
 *
 * 手写最小 DER 编码：TBSCertificate + Sha256WithRSA 签名。
 * 只要满足 APK 签名校验需要的最小字段集合：
 * version / serial / signature / issuer / validity / subject / SPKI。
 */
internal object CertificateBuilder {

    fun build(
        keyPair: KeyPair,
        subject: String,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
    ): X509Certificate {
        val spki = keyPair.public.encoded
        val name = derSequence(derSet(derSequence(derOid("2.5.4.3"), utf8String(subject))))
        val algo = derSequence(derOid("1.2.840.113549.1.1.11"), derNull())

        val tbs = derSequence(
            derTagged(0xA0.toByte(), derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            algo,
            name,
            derSequence(derTime(notBefore), derTime(notAfter)),
            name,
            spki,
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }

        val cert = derSequence(
            tbs,
            algo,
            derBitString(signature),
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    // ---------- DER 原语 ----------

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
        len <= 0xFFFF -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        len <= 0xFFFFFF -> byteArrayOf(
            0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte(),
        )
        else -> throw IllegalArgumentException("length too large: $len")
    }

    private fun derTag(tag: Byte, content: ByteArray): ByteArray =
        byteArrayOf(tag) + derLength(content.size) + content

    private fun derTagged(tag: Byte, content: ByteArray): ByteArray = derTag(tag, content)

    private fun derSequence(vararg parts: ByteArray): ByteArray =
        derTag(0x30, parts.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    private fun derSet(vararg parts: ByteArray): ByteArray =
        derTag(0x31, parts.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    private fun derInteger(value: BigInteger): ByteArray =
        derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray =
        derTag(0x03, byteArrayOf(0x00) + bytes)

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Int>()
            stack.add((v and 0x7F).toInt())
            v = v shr 7
            while (v > 0) {
                stack.add(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            for (j in stack.indices.reversed()) out.write(stack[j])
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun utf8String(text: String): ByteArray = derTag(0x0C, text.toByteArray(Charsets.UTF_8))

    private fun derTime(date: Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        // UTCTime；2050 年后须改用 GeneralizedTime，这里限制在 30 年内
        return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    /** 生成随机序号，保证正数。 */
    fun randomSerial(): BigInteger =
        BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE)
}
