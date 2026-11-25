package az.easyconnect.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Paylaşım zamanı icazələri dəyişmək. Bu ekran UZAQDAN İDARƏYƏ BAĞLIDIR
 * (ProtectedApps.SELF_LOCKED_ACTIVITIES) — qarşı tərəf özünə icazə verə bilməz.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var control: CheckBox
    private lateinit var keyboard: CheckBox
    private lateinit var audio: CheckBox
    private lateinit var files: CheckBox
    private lateinit var a11yWarning: TextView
    private lateinit var a11yBtn: Button
    private var binding = false

    private val listener: () -> Unit = { runOnUiThread { render() } }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) audio.isChecked = false
        apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)

        val color = { id: Int -> ContextCompat.getColor(this, id) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(20), dp(20), dp(20), dp(20))
            filterTouchesWhenObscured = true
        }
        root.addView(TextView(this).apply {
            setText(R.string.perms_title)
            textSize = 22f
            setTextColor(color(R.color.text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(R.string.perms_live_hint)
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(4), 0, dp(12))
        })
        fun box(text: Int) = CheckBox(this).apply {
            setText(text)
            textSize = 15f
            setTextColor(color(R.color.text))
            minHeight = dp(48)
            buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
            setOnCheckedChangeListener { _, _ -> if (!binding) onToggle(this) }
            root.addView(this)
        }
        control = box(R.string.perm_control)
        keyboard = box(R.string.perm_keyboard)
        audio = box(R.string.perm_audio)
        files = box(R.string.perm_files)
        a11yWarning = TextView(this).apply {
            setText(R.string.a11y_needed)
            setTextColor(color(R.color.warn))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            root.addView(this)
        }
        a11yBtn = Button(this).apply {
            setText(R.string.a11y_open)
            isAllCaps = false
            setOnClickListener { openAccessibility() }
            root.addView(this)
        }
        if (!BuildConfig.REMOTE_CONTROL) {
            control.visibility = View.GONE
            keyboard.visibility = View.GONE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) audio.visibility = View.GONE
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        SessionPolicy.addListener(listener)
        render()
    }

    override fun onStop() {
        SessionPolicy.removeListener(listener)
        super.onStop()
    }

    private fun onToggle(box: CheckBox) {
        if (box === audio && audio.isChecked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        apply()
    }

    private fun apply() {
        SessionPolicy.setPermissions(control.isChecked, keyboard.isChecked, audio.isChecked, files.isChecked)
    }

    private fun render() {
        if (!SessionPolicy.active) { finish(); return }
        binding = true
        control.isChecked = SessionPolicy.allowControl
        keyboard.isChecked = SessionPolicy.allowKeyboard
        audio.isChecked = SessionPolicy.allowAudio
        files.isChecked = SessionPolicy.allowFiles
        binding = false
        val needs = BuildConfig.REMOTE_CONTROL && (SessionPolicy.allowControl || SessionPolicy.allowKeyboard) && !SessionPolicy.serviceReady
        a11yWarning.visibility = if (needs) View.VISIBLE else View.GONE
        a11yBtn.visibility = if (needs) View.VISIBLE else View.GONE
    }

    private fun openAccessibility() {
        val component = ComponentName(this, ControlService::class.java).flattenToString()
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
