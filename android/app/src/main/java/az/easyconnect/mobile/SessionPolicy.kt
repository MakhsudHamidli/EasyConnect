package az.easyconnect.mobile

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Paylaşım zamanı nəyə icazə verildiyinə dair YEGANƏ qərar mənbəyi.
 * Kompüterdən/digər telefondan gələn hər idarə əmri burada yoxlanılır.
 *
 * Qaydalar:
 *  - İdarə və klaviatura yalnız sahib onları seçibsə VƏ Accessibility xidməti aktivdirsə işləyir.
 *  - Telefon kilidlidirsə, sahib fasilə veribsə və ya qorunan/sistem tətbiqi öndədirsə — idarə yoxdur.
 *  - Qorunan tətbiq (bank və s.) öndədirsə — görüntü də dayanır, sahibin yerli təsdiqi lazımdır.
 *    Təsdiqdən sonra belə həmin tətbiqdə uzaqdan idarə bloklu qalır.
 *  - Qorunan tətbiq UZAQDAN açılıbsa (son 3 saniyədə uzaqdan əmr olub) — telefon dərhal
 *    ana ekrana qayıdır və sahibə xəbər verilir.
 */
object SessionPolicy {
    enum class Block(val wire: String) { LOCKED("locked"), PROTECTED("protected"), SYSTEM("system") }

    @Volatile var active = false; private set
    @Volatile var allowControl = false; private set
    @Volatile var allowKeyboard = false; private set
    /** Telefonun səsini (media/oyun) ötürmək — mikrofon YOX. */
    @Volatile var allowAudio = false; private set
    /** Fayl göndərmək / qəbul etmək (yalnız Downloads/EasyConnect və sahibin seçdiyi fayllar). */
    @Volatile var allowFiles = false; private set
    @Volatile var userPaused = false; private set
    @Volatile var lockPaused = false; private set

    @Volatile var foregroundPkg: String? = null; private set
    /** Ön plandakı EasyConnect ekranının sinfi (öz tətbiqimizdə hansı ekranın bloklandığını bilmək üçün). */
    @Volatile var foregroundActivity: String? = null; private set
    /** Qorunan və hələ təsdiqlənməmiş tətbiq — görüntü gizlidir. */
    @Volatile var hiddenPkg: String? = null; private set
    /** Sahibin "bu dəfə göstər" dediyi qorunan tətbiq — görüntü var, idarə yoxdur. */
    @Volatile private var shownOncePkg: String? = null
    @Volatile private var systemLocked = false
    @Volatile private var lastRemoteInputAt = 0L

    /** Uzaqdan açılmağa cəhd edilib bloklanan tətbiq (bildiriş üçün). */
    @Volatile var remoteOpenBlocked: String? = null; private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    val serviceReady: Boolean get() = ControlService.instance != null

    fun start(control: Boolean, keyboard: Boolean, audio: Boolean, files: Boolean) {
        active = true
        allowControl = control
        allowKeyboard = keyboard
        allowAudio = audio
        allowFiles = files
        userPaused = false
        hiddenPkg = null
        shownOncePkg = null
        remoteOpenBlocked = null
        foregroundPkg?.let { reevaluate(it) }
        changed()
    }

    fun end() {
        active = false
        allowControl = false
        allowKeyboard = false
        allowAudio = false
        allowFiles = false
        hiddenPkg = null
        shownOncePkg = null
        systemLocked = false
        changed()
    }

    fun setPermissions(control: Boolean, keyboard: Boolean, audio: Boolean, files: Boolean) {
        allowControl = control
        allowKeyboard = keyboard
        allowAudio = audio
        allowFiles = files
        changed()
    }

    fun setUserPaused(value: Boolean) { userPaused = value; changed() }
    fun setLockPaused(value: Boolean) { lockPaused = value; changed() }
    fun notifyServiceChanged() = changed()

    /** ControlService çağırır: ön plandakı tətbiq dəyişdi. */
    fun onForeground(context: Context, pkg: String?, activity: String? = null) {
        if (pkg == null || ProtectedApps.shouldIgnoreForeground(context, pkg)) return
        // Öz tətbiqimizdə ekran (Activity) dəyişəndə də yenidən qiymətləndiririk
        val newActivity = if (pkg == BuildConfigPkg.SELF && activity?.startsWith(BuildConfigPkg.SELF) == true) activity
            else if (pkg == BuildConfigPkg.SELF) foregroundActivity else null
        if (pkg == foregroundPkg && newActivity == foregroundActivity) return
        foregroundPkg = pkg
        foregroundActivity = newActivity
        if (!active) return
        reevaluate(pkg)
        if (hiddenPkg == pkg && SystemClock.elapsedRealtime() - lastRemoteInputAt < 3000) {
            // Uzaqdakı şəxs qorunan tətbiqi açmağa çalışdı — bağlayırıq.
            remoteOpenBlocked = pkg
            ControlService.instance?.goHome()
        }
        changed()
    }

    private fun reevaluate(pkg: String) {
        if (shownOncePkg != null && shownOncePkg != pkg) shownOncePkg = null
        when (ProtectedApps.classify(pkg, foregroundActivity)) {
            ProtectedApps.Level.HIDE -> {
                hiddenPkg = if (pkg == shownOncePkg) null else pkg
                systemLocked = false
            }
            ProtectedApps.Level.CONTROL_LOCK -> { hiddenPkg = null; systemLocked = true }
            ProtectedApps.Level.NONE -> { hiddenPkg = null; systemLocked = false }
        }
    }

    /** Sahib bildirişdə "Bu dəfə göstər" basdı. */
    fun showHiddenOnce() {
        shownOncePkg = hiddenPkg
        hiddenPkg = null
        changed()
    }

    fun clearRemoteOpenBlocked() { remoteOpenBlocked = null }

    fun block(): Block? = when {
        lockPaused -> Block.LOCKED
        hiddenPkg != null -> Block.PROTECTED
        shownOncePkg != null && shownOncePkg == foregroundPkg -> Block.PROTECTED
        systemLocked -> Block.SYSTEM
        else -> null
    }

    fun videoPaused() = userPaused || lockPaused || hiddenPkg != null

    fun pauseReason(): String? = when {
        lockPaused -> "locked"
        hiddenPkg != null -> "protected"
        userPaused -> "user"
        else -> null
    }

    fun canControl() = active && allowControl && serviceReady && !userPaused && block() == null
    fun canType() = active && allowKeyboard && serviceReady && !userPaused && block() == null

    /**
     * Yalnız Geri / Ana ekran / Son tətbiqlər. Bu düymələr heç bir icazəni dəyişə bilməz,
     * ona görə qorunan və sistem ekranlarında da (EasyConnect, Ayarlar, bank) icazəlidir —
     * qarşı tərəf həmin ekrandan çıxa bilsin. Toxunma və yazmaq orada yenə bloklu qalır.
     * Kilid və fasilə zamanı isə heç nə yoxdur.
     */
    fun canNavigate() = active && allowControl && serviceReady && !userPaused && !lockPaused

    fun markRemoteInput() { lastRemoteInputAt = SystemClock.elapsedRealtime() }

    fun permsJson(): JSONObject = JSONObject()
        .put("control", allowControl)
        .put("keyboard", allowKeyboard)
        .put("navigate", canNavigate())
        .put("audio", allowAudio)
        .put("files", allowFiles)
        .put("controlReady", serviceReady)
        .put("blocked", block()?.wire ?: JSONObject.NULL)
}
