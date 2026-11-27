package com.kivrik.game

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor

/** Kalıcı oyun durumu (kayıt dosyası). */
class GameState {
    var apples = 0L
    var totalApplesCollected = 0L
    var runs = 0
    var bestRun = 0L
    val skills = HashMap<String, Int>()
    var speedMode = "normal" // slow | normal | turbo
    var soundEnabled = true
    var treeIntroSeen = false
    var hapticsEnabled = true
    var swipeUsed = false
    var speedTipDone = false

    init {
        for (s in Tree.ALL) skills[s.id] = if (s.id == "right") 1 else 0
    }

    fun lvl(id: String): Int = skills[id] ?: 0
}

object Store {
    private const val PREFS = "kivrik_yilan_native_v1"
    private const val KEY = "save"

    fun load(ctx: Context): GameState {
        val st = GameState()
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return st
            val o = JSONObject(raw)
            st.apples = maxOf(0L, o.optLong("apples", 0))
            st.totalApplesCollected = maxOf(0L, o.optLong("totalApplesCollected", 0))
            st.runs = maxOf(0, o.optInt("runs", 0))
            st.bestRun = maxOf(0L, o.optLong("bestRun", 0))
            val sk = o.optJSONObject("skills")
            for (s in Tree.ALL) {
                val v = sk?.optInt(s.id, 0) ?: 0
                st.skills[s.id] = v.coerceIn(0, s.maxLevel)
            }
            st.skills["right"] = 1
            val sm = o.optString("speedMode", "normal")
            st.speedMode = if (sm == "slow" || sm == "normal" || sm == "turbo") sm else "normal"
            st.soundEnabled = o.optBoolean("soundEnabled", true)
            st.hapticsEnabled = o.optBoolean("hapticsEnabled", true)
            st.swipeUsed = o.optBoolean("swipeUsed", false)
            st.speedTipDone = o.optBoolean("speedTipDone", false)
            st.treeIntroSeen = if (o.has("treeIntroSeen")) o.optBoolean("treeIntroSeen") else (st.runs > 1 || st.bestRun > 0)
        } catch (e: Exception) {
            return GameState()
        }
        return st
    }

    fun save(ctx: Context, st: GameState) {
        try {
            val o = JSONObject()
            o.put("apples", st.apples)
            o.put("totalApplesCollected", st.totalApplesCollected)
            o.put("runs", st.runs)
            o.put("bestRun", st.bestRun)
            val sk = JSONObject()
            for ((k, v) in st.skills) sk.put(k, v)
            o.put("skills", sk)
            o.put("speedMode", st.speedMode)
            o.put("soundEnabled", st.soundEnabled)
            o.put("hapticsEnabled", st.hapticsEnabled)
            o.put("swipeUsed", st.swipeUsed)
            o.put("speedTipDone", st.speedTipDone)
            o.put("treeIntroSeen", st.treeIntroSeen)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/** 12500 -> 12.5K gibi kısa sayı biçimi. */
fun fmt(n: Long): String {
    val v = maxOf(0L, n).toDouble()
    fun f(x: Double, u: String) = String.format(Locale.US, "%.1f", x).removeSuffix(".0") + u
    return when {
        v >= 1e9 -> f(v / 1e9, "B")
        v >= 1e6 -> f(v / 1e6, "M")
        v >= 1e4 -> f(v / 1e3, "K")
        else -> floor(v).toLong().toString()
    }
}

fun fmt(n: Int): String = fmt(n.toLong())
