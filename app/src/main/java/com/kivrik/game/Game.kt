package com.kivrik.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class Mode { READY, PLAYING, PAUSED, BONKED, TREE }

class Pt(var x: Int, var y: Int) {
    fun copy() = Pt(x, y)
}

class Dir(val x: Int, val y: Int)

class Apple(
    var x: Int, var y: Int,
    val isGolden: Boolean, val isGem: Boolean,
    var hp: Int, val maxHp: Int
) {
    var hitFlash = 0f
    var poison = 0
    var popScale = 0.1f
    val bobOffset = Random.nextFloat() * (Math.PI * 2).toFloat()
    var pull = 0f
    var dead = false
}

class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val r: Float, val color: Int, val stroke: Boolean, var life: Float, val decay: Float
)

class FloatText(var x: Float, var y: Float, val text: String, val color: Int, val size: Float, var life: Float)

class Bolt(val pts: List<FloatArray>, var life: Float)

/** Oyun motoru: HTML sürümündeki tüm kuralların birebir Kotlin karşılığı. */
class Game(val st: GameState, private val host: Host) {

    interface Host {
        fun save()
        fun toast(msg: String)
        fun openTree(fromDeath: Boolean)
        fun closeTree()
    }

    // ---- genel durum ----
    var mode = Mode.READY
    var gridSize = 12
    var newRecord = false
    var lastRunMsg = ""
    var recordMsg = ""

    var snake = ArrayList<Pt>()
    var prevSnake = ArrayList<Pt>()
    var dir = Dir(1, 0)
    private val inputQueue = ArrayList<Dir>()
    var startRow = 6
    var startCol = 2

    val apples = ArrayList<Apple>()
    val particles = ArrayList<Particle>()
    val floatTexts = ArrayList<FloatText>()
    val bolts = ArrayList<Bolt>()

    var runApples = 0L
    var runEatenCount = 0
    var cushionsLeft = 0
    var wrapsLeft = 0
    var ghostLeft = 0
    var guardMax = 0
    var phoenixLeft = 0
    private var goldRushLeft = 0
    private var orchardTimer = 0
    private var lightningTimer = 0
    private var lastManualTick = -99
    var ghostSteps = 0
    private var tickCount = 0
    private var lastEatTick = -99
    private var combo = 0
    private var slowTicks = 0
    private var trimPending = 0
    private var growPending = 0

    var eatSquashTimer = 0f
    var bonkTimer = 0f
    var bonkWord = "KÜT!"
    var bonkX = 6
    var bonkY = 6
    var screenShake = 0f

    private var tickAccum = 0f
    var bgDirty = true
    var speedTipOn = false
    var inputLockUntil = 0L
    private var lastLockedMsg = 0L

    fun lvl(id: String) = st.lvl(id)

    // ---- yetenek yardımcıları ----
    fun isVisible(s: Skill): Boolean = s.parent == null || lvl(s.parent) > 0

    fun cost(s: Skill): Long {
        val l = lvl(s.id)
        if (l >= s.maxLevel) return Long.MAX_VALUE
        val base = s.costs.getOrElse(l) { s.costs.last() }
        return max(1L, ceil(base * (1.0 - 0.04 * lvl("discount"))).toLong())
    }

    fun affordableCount(): Int =
        Tree.ALL.count { isVisible(it) && lvl(it.id) < it.maxLevel && cost(it) <= st.apples }

    fun canWrap() = wrapsLeft > 0
    fun canCushion() = cushionsLeft > 0
    fun canGhost() = ghostLeft > 0

    private fun gridSizeNow() = 12 + lvl("wideField") * 3

    fun tickRate(): Float {
        val gear = lvl("speedGear")
        var base = 4.6f + gear * 0.9f
        if (gear > 0) {
            if (st.speedMode == "slow") base *= 0.72f
            else if (st.speedMode == "turbo") base *= 1.42f
        }
        if (slowTicks > 0) base *= 0.7f
        return base
    }

    private fun maxApplesOnBoard(): Int {
        val hasVertical = lvl("down") > 0 || lvl("up") > 0
        return (if (hasVertical) 2 else 3) + lvl("moreApples")
    }

