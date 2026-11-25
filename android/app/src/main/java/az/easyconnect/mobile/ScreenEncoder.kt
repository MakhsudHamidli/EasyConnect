package az.easyconnect.mobile

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ekranı H.264-ə kodlayır. Sistem bank tətbiqləri kimi FLAG_SECURE pəncərələrini
 * VirtualDisplay-ə qara verir — bu Android səviyyəsindədir, tətbiq bunu keçə bilməz.
 *
 * Telefon döndükdə [resize] çağırılır: VirtualDisplay eyni qalır (MediaProjection yalnız bir
 * VirtualDisplay-ə icazə verir), yeni ölçüdə yeni kodlayıcı qurulur.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val dpi: Int,
    private val sink: (flags: Int, ptsUs: Long, data: ByteArray, length: Int) -> Unit,
    private val onMeta: (width: Int, height: Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var stopped = false

    /** Fasilədə kadrlar göndərilmir. */
    @Volatile var paused = false
        set(value) {
            val resumed = field && !value
            field = value
            if (resumed) requestKeyFrame()
        }

    var width = 0; private set
    var height = 0; private set

    fun start(screenWidth: Int, screenHeight: Int) = synchronized(lock) {
        val (w, h) = targetSize(screenWidth, screenHeight, MAX_SIZE)
        val (c, s) = createCodec(w, h)
        display = projection.createVirtualDisplay(
            "EasyConnect", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null,
        )
        begin(c, s, w, h)
    }

    /** Ekranın ölçüsü/istiqaməti dəyişdi. */
    fun resize(screenWidth: Int, screenHeight: Int) = synchronized(lock) {
        if (stopped) return@synchronized
        val (w, h) = targetSize(screenWidth, screenHeight, MAX_SIZE)
        if (w == width && h == height) return@synchronized
        endCurrent()
        val (c, s) = createCodec(w, h)
        display?.resize(w, h, dpi)
        display?.surface = s
        begin(c, s, w, h)
    }

    private fun createCodec(w: Int, h: Int): Pair<MediaCodec, Surface> {
        fun format(lowLatency: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, MAX_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Ekran dəyişməyəndə də kadr göndər (yeni qoşulan pəncərə boş qalmasın)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (lowLatency) {
                // 90/120 Hz ekranlarda encoder-ə saniyədə MAX_FPS-dən çox kadr verilməsin —
                // əks halda kompüterdə kadrlar növbəyə yığılır və gecikmə saniyələrə çatır.
                setFloat("max-fps-to-encoder", MAX_FPS.toFloat())
                setInteger(MediaFormat.KEY_PRIORITY, 0)   // real-time
                setInteger(MediaFormat.KEY_LATENCY, 1)    // kadrı gözlətmədən çıxar
                setInteger("max-bframes", 0)              // B-frame = əlavə gecikmə
            }
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format(lowLatency = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Bəzi encoder-lər bu açarları qəbul etmir — sadə ayarlarla yenidən
            c.reset()
            c.configure(format(lowLatency = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val s = c.createInputSurface()
        return c to s
    }

    private fun begin(c: MediaCodec, s: Surface, w: Int, h: Int) {
        codec = c
        surface = s
        width = w
        height = h
        onMeta(w, h)
        c.start()
        running = true
        thread = Thread({ drain(c) }, "ec-encoder").apply { start() }
    }

    private fun endCurrent() {
        running = false
        runCatching { thread?.join(1000) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        codec = null
        surface = null
        thread = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buf = ByteArray(256 * 1024)
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                try {
                    if (info.size > 0) {
                        val out = c.getOutputBuffer(index) ?: continue
                        if (buf.size < info.size) buf = ByteArray(info.size * 2)
                        out.position(info.offset)
                        out.get(buf, 0, info.size)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val flags = (if (isConfig) Protocol.FLAG_CONFIG else 0) or (if (isKey) Protocol.FLAG_KEY else 0)
                        // Konfiqurasiya (SPS/PPS) həmişə göndərilir — onda şəkil yoxdur.
                        if (isConfig || !paused) sink(flags, info.presentationTimeUs, buf, info.size)
                    }
                } finally {
                    c.releaseOutputBuffer(index, false)
                }
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        }
    }

    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun stop() = synchronized(lock) {
        stopped = true
        endCurrent()
        runCatching { display?.release() }
        display = null
    }

    companion object {
        const val MAX_SIZE = 1600
        const val BIT_RATE = 6_000_000
        const val MAX_FPS = 30

        /** Böyük tərəfi [maxSide]-a endirir, ölçüləri 16-ya bölünən edir (encoderlər üçün təhlükəsiz). */
        fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val scale = minOf(1.0, maxSide.toDouble() / max(width, height))
            fun align(v: Double) = max(16, ((v / 16).roundToInt()) * 16)
            return align(width * scale) to align(height * scale)
        }
    }
}
