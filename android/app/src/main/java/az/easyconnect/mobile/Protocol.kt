package az.easyconnect.mobile

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Kompüterlə mesajlaşma: `[tip: u8][uzunluq: u32 BE][məzmun]`.
 * Masaüstü tərəf: `src-tauri/src/easy/proto.rs`.
 */
object Protocol {
    const val HELLO: Int = 0x01
    const val WELCOME: Int = 0x02
    const val VIDEO: Int = 0x11
    const val REQUEST_KEYFRAME: Int = 0x12
    const val STATE: Int = 0x20
    const val PERMS: Int = 0x21
    const val CONTROL: Int = 0x30
    const val VIDEO_META: Int = 0x10
    /** telefon → qarşı tərəf: [flags u8][pts u64][AAC] */
    const val AUDIO: Int = 0x13
    /** hər iki istiqamət: JSON {id, name, size} */
    const val FILE_BEGIN: Int = 0x40
    /** hər iki istiqamət: [id u32][bayt...] */
    const val FILE_DATA: Int = 0x41
    /** hər iki istiqamət: JSON {id, ok, reason?} */
    const val FILE_END: Int = 0x42
    /** PC → telefon: "faylı seç və göndər" (telefonda seçici açılır) */
    const val FILE_REQUEST: Int = 0x43
    const val PING: Int = 0x0E
    const val PONG: Int = 0x0F
    const val BYE: Int = 0x7F

    const val FLAG_CONFIG: Int = 0x01
    const val FLAG_KEY: Int = 0x02

    /** Kompüterdən gələ biləcək ən böyük mesaj (bizə yalnız kiçik JSON-lar gəlir). */
    const val MAX_CONTROL = 64 * 1024

    /** Fayl hissəsi: 256 KB məlumat + başlıq */
    const val FILE_CHUNK = 256 * 1024
    const val MAX_INCOMING_WITH_FILES = FILE_CHUNK + 64

    /** Görüntüləyici (video qəbul edən) tərəf üçün */
    const val MAX_VIDEO = 8 * 1024 * 1024

    class Writer(private val out: OutputStream) {
        private val lock = Any()

        fun send(type: Int, payload: ByteArray = ByteArray(0)) = synchronized(lock) {
            val header = ByteBuffer.allocate(5).put(type.toByte()).putInt(payload.size).array()
            out.write(header)
            out.write(payload)
            out.flush()
        }

        fun sendVideo(flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) =
            sendMedia(VIDEO, flags, ptsUs, data, offset, length)

        fun sendMedia(type: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
            val header = ByteBuffer.allocate(5 + 9)
                .put(type.toByte())
                .putInt(9 + length)
                .put(flags.toByte())
                .putLong(ptsUs)
                .array()
            out.write(header)
            out.write(data, offset, length)
            out.flush()
        }
    }

    data class Message(val type: Int, val payload: ByteArray)

    fun read(input: DataInputStream, maxLen: Int = MAX_CONTROL): Message {
        val type = input.read()
        if (type < 0) throw EOFException()
        val len = input.readInt()
        if (len < 0 || len > maxLen) throw IOException("mesaj çox böyükdür")
        val payload = ByteArray(len)
        input.readFully(payload)
        return Message(type, payload)
    }
}
