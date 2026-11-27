package com.kivrik.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** "#rgb" / "#rrggbb" / "#aarrggbb" renk ayrıştırıcı (önbellekli). */
object Pal {
    private val cache = HashMap<String, Int>()
    fun c(s: String): Int = cache.getOrPut(s) {
        var h = s.trim()
        if (h.startsWith("#") && h.length == 4) {
            h = "#" + h[1] + h[1] + h[2] + h[2] + h[3] + h[3]
        }
        Color.parseColor(h)
    }
    fun a(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    val INK = c("#231f20")
    val CREAM = c("#fff8e7")
}

/** Çok küçük bir SVG path (d özniteliği) ayrıştırıcı: M L H V C Q A Z (mutlak + göreli). */
object SvgPath {
    fun parse(d: String): Path {
        val p = Path()
        var i = 0
        val n = d.length
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lcx = 0f // son kübik bezier kontrol noktası (S/s için yansıtılır)
        var lcy = 0f
        var ctl = false

        fun skipSep() {
            while (i < n && (d[i] == ' ' || d[i] == ',' || d[i] == '\n' || d[i] == '\t' || d[i] == '\r')) i++
        }

        fun num(): Float {
            skipSep()
            val st = i
            if (i < n && (d[i] == '-' || d[i] == '+')) i++
            var dot = false
            while (i < n) {
                val ch = d[i]
                if (ch in '0'..'9') i++
                else if (ch == '.' && !dot) { dot = true; i++ }
                else break
            }
            return d.substring(st, i).toFloat()
        }

        fun flag(): Boolean {
            skipSep()
            val v = d[i] == '1'
            i++
            return v
        }

        while (true) {
            skipSep()
            if (i >= n) break
            val ch = d[i]
            if (ch.isLetter()) {
                cmd = ch
                i++
            } else if (cmd == ' ') {
                break
            }
            val wasCubic = ctl
            ctl = false
            when (cmd) {
                'M', 'm' -> {
                    var x = num(); var y = num()
                    if (cmd == 'm') { x += cx; y += cy }
                    p.moveTo(x, y); cx = x; cy = y; sx = x; sy = y
                    cmd = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    var x = num(); var y = num()
                    if (cmd == 'l') { x += cx; y += cy }
                    p.lineTo(x, y); cx = x; cy = y
                }
                'H', 'h' -> {
                    var x = num()
                    if (cmd == 'h') x += cx
                    p.lineTo(x, cy); cx = x
                }
                'V', 'v' -> {
                    var y = num()
                    if (cmd == 'v') y += cy
                    p.lineTo(cx, y); cy = y
                }
                'C', 'c' -> {
                    var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (cmd == 'c') { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x; cy = y
                    lcx = x2; lcy = y2; ctl = true
                }
                'S', 's' -> {
                    var x2 = num(); var y2 = num(); var x = num(); var y = num()
                    if (cmd == 's') { x2 += cx; y2 += cy; x += cx; y += cy }
                    val x1 = if (wasCubic) 2f * cx - lcx else cx
                    val y1 = if (wasCubic) 2f * cy - lcy else cy
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x; cy = y
                    lcx = x2; lcy = y2; ctl = true
                }
                'Q', 'q' -> {
                    var x1 = num(); var y1 = num(); var x = num(); var y = num()
                    if (cmd == 'q') { x1 += cx; y1 += cy; x += cx; y += cy }
                    p.quadTo(x1, y1, x, y); cx = x; cy = y
                }
                'A', 'a' -> {
                    val rx0 = num(); val ry0 = num(); num() // dönüş (hep 0)
                    val fa = flag(); val fs = flag()
                    var x = num(); var y = num()
                    if (cmd == 'a') { x += cx; y += cy }
                    arc(p, cx, cy, rx0, ry0, fa, fs, x, y)
                    cx = x; cy = y
                }
                'Z', 'z' -> {
                    p.close(); cx = sx; cy = sy
                    cmd = ' '
                }
                else -> break
            }
        }
        return p
    }

    private fun arc(p: Path, x1: Float, y1: Float, rx0: Float, ry0: Float, fa: Boolean, fs: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0)
        var ry = abs(ry0)
        if (rx == 0f || ry == 0f) { p.lineTo(x2, y2); return }
        val dx = (x1 - x2) / 2f
        val dy = (y1 - y2) / 2f
        val lam = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        if (lam > 1f) {
            val s = sqrt(lam)
            rx *= s; ry *= s
        }
        val rx2 = rx * rx
        val ry2 = ry * ry
        val num = rx2 * ry2 - rx2 * dy * dy - ry2 * dx * dx
        val den = rx2 * dy * dy + ry2 * dx * dx
        val sign = if (fa == fs) -1f else 1f
        val coef = sign * sqrt(max(0f, num / den))
        val cxp = coef * (rx * dy / ry)
        val cyp = coef * (-ry * dx / rx)
        val ccx = cxp + (x1 + x2) / 2f
        val ccy = cyp + (y1 + y2) / 2f
        val t1 = atan2((dy - cyp) / ry, (dx - cxp) / rx)
        val t2 = atan2((-dy - cyp) / ry, (-dx - cxp) / rx)
        var dt = t2 - t1
        if (!fs && dt > 0) dt -= (2 * PI).toFloat()
        else if (fs && dt < 0) dt += (2 * PI).toFloat()
        val startDeg = (t1 * 180.0 / PI).toFloat()
        val sweepDeg = (dt * 180.0 / PI).toFloat()
        p.arcTo(RectF(ccx - rx, ccy - ry, ccx + rx, ccy + ry), startDeg, sweepDeg, false)
    }
}

class IconDef(val shape: String, val color: String, val badge: String = "", val rot: Float = 0f)

object Glyphs {
    // 32x32 birimlik çizgi film glifleri
    private val RAW: Map<String, String> = mapOf(
        "arrow" to """<path d="M4 12h13V5l11 11-11 11v-7H4z"/>""",
        "shield" to """<path d="M16 3l11 4v8c0 7-5 11-11 14C10 26 5 22 5 15V7z"/>""",
        "portal" to """<circle cx="16" cy="16" r="12"/><path fill="none" d="M16 9a7 7 0 11-7 7M16 14a2 2 0 11-2 2"/>""",
        "ghost" to """<path d="M6 29V15a10 10 0 0120 0v14l-3.5-3-3 3-3.5-3-3.5 3-3-3z"/><circle cx="12" cy="15" r="2" fill="#231f20"/><circle cx="20" cy="15" r="2" fill="#231f20"/>""",
        "flame" to """<path d="M16 3c1 6 9 9 9 18a9 9 0 01-18 0c0-4 2-6 4-8 0 3 2 4 3 4-1-5 0-10 2-14z"/>""",
        "tooth" to """<path d="M7 5h18c1 8-1 12-3 22-2 0-3-6-6-6s-4 6-6 6C8 17 6 13 7 5z"/>""",
        "burst" to """<path d="M16 2l3.5 9 9.5-3.5L24 16l7 6-9.5-.5L20 31l-4-8-4 8-2.5-9.5L1 22l7-6-5-8.5L12.5 11z"/>""",
        "axe" to """<path d="M7 28L21 11" fill="none" stroke-width="4"/><path d="M17 4l11 9-7 3-6-6z"/>""",
        "skull" to """<path d="M16 4C9 4 5 9 5 15c0 4 2 6 4 7v5h14v-5c2-1 4-3 4-7 0-6-4-11-11-11z"/><circle cx="12" cy="15" r="3" fill="#231f20"/><circle cx="20" cy="15" r="3" fill="#231f20"/><path fill="none" d="M13 27v-4M19 27v-4"/>""",
        "bomb" to """<circle cx="14" cy="19" r="10"/><path fill="none" d="M21 11l4-4"/><path fill="#facc15" d="M26 2l1 3 3 1-3 1-1 3-1-3-3-1 3-1z"/>""",
        "worm" to """<circle cx="8" cy="22" r="5"/><circle cx="16" cy="16" r="5.5"/><circle cx="24" cy="9" r="6"/><circle cx="25.5" cy="8" r="1.4" fill="#231f20"/>""",
        "apple" to """<path d="M16 10C12 6 5 8 5 17c0 7 5 12 8 11 2-1 4-1 6 0 3 1 8-4 8-11 0-9-7-11-11-7z"/><path fill="none" d="M16 10c0-3 1-5 3-7"/><path fill="#4ade80" d="M19 5c3-3 7-2 8 0-3 3-6 2-8 0z"/>""",
        "cherry" to """<circle cx="10" cy="23" r="6"/><circle cx="22" cy="21" r="6"/><path fill="none" d="M10 17c2-7 6-11 12-12M22 15V5"/>""",
        "magnet" to """<path d="M5 4h8v13a3 3 0 006 0V4h8v13a11 11 0 01-22 0z"/><path fill="#fff" d="M5 4h8v5H5zM19 4h8v5h-8z"/>""",
        "cloud" to """<path d="M9 20a6 6 0 010-12 8 8 0 0115-1 6 6 0 010 13z"/><path fill="none" d="M10 25v3M16 25v4M22 25v3"/>""",
        "chain" to """<rect x="2" y="10" width="17" height="12" rx="6"/><rect x="13" y="10" width="17" height="12" rx="6"/>""",
        "clock" to """<circle cx="16" cy="17" r="12"/><path fill="none" d="M16 10v7l5 3M13 3h6"/>""",
        "crown" to """<path d="M4 25l2-15 7 7 3-11 3 11 7-7 2 15z"/>""",
        "star" to """<path d="M16 3l4 9 10 1-7.5 6.5 2.3 9.7L16 24l-8.8 5.2 2.3-9.7L2 13l10-1z"/>""",
        "gift" to """<rect x="4" y="12" width="24" height="16" rx="2"/><path fill="#ef4444" d="M4 12h24v5H4zM14 12h4v16h-4z"/><path fill="none" d="M16 12c-6-8-11-1-3 0M16 12c6-8 11-1 3 0"/>""",
        "sprout" to """<path d="M16 29V14" fill="none"/><path d="M16 17C16 9 8 7 4 9c0 8 6 10 12 8z"/><path d="M16 14c0-6 6-9 12-8 0 7-5 9-12 8z"/>""",
        "bolt" to """<path d="M19 2L6 18h8l-2 12 14-18h-8z"/>""",
        "gear" to """<path fill="none" stroke-width="5" d="M16 3v4M16 25v4M3 16h4M25 16h4M7 7l3 3M22 22l3 3M25 7l-3 3M10 22l-3 3"/><circle cx="16" cy="16" r="9"/><circle cx="16" cy="16" r="3.5" fill="#fff"/>""",
        "grid" to """<rect x="3" y="3" width="26" height="26" rx="3"/><path fill="none" d="M11.7 3v26M20.3 3v26M3 11.7h26M3 20.3h26"/>""",
        "scissors" to """<circle cx="8" cy="24" r="4"/><circle cx="24" cy="24" r="4"/><path fill="none" d="M10 21L26 4M22 21L6 4"/>""",
        "clover" to """<circle cx="11" cy="11" r="6.5"/><circle cx="21" cy="11" r="6.5"/><circle cx="11" cy="21" r="6.5"/><circle cx="21" cy="21" r="6.5"/><path fill="none" d="M16 16l-2 14"/>""",
        "gem" to """<path d="M9 5h14l6 8-13 16L3 13z"/><path fill="none" d="M3 13h26M12 13l4 16 4-16M9 5l3 8M23 5l-3 8"/>""",
        "moon" to """<path d="M22 4a13 13 0 100 24A10 10 0 0122 4z"/>""",
        "bank" to """<path d="M3 12L16 4l13 8z"/><path fill="none" stroke-width="3" d="M7 15v10M13 15v10M19 15v10M25 15v10M3 28h26"/>""",
        "tag" to """<path d="M3 16L16 3h12v12L15 28z"/><circle cx="22" cy="10" r="2.5" fill="#fff"/>""",
        "tree" to """<path d="M14 20h4v9h-4z"/><circle cx="16" cy="12" r="9"/>""",
        "robot" to """<rect x="5" y="9" width="22" height="16" rx="4"/><circle cx="12" cy="17" r="2.5" fill="#fff"/><circle cx="20" cy="17" r="2.5" fill="#fff"/><path fill="none" d="M16 9V4M12 25v3M20 25v3"/>""",
        "bag" to """<path d="M8 12a8 8 0 0116 0v16H8z"/><path fill="none" d="M12 21h8"/>""",
        "basket" to """<path d="M4 13h24l-3 15H7z"/><path fill="none" d="M9 13l5-9M23 13l-5-9M12 19v5M20 19v5"/>""",
        "trophy" to """<path d="M9 4h14v8a7 7 0 01-14 0z"/><path fill="none" d="M9 7H4c0 6 3 8 6 8M23 7h5c0 6-3 8-6 8M16 19v6M10 28h12"/>""",
        "pause" to """<rect x="7" y="5" width="6" height="22" rx="2"/><rect x="19" y="5" width="6" height="22" rx="2"/>""",
        "play" to """<path d="M8 4l20 12L8 28z"/>""",
        "speaker" to """<path d="M4 12h6l8-7v22l-8-7H4z"/><path fill="none" d="M22 11c3 3 3 7 0 10M25 7c6 5 6 13 0 18"/>""",
        "speakerOff" to """<path d="M4 12h6l8-7v22l-8-7H4z"/><path fill="none" d="M22 12l7 8M29 12l-7 8"/>""",
        "trash" to """<path d="M6 9h20l-2 20H8z"/><path fill="none" d="M4 9h24M12 9V4h8v5M13 14v10M19 14v10"/>""",
        "target" to """<circle cx="16" cy="16" r="12"/><circle cx="16" cy="16" r="7" fill="#fff"/><circle cx="16" cy="16" r="2.5" fill="#ef4444"/>""",
        "move" to """<path fill="none" d="M16 3v26M3 16h26M16 3l-4 4M16 3l4 4M16 29l-4-4M16 29l4-4M3 16l4-4M3 16l4 4M29 16l-4-4M29 16l-4 4"/>""",
        "turtle" to """<path d="M5 22c0-8 5-13 11-13s11 5 11 13z"/><path fill="none" d="M11 22l2-8M21 22l-2-8M16 9v13M8 22v5M22 22v5" stroke-width="2"/><circle cx="28" cy="18" r="3"/><circle cx="29" cy="17" r="0.9" fill="#231f20" stroke="none"/>""",
        "snake" to """<rect x="3" y="5" width="26" height="20" rx="9"/><circle cx="10" cy="13" r="5" fill="#fff"/><circle cx="22" cy="13" r="5" fill="#fff"/><circle cx="11" cy="14" r="2" fill="#231f20"/><circle cx="21" cy="14" r="2" fill="#231f20"/><path fill="none" stroke="#ef4444" d="M16 25v4M16 29l-2.5 2.5M16 29l2.5 2.5"/>""",
        "rabbit" to """<path d="M10 14C8 5 9 2 11 2s4 5 4 12zM17 14c0-9 1-12 3-12s3 3 2 12z"/><circle cx="16" cy="20" r="9"/><circle cx="12.5" cy="19" r="1.6" fill="#231f20" stroke="none"/><circle cx="19.5" cy="19" r="1.6" fill="#231f20" stroke="none"/><path fill="none" d="M14 23.5q2 1.5 4 0"/>""",
        "lock" to """<rect x="6" y="14" width="20" height="15" rx="3"/><path fill="none" d="M10 14V9a6 6 0 0112 0v5"/>""",
        "vibrate" to """<rect x="9" y="4" width="14" height="24" rx="3"/><path fill="none" d="M3 10v12M29 10v12M6 13v6M26 13v6"/>""",
        "vibrateOff" to """<rect x="9" y="4" width="14" height="24" rx="3"/><path fill="none" d="M3 10v12M29 10v12M6 13v6M26 13v6"/><path fill="none" stroke="#ef4444" stroke-width="3.5" d="M4 4l24 24"/>"""
    )