    fun isDirUnlocked(dx: Int, dy: Int): Boolean {
        if (dx == 1 && dy == 0) return true
        if (dx == 0 && dy == 1) return lvl("down") > 0
        if (dx == 0 && dy == -1) return lvl("up") > 0
        if (dx == -1 && dy == 0) return lvl("left") > 0
        return false
    }

    fun magnetRadius(): Float = floatArrayOf(0f, 1f, 1.5f, 2f).getOrElse(lvl("magnet")) { 0f }

    // ---- yeni tur ----
    fun startRun() {
        host.closeTree()
        st.runs++
        gridSize = gridSizeNow()
        startRow = gridSize / 2
        startCol = 2

        snake = arrayListOf(Pt(startCol, startRow), Pt(startCol - 1, startRow), Pt(startCol - 2, startRow))
        prevSnake = ArrayList(snake.map { it.copy() })
        dir = Dir(1, 0)
        inputQueue.clear()

        apples.clear(); particles.clear(); floatTexts.clear(); bolts.clear()
        runApples = 0
        runEatenCount = 0
        guardMax = max(1, lvl("startKit"))
        cushionsLeft = if (lvl("wallCushion") > 0) guardMax else 0
        wrapsLeft = if (lvl("wallWrap") > 0) guardMax else 0
        ghostLeft = if (lvl("ghostTail") > 0) guardMax else 0
        phoenixLeft = lvl("phoenix")
        goldRushLeft = 0; orchardTimer = 0; lightningTimer = 0; lastManualTick = -99
        ghostSteps = 0
        tickCount = 0; lastEatTick = -99; combo = 0; slowTicks = 0; trimPending = 0
        growPending = 0
        eatSquashTimer = 0f
        bonkTimer = 0f
        screenShake = 0f
        tickAccum = 0f
        newRecord = false
        bgDirty = true
        mode = Mode.READY

        spawnInitialApples()
        val intLvl = lvl("interest")
        if (intLvl > 0 && st.runs > 1) {
            val bonus = min(floor(st.apples * 0.004 * intLvl).toLong(), 1500L * intLvl)
            if (bonus > 0) {
                st.apples += bonus
                st.totalApplesCollected += bonus
                floatText(startCol, max(0, startRow - 1), "🏦 Faiz +${fmt(bonus)}", Pal.c("#86efac"), 17f)
            }
        }
        speedTipOn = lvl("speedGear") > 0 && !st.speedTipDone
        host.save()
    }

    fun dismissSpeedTip() {
        if (st.speedTipDone || lvl("speedGear") <= 0) return
        st.speedTipDone = true
        speedTipOn = false
        host.save()
    }

    // ---- elma doğurma ----
    private fun isOccupied(x: Int, y: Int): Boolean =
        snake.any { it.x == x && it.y == y } || apples.any { it.x == x && it.y == y }

    private fun luck(p: Double): Double =
        min(0.95, p * (1 + doubleArrayOf(0.0, 0.05, 0.10, 0.20).getOrElse(lvl("luckyClover")) { 0.0 }))

    private fun appleBaseHp(): Int {
        val div = 4 + lvl("softApples") * 2
        return min(100, 1 + floor((runEatenCount.toDouble() / div).pow(0.8)).toInt())
    }

    private fun createApple(x: Int, y: Int, forceGolden: Boolean = false): Apple {
        val golden = forceGolden || Random.nextDouble() < luck(lvl("goldenApple") * 0.03)
        val gem = !golden && Random.nextDouble() < luck(lvl("gemApple") * 0.01)
        var hp = appleBaseHp()
        if (golden) hp *= 2
        if (gem) hp *= 3
        return Apple(x, y, golden, gem, hp, hp)
    }

    private fun spawnInitialApples() {
        val hasDown = lvl("down") > 0
        val hasUp = lvl("up") > 0
        val hasLeft = lvl("left") > 0
        val target = maxApplesOnBoard()

        if (!hasDown && !hasUp) {
            val cols = ArrayList<Int>()
            for (x in startCol + 2 until gridSize - 1) cols.add(x)
            val count = min(target, cols.size)
            if (count <= 0) return
            val step = max(1, cols.size / count)
            for (i in 0 until count) {
                val col = cols[min(cols.size - 1, i * step)]
                if (!isOccupied(col, startRow)) apples.add(createApple(col, startRow))
            }
            return
        }

        if (hasDown && !hasUp && !hasLeft) {
            val firstX = min(gridSize - 2, startCol + 3 + Random.nextInt(3))
            if (!isOccupied(firstX, startRow)) apples.add(createApple(firstX, startRow))
        }
        maintainApples()
    }

