package com.kivrik.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Çit + bahçe + elma + yılan çizimi (HTML <canvas> kodunun Android Canvas karşılığı). Ölçüler dp. */
class Board(private val ink: Ink, private val density: Float) {

    private var bg: Bitmap? = null
    private var bgSize = 0
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val cInk = Pal.INK
    private val cCream = Pal.CREAM
    private val cGrass1 = Pal.c("#8ee052")
    private val cGrass2 = Pal.c("#7cd142")
    private val cWood = Pal.c("#b87433")
    private val cWater = Pal.c("#60a5fa")

    private fun lerpCoord(prev: Int, curr: Int, t: Float): Float =
        if (kotlin.math.abs(curr - prev) > 1.5f) curr.toFloat() else prev + (curr - prev) * t

    private fun buildBackground(g: Game, size: Float, fencePad: Float, cell: Float, boardSize: Float) {
        val px = max(1, (size * density).toInt())
        var bm = bg
        if (bm == null || bm.width != px) {
            bm?.recycle()
            bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            bg = bm
        }
        bgSize = px
        bm!!.eraseColor(0)
        val b = Canvas(bm)
        b.scale(density, density)

        b.drawRect(0f, 0f, size, size, ink.fill(if (g.canWrap()) cWater else cWood))

        val plank = size / 12f
        val sp = ink.stroke(cInk, 2.5f)
        for (i in 1 until 12) {
            val p = i * plank
            b.drawLine(p, 0f, p, fencePad, sp)
            b.drawLine(p, size - fencePad, p, size, sp)
            b.drawLine(0f, p, fencePad, p, sp)
            b.drawLine(size - fencePad, p, size, p, sp)
        }

        b.save()
        b.translate(fencePad, fencePad)
        for (y in 0 until g.gridSize) {
            for (x in 0 until g.gridSize) {
                b.drawRect(
                    x * cell, y * cell, x * cell + cell + 0.6f, y * cell + cell + 0.6f,
                    ink.fill(if ((x + y) % 2 == 0) cGrass1 else cGrass2)
                )
            }
        }
        rect.set(0f, 0f, boardSize, boardSize)
        b.drawRect(rect, ink.stroke(cInk, 3.5f))
        b.restore()
        g.bgDirty = false
    }

