package com.example.coverflow

import android.graphics.*
import android.graphics.drawable.Drawable

const val SETTINGS_PKG = "__settings__"

// Big gradient gear tile that sits first in the row
fun makeSettingsArt(): Pair<Bitmap, Bitmap> {
    fun draw(size: Int, round: Boolean): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size.toFloat()
        if (round) c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * .2f, s * .2f, Path.Direction.CW) })
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(0f, 0f, s, s, intArrayOf(0xFF4DE8FF.toInt(), 0xFF2F7BFF.toInt(), 0xFFA35CFF.toInt()), null, Shader.TileMode.CLAMP)
        })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.save(); c.translate(s / 2, s / 2)
        for (i in 0 until 8) {
            c.drawRoundRect(RectF(-s * .07f, -s * .37f, s * .07f, -s * .2f), s * .02f, s * .02f, white)
            c.rotate(45f)
        }
        c.drawCircle(0f, 0f, s * .26f, white)
        c.drawCircle(0f, 0f, s * .1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14214F.toInt() })
        c.restore()
        return b
    }
    return draw(256, true) to draw(320, false)
}

// Returns (normal rounded icon, album-cover square)
fun makeArt(d: Drawable, label: String): Pair<Bitmap, Bitmap> {
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
