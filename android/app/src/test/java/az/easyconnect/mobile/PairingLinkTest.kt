package az.easyconnect.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    private val token = "A".repeat(32)
    private val fp = "ab".repeat(32)

    private fun link(hosts: String, extra: String = "") =
        "easyconnect://pair?v=1&n=MY-PC%20Home&h=$hosts&p=47800&t=$token&f=$fp$extra"

    @Test
    fun parsesValidLink() {
        val l = PairingLink.parse(link("192.168.43.12,10.0.0.5"))
        assertEquals("MY-PC Home", l.desktopName)
        assertEquals(listOf("192.168.43.12", "10.0.0.5"), l.hosts)
        assertEquals(47800, l.port)
        assertEquals("ABAB-ABAB", l.shortCode)
    }

    @Test
    fun rejectsPublicAddresses() {
        // Saxta QR ekranı internetdəki serverə yönləndirə bilməməlidir
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(link("8.8.8.8")) }
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(link("192.168.1.5,1.2.3.4")) }
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(link("evil.example.com")) }
    }

    @Test
    fun rejectsMalformed() {
        assertThrows(IllegalArgumentException::class.java) { PairingLink.parse("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) {
            PairingLink.parse("easyconnect://pair?v=1&h=192.168.1.5&p=1&t=short&f=$fp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingLink.parse("easyconnect://pair?v=1&h=192.168.1.5&p=1&t=$token&f=XYZ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingLink.parse("easyconnect://pair?v=2&h=192.168.1.5&p=1&t=$token&f=$fp")
        }
    }

    @Test
    fun privateRanges() {
        assertTrue(PairingLink.isPrivateIpv4("172.16.0.1"))
        assertTrue(PairingLink.isPrivateIpv4("172.31.255.255"))
        assertFalse(PairingLink.isPrivateIpv4("172.32.0.1"))
        assertFalse(PairingLink.isPrivateIpv4("192.169.0.1"))
        assertFalse(PairingLink.isPrivateIpv4("10.0.0.256"))
    }

    @Test
    fun targetSizeIsAligned() {
        val (w, h) = ScreenEncoder.targetSize(1200, 2640, 1600)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue(h <= 1616)
    }
}