    // yetenek id -> [şekil, renk, rozet, dönüş]
    val ICONS: Map<String, IconDef> = mapOf(
        "right" to IconDef("arrow", "#4ade80", "", 0f),
        "down" to IconDef("arrow", "#4ade80", "", 90f),
        "up" to IconDef("arrow", "#4ade80", "", 270f),
        "left" to IconDef("arrow", "#4ade80", "", 180f),
        "wallCushion" to IconDef("shield", "#60a5fa"),
        "shockCushion" to IconDef("shield", "#facc15", "!"),
        "startKit" to IconDef("bag", "#fb923c", "+"),
        "wallWrap" to IconDef("portal", "#a78bfa"),
        "portalLoot" to IconDef("portal", "#fbbf24", "\$"),
        "ghostTail" to IconDef("ghost", "#e9d5ff"),
        "ghostFeast" to IconDef("ghost", "#fca5a5", "+"),
        "cushionRegen" to IconDef("shield", "#86efac", "↻"),
        "portalRegen" to IconDef("portal", "#86efac", "↻"),
        "phoenix" to IconDef("flame", "#fb923c"),
        "sharpTeeth" to IconDef("tooth", "#ffffff"),
        "critBite" to IconDef("burst", "#fb923c"),
        "executioner" to IconDef("axe", "#d6d3d1"),
        "venom" to IconDef("skull", "#bef264"),
        "explosive" to IconDef("bomb", "#9ca3af"),
        "bodyBite" to IconDef("worm", "#4ade80"),
        "softApples" to IconDef("apple", "#fdba74", "↓"),
        "moreApples" to IconDef("apple", "#86efac", "+"),
        "doubleTaste" to IconDef("cherry", "#ef4444"),
        "splitApple" to IconDef("apple", "#ef4444", "×2"),
        "appleValue" to IconDef("apple", "#ef4444", "↑"),
        "goldenApple" to IconDef("apple", "#facc15", "★"),
        "magnet" to IconDef("magnet", "#ef4444"),
        "magnetPower" to IconDef("magnet", "#60a5fa", "+"),
        "goldenMagnet" to IconDef("magnet", "#facc15", "★"),
        "appleRain" to IconDef("cloud", "#7dd3fc"),
        "comboChain" to IconDef("chain", "#f9a8d4"),
        "comboSaver" to IconDef("clock", "#fde68a", "+"),
        "goldenValue" to IconDef("crown", "#facc15"),
        "goldRush" to IconDef("star", "#facc15", "×2"),
        "consolation" to IconDef("gift", "#f9a8d4"),
        "globalBoost" to IconDef("sprout", "#4ade80", "↑"),
        "autopilot" to IconDef("robot", "#a5b4fc"),
        "lightning" to IconDef("bolt", "#fde047"),
        "chainLightning" to IconDef("bolt", "#7dd3fc", "×3"),
        "lightningBounty" to IconDef("bolt", "#facc15", "\$"),
        "stormCharge" to IconDef("bolt", "#fb923c", "★"),
        "speedGear" to IconDef("gear", "#94a3b8"),
        "shortTail" to IconDef("worm", "#fde047", "↓"),
        "wideField" to IconDef("grid", "#86efac"),
        "tailTrim" to IconDef("scissors", "#fca5a5"),
        "slowMo" to IconDef("clock", "#bae6fd", "↓"),
        "luckyClover" to IconDef("clover", "#4ade80"),
        "gemApple" to IconDef("gem", "#22d3ee"),
        "harvestMoon" to IconDef("moon", "#fde68a"),
        "interest" to IconDef("bank", "#fcd34d"),
        "discount" to IconDef("tag", "#fca5a5"),
        "orchard" to IconDef("tree", "#4ade80"),
        "longBody" to IconDef("worm", "#a78bfa", "+")
    )

