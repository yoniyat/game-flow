package com.example.coverflow

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    lateinit var flow: CoverFlowView
    lateinit var menu: MenuView
    val audio by lazy { Audio(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var dwell: Runnable? = null
    var selPkg: String? = null
    var hoverOn = true
    var dwellMs = 2000L
    var sort = 0
    var style = 0
    var layout = 0
    var fx = true
    var themeIdx = 6
    private var lastStick = 0L
    private var lastMenuStick = 0L
    var pendingPkg: String? = null
    var pendingSound: String? = null
    var pendingBg = 0
    private var pendingFocus: String? = null
    private var firstLoad = true
    private var rxOn = false
    val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    val sortNames = listOf("A–Z", "Recently played", "My order")
    val styleNames = listOf("Normal icons", "Album covers", "Game covers")
    val layoutNames = listOf("Flow", "Cover Flow", "Xbox 360 style", "Switch style")
    val layoutThemes = listOf(6, 0, 4, 5)
    val dwellOptions = listOf(1000L, 2000L, 3000L, 5000L)
    val themes = listOf(
        Triple("Grey", 0xFF30303A, 0xFF050507), Triple("Blue", 0xFF1B3A6B, 0xFF040A18),
        Triple("Purple", 0xFF4A2A7A, 0xFF0A0614), Triple("Red", 0xFF6B1B1B, 0xFF180404),
        Triple("Green", 0xFF1B5A32, 0xFF04120A), Triple("Black", 0xFF111111, 0xFF000000),
        Triple("Flow", 0xFF14214F, 0xFF03040B)
    )
    private val settingsItem by lazy {
        val a = makeSettingsArt()
        Item(SETTINGS_PKG, "Settings", a.first, a.second, null)
    }

    // Battery, clock and Bluetooth events
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(context: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                BluetoothDevice.ACTION_ACL_CONNECTED -> btConnected(i)
                else -> updateClock()
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sort = prefs.getInt("sort", 0).coerceIn(0, 2)
        style = prefs.getInt("style", 0)
        layout = prefs.getInt("layout5", 0).coerceIn(0, 3)
        fx = prefs.getBoolean("fx", true)
        hoverOn = prefs.getBoolean("hover", true)
        dwellMs = prefs.getLong("dwell", 2000L)
        themeIdx = prefs.getInt("theme5", 6).coerceIn(0, themes.size - 1)
        audio.sound = prefs.getBoolean("sound", true)
        audio.musicOn = prefs.getBoolean("music", true)
        audio.loadScroll()
        flow = CoverFlowView(this)
        flow.style = style
        flow.mode = layout
        flow.fx = fx
        applyTheme()
        loadBg()
        menu = MenuView(this)
        val root = FrameLayout(this)
        root.addView(flow)
        root.addView(menu)
        setContentView(root)
        flow.onLaunch = { item -> launch(item) }
        flow.onLong = { item -> gameMenu(item) }
        flow.onTick = { audio.tick() }
        flow.onSelect = { item -> onSelect(item) }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3)
        }
    }

    override fun onResume() { super.onResume(); refresh(); audio.startMusic(); startRx() }

    override fun onPause() {
        stopRx()
        cancelDwell(); selPkg = null
        audio.stopGame(false); flow.hud.let { }; flow.showHover(null)
        audio.stopMusic()
        super.onPause()
    }

    override fun onDestroy() { cancelDwell(); audio.release(); super.onDestroy() }

    override fun onBackPressed() { if (menu.isOpen) menu.back() else super.onBackPressed() }

    // ---------- Top bar: battery, clock, Bluetooth ----------
    private fun startRx() {
        if (rxOn) return
        val f = IntentFilter()
        f.addAction(Intent.ACTION_BATTERY_CHANGED)
        f.addAction(Intent.ACTION_TIME_TICK)
        f.addAction(Intent.ACTION_TIME_CHANGED)
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
        rxOn = true
        if (sticky != null && sticky.action == Intent.ACTION_BATTERY_CHANGED) readBattery(sticky)
        updateClock()
    }

    private fun stopRx() { if (rxOn) { unregisterReceiver(rx); rxOn = false } }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) flow.hud.battery = level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        flow.hud.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        flow.invalidate()
    }

    private fun updateClock() {
        val now = Date()
        flow.hud.timeText = android.text.format.DateFormat.getTimeFormat(this).format(now)
        flow.hud.dateText = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
        flow.invalidate()
    }

    private fun btConnected(i: Intent) {
        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val name = try { d?.name } catch (e: SecurityException) { null }
        flow.hud.toast(if (name.isNullOrBlank()) "Bluetooth device" else name)
    }

    fun applyTheme() { flow.bgA = themes[themeIdx].second.toInt(); flow.bgB = themes[themeIdx].third.toInt() }
    fun covers() = File(filesDir, "covers").apply { mkdirs() }
    fun bgs() = File(filesDir, "bg").apply { mkdirs() }
    fun loadBg() {
        flow.bgImage = File(bgs(), "$layout.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    // ---------- Hover preview ----------
    private fun cancelDwell() { dwell?.let { handler.removeCallbacks(it) }; dwell = null }

    private fun onSelect(item: Item?) {
        if (item?.pkg == selPkg) return
        selPkg = item?.pkg
        cancelDwell()
        audio.stopGame()
        flow.showHover(null)
        if (!hoverOn || item == null || item.pkg == SETTINGS_PKG) return
        val r = Runnable { flow.showHover(item.cover ?: item.album); audio.playGame(item.pkg) }
        dwell = r
        handler.postDelayed(r, dwellMs)
    }

    fun retrigger() { selPkg = null; onSelect(flow.current()) }

    // ---------- Actions ----------
    private fun launch(item: Item) {
        if (item.pkg == SETTINGS_PKG) { openSettings(); return }
        val i = packageManager.getLaunchIntentForPackage(item.pkg) ?: return
        prefs.edit().putLong("last_" + item.pkg, System.currentTimeMillis()).apply()
        audio.launch()
        startActivity(i)
    }

    fun moveGame(item: Item, d: Int) {
        val l = flow.items.map { it.pkg }.filter { it != SETTINGS_PKG }.toMutableList()
        val i = l.indexOf(item.pkg); val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        java.util.Collections.swap(l, i, j)
        sort = 2
        prefs.edit().putString("order", l.joinToString(",")).putInt("sort", 2).apply()
        pendingFocus = item.pkg
        refresh()
    }

    fun hideGame(item: Item) {
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
                style = 2; prefs.edit().putInt("style", 2).apply(); flow.style = 2
                selPkg = null; refresh(); menu.close()
            }
        }
        if (req == 8) pendingSound?.let { saveSound(uri, it) }
        if (req == 9) saveImage(uri, File(bgs(), "$pendingBg.jpg"), 1920) { loadBg(); menu.invalidate() }
    }

    // ---------- Controller ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val vol = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (menu.isOpen && !vol) {
            val vert = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || vert)) menu.key(e.keyCode)
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { flow.move(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { flow.move(1); return true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.repeatCount == 0) flow.launchCurrent(); return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> { if (e.repeatCount == 0) flow.current()?.let { gameMenu(it) }; return true }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { if (e.repeatCount == 0) openSettings(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            var x = e.getAxisValue(MotionEvent.AXIS_X)
            if (abs(x) < 0.3f) x = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val now = System.currentTimeMillis()
            if (menu.isOpen) {
                var y = e.getAxisValue(MotionEvent.AXIS_Y)
                if (abs(y) < 0.3f) y = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
                if (abs(y) > 0.6f && now - lastMenuStick > 200) {
                    lastMenuStick = now; menu.key(if (y > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                } else if (abs(x) > 0.6f && abs(y) <= 0.6f && now - lastMenuStick > 300) {
                    lastMenuStick = now; menu.key(if (x > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                } else if (abs(x) < 0.3f && abs(y) < 0.3f) lastMenuStick = 0
                return true
            }
            if (abs(x) > 0.5f) {
                val gap = if (abs(x) > 0.9f) 100 else 220
                if (now - lastStick > gap) { lastStick = now; flow.move(if (x > 0) 1 else -1) }
            } else lastStick = 0
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------- Library ----------
    fun set(key: String) = prefs.getStringSet(key, emptySet())!!.toSet()
    fun put(key: String, v: Set<String>) = prefs.edit().putStringSet(key, HashSet(v)).apply()

    private fun isGame(a: ApplicationInfo) =
        a.category == ApplicationInfo.CATEGORY_GAME || (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    fun refresh() {
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
                    val (n, a) = makeArt(pm.getApplicationIcon(it), label)
                    val f = File(covers(), it.packageName + ".jpg")
                    Item(it.packageName, label, n, a, if (f.exists()) BitmapFactory.decodeFile(f.path) else null)
                }
            val sorted = when (sort) {
                1 -> list.sortedWith(compareByDescending<Item> { prefs.getLong("last_" + it.pkg, 0L) }.thenBy { it.label.lowercase() })
                2 -> list.sortedWith(compareBy<Item> { val i = order.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i }.thenBy { it.label.lowercase() })
                else -> list.sortedBy { it.label.lowercase() }
            }
            val full = listOf(settingsItem) + sorted
            runOnUiThread {
                flow.items = full
                if (firstLoad && full.size > 1) { flow.goTo(1); firstLoad = false }
                pendingFocus?.let { pk ->
                    val ix = full.indexOfFirst { it.pkg == pk }
                    if (ix >= 0) flow.goTo(ix)
                    pendingFocus = null
                }
                flow.emptyHint = "No games detected.\nOpen the Settings tile and tap “Add a game…”."
            }
        }.start()
    }
}
