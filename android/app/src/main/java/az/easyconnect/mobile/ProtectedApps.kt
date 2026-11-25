package az.easyconnect.mobile

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * Hansı tətbiqlərin qorunduğunu müəyyən edir.
 *
 *  - [Level.HIDE]: bank, ödəniş, parol, autentifikator və sahibin seçdikləri.
 *    Görüntü dayanır, idarə bloklanır; göstərmək üçün telefonda yerli təsdiq lazımdır.
 *  - [Level.CONTROL_LOCK]: Ayarlar, icazə dialoqları, tətbiq quraşdırıcısı, Play Store və
 *    EasyConnect-in özü. Görüntü göstərilir, amma uzaqdan idarə HƏMİŞƏ bloklanır
 *    (uzaqdakı şəxs özünə icazə verə, nəsə quraşdıra və ya ayarları dəyişə bilməz).
 */
object ProtectedApps {
    enum class Level { NONE, HIDE, CONTROL_LOCK }

    private const val PREFS = "protected_apps"
    private const val KEY_OVERRIDES = "overrides"

    private val CONTROL_LOCK_PACKAGES = setOf(
        BuildConfigPkg.SELF,
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vending",
        "com.android.systemui.accessibility",
        "com.hihonor.systemmanager",
        "com.huawei.systemmanager",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
    )

    /** Paket adının hissələrindən birində bu sözlər varsa — maliyyə/parol tətbiqi sayılır. */
    private val CONTAINS = listOf(
        "bank", "wallet", "crypto", "authenticator", "authy", "password", "passwd",
        "bitwarden", "1password", "lastpass", "keepass", "dashlane", "finance",
    )

    /** Hissə tam bu adlardırsa (Azərbaycan bankları/ödəniş sistemləri və s.). */
    private val EXACT = setOf(
        "birbank", "leobank", "abb", "m10", "emanat", "yelo", "unibank", "xalqbank",
        "pashabank", "rabitabank", "accessbank", "expressbank", "bankrespublika",
        "revolut", "wise", "binance", "paypal", "payoneer", "kapitalbank",
    )

    /** Uzaqdan idarəyə təsir etməyən sistem pəncərələri (bildiriş paneli, klaviatura). */
    private val IGNORED_FOREGROUND = setOf("com.android.systemui")

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun shouldIgnoreForeground(context: Context, pkg: String): Boolean {
        if (pkg in IGNORED_FOREGROUND) return true
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && ime.substringBefore('/') == pkg
    }

    fun defaultLevel(pkg: String): Level {
        if (pkg in CONTROL_LOCK_PACKAGES) return Level.CONTROL_LOCK
        val parts = pkg.lowercase().split('.', '_')
        val financial = parts.any { part ->
            part in EXACT || part.startsWith("pay") || CONTAINS.any { part.contains(it) }
        }
        return if (financial) Level.HIDE else Level.NONE
    }

    /**
     * EasyConnect-in öz ekranlarından yalnız icazələrin dəyişdirildiyi yerlər bloklanır —
     * əsas ekran uzaqdan idarə oluna bilər (məs. tətbiqdən çıxmaq, fayl göndərmək).
     */
    private val SELF_LOCKED_ACTIVITIES = setOf(
        "${BuildConfigPkg.SELF}.PermissionsActivity",
        "${BuildConfigPkg.SELF}.ProtectedAppsActivity",
    )

    fun classify(pkg: String, activity: String? = null): Level {
        if (pkg == BuildConfigPkg.SELF) {
            // Ekran bilinmirsə — ehtiyatlı davranırıq (bloklu)
            return if (activity == null || activity in SELF_LOCKED_ACTIVITIES) Level.CONTROL_LOCK else Level.NONE
        }
        if (pkg in CONTROL_LOCK_PACKAGES) return Level.CONTROL_LOCK
        return when (overrides()[pkg]) {
            true -> Level.HIDE
            false -> Level.NONE
            null -> defaultLevel(pkg)
        }
    }

    fun isHidden(pkg: String) = classify(pkg) == Level.HIDE

    /** Sahibin seçimi: true = qoru, false = qoruma. */
    fun setHidden(pkg: String, hidden: Boolean) {
        val ctx = appContext ?: return
        val map = overrides().toMutableMap()
        if (hidden == (defaultLevel(pkg) == Level.HIDE)) map.remove(pkg) else map[pkg] = hidden
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OVERRIDES, json.toString()).apply()
    }

    private fun overrides(): Map<String, Boolean> {
        val ctx = appContext ?: return emptyMap()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getBoolean(it) }
        }.getOrDefault(emptyMap())
    }
}

/** Tətbiqin öz paket adı (testlərdə də işləsin deyə sabit). */
object BuildConfigPkg {
    const val SELF = "az.easyconnect.mobile"
}