    // emoji -> [glif, renk, dönüş]
    val EMOJI: Map<String, IconDef> = linkedMapOf(
        "🍎" to IconDef("apple", "#ef4444"), "🧺" to IconDef("basket", "#fcd34d"), "🏆" to IconDef("trophy", "#facc15"),
        "🛡" to IconDef("shield", "#60a5fa"), "🌀" to IconDef("portal", "#a78bfa"), "👻" to IconDef("ghost", "#e9d5ff"),
        "🔥" to IconDef("flame", "#fb923c"), "⏸" to IconDef("pause", "#60a5fa"), "▶" to IconDef("play", "#22c55e"),
        "🔊" to IconDef("speaker", "#60a5fa"), "🔇" to IconDef("speakerOff", "#9ca3af"), "🗑" to IconDef("trash", "#e5e7eb"),
        "🌳" to IconDef("tree", "#4ade80"), "🎯" to IconDef("target", "#f87171"), "🖐" to IconDef("move", "#fff8e7"),
        "🐢" to IconDef("turtle", "#4ade80"), "🐍" to IconDef("snake", "#4ade80"), "🐇" to IconDef("rabbit", "#f5f5f4"),
        "⚡" to IconDef("bolt", "#fde047"), "🔒" to IconDef("lock", "#fbbf24"), "✨" to IconDef("star", "#fde047"),
        "⬆" to IconDef("arrow", "#4ade80", "", 270f), "📳" to IconDef("vibrate", "#fde68a"), "📴" to IconDef("vibrateOff", "#d6d3d1")
    )

