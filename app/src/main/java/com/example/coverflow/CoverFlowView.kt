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

class Item(val pkg: String, val label: String, val icon: Bitmap, val album: Bitmap)

class CoverFlowView(ctx: Context) : View(ctx) {
    var items: List<Item> = emptyList()
        set(v) { field = v; pos = pos.coerceIn(0f, max(0, v.size - 1).toFloat()); invalidate() }
    var album = false
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

    private fun s() = min(width * .55f, height * .42f)

    private fun tickCheck() {
        val i = pos.roundToInt()
        if (i != lastIdx) { if (lastIdx != -1) onTick(); lastIdx = i }
    }

    private fun snap(target: Float) {
        val t = target.roundToInt().coerceIn(0, max(0, items.size - 1)).toFloat()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, t).apply {
            duration = 320; interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; tickCheck(); invalidate() }
            start()
        }
    }

    fun move(d: Int) { snap(pos.roundToInt() + d.toFloat()) }
    fun launchCurrent() { items.getOrNull(pos.roundToInt())?.let(onLaunch) }

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
        override fun onLongPress(e: MotionEvent) { handled = true; items.getOrNull(pos.roundToInt())?.let(onLong) }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP && !handled) snap(pos)
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg.shader = RadialGradient(w / 2, h * .35f, max(w, h) * .8f, 0xFF30303A.toInt(), 0xFF050507.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, bg)
        if (items.isEmpty()) {
            txt.textSize = 44f; txt.alpha = 160
            emptyHint.split("\n").forEachIndexed { i, l -> c.drawText(l, w / 2, h / 2 + i * 60f, txt) }
            txt.alpha = 255; return
        }
        val s = s(); val cy = h * .42f
        val rect = RectF(0f, 0f, s, s)
        mask.shader = LinearGradient(0f, s, 0f, s * 1.6f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        for (i in items.indices.sortedByDescending { abs(it - pos) }) {
            val d = i - pos
            if (abs(d) > 5) continue
            val bmp = if (album) items[i].album else items[i].icon
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
        val cur = items[pos.roundToInt().coerceIn(0, items.size - 1)]
        txt.textSize = 58f; c.drawText(cur.label, w / 2, h * .86f, txt)
        txt.textSize = 36f; txt.alpha = 140
        c.drawText("Tap or press A to launch · long-press to edit library", w / 2, h * .86f + 60f, txt); txt.alpha = 255
    }
}
