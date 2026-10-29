package com.example.coverflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import kotlin.math.max

// Top bar: clock + date, battery with the percentage inside, and the Bluetooth popup
class Hud(private val view: View) {
    var battery = 100
    var charging = false
    var timeText = ""
    var dateText = ""

    private val cyan = 0xFF4DE8FF.toInt()
    private val purple = 0xFFA35CFF.toInt()
    private val green = 0xFF4ADE80.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val bt = Path()
    private var toastName: String? = null
    private var toastP = 0f
    private var toastAnim: ValueAnimator? = null

    fun toast(name: String) {
        toastName = name
        toastAnim?.cancel()
        toastAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3800
            addUpdateListener { toastP = it.animatedValue as Float; view.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (toastP >= 1f) { toastName = null; view.invalidate() }
                }
            })
            start()
        }
    }

    fun draw(c: Canvas, w: Float, h: Float) {
        val u = h / 720f
        val cy = 84f * u
        val left = drawBattery(c, w, cy, u)
        drawClock(c, left, cy, u)
        drawToast(c, w, cy, u)
    }

    private fun drawBattery(c: Canvas, w: Float, cy: Float, u: Float): Float {
        val bw = 92f * u
        val bh = 46f * u
        val right = w - 52f * u
        val body = RectF(right - bw, cy - bh / 2, right, cy + bh / 2)
        val lvl = battery.coerceIn(0, 100)
        line.shader = null; line.color = Color.argb(230, 255, 255, 255); line.strokeWidth = 3f * u
        c.drawRoundRect(body, 10f * u, 10f * u, line)
        p.shader = null; p.color = Color.argb(230, 255, 255, 255)
        c.drawRoundRect(RectF(right + 3f * u, cy - 9f * u, right + 10f * u, cy + 9f * u), 3f * u, 3f * u, p)
        val inner = RectF(body.left + 6f * u, body.top + 6f * u, body.left + 6f * u + (bw - 12f * u) * lvl / 100f, body.bottom - 6f * u)
        if (inner.width() > 1f) {
            val low = lvl <= 15 && !charging
            p.shader = if (low) {
                LinearGradient(body.left, 0f, body.right, 0f, 0xFFFF5A5A.toInt(), 0xFFFF3B3B.toInt(), Shader.TileMode.CLAMP)
            } else if (charging) {
                LinearGradient(body.left, 0f, body.right, 0f, cyan, green, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(body.left, 0f, body.right, 0f, cyan, purple, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(inner, 6f * u, 6f * u, p)
            p.shader = null
        }
        tp.textAlign = Paint.Align.CENTER; tp.textSize = 28f * u; tp.color = Color.WHITE; tp.alpha = 255
        tp.setShadowLayer(3f * u, 0f, 1f * u, Color.BLACK)
        c.drawText(lvl.toString(), body.centerX(), cy + 10f * u, tp)
        tp.clearShadowLayer()
        return body.left
    }

    private fun drawClock(c: Canvas, left: Float, cy: Float, u: Float) {
        if (timeText.isEmpty()) return
        val textRight = left - 36f * u
        tp.textAlign = Paint.Align.RIGHT; tp.color = Color.WHITE; tp.textSize = 38f * u; tp.alpha = 255
        c.drawText(timeText, textRight, cy - 2f * u, tp)
        val tw = tp.measureText(timeText)
        tp.textSize = 22f * u; tp.alpha = 170
        val dw = tp.measureText(dateText)
        c.drawText(dateText, textRight, cy + 26f * u, tp)
        val ix = textRight - max(tw, dw) - 40f * u
        line.shader = null; line.color = Color.argb(230, 255, 255, 255); line.strokeWidth = 3f * u
        c.drawCircle(ix, cy, 22f * u, line)
        line.strokeCap = Paint.Cap.ROUND
        c.drawLine(ix, cy, ix, cy - 13f * u, line)
        c.drawLine(ix, cy, ix + 9f * u, cy + 5f * u, line)
        line.strokeCap = Paint.Cap.BUTT
    }

    private fun drawBt(c: Canvas, cx: Float, cy: Float, s: Float, a: Int) {
        val pts = floatArrayOf(-.4f, -.3f, .4f, .3f, 0f, .7f, 0f, -.7f, .4f, -.3f, -.4f, .3f)
        bt.reset()
        for (i in 0 until 6) {
            val x = cx + pts[i * 2] * s
            val y = cy + pts[i * 2 + 1] * s
            if (i == 0) bt.moveTo(x, y) else bt.lineTo(x, y)
        }
        line.shader = null; line.color = Color.argb(a, 77, 232, 255); line.strokeWidth = s * .16f
        line.strokeCap = Paint.Cap.ROUND; line.strokeJoin = Paint.Join.ROUND
        c.drawPath(bt, line)
        line.strokeCap = Paint.Cap.BUTT; line.strokeJoin = Paint.Join.MITER
    }

    private fun drawToast(c: Canvas, w: Float, cy: Float, u: Float) {
        val name = toastName ?: return
        val pr = toastP
        val e = (if (pr < .1f) pr / .1f else if (pr > .9f) (1f - pr) / .1f else 1f).coerceIn(0f, 1f)
        val a = (255 * e).toInt()
        tp.textAlign = Paint.Align.LEFT; tp.textSize = 34f * u; tp.color = Color.WHITE; tp.alpha = a
        var n = name
        while (n.length > 3 && tp.measureText("$n Connected") > w * .5f) n = n.dropLast(1)
        val shown = (if (n.length < name.length) "$n…" else n) + " Connected"
        val tw = tp.measureText(shown)
        val ph = 70f * u
        val icon = 40f * u
        val pad = 28f * u
        val pw = pad + icon + 18f * u + tw + pad
        val x0 = (w - pw) / 2
        val y0 = cy - ph / 2 - (1f - e) * 120f * u
        val r = RectF(x0, y0, x0 + pw, y0 + ph)
        p.shader = null; p.color = Color.argb((235 * e).toInt(), 12, 20, 48)
        c.drawRoundRect(r, ph / 2, ph / 2, p)
        line.shader = LinearGradient(r.left, r.top, r.right, r.bottom, cyan, purple, Shader.TileMode.CLAMP)
        line.strokeWidth = 12f * u; line.alpha = (45 * e).toInt()
        c.drawRoundRect(r, ph / 2, ph / 2, line)
        line.strokeWidth = 2.5f * u; line.alpha = a
        c.drawRoundRect(r, ph / 2, ph / 2, line)
        line.shader = null
        drawBt(c, x0 + pad + icon / 2, r.centerY(), icon * .7f, a)
        tp.alpha = a
        c.drawText(shown, x0 + pad + icon + 18f * u, r.centerY() + 12f * u, tp)
    }
}
