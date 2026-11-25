package az.easyconnect.mobile

import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Görüntüləyici telefonun TLS serveri (masaüstündəki `easy/server.rs`-in Android analoqu).
 * Eyni təhlükəsizlik: birdəfəlik 5 dəqiqəlik token, pin olunan sertifikat, yalnız lokal şəbəkə.
 */
class PhoneServer(private val listener: Listener) {

    interface Listener {
        fun onConnected(phoneName: String)
        fun onVideo(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int)
        fun onMeta(width: Int, height: Int)
        fun onAudio(flags: Int, ptsUs: Long, data: ByteArray)
        fun onState(json: JSONObject)
        fun onPerms(json: JSONObject)
        fun onEnded(reason: String)
    }

    data class Pairing(val link: String, val shortCode: String, val hosts: List<String>)

    private val cert = SelfSignedCert.generate()
    private val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    private var server: SSLServerSocket? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenExpires = 0L
    @Volatile private var writer: Protocol.Writer? = null
    @Volatile private var client: SSLSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val pw = CharArray(0)
        ks.setKeyEntry("ec", cert.keyPair.private, pw, arrayOf(cert.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        runCatching { ss.bind(InetSocketAddress(PORT)) }.onFailure { ss.bind(InetSocketAddress(0)) }
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "ec-phone-server").apply { isDaemon = true; start() }
    }

    /** Yeni birdəfəlik QR linki. */
    fun newPairing(name: String): Pairing {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val rnd = SecureRandom()
        val t = (1..32).map { alphabet[rnd.nextInt(alphabet.size)] }.joinToString("")
        token = t
        tokenExpires = System.currentTimeMillis() + 5 * 60_000
        val hosts = localIpv4s()
        val encName = java.net.URLEncoder.encode(name.take(64), "UTF-8").replace("+", "%20")
        val link = "easyconnect://pair?v=1&n=$encName&h=${hosts.joinToString(",")}&p=$port&t=$t&f=$fingerprint"
        return Pairing(link, fingerprint.take(8).uppercase().chunked(4).joinToString("-"), hosts)
    }

    fun sendControl(json: JSONObject) {
        runCatching { writer?.send(Protocol.CONTROL, json.toString().toByteArray()) }
    }

    fun requestKeyFrame() {
        runCatching { writer?.send(Protocol.REQUEST_KEYFRAME) }
    }

    fun stop() {
        running = false
        runCatching { writer?.send(Protocol.BYE, JSONObject().put("reason", "desktop_closed").toString().toByteArray()) }
        runCatching { client?.close() }
        runCatching { server?.close() }
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (running) {
            val s = try { ss.accept() as SSLSocket } catch (e: Exception) { return }
            if (client != null) { runCatching { s.close() }; continue } // eyni anda bir sessiya
            Thread({ handle(s) }, "ec-phone-session").apply { isDaemon = true; start() }
        }
    }

    private fun consumeToken(candidate: String): Boolean {
        val t = token ?: return false
        if (System.currentTimeMillis() > tokenExpires) return false
        val ok = MessageDigest.isEqual(t.toByteArray(), candidate.toByteArray())
        if (ok) token = null
        return ok
    }

    private fun handle(s: SSLSocket) {
        var reason = "connection_lost"
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            s.startHandshake()
            val input = DataInputStream(BufferedInputStream(s.inputStream, 256 * 1024))
            val w = Protocol.Writer(BufferedOutputStream(s.outputStream, 16 * 1024))
            val hello = Protocol.read(input)
            val hj = runCatching { JSONObject(String(hello.payload)) }.getOrNull()
            if (hello.type != Protocol.HELLO || hj == null || hj.optInt("v") != 1 || !consumeToken(hj.optString("token"))) {
                w.send(Protocol.WELCOME, JSONObject().put("ok", false).put("reason", "invalid_token").toString().toByteArray())
                s.close()
                return
            }
            val myName = (Build.MODEL ?: "Telefon")
            w.send(Protocol.WELCOME, JSONObject().put("ok", true).put("desktop", myName).put("control", true).toString().toByteArray())
            client = s
            writer = w
            val phone = hj.optJSONObject("device")?.optString("name")?.take(64)?.ifBlank { null } ?: "Telefon"
            listener.onConnected(phone)

            s.soTimeout = 20_000
            while (running) {
                val m = Protocol.read(input, Protocol.MAX_VIDEO)
                when (m.type) {
                    Protocol.VIDEO -> if (m.payload.size >= 9) {
                        val flags = m.payload[0].toInt() and 0xFF
                        var pts = 0L
                        for (i in 1..8) pts = (pts shl 8) or (m.payload[i].toLong() and 0xFF)
                        listener.onVideo(flags, pts, m.payload, 9, m.payload.size - 9)
                    }
                    Protocol.AUDIO -> if (m.payload.size >= 9) {
                        val flags = m.payload[0].toInt() and 0xFF
                        var pts = 0L
                        for (i in 1..8) pts = (pts shl 8) or (m.payload[i].toLong() and 0xFF)
                        listener.onAudio(flags, pts, m.payload.copyOfRange(9, m.payload.size))
                    }
                    Protocol.VIDEO_META -> runCatching {
                        val j = JSONObject(String(m.payload))
                        listener.onMeta(j.getInt("w"), j.getInt("h"))
                    }
                    Protocol.STATE -> runCatching { listener.onState(JSONObject(String(m.payload))) }
                    Protocol.PERMS -> runCatching { listener.onPerms(JSONObject(String(m.payload))) }
                    Protocol.PING -> w.send(Protocol.PONG)
                    Protocol.BYE -> { reason = "phone_closed"; break }
                    else -> Unit
                }
            }
            if (!running) reason = "desktop_closed"
        } catch (e: SocketTimeoutException) {
            reason = "timeout"
        } catch (e: Exception) {
            if (!running) reason = "desktop_closed"
        } finally {
            runCatching { s.close() }
            if (client === s) {
                client = null
                writer = null
                listener.onEnded(reason)
            }
        }
    }

    companion object {
        const val PORT = 47801

        /** Telefonun lokal IPv4 ünvanları — Wi-Fi və hotspot əvvəldə, mobil internet heç vaxt. */
        fun localIpv4s(): List<String> {
            val list = mutableListOf<Pair<Int, String>>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val n = ni.name.lowercase()
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy")) continue
                    val rank = when {
                        n.startsWith("wlan") -> 0
                        n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 1
                        else -> 2
                    }
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            val ip = a.hostAddress ?: continue
                            if (PairingLink.isPrivateIpv4(ip)) list += rank to ip
                        }
                    }
                }
            }
            return list.sortedBy { it.first }.map { it.second }.distinct().take(4)
        }
    }
}
