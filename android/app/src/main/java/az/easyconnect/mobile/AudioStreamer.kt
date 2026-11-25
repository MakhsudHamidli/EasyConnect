package az.easyconnect.mobile

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Telefonun ÇALINAN səsini (musiqi, video, oyun) AAC-yə kodlayıb göndərir. Android 10+.
 *
 *  - Mikrofon YAZILMIR: AudioPlaybackCapture yalnız tətbiqlərin çaldığı səsi tutur.
 *  - Səsin yazılmasını qadağan edən tətbiqlərin (allowAudioPlaybackCapture=false, zənglər və s.)
 *    səsi sistem tərəfindən verilmir.
 *  - Fasilə / kilid / qorunan tətbiq zamanı səs göndərilmir.
 */
class AudioStreamer(
    private val projection: MediaProjection,
    private val sink: (flags: Int, ptsUs: Long, data: ByteArray, length: Int) -> Unit,
) {
    @Volatile var paused = false
    @Volatile private var running = false
    private var thread: Thread? = null

    /** RECORD_AUDIO icazəsi verilməyibsə false qaytarır. */
    @SuppressLint("MissingPermission") // icazə çağırandan əvvəl yoxlanılır (ScreenShareService)
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val pcm = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(pcm)
                .setBufferSizeInBytes(maxOf(minBuf * 2, FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        running = true
        thread = Thread({ loop(record, codec) }, "ec-audio").apply { start() }
        return true
    }

    private fun loop(record: AudioRecord, codec: MediaCodec) {
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var out = ByteArray(8 * 1024)
        var samples = 0L
        try {
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    buf.put(pcm, 0, minOf(n, buf.remaining()))
                    val pts = samples * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inIdx, 0, minOf(n, buf.capacity()), pts, 0)
                    samples += n / (2 * CHANNELS)
                }
                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        if (out.size < info.size) out = ByteArray(info.size * 2)
                        ob.position(info.offset)
                        ob.get(out, 0, info.size)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // Konfiqurasiya (AudioSpecificConfig) həmişə göndərilir; səs isə fasilədə yox
                        if (isConfig || !paused) {
                            sink(if (isConfig) Protocol.FLAG_CONFIG else 0, info.presentationTimeUs, out, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BIT_RATE = 128_000
        /** 1024 nümunə × 2 kanal × 2 bayt — bir AAC kadrı */
        const val FRAME_BYTES = 1024 * CHANNELS * 2
    }
}