    private val EMOJI_RE = Regex("(" + EMOJI.keys.joinToString("|") { Regex.escape(it) } + ")")

    /** Metni [(parça, emojiAnahtarı?)] listesine böler. */
    private val richCache = HashMap<String, List<Pair<String, String?>>>()

    fun splitRich(text: String): List<Pair<String, String?>> {
        richCache[text]?.let { return it }
        val res = splitRichUncached(text)
        if (richCache.size > 400) richCache.clear()
        richCache[text] = res
        return res
    }

    private fun splitRichUncached(text: String): List<Pair<String, String?>> {
        val clean = text.replace("️", "")
        val out = ArrayList<Pair<String, String?>>()
        var last = 0
        for (m in EMOJI_RE.findAll(clean)) {
            if (m.range.first > last) out.add(clean.substring(last, m.range.first) to null)
            out.add(m.value to m.value)
            last = m.range.last + 1
        }
        if (last < clean.length) out.add(clean.substring(last) to null)
        return out
    }

    // ---- ayrıştırılmış öğe önbelleği ----
    private class Elem(val path: Path, val fill: String?, val stroke: String?, val strokeW: Float?)

    private val parsed = HashMap<String, List<Elem>>()
    private val tagRe = Regex("<(path|circle|rect)\\s([^>]*?)/?>")
    private val attrRe = Regex("([a-zA-Z][\\w-]*)=\"([^\"]*)\"")

