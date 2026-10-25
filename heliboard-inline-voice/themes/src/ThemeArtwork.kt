// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

/** Original, deterministic vector artwork. No downloaded images, animation or fake effects. */
class ThemeArtwork(private val spec: ThemeSpec) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    private var filter: ColorFilter? = null
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val count = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 1000f, bounds.height() / 400f)
        paint.alpha = opacity
        paint.colorFilter = filter
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 1000f, 400f,
            Color.parseColor(spec.background), Color.parseColor(spec.secondary), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1000f, 400f, paint)
        paint.shader = null
        paint.color = Color.parseColor(spec.decoration)
        paint.alpha = opacity * (if (spec.pattern == "solid") 0 else 55) / 255
        paint.strokeWidth = 2.3f
        when (spec.pattern) {
            "grid", "circuit", "terminal" -> {
                paint.style = Paint.Style.STROKE
                for (x in 0..1000 step 45) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 400f, paint)
                for (y in 0..400 step 40) canvas.drawLine(0f, y.toFloat(), 1000f, y.toFloat(), paint)
                if (spec.pattern == "circuit") for (i in 0..15) {
                    val x = (i * 67 % 1000).toFloat(); val y = (i * 43 % 380).toFloat()
                    canvas.drawCircle(x, y, 8f, paint)
                    canvas.drawLine(x, y, x + 35, y + 35, paint)
                }
                if (spec.pattern == "terminal") {
                    paint.style = Paint.Style.FILL; paint.textSize = 21f; paint.typeface = Typeface.MONOSPACE
                    for (i in 0..8) canvas.drawText(listOf("{ }", "</>", "0 1", "=>", "[ ]")[i % 5], (i * 137 % 920).toFloat(), (i * 71 % 340 + 30).toFloat(), paint)
                    paint.typeface = null
                }
            }
            "waves", "dunes", "aurora" -> {
                for (i in 0..6) {
                    path.reset(); path.moveTo(-100f, 40f + i * 65)
                    path.cubicTo(250f, -80f + i * 65, 580f, 260f + i * 30, 1100f, 10f + i * 70)
                    if (spec.pattern == "dunes") {
                        path.lineTo(1100f, 450f); path.lineTo(-100f, 450f); path.close()
                        paint.style = Paint.Style.FILL; paint.alpha = opacity * 16 / 255
                    } else { paint.style = Paint.Style.STROKE; paint.strokeWidth = if (spec.pattern == "aurora") 19f else 3f }
                    canvas.drawPath(path, paint)
                }
            }
            "leaves", "bamboo", "fern" -> {
                for (i in 0..12) {
                    val x = (i * 89 % 1020).toFloat(); val y = (i * 79 % 460 - 40).toFloat()
                    val c = canvas.save(); canvas.rotate((i * 37 % 140 - 70).toFloat(), x, y)
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(x, y - 90, x, y + 100, paint)
                    paint.style = Paint.Style.FILL
                    for (j in -2..2) {
                        val h = y + j * 32
                        canvas.drawOval(x - 48, h - 19, x, h + 5, paint)
                        if (spec.pattern != "bamboo") canvas.drawOval(x, h - 3, x + 48, h + 21, paint)
                    }
                    canvas.restoreToCount(c)
                }
            }
            "flowers", "sakura", "daisy", "lotus" -> {
                for (i in 0..11) {
                    val x = (i * 113 % 1050).toFloat(); val y = (i * 83 % 470 - 35).toFloat()
                    val petals = when (spec.pattern) { "daisy" -> 12; "lotus" -> 8; else -> 5 }
                    for (p in 0 until petals) {
                        val c = canvas.save(); canvas.rotate(p * 360f / petals + i * 17, x, y)
                        canvas.drawOval(x - 12, y - 50, x + 12, y - 4, paint); canvas.restoreToCount(c)
                    }
                    canvas.drawCircle(x, y, 9f, paint)
                }
            }
            "mountains" -> {
                for (i in 0..7) {
                    val x = i * 170f - 100
                    path.reset(); path.moveTo(x, 420f); path.lineTo(x + 170, (i % 3 * 35 + 35).toFloat()); path.lineTo(x + 340, 420f); path.close()
                    canvas.drawPath(path, paint)
                }
            }
            "stars", "dots", "rain" -> {
                for (i in 0..80) {
                    val x = (i * 137 % 1000).toFloat(); val y = (i * 61 % 400).toFloat()
                    if (spec.pattern == "rain") canvas.drawLine(x, y, x - 15, y + 30, paint)
                    else canvas.drawCircle(x, y, if (spec.pattern == "dots") 4f else (i % 3 + 1).toFloat(), paint)
                }
            }
            "hex", "triangles", "diamonds" -> {
                paint.style = Paint.Style.STROKE
                val sides = if (spec.pattern == "hex") 6 else if (spec.pattern == "triangles") 3 else 4
                for (i in 0..15) for (j in 0..5) {
                    val x = i * 83f + (j % 2) * 40; val y = j * 80f
                    path.reset()
                    for (p in 0..sides) {
                        val a = p * 2 * PI / sides
                        val px = x + 43 * cos(a).toFloat(); val py = y + 43 * sin(a).toFloat()
                        if (p == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            "rings" -> { paint.style = Paint.Style.STROKE; for (i in 1..14) canvas.drawCircle(800f, 70f, i * 38f, paint) }
            "stripes" -> { paint.style = Paint.Style.STROKE; for (i in -5..20) canvas.drawLine(i * 85f, 0f, i * 85f - 300, 400f, paint) }
        }
        canvas.restoreToCount(count)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    companion object {
        val patterns = setOf("solid", "gradient", "grid", "circuit", "terminal", "waves", "dunes", "aurora", "leaves", "bamboo", "fern", "flowers", "sakura", "daisy", "lotus", "mountains", "stars", "dots", "rain", "hex", "triangles", "diamonds", "rings", "stripes")
    }
}
