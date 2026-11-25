package az.easyconnect.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Uzaqdan idarəni icra edən Accessibility xidməti.
 *
 * Sahib bu xidməti Ayarlarda özü yandırmalıdır. Xidmət yandırılsa belə, heç bir əmr
 * [SessionPolicy] icazə vermədən icra olunmur: aktiv paylaşım, sahibin seçdiyi icazə,
 * kilidsiz ekran, qorunan/sistem tətbiqi öndə olmamalıdır.
 *
 * Xidmət ekran məzmununu OXUMUR və heç yerə göndərmir. Yalnız ön plandakı tətbiqin paket
 * adına baxır (qorunan tətbiqləri tanımaq üçün) və fokusdakı mətn sahəsinə yazır.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        ProtectedApps.init(this)
        SessionPolicy.notifyServiceChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            ?: event.packageName?.toString()
        // Activity sinfi yalnız TYPE_WINDOW_STATE_CHANGED hadisəsində gəlir
        val activity = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.className?.toString() else null
        SessionPolicy.onForeground(this, pkg, activity)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        SessionPolicy.notifyServiceChanged()
        super.onDestroy()
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Əmri icra edir. Çağıran tərəf əvvəlcə [SessionPolicy.canControl]/[SessionPolicy.canType]
     * yoxlamalıdır; burada da təkrar yoxlanılır (müdafiə qatı).
     */
    fun perform(cmd: JSONObject): Boolean {
        val t = cmd.optString("t")
        val k = cmd.optString("k")
        val isTyping = t == "text" || (t == "key" && k in setOf("backspace", "enter"))
        // Naviqasiya + səs/zəng rejimi: heç bir icazəni dəyişə bilməz
        val isNavigation = (t == "key" && k in setOf("back", "home", "recents", "quicksettings")) ||
            t == "vol" || t == "ringer"
        when {
            isTyping -> if (!SessionPolicy.canType()) return false
            isNavigation -> if (!SessionPolicy.canNavigate()) return false
            else -> if (!SessionPolicy.canControl()) return false
        }
        SessionPolicy.markRemoteInput()

        val (w, h) = realSize()
        fun px(x: Double) = (x.coerceIn(0.0, 1.0) * (w - 1)).toFloat()
        fun py(y: Double) = (y.coerceIn(0.0, 1.0) * (h - 1)).toFloat()
        fun coord(o: JSONObject, k: String): Double? = o.optDouble(k).takeIf { !it.isNaN() }

        return when (t) {
            "tap", "long" -> {
                val x = coord(cmd, "x") ?: return false
                val y = coord(cmd, "y") ?: return false
                gesture(listOf(px(x) to py(y)), if (t == "tap") 60 else 650)
            }
            "swipe" -> {
                val arr = cmd.optJSONArray("pts") ?: return false
                if (arr.length() < 2 || arr.length() > 64) return false
                val pts = (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONArray(i) ?: return@mapNotNull null
                    val x = p.optDouble(0).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    val y = p.optDouble(1).takeIf { !it.isNaN() } ?: return@mapNotNull null
                    px(x) to py(y)
                }
                if (pts.size < 2) return false
                gesture(pts, cmd.optLong("ms", 300).coerceIn(50, 5000))
            }
            "scroll" -> {
                val x = coord(cmd, "x") ?: return false
                val y = coord(cmd, "y") ?: return false
                val dy = if (cmd.optInt("dy") > 0) 1 else -1
                // Təkər aşağı → məzmun aşağı sürüşür → barmaq yuxarı hərəkət edir
                val endY = (y - dy * 0.25).coerceIn(0.05, 0.95)
                gesture(listOf(px(x) to py(y), px(x) to py(endY)), 220)
            }
            "key" -> when (cmd.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quicksettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "backspace" -> editFocused { it.dropLast(1) }
                "enter" -> pressEnter()
                else -> false
            }
            "vol" -> {
                val am = getSystemService(android.media.AudioManager::class.java)
                val dir = if (cmd.optString("d") == "up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
                am.adjustSuggestedStreamVolume(dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI)
                true
            }
            "ringer" -> cycleRinger()
            "text" -> {
                val s = cmd.optString("s").filter { !it.isISOControl() }.take(1000)
                if (s.isEmpty()) false else editFocused { it + s }
            }
            else -> false
        }
    }

    /**
     * Zəng rejimi: səsli → vibrasiya → səssiz → səsli.
     * "Səssiz" üçün Android "Narahat etməyin" icazəsi tələb edir; yoxdursa vibrasiyada qalır.
     */
    private fun cycleRinger(): Boolean {
        val am = getSystemService(android.media.AudioManager::class.java)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val next = when (am.ringerMode) {
            android.media.AudioManager.RINGER_MODE_NORMAL -> android.media.AudioManager.RINGER_MODE_VIBRATE
            android.media.AudioManager.RINGER_MODE_VIBRATE ->
                if (nm.isNotificationPolicyAccessGranted) android.media.AudioManager.RINGER_MODE_SILENT
                else android.media.AudioManager.RINGER_MODE_NORMAL
            else -> android.media.AudioManager.RINGER_MODE_NORMAL
        }
        return runCatching { am.ringerMode = next; true }.getOrDefault(false)
    }

    private fun gesture(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            if (points.size == 1) lineTo(points[0].first, points[0].second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Fokusdakı mətn sahəsini dəyişir. Parol sahələrinə toxunulmur. */
    private fun editFocused(transform: (String) -> String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable || node.isPassword) return false
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            ""
        } else {
            node.text?.toString() ?: ""
        }
        val next = transform(current)
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) },
        )
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
            },
        )
        return ok
    }

    private fun pressEnter(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (node.isPassword) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            editFocused { it + "\n" }
        }
    }

    private fun realSize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    companion object {
        @Volatile var instance: ControlService? = null
            private set
    }
}