    fun draw(c: Canvas, g: Game, st: GameState, size: Float, now: Long) {
        val fencePad = max(12f, floor(size * 0.036f))
        val boardSize = size - fencePad * 2
        val cell = boardSize / g.gridSize
        val interp = g.interp()

        c.save()
        if (g.screenShake > 0.2f) {
            c.translate((Math.random().toFloat() - 0.5f) * g.screenShake, (Math.random().toFloat() - 0.5f) * g.screenShake)
        }

        val px = max(1, (size * density).toInt())
        if (g.bgDirty || bg == null || bgSize != px) buildBackground(g, size, fencePad, cell, boardSize)
        rect.set(0f, 0f, size, size)
        c.drawBitmap(bg!!, null, rect, bgPaint)

        c.save()
        c.translate(fencePad, fencePad)

        val magLvl = g.lvl("magnet")
        val snake = g.snake
        val prev = g.prevSnake

        // Mıknatıs halkası
        if (magLvl > 0 && snake.isNotEmpty() && g.mode == Mode.PLAYING) {
            val h = snake[0]
            val ph = prev.getOrNull(0) ?: h
            val hx = (lerpCoord(ph.x, h.x, interp) + 0.5f) * cell
            val hy = (lerpCoord(ph.y, h.y, interp) + 0.5f) * cell
            val r = g.magnetRadius() * cell
            val p = ink.stroke(Pal.a(Pal.c("#ffffff"), 0.55f), 2.5f)
            p.pathEffect = DashPathEffect(floatArrayOf(7f, 7f), (now * 0.04f) % 14f)
            c.drawCircle(hx, hy, r, p)
            p.pathEffect = null
        }

        var magHx = 0f
        var magHy = 0f
        var magOn = false
        if (magLvl > 0 && snake.isNotEmpty()) {
            val h0 = snake[0]
            val p0 = prev.getOrNull(0) ?: h0
            magHx = (lerpCoord(p0.x, h0.x, interp) + 0.5f) * cell
            magHy = (lerpCoord(p0.y, h0.y, interp) + 0.5f) * cell
            magOn = true
        }

        for (a in g.apples) drawApple(c, a, cell, now, magOn, magHx, magHy)

        drawSnake(c, g, interp, cell, now)

        // Yıldırımlar
        for (b in g.bolts) {
            val al = max(0f, b.life)
            path.reset()
            b.pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt[0] * cell, pt[1] * cell) else path.lineTo(pt[0] * cell, pt[1] * cell) }
            c.drawPath(path, ink.stroke(Pal.a(cInk, al), 9f, true))
            c.drawPath(path, ink.stroke(Pal.a(Pal.c("#fde047"), al), 4.5f, true))
            c.drawPath(path, ink.stroke(Pal.a(Pal.c("#ffffff"), al), 1.8f, true))
        }

        // Parçacıklar
        for (p in g.particles) {
            val al = max(0f, p.life)
            val rr = max(1f, p.r * p.life)
            c.drawCircle(p.x * cell, p.y * cell, rr, ink.fill(Pal.a(p.color, al)))
            if (p.stroke) c.drawCircle(p.x * cell, p.y * cell, rr, ink.stroke(Pal.a(cInk, al), 2f))
        }

        // Uçan metinler
        for (ft in g.floatTexts) {
            val al = max(0f, min(1f, ft.life))
            val w = ink.richWidth(ft.text, ft.size)
            val x = ft.x * cell
            val y = ft.y * cell
            c.saveLayerAlpha(x - w / 2 - 12, y - ft.size - 6, x + w / 2 + 12, y + ft.size + 6, (al * 255).toInt())
            ink.rich(c, ft.text, x, y, ft.size, ft.color, 5f)
            c.restore()
        }

        if (g.mode == Mode.BONKED) {
            drawBonk(c, g.bonkX * cell + cell * 0.5f, g.bonkY * cell + cell * 0.5f, boardSize, g.bonkWord, now)
        }

        if (g.mode == Mode.READY || g.mode == Mode.PAUSED) {
            val big = max(14f, floor(boardSize / 24f))
            val small = max(12f, floor(boardSize / 30f))
            val msg = if (g.mode == Mode.READY) "Başlamak için yön tuşuna bas!" else "⏸ Duraklatıldı — devam için dokun"
            ink.rich(c, msg, boardSize / 2, boardSize * 0.2f, big, cCream, 6f)
            if (g.mode == Mode.READY && g.lastRunMsg.isNotEmpty()) ink.rich(c, g.lastRunMsg, boardSize / 2, boardSize * 0.28f, small, Pal.c("#fde68a"), 5f)
            if (g.mode == Mode.READY && g.recordMsg.isNotEmpty()) ink.rich(c, g.recordMsg, boardSize / 2, boardSize * 0.35f, small + 2, Pal.c("#facc15"), 5f)
            if (g.mode == Mode.READY && !st.swipeUsed) {
                ink.rich(c, "Ekranda parmağını kaydırarak da yönlendir!", boardSize / 2, boardSize * 0.7f, small, cCream, 5f)
                drawSwipeHint(c, now, boardSize)
            }
        }
        c.restore()
        c.restore()
    }

    private fun drawApple(c: Canvas, a: Apple, cell: Float, now: Long, magOn: Boolean, magHx: Float, magHy: Float) {
        var ax = (a.x + 0.5f) * cell
        var ay = (a.y + 0.5f) * cell + sin(now * 0.006f + a.bobOffset) * 2f
        if (magOn && a.pull > 0.01f) {
            val k = a.pull * 0.5f
            ax += (magHx - ax) * k + sin(now * 0.05f + a.bobOffset) * a.pull * cell * 0.04f
            ay += (magHy - ay) * k
        }
        val hitK = a.hitFlash
        val sizeBoost = 1f + min(0.3f, (a.maxHp - 1) * 0.035f) + hitK * 0.14f
        val r = cell * 0.36f * a.popScale * sizeBoost
        val special = a.isGolden || a.isGem

        // Gölge
        rect.set(ax - r * 0.78f, ay + r * 0.85f - r * 0.28f, ax + r * 0.78f, ay + r * 0.85f + r * 0.28f)
        c.drawOval(rect, ink.fill(Pal.a(cInk, 0.22f)))

        // Parıltı yıldızları
        if (special) {
            c.save()
            c.translate(ax, ay)
            c.rotate((now * 0.003f) * 180f / PI.toFloat())
            val col = if (a.isGem) Pal.c("#a5f3fc") else Pal.c("#fef08a")
            for (k in 0 until 4) {
                c.rotate(90f)
                path.reset()
                path.moveTo(0f, -r * 1.45f)
                path.lineTo(r * 0.22f, -r * 0.95f)
                path.lineTo(-r * 0.22f, -r * 0.95f)
                path.close()
                c.drawPath(path, ink.fill(col))
            }
            c.restore()
        }

        // Zehir halkası
        if (a.poison > 0) {
            val p = ink.stroke(Pal.c("#84cc16"), 3f)
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
            c.drawCircle(ax, ay, r * 1.22f, p)
            p.pathEffect = null
        }

        val lw = max(2.2f, cell * 0.085f)
        val body = if (a.isGem) Pal.c("#22d3ee") else if (a.isGolden) Pal.c("#facc15") else Pal.c("#ef4444")
        c.drawCircle(ax, ay, r, ink.fill(body))
        c.drawCircle(ax, ay, r, ink.stroke(cInk, lw))

        if (hitK > 0f) c.drawCircle(ax, ay, r, ink.fill(Pal.a(Pal.c("#ffffff"), hitK * 0.7f)))

        // Işık yansıması
        c.save()
        c.translate(ax - r * 0.32f, ay - r * 0.28f)
        c.rotate(-0.6f * 180f / PI.toFloat())
        rect.set(-r * 0.24f, -r * 0.14f, r * 0.24f, r * 0.14f)
        c.drawOval(rect, ink.fill(Pal.c("#ffffff")))
        c.restore()

        // Sap + yaprak
        path.reset()
        path.moveTo(ax, ay - r * 0.85f)
        path.quadTo(ax + r * 0.15f, ay - r * 1.3f, ax + r * 0.28f, ay - r * 1.25f)
        c.drawPath(path, ink.stroke(cInk, lw))

        c.save()
        c.translate(ax + r * 0.34f, ay - r * 0.98f)
        c.rotate(-0.45f * 180f / PI.toFloat())
        rect.set(-r * 0.32f, -r * 0.17f, r * 0.32f, r * 0.17f)
        c.drawOval(rect, ink.fill(Pal.c("#22c55e")))
        c.drawOval(rect, ink.stroke(cInk, lw))
        c.restore()

        // Can
        if (a.maxHp > 1) {
            val fs = max(10f, floor(cell * 0.3f))
            ink.text(c, fmt(a.hp), ax, ay + r * 0.12f, fs, Pal.c("#ffffff"), Paint.Align.CENTER, cInk, 4f)

            val bw = cell * 0.78f
            val bh = max(4f, cell * 0.11f)
            val bx = ax - bw / 2
            val by = ay - r * 1.5f - bh
            val ratio = max(0f, min(1f, a.hp.toFloat() / a.maxHp))
            c.drawRect(bx, by, bx + bw, by + bh, ink.fill(cCream))
            c.drawRect(
                bx, by, bx + bw * ratio, by + bh,
                ink.fill(if (ratio > 0.5f) Pal.c("#4ade80") else if (ratio > 0.25f) Pal.c("#facc15") else Pal.c("#ef4444"))
            )
            c.drawRect(bx, by, bx + bw, by + bh, ink.stroke(cInk, 2f))
        }
    }

    private fun drawSnake(c: Canvas, g: Game, interp: Float, cell: Float, now: Long) {
        val snake = g.snake
        if (snake.isEmpty()) return
        val dir = g.dir
        val isGhost = g.ghostSteps > 0
        val n = snake.size
        val ptsX = FloatArray(n)
        val ptsY = FloatArray(n)
        for (i in 0 until n) {
            val seg = snake[i]
            val pv = g.prevSnake.getOrNull(i) ?: seg
            ptsX[i] = (lerpCoord(pv.x, seg.x, interp) + 0.5f) * cell
            ptsY[i] = (lerpCoord(pv.y, seg.y, interp) + 0.5f) * cell
        }
        val hx = ptsX[0]
        val hy = ptsY[0]

        // Elmaya yakınsa dil
        if (g.mode == Mode.PLAYING && g.apples.isNotEmpty()) {
            var minDist = Double.MAX_VALUE
            for (a in g.apples) {
                val d = hypot((snake[0].x - a.x).toDouble(), (snake[0].y - a.y).toDouble())
                if (d < minDist) minDist = d
            }
            if (minDist < 3.6 || sin(now * 0.01) > 0.72) {
                val tongueLen = cell * (0.45f + sin(now * 0.03f) * 0.12f)
                val tx = hx + dir.x * (cell * 0.38f)
                val ty = hy + dir.y * (cell * 0.38f)
                val tipX = tx + dir.x * tongueLen
                val tipY = ty + dir.y * tongueLen
                val perpX = -dir.y * cell * 0.14f
                val perpY = dir.x * cell * 0.14f
                path.reset()
                path.moveTo(tx, ty); path.lineTo(tipX, tipY)
                path.moveTo(tipX, tipY); path.lineTo(tipX + dir.x * cell * 0.14f + perpX, tipY + dir.y * cell * 0.14f + perpY)
                path.moveTo(tipX, tipY); path.lineTo(tipX + dir.x * cell * 0.14f - perpX, tipY + dir.y * cell * 0.14f - perpY)
                c.drawPath(path, ink.stroke(Pal.c("#ef4444"), max(3f, cell * 0.1f), true))
            }
        }

        if (isGhost) c.saveLayerAlpha(null, 140)
        val lw = max(2.6f, cell * 0.095f)
        for (i in n - 1 downTo 0) {
            val isHead = i == 0
            val taper = if (isHead) 1.06f else max(0.62f, 1f - (i.toFloat() / (n + 5)) * 0.36f)
            val squash = if (g.eatSquashTimer > 0f && i <= 2) 1f + sin(g.eatSquashTimer * PI.toFloat()) * (if (isHead) 0.22f else 0.15f) else 1f
            val r = cell * 0.43f * taper * squash
            val x = ptsX[i]
            val y = ptsY[i]

            rect.set(x - r * 0.85f, y + r * 0.85f - r * 0.3f, x + r * 0.85f, y + r * 0.85f + r * 0.3f)
            c.drawOval(rect, ink.fill(Pal.a(cInk, 0.18f)))

            c.drawCircle(x, y, r, ink.fill(if (isHead) Pal.c("#34d35c") else if (i % 2 == 0) Pal.c("#2ec954") else Pal.c("#26b84a")))
            c.drawCircle(x, y, r, ink.stroke(cInk, lw))

            if (!isHead && i % 2 == 1) c.drawCircle(x, y + r * 0.12f, r * 0.38f, ink.fill(Pal.c("#fde047")))
        }
        if (isGhost) c.restore()

        // Gözler
        val eyeSpread = cell * 0.21f
        val eyeForward = cell * 0.14f
        val eyeR = cell * 0.21f
        val e1x: Float; val e1y: Float; val e2x: Float; val e2y: Float
        if (dir.x != 0) {
            e1x = hx + dir.x * eyeForward; e2x = e1x
            e1y = hy - eyeSpread; e2y = hy + eyeSpread
        } else {
            e1y = hy + dir.y * eyeForward; e2y = e1y
            e1x = hx - eyeSpread; e2x = hx + eyeSpread
        }

        var lookX = dir.x.toFloat()
        var lookY = dir.y.toFloat()
        if (g.apples.isNotEmpty() && g.mode == Mode.PLAYING) {
            var nearest = g.apples[0]
            var minD = Double.MAX_VALUE
            for (a in g.apples) {
                val d = hypot((a.x - snake[0].x).toDouble(), (a.y - snake[0].y).toDouble())
                if (d < minD) { minD = d; nearest = a }
            }
            val ang = atan2((nearest.y - snake[0].y).toDouble(), (nearest.x - snake[0].x).toDouble())
            lookX = cos(ang).toFloat()
            lookY = sin(ang).toFloat()
        }

        val eyeLw = max(2f, cell * 0.075f)
        for (k in 0 until 2) {
            val ex = if (k == 0) e1x else e2x
            val ey = if (k == 0) e1y else e2y
            c.drawCircle(ex, ey, eyeR, ink.fill(Pal.c("#ffffff")))
            c.drawCircle(ex, ey, eyeR, ink.stroke(cInk, eyeLw))
            if (g.mode == Mode.BONKED) {
                c.save()
                c.translate(ex, ey)
                c.rotate(now * 0.018f * 180f / PI.toFloat())
                path.reset()
                var a = 0f
                var first = true
                val total = PI.toFloat() * 3.5f
                while (a < total) {
                    val sr = (a / total) * (eyeR * 0.72f)
                    val sx = cos(a) * sr
                    val sy = sin(a) * sr
                    if (first) { path.moveTo(sx, sy); first = false } else path.lineTo(sx, sy)
                    a += 0.35f
                }
                c.drawPath(path, ink.stroke(cInk, 2f))
                c.restore()
            } else {
                val ppx = ex + lookX * eyeR * 0.34f
                val ppy = ey + lookY * eyeR * 0.34f
                c.drawCircle(ppx, ppy, eyeR * 0.48f, ink.fill(cInk))
                c.drawCircle(ppx - eyeR * 0.14f, ppy - eyeR * 0.14f, eyeR * 0.18f, ink.fill(Pal.c("#ffffff")))
            }
        }

        if (g.mode == Mode.BONKED) {
            for (s in 0 until 3) {
                val ang = now * 0.01f + (s * PI.toFloat() * 2f) / 3f
                val sx = hx + cos(ang) * cell * 0.65f
                val sy = hy - cell * 0.35f + sin(ang) * cell * 0.25f
                drawMiniStar(c, sx, sy, cell * 0.19f, Pal.c("#facc15"))
            }
        }
    }

    private fun drawMiniStar(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.45f
            val a = (i * PI.toFloat()) / 5f - PI.toFloat() / 2f
            val px = x + cos(a) * rad
            val py = y + sin(a) * rad
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, ink.fill(color))
        c.drawPath(path, ink.stroke(cInk, 2f))
    }

    private fun drawBonk(c: Canvas, x: Float, y: Float, boardSize: Float, word: String, now: Long) {
        val cx = max(70f, min(boardSize - 70f, x))
        val cy = max(55f, min(boardSize - 55f, y - 16f))
        val scale = 1f + sin(now * 0.03f) * 0.08f
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        val spikes = 12
        path.reset()
        for (i in 0 until spikes * 2) {
            val r = if (i % 2 == 0) 54f else 34f
            val a = (i * PI.toFloat()) / spikes
            val px = cos(a) * r
            val py = sin(a) * r * 0.72f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, ink.fill(Pal.c("#facc15")))
        c.drawPath(path, ink.stroke(cInk, 4f))
        ink.text(c, word, 0f, 2f, 24f, Pal.c("#ef4444"), Paint.Align.CENTER, cInk, 6f)
        c.restore()
    }

    private fun drawSwipeHint(c: Canvas, now: Long, bs: Float) {
        val t = (now % 2000L) / 2000f
        val e = if (t < 0.7f) 0.5f - cos(t / 0.7f * PI.toFloat()) / 2f else 1f
        val y = bs * 0.8f
        val x0 = bs * 0.3f
        val x1 = bs * 0.7f
        val r = bs * 0.05f
        val al = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
        val xe = x0 + (x1 - x0) * e
        c.saveLayerAlpha(0f, y - r - 8, bs, y + r + 8, (al.coerceIn(0f, 1f) * 255).toInt())
        val p = ink.stroke(Pal.a(Pal.c("#fff8e7"), 0.85f), r * 0.5f, true)
        p.pathEffect = DashPathEffect(floatArrayOf(r * 0.6f, r * 1.1f), 0f)
        c.drawLine(x0, y, xe, y, p)
        p.pathEffect = null
        c.drawCircle(xe, y, r, ink.fill(cCream))
        c.drawCircle(xe, y, r, ink.stroke(cInk, 4f))
        c.drawCircle(xe, y, r * 0.45f, ink.fill(Pal.c("#facc15")))
        c.restore()
    }
}
