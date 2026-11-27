package com.kivrik.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tüm arayüzü (HUD, tahta, yön tuşları, yetenek ağacı, onay penceresi) tek bir özel View içinde
 * Canvas ile çizen native ekran. WebView / HTML yok. Tuval density ile ölçeklenir; ölçüler dp'dir.
 */
class GameView(context: Context) : View(context), Game.Host {

    private class R {
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        fun set(l: Float, t: Float, r: Float, b: Float): R { this.l = l; this.t = t; this.r = r; this.b = b; return this }
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2f
        val cy get() = (t + b) / 2f
        fun has(x: Float, y: Float) = x >= l && x <= r && y >= t && y <= b
    }

    private var st: GameState = Store.load(context)
    private var game: Game = Game(st, this)
    private val ink = Ink()
    private val dens = resources.displayMetrics.density
    private val board = Board(ink, dens)

    private var W = 360f
    private var H = 640f

    // ---- yerleşim ----
    private val btn = LinkedHashMap<String, R>()
    private val stage = R()
    private val canvasRect = R()
    private val logo = R()
    private var statRowY = 60f
    private var titleX = 0f
    private val vp = R()
    private val panel = R()
    private val tipRect = R()

    // ---- basılı/animasyon durumu ----
    private val pressedPtr = HashMap<Int, String>()
    private val pressedUntil = HashMap<String, Long>()
    private val shakeUntil = HashMap<String, Long>()
    private val nodeAnim = HashMap<String, Pair<Char, Long>>()

    // ---- bildirim ----
    private var toastText = ""
    private var toastStart = 0L

    // ---- onay penceresi ----
    private var confirmOpen = false

    // ---- yetenek ağacı ----
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var selected = "down"
    private var treeSummary = ""
    private var hintVisible = true
    private var needCenter = false
    private val ZMIN = 0.4f
    private val ZMAX = 1.8f

    private var swipeX = 0f
    private var swipeY = 0f
    private var swipePtr = -1

    private var panning = false
    private var panPtr = -1
    private var panLastX = 0f
    private var panLastY = 0f
    private var panTotal = 0f
    private var downNode: String? = null
    private var longFired = false
    private var pinchDist = 0f
    private var pinching = false
    private var longRunnable: Runnable? = null
    private var lastBuyBtnDown = false
    private val slop = 8f

