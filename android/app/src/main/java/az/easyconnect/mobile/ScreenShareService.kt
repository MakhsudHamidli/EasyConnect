package az.easyconnect.mobile

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Ekranı kompüterə / digər telefona göndərən fon xidməti.
 *
 * Təhlükəsizlik (ətraflı: docs/SECURITY.md):
 *  1. Yalnız sahib razılıq ekranında icazələri seçib "İcazə ver" basandan VƏ Android-in öz
 *     "ekranı paylaş" dialoqunu təsdiqləyəndən sonra başlayır.
 *  2. Bildiriş həmişə görünür; oradan fasilə vermək / dayandırmaq olur.
 *  3. Ekran kilidlidirsə və ya qorunan tətbiq öndədirsə — kadr göndərilmir.
 *  4. Uzaqdan gələn hər idarə əmri [SessionPolicy] tərəfindən yoxlanılır.
 */
class ScreenShareService : Service() {

    private val io = Executors.newSingleThreadExecutor()
    private val resizer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var socket: SSLSocket? = null
    private var writer: Protocol.Writer? = null
    private var desktopName: String = ""
    private var triedHosts: String = ""
    private var lastLandscape: Boolean? = null
    private var lastHiddenNotified: String? = null
    private var audio: AudioStreamer? = null
    private var files: FileTransfer? = null

    @Volatile private var active = false

