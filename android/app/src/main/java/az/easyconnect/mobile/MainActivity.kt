package az.easyconnect.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import az.easyconnect.mobile.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pendingLink: String? = null
    private var pendingControl = false
    private var pendingKeyboard = false
    private var pendingAudio = false
    private var pendingFiles = false

    private val statusListener: (ShareStatus) -> Unit = { render(it) }
    private val policyListener: () -> Unit = { runOnUiThread { renderLivePerms() } }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleLink(it) }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestProjection()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val link = pendingLink
        pendingLink = null
        hideConsent()
        if (res.resultCode != RESULT_OK || res.data == null || link == null) {
            Toast.makeText(this, R.string.share_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val intent = Intent(this, ScreenShareService::class.java)
            .setAction(ScreenShareService.ACTION_START)
            .putExtra(ScreenShareService.EXTRA_RESULT_CODE, res.resultCode)
            .putExtra(ScreenShareService.EXTRA_DATA, res.data)
            .putExtra(ScreenShareService.EXTRA_LINK, link)
            .putExtra(ScreenShareService.EXTRA_ALLOW_CONTROL, pendingControl)
            .putExtra(ScreenShareService.EXTRA_ALLOW_KEYBOARD, pendingKeyboard)
            .putExtra(ScreenShareService.EXTRA_ALLOW_AUDIO, pendingAudio)
            .putExtra(ScreenShareService.EXTRA_ALLOW_FILES, pendingFiles)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Başqa tətbiqlərin pəncərələri bu ekranı örtə bilməsin (tapjacking)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        ProtectedApps.init(this)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.filterTouchesWhenObscured = true

        b.scanBtn.setOnClickListener { startScan() }
        b.viewBtn.setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        b.protectedBtn.setOnClickListener { startActivity(Intent(this, ProtectedAppsActivity::class.java)) }
        b.allowBtn.setOnClickListener { onAllow() }
        b.denyBtn.setOnClickListener { pendingLink = null; hideConsent() }
        b.stopBtn.setOnClickListener { sendToService(ScreenShareService.ACTION_STOP) }
        b.pauseBtn.setOnClickListener { sendToService(ScreenShareService.ACTION_TOGGLE_PAUSE) }
        b.a11yBtn.setOnClickListener { openAccessibilitySettings() }
        setupLanguage()
        applyFlavor()

        val consentChanged = { _: CompoundButton, _: Boolean -> renderConsentA11y() }
        b.permControl.setOnCheckedChangeListener(consentChanged)
        b.permKeyboard.setOnCheckedChangeListener(consentChanged)

        // Paylaşım zamanı icazələr ayrıca ekranda dəyişdirilir (uzaqdan idarəyə bağlıdır)
        b.permsBtn.setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        b.sendFileBtn.setOnClickListener { startActivity(Intent(this, FilePickActivity::class.java)) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) b.permAudio.visibility = View.GONE

        handleIntent(intent)
    }

    /**
     * "lite" versiya (Play-dən kənar APK): Accessibility yoxdur, ona görə uzaqdan idarə və
     * qorunan tətbiqlərin tanınması da yoxdur. Bu seçimləri göstərmirik və mətni dəqiqləşdiririk.
     */
    private fun applyFlavor() {
        if (BuildConfig.REMOTE_CONTROL) return
        listOf(b.permControl, b.permKeyboard, b.consentA11y, b.a11yWarning, b.a11yBtn, b.protectedBtn)
            .forEach { it.visibility = View.GONE }
        b.liteNote.visibility = View.VISIBLE
        b.pointSecure.setText(R.string.consent_point_secure_lite)
    }

    /** Tətbiq daxilində dil seçimi (az/ru/en). Seçilməyibsə — telefonun dili. */
    private fun setupLanguage() {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            .ifBlank { resources.configuration.locales[0].language }
        listOf(b.langAz to "az", b.langRu to "ru", b.langEn to "en").forEach { (btn, tag) ->
            btn.alpha = if (tag == current) 1f else 0.5f
            btn.setTypeface(null, if (tag == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            btn.setOnClickListener {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ShareState.observe(statusListener)
        SessionPolicy.addListener(policyListener)
        renderLivePerms()
        renderConsentA11y()
    }

    override fun onStop() {
        ShareState.remove(statusListener)
        SessionPolicy.removeListener(policyListener)
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { handleLink(it) }
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** Link yalnız razılıq ekranını açır — heç nə avtomatik başlamır. */
    private fun handleLink(raw: String) {
        val current = ShareState.current
        if (current is ShareStatus.Sharing || current is ShareStatus.Connecting) {
            Toast.makeText(this, R.string.stop_current_first, Toast.LENGTH_LONG).show()
            return
        }
        val link = try {
            PairingLink.parse(raw)
        } catch (e: LinkError) {
            val msg = when (e.reason) {
                LinkError.Reason.NOT_EASYCONNECT -> R.string.link_not_easyconnect
                LinkError.Reason.VERSION -> R.string.link_version
                LinkError.Reason.NO_HOST -> R.string.link_no_host
                LinkError.Reason.NOT_LOCAL -> R.string.link_not_local
                LinkError.Reason.BAD_PORT -> R.string.link_bad_port
                LinkError.Reason.DAMAGED -> R.string.link_damaged
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        pendingLink = raw
        // Standart: yalnız baxış. İdarəni sahib özü seçməlidir.
        b.permControl.isChecked = false
        b.permKeyboard.isChecked = false
        b.permAudio.isChecked = false
        b.permFiles.isChecked = false
        b.consentPc.text = link.desktopName
        b.consentDetails.text = getString(R.string.consent_details, link.hosts.first(), link.shortCode)
        b.consentCard.visibility = View.VISIBLE
        b.homeButtons.visibility = View.GONE
        renderConsentA11y()
        b.root.post { b.root.smoothScrollTo(0, b.consentCard.top) }
    }

    private fun hideConsent() {
        b.consentCard.visibility = View.GONE
        render(ShareState.current)
    }

    private fun renderConsentA11y() {
        val wants = b.permControl.isChecked || b.permKeyboard.isChecked
        b.consentA11y.visibility = if (wants && !SessionPolicy.serviceReady) View.VISIBLE else View.GONE
    }

    private fun onAllow() {
        if (pendingLink == null) return
        pendingControl = b.permControl.isChecked
        pendingKeyboard = b.permKeyboard.isChecked
        pendingAudio = b.permAudio.isChecked && b.permAudio.visibility == View.VISIBLE
        pendingFiles = b.permFiles.isChecked
        if ((pendingControl || pendingKeyboard) && !SessionPolicy.serviceReady) {
            showAccessibilityDialog(continueAfter = true)
            return
        }
        continueAllow()
    }

    /** Səs seçilibsə, əvvəlcə Android-in "səs yazmaq" icazəsi (yalnız çalınan səs üçün istifadə olunur). */
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) pendingAudio = false
        continueAllow(audioAsked = true)
    }

    private fun continueAllow(audioAsked: Boolean = false) {
        if (pendingAudio && !audioAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun showAccessibilityDialog(continueAfter: Boolean = false) {
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.a11y_dialog_title)
            .setMessage(R.string.a11y_dialog_text)
            .setPositiveButton(R.string.a11y_open) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(if (continueAfter) R.string.a11y_view_only else R.string.cancel) { _, _ ->
                if (continueAfter) continueAllow()
            }
            .create()
        d.setOnShowListener { d.window?.decorView?.filterTouchesWhenObscured = true }
        d.show()
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, ControlService::class.java).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
        runCatching { startActivity(i) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    /** Android-in öz "ekranı paylaş" dialoqu — ikinci, sistem səviyyəli razılıq. */
    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun sendToService(action: String) {
        startService(Intent(this, ScreenShareService::class.java).setAction(action))
    }

    private fun renderLivePerms() {
        val sharing = ShareState.current is ShareStatus.Sharing
        b.livePerms.visibility = if (sharing) View.VISIBLE else View.GONE
        b.sendFileBtn.visibility = if (sharing && SessionPolicy.allowFiles) View.VISIBLE else View.GONE
        val needsService = BuildConfig.REMOTE_CONTROL && sharing && (SessionPolicy.allowControl || SessionPolicy.allowKeyboard) && !SessionPolicy.serviceReady
        b.a11yWarning.visibility = if (needsService) View.VISIBLE else View.GONE
        b.a11yBtn.visibility = if (needsService) View.VISIBLE else View.GONE
    }

    private fun render(s: ShareStatus) {
        val sharing = s is ShareStatus.Sharing
        b.activeButtons.visibility = if (sharing) View.VISIBLE else View.GONE
        val busy = sharing || s is ShareStatus.Connecting
        if (b.consentCard.visibility != View.VISIBLE) {
            b.homeButtons.visibility = if (busy) View.GONE else View.VISIBLE
        }
        val color = { id: Int -> ContextCompat.getColor(this, id) }
        when (s) {
            ShareStatus.Idle -> {
                b.statusTitle.text = getString(R.string.idle_title)
                b.statusTitle.setTextColor(color(R.color.text))
                b.statusText.text = getString(R.string.idle_text)
            }
            is ShareStatus.Connecting -> {
                b.statusTitle.text = getString(R.string.status_connecting)
                b.statusTitle.setTextColor(color(R.color.text))
                b.statusText.text = getString(R.string.status_connecting_text, s.desktop)
            }
            is ShareStatus.Sharing -> {
                b.statusTitle.text = getString(if (s.paused) R.string.status_paused else R.string.status_sharing)
                b.statusTitle.setTextColor(color(if (s.paused) R.color.warn else R.color.ok))
                b.statusText.text = s.pauseReason ?: getString(
                    if (SessionPolicy.canControl()) R.string.status_sharing_control_text else R.string.status_sharing_text,
                    s.desktop,
                )
                b.pauseBtn.text = getString(if (s.userPaused) R.string.resume_sharing else R.string.pause_sharing)
            }
            is ShareStatus.Ended -> {
                b.statusTitle.text = getString(if (s.isError) R.string.status_failed else R.string.status_ended)
                b.statusTitle.setTextColor(color(if (s.isError) R.color.err else R.color.text))
                b.statusText.text = s.message
            }
        }
        renderLivePerms()
    }
}