    private fun spawnSmartApple(): Boolean {
        if (apples.size >= maxApplesOnBoard() + 12) return false
        val hasDown = lvl("down") > 0
        val hasUp = lvl("up") > 0
        val hasLeft = lvl("left") > 0
        val hasWrap = lvl("wallWrap") > 0
        val head = snake[0]

        for (attempt in 0 until 80) {
            val x: Int
            val y: Int
            if (!hasDown && !hasUp) {
                y = startRow
                val minX = min(gridSize - 1, head.x + 1)
                if (minX >= gridSize) return false
                x = minX + Random.nextInt(gridSize - minX)
            } else if (hasDown && !hasUp && !hasLeft && !hasWrap) {
                val minX = min(gridSize - 1, head.x)
                val minY = min(gridSize - 1, head.y)
                x = minX + Random.nextInt(gridSize - minX)
                y = minY + Random.nextInt(gridSize - minY)
            } else if (hasDown && hasUp && !hasLeft && !hasWrap) {
                val minX = min(gridSize - 1, head.x)
                x = minX + Random.nextInt(gridSize - minX)
                y = Random.nextInt(gridSize)
            } else {
                x = Random.nextInt(gridSize)
                y = Random.nextInt(gridSize)
            }
            if (!isOccupied(x, y)) {
                apples.add(createApple(x, y))
                return true
            }
        }
        return false
    }

    private fun maintainApples() {
        val target = maxApplesOnBoard()
        var guard = 0
        while (apples.size < target && guard < 12) {
            if (!spawnSmartApple()) break
            guard++
        }
    }

    // ---- efektler ----
    private fun spawnDust(gx: Int, gy: Int, count: Int = 5) {
        repeat(count) {
            val a = Random.nextFloat() * (Math.PI * 2).toFloat()
            val sp = 0.8f + Random.nextFloat() * 1.4f
            particles.add(
                Particle(
                    gx + 0.5f, gy + 0.5f, kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp,
                    4f + Random.nextFloat() * 5f, Pal.CREAM, true, 1f, 2.4f
                )
            )
        }
    }

    private fun spawnBurst(gx: Int, gy: Int, color: Int, count: Int = 10) {
        repeat(count) {
            val a = Random.nextFloat() * (Math.PI * 2).toFloat()
            val sp = 1.6f + Random.nextFloat() * 2.8f
            particles.add(
                Particle(
                    gx + 0.5f, gy + 0.5f, kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp,
                    3.5f + Random.nextFloat() * 4.5f, color, true, 1f, 1.9f
                )
            )
        }
    }

    fun floatText(gx: Int, gy: Int, text: String, color: Int = Pal.c("#facc15"), size: Float = 18f) {
        if (floatTexts.size > 30) floatTexts.removeAt(0)
        floatTexts.add(FloatText(gx + 0.5f, gy + 0.2f, text, color, size, 1f))
    }

    // ---- tehlike / yön ----
    private fun isCellDangerous(nx: Int, ny: Int): String? {
        val out = nx < 0 || nx >= gridSize || ny < 0 || ny >= gridSize
        if (out && !canWrap()) return "wall"
        if (ghostSteps <= 0 && !canGhost()) {
            val wx = (nx + gridSize) % gridSize
            val wy = (ny + gridSize) % gridSize
            for (i in 0 until snake.size - 1) {
                if (snake[i].x == wx && snake[i].y == wy) return "self"
            }
        }
        return null
    }

    private fun findSafeUnlockedDir(head: Pt): Dir? {
        val cands = listOf(Dir(0, -1), Dir(0, 1), Dir(-1, 0), Dir(1, 0)).filter {
            isDirUnlocked(it.x, it.y) &&
                !(it.x == -dir.x && it.y == -dir.y) &&
                !(it.x == dir.x && it.y == dir.y)
        }
        var best: Dir? = null
        var bestOpen = -1
        for (c in cands) {
            val nx = head.x + c.x
            val ny = head.y + c.y
            if (isCellDangerous(nx, ny) == null) {
                val open = if (isCellDangerous(nx + c.x, ny + c.y) != null) 1 else 2
                if (open > bestOpen) { bestOpen = open; best = c }
            }
        }
        return best
    }

