package com.example.coverflow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Item(val pkg: String, val label: String, val icon: Bitmap, val album: Bitmap, val cover: Bitmap?)

class CoverFlowView(ctx: Context) : View(ctx) {
    var items: List<Item> = emptyList()
        set(v) { field = v; pos = pos.coerceIn(0f, max(0, v.size - 1).toFloat()); invalidate() }
    var style = 0
        set(v) { field = v; invalidate() }
    var mode = 0
        set(v) { field = v; invalidate() }
    var bgA = 0xFF30303A.toInt()
        set(v) { field = v; invalidate() }
    var bgB = 0xFF050507.toInt()
        set(v) { field = v; invalidate() }
    var bgImage: Bitmap? = null
        set(v) { field = v; invalidate() }
    var emptyHint = ""
    var onLaunch: (Item) -> Unit = {}
    var onLong: (Item) -> Unit = {}
    var onTick: () -> Unit = {}

    private var pos = 0f
    private var lastIdx = -1
    private var anim: ValueAnimator? = null
    private var handled = false
    private val cam = Camera().apply { setLocation(0f, 0f, -20f) }
    private val m = Matrix()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val bg = Paint()
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun s() = min(width * .55f, height * .42f)

    private fun tickCheck() {
        val i = pos.roundToInt()
        if (i != lastIdx) { if (lastIdx != -1) onTick(); lastIdx = i }
    }

