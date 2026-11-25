package az.easyconnect.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Telefon görüntüləyicisi: qarşı telefonun səsini (AAC) çalır. */
class AudioPlayer {
    private class Packet(val flags: Int, val pts: Long, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Packet>(64)
    @Volatile private var running = true
    @Volatile var muted = false
        set(value) { field = value; track?.setVolume(if (value) 0f else 1f) }
    @Volatile private var track: AudioTrack? = null
    private val thread = Thread({ loop() }, "ec-audio-play").apply { start() }

    fun onPacket(flags: Int, pts: Long, data: ByteArray) {
        if (!queue.offer(Packet(flags, pts, data))) {
            // Gecikmə yığılıb — köhnə səsi atırıq (konfiqurasiyanı saxlayırıq)
            val cfg = queue.firstOrNull { it.flags and Protocol.FLAG_CONFIG != 0 }
            queue.clear()
            cfg?.let { queue.offer(it) }
            queue.offer(Packet(flags, pts, data))
        }
    }

    private fun loop() {
        var codec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(16 * 1024)
        try {
            while (running) {
                val p = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (p.flags and Protocol.FLAG_CONFIG != 0) {
                    runCatching { codec?.stop(); codec?.release() }
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2)
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(p.data))
                        configure(f, null, null, 0)
                        start()
                    }
                    if (track == null) track = newTrack()
                    continue
                }
                val c = codec ?: continue
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val b = c.getInputBuffer(inIdx)!!
                    b.clear()
                    b.put(p.data, 0, minOf(p.data.size, b.remaining()))
                    c.queueInputBuffer(inIdx, 0, minOf(p.data.size, b.capacity()), p.pts, 0)
                }
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(pcm, 0, info.size)
                        track?.write(pcm, 0, info.size)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { track?.stop(); track?.release() }
            track = null
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply {
                setVolume(if (muted) 0f else 1f)
                play()
            }
    }

    fun release() {
        running = false
        queue.clear()
        runCatching { thread.join(300) }
    }

    companion object {
        const val RATE = 48_000
    }
}