    // ---- hasar sistemi ----
    private fun magnetDamage(): Int {
        val base = 1 + lvl("sharpTeeth")
        val pct = doubleArrayOf(0.0, 0.10, 0.25, 0.50).getOrElse(lvl("magnet")) { 0.10 }
        return max(1, ceil(base * pct - 1e-9).toInt()) + lvl("magnetPower")
    }

    private fun applyMagnet() {
        val radius = magnetRadius()
        if (radius <= 0f || snake.isEmpty()) return
        val head = snake[0]
        val gm = lvl("goldenMagnet")
        for (a in apples.toList()) {
            if (a.dead) continue
            val r = radius * (if (gm > 0 && (a.isGolden || a.isGem)) 1f + gm * 0.5f else 1f)
            if (hypot((head.x - a.x).toDouble(), (head.y - a.y).toDouble()) <= r) hitApple(a, magnetDamage(), "magnet", false)
        }
    }

    private class Bite(val d: Int, val crit: Boolean)

    private fun biteDamage(): Bite {
        var d = 1 + lvl("sharpTeeth")
        var crit = false
        if (Random.nextDouble() < luck(lvl("critBite") * 0.06)) { d *= 3; crit = true }
        return Bite(d, crit)
    }

    private fun killApple(a: Apple, src: String) {
        if (a.dead) return
        a.dead = true
        apples.remove(a)
        onEatApple(a, src)
    }

    private fun hitApple(a: Apple, dmg0: Int, src: String, crit: Boolean): Boolean {
        if (a.dead) return false
        var dmg = dmg0
        val ex = lvl("executioner")
        if (ex > 0 && a.hp - dmg > 0 && (a.hp - dmg).toDouble() / a.maxHp <= ex * 0.1) dmg = a.hp
        a.hp -= dmg
        a.hitFlash = 1f
        if (a.hp <= 0) { killApple(a, src); return true }
        if (src == "bite") {
            val v = lvl("venom")
            if (v > 0) a.poison = 2 + v * 2
        }
        if (src == "bite" || src == "lightning" || src == "shock" || src == "blast") {
            floatText(
                a.x, a.y, if (crit) "💥${fmt(dmg)}" else "-${fmt(dmg)}",
                if (crit) Pal.c("#fb923c") else Pal.c("#ffffff"), if (crit) 19f else 14f
            )
            Sfx.hit()
        }
        return false
    }

    private fun addBolt(x1: Int, y1: Int, x2: Int, y2: Int) {
        val pts = ArrayList<FloatArray>()
        pts.add(floatArrayOf(x1 + 0.5f, y1 + 0.5f))
        val seg = 5
        for (i in 1 until seg) {
            val t = i.toFloat() / seg
            pts.add(
                floatArrayOf(
                    x1 + 0.5f + (x2 - x1) * t + (Random.nextFloat() - 0.5f) * 0.6f,
                    y1 + 0.5f + (y2 - y1) * t + (Random.nextFloat() - 0.5f) * 0.6f
                )
            )
        }
        pts.add(floatArrayOf(x2 + 0.5f, y2 + 0.5f))
        bolts.add(Bolt(pts, 1f))
        if (bolts.size > 30) bolts.removeAt(0)
    }

    private fun strikeLightning(count: Int) {
        val l = lvl("lightning")
        if (l <= 0) return
        val dmg = 1 + l
        val chain = lvl("chainLightning")
        for (n in 0 until count) {
            if (apples.isEmpty()) break
            val target = apples[Random.nextInt(apples.size)]
            val sx = target.x
            val sy = target.y
            addBolt(sx, -1, sx, sy)
            hitApple(target, dmg, "lightning", false)
            if (chain > 0) {
                var lx = sx
                var ly = sy
                val used = HashSet<Apple>()
                used.add(target)
                for (c in 0 until chain) {
                    var best: Apple? = null
                    var bd = 1e9
                    for (a in apples) {
                        if (a.dead || used.contains(a)) continue
                        val d = hypot((a.x - lx).toDouble(), (a.y - ly).toDouble())
                        if (d < bd && d <= 5) { bd = d; best = a }
                    }
                    val b = best ?: break
                    used.add(b)
                    val bx = b.x
                    val by = b.y
                    addBolt(lx, ly, bx, by)
                    hitApple(b, max(1, ceil(dmg * 0.6).toInt()), "lightning", false)
                    lx = bx; ly = by
                }
            }
        }
        screenShake = max(screenShake, 3f)
        Sfx.zap()
    }