    private val policyListener: () -> Unit = { onPolicyChanged() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> SessionPolicy.setLockPaused(true)
                Intent.ACTION_USER_PRESENT -> SessionPolicy.setLockPaused(false)
                Intent.ACTION_SCREEN_ON -> SessionPolicy.setLockPaused(isLocked())
            }
        }
    }
    private var receiverRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = screenSize()
            val landscape = w > h
            if (landscape == lastLandscape) return
            lastLandscape = landscape
            val enc = encoder ?: return
            // Telefon döndü — kodlayıcını yeni ölçüdə qururuq
            resizer.execute { runCatching { enc.resize(w, h) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSession("phone_closed", null)
            ACTION_TOGGLE_PAUSE -> SessionPolicy.setUserPaused(!SessionPolicy.userPaused)
            ACTION_SHOW_PROTECTED -> SessionPolicy.showHiddenOnce()
            ACTION_KEEP_HIDDEN -> cancelProtectedNotification()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (active) return
        val link = runCatching { PairingLink.parse(intent.getStringExtra(EXTRA_LINK) ?: "") }.getOrNull()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (link == null || data == null) {
            stopSelf()
            return
        }
        active = true
        desktopName = link.desktopName
        ProtectedApps.init(this)

        // Android 14+: startForeground getMediaProjection-dan ƏVVƏL çağırılmalıdır.
        startInForeground()

        val proj = runCatching {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        }.getOrNull()
        if (proj == null) {
            finish(ShareStatus.Ended(getString(R.string.share_no_permission), true))
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSession("phone_closed", null)
        }, main)

        SessionPolicy.start(
            control = intent.getBooleanExtra(EXTRA_ALLOW_CONTROL, false),
            keyboard = intent.getBooleanExtra(EXTRA_ALLOW_KEYBOARD, false),
            audio = intent.getBooleanExtra(EXTRA_ALLOW_AUDIO, false),
            files = intent.getBooleanExtra(EXTRA_ALLOW_FILES, false),
        )
        current = this
        SessionPolicy.setLockPaused(isLocked())
        SessionPolicy.addListener(policyListener)
        registerScreenReceiver()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)

        ShareState.set(ShareStatus.Connecting(link.desktopName))
        Thread({ run(link, proj) }, "ec-session").start()
    }

    private fun run(link: PairingLink, proj: MediaProjection) {
        try {
            triedHosts = link.hosts.joinToString(", ")
            val s = PinnedTls.connect(link)
            socket = s
            val w = Protocol.Writer(BufferedOutputStream(s.outputStream, 64 * 1024))
            writer = w
            val input = DataInputStream(BufferedInputStream(s.inputStream))

            w.send(Protocol.HELLO, helloJson(link.token).toByteArray())
            val welcome = Protocol.read(input)
            if (welcome.type != Protocol.WELCOME) throw IllegalStateException("protocol")
            val wj = JSONObject(String(welcome.payload))
            if (!wj.optBoolean("ok")) {
                stopSession(null, null, ShareStatus.Ended(getString(R.string.share_qr_expired), true))
                return
            }
            desktopName = wj.optString("desktop").take(64).ifBlank { link.desktopName }

            val (sw, sh) = screenSize()
            lastLandscape = sw > sh
            val enc = ScreenEncoder(
                projection = proj,
                dpi = resources.displayMetrics.densityDpi,
                sink = { flags, pts, data, len ->
                    try {
                        w.sendVideo(flags, pts, data, 0, len)
                    } catch (e: Exception) {
                        stopSession(null, getString(R.string.share_lost))
                    }
                },
                onMeta = { vw, vh ->
                    runCatching { w.send(Protocol.VIDEO_META, JSONObject().put("w", vw).put("h", vh).toString().toByteArray()) }
                },
                onError = { stopSession(null, getString(R.string.share_encoder_error, it.message ?: "")) },
            )
            encoder = enc
            enc.paused = SessionPolicy.videoPaused()
            enc.start(sw, sh)
            files = FileTransfer(this, w) { name -> main.post { notifyFileReceived(name) } }
            updateAudio()
            sendStateAndPerms()
            publishSharing()
            updateNotification()
            startPinger(w)

            s.soTimeout = 30_000
            while (active) {
                val msg = Protocol.read(input, Protocol.MAX_INCOMING_WITH_FILES)
                when (msg.type) {
                    Protocol.REQUEST_KEYFRAME -> enc.requestKeyFrame()
                    Protocol.CONTROL -> handleControl(msg.payload)
                    Protocol.FILE_BEGIN -> files?.onBegin(msg.payload)
                    Protocol.FILE_DATA -> files?.onData(msg.payload)
                    Protocol.FILE_END -> files?.onEnd(msg.payload)
                    Protocol.FILE_REQUEST -> if (SessionPolicy.allowFiles) main.post { openFilePicker(fromRemote = true) }
                    Protocol.PONG -> Unit
                    Protocol.BYE -> {
                        stopSession(null, null, ShareStatus.Ended(getString(R.string.share_remote_closed), false))
                        return
                    }
                    else -> Unit // naməlum mesajlar nəzərə alınmır
                }
            }
        } catch (e: Exception) {
            if (active) stopSession(null, explain(e))
        }
    }

    /** Uzaqdan gələn idarə əmri — yalnız SessionPolicy icazə verirsə icra olunur. */
    private fun handleControl(payload: ByteArray) {
        val cmd = runCatching { JSONObject(String(payload)) }.getOrNull() ?: return
        val svc = ControlService.instance ?: return
        main.post { runCatching { svc.perform(cmd) } }
    }

    private fun onPolicyChanged() {
        val enc = encoder
        enc?.paused = SessionPolicy.videoPaused()
        audio?.paused = SessionPolicy.videoPaused()
        if (!active) return
        io.execute {
            updateAudio()
            sendStateAndPerms()
        }
        publishSharing()
        main.post {
            updateNotification()
            updateProtectedNotification()
        }
    }

    private fun sendStateAndPerms() {
        val state = JSONObject().put("paused", SessionPolicy.videoPaused())
        SessionPolicy.pauseReason()?.let { state.put("reason", it) }
        runCatching { writer?.send(Protocol.STATE, state.toString().toByteArray()) }
        runCatching { writer?.send(Protocol.PERMS, SessionPolicy.permsJson().toString().toByteArray()) }
    }

    /** Səs icazəsi verilib/götürülüb — səs ötürülməsini başlat/dayandır. */
    @Synchronized
    private fun updateAudio() {
        val proj = projection ?: return
        val w = writer ?: return
        val want = active && SessionPolicy.allowAudio && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (want && audio == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val a = AudioStreamer(proj) { flags, pts, data, len ->
                runCatching { w.sendMedia(Protocol.AUDIO, flags, pts, data, 0, len) }
            }
            a.paused = SessionPolicy.videoPaused()
            if (a.start()) audio = a
        } else if (!want && audio != null) {
            audio?.stop()
            audio = null
        }
    }

    /** Sahibin seçdiyi faylları göndərir (ayrı axında). */
    fun sendFiles(uris: List<android.net.Uri>) {
        val f = files ?: return
        Thread({ f.send(uris) }, "ec-files-out").start()
    }

    /**
     * Fayl seçicisini açır. Uzaqdan istək gələndə birbaşa açmaq Android tərəfindən
     * bloklana bilər (fon məhdudiyyəti) — ona görə bildiriş də göstəririk.
     */
    private fun openFilePicker(fromRemote: Boolean) {
        val intent = Intent(this, FilePickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
        if (fromRemote) {
            val pi = PendingIntent.getActivity(this, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CHANNEL_PROTECTED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.files_request_title, desktopName))
                .setContentText(getString(R.string.files_request_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_FILES_ID, n)
        }
    }

    private fun notifyFileReceived(name: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.files_received_title))
            .setContentText(getString(R.string.files_received_text, name))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_FILES_ID + 1, n)
    }

    private fun startPinger(w: Protocol.Writer) {
        Thread({
            while (active) {
                try {
                    Thread.sleep(5_000)
                    if (active) w.send(Protocol.PING)
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }, "ec-ping").apply { isDaemon = true; start() }
    }

    private fun publishSharing() {
        val reason = when (SessionPolicy.pauseReason()) {
            "locked" -> getString(R.string.pause_locked)
            "protected" -> getString(R.string.pause_protected)
            "user" -> getString(R.string.pause_user)
            else -> null
        }
        ShareState.set(ShareStatus.Sharing(desktopName, SessionPolicy.videoPaused(), reason, SessionPolicy.userPaused))
    }

    private fun stopSession(byeReason: String?, error: String?, finalStatus: ShareStatus? = null) {
        if (!active) return
        active = false
        val w = writer
        val s = socket
        val enc = encoder
        val proj = projection
        io.execute {
            if (byeReason != null && w != null) {
                runCatching { w.send(Protocol.BYE, JSONObject().put("reason", byeReason).toString().toByteArray()) }
            }
            runCatching { enc?.stop() }
            runCatching { audio?.stop() }
            runCatching { files?.close() }
            runCatching { s?.close() }
            main.post { runCatching { proj?.stop() } }
        }
        val status = finalStatus
            ?: if (error != null) ShareStatus.Ended(error, true) else ShareStatus.Ended(getString(R.string.share_stopped), false)
        finish(status)
    }

    private fun finish(status: ShareStatus) {
        active = false
        SessionPolicy.removeListener(policyListener)
        SessionPolicy.end()
        ShareState.set(status)
        main.post {
            unregisterScreenReceiver()
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
            cancelProtectedNotification()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (active) stopSession("phone_closed", null)
        unregisterScreenReceiver()
        io.shutdown()
        resizer.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun isLocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun helloJson(token: String): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return JSONObject()
            .put("v", 1)
            .put("token", token)
            .put("device", JSONObject()
                .put("name", name)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os", "Android ${Build.VERSION.RELEASE}"))
            .put("capabilities", JSONObject().put("control", true))
            .toString()
    }

    /** Hazırkı istiqamətdə ekranın real ölçüsü. */
    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun explain(e: Exception): String {
        val root = generateSequence<Throwable>(e) { it.cause }.toList()
        // Bəzi telefonlarda (HONOR) logcat söndürülür — texniki səbəbi tətbiqin öz qovluğunda saxlayırıq.
        val technical = root.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
        runCatching {
            java.io.File(filesDir, "last_error.txt").writeText("${java.util.Date()}\n$technical\n" + e.stackTraceToString().take(4000))
        }
        val friendly = when {
            root.any { it is CertificateException } ->
                getString(R.string.err_identity)
            e is SSLHandshakeException -> getString(R.string.err_tls)
            e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException ->
                getString(R.string.err_unreachable, triedHosts)
            e is EOFException -> getString(R.string.share_lost)
            else -> getString(R.string.err_generic, e.message ?: e.javaClass.simpleName)
        }
        return friendly + "\n\n($technical)".take(300)
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        receiverRegistered = false
    }

    // ----------------------------------------------------------- notifications

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROTECTED, getString(R.string.notif_channel_protected), NotificationManager.IMPORTANCE_HIGH))
    }

    // foregroundServiceType="mediaProjection" main/AndroidManifest.xml-də elan olunub. Flavor
    // manifesti (src/full) olanda lint onu görmür — yanlış xəbərdarlıqdır.
    @android.annotation.SuppressLint("ForegroundServiceType")
    private fun startInForeground() {
        channels()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!active) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ScreenShareService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = desktopName.ifBlank { getString(R.string.remote_party) }
        val text = when {
            SessionPolicy.videoPaused() -> getString(R.string.notif_paused, name)
            SessionPolicy.canControl() -> getString(R.string.notif_text_control, name)
            else -> getString(R.string.notif_text, name)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, getString(if (SessionPolicy.userPaused) R.string.resume_sharing else R.string.pause_sharing), servicePending(ACTION_TOGGLE_PAUSE, 2))
            .addAction(0, getString(R.string.stop_sharing), servicePending(ACTION_STOP, 1))
            .build()
    }

    /**
     * Qorunan tətbiq öndədirsə — sahibdən soruşuruq. Bu anda uzaqdan idarə bloklu olduğu
     * üçün bildirişdəki düymələri yalnız telefonun sahibi basa bilər.
     */
    private fun updateProtectedNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val hidden = SessionPolicy.hiddenPkg
        if (hidden == null) {
            if (lastHiddenNotified != null) nm.cancel(NOTIF_PROTECTED_ID)
            lastHiddenNotified = null
            return
        }
        if (hidden == lastHiddenNotified) return
        lastHiddenNotified = hidden
        val label = appLabel(hidden)
        val remote = SessionPolicy.remoteOpenBlocked == hidden
        SessionPolicy.clearRemoteOpenBlocked()
        val title = if (remote) getString(R.string.protected_remote_title, label) else getString(R.string.protected_title, label)
        val text = if (remote) getString(R.string.protected_remote_text, desktopName) else getString(R.string.protected_text, desktopName)
        val b = NotificationCompat.Builder(this, CHANNEL_PROTECTED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.protected_keep), servicePending(ACTION_KEEP_HIDDEN, 4))
        if (!remote) b.addAction(0, getString(R.string.protected_show), servicePending(ACTION_SHOW_PROTECTED, 3))
        nm.notify(NOTIF_PROTECTED_ID, b.build())
    }

    private fun cancelProtectedNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_PROTECTED_ID)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val CHANNEL_ID = "sharing"
        private const val CHANNEL_PROTECTED = "protected"
        private const val NOTIF_ID = 1001
        private const val NOTIF_PROTECTED_ID = 1002
        private const val NOTIF_FILES_ID = 1003

        /** Aktiv paylaşım xidməti (FilePickActivity fayl göndərmək üçün istifadə edir). */
        @Volatile var current: ScreenShareService? = null
            private set

        const val ACTION_START = "az.easyconnect.mobile.START"
        const val ACTION_STOP = "az.easyconnect.mobile.STOP"
        const val ACTION_TOGGLE_PAUSE = "az.easyconnect.mobile.TOGGLE_PAUSE"
        const val ACTION_SHOW_PROTECTED = "az.easyconnect.mobile.SHOW_PROTECTED"
        const val ACTION_KEEP_HIDDEN = "az.easyconnect.mobile.KEEP_HIDDEN"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_LINK = "link"
        const val EXTRA_ALLOW_CONTROL = "allowControl"
        const val EXTRA_ALLOW_KEYBOARD = "allowKeyboard"
        const val EXTRA_ALLOW_AUDIO = "allowAudio"
        const val EXTRA_ALLOW_FILES = "allowFiles"
        const val ACTION_PICK_FILES = "az.easyconnect.mobile.PICK_FILES"
    }
}
