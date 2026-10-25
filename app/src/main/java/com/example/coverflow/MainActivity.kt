package com.example.coverflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var flow: CoverFlowView
    private lateinit var toggle: Button
    private var all = false
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        flow = CoverFlowView(this)
        toggle = Button(this).apply { setOnClickListener { all = !all; refresh() } }
        val root = FrameLayout(this)
        root.addView(flow)
        root.addView(toggle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(24, 72, 24, 0) })
        setContentView(root)
        flow.onLaunch = { item -> packageManager.getLaunchIntentForPackage(item.pkg)?.let { startActivity(it) } }
        flow.onLong = { item -> askToggle(item) }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun set(key: String) = prefs.getStringSet(key, emptySet())!!.toSet()
    private fun put(key: String, v: Set<String>) = prefs.edit().putStringSet(key, HashSet(v)).apply()

    private fun askToggle(item: Item) {
        val msg = if (all) "Add ${item.label} to your game library?" else "Hide ${item.label} from your game library?"
        AlertDialog.Builder(this).setMessage(msg).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                if (all) { put("pinned", set("pinned") + item.pkg); put("hidden", set("hidden") - item.pkg) }
                else { put("hidden", set("hidden") + item.pkg); put("pinned", set("pinned") - item.pkg) }
                refresh()
            }.show()
    }

    private fun isGame(a: ApplicationInfo) =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun refresh() {
        toggle.text = if (all) "Show games only" else "Show all apps"
        val showAll = all
        Thread {
            val pm = packageManager
            val hidden = set("hidden"); val pinned = set("pinned")
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .filter { showAll || (it.packageName !in hidden && (isGame(it) || it.packageName in pinned)) }
                .map { Item(it.packageName, pm.getApplicationLabel(it).toString(), toBitmap(pm.getApplicationIcon(it))) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { flow.items = list; flow.emptyHint = if (showAll) "No apps found" else "No games detected.\nTap “Show all apps” and long-press a game to add it." }
        }.start()
    }

    private fun toBitmap(d: Drawable): Bitmap {
        val s = 256
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply { addRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), 52f, 52f, Path.Direction.CW) }
        c.clipPath(path)
        d.setBounds(0, 0, s, s); d.draw(c)
        return bmp
    }
}
