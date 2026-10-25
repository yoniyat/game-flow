package com.example.coverflow

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

// kind: 0 = action, 1 = switch, 2 = cycle (left/right), 3 = section header
class Row(
    val label: String,
    val kind: Int = 0,
    val value: () -> String = { "" },
    val checked: () -> Boolean = { false },
    val on: (Int) -> Unit = {}
)

class Menu(val title: String, val rows: List<Row>)

fun hdr(t: String) = Row(t, 3)
fun act(t: String, v: () -> String = { "" }, f: () -> Unit) = Row(t, 0, v, { false }, { f() })
fun tog(t: String, get: () -> Boolean, set: (Boolean) -> Unit) = Row(t, 1, { "" }, get, { set(!get()) })
fun cyc(t: String, v: () -> String, step: (Int) -> Unit) = Row(t, 2, v, { false }, { d -> step(if (d == 0) 1 else d) })

class MenuView(ctx: Context) : View(ctx) {
    private class Level(val menu: Menu, var sel: Int, var scroll: Float)

    private val stack = ArrayList<Level>()
    val isOpen: Boolean get() = visibility == VISIBLE

    private val cyan = 0xFF4DE8FF.toInt()
    private val purple = 0xFFA35CFF.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val panel = RectF()
    private val list = RectF()
    private var sc = 1f

    init { visibility = GONE }

    private fun layoutRects() {
        sc = height / 720f
        val pw = min(width * .72f, 1100f * sc)
        val ph = height * .84f
        panel.set((width - pw) / 2, (height - ph) / 2, (width + pw) / 2, (height + ph) / 2)
        list.set(panel.left + 24 * sc, panel.top + 120 * sc, panel.right - 24 * sc, panel.bottom - 64 * sc)
    }

    private fun rowH(r: Row) = (if (r.kind == 3) 56 else 84) * sc
    private fun contentH(l: Level) = l.menu.rows.sumOf { rowH(it).toDouble() }.toFloat()
    private fun rowTop(l: Level, idx: Int): Float {
        var y = 0f
        for (i in 0 until idx) y += rowH(l.menu.rows[i])
        return y
    }

    private fun newLevel(m: Menu) = Level(m, max(0, m.rows.indexOfFirst { it.kind != 3 }), 0f)

    fun open(m: Menu) {
        stack.clear(); stack.add(newLevel(m))
        alpha = 0f; visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        invalidate()
    }

    fun push(m: Menu) { stack.add(newLevel(m)); invalidate() }

    fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); invalidate() } else close() }

    fun close() {
        if (!isOpen) return
        animate().alpha(0f).setDuration(150).withEndAction { visibility = GONE; stack.clear() }.start()
    }

    private fun ensureVisible(l: Level) {
        layoutRects()
        val top = rowTop(l, l.sel)
        val bot = top + rowH(l.menu.rows[l.sel])
        val vh = list.height()
        if (top < l.scroll) l.scroll = top
        if (bot > l.scroll + vh) l.scroll = bot - vh
        l.scroll = l.scroll.coerceIn(0f, max(0f, contentH(l) - vh))
    }

    private fun move(d: Int) {
        val l = stack.lastOrNull() ?: return
        var i = l.sel
        do { i += d } while (i in l.menu.rows.indices && l.menu.rows[i].kind == 3)
        if (i in l.menu.rows.indices) { l.sel = i; ensureVisible(l); invalidate() }
    }

    private fun activate(d: Int) {
        val l = stack.lastOrNull() ?: return
        val r = l.menu.rows.getOrNull(l.sel) ?: return
        if (r.kind == 3) return
        if (r.kind == 0 && d != 0) return
        r.on(d)
        invalidate()
    }

    fun key(code: Int): Boolean {
        if (!isOpen) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> activate(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> activate(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activate(0)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> back()
            KeyEvent.KEYCODE_BUTTON_START -> close()
        }
        return true
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            val l = stack.lastOrNull() ?: return true
            layoutRects()
            l.scroll = (l.scroll + dy).coerceIn(0f, max(0f, contentH(l) - list.height()))
            invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val l = stack.lastOrNull() ?: return true
            layoutRects()
            if (!panel.contains(e.x, e.y)) { close(); return true }
            if (e.y < list.top) {
                if (e.x > panel.right - 140 * sc) close()
                else if (e.x < panel.left + 200 * sc && stack.size > 1) back()
                return true
            }
            if (list.contains(e.x, e.y)) {
                var y = list.top - l.scroll
                for (i in l.menu.rows.indices) {
                    val h = rowH(l.menu.rows[i])
                    if (e.y >= y && e.y < y + h) {
                        if (l.menu.rows[i].kind != 3) { l.sel = i; activate(0) }
                        break
                    }
                    y += h
                }
            }
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean { gd.onTouchEvent(e); return true }

    override fun onDraw(c: Canvas) {
        val l = stack.lastOrNull() ?: return
        layoutRects()
        c.drawColor(0xB8030618.toInt())
        p.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom, 0xF2172A5E.toInt(), 0xF20A1030.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, 36 * sc, 36 * sc, p)
        p.shader = null
        stroke.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom, cyan, purple, Shader.TileMode.CLAMP)
        stroke.strokeWidth = 16 * sc; stroke.alpha = 45
        c.drawRoundRect(panel, 36 * sc, 36 * sc, stroke)
        stroke.strokeWidth = 3 * sc; stroke.alpha = 255
        c.drawRoundRect(panel, 36 * sc, 36 * sc, stroke)
        stroke.shader = null

        val tx = panel.left + (if (stack.size > 1) 110 else 44) * sc
        tp.color = Color.WHITE; tp.textAlign = Paint.Align.LEFT; tp.textSize = 52 * sc; tp.alpha = 255
        c.drawText(l.menu.title, tx, panel.top + 80 * sc, tp)
        p.shader = LinearGradient(tx, 0f, tx + 140 * sc, 0f, cyan, purple, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(tx, panel.top + 94 * sc, tx + 140 * sc, panel.top + 99 * sc), 3 * sc, 3 * sc, p)
        p.shader = null
        tp.textSize = 64 * sc; tp.alpha = 220
        if (stack.size > 1) c.drawText("‹", panel.left + 40 * sc, panel.top + 84 * sc, tp)
        tp.textAlign = Paint.Align.RIGHT; tp.textSize = 46 * sc
        c.drawText("✕", panel.right - 44 * sc, panel.top + 80 * sc, tp)

        c.save(); c.clipRect(list)
        var y = list.top - l.scroll
        for (i in l.menu.rows.indices) {
            val r = l.menu.rows[i]
            val h = rowH(r)
            if (y + h >= list.top && y <= list.bottom) drawRow(c, r, i == l.sel, y, h)
            y += h
        }
        c.restore()

        val ch = contentH(l)
        if (ch > list.height()) {
            val th = list.height() * list.height() / ch
            val ty = list.top + (list.height() - th) * (l.scroll / (ch - list.height()))
            p.color = 0x55FFFFFF
            c.drawRoundRect(RectF(panel.right - 14 * sc, ty, panel.right - 8 * sc, ty + th), 3 * sc, 3 * sc, p)
        }
        tp.color = Color.WHITE; tp.textAlign = Paint.Align.CENTER; tp.textSize = 26 * sc; tp.alpha = 130
        c.drawText("A: select    ‹ ›: change    B: back    Start: close", panel.centerX(), panel.bottom - 22 * sc, tp)
    }

    private fun drawRow(c: Canvas, r: Row, selected: Boolean, y: Float, h: Float) {
        if (r.kind == 3) {
            tp.textAlign = Paint.Align.LEFT; tp.textSize = 26 * sc; tp.color = cyan; tp.alpha = 200
            c.drawText(r.label.uppercase(), list.left + 14 * sc, y + h - 14 * sc, tp)
            return
        }
        val rect = RectF(list.left, y + 6 * sc, list.right - 22 * sc, y + h - 6 * sc)
        val rr = 22 * sc
        if (selected) {
            p.shader = LinearGradient(rect.left, 0f, rect.right, 0f, 0x664DE8FF, 0x66A35CFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rr, rr, p); p.shader = null
            stroke.shader = LinearGradient(rect.left, 0f, rect.right, 0f, cyan, purple, Shader.TileMode.CLAMP)
            stroke.strokeWidth = 2.5f * sc; stroke.alpha = 255
            c.drawRoundRect(rect, rr, rr, stroke); stroke.shader = null
        } else {
            p.color = 0x16FFFFFF
            c.drawRoundRect(rect, rr, rr, p)
        }
        tp.color = Color.WHITE; tp.textAlign = Paint.Align.LEFT; tp.textSize = 36 * sc
        tp.alpha = if (selected) 255 else 215
        val cy = rect.centerY() + 12 * sc
        c.drawText(r.label, rect.left + 28 * sc, cy, tp)
        val right = rect.right - 28 * sc
        when (r.kind) {
            0 -> {
                tp.textAlign = Paint.Align.RIGHT; tp.color = cyan; tp.alpha = 255
                c.drawText("›", right, cy, tp)
                val v = r.value()
                if (v.isNotEmpty()) c.drawText(v, right - 34 * sc, cy, tp)
            }
            1 -> {
                val on = r.checked()
                val tw = 96 * sc; val th = 48 * sc
                val track = RectF(right - tw, rect.centerY() - th / 2, right, rect.centerY() + th / 2)
                if (on) p.shader = LinearGradient(track.left, 0f, track.right, 0f, cyan, purple, Shader.TileMode.CLAMP)
                else p.color = 0x44FFFFFF
                c.drawRoundRect(track, th / 2, th / 2, p)
                p.shader = null
                p.color = Color.WHITE
                c.drawCircle(if (on) track.right - th / 2 else track.left + th / 2, track.centerY(), th / 2 - 5 * sc, p)
            }
            2 -> {
                tp.textAlign = Paint.Align.RIGHT; tp.color = cyan; tp.alpha = 255
                c.drawText("‹  " + r.value() + "  ›", right, cy, tp)
            }
        }
    }
}