    // ---- ana adım ----
    private fun gameTick() {
        if (mode != Mode.PLAYING) return

        tickCount++
        if (slowTicks > 0) slowTicks--
        if (trimPending > 0) {
            val target = max(4, snake.size - trimPending)
            while (snake.size > target) snake.removeAt(snake.size - 1)
            trimPending = 0
        }
        prevSnake = ArrayList(snake.map { it.copy() })
        if (ghostSteps > 0) ghostSteps--

        if (inputQueue.isNotEmpty()) {
            val next = inputQueue.removeAt(0)
            if (isDirUnlocked(next.x, next.y) && !(next.x == -dir.x && next.y == -dir.y)) {
                if (next.x != dir.x || next.y != dir.y) {
                    dir = next
                    spawnDust(snake[0].x, snake[0].y, 5)
                    Sfx.turn()
                }
            }
        }

        val head = snake[0]
        var nx = head.x + dir.x
        var ny = head.y + dir.y
        var danger = isCellDangerous(nx, ny)

        // Duvar Yastığı
        if (danger == "wall" && canCushion()) {
            val safe = findSafeUnlockedDir(head)
            if (safe != null) {
                cushionsLeft--
                dir = safe
                nx = head.x + dir.x
                ny = head.y + dir.y
                danger = null
                screenShake = 5f
                spawnBurst(head.x, head.y, Pal.c("#60a5fa"), 10)
                floatText(head.x, head.y, "BOİNG! 🛡️", Pal.c("#60a5fa"), 17f)
                Sfx.bounce()
                val sc = lvl("shockCushion")
                if (sc > 0) {
                    for (a in apples.toList()) hitApple(a, sc * 2, "shock", false)
                    floatText(head.x, max(0, head.y - 1), "🔔 ŞOK!", Pal.c("#fbbf24"), 16f)
                }
            }
        }

        // Anka Kuşu
        if (danger != null && phoenixLeft > 0) {
            phoenixLeft--
            ghostSteps = 6
            danger = null
            screenShake = 6f
            spawnBurst(head.x, head.y, Pal.c("#fb923c"), 14)
            floatText(head.x, head.y, "🔥 ANKA KUŞU!", Pal.c("#fb923c"), 18f)
            Sfx.bounce()
        }

        if (danger != null) {
            triggerBonk(head.x, head.y)
            return
        }

        // Hak tüketimi: portal ve hayalet kuyruk
        if (nx < 0 || nx >= gridSize || ny < 0 || ny >= gridSize) {
            if (canWrap()) {
                wrapsLeft--
                if (wrapsLeft <= 0) bgDirty = true
                floatText(head.x, head.y, "🌀 PORTAL!", Pal.c("#60a5fa"), 16f)
                Sfx.pop()
                val pl = lvl("portalLoot")
                if (pl > 0) {
                    val loot = max(1L, Math.round(pl * 2.0 * (1 + lvl("appleValue")) * (1 + 0.15 * lvl("globalBoost"))))
                    st.apples += loot
                    st.totalApplesCollected += loot
                    runApples += loot
                    floatText(head.x, max(0, head.y - 1), "💰 +${fmt(loot)}", Pal.c("#fde047"), 15f)
                }
            }
        }
        if (ghostSteps <= 0) {
            val gx = (nx + gridSize) % gridSize
            val gy = (ny + gridSize) % gridSize
            var hit = false
            for (i in 0 until snake.size - 1) if (snake[i].x == gx && snake[i].y == gy) { hit = true; break }
            if (hit) {
                ghostLeft--
                ghostSteps = 4
                floatText(head.x, head.y, "👻 HAYALET!", Pal.c("#c4b5fd"), 16f)
                Sfx.pop()
            }
        }

        val finalX = (nx + gridSize) % gridSize
        val finalY = (ny + gridSize) % gridSize

        snake.add(0, Pt(finalX, finalY))
        if (growPending > 0) {
            growPending--
            prevSnake.add(prevSnake[prevSnake.size - 1].copy())
        } else {
            snake.removeAt(snake.size - 1)
        }

        // Isırık
        for (a in apples.toList()) {
            if (!a.dead && a.x == finalX && a.y == finalY) {
                val b = biteDamage()
                hitApple(a, b.d, "bite", b.crit)
            }
        }

        // Gövde Isırığı
        val bb = lvl("bodyBite")
        if (bb > 0 && apples.isNotEmpty()) {
            val segs = snake.drop(1).take(bb * 3)
            val bodyDmg = 1 + lvl("sharpTeeth") / 2
            for (a in apples.toList()) {
                if (a.dead) continue
                if (segs.any { it.x == a.x && it.y == a.y }) hitApple(a, bodyDmg, "body", false)
            }
        }

        applyMagnet()

        // Zehir
        if (lvl("venom") > 0) {
            for (a in apples.toList()) {
                if (!a.dead && a.poison > 0) {
                    a.poison--
                    hitApple(a, 1, "poison", false)
                }
            }
        }

        // Yıldırım
        val lgt = lvl("lightning")
        if (lgt > 0) {
            lightningTimer++
            if (lightningTimer >= intArrayOf(0, 45, 36, 29, 23, 18)[lgt]) {
                lightningTimer = 0
                strikeLightning(1)
            }
        }

        // Fidan
        val orc = lvl("orchard")
        if (orc > 0) {
            orchardTimer++
            if (orchardTimer >= intArrayOf(0, 20, 15, 10)[orc]) {
                orchardTimer = 0
                if (apples.size < maxApplesOnBoard() + 8) spawnSmartApple()
            }
        }

        if (lvl("down") > 0 || lvl("up") > 0) maintainApples()
    }

