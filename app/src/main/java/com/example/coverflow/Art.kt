package com.example.coverflow

import android.graphics.*
import android.graphics.drawable.Drawable

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
