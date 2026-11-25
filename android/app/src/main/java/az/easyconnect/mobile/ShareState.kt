package az.easyconnect.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Paylaşım vəziyyəti — xidmət yazır, ekran oxuyur. */
sealed class ShareStatus {
    data object Idle : ShareStatus()
    data class Connecting(val desktop: String) : ShareStatus()
    data class Sharing(
        val desktop: String,
        val paused: Boolean,
        val pauseReason: String?,
        /** Sahib özü fasilə veribsə (kilid səbəbindən deyil) */
        val userPaused: Boolean,
    ) : ShareStatus()
    data class Ended(val message: String, val isError: Boolean) : ShareStatus()
}

object ShareState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ShareStatus) -> Unit>()

    @Volatile var current: ShareStatus = ShareStatus.Idle
        private set

    fun set(status: ShareStatus) {
        current = status
        main.post { listeners.forEach { it(status) } }
    }

    fun observe(listener: (ShareStatus) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (ShareStatus) -> Unit) {
        listeners -= listener
    }
}