    private fun regenRight(kind: String, x: Int, y: Int) {
        if (kind == "cushion") {
            if (cushionsLeft >= guardMax) return
            cushionsLeft++
        } else {
            if (wrapsLeft >= guardMax) return
            if (wrapsLeft <= 0) bgDirty = true
            wrapsLeft++
        }
        floatText(x, y, if (kind == "cushion") "🛡️ +1" else "🌀 +1", Pal.c("#93c5fd"), 16f)
    }

    private fun onEatApple(apple: Apple, src: String) {
        runEatenCount++
        eatSquashTimer = 1f

        var v = (1 + lvl("appleValue")).toDouble()
        if (apple.isGolden) v *= 5 + lvl("goldenValue") * 2
        if (apple.isGem) v *= 20
        if (Random.nextDouble() < luck(lvl("doubleTaste") * 0.08)) v *= 2
        val comboLvl = lvl("comboChain")
        if (comboLvl > 0) {
            combo = if (tickCount - lastEatTick <= 8 + lvl("comboSaver") * 4) combo + 1 else 1
            lastEatTick = tickCount
            if (combo >= 3) v += comboLvl * (combo / 3)
        }
        if (ghostSteps > 0 && lvl("ghostFeast") > 0) v *= 1 + 0.5 * lvl("ghostFeast")
        val lb = lvl("longBody")
        if (lb > 0) v *= 1 + min(snake.size, 60) * 0.01 * lb
        if (src == "lightning") v *= 1 + 0.25 * lvl("lightningBounty")
        if (goldRushLeft > 0) { v *= 2; goldRushLeft-- }
        if (apple.isGolden && lvl("goldRush") > 0) goldRushLeft = 1 + lvl("goldRush") * 2
        if (lvl("speedGear") > 0 && st.speedMode == "turbo") v *= 1 + doubleArrayOf(0.0, 0.1, 0.2, 0.5)[lvl("speedGear")]
        v *= 1 + 0.15 * lvl("globalBoost")
        val hm = lvl("harvestMoon")
        var moon = 0.0
        if (hm > 0 && runEatenCount % 25 == 0) {
            moon = v * 3 * hm
            floatText(apple.x, max(0, apple.y - 1), "🌕 HASAT AYI!", Pal.c("#fde68a"), 17f)
        }
        val value = max(1L, Math.round(v + moon))

        st.apples += value
        st.totalApplesCollected += value
        runApples += value

        val cr = lvl("cushionRegen")
        if (cr > 0 && lvl("wallCushion") > 0 && runEatenCount % (24 - cr * 4) == 0) regenRight("cushion", snake[0].x, snake[0].y)
        val pr = lvl("portalRegen")
        if (pr > 0 && lvl("wallWrap") > 0 && runEatenCount % (36 - pr * 6) == 0) regenRight("wrap", snake[0].x, snake[0].y)
        slowTicks = lvl("slowMo") * 2
        if (apple.isGolden && lvl("tailTrim") > 0) trimPending += lvl("tailTrim")

        val tailDivisor = 1 + lvl("shortTail")
        if (runEatenCount % tailDivisor == 0) growPending += 1

        val special = apple.isGolden || apple.isGem
        spawnBurst(
            apple.x, apple.y,
            if (apple.isGem) Pal.c("#22d3ee") else if (apple.isGolden) Pal.c("#facc15") else Pal.c("#ef4444"),
            if (special) 16 else 10
        )
        floatText(
            apple.x, apple.y, "+${fmt(value)} 🍎",
            if (apple.isGem) Pal.c("#67e8f9") else if (apple.isGolden) Pal.c("#fde047") else Pal.c("#ffffff"),
            if (special) 20f else 17f
        )
        Sfx.eat(special)

        val exl = lvl("explosive")
        if (exl > 0) {
            var any = false
            for (o in apples.toList()) {
                if (o.dead) continue
                if (hypot((o.x - apple.x).toDouble(), (o.y - apple.y).toDouble()) <= 1.5) { any = true; hitApple(o, exl, "blast", false) }
            }
            if (any) { spawnBurst(apple.x, apple.y, Pal.c("#fb923c"), 8); Sfx.boom() }
        }

        val spl = lvl("splitApple")
        if (spl > 0 && Random.nextDouble() < luck(0.04 * spl)) {
            spawnSmartApple()
            spawnSmartApple()
            floatText(apple.x, max(0, apple.y - 1), "🧬 ÇOĞALDI!", Pal.c("#86efac"), 15f)
        }

        val stc = lvl("stormCharge")
        if (stc > 0 && apple.isGolden && src != "lightning") strikeLightning(stc)

        val rainLvl = lvl("appleRain")
        if (rainLvl > 0 && runEatenCount % 8 == 0) {
            repeat(rainLvl * 3) { spawnSmartApple() }
            floatText(snake[0].x, snake[0].y, "🌧️ ELMA YAĞMURU!", Pal.c("#38bdf8"), 18f)
            Sfx.rain()
        }

        host.save()
    }

