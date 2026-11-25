package az.easyconnect.mobile

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Görüntüləyici telefon üçün hər sessiyada yeni EC P-256 açarı və self-signed sertifikat.
 * Xarici kitabxana (BouncyCastle) lazım olmasın deyə minimal X.509 v3 DER əl ilə qurulur.
 * Barmaq izi QR-a yazılır, paylaşan telefon onu pin edir — CA zənciri lazım deyil.
 */
object SelfSignedCert {
    data class Result(val keyPair: KeyPair, val certificate: X509Certificate)

    fun generate(commonName: String = "EasyConnect Phone"): Result {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()

        val ecdsaSha256 = seq(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
        val name = seq(set(seq(oid(byteArrayOf(0x55, 0x04, 0x03)), der(0x0C, commonName.toByteArray()))))
        val now = System.currentTimeMillis()
        val validity = seq(utcTime(Date(now - 86_400_000L)), utcTime(Date(now + 365L * 86_400_000L)))
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }.also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val version = der(0xA0, der(0x02, byteArrayOf(0x02)))

        val tbs = seq(version, der(0x02, serial), ecdsaSha256, name, validity, name, kp.public.encoded)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(tbs)
            sign()
        }
        val certDer = seq(tbs, ecdsaSha256, der(0x03, byteArrayOf(0) + sig))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certDer.inputStream()) as X509Certificate
        cert.verify(kp.public)
        return Result(kp, cert)
    }

    // ---- minimal DER

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })
    private fun set(vararg parts: ByteArray) = der(0x31, parts.fold(ByteArray(0)) { acc, p -> acc + p })
    private fun oid(encoded: ByteArray) = der(0x06, encoded)

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(0x17, f.format(d).toByteArray())
    }
}