    private fun snap(target: Float) {
        val t = target.roundToInt().coerceIn(0, max(0, items.size - 1)).toFloat()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, t).apply {
            duration = 240; interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; tickCheck(); invalidate() }
            start()
        }
    }

    fun move(d: Int) { snap(pos.roundToInt() + d.toFloat()) }
    fun goTo(i: Int) { anim?.cancel(); pos = i.coerceIn(0, max(0, items.size - 1)).toFloat(); lastIdx = pos.roundToInt(); invalidate() }
    fun current(): Item? = items.getOrNull(pos.roundToInt())
    fun launchCurrent() { current()?.let(onLaunch) }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { anim?.cancel(); handled = false; return true }
        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            pos = (pos + dx / (s() * .3f)).coerceIn(0f, max(0, items.size - 1).toFloat()); tickCheck(); invalidate(); return true
        }
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            handled = true; snap(pos - vx / (s() * .3f) * .15f); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handled = true
            val cx = width / 2f
            if (abs(e.x - cx) < s() / 2) launchCurrent()
            else snap(pos + if (e.x < cx) -1 else 1)
            return true
        }
        override fun onLongPress(e: MotionEvent) { handled = true; current()?.let(onLong) }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP && !handled) snap(pos)
        return true
    }

    private fun artOf(i: Item) = when (style) { 1 -> i.album; 2 -> i.cover ?: i.album; else -> i.icon }
    private fun aspOf(i: Item): Float {
        val cv = i.cover
        return if (style == 2 && cv != null) (cv.width.toFloat() / cv.height).coerceIn(.5f, 1f) else 1f
    }
    private fun order() = items.indices.sortedByDescending { abs(it - pos) }

    private fun tile(c: Canvas, i: Item, cx: Float, cy: Float, size: Float, alpha: Int, border: Int, bw: Float) {
        val half = size * aspOf(i) / 2
        val r = RectF(cx - half, cy - size / 2, cx + half, cy + size / 2)
        p.alpha = alpha; c.drawBitmap(artOf(i), null, r, p); p.alpha = 255
        val a = Color.alpha(border)
        if (a > 8 && bw > 0f) {
            bp.strokeWidth = bw * 3; bp.color = Color.argb(a / 3, Color.red(border), Color.green(border), Color.blue(border))
            c.drawRect(r, bp)
            bp.strokeWidth = bw; bp.color = border
            c.drawRect(r, bp)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg.shader = RadialGradient(w / 2, h * .35f, max(w, h) * .8f, bgA, bgB, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        val bi = bgImage
        if (bi != null) {
            val sc = max(w / bi.width, h / bi.height)
            val dw = bi.width * sc; val dh = bi.height * sc
            c.drawBitmap(bi, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            c.drawRect(0f, 0f, w, h, dim)
        }
        if (items.isEmpty()) {
            txt.textSize = 44f; txt.alpha = 160
            emptyHint.split("\n").forEachIndexed { i, l -> c.drawText(l, w / 2, h / 2 + i * 60f, txt) }
            txt.alpha = 255; return
        }
        when (mode) { 1 -> ps4(c, w, h); 2 -> blades(c, w, h); 3 -> switchRow(c, w, h); else -> flow(c, w, h) }
        val cur = items[pos.roundToInt().coerceIn(0, items.size - 1)]
        txt.textSize = 58f; c.drawText(cur.label, w / 2, h * .86f, txt)
        txt.textSize = 34f; txt.alpha = 140
        c.drawText("A / tap: launch · X / long-press: game options · Start / ⚙: settings", w / 2, h * .86f + 60f, txt)
        txt.alpha = 255
    }

    private fun flow(c: Canvas, w: Float, h: Float) {
        val s = s(); val cy = h * .42f
        mask.shader = LinearGradient(0f, s, 0f, s * 1.6f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        for (i in order()) {
            val d = i - pos
            if (abs(d) > 5) continue
            val item = items[i]
            val bmp = artOf(item)
            val bw = s * aspOf(item)
            val rect = RectF((s - bw) / 2, 0f, (s + bw) / 2, s)
            val t = d.coerceIn(-1f, 1f)
            val x = w / 2 + t * s * .62f + (d - t) * s * .28f
            cam.save(); cam.translate(0f, 0f, abs(t) * s * .35f); cam.rotateY(-t * 62f); cam.getMatrix(m); cam.restore()
            m.preTranslate(-s / 2, -s / 2); m.postTranslate(x, cy)
            c.save(); c.concat(m)
            c.drawBitmap(bmp, null, rect, p)
            val layer = c.saveLayer(0f, s, s, s * 1.6f, null)
            c.save(); c.translate(0f, 2 * s); c.scale(1f, -1f); c.drawBitmap(bmp, null, rect, p); c.restore()
            c.drawRect(0f, s, s, s * 1.6f, mask)
            c.restoreToCount(layer)
            c.restore()
        }
    }

    private fun ps4(c: Canvas, w: Float, h: Float) {
        val t = min(h * .30f, w * .2f); val cy = h * .45f
        for (i in order()) {
            val d = i - pos
            if (abs(d) > 7) continue
            val near = 1f - min(1f, abs(d))
            val x = w / 2 + d * t * 1.15f + d.coerceIn(-1f, 1f) * t * .25f
            tile(c, items[i], x, cy, t * (1f + .3f * near), (150 + 105 * near).toInt(), Color.argb((255 * near).toInt(), 255, 255, 255), 6f)
        }
    }

    private fun blades(c: Canvas, w: Float, h: Float) {
        val s = s(); val cy = h * .45f; val ph = s * 1.2f
        for (i in order()) {
            val d = i - pos
            if (abs(d) > 6) continue
            val near = 1f - min(1f, abs(d))
            val pw = s * (.55f + .5f * near)
            val x = w / 2 + d * s * .6f + d.coerceIn(-1f, 1f) * s * .25f
            val r = RectF(x - pw / 2, cy - ph / 2, x + pw / 2, cy + ph / 2)
            fillP.color = Color.argb((90 + 70 * near).toInt(), 0, 0, 0)
            c.drawRoundRect(r, 18f, 18f, fillP)
            bp.strokeWidth = 3f; bp.color = Color.argb((90 + 165 * near).toInt(), 139, 197, 63)
            c.drawRoundRect(r, 18f, 18f, bp)
            tile(c, items[i], x, cy - ph * .06f, pw * .78f, (140 + 115 * near).toInt(), 0, 0f)
        }
    }

    private fun switchRow(c: Canvas, w: Float, h: Float) {
        val t = min(h * .26f, w * .17f); val cy = h * .45f
        for (i in order()) {
            val d = i - pos
            if (abs(d) > 7) continue
            val near = 1f - min(1f, abs(d))
            val x = w / 2 + d * t * 1.12f + d.coerceIn(-1f, 1f) * t * .06f
            tile(c, items[i], x, cy - near * t * .06f, t * (1f + .12f * near), (170 + 85 * near).toInt(), Color.argb((255 * near).toInt(), 0, 195, 227), 8f)
        }
        val k = min(items.size, 15)
        val sel = if (items.size > 1) (pos / (items.size - 1) * (k - 1)).roundToInt() else 0
        for (j in 0 until k) {
            fillP.color = Color.argb(if (j == sel) 255 else 90, 255, 255, 255)
            c.drawCircle(w / 2 + (j - (k - 1) / 2f) * 34f, h * .72f, if (j == sel) 9f else 6f, fillP)
        }
    }
}
