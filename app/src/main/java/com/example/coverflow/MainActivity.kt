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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var flow: CoverFlowView
    private var sort = 0
    private var style = 0
    private var layout = 0
    private var sound = true
    private var musicOn = true
    private var fx = true
    private var theme = 6
    private var tone: ToneGenerator? = null
    private var mp: MediaPlayer? = null
    private var music: MediaPlayer? = null
    private var scrollId = 0
    private var lastStick = 0L
    private var pendingPkg: String? = null
    private var pendingSound: String? = null
    private var pendingBg = 0
    private var pendingFocus: String? = null
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val sp by lazy {
        SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
    }
    private val sortNames = listOf("A–Z", "Recently played", "My order")
    private val styleNames = listOf("Normal icons", "Album covers", "Game covers")
    private val layoutNames = listOf("Flow", "Cover Flow", "Xbox 360 style", "Switch style")
    private val layoutThemes = listOf(6, 0, 4, 5)
    private val themes = listOf(
        Triple("Grey", 0xFF30303A, 0xFF050507), Triple("Blue", 0xFF1B3A6B, 0xFF040A18),
        Triple("Purple", 0xFF4A2A7A, 0xFF0A0614), Triple("Red", 0xFF6B1B1B, 0xFF180404),
        Triple("Green", 0xFF1B5A32, 0xFF04120A), Triple("Black", 0xFF111111, 0xFF000000),
        Triple("Flow", 0xFF14214F, 0xFF03040B)
    )

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sort = prefs.getInt("sort", 0).coerceIn(0, 2)
        style = prefs.getInt("style", 0)
        layout = prefs.getInt("layout5", 0).coerceIn(0, 3)
        sound = prefs.getBoolean("sound", true)
        musicOn = prefs.getBoolean("music", true)
        fx = prefs.getBoolean("fx", true)
        theme = prefs.getInt("theme5", 6).coerceIn(0, themes.size - 1)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 40) } catch (e: Exception) { null }
        loadScroll()
        flow = CoverFlowView(this)
        flow.style = style
        flow.mode = layout
        flow.fx = fx
        applyTheme()
        loadBg()
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 26f; setTextColor(0xCCFFFFFF.toInt()); setPadding(24, 12, 24, 12)
            setOnClickListener { showSettings() }
        }
        val root = FrameLayout(this)
        root.addView(flow)
        root.addView(gear, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 36, 16, 0) })
        setContentView(root)
        flow.onLaunch = { item -> launch(item) }
        flow.onLong = { item -> gameMenu(item) }
        flow.onTick = { playTick() }
    }

    override fun onResume() { super.onResume(); refresh(); startMusic() }
    override fun onPause() { stopMusic(); super.onPause() }
    override fun onDestroy() { tone?.release(); mp?.release(); stopMusic(); sp.release(); super.onDestroy() }

    private fun applyTheme() { flow.bgA = themes[theme].second.toInt(); flow.bgB = themes[theme].third.toInt() }
    private fun covers() = File(filesDir, "covers").apply { mkdirs() }
    private fun sounds() = File(filesDir, "sounds").apply { mkdirs() }
    private fun bgs() = File(filesDir, "bg").apply { mkdirs() }
    private fun musicFile() = File(sounds(), "lobby.snd")
    private fun loadBg() {
        flow.bgImage = File(bgs(), "$layout.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    // ---------- Sounds & music ----------
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

    private fun startMusic() {
        stopMusic()
        if (!musicOn || !musicFile().exists()) return
        try {
            music = MediaPlayer().apply {
                setDataSource(musicFile().path); isLooping = true; setVolume(.5f, .5f)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) { music = null }
    }

    private fun stopMusic() { music?.release(); music = null }

    private fun pickAudio() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 8)
    }

    private fun soundMenu(kind: String) {
        val name = if (kind == "scroll") "Scroll sound" else "Launch sound"
        dlg().setTitle(name)
            .setItems(arrayOf("Choose audio file", "Use default sound")) { _, w ->
                if (w == 0) { pendingSound = kind; pickAudio() }
                else {
                    File(sounds(), "$kind.snd").delete()
                    if (kind == "scroll") loadScroll()
                    showSettings()
                }
            }.setNegativeButton("Back") { _, _ -> showSettings() }.show()
    }

    private fun musicMenu() {
        dlg().setTitle("Lobby music")
            .setItems(arrayOf(if (musicOn) "Turn off" else "Turn on", "Choose music file", "Remove music file")) { _, w ->
                when (w) {
                    0 -> { musicOn = !musicOn; prefs.edit().putBoolean("music", musicOn).apply(); startMusic(); showSettings() }
                    1 -> { pendingSound = "lobby"; pickAudio() }
                    2 -> { musicFile().delete(); stopMusic(); showSettings() }
                }
            }.setNegativeButton("Back") { _, _ -> showSettings() }.show()
    }

    private fun saveSound(uri: Uri, kind: String) {
        Thread {
            val f = File(sounds(), "$kind.snd")
            val limit = if (kind == "lobby") 30_000_000L else 8_000_000L
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ok = f.length() > 0 && f.length() < limit
            } catch (e: Exception) { }
            if (!ok) f.delete()
            runOnUiThread {
                if (kind == "scroll") loadScroll()
                if (kind == "lobby" && ok) startMusic()
                Toast.makeText(this, if (ok) "Saved" else "Couldn’t use that file (max ${limit / 1_000_000} MB)", Toast.LENGTH_SHORT).show()
                showSettings()
            }
        }.start()
    }

    // ---------- Menus ----------
    private fun showSettings() {
        val lobby = when { !musicFile().exists() -> "No file"; musicOn -> "On"; else -> "Off" }
        val items = arrayOf(
            "Add a game…",
            "Sort: " + sortNames[sort],
            "Layout: " + layoutNames[layout],
            "Style: " + styleNames[style],
            "Sound effects: " + if (sound) "On" else "Off",
            "Scroll sound: " + if (File(sounds(), "scroll.snd").exists()) "Custom" else "Default",
            "Launch sound: " + if (File(sounds(), "launch.snd").exists()) "Custom" else "Default",
            "Lobby music: $lobby",
            "Background colour: " + themes[theme].first,
            "Background picture: " + if (File(bgs(), "$layout.jpg").exists()) "Set" else "None",
            "Particles & waves: " + if (fx) "On" else "Off"
        )
        dlg().setTitle("Settings").setItems(items) { _, w ->
            var reopen = true
            when (w) {
                0 -> { addGame(); reopen = false }
                1 -> { sort = (sort + 1) % 3; prefs.edit().putInt("sort", sort).apply(); refresh() }
                2 -> {
                    layout = (layout + 1) % 4; flow.mode = layout
                    theme = layoutThemes[layout]; applyTheme(); loadBg()
                    prefs.edit().putInt("layout5", layout).putInt("theme5", theme).apply()
                }
                3 -> { style = (style + 1) % 3; prefs.edit().putInt("style", style).apply(); flow.style = style }
                4 -> { sound = !sound; prefs.edit().putBoolean("sound", sound).apply() }
                5 -> { soundMenu("scroll"); reopen = false }
                6 -> { soundMenu("launch"); reopen = false }
                7 -> { musicMenu(); reopen = false }
                8 -> { theme = (theme + 1) % themes.size; prefs.edit().putInt("theme5", theme).apply(); applyTheme() }
                9 -> { bgMenu(); reopen = false }
                10 -> { fx = !fx; prefs.edit().putBoolean("fx", fx).apply(); flow.fx = fx }
            }
            if (reopen) showSettings()
        }.setNegativeButton("Close", null).show()
    }

    private fun bgMenu() {
        dlg().setTitle("Background picture (" + layoutNames[layout] + ")")
            .setItems(arrayOf("Choose picture", "Remove picture")) { _, w ->
                if (w == 0) {
                    pendingBg = layout
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 9)
                } else { File(bgs(), "$layout.jpg").delete(); loadBg(); showSettings() }
            }.setNegativeButton("Back") { _, _ -> showSettings() }.show()
    }

    private fun addGame() {
        val inLib = flow.items.map { it.pkg }.toSet()
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName && it.packageName !in inLib }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
            runOnUiThread { showAddDialog(apps) }
        }.start()
    }

    private fun showAddDialog(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) { Toast.makeText(this, "No other apps found", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(apps.size)
        dlg().setTitle("Add games")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Add") { _, _ ->
                val add = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                if (add.isNotEmpty()) {
                    put("pinned", set("pinned") + add)
                    put("hidden", set("hidden") - add.toSet())
                    refresh()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun gameMenu(item: Item) {
        dlg().setTitle(item.label)
            .setItems(arrayOf("Hide from library", "Rename", "Set cover image", "Remove cover image", "Move left", "Move right")) { _, w ->
                when (w) {
                    0 -> hideGame(item)
                    1 -> renameGame(item)
                    2 -> {
                        pendingPkg = item.pkg
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 7)
                    }
                    3 -> { File(covers(), item.pkg + ".jpg").delete(); refresh() }
                    4 -> moveGame(item, -1)
                    5 -> moveGame(item, 1)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renameGame(item: Item) {
        val et = EditText(this).apply { setText(item.label); setSelectAllOnFocus(true); setSingleLine() }
        dlg().setTitle("Rename (empty = original name)").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                val ed = prefs.edit()
                if (t.isEmpty()) ed.remove("name_" + item.pkg) else ed.putString("name_" + item.pkg, t)
                ed.apply()
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun moveGame(item: Item, d: Int) {
        val l = flow.items.map { it.pkg }.toMutableList()
        val i = l.indexOf(item.pkg); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        java.util.Collections.swap(l, i, j)
        sort = 2
        prefs.edit().putString("order", l.joinToString(",")).putInt("sort", 2).apply()
        pendingFocus = item.pkg
        refresh()
    }

    private fun hideGame(item: Item) {
        put("hidden", set("hidden") + item.pkg); put("pinned", set("pinned") - item.pkg)
        refresh()
    }

    // ---------- Files from the phone ----------
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == 7) pendingPkg?.let { pkg ->
            saveImage(uri, File(covers(), "$pkg.jpg"), 1200) {
                style = 2; prefs.edit().putInt("style", 2).apply(); flow.style = 2; refresh()
            }
        }
        if (req == 8) pendingSound?.let { saveSound(uri, it) }
        if (req == 9) saveImage(uri, File(bgs(), "$pendingBg.jpg"), 1920) { loadBg(); showSettings() }
    }

    private fun saveImage(uri: Uri, dest: File, max: Int, done: () -> Unit) {
        Thread {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var ss = 1
                while (o.outWidth / ss > max || o.outHeight / ss > max) ss *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                if (bmp != null) dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } catch (e: Exception) { }
            runOnUiThread { done() }
        }.start()
    }

    private fun launch(item: Item) {
        val i = packageManager.getLaunchIntentForPackage(item.pkg) ?: return
        prefs.edit().putLong("last_" + item.pkg, System.currentTimeMillis()).apply()
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

    private fun isGame(a: ApplicationInfo) =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun refresh() {
        Thread {
            val pm = packageManager
            val hidden = set("hidden"); val pinned = set("pinned")
            val order = (prefs.getString("order", "") ?: "").split(",").filter { it.isNotEmpty() }
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .filter { it.packageName !in hidden && (isGame(it) || it.packageName in pinned) }
                .map {
                    val label = prefs.getString("name_" + it.packageName, null) ?: pm.getApplicationLabel(it).toString()
                    val (n, a) = art(pm.getApplicationIcon(it), label)
                    val f = File(covers(), it.packageName + ".jpg")
                    Item(it.packageName, label, n, a, if (f.exists()) BitmapFactory.decodeFile(f.path) else null)
                }
            val sorted = when (sort) {
                1 -> list.sortedWith(compareByDescending<Item> { prefs.getLong("last_" + it.pkg, 0L) }.thenBy { it.label.lowercase() })
                2 -> list.sortedWith(compareBy<Item> { val i = order.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i }.thenBy { it.label.lowercase() })
                else -> list.sortedBy { it.label.lowercase() }
            }
            runOnUiThread {
                flow.items = sorted
                pendingFocus?.let { pk ->
                    val ix = sorted.indexOfFirst { it.pkg == pk }
                    if (ix >= 0) flow.goTo(ix)
                    pendingFocus = null
                }
                flow.emptyHint = "No games detected.\nOpen ⚙ settings and tap “Add a game…”."
            }
        }.start()
    }

    // Returns (normal rounded icon, album-cover square)
    private fun art(d: Drawable, label: String): Pair<Bitmap, Bitmap> {
        val n = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(n)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 256f, 256f), 52f, 52f, Path.Direction.CW) })
        d.setBounds(0, 0, 256, 256)
