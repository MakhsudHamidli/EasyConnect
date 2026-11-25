package az.easyconnect.mobile

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Yalnız QR-dakı barmaq izinə uyğun sertifikatı olan kompüterə qoşulur (certificate pinning).
 * Şəbəkədə özünü kompüter kimi göstərən başqa cihaz (MITM) bu yoxlamadan keçə bilməz.
 *
 * Lint `CustomX509TrustManager` xəbərdarlığı: bu menecer yoxlamanı SÖNDÜRMÜR — əksinə,
 * sistem CA-larından daha sərtdir: yalnız bir konkret sertifikat qəbul olunur, qalan hamısı rədd.
 * (Kompüterin sertifikatı self-signed-dir, ona görə sistem CA yoxlaması burada tətbiq oluna bilməz.)
 */
@android.annotation.SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val expectedSha256Hex: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("sertifikat yoxdur")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val expected = expectedSha256Hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (!MessageDigest.isEqual(actual, expected)) {
            throw CertificateException("Kompüterin sertifikatı QR kodla uyğun gəlmir")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client mode istifadə olunmur")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object PinnedTls {
    /** QR-dakı ünvanları növbə ilə yoxlayır, ilk uğurlu TLS əlaqəsini qaytarır. */
    fun connect(link: PairingLink, timeoutMs: Int = 4000): SSLSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(PinningTrustManager(link.fingerprint)), null)
        var lastError: Exception? = null
        for (host in link.hosts) {
            val socket = ctx.socketFactory.createSocket() as SSLSocket
            try {
                val wanted = listOf("TLSv1.3", "TLSv1.2")
                socket.enabledProtocols = socket.supportedProtocols.filter { it in wanted }.toTypedArray()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, link.port), timeoutMs)
                socket.soTimeout = 10_000
                socket.startHandshake()
                return socket
            } catch (e: Exception) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IllegalStateException("ünvan yoxdur")
    }
}