    private val vib: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    } catch (e: Exception) { null }

    private val hasVib = vib?.hasVibrator() == true

    // Kayıt (SharedPreferences) en fazla 2,5 sn'de bir yazılır; her elmada diske gitmeyiz.
    private var saveDirty = false
    private var lastSave = 0L

    private fun flushSave() {
        Store.save(context, st)
        saveDirty = false
        lastSave = SystemClock.uptimeMillis()
    }

    private var running = false
    private var lastFrame = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // Süre, ekranın gerçek kare zamanından hesaplanır (120 Hz'de bile pürüzsüz adım)
            val dt = if (lastFrame == 0L) 0f else min(0.1f, (frameTimeNanos - lastFrame) / 1_000_000_000f)
            lastFrame = frameTimeNanos
            game.update(dt, now)
            if (saveDirty && now - lastSave > 2500) flushSave()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ============================================================================================
    // başlatma
    // ============================================================================================
    init {
        Sfx.init(context)
        Sfx.enabled = st.soundEnabled
        game.startRun()
        isFocusable = true
    }

    fun onHostResume() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun onHostPause() {
        running = false
        game.autoPause()
        Store.save(context, st)
    }

    /** true dönerse geri tuşu burada tüketildi. */
    fun handleBack(): Boolean {
        if (confirmOpen) { confirmOpen = false; return true }
        if (game.mode == Mode.TREE) { game.startRun(); return true }
        if (game.mode == Mode.PLAYING) { game.autoPause(); return true }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        W = w / dens
        H = h / dens
        needCenter = true
    }

    // ============================================================================================
    // Game.Host
    // ============================================================================================
    override fun save() {
        saveDirty = true
        if (SystemClock.uptimeMillis() - lastSave > 2500) flushSave()
    }

    override fun toast(msg: String) {
        toastText = msg
        toastStart = SystemClock.uptimeMillis()
    }

    override fun openTree(fromDeath: Boolean) {
        game.mode = Mode.TREE
        hintVisible = true
        treeSummary = if (fromDeath) {
            if (game.runApples > 0)
                "KÜT! Bu turda +${game.runApples} elma topladın!" +
                    (if (game.newRecord) " YENİ REKOR!" else " (Rekor: ${st.bestRun})") + " Yeni dallar aç!"
            else "Çite çarptın! Ağaçtaki yeteneklere göz at ve tekrar dene!"
        } else "Elmalarını harca, yeni dallar aç ve bahçeye hükmet!"

        val firstBuyable = Tree.ALL.firstOrNull { game.isVisible(it) && game.lvl(it.id) < it.maxLevel && st.apples >= game.cost(it) }
        if (firstBuyable != null) selected = firstBuyable.id
        else {
            val cur = Tree.MAP[selected]
            if (cur == null || !game.isVisible(cur)) selected = "down"
        }
        needCenter = true
    }

    override fun closeTree() {
        // mod startRun içinde READY'ye alınır
    }

    // ============================================================================================
    // yardımcılar
    // ============================================================================================
    private fun now() = SystemClock.uptimeMillis()

    private fun haptic(ms: Long) {
        if (!st.hapticsEnabled || !hasVib) return
        try {
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib?.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun hapticNope() {
        if (!st.hapticsEnabled || !hasVib) return
        try {
            if (Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 8, 40, 8), -1))
            else @Suppress("DEPRECATION") vib?.vibrate(longArrayOf(0, 8, 40, 8), -1)
        } catch (_: Exception) {}
    }

    private val layoutCache = HashMap<String, StaticLayout>()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun layoutOf(text: String, size: Float, width: Float, color: Int, maxLines: Int, center: Boolean = false): StaticLayout {
        val key = "$size|${width.toInt()}|$color|$maxLines|$center|$text"
        layoutCache[key]?.let { return it }
        if (layoutCache.size > 80) layoutCache.clear()
        val p = TextPaint(textPaint)
        p.textSize = size
        p.color = color
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, p, max(10, width.toInt()))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.12f)
            .build()
        layoutCache[key] = sl
        return sl
    }

    private fun setBtn(id: String, l: Float, t: Float, r: Float, b: Float): R {
        val rect = btn.getOrPut(id) { R() }
        return rect.set(l, t, r, b)
    }

    private fun isPressed(id: String): Boolean =
        pressedPtr.containsValue(id) || (pressedUntil[id] ?: 0L) > now()

    // ============================================================================================
    // yerleşim hesabı
    // ============================================================================================
    private val m = 12f
    private var padTop = 0f
    private var speedBarR = R()
    private var treeBtnR = R()

    // yetenek ağacı yerleşimi
    private var hdrB = 0f
    private var cardT = 0f
    private var cardB = 0f
    private var descLayout: StaticLayout? = null
    private var sumLayout: StaticLayout? = null

    private fun relayout() {
        btn.clear()
        val gm = game

        // ---- üst bar ----
        logo.set(m, 8f, m + 40f, 48f)
        titleX = m + 40f + 10f
        var bx = W - m - 40f
        setBtn("reset", bx, 8f, bx + 40f, 48f)
        if (hasVib) { bx -= 48f; setBtn("haptic", bx, 8f, bx + 40f, 48f) }
        bx -= 48f; setBtn("sound", bx, 8f, bx + 40f, 48f)
        bx -= 48f; setBtn("pause", bx, 8f, bx + 40f, 48f)
        statRowY = 60f

        // ---- tahta ----
        val stageTop = 110f
        val reserve = 10f + 12f + 45f + 16f + 109f + 18f
        var s = min(min(W - 2 * m, 540f), H - stageTop - reserve)
        s = max(s, 200f)
        s = min(s, W - 2 * m)
        stage.set((W - s) / 2f, stageTop, (W + s) / 2f, stageTop + s)
        canvasRect.set(stage.l + 13f, stage.t + 13f, stage.r - 13f, stage.b - 13f)

        // ---- alt panel ----
        val pY = stage.b + 10f + 12f
        padTop = pY
        val ac = gm.affordableCount()
        val showTree = ac > 0 || st.totalApplesCollected > 0
        val showSpeed = gm.lvl("speedGear") > 0
        val treeW = if (showTree) ink.richWidth("🌳 Yetenek Ağacı", 14f) + 28f + (if (ac > 0) 28f else 0f) else 0f
        val speedW = if (showSpeed) 3 * 38f + 2 * 4f + 14f else 0f
        val total = treeW + speedW + (if (showTree && showSpeed) 10f else 0f)
        var px = (W - total) / 2f
        if (showTree) { treeBtnR = setBtn("tree", px, pY, px + treeW, pY + 40f); px += treeW + 10f }
        if (showSpeed) {
            speedBarR = R().set(px, pY + 2f, px + speedW, pY + 38f)
            var sx = px + 7f
            for (id in arrayOf("speedSlow", "speedNormal", "speedTurbo")) {
                setBtn(id, sx, pY + 5f, sx + 38f, pY + 35f)
                sx += 42f
            }
            if (gm.speedTipOn) {
                tipRect.set(speedBarR.r - 240f, speedBarR.t - 14f - 86f, speedBarR.r, speedBarR.t - 14f)
                if (tipRect.l < 8f) tipRect.set(8f, tipRect.t, 248f, tipRect.b)
                btn["tip"] = tipRect
            }
        }

        // ---- yön tuşları ----
        val dy0 = pY + 40f + 5f + 16f
        val kw = (192f - 16f) / 3f
        val kl = (W - 192f) / 2f
        setBtn("padUp", kl + kw + 8f, dy0, kl + 2 * kw + 8f, dy0 + 48f)
        setBtn("padLeft", kl, dy0 + 56f, kl + kw, dy0 + 104f)
        setBtn("padDown", kl + kw + 8f, dy0 + 56f, kl + 2 * kw + 8f, dy0 + 104f)
        setBtn("padRight", kl + 2 * kw + 16f, dy0 + 56f, kl + 3 * kw + 16f, dy0 + 104f)

        // ---- yetenek ağacı ----
        if (gm.mode == Mode.TREE) layoutTree()

        // ---- onay penceresi ----
        if (confirmOpen) {
            btn.clear()
            val dw = min(W - 32f, 384f)
            val dl = (W - dw) / 2f
            val body = layoutOf(
                "Tüm elmaların ve açtığın yetenek ağacı sıfırlanacak, en baştan sadece sağa giderek başlayacaksın. Emin misin?",
                14f, dw - 40f, Pal.c("#231f20"), 6
            )
            val dh = 20f + 30f + 8f + body.height + 20f + 44f + 20f
            val dt = (H - dh) / 2f
            modalRect.set(dl, dt, dl + dw, dt + dh)
            val by = modalRect.b - 20f - 40f
            val cw = (dw - 40f - 10f) / 2f
            setBtn("mCancel", dl + 20f, by, dl + 20f + cw, by + 40f)
            setBtn("mConfirm", dl + 20f + cw + 10f, by, dl + 20f + 2 * cw + 10f, by + 40f)
        }
    }

    private val modalRect = R()

    private fun layoutTree() {
        btn.clear() // ağaç açıkken alttaki ana ekran tuşlarına dokunulamaz
        panel.set(10f, 10f, W - 10f, H - 10f)
        val inL = panel.l + 4f
        val inR = panel.r - 4f

        sumLayout = layoutOf(treeSummary, 11f, inR - inL - 24f, Pal.a(Pal.INK, 0.8f), 3)
        hdrB = panel.t + 4f + 8f + 36f + 4f + sumLayout!!.height + 8f

        val cl = inL + 12f
        val cr = inR - 12f
        val playB = panel.b - 4f - 14f
        setBtn("play", cl, playB - 56f, cr, playB)
        cardB = playB - 56f - 16f

        val sk = Tree.MAP[selected] ?: Tree.ALL[0]
        descLayout = layoutOf(sk.desc, 12f, (cr - cl) - 32f, Pal.a(Pal.INK, 0.85f), 7)
        val cardH = 16f + 48f + 8f + descLayout!!.height + 8f + 9f + 34f + 16f
        cardT = cardB - cardH

        vp.set(cl, hdrB + 12f, cr, cardT - 14f)
        if (vp.h < 120f) vp.b = vp.t + 120f

        setBtn("center", inR - 12f - 40f, panel.t + 4f + 8f, inR - 12f, panel.t + 4f + 8f + 32f)
        setBtn("zoomIn", vp.r - 10f - 36f, vp.t + 10f, vp.r - 10f, vp.t + 46f)
        setBtn("zoomOut", vp.r - 10f - 36f, vp.t + 52f, vp.r - 10f, vp.t + 88f)

        val buyW = 118f
        setBtn("buy", cr - 16f - buyW, cardB - 16f - 34f, cr - 16f, cardB - 16f)

        if (needCenter) {
            needCenter = false
            centerTree()
        }
    }

    private fun centerTree() {
        val sk = Tree.MAP[selected] ?: Tree.MAP.getValue("right")
        zoom = if (vp.w < 520f) 0.8f else 1f
        panX = vp.w / 2f - sk.x * zoom
        panY = vp.h * 0.55f - sk.y * zoom
    }

    // ============================================================================================
    // ÇİZİM
    // ============================================================================================
    override fun onDraw(canvas: Canvas) {
        val c = canvas
        c.save()
        c.scale(dens, dens)
        relayout()
        val t = now()

        drawBackdrop(c)
        drawHeader(c)
        drawStatRow(c)
        drawStage(c, t)
        drawControls(c, t)
        drawDpad(c, t)

        if (game.mode == Mode.TREE) drawTreeOverlay(c, t)
        if (confirmOpen) drawModal(c)
        drawToast(c, t)
        c.restore()
    }

    private var skyShader: LinearGradient? = null
    private var blob1: RadialGradient? = null
    private var blob2: RadialGradient? = null
    private var skyH = 0f
    private var skyW = 0f
    private val skyP = Paint()

    private fun drawBackdrop(c: Canvas) {
        if (skyShader == null || skyH != H || skyW != W) {
            skyH = H
            skyW = W
            skyShader = LinearGradient(
                0f, 0f, 0f, H,
                intArrayOf(Pal.c("#87e0ff"), Pal.c("#c8f4ff"), Pal.c("#b2eb88")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            blob1 = RadialGradient(W * 0.12f, H * 0.18f, W * 0.3f, Pal.a(Pal.c("#ffffff"), 0.7f), 0x00ffffff, Shader.TileMode.CLAMP)
            blob2 = RadialGradient(W * 0.88f, H * 0.14f, W * 0.32f, Pal.a(Pal.c("#ffffff"), 0.65f), 0x00ffffff, Shader.TileMode.CLAMP)
        }
        skyP.shader = skyShader
        c.drawRect(0f, 0f, W, H, skyP)
        skyP.shader = blob1
        c.drawRect(0f, 0f, W, H, skyP)
        skyP.shader = blob2
        c.drawRect(0f, 0f, W, H, skyP)
        skyP.shader = null
    }

    private fun iconBtn(c: Canvas, id: String, fill: Int, key: String, enabled: Boolean = true) {
        val r = btn[id] ?: return
        val pr = isPressed(id) && enabled
        ink.comicBox(c, r.l, r.t, r.r, r.b, 16f, fill, 3.5f, 5f, pr, if (enabled) 1f else 0.55f)
        val dy = if (pr) 4f else 0f
        Glyphs.drawEmoji(c, key, r.cx - 12f, r.cy - 12f + dy, 24f)
    }

    private val logoTail: Path by lazy { SvgPath.parse("M20 29v6M20 35l-3 3M20 35l3 3") }
    private val logoSmile: Path by lazy { SvgPath.parse("M13 27q7 4.5 14 0") }

    private fun drawHeader(c: Canvas) {
        // logo
        ink.comicBox(c, logo.l, logo.t, logo.r, logo.b, 16f, Pal.c("#4ade80"), 3.5f, 4f)
        c.save()
        val side = 40f - 7f - 2f
        c.translate(logo.l + 3.5f + 1f, logo.t + 3.5f + 1f)
        c.scale(side / 40f, side / 40f)
        c.drawPath(logoTail, ink.stroke(Pal.c("#ef4444"), 2.4f, true))
        c.drawCircle(13f, 17f, 6.6f, ink.fill(Pal.c("#ffffff")))
        c.drawCircle(13f, 17f, 6.6f, ink.stroke(Pal.INK, 2.6f))
        c.drawCircle(27f, 17f, 6.6f, ink.fill(Pal.c("#ffffff")))
        c.drawCircle(27f, 17f, 6.6f, ink.stroke(Pal.INK, 2.6f))
        c.drawCircle(14.4f, 18.4f, 2.8f, ink.fill(Pal.INK))
        c.drawCircle(25.6f, 18.4f, 2.8f, ink.fill(Pal.INK))
        c.drawCircle(15.2f, 17.4f, 0.9f, ink.fill(Pal.c("#ffffff")))
        c.drawCircle(26.4f, 17.4f, 0.9f, ink.fill(Pal.c("#ffffff")))
        c.drawPath(logoSmile, ink.stroke(Pal.INK, 2.4f, true))
        c.restore()

        // başlık
        ink.text(c, "KIVRIK", titleX + 2f, 8f + 15f + 2f, 24f, Pal.CREAM, Paint.Align.LEFT)
        ink.text(c, "KIVRIK", titleX, 8f + 15f, 24f, Pal.INK, Paint.Align.LEFT)

        // turbo etiketi
        val gear = game.lvl("speedGear")
        if (gear > 0 && st.speedMode == "turbo") {
            val label = "🐇 TURBO +%${intArrayOf(0, 10, 20, 50)[gear]}"
            val w = ink.richWidth(label, 10f) + 14f
            ink.bordered(c, titleX, 8f + 27f, titleX + w, 8f + 27f + 16f, 8f, Pal.c("#fde047"), 2f)
            ink.rich(c, label, titleX + w / 2f, 8f + 35f, 10f, Pal.INK, 0f)
        }

        // butonlar
        val canPause = game.mode == Mode.PLAYING || game.mode == Mode.PAUSED
        iconBtn(c, "pause", Pal.c("#fde68a"), if (game.mode == Mode.PAUSED) "▶" else "⏸", canPause)
        iconBtn(c, "sound", Pal.CREAM, if (st.soundEnabled) "🔊" else "🔇")
        if (hasVib) iconBtn(c, "haptic", Pal.CREAM, if (st.hapticsEnabled) "📳" else "📴")
        iconBtn(c, "reset", Pal.c("#fca5a5"), "🗑")
    }

    private fun statPill(c: Canvas, x: Float, y: Float, icon: String, text: String, fill: Int): Float {
        val tw = ink.measure(text, 14f)
        val w = 9f + 16f + 3f + tw + 9f
        ink.comicBox(c, x, y, x + w, y + 30f, 15f, fill, 3f, 4f)
        Glyphs.drawEmoji(c, icon, x + 9f, y + 15f - 8f, 16f)
        ink.text(c, text, x + 9f + 16f + 3f, y + 15f, 14f, Pal.INK, Paint.Align.LEFT)
        return w
    }

    private fun drawStatRow(c: Canvas) {
        var x = m
        val y = statRowY
        x += statPill(c, x, y, "🍎", fmt(st.apples), Pal.CREAM) + 6f
        x += statPill(c, x, y, "🧺", "+${fmt(game.runApples)}", Pal.c("#fef9c3")) + 6f
        x += statPill(c, x, y, "🏆", fmt(st.bestRun), Pal.c("#fde68a")) + 6f

        val g = game
        val chips = ArrayList<Triple<String, Int, Boolean>>()
        if (g.lvl("wallCushion") > 0) chips.add(Triple("🛡", g.cushionsLeft, g.cushionsLeft > 0))
        if (g.lvl("wallWrap") > 0) chips.add(Triple("🌀", g.wrapsLeft, g.wrapsLeft > 0))
        if (g.lvl("ghostTail") > 0) chips.add(Triple("👻", g.ghostLeft, g.ghostLeft > 0))
        if (g.lvl("phoenix") > 0) chips.add(Triple("🔥", g.phoenixLeft, g.phoenixLeft > 0))
        if (chips.isNotEmpty()) {
            var cw = 18f
            for (ch in chips) cw += 14f + 1f + ink.measure(fmt(ch.second), 12f) + 8f
            val avail = W - m - x
            if (cw > avail) cw = avail
            ink.comicBox(c, x, y, x + cw, y + 30f, 15f, Pal.c("#dbeafe"), 3f, 4f)
            c.save()
            c.clipRect(x + 3f, y, x + cw - 3f, y + 34f)
            var cx = x + 9f
            for (ch in chips) {
                val a = if (ch.third) 1f else 0.4f
                val tw = ink.measure(fmt(ch.second), 12f)
                val layered = a < 1f // yalnızca soluk (0 hak) çiplerde ara katman kullan
                if (layered) c.saveLayerAlpha(cx - 2f, y, cx + 14f + 1f + tw + 4f, y + 30f, (a * 255).toInt())
                Glyphs.drawEmoji(c, ch.first, cx, y + 15f - 7f, 14f)
                ink.text(c, fmt(ch.second), cx + 15f, y + 15f, 12f, Pal.INK, Paint.Align.LEFT)
                if (layered) c.restore()
                cx += 14f + 1f + tw + 8f
            }
            c.restore()
        }
    }

    private fun drawStage(c: Canvas, t: Long) {
        // ahşap çerçeve
        ink.rrFill(c, stage.l, stage.t + 10f, stage.r, stage.b + 10f, 26f, Pal.INK)
        ink.bordered(c, stage.l, stage.t, stage.r, stage.b, 26f, Pal.c("#c58442"), 5f)
        val sz = canvasRect.w
        c.save()
        val clip = Path()
        clip.addRoundRect(RectF(canvasRect.l, canvasRect.t, canvasRect.r, canvasRect.b), 16f, 16f, Path.Direction.CW)
        c.clipPath(clip)
        c.translate(canvasRect.l, canvasRect.t)
        board.draw(c, game, st, sz, t)
        c.restore()
    }

    private fun drawControls(c: Canvas, t: Long) {
        val g = game
        btn["tree"]?.let { r ->
            val enabled = g.mode == Mode.READY
            val ac = g.affordableCount()
            val pr = isPressed("tree") && enabled
            ink.comicBox(c, r.l, r.t, r.r, r.b, 16f, Pal.c("#4ade80"), 3.5f, 5f, pr, if (enabled) 1f else 0.55f)
            val dy = if (pr) 4f else 0f
            val textCx = if (ac > 0) r.cx - 12f else r.cx
            ink.rich(c, "🌳 Yetenek Ağacı", textCx, r.cy + dy, 14f, Pal.INK, 0f)
            if (ac > 0) {
                val label = ac.toString()
                val bw = max(20f, ink.measure(label, 12f) + 10f)
                val bx = r.r - 10f - bw
                ink.bordered(c, bx, r.cy - 10f + dy, bx + bw, r.cy + 10f + dy, 10f, Pal.CREAM, 2f)
                ink.text(c, label, bx + bw / 2f, r.cy + dy, 12f, Pal.INK)
            }
        }

        if (g.lvl("speedGear") > 0) {
            val r = speedBarR
            if (g.speedTipOn) {
                val pulse = 0.5f + 0.5f * sin(t / 1100.0 * 2 * PI).toFloat()
                ink.rrStroke(c, r.l - 5f, r.t - 5f, r.r + 5f, r.b + 5f, 22f, Pal.a(Pal.c("#facc15"), 0.4f + 0.6f * pulse), 4f)
            }
            ink.comicBox(c, r.l, r.t, r.r, r.b, 18f, Pal.CREAM, 3f, 4f)
            val items = arrayOf("speedSlow" to "🐢", "speedNormal" to "🐍", "speedTurbo" to "🐇")
            val modes = arrayOf("slow", "normal", "turbo")
            for ((i, item) in items.withIndex()) {
                val b = btn[item.first] ?: continue
                val active = st.speedMode == modes[i]
                val up = if (active) -1f else 0f
                if (active) ink.rrFill(c, b.l, b.t + up + 3f, b.r, b.b + up + 3f, 12f, Pal.INK)
                ink.bordered(c, b.l, b.t + up, b.r, b.b + up, 12f, if (active) Pal.c("#facc15") else Pal.c("#ffffff"), 2.5f)
                Glyphs.drawEmoji(c, item.second, b.cx - 10f, b.cy - 10f + up, 20f)
            }
            if (g.speedTipOn) drawSpeedTip(c, t)
        }
    }

    private fun drawSpeedTip(c: Canvas, t: Long) {
        val r = tipRect
        val bob = -4f * (0.5f + 0.5f * sin(t / 1200.0 * 2 * PI).toFloat())
        c.save()
        c.translate(0f, bob)
        ink.comicBox(c, r.l, r.t, r.r, r.b, 22f, Pal.c("#fef08a"), 3f, 5f)
        val p = Path()
        val ax = r.r - 32f
        p.moveTo(ax - 9f, r.b - 1f); p.lineTo(ax + 9f, r.b - 1f); p.lineTo(ax, r.b + 12f); p.close()
        c.drawPath(p, ink.fill(Pal.c("#fef08a")))
        val ln = Path()
        ln.moveTo(ax - 9f, r.b - 1f); ln.lineTo(ax, r.b + 12f); ln.lineTo(ax + 9f, r.b - 1f)
        c.drawPath(ln, ink.stroke(Pal.INK, 3f))
        ink.rich(c, "⚡ Hız Seçici açıldı!", r.cx, r.t + 18f, 12f, Pal.INK, 0f)
        ink.text(c, "Oyuna başlamadan önce", r.cx, r.t + 36f, 12f, Pal.INK)
        ink.text(c, "buradan hızı ayarla.", r.cx, r.t + 51f, 12f, Pal.INK)
        ink.text(c, "(kapatmak için dokun)", r.cx, r.t + 69f, 10f, Pal.a(Pal.INK, 0.7f))
        c.restore()
    }

    private fun drawDpad(c: Canvas, t: Long) {
        val keys = arrayOf(
            Triple("padUp", "↑", game.lvl("up") > 0),
            Triple("padLeft", "←", game.lvl("left") > 0),
            Triple("padDown", "↓", game.lvl("down") > 0),
            Triple("padRight", "→", true)
        )
        for ((id, arrow, unlocked) in keys) {
            val r = btn[id] ?: continue
            val sh = if ((shakeUntil[id] ?: 0L) > t) (if (((t / 45) % 2L) == 0L) -4f else 4f) else 0f
            val pr = isPressed(id) && unlocked
            val fill = if (unlocked) Pal.c("#ffdf5d") else Pal.c("#d6cfc2")
            val alpha = if (unlocked) 1f else 0.85f
            c.save()
            c.translate(sh, 0f)
            ink.comicBox(c, r.l, r.t, r.r, r.b, 16f, fill, 3.5f, if (unlocked) 5f else 3f, pr, alpha)
            val dy = if (pr) 4f else 0f
            if (unlocked) ink.text(c, arrow, r.cx, r.cy + dy, 24f, Pal.INK)
            else ink.rich(c, "$arrow 🔒", r.cx, r.cy, 18f, Pal.c("#6b635b"), 0f)
            c.restore()
        }
        val bottom = (btn["padLeft"]?.b ?: 0f) + 14f
        if (H - bottom > 22f) {
            ink.text(c, "Kaydır veya tuşlara bas", W / 2f, bottom + 8f, 12f, Pal.a(Pal.INK, 0.75f))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Toast
    // ---------------------------------------------------------------------------------------------
    private fun drawToast(c: Canvas, t: Long) {
        if (toastText.isEmpty()) return
        val p = (t - toastStart) / 2200f
        if (p >= 1f) { toastText = ""; return }
        val alpha: Float
        val ty: Float
        val sc: Float
        when {
            p < 0.15f -> { val k = p / 0.15f; alpha = k; ty = 18f * (1 - k); sc = 0.85f + 0.19f * k }
            p < 0.25f -> { val k = (p - 0.15f) / 0.10f; alpha = 1f; ty = 0f; sc = 1.04f - 0.04f * k }
            p < 0.85f -> { alpha = 1f; ty = 0f; sc = 1f }
            else -> { val k = (p - 0.85f) / 0.15f; alpha = 1f - k; ty = -12f * k; sc = 1f - 0.08f * k }
        }
        var size = 13f
        val maxW = W * 0.9f - 32f
        var w = ink.richWidth(toastText, size)
        if (w > maxW) { size *= maxW / w; w = maxW }
        val bw = w + 32f
        val bh = 36f
        val cx = W / 2f
        val top = 52f + ty
        c.save()
        c.scale(sc, sc, cx, top + bh / 2f)
        c.saveLayerAlpha(cx - bw / 2 - 8, top - 8, cx + bw / 2 + 8, top + bh + 14, (alpha.coerceIn(0f, 1f) * 255).toInt())
        ink.comicBox(c, cx - bw / 2, top, cx + bw / 2, top + bh, bh / 2, Pal.c("#fef08a"), 3f, 4f)
        ink.rich(c, toastText, cx, top + bh / 2, size, Pal.INK, 0f)
        c.restore()
        c.restore()
    }

    // ---------------------------------------------------------------------------------------------
    // Onay penceresi
    // ---------------------------------------------------------------------------------------------
    private fun drawModal(c: Canvas) {
        c.drawRect(0f, 0f, W, H, ink.fill(Pal.a(Pal.INK, 0.7f)))
        val r = modalRect
        ink.comicBox(c, r.l, r.t, r.r, r.b, 22f, Pal.CREAM, 4f, 6f)
        ink.rich(c, "🗑 Her Şeyi Sıfırla?", r.l + 20f + ink.richWidth("🗑 Her Şeyi Sıfırla?", 20f) / 2f, r.t + 20f + 15f, 20f, Pal.INK, 0f)
        val body = layoutOf(
            "Tüm elmaların ve açtığın yetenek ağacı sıfırlanacak, en baştan sadece sağa giderek başlayacaksın. Emin misin?",
            14f, r.w - 40f, Pal.c("#231f20"), 6
        )
        c.save()
        c.translate(r.l + 20f, r.t + 20f + 30f + 8f)
        body.draw(c)
        c.restore()
        for ((id, label, fill) in arrayOf(
            Triple("mCancel", "Vazgeç", Pal.c("#e5ddd0")),
            Triple("mConfirm", "Evet, Sıfırla!", Pal.c("#f87171"))
        )) {
            val b = btn[id] ?: continue
            val pr = isPressed(id)
            ink.comicBox(c, b.l, b.t, b.r, b.b, 16f, fill, 3.5f, 5f, pr)
            ink.text(c, label, b.cx, b.cy + (if (pr) 4f else 0f), 14f, Pal.INK)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Yetenek ağacı
    // ---------------------------------------------------------------------------------------------
    private val gradMax by lazy { nodeGrad("#86efac", "#4ade80") }
    private val gradAff by lazy { nodeGrad("#fef08a", "#facc15") }
    private val gradPart by lazy { nodeGrad("#bae6fd", "#7dd3fc") }

    private fun nodeGrad(a: String, b: String) =
        LinearGradient(0f, -41f, 0f, 41f, Pal.c(a), Pal.c(b), Shader.TileMode.CLAMP)

    private fun drawTreeOverlay(c: Canvas, t: Long) {
        c.drawRect(0f, 0f, W, H, ink.fill(Pal.a(Pal.INK, 0.9f)))
        val p = panel
        ink.comicBox(c, p.l, p.t, p.r, p.b, 22f, Pal.CREAM, 4f, 6f)

        // başlık bandı
        c.save()
        val clip = Path()
        clip.addRoundRect(RectF(p.l + 4f, p.t + 4f, p.r - 4f, p.b - 4f), 18f, 18f, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(p.l + 4f, p.t + 4f, p.r - 4f, hdrB, ink.fill(Pal.c("#ffdf5d")))
        c.drawRect(p.l + 4f, hdrB - 4f, p.r - 4f, hdrB, ink.fill(Pal.INK))
        c.restore()

        val inL = p.l + 4f
        val inR = p.r - 4f
        val ty = p.t + 4f + 8f + 16f
        Glyphs.drawEmoji(c, "🌳", inL + 12f, ty - 11f, 22f)
        ink.text(c, "YETENEK AĞACI", inL + 12f + 22f + 8f, ty, 19f, Pal.INK, Paint.Align.LEFT)

        val cb = btn["center"]
        if (cb != null) {
            val pr = isPressed("center")
            ink.comicBox(c, cb.l, cb.t, cb.r, cb.b, 14f, Pal.CREAM, 3.5f, 4f, pr)
            Glyphs.drawEmoji(c, "🎯", cb.cx - 10f, cb.cy - 10f + (if (pr) 3f else 0f), 20f)
        }
        // elma bakiyesi
        val bal = fmt(st.apples)
        val bw = 9f + 18f + 4f + ink.measure(bal, 15f) + 9f
        val bR = (cb?.l ?: (inR - 12f)) - 8f
        ink.comicBox(c, bR - bw, p.t + 4f + 8f + 1f, bR, p.t + 4f + 8f + 31f, 15f, Pal.CREAM, 3f, 3f)
        Glyphs.drawEmoji(c, "🍎", bR - bw + 9f, p.t + 4f + 8f + 16f - 9f, 18f)
        ink.text(c, bal, bR - bw + 9f + 18f + 4f, p.t + 4f + 8f + 16f, 15f, Pal.INK, Paint.Align.LEFT)

        sumLayout?.let {
            c.save()
            c.translate(inL + 12f, p.t + 4f + 8f + 36f + 4f)
            it.draw(c)
            c.restore()
        }

        // ağaç alanı
        c.save()
        val vclip = Path()
        vclip.addRoundRect(RectF(vp.l, vp.t, vp.r, vp.b), 18f, 18f, Path.Direction.CW)
        ink.rrFill(c, vp.l, vp.t, vp.r, vp.b, 22f, Pal.INK)
        c.clipPath(vclip)
        c.drawRect(vp.l, vp.t, vp.r, vp.b, ink.fill(Pal.c("#fdf2d6")))
        val dot = ink.fill(Pal.c("#e3cda0"))
        var gy = vp.t + 13f
        while (gy < vp.b) {
            var gx = vp.l + 13f
            while (gx < vp.r) { c.drawCircle(gx, gy, 2f, dot); gx += 26f }
            gy += 26f
        }

        c.save()
        c.translate(vp.l + panX, vp.t + panY)
        c.scale(zoom, zoom)
        drawTreeEdges(c)
        drawTreeNodes(c, t)
        c.restore()

        // yakınlaştırma düğmeleri
        for ((id, label) in arrayOf("zoomIn" to "+", "zoomOut" to "−")) {
            val b = btn[id] ?: continue
            val pr = isPressed(id)
            ink.comicBox(c, b.l, b.t, b.r, b.b, 14f, Pal.CREAM, 3.5f, 4f, pr)
            ink.text(c, label, b.cx, b.cy + (if (pr) 3f else 0f), 22f, Pal.INK)
        }
        if (hintVisible) {
            val hint = "🖐 Sürükle • iki parmak: yakınlaştır • Basılı tut: toplu al"
            var size = 11f
            var w = ink.richWidth(hint, size)
            val maxW = vp.w - 28f
            if (w > maxW) { size *= maxW / w; w = maxW }
            ink.bordered(c, vp.l + 8f, vp.b - 30f, vp.l + 8f + w + 16f, vp.b - 8f, 11f, Pal.CREAM, 2f)
            ink.rich(c, hint, vp.l + 8f + 8f + w / 2f, vp.b - 19f, size, Pal.INK, 0f)
        }
        c.restore()
        ink.rrStroke(c, vp.l + 2f, vp.t + 2f, vp.r - 2f, vp.b - 2f, 20f, Pal.INK, 4f)

        drawDetailCard(c)

        // TEKRAR OYNA
        btn["play"]?.let { b ->
            val pr = isPressed("play")
            ink.comicBox(c, b.l, b.t, b.r, b.b, 16f, Pal.c("#4ade80"), 3.5f, 5f, pr)
            // alt yarıya hafif koyu degrade etkisi
            val dy = if (pr) 4f else 0f
            ink.rich(c, "TEKRAR OYNA ▶", b.cx, b.cy + dy, 19f, Pal.INK, 0f)
        }
    }

    private fun drawTreeEdges(c: Canvas) {
        val g = game
        val path = Path()
        for (sk in Tree.ALL) {
            val par = sk.parent ?: continue
            if (!g.isVisible(sk)) continue
            val pn = Tree.MAP[par] ?: continue
            val unlocked = g.lvl(sk.id) > 0
            val aff = !unlocked && st.apples >= g.cost(sk)
            val midY = (pn.y + sk.y) / 2f
            path.reset()
            path.moveTo(pn.x, pn.y)
            path.cubicTo(pn.x, midY, sk.x, midY, sk.x, sk.y)
            c.drawPath(path, ink.stroke(Pal.INK, 13f, true))
            c.drawPath(path, ink.stroke(if (unlocked) Pal.c("#4ade80") else if (aff) Pal.c("#facc15") else Pal.c("#d6cfc2"), 6f, true))
        }
    }

    private fun drawTreeNodes(c: Canvas, t: Long) {
        val g = game
        for (sk in Tree.ALL) {
            if (!g.isVisible(sk)) continue
            val sx = sk.x * zoom + panX
            val sy = sk.y * zoom + panY
            if (sx < -90f * zoom || sy < -120f * zoom || sx > vp.w + 90f * zoom || sy > vp.h + 120f * zoom) continue

            val lvl = g.lvl(sk.id)
            val isMax = lvl >= sk.maxLevel
            val cost = g.cost(sk)
            val canBuy = !isMax && st.apples >= cost
            val sel = selected == sk.id

            c.save()
            c.translate(sk.x, sk.y)
            val an = nodeAnim[sk.id]
            if (an != null) {
                val k = (t - an.second) / (if (an.first == 'p') 380f else 300f)
                if (k >= 1f) nodeAnim.remove(sk.id)
                else if (an.first == 'p') {
                    val sc = when {
                        k < 0.4f -> 1f + 0.28f * (k / 0.4f)
                        k < 0.75f -> 1.28f - 0.33f * ((k - 0.4f) / 0.35f)
                        else -> 0.95f + 0.05f * ((k - 0.75f) / 0.25f)
                    }
                    val rot = when {
                        k < 0.4f -> -5f * (k / 0.4f)
                        k < 0.75f -> -5f + 8f * ((k - 0.4f) / 0.35f)
                        else -> 3f * (1f - (k - 0.75f) / 0.25f)
                    }
                    c.scale(sc, sc); c.rotate(rot)
                } else {
                    val dx = when {
                        k < 0.25f -> -7f * (k / 0.25f)
                        k < 0.5f -> -7f + 14f * ((k - 0.25f) / 0.25f)
                        k < 0.75f -> 7f - 11f * ((k - 0.5f) / 0.25f)
                        else -> -4f * (1f - (k - 0.75f) / 0.25f)
                    }
                    c.translate(dx, 0f)
                }
            }

            // gölge
            ink.rrFill(c, -41f, -41f + 6f, 41f, 41f + 6f, 22f, Pal.INK)
            if (canBuy) ink.rrStroke(c, -43.5f, -43.5f, 43.5f, 43.5f, 24.5f, Pal.a(Pal.c("#facc15"), 0.75f), 5f)
            // gövde
            ink.rf.set(-41f, -41f, 41f, 41f)
            val body = ink.fp
            body.shader = null
            if (isMax) body.shader = gradMax
            else if (canBuy) body.shader = gradAff
            else if (lvl > 0) body.shader = gradPart
            else body.color = Pal.c("#e5ddd0")
            c.drawRoundRect(ink.rf, 22f, 22f, body)
            body.shader = null
            ink.rrStroke(c, -39f, -39f, 39f, 39f, 20f, Pal.INK, 4f)
            if (sel) ink.rrStroke(c, -46f, -46f, 46f, 46f, 27f, Pal.c("#38bdf8"), 4f)

            // ikon + bedel
            Glyphs.drawSkillIcon(c, sk.id, -18f, -30f, 36f)
            val label = if (isMax) "MAKS ✓" else "🍎 ${fmt(cost)}"
            val lw = ink.richWidth(label, 10f) + 12f
            ink.bordered(c, -lw / 2f, 12f, lw / 2f, 29f, 6f, Pal.c("#ffffff"), 2f)
            ink.rich(c, label, 0f, 20.5f, 10f, Pal.INK, 0f)

            // seviye rozeti
            if (sk.maxLevel > 1) {
                val txt = "$lvl/${sk.maxLevel}"
                val bw = ink.measure(txt, 10f) + 12f
                ink.bordered(c, 41f + 10f - bw, -41f - 10f, 41f + 10f, -41f - 10f + 18f, 9f, Pal.CREAM, 2f)
                ink.text(c, txt, 41f + 10f - bw / 2f, -41f - 10f + 9f, 10f, Pal.INK)
            }

            // ad etiketi
            val nm = sk.shortName
            val nw = ink.measure(nm, 11f) + 16f
            ink.rrFill(c, -nw / 2f, 41f + 4f + 2f, nw / 2f, 41f + 24f + 2f, 8f, Pal.INK)
            ink.bordered(c, -nw / 2f, 41f + 4f, nw / 2f, 41f + 24f, 8f, Pal.CREAM, 2f)
            ink.text(c, nm, 0f, 41f + 14f, 11f, Pal.INK)
            c.restore()
        }
    }

    private fun drawDetailCard(c: Canvas) {
        val g = game
        val sk = Tree.MAP[selected] ?: Tree.ALL[0]
        val lvl = g.lvl(sk.id)
        val isMax = lvl >= sk.maxLevel
        val cost = g.cost(sk)
        val canBuy = !isMax && st.apples >= cost

        val l = vp.l
        val r = vp.r
        ink.comicBox(c, l, cardT, r, cardB, 22f, Pal.c("#fffdf7"), 4f, 5f)
        val x0 = l + 16f
        val y0 = cardT + 16f

        ink.bordered(c, x0, y0, x0 + 48f, y0 + 48f, 16f, Pal.c("#fef08a"), 3f)
        Glyphs.drawSkillIcon(c, sk.id, x0 + 7f, y0 + 7f, 34f)

        val nx = x0 + 48f + 12f
        ink.text(c, sk.name, nx, y0 + 12f, 16f, Pal.INK, Paint.Align.LEFT)
        val nameW = ink.measure(sk.name, 16f)
        val chip = if (isMax) "MAKS" else "Svy $lvl/${sk.maxLevel}"
        val cw = ink.measure(chip, 11f) + 14f
        var chipX = nx + nameW + 8f
        var chipY = y0 + 3f
        if (chipX + cw > r - 16f) { chipX = nx; chipY = y0 + 22f }
        ink.bordered(c, chipX, chipY, chipX + cw, chipY + 18f, 8f, Pal.c("#e0f2fe"), 2f)
        ink.text(c, chip, chipX + cw / 2f, chipY + 9f, 11f, Pal.INK)
        val branchY = if (chipY == y0 + 22f) y0 + 42f else y0 + 34f
        ink.text(c, sk.branch, nx, branchY, 11f, Pal.c("#b45309"), Paint.Align.LEFT)

        descLayout?.let {
            c.save()
            c.translate(x0, y0 + 48f + 8f)
            it.draw(c)
            c.restore()
        }

        val dY = cardB - 16f - 34f - 8f
        val dash = ink.stroke(Pal.a(Pal.INK, 0.2f), 2f)
        dash.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        c.drawLine(x0, dY, r - 16f, dY, dash)
        dash.pathEffect = null

        val costLabel = if (isMax) "Tamamlandı! ✓" else "Bedel: 🍎 ${fmt(cost)}"
        ink.richLeft(c, costLabel, x0, cardB - 16f - 17f, 14f, Pal.INK)

        btn["buy"]?.let { b ->
            val pr = isPressed("buy") && (canBuy)
            val fill = when {
                isMax -> Pal.c("#86efac")
                canBuy -> Pal.c("#facc15")
                else -> Pal.c("#e5ddd0")
            }
            val label = when {
                isMax -> "Açık"
                canBuy -> "Geliştir! ✨"
                else -> "${fmt(cost - st.apples)} 🍎 Eksik"
            }
            // etikete göre genişlet
            val need = ink.richWidth(label, 12f) + 28f
            val bl = if (need > b.w) b.r - need else b.l
            ink.comicBox(c, bl, b.t, b.r, b.b, 16f, fill, 3.5f, 5f, pr, if (isMax || !canBuy) 0.9f else 1f)
            ink.rich(c, label, (bl + b.r) / 2f, b.cy + (if (pr) 4f else 0f), 12f, Pal.INK, 0f)
        }
    }

    // ============================================================================================
    // DOKUNMA
    // ============================================================================================
    private fun hit(x: Float, y: Float): String? {
        // sondan başa: üstte çizilenler öncelikli
        val ids = btn.keys.toList().asReversed()
        for (id in ids) if (btn[id]!!.has(x, y)) return id
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        val idx = e.actionIndex
        val pid = e.getPointerId(idx)
        val x = e.getX(idx) / dens
        val y = e.getY(idx) / dens

        if (confirmOpen) {
            when (a) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> hit(x, y)?.let { pressedPtr[pid] = it }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = pressedPtr.remove(pid)
                    if (id != null && hit(x, y) == id) modalClick(id)
                }
                MotionEvent.ACTION_CANCEL -> pressedPtr.clear()
            }
            return true
        }
        if (game.mode == Mode.TREE) { treeTouch(e, a, idx, pid, x, y); return true }
        mainTouch(e, a, pid, x, y)
        return true
    }

    private fun modalClick(id: String) {
        if (id == "mCancel") confirmOpen = false
        else if (id == "mConfirm") {
            confirmOpen = false
            Store.clear(context)
            st = GameState()
            Sfx.enabled = st.soundEnabled
            game = Game(st, this)
            toast("🗑 Oyun sıfırlandı! Yeni macera başlıyor!")
            game.startRun()
        }
    }

    // ---- ana ekran ----
    private fun mainTouch(e: MotionEvent, a: Int, pid: Int, x: Float, y: Float) {
        when (a) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = hit(x, y)
                if (id != null && id.startsWith("pad")) {
                    dpadPress(id)
                    pressedUntil[id] = now() + 110
                    pressedPtr[pid] = id
                } else if (id != null) {
                    pressedPtr[pid] = id
                } else if (canvasRect.has(x, y)) {
                    game.resumePlay(now())
                    swipePtr = pid
                    swipeX = x; swipeY = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipePtr >= 0) {
                    val i = e.findPointerIndex(swipePtr)
                    if (i >= 0) {
                        val mx = e.getX(i) / dens
                        val my = e.getY(i) / dens
                        val dx = mx - swipeX
                        val dy = my - swipeY
                        if (hypot(dx, dy) > 24f) {
                            val r = if (abs(dx) > abs(dy)) game.queueDirection(if (dx > 0) 1 else -1, 0, now())
                            else game.queueDirection(0, if (dy > 0) 1 else -1, now())
                            if (r == "ok" && !st.swipeUsed) { st.swipeUsed = true; Store.save(context, st) }
                            swipeX = mx; swipeY = my
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pid == swipePtr || a == MotionEvent.ACTION_CANCEL) swipePtr = -1
                val id = pressedPtr.remove(pid)
                if (a == MotionEvent.ACTION_CANCEL) { pressedPtr.clear(); return }
                if (id != null && !id.startsWith("pad") && hit(x, y) == id) click(id)
            }
        }
    }

    private fun dpadPress(id: String) {
        val r = when (id) {
            "padRight" -> game.queueDirection(1, 0, now())
            "padDown" -> game.queueDirection(0, 1, now())
            "padUp" -> game.queueDirection(0, -1, now())
            "padLeft" -> game.queueDirection(-1, 0, now())
            else -> "ignored"
        }
        if (r == "ok") haptic(12)
        else {
            hapticNope()
            shakeUntil[id] = now() + 280
        }
    }

    private fun click(id: String) {
        when (id) {
            "pause" -> if (game.mode == Mode.PLAYING || game.mode == Mode.PAUSED) game.togglePause(now())
            "sound" -> {
                st.soundEnabled = !st.soundEnabled
                Sfx.enabled = st.soundEnabled
                Store.save(context, st)
                if (st.soundEnabled) Sfx.pop()
            }
            "haptic" -> {
                st.hapticsEnabled = !st.hapticsEnabled
                Store.save(context, st)
                haptic(20)
                toast(if (st.hapticsEnabled) "📳 Titreşim açık" else "📴 Titreşim kapalı")
            }
            "reset" -> confirmOpen = true
            "tree" -> if (game.mode == Mode.READY) openTree(false)
            "tip" -> game.dismissSpeedTip()
            "speedSlow", "speedNormal", "speedTurbo" -> {
                st.speedMode = when (id) { "speedSlow" -> "slow"; "speedTurbo" -> "turbo"; else -> "normal" }
                game.dismissSpeedTip()
                Sfx.pop()
                when (st.speedMode) {
                    "turbo" -> toast("🐇 TURBO AÇIK! Yılan çok daha hızlı, elmalar +%${intArrayOf(0, 10, 20, 50)[game.lvl("speedGear")]} değerli")
                    "slow" -> toast("🐢 Yavaş mod açık")
                    else -> toast("🐍 Normal hız")
                }
                Store.save(context, st)
            }
        }
    }

    // ---- yetenek ağacı dokunma ----
    private fun nodeAt(x: Float, y: Float): String? {
        val wx = (x - vp.l - panX) / zoom
        val wy = (y - vp.t - panY) / zoom
        var best: String? = null
        for (sk in Tree.ALL) {
            if (!game.isVisible(sk)) continue
            if (abs(wx - sk.x) <= 41f && abs(wy - sk.y) <= 41f) best = sk.id
        }
        return best
    }

    private fun cancelLong() {
        longRunnable?.let { removeCallbacks(it) }
        longRunnable = null
    }

    private fun scheduleLong(onFire: () -> Unit) {
        cancelLong()
        longFired = false
        val r = Runnable { longRunnable = null; longFired = true; onFire() }
        longRunnable = r
        postDelayed(r, 380)
    }

    private fun zoomAt(cx: Float, cy: Float, factor: Float) {
        hintVisible = false
        val px = cx - vp.l
        val py = cy - vp.t
        val nz = (zoom * factor).coerceIn(ZMIN, ZMAX)
        val k = nz / zoom
        panX = px - (px - panX) * k
        panY = py - (py - panY) * k
        zoom = nz
    }

    private fun treeTouch(e: MotionEvent, a: Int, idx: Int, pid: Int, x: Float, y: Float) {
        when (a) {
            MotionEvent.ACTION_DOWN -> {
                val id = hit(x, y)
                if (id != null) {
                    pressedPtr[pid] = id
                    if (id == "buy") scheduleLong { buyMaxSelected() }
                } else if (vp.has(x, y)) {
                    panning = true
                    panPtr = pid
                    panLastX = x; panLastY = y
                    panTotal = 0f
                    downNode = nodeAt(x, y)
                    longFired = false
                    val dn = downNode
                    if (dn != null) scheduleLong { buyMaxNode(dn) }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && vp.has(e.getX(0) / dens, e.getY(0) / dens)) {
                    cancelLong()
                    panning = false
                    pinching = true
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) / dens
                    downNode = null
                } else {
                    val id = hit(x, y)
                    if (id != null) pressedPtr[pid] = id
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && e.pointerCount >= 2) {
                    val x0 = e.getX(0) / dens; val y0 = e.getY(0) / dens
                    val x1 = e.getX(1) / dens; val y1 = e.getY(1) / dens
                    val d = hypot(x0 - x1, y0 - y1)
                    if (pinchDist > 0f) zoomAt((x0 + x1) / 2f, (y0 + y1) / 2f, d / pinchDist)
                    pinchDist = d
                } else if (panning) {
                    val i = e.findPointerIndex(panPtr)
                    if (i >= 0) {
                        val mx = e.getX(i) / dens
                        val my = e.getY(i) / dens
                        panTotal += hypot(mx - panLastX, my - panLastY)
                        if (panTotal > slop) {
                            if (downNode != null || longRunnable != null) { cancelLong(); downNode = null }
                            hintVisible = false
                            panX += mx - panLastX
                            panY += my - panLastY
                        }
                        panLastX = mx; panLastY = my
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinching && e.pointerCount <= 2) {
                    pinching = false
                    panning = false
                    pinchDist = 0f
                }
                pressedPtr.remove(pid)
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                val id = pressedPtr.remove(pid)
                if (id != null) {
                    if (!longFired && hit(x, y) == id) treeClick(id)
                } else if (panning && panTotal <= slop && !longFired) {
                    val n = nodeAt(x, y)
                    if (n != null && n == downNode) tapNode(n)
                }
                panning = false
                pinching = false
                downNode = null
                longFired = false
                pressedPtr.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLong()
                panning = false; pinching = false; downNode = null; pressedPtr.clear()
            }
        }
    }

    private fun treeClick(id: String) {
        when (id) {
            "play" -> game.startRun()
            "center" -> centerTree()
            "zoomIn" -> zoomAt(vp.cx, vp.cy, 1.25f)
            "zoomOut" -> zoomAt(vp.cx, vp.cy, 0.8f)
            "buy" -> tryBuy(selected)
        }
    }

    private fun tapNode(id: String) {
        // dokunmatikte ilk dokunuş seçer, ikinci dokunuş satın alır
        if (selected != id) { selected = id; return }
        tryBuy(id)
    }

    private fun tryBuy(id: String) {
        val sk = Tree.MAP[id] ?: return
        if (!game.isVisible(sk)) return
        val l = game.lvl(id)
        if (l >= sk.maxLevel) return
        val c = game.cost(sk)
        if (st.apples < c) {
            Sfx.nope()
            nodeAnim[id] = 'n' to now()
            return
        }
        st.apples -= c
        st.skills[id] = l + 1
        Sfx.pop()
        Store.save(context, st)
        nodeAnim[id] = 'p' to now()
    }

    private fun buyMaxNode(id: String) {
        selected = id
        buyMax(id)
    }

    private fun buyMaxSelected() = buyMax(selected)

    private fun buyMax(id: String) {
        var n = 0
        while (game.buyOnce(id)) n++
        if (n > 0) {
            Sfx.pop()
            Store.save(context, st)
            nodeAnim[id] = 'p' to now()
            toast("⬆ ${Tree.MAP[id]?.shortName ?: id}: +$n seviye alındı!")
        } else {
            Sfx.nope()
        }
    }
}
