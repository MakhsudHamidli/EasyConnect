package az.easyconnect.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fayl ötürmə (yalnız sahib "Fayllar" icazəsi veribsə).
 *
 * Təhlükəsizlik:
 *  - Qəbul edilən fayllar YALNIZ Downloads/EasyConnect qovluğuna yazılır; ad təmizlənir
 *    (yol hissələri, gizli fayl nöqtəsi, idarə simvolları silinir). Mövcud fayl üzərinə yazılmır.
 *  - Göndərmə yalnız sahibin (və ya icazəli uzaqdan idarənin) sistem fayl seçicisində
 *    seçdiyi fayllarla mümkündür — telefonun fayllarına gizli giriş YOXDUR.
 */
class FileTransfer(
    private val context: Context,
    private val writer: Protocol.Writer,
    private val onReceived: (name: String) -> Unit,
) {
    private class Incoming(val name: String, val size: Long, val out: OutputStream, val uri: Uri?, val file: File?) {
        var written = 0L
    }

    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val nextId = AtomicInteger(1)

    // ------------------------------------------------------------ qəbul

    fun onBegin(payload: ByteArray) {
        val j = runCatching { JSONObject(String(payload)) }.getOrNull() ?: return
        val id = j.optInt("id", -1)
        if (id < 0) return
        if (!SessionPolicy.allowFiles) {
            end(id, false, "denied")
            return
        }
        val size = j.optLong("size", -1)
        if (size < 0 || size > MAX_SIZE) {
            end(id, false, "too_large")
            return
        }
        val name = sanitize(j.optString("name"))
        try {
            incoming[id] = open(name, size)
        } catch (e: Exception) {
            end(id, false, "io")
        }
    }

    fun onData(payload: ByteArray) {
        if (payload.size < 4) return
        val id = ByteBuffer.wrap(payload, 0, 4).int
        val f = incoming[id] ?: return
        if (!SessionPolicy.allowFiles) { abort(id, "denied"); return }
        val len = payload.size - 4
        if (f.written + len > f.size) { abort(id, "size_mismatch"); return }
        try {
            f.out.write(payload, 4, len)
            f.written += len
        } catch (e: Exception) {
            abort(id, "io")
        }
    }

    fun onEnd(payload: ByteArray) {
        val j = runCatching { JSONObject(String(payload)) }.getOrNull() ?: return
        val id = j.optInt("id", -1)
        val f = incoming.remove(id) ?: return
        runCatching { f.out.close() }
        if (f.written != f.size || !j.optBoolean("ok", true)) {
            discard(f)
            return
        }
        finish(f)
        end(id, true, null)
        onReceived(f.name)
    }

    fun close() {
        incoming.keys.toList().forEach { abort(it, "closed", notify = false) }
    }

    private fun abort(id: Int, reason: String, notify: Boolean = true) {
        val f = incoming.remove(id) ?: return
        runCatching { f.out.close() }
        discard(f)
        if (notify) end(id, false, reason)
    }

    private fun end(id: Int, ok: Boolean, reason: String?) {
        val j = JSONObject().put("id", id).put("ok", ok)
        reason?.let { j.put("reason", it) }
        runCatching { writer.send(Protocol.FILE_END, j.toString().toByteArray()) }
    }

    private fun open(name: String, size: Long): Incoming {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EasyConnect")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            Incoming(name, size, context.contentResolver.openOutputStream(uri)!!, uri, null)
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "EasyConnect").apply { mkdirs() }
            var file = File(dir, name)
            var n = 1
            while (file.exists()) file = File(dir, "${name.substringBeforeLast('.')} ($n)${name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}").also { n++ }
            Incoming(file.name, size, file.outputStream(), null, file)
        }
    }

    private fun finish(f: Incoming) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && f.uri != null) {
            context.contentResolver.update(f.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
    }

    private fun discard(f: Incoming) {
        runCatching {
            if (f.uri != null) context.contentResolver.delete(f.uri, null, null)
            f.file?.delete()
        }
    }

    // ------------------------------------------------------------ göndərmə

    /** Sahibin seçdiyi faylları göndərir (ayrıca axında çağırın). */
    fun send(uris: List<Uri>) {
        for (uri in uris) {
            if (!SessionPolicy.allowFiles) return
            val id = nextId.getAndIncrement()
            val (name, size) = describe(uri)
            try {
                writer.send(Protocol.FILE_BEGIN, JSONObject().put("id", id).put("name", name).put("size", size).toString().toByteArray())
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val buf = ByteArray(Protocol.FILE_CHUNK)
                    val frame = ByteArray(4 + Protocol.FILE_CHUNK)
                    var sent = 0L
                    while (true) {
                        if (!SessionPolicy.allowFiles) throw IllegalStateException("denied")
                        val n = input.read(buf)
                        if (n < 0) break
                        ByteBuffer.wrap(frame).putInt(id)
                        System.arraycopy(buf, 0, frame, 4, n)
                        writer.send(Protocol.FILE_DATA, frame.copyOf(4 + n))
                        sent += n
                    }
                    writer.send(Protocol.FILE_END, JSONObject().put("id", id).put("ok", sent == size || size < 0).toString().toByteArray())
                }
            } catch (e: Exception) {
                runCatching { writer.send(Protocol.FILE_END, JSONObject().put("id", id).put("ok", false).toString().toByteArray()) }
                return
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = sanitize(it) }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    companion object {
        /** 4 GB */
        const val MAX_SIZE = 4L * 1024 * 1024 * 1024

        /** Yol hissələrini, idarə simvollarını və gizli fayl nöqtəsini silir. */
        fun sanitize(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val clean = base.filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trimStart('.')
            return clean.take(120).ifBlank { "file" }
        }
    }
}
