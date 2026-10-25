package com.example.coverflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var flow: CoverFlowView
    private lateinit var bApps: Button
    private lateinit var bStyle: Button
    private lateinit var bSound: Button
    private var all = false
    private var album = false
    private var sound = true
    private var tone: ToneGenerator? = null
    private var lastStick = 0L
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }

    private fun btn(onClick: () -> Unit) = Button(this).apply {
        textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        album = prefs.getBoolean("album", false)
        sound = prefs.getBoolean("sound", true)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 40) } catch (e: Exception) { null }
        flow = CoverFlowView(this)
        flow.album = album
        bApps = btn { all = !all; refresh() }
        bStyle = btn { album = !album; prefs.edit().putBoolean("album", album).apply(); flow.album = album; labels() }
        bSound = btn { sound = !sound; prefs.edit().putBoolean("sound", sound).apply(); labels() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bApps); addView(bStyle); addView(bSound)
        }
        val root = FrameLayout(this)
        root.addView(flow)
        root.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 72, 0, 0) })
        setContentView(root)
        flow.onLaunch = { item -> launch(item) }
        flow.onLong = { item -> askToggle(item) }
        flow.onTick = { if (sound) tone?.startTone(ToneGenerator.TONE_DTMF_S, 20) }
        labels()
    }

    override fun onDestroy() { tone?.release(); super.onDestroy() }
    override fun onResume() { super.onResume(); refresh() }

    private fun labels() {
        bApps.text = if (all) "Apps: All" else "Apps: Games"
        bStyle.text = if (album) "Style: Album" else "Style: Normal"
        bSound.text = if (sound) "Sound: On" else "Sound: Off"
    }

    private fun launch(item: Item) {
        val i = packageManager.getLaunchIntentForPackage(item.pkg) ?: return
        if (sound) tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        startActivity(i)
    }

    // Controller: D-pad, A to launch, Y to switch style
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { flow.move(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { flow.move(1); return true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.repeatCount == 0) flow.launchCurrent(); return true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> { if (e.repeatCount == 0) bStyle.performClick(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // Controller: left stick
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            var x = e.getAxisValue(MotionEvent.AXIS_X)
            if (abs(x) < 0.3f) x = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val now = System.currentTimeMillis()
            if (abs(x) > 0.5f) {
                val gap = if (abs(x) > 0.9f) 150 else 280
                if (now - lastStick > gap) { lastStick = now; flow.move(if (x > 0) 1 else -1) }
            } else lastStick = 0
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

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
        labels()
        val showAll = all
        Thread {
            val pm = packageManager
            val hidden = set("hidden"); val pinned = set("pinned")
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .filter { showAll || (it.packageName !in hidden && (isGame(it) || it.packageName in pinned)) }
                .map {
                    val label = pm.getApplicationLabel(it).toString()
                    val (n, a) = art(pm.getApplicationIcon(it), label)
                    Item(it.packageName, label, n, a)
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                flow.items = list
                flow.emptyHint = if (showAll) "No apps found" else "No games detected.\nTap “Apps: Games” and long-press a game to add it."
            }
        }.start()
    }

    // Returns (normal rounded icon, album-cover square)
    private fun art(d: Drawable, label: String): Pair<Bitmap, Bitmap> {
        val n = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(n)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 256f, 256f), 52f, 52f, Path.Direction.CW) })
        d.setBounds(0, 0, 256, 256); d.draw(c)

        val a = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val avg = Bitmap.createScaledBitmap(n, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt()
        val dark = Color.rgb(Color.red(avg) / 3, Color.green(avg) / 3, Color.blue(avg) / 3)
        val ac = Canvas(a)
        ac.drawRect(0f, 0f, 320f, 320f, Paint().apply { shader = LinearGradient(0f, 0f, 320f, 320f, avg, dark, Shader.TileMode.CLAMP) })
        d.setBounds(60, 40, 260, 240); d.draw(ac)
        ac.drawRect(60f, 40f, 260f, 240f, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x66FFFFFF })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; isFakeBoldText = true; setShadowLayer(4f, 0f, 2f, Color.BLACK) }
        var t = label.uppercase()
        while (t.length > 1 && tp.measureText(t) > 290f) t = t.dropLast(1)
        ac.drawText(t, 16f, 296f, tp)
        return n to a
    }
}
