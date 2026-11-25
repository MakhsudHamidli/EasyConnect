package az.easyconnect.mobile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real kompüterə qarşı TLS handshake testi (Android-in öz TLS kitabxanası ilə).
 * İşə salmaq:
 *   gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.host=10.0.0.5 \
 *     -Pandroid.testInstrumentationRunnerArguments.fp=<sha256>
 */
class TlsProbeTest {
    @Test
    fun handshakeWithDesktop() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("host")
        val fp = args.getString("fp")
        assumeTrue("host/fp verilməyib", host != null && fp != null)
        val link = PairingLink("probe", listOf(host!!), (args.getString("port") ?: "47800").toInt(), "A".repeat(32), fp!!)
        try {
            val s = PinnedTls.connect(link)
            Log.i("TlsProbe", "OK ${s.session.protocol} ${s.session.cipherSuite}")
            s.close()
        } catch (e: Exception) {
            var t: Throwable? = e
            while (t != null) { Log.e("TlsProbe", "${t.javaClass.name}: ${t.message}"); t = t.cause }
            throw e
        }
    }
}
