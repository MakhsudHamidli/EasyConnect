package az.easyconnect.mobile

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Sahib hansı tətbiqlərin paylaşım zamanı gizlədiləcəyini seçir.
 * Bank/ödəniş/parol tətbiqləri avtomatik işarələnir; sahib əlavə edə və ya çıxara bilər.
 */
class ProtectedAppsActivity : AppCompatActivity() {

    private data class App(val pkg: String, val label: String, val icon: Drawable?, var hidden: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bu ekranı da başqa pəncərələr örtə bilməsin
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        ProtectedApps.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.protected_apps_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.protected_apps_hint)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            setPadding(0, dp(4), 0, dp(12))
        })

        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .map { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                App(
                    pkg = pkg,
                    label = ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                    icon = ai?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
                    hidden = ProtectedApps.isHidden(pkg),
                )
            }
            .sortedWith(compareByDescending<App> { it.hidden }.thenBy { it.label.lowercase() })

        val list = ListView(this)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@ProtectedAppsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
                    addView(TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
                        textSize = 15f
                        setTextColor(ContextCompat.getColor(context, R.color.text))
                    })
                    addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (row.getChildAt(1) as TextView).text = app.label
                (row.getChildAt(2) as CheckBox).isChecked = app.hidden
                row.setOnClickListener {
                    app.hidden = !app.hidden
                    ProtectedApps.setHidden(app.pkg, app.hidden)
                    notifyDataSetChanged()
                }
                return row
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.filterTouchesWhenObscured = true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
