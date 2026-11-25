package az.easyconnect.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

class SecurityRulesTest {

    @Test
    fun bankingAppsAreHiddenByDefault() {
        listOf(
            "az.kapitalbank.birbank", "az.leobank.app", "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel", "com.x8bit.bitwarden",
            "com.google.android.apps.authenticator2", "com.revolut.revolut", "az.pashabank.mobile",
        ).forEach { assertEquals(it, ProtectedApps.Level.HIDE, ProtectedApps.defaultLevel(it)) }
    }

    @Test
    fun systemAppsAreControlLocked() {
        listOf(
            "com.android.settings", "com.google.android.permissioncontroller",
            "com.android.vending", "com.google.android.packageinstaller", BuildConfigPkg.SELF,
        ).forEach { assertEquals(it, ProtectedApps.Level.CONTROL_LOCK, ProtectedApps.defaultLevel(it)) }
    }

    @Test
    fun ordinaryAppsAreNotProtected() {
        listOf(
            "com.whatsapp", "com.google.android.youtube", "com.android.chrome",
            "com.google.android.apps.maps", "com.instagram.android", "com.spotify.music",
        ).forEach { assertEquals(it, ProtectedApps.Level.NONE, ProtectedApps.defaultLevel(it)) }
    }

    @Test
    fun noControlWithoutActiveSessionOrService() {
        // Sessiya yoxdur → heç nə icra olunmamalıdır
        SessionPolicy.end()
        assertFalse(SessionPolicy.canControl())
        assertFalse(SessionPolicy.canType())
        // Sessiya var, icazə var, amma Accessibility xidməti yoxdur → yenə yox
        SessionPolicy.start(control = true, keyboard = true, audio = true, files = true)
        assertFalse(SessionPolicy.canControl())
        assertFalse(SessionPolicy.canType())
        SessionPolicy.end()
    }

    @Test
    fun ownAppOnlyPermissionScreensAreLocked() {
        val self = BuildConfigPkg.SELF
        // İcazə və qorunan tətbiqlər ekranları uzaqdan idarə edilə bilməz
        assertEquals(ProtectedApps.Level.CONTROL_LOCK, ProtectedApps.classify(self, "$self.PermissionsActivity"))
        assertEquals(ProtectedApps.Level.CONTROL_LOCK, ProtectedApps.classify(self, "$self.ProtectedAppsActivity"))
        // Ekran bilinmirsə — ehtiyatla bloklu
        assertEquals(ProtectedApps.Level.CONTROL_LOCK, ProtectedApps.classify(self, null))
        // Əsas ekran idarə oluna bilər (tətbiqdən çıxmaq, fayl göndərmək)
        assertEquals(ProtectedApps.Level.NONE, ProtectedApps.classify(self, "$self.MainActivity"))
    }

    @Test
    fun receivedFileNamesCannotEscapeDownloadFolder() {
        assertEquals("passwd", FileTransfer.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileTransfer.sanitize("C:\\Windows\\evil.txt"))
        assertEquals("bashrc", FileTransfer.sanitize(".bashrc"))
        assertEquals("file", FileTransfer.sanitize(""))
        assertEquals("a b.pdf", FileTransfer.sanitize("a b.pdf"))
    }

    @Test
    fun selfSignedCertificateIsValidAndUnique() {
        val a = SelfSignedCert.generate()
        val b = SelfSignedCert.generate()
        a.certificate.checkValidity()
        a.certificate.verify(a.keyPair.public)
        val fa = MessageDigest.getInstance("SHA-256").digest(a.certificate.encoded)
        val fb = MessageDigest.getInstance("SHA-256").digest(b.certificate.encoded)
        assertFalse(fa.contentEquals(fb))
    }
}