    private fun triggerBonk(hx: Int, hy: Int) {
        mode = Mode.BONKED
        bonkTimer = 0.85f
        bonkWord = if (Random.nextBoolean()) "KÜT!" else "BOİNG!"
        bonkX = hx
        bonkY = hy
        var bonus = 0L
        val consLvl = lvl("consolation")
        if (consLvl > 0 && runApples > 0) {
            bonus = ceil(runApples * 0.12 * consLvl).toLong()
            st.apples += bonus
        }
        if (runApples > st.bestRun) { st.bestRun = runApples; newRecord = true }
        lastRunMsg = if (runApples > 0)
            "Son tur: +${fmt(runApples)} 🍎${if (bonus > 0) " (+${fmt(bonus)} ödül)" else ""}"
        else "Çite çarptın! Tekrar dene!"
        recordMsg = if (newRecord) "🏆 YENİ REKOR!" else ""
        screenShake = 11f
        spawnBurst(hx, hy, Pal.c("#facc15"), 14)
        spawnBurst(hx, hy, Pal.c("#ef4444"), 10)
        Sfx.bonk()
        host.save()
    }

    // ---- girdi ----
    fun resumePlay(now: Long) {
        if (mode != Mode.READY && mode != Mode.PAUSED) return
        if (now < inputLockUntil) return
        dismissSpeedTip()
        mode = Mode.PLAYING
        tickAccum = 0f
    }

    fun togglePause(now: Long) {
        if (mode == Mode.PLAYING) mode = Mode.PAUSED else resumePlay(now)
    }