    private fun elems(name: String): List<Elem> = parsed.getOrPut(name) {
        val src = RAW[name] ?: return@getOrPut emptyList()
        val list = ArrayList<Elem>()
        for (m in tagRe.findAll(src)) {
            val tag = m.groupValues[1]
            val attrs = HashMap<String, String>()
            for (a in attrRe.findAll(m.groupValues[2])) attrs[a.groupValues[1]] = a.groupValues[2]
            val path = Path()
            when (tag) {
                "path" -> path.set(SvgPath.parse(attrs["d"] ?: ""))
                "circle" -> path.addCircle(
                    attrs["cx"]!!.toFloat(), attrs["cy"]!!.toFloat(), attrs["r"]!!.toFloat(), Path.Direction.CW
                )
                "rect" -> {
                    val x = attrs["x"]!!.toFloat(); val y = attrs["y"]!!.toFloat()
                    val w = attrs["width"]!!.toFloat(); val h = attrs["height"]!!.toFloat()
                    val r = attrs["rx"]?.toFloat() ?: 0f
                    path.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW)
                }
            }
            list.add(Elem(path, attrs["fill"], attrs["stroke"], attrs["stroke-width"]?.toFloat()))
        }
        list
    }

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** 32x32 birim uzayında glif çizer (mevcut canvas dönüşümüne göre). */
    fun drawGlyph(c: Canvas, name: String, fill: Int, strokeW: Float, rot: Float) {
        val list = elems(name)
        c.save()
        if (rot != 0f) c.rotate(rot, 16f, 16f)
        for (e in list) {
            val fa = e.fill
            val f = if (fa == null) fill else if (fa == "none") 0 else Pal.c(fa)
            if (f != 0) {
                fillP.color = f
                c.drawPath(e.path, fillP)
            }
            val sa = e.stroke
            if (sa != "none") {
                strokeP.color = if (sa == null) Pal.INK else Pal.c(sa)
                strokeP.strokeWidth = e.strokeW ?: strokeW
                c.drawPath(e.path, strokeP)
            }
        }
        c.restore()
    }

