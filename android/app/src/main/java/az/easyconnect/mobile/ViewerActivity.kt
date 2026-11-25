package az.easyconnect.mobile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import az.easyconnect.mobile.databinding.ActivityViewerBinding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Başqa telefonun ekranına baxmaq (və o telefonun sahibi icazə veribsə, idarə etmək).
 * Bu telefon QR göstərir; paylaşan telefon onu EasyConnect tətbiqi ilə skan edir.
 */
class ViewerActivity : AppCompatActivity(), PhoneServer.Listener {

    private lateinit var b: ActivityViewerBinding
    private var server: PhoneServer? = null

    // dekoder
    private val packets = LinkedBlockingQueue<Packet>(120)
    @Volatile private var surfaceReady = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var decoding = false
    @Volatile private var lastConfig: ByteArray? = null
    private var decoderThread: Thread? = null

    // icazələr (göstərmək üçün; yoxlama paylaşan telefondadır)
    @Volatile private var perms = JSONObject()
    @Volatile private var paused = false

    private data class Packet(val flags: Int, val pts: Long, val data: ByteArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.newQrBtn.setOnClickListener { showQr() }
        b.keyBack.setOnClickListener { key("back") }
        b.keyHome.setOnClickListener { key("home") }
        b.keyRecents.setOnClickListener { key("recents") }
        b.keyClose.setOnClickListener { finish() }
        b.keyMore.setOnClickListener {
            b.extraRow.visibility = if (b.extraRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.keyMute.setOnClickListener {
            audio.muted = !audio.muted
            b.keyMute.alpha = if (audio.muted) 0.5f else 1f
        }
        b.keyVolDown.setOnClickListener { sendNav(JSONObject().put("t", "vol").put("d", "down")) }
        b.keyVolUp.setOnClickListener { sendNav(JSONObject().put("t", "vol").put("d", "up")) }
        b.keyRinger.setOnClickListener { sendNav(JSONObject().put("t", "ringer")) }
        b.keyQuick.setOnClickListener { sendNav(JSONObject().put("t", "key").put("k", "quicksettings")) }
        b.keyKeyboard.setOnClickListener {
            b.textRow.visibility = if (b.textRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        b.textSend.setOnClickListener {
            val s = b.textInput.text.toString()
            if (s.isNotEmpty()) {
                server?.sendControl(JSONObject().put("t", "text").put("s", s))
                b.textInput.setText("")
            }
        }
        b.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; restartDecoder() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopDecoder() }
        })
        setupTouch()

        try {
            server = PhoneServer(this).also { it.start() }
            showQr()
        } catch (e: Exception) {
            b.qrStatus.text = getString(R.string.viewer_server_failed, e.message ?: "")
        }
    }

    private val audio = AudioPlayer()

    private fun sendNav(cmd: JSONObject) {
        if (canNavigate()) server?.sendControl(cmd)
    }

    override fun onAudio(flags: Int, ptsUs: Long, data: ByteArray) = audio.onPacket(flags, ptsUs, data)

    override fun onDestroy() {
        audio.release()
        server?.stop()
        stopDecoder()
        super.onDestroy()
    }

    private fun showQr() {
        val s = server ?: return
        val p = s.newPairing(android.os.Build.MODEL ?: "Telefon")
        if (p.hosts.isEmpty()) {
            b.qrImage.setImageDrawable(null)
            b.qrStatus.text = getString(R.string.viewer_no_network)
            return
        }
        val bmp: Bitmap = BarcodeEncoder().encodeBitmap(p.link, BarcodeFormat.QR_CODE, 720, 720)
        b.qrImage.setImageBitmap(bmp)
        b.qrStatus.text = getString(R.string.viewer_qr_status, p.shortCode, p.hosts.first())
    }

    // ------------------------------------------------------------- server events

    override fun onConnected(phoneName: String) = runOnUiThread {
        b.pairingPanel.visibility = View.GONE
        b.videoPanel.visibility = View.VISIBLE
        b.videoOverlay.visibility = View.VISIBLE
        b.videoOverlay.text = getString(R.string.viewer_waiting, phoneName)
        title = phoneName
        renderPerms()
    }

    override fun onMeta(width: Int, height: Int) {
        if (width == videoW && height == videoH) return
        videoW = width
        videoH = height
        runOnUiThread { fitSurface() }
        restartDecoder()
    }

    override fun onVideo(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) {
        val copy = data.copyOfRange(offset, offset + length)
        if (flags and Protocol.FLAG_CONFIG != 0) lastConfig = copy
        if (!packets.offer(Packet(flags, ptsUs, copy))) {
            // Gecikmə yığılır — köhnə kadrları atıb açar kadr istəyirik
            packets.clear()
            server?.requestKeyFrame()
        }
    }

    override fun onState(json: JSONObject) = runOnUiThread {
        paused = json.optBoolean("paused")
        if (paused) {
            b.videoOverlay.visibility = View.VISIBLE
            b.videoOverlay.text = when (json.optString("reason")) {
                "locked" -> getString(R.string.viewer_locked)
                "protected" -> getString(R.string.viewer_protected)
                else -> getString(R.string.viewer_paused)
            }
        } else {
            b.videoOverlay.visibility = View.GONE
        }
        renderPerms()
    }

    override fun onPerms(json: JSONObject) = runOnUiThread {
        perms = json
        renderPerms()
    }

    override fun onEnded(reason: String) = runOnUiThread {
        stopDecoder()
        b.videoOverlay.visibility = View.VISIBLE
        b.videoOverlay.text = getString(R.string.viewer_ended)
        perms = JSONObject()
        renderPerms()
    }

    private fun canControl() = perms.optBoolean("control") && perms.optBoolean("controlReady") &&
        perms.isNull("blocked") && !paused

    /** Geri/Ana ekran/Son tətbiqlər — qorunan ekranlarda da icazəlidir (telefon özü qərar verir). */
    private fun canNavigate() = perms.optBoolean("navigate")

    private fun canType() = perms.optBoolean("keyboard") && perms.optBoolean("controlReady") &&
        perms.isNull("blocked") && !paused

    private fun renderPerms() {
        val text = when {
            !perms.isNull("blocked") && perms.has("blocked") -> when (perms.optString("blocked")) {
                "protected" -> getString(R.string.perm_blocked_protected)
                "system" -> getString(R.string.perm_blocked_system)
                "locked" -> getString(R.string.perm_blocked_locked)
                else -> getString(R.string.perm_blocked_other)
            }
            perms.optBoolean("control") && !perms.optBoolean("controlReady") -> getString(R.string.perm_not_ready)
            canControl() && canType() -> getString(R.string.perm_control_keyboard)
            canControl() -> getString(R.string.perm_control_only)
            canType() -> getString(R.string.perm_keyboard_only)
            else -> getString(R.string.perm_view_only)
        }
        b.permStatus.text = text
        val c = canControl()
        val nav = canNavigate()
        listOf(b.keyBack, b.keyHome, b.keyRecents).forEach { it.isEnabled = nav; it.alpha = if (nav) 1f else 0.35f }
        b.keyKeyboard.isEnabled = canType()
        b.keyKeyboard.alpha = if (canType()) 1f else 0.35f
        listOf(b.keyVolDown, b.keyVolUp, b.keyRinger, b.keyQuick).forEach { it.isEnabled = nav; it.alpha = if (nav) 1f else 0.35f }
        if (!canType()) b.textRow.visibility = View.GONE
    }

    // ------------------------------------------------------------------- video

    /** SurfaceView-i videonun nisbətinə uyğun mərkəzdə yerləşdirir. */
    private fun fitSurface() {
        val cw = b.videoContainer.width
        val ch = b.videoContainer.height
        if (cw == 0 || ch == 0 || videoW == 0 || videoH == 0) {
            b.videoContainer.post { if (b.videoContainer.width > 0) fitSurface() }
            return
        }
        val scale = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
        val lp = b.surface.layoutParams as FrameLayout.LayoutParams
        lp.width = (videoW * scale).toInt()
        lp.height = (videoH * scale).toInt()
        b.surface.layoutParams = lp
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        b.videoContainer.post { fitSurface() }
    }

    @Synchronized
    private fun restartDecoder() {
        stopDecoder()
        if (!surfaceReady || videoW == 0) return
        decoding = true
        decoderThread = Thread({ decodeLoop(videoW, videoH) }, "ec-decoder").apply { start() }
        server?.requestKeyFrame()
    }

    @Synchronized
    private fun stopDecoder() {
        decoding = false
        runCatching { decoderThread?.join(500) }
        decoderThread = null
    }

    private fun decodeLoop(w: Int, h: Int) {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                configure(f, b.surface.holder.surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            return
        }
        val info = MediaCodec.BufferInfo()
        var needKey = true
        var shownFirst = false
        fun queue(data: ByteArray, pts: Long, flags: Int): Boolean {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) return false
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(data.size, buf.remaining())
            buf.put(data, 0, n)
            codec.queueInputBuffer(idx, 0, n, pts, flags)
            return true
        }
        try {
            // Yeni dekoder: son məlum SPS/PPS-i əvvəlcə veririk
            lastConfig?.let { queue(it, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
            while (decoding) {
                val p = packets.poll(20, TimeUnit.MILLISECONDS)
                if (p != null) {
                    val isConfig = p.flags and Protocol.FLAG_CONFIG != 0
                    val isKey = p.flags and Protocol.FLAG_KEY != 0
                    if (isConfig) {
                        queue(p.data, p.pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        needKey = true
                    } else if (!needKey || isKey) {
                        if (queue(p.data, p.pts, 0) && isKey) needKey = false
                    }
                }
                var out = codec.dequeueOutputBuffer(info, 0)
                while (out >= 0) {
                    codec.releaseOutputBuffer(out, true)
                    if (!shownFirst) {
                        shownFirst = true
                        runOnUiThread { if (!paused) b.videoOverlay.visibility = View.GONE }
                    }
                    out = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ------------------------------------------------------------------ control

    private fun key(k: String) {
        if (canNavigate()) server?.sendControl(JSONObject().put("t", "key").put("k", k))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downAt = 0L
        val pts = mutableListOf<Pair<Double, Double>>()
        b.surface.setOnTouchListener { v, e ->
            if (!canControl()) return@setOnTouchListener true
            val x = (e.x / v.width).toDouble().coerceIn(0.0, 1.0)
            val y = (e.y / v.height).toDouble().coerceIn(0.0, 1.0)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); pts.clear(); pts += x to y }
                MotionEvent.ACTION_MOVE -> if (pts.isEmpty() || hypot(x - pts.last().first, y - pts.last().second) > 0.005) pts += x to y
                MotionEvent.ACTION_UP -> {
                    pts += x to y
                    val ms = SystemClock.uptimeMillis() - downAt
                    val (x0, y0) = pts.first()
                    val (x1, y1) = pts.last()
                    val cmd = if (hypot(x1 - x0, y1 - y0) < 0.015) {
                        JSONObject().put("t", if (ms > 450) "long" else "tap").put("x", x0).put("y", y0)
                    } else {
                        val step = maxOf(1, pts.size / 24)
                        val sampled = pts.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
                        if (sampled.last() != pts.last()) sampled += pts.last()
                        val arr = JSONArray()
                        sampled.forEach { (px, py) -> arr.put(JSONArray().put(px).put(py)) }
                        JSONObject().put("t", "swipe").put("pts", arr).put("ms", ms.coerceIn(80, 3000))
                    }
                    server?.sendControl(cmd)
                    pts.clear()
                }
            }
            true
        }
    }
}