    fun autoPause() {
        if (mode == Mode.PLAYING) mode = Mode.PAUSED
    }

    /** "ok" | "rev" | "locked" | "ignored" */
    fun queueDirection(dx: Int, dy: Int, now: Long): String {
        val waiting = mode == Mode.READY || mode == Mode.PAUSED
        if (mode != Mode.PLAYING && !waiting) return "ignored"
        if (waiting && now < inputLockUntil) return "ignored"
        if (!isDirUnlocked(dx, dy)) {
            if (now - lastLockedMsg > 3000) {
                lastLockedMsg = now
                Sfx.nope()
                host.toast("🔒 Bu yön henüz kilitli! Yetenek Ağacından aç!")
            }
            return "locked"
        }
        if (waiting) resumePlay(now)
        lastManualTick = tickCount
        val last = if (inputQueue.isNotEmpty()) inputQueue[inputQueue.size - 1] else dir
        if (dx == -last.x && dy == -last.y) return "rev"
        if (dx == last.x && dy == last.y) return "ok"
        if (inputQueue.size < 2) inputQueue.add(Dir(dx, dy)) else inputQueue[1] = Dir(dx, dy)
        return "ok"
    }

    // ---- yetenek satın alma ----
    fun buyOnce(id: String): Boolean {
        val s = Tree.MAP[id] ?: return false
        if (!isVisible(s)) return false
        val l = lvl(id)
        if (l >= s.maxLevel) return false
        val c = cost(s)
        if (st.apples < c) return false
        st.apples -= c
        st.skills[id] = l + 1
        return true
    }

    // ---- kare güncellemesi ----
    fun interp(): Float {
        if (mode != Mode.PLAYING) return 1f
        val ti = 1f / tickRate()
        return min(1f, tickAccum / ti)
    }

    fun update(dt: Float, now: Long) {
        if (mode == Mode.TREE) return

        if (mode == Mode.PLAYING) {
            val tickInterval = 1f / tickRate()
            tickAccum += dt
            var safety = 0
            while (tickAccum >= tickInterval && safety < 3) {
                gameTick()
                tickAccum -= tickInterval
                safety++
                if (mode != Mode.PLAYING) break
            }
            if (safety >= 3) tickAccum = 0f
        } else if (mode == Mode.BONKED) {
            bonkTimer -= dt
            if (bonkTimer <= 0f) {
                if (!st.treeIntroSeen) {
                    st.treeIntroSeen = true
                    host.save()
                    host.openTree(true)
                } else {
                    startRun()
                    inputLockUntil = now + 900
                }
            }
        }

        if (eatSquashTimer > 0f) eatSquashTimer = max(0f, eatSquashTimer - dt * 4.5f)
        if (screenShake > 0f) screenShake = max(0f, screenShake - dt * 28f)

        val magR = magnetRadius()
        val gmL = lvl("goldenMagnet")
        val mHead = if (magR > 0f && mode == Mode.PLAYING && snake.isNotEmpty()) snake[0] else null
        for (a in apples) {
            val rr = magR * (if (gmL > 0 && (a.isGolden || a.isGem)) 1f + gmL * 0.5f else 1f)
            val inField = mHead != null && hypot((mHead.x - a.x).toDouble(), (mHead.y - a.y).toDouble()) <= rr
            a.pull += ((if (inField) 1f else 0f) - a.pull) * min(1f, dt * 12f)
            if (a.popScale < 1f) a.popScale = min(1f, a.popScale + dt * 5.5f)
            if (a.hitFlash > 0f) a.hitFlash = max(0f, a.hitFlash - dt * 5f)
        }
        val bi = bolts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.life -= dt * 4f
            if (b.life <= 0f) bi.remove()
        }
        val pi = particles.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.92f
            p.vy *= 0.92f
            p.life -= p.decay * dt
            if (p.life <= 0f) pi.remove()
        }
        while (particles.size > 160) particles.removeAt(0)
        val fi = floatTexts.iterator()
        while (fi.hasNext()) {
            val ft = fi.next()
            ft.y -= dt * 1.4f
            ft.life -= dt * 1.25f
            if (ft.life <= 0f) fi.remove()
        }
    }
}
