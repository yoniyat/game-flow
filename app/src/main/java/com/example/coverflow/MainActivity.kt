package com.example.coverflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var flow: CoverFlowView
    private var all = false
    private var style = 0
    private var layout = 0
    private var sound = true
    private var theme = 0
    private var tone: ToneGenerator? = null
    private var mp: MediaPlayer? = null
    private var scrollId = 0
    private var lastStick = 0L
    private var pendingPkg: String? = null
    private var pendingSound: String? = null
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val sp by lazy {
        SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    }
    private val styleNames = listOf("Normal icons", "Album covers", "Game covers")
    private val layoutNames = listOf("Cover Flow", "PS4 style", "Xbox 360 style", "Switch style")
    private val layoutThemes = listOf(0, 1, 4, 5)
    private val themes = listOf(
        Triple("Grey", 0xFF30303A, 0xFF050507), Triple("Blue", 0xFF1B3A6B, 0xFF040A18),
        Triple("Purple", 0xFF4A2A7A, 0xFF0A0614), Triple("Red", 0xFF6B1B1B, 0xFF180404),
        Triple("Green", 0xFF1B5A32, 0xFF04120A), Triple("Black", 0xFF111111, 0xFF000000)
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        style = prefs.getInt("style", 0)
        layout = prefs.getInt("layout", 0).coerceIn(0, 3)
        sound = prefs.getBoolean("sound", true)
        theme = prefs.getInt("theme", 0).coerceIn(0, themes.size - 1)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 40) } catch (e: Exception) { null }
        loadScroll()
        flow = CoverFlowView(this)
        flow.style = style
        flow.mode = layout
        applyTheme()
        val gear = Button(this).apply { text = "⚙"; textSize = 22f; minWidth = 0; minimumWidth = 0; setOnClickListener { showSettings() } }
        val root = FrameLayout(this)
        root.addView(flow)
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48, 24, 0) })
        setContentView(root)
        flow.onLaunch = { item -> launch(item) }
        flow.onLong = { item -> gameMenu(item) }
        flow.onTick = { playTick() }
    }

    override fun onDestroy() { tone?.release(); mp?.release(); sp.release(); super.onDestroy() }
    override fun onResume() { super.onResume(); refresh() }

    private fun applyTheme() { flow.bgA = themes[theme].second.toInt(); flow.bgB = themes[theme].third.toInt() }
    private fun covers() = File(filesDir, "covers").apply { mkdirs() }
    private fun sounds() = File(filesDir, "sounds").apply { mkdirs() }

    // ---------- Sounds ----------
    private fun loadScroll() {
        val f = File(sounds(), "scroll.snd")
        scrollId = if (f.exists()) sp.load(f.path, 1) else 0
    }

    private fun playTick() {
        if (!sound) return
        if (scrollId != 0) sp.play(scrollId, 1f, 1f, 1, 0, 1f) else tone?.startTone(ToneGenerator.TONE_DTMF_S, 20)
    }

    private fun playLaunch() {
        if (!sound) return
        val f = File(sounds(), "launch.snd")
        if (f.exists()) {
            try {
                mp?.release()
                mp = MediaPlayer().apply {
                    setDataSource(f.path)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release(); if (mp === it) mp = null }
                    prepareAsync()
                }
                return
            } catch (e: Exception) { }
        }
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    }

    private fun soundMenu(kind: String) {
        val name = if (kind == "scroll") "Scroll sound" else "Launch sound"
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf("Choose audio file", "Use default sound")) { _, w ->
                if (w == 0) {
                    pendingSound = kind
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 8)
                } else {
                    File(sounds(), "$kind.snd").delete()
                    if (kind == "scroll") loadScroll()
                    showSettings()
                }
            }.setNegativeButton("Back") { _, _ -> showSettings() }.show()
    }

    private fun saveSound(uri: Uri, kind: String) {
        Thread {
            val f = File(sounds(), "$kind.snd")
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ok = f.length() > 0 && f.length() < 8_000_000L
            } catch (e: Exception) { }
            if (!ok) f.delete()
            runOnUiThread {
                if (kind == "scroll") loadScroll()
                Toast.makeText(this, if (ok) "Sound saved" else "Couldn’t use that file (max 8 MB)", Toast.LENGTH_SHORT).show()
                showSettings()
            }
        }.start()
    }

    // ---------- Menus ----------
    private fun showSettings() {
        val items = arrayOf(
            "Apps: " + if (all) "All apps" else "Games only",
            "Layout: " + layoutNames[layout],
            "Style: " + styleNames[style],
            "Sound: " + if (sound) "On" else "Off",
            "Scroll sound: " + if (File(sounds(), "scroll.snd").exists()) "Custom" else "Default",
            "Launch sound: " + if (File(sounds(), "launch.snd").exists()) "Custom" else "Default",
            "Background: " + themes[theme].first
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, w ->
            var reopen = true
            when (w) {
                0 -> { all = !all; refresh() }
                1 -> {
                    layout = (layout + 1) % 4; flow.mode = layout
                    theme = layoutThemes[layout]; applyTheme()
                    prefs.edit().putInt("layout", layout).putInt("theme", theme).apply()
                }
                2 -> { style = (style + 1) % 3; prefs.edit().putInt("style", style).apply(); flow.style = style }
                3 -> { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }
                4 -> { soundMenu("scroll"); reopen = false }
                5 -> { soundMenu("launch"); reopen = false }
                6 -> { theme = (theme + 1) % themes.size; prefs.edit().putInt("theme", theme).apply(); applyTheme() }
            }
            if (reopen) showSettings()
        }.setNegativeButton("Close", null).show()
    }

    private fun gameMenu(item: Item) {
        val lib = if (all) "Add to library" else "Hide from library"
        AlertDialog.Builder(this).setTitle(item.label)
            .setItems(arrayOf(lib, "Set cover image", "Remove cover image")) { _, w ->
                when (w) {
                    0 -> toggleLib(item)
                    1 -> {
                        pendingPkg = item.pkg
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 7)
                    }
                    2 -> { File(covers(), item.pkg + ".jpg").delete(); refresh() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == 7) pendingPkg?.let { saveCover(uri, it) }
        if (req == 8) pendingSound?.let { saveSound(uri, it) }
    }

    private fun saveCover(uri: Uri, pkg: String) {
        Thread {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var ss = 1
                while (o.outWidth / ss > 1200 || o.outHeight / ss > 1200) ss *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                if (bmp != null) File(covers(), "$pkg.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } catch (e: Exception) { }
            runOnUiThread {
                style = 2; prefs.edit().putInt("style", 2).apply(); flow.style = 2
                refresh()
            }
        }.start()
    }

    private fun launch(item: Item) {
        val i = packageManager.getLaunchIntentForPackage(item.pkg) ?: return
        playLaunch()
        startActivity(i)
    }

    // ---------- Controller ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { flow.move(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { flow.move(1); return true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.repeatCount == 0) flow.launchCurrent(); return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> { if (e.repeatCount == 0) flow.current()?.let { gameMenu(it) }; return true }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) showSettings(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            var x = e.getAxisValue(MotionEvent.AXIS_X)
            if (abs(x) < 0.3f) x = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val now = System.currentTimeMillis()
            if (abs(x) > 0.5f) {
                val gap = if (abs(x) > 0.9f) 100 else 220
                if (now - lastStick > gap) { lastStick = now; flow.move(if (x > 0) 1 else -1) }
            } else lastStick = 0
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------- Library ----------
    private fun set(key: String) = prefs.getStringSet(key, emptySet())!!.toSet()
    private fun put(key: String, v: Set<String>) = prefs.edit().putStringSet(key, HashSet(v)).apply()

    private fun toggleLib(item: Item) {
        if (all) { put("pinned", set("pinned") + item.pkg); put("hidden", set("hidden") - item.pkg) }
        else { put("hidden", set("hidden") + item.pkg); put("pinned", set("pinned") - item.pkg) }
        refresh()
    }

    private fun isGame(a: ApplicationInfo) =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun refresh() {
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
                    val f = File(covers(), it.packageName + ".jpg")
                    Item(it.packageName, label, n, a, if (f.exists()) BitmapFactory.decodeFile(f.path) else null)
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                flow.items = list
                flow.emptyHint = if (showAll) "No apps found" else "No games detected.\nOpen ⚙ settings, choose Apps: All apps,\nthen long-press a game to add it."
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
