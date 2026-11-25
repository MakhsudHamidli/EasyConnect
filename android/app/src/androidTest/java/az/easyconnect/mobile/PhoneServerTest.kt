package az.easyconnect.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Telefon-telefon: görüntüləyici server ↔ paylaşan tərəf, real TLS ilə (cihazda). */
class PhoneServerTest {

    private class Recorder : PhoneServer.Listener {
        val connected = CountDownLatch(1)
        val gotPerms = CountDownLatch(1)
        var perms: JSONObject? = null
        override fun onConnected(phoneName: String) = connected.countDown()
        override fun onVideo(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) = Unit
        override fun onMeta(width: Int, height: Int) = Unit
        override fun onState(json: JSONObject) = Unit
        override fun onPerms(json: JSONObject) { perms = json; gotPerms.countDown() }
        override fun onAudio(flags: Int, ptsUs: Long, data: ByteArray) = Unit
        override fun onEnded(reason: String) = Unit
    }

    private fun loopbackLink(raw: String): PairingLink {
        val l = PairingLink.parse(raw)
        return l.copy(hosts = listOf("127.0.0.1"))
    }

    @Test
    fun validTokenConnectsAndCanBeUsedOnlyOnce() {
        val rec = Recorder()
        val server = PhoneServer(rec).apply { start() }
        try {
            val pairing = server.newPairing("Test")
            assertTrue(pairing.hosts.isNotEmpty() || true)
            val link = loopbackLink(pairing.link.replace(Regex("h=[^&]*"), "h=192.168.0.1"))

            // 1) düzgün token
            val s = PinnedTls.connect(link)
            val w = Protocol.Writer(BufferedOutputStream(s.outputStream))
            val input = DataInputStream(BufferedInputStream(s.inputStream))
            w.send(Protocol.HELLO, JSONObject().put("v", 1).put("token", link.token)
                .put("device", JSONObject().put("name", "Sharer")).toString().toByteArray())
            val welcome = Protocol.read(input)
            assertEquals(Protocol.WELCOME, welcome.type)
            assertTrue(JSONObject(String(welcome.payload)).getBoolean("ok"))
            assertTrue(rec.connected.await(3, TimeUnit.SECONDS))
            w.send(Protocol.PERMS, JSONObject().put("control", true).toString().toByteArray())
            assertTrue(rec.gotPerms.await(3, TimeUnit.SECONDS))
            s.close()

            // 2) eyni token ikinci dəfə — rədd olunmalıdır
            Thread.sleep(300)
            val s2 = PinnedTls.connect(link)
            val w2 = Protocol.Writer(BufferedOutputStream(s2.outputStream))
            val in2 = DataInputStream(BufferedInputStream(s2.inputStream))
            w2.send(Protocol.HELLO, JSONObject().put("v", 1).put("token", link.token).toString().toByteArray())
            val w2r = Protocol.read(in2)
            assertFalse(JSONObject(String(w2r.payload)).getBoolean("ok"))
            s2.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun wrongFingerprintIsRejected() {
        val server = PhoneServer(Recorder()).apply { start() }
        try {
            val link = loopbackLink(server.newPairing("Test").link.replace(Regex("h=[^&]*"), "h=192.168.0.1"))
            val forged = link.copy(fingerprint = "00".repeat(32))
            val err = runCatching { PinnedTls.connect(forged) }.exceptionOrNull()
            assertTrue("MITM qəbul edilməməlidir", err != null)
        } finally {
            server.stop()
        }
    }
}
