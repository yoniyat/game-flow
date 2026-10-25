package com.example.coverflow

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import java.io.File

fun MainActivity.openSettings() { menu.open(rootMenu()) }

fun MainActivity.rootMenu(): Menu = Menu("Settings", listOf(
    hdr("Library"),
    act("Add a game…") { loadAddGame() },
    cyc("Sort", { sortNames[sort] }) { d ->
        sort = (sort + d + 3) % 3; prefs.edit().putInt("sort", sort).apply(); refresh()
    },
    hdr("Look"),
    cyc("Layout", { layoutNames[layout] }) { d ->
        layout = (layout + d + 4) % 4; flow.mode = layout
        theme = layoutThemes[layout]; applyTheme(); loadBg()
        prefs.edit().putInt("layout5", layout).putInt("theme5", theme).apply()
    },
    cyc("Style", { styleNames[style] }) { d ->
        style = (style + d + 3) % 3; prefs.edit().putInt("style", style).apply(); flow.style = style
    },
    cyc("Background colour", { themes[theme].first }) { d ->
        theme = (theme + d + themes.size) % themes.size; prefs.edit().putInt("theme5", theme).apply(); applyTheme()
    },
    act("Background picture", { if (File(bgs(), "$layout.jpg").exists()) "Set" else "None" }) { menu.push(bgMenu()) },
    tog("Particles & waves", { fx }) { fx = it; prefs.edit().putBoolean("fx", it).apply(); flow.fx = it },
    hdr("Sound"),
    tog("Sound effects", { audio.sound }) { audio.sound = it; prefs.edit().putBoolean("sound", it).apply() },
    act("Scroll sound", { if (File(audio.dir(), "scroll.snd").exists()) "Custom" else "Default" }) { menu.push(soundMenu("scroll")) },
    act("Launch sound", { if (File(audio.dir(), "launch.snd").exists()) "Custom" else "Default" }) { menu.push(soundMenu("launch")) },
    act("Lobby music", { if (!audio.musicFile().exists()) "No file" else if (audio.musicOn) "On" else "Off" }) { menu.push(musicMenu()) },
    hdr("Hover preview"),
    tog("Hover music & art", { hoverOn }) { hoverOn = it; prefs.edit().putBoolean("hover", it).apply(); retrigger() },
    cyc("Hover delay", { "${dwellMs / 1000}s" }) { d ->
        val n = dwellOptions.size
        dwellMs = dwellOptions[(dwellOptions.indexOf(dwellMs) + d + n) % n]
        prefs.edit().putLong("dwell", dwellMs).apply(); retrigger()
    }
))

fun MainActivity.soundMenu(kind: String): Menu = Menu(if (kind == "scroll") "Scroll sound" else "Launch sound", listOf(
    act("Choose audio file") { pendingSound = kind; pickAudio() },
    act("Use default sound") {
        File(audio.dir(), "$kind.snd").delete()
        if (kind == "scroll") audio.loadScroll()
        menu.back()
    }
))

fun MainActivity.musicMenu(): Menu = Menu("Lobby music", listOf(
    tog("Lobby music on", { audio.musicOn }) { audio.musicOn = it; prefs.edit().putBoolean("music", it).apply(); audio.startMusic() },
    act("Choose music file") { pendingSound = "lobby"; pickAudio() },
    act("Remove music file") { audio.musicFile().delete(); audio.stopMusic(); menu.back() }
))

fun MainActivity.bgMenu(): Menu = Menu("Background picture", listOf(
    act("Choose picture") { pendingBg = layout; pickImage(9) },
    act("Remove picture") { File(bgs(), "$layout.jpg").delete(); loadBg(); menu.back() }
))

fun MainActivity.gameMenu(item: Item) {
    if (item.pkg == SETTINGS_PKG) { openSettings(); return }
    menu.open(Menu(item.label, listOf(
        act("Hide from library") { hideGame(item); menu.close() },
        act("Rename") { menu.close(); renameGame(item) },
        act("Set cover image") { pendingPkg = item.pkg; pickImage(7) },
        act("Remove cover image") { File(covers(), item.pkg + ".jpg").delete(); selPkg = null; refresh(); menu.close() },
        act("Move left") { moveGame(item, -1); menu.close() },
        act("Move right") { moveGame(item, 1); menu.close() },
        act("Set game music") { pendingSound = "game_" + item.pkg; pickAudio() },
        act("Remove game music") { audio.gameFile(item.pkg).delete(); retrigger(); menu.close() }
    )))
}

fun MainActivity.loadAddGame() {
    val inLib = flow.items.map { it.pkg }.toSet()
    Thread {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName && it.packageName !in inLib }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
        runOnUiThread {
            if (apps.isEmpty()) {
                Toast.makeText(this, "No other apps found", Toast.LENGTH_SHORT).show()
            } else {
                val picked = HashSet<String>()
                val rows = ArrayList<Row>()
                rows.add(act("Add selected games") {
                    if (picked.isNotEmpty()) {
                        put("pinned", set("pinned") + picked)
                        put("hidden", set("hidden") - picked)
                        refresh()
                    }
                    menu.back()
                })
                rows.add(hdr("Tick the games to add"))
                for (a in apps) {
                    val pkg = a.first
                    rows.add(tog(a.second, { pkg in picked }) { if (it) picked.add(pkg) else picked.remove(pkg) })
                }
                menu.push(Menu("Add games", rows))
            }
        }
    }.start()
}

fun MainActivity.renameGame(item: Item) {
    val et = EditText(this).apply { setText(item.label); setSelectAllOnFocus(true); setSingleLine() }
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Rename (empty = original name)").setView(et)
        .setPositiveButton("Save") { _, _ ->
            val t = et.text.toString().trim()
            val ed = prefs.edit()
            if (t.isEmpty()) ed.remove("name_" + item.pkg) else ed.putString("name_" + item.pkg, t)
            ed.apply()
            refresh()
        }.setNegativeButton("Cancel", null).show()
}

@Suppress("DEPRECATION")
fun MainActivity.pickAudio() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 8)
}

@Suppress("DEPRECATION")
fun MainActivity.pickImage(req: Int) {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), req)
}

fun MainActivity.saveSound(uri: Uri, kind: String) {
    Thread {
        val f = File(audio.dir(), "$kind.snd")
        val limit = if (kind == "lobby" || kind.startsWith("game_")) 30_000_000L else 8_000_000L
        var ok = false
        try {
            contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ok = f.length() > 0 && f.length() < limit
        } catch (e: Exception) { }
        if (!ok) f.delete()
        runOnUiThread {
            if (kind == "scroll") audio.loadScroll()
            if (kind == "lobby" && ok) audio.startMusic()
            Toast.makeText(this, if (ok) "Saved" else "Couldn’t use that file (max ${limit / 1_000_000} MB)", Toast.LENGTH_SHORT).show()
            if (kind.startsWith("game_")) { menu.close(); retrigger() } else menu.invalidate()
        }
    }.start()
}

fun MainActivity.saveImage(uri: Uri, dest: File, max: Int, done: () -> Unit) {
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
