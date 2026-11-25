package az.easyconnect.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Android-in öz fayl seçicisini açır və seçilən faylları aktiv paylaşım sessiyasına göndərir.
 * Heç bir yaddaş icazəsi tələb etmir: tətbiq yalnız seçilmiş fayllara müvəqqəti giriş alır.
 */
class FilePickActivity : AppCompatActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val svc = ScreenShareService.current
            if (svc != null && SessionPolicy.active && SessionPolicy.allowFiles) {
                svc.sendFiles(uris)
                Toast.makeText(this, getString(R.string.files_sending, uris.size), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.files_not_allowed, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionPolicy.active || !SessionPolicy.allowFiles) {
            Toast.makeText(this, R.string.files_not_allowed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) picker.launch(arrayOf("*/*"))
    }
}
