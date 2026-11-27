package com.kivrik.game

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Çizgi roman çizim yardımcıları. Tüm ölçüler "dp" cinsindendir; GameView tuvali
 * yoğunlukla ölçeklediği için buradaki sayılar HTML sürümündeki CSS piksel değerleriyle aynıdır.
 */
class Ink {
    val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val rf = RectF()

    fun stroke(color: Int, w: Float, round: Boolean = false): Paint {
        sp.color = color
        sp.strokeWidth = w
        sp.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        sp.pathEffect = null
        return sp
    }

    fun fill(color: Int): Paint {
        fp.color = color
        fp.shader = null
        return fp
    }

    // ---- şekiller ----
    fun rrFill(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        rf.set(l, t, r, b)
        c.drawRoundRect(rf, rad, rad, fill(color))
    }

    fun rrStroke(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, w: Float) {
        rf.set(l, t, r, b)
        c.drawRoundRect(rf, rad, rad, stroke(color, w))
    }

    /** CSS "border-box": tüm dikdörtgen dolgulu, kenarlık içeride. */
    fun bordered(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, fillColor: Int, border: Float = 3.5f) {
        rrFill(c, l, t, r, b, rad, Pal.INK)
        val ir = max0(rad - border)
        rrFill(c, l + border, t + border, r - border, b - border, ir, fillColor)
    }

    /** Kalın kenarlık + alttan düşen sert gölge (comic-btn / comic-pill / comic-panel). */
    fun comicBox(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, fillColor: Int,
        border: Float = 3.5f, shadow: Float = 5f, pressed: Boolean = false, alpha: Float = 1f
    ) {
        val sh = if (pressed) 1f else shadow
        val dy = if (pressed) shadow - 1f else 0f
        if (alpha < 1f) c.saveLayerAlpha(l - 6f, t - 6f, r + 6f, b + shadow + 6f, (alpha * 255).toInt())
        rrFill(c, l, t + dy + sh, r, b + dy + sh, rad, Pal.INK)
        bordered(c, l, t + dy, r, b + dy, rad, fillColor, border)
        if (alpha < 1f) c.restore()
    }

    private fun max0(v: Float) = if (v < 0f) 0f else v

    // ---- yazı ----
    fun measure(s: String, size: Float): Float {
        tp.textSize = size
        return tp.measureText(s)
    }

    /** y = satırın dikey ortası. */
    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, outline: Int = 0, lw: Float = 0f
    ) {
        tp.textSize = size
        tp.textAlign = align
        val fm = tp.fontMetrics
        val by = y - (fm.ascent + fm.descent) / 2f
        if (lw > 0f) {
            tp.style = Paint.Style.STROKE
            tp.strokeJoin = Paint.Join.ROUND
            tp.strokeWidth = lw
            tp.color = if (outline != 0) outline else Pal.INK
            c.drawText(s, x, by, tp)
        }
        tp.style = Paint.Style.FILL
        tp.color = color
        c.drawText(s, x, by, tp)
        tp.textAlign = Paint.Align.CENTER
    }

    // ---- emoji'li ("zengin") yazı ----
    fun richWidth(s: String, size: Float): Float {
        var total = 0f
        tp.textSize = size
        for ((part, key) in Glyphs.splitRich(s)) total += if (key != null) size * 1.15f else tp.measureText(part)
        return total
    }

    /** Ortalanmış yazı; içindeki emojiler çizgi film ikonu olarak çizilir. lw > 0 ise ink konturlu. */
    fun rich(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, lw: Float = 0f) {
        val parts = Glyphs.splitRich(s)
        val iw = size * 1.15f
        tp.textSize = size
        var total = 0f
        for ((part, key) in parts) total += if (key != null) iw else tp.measureText(part)
        var x = cx - total / 2f
        for ((part, key) in parts) {
            if (key != null) {
                Glyphs.drawEmoji(c, key, x, cy - iw / 2f, iw)
                x += iw
            } else if (part.isNotEmpty()) {
                text(c, part, x, cy, size, color, Paint.Align.LEFT, Pal.INK, lw)
                tp.textSize = size
                x += tp.measureText(part)
            }
        }
    }

    /** Sola yaslı zengin yazı. */
    fun richLeft(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int) {
        rich(c, s, x + richWidth(s, size) / 2f, cy, size, color, 0f)
    }
}