    /** Yetenek ikonu: sol-üst (x,y), kenar uzunluğu size. */
    fun drawSkillIcon(c: Canvas, skillId: String, x: Float, y: Float, size: Float) {
        val d = ICONS[skillId] ?: return
        c.save()
        c.translate(x, y)
        val s = size / 32f
        c.scale(s, s)
        drawGlyph(c, d.shape, Pal.c(d.color), 2f, d.rot)
        if (d.badge.isNotEmpty()) {
            fillP.color = Color.WHITE
            c.drawCircle(25f, 25f, 7f, fillP)
            strokeP.color = Pal.INK
            strokeP.strokeWidth = 1.8f
            c.drawCircle(25f, 25f, 7f, strokeP)
            textP.color = Pal.INK
            textP.textSize = if (d.badge.length > 1) 8f else 10f
            c.drawText(d.badge, 25f, 28.4f, textP)
        }
        c.restore()
    }

    /** Emoji karşılığı çizgi film ikonu. (x,y) sol-üst, size = görünür kenar. */
    fun drawEmoji(c: Canvas, key: String, x: Float, y: Float, size: Float) {
        val d = EMOJI[key] ?: return
        c.save()
        c.translate(x, y)
        val s = size / 38f
        c.scale(s, s)
        c.translate(3f, 3f)
        drawGlyph(c, d.shape, Pal.c(d.color), 2.6f, d.rot)
        c.restore()
    }
}
