package com.kivrik.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Çizgi film ses efektleri. WebAudio osilatörlerinin birebir karşılığı:
 * her efekt başlangıçta PCM olarak sentezlenir, AudioTrack ile çalınır. Harici ses dosyası yok.
 */
object Sfx {
    private const val RATE = 22050

    var enabled = true

    private class Tone(
        val f0: Float, val f1: Float, val dur: Float,
        val type: Char, val vol: Float, val delay: Float
    )

    private val handler = Handler(Looper.getMainLooper())
    private var active = 0
    private val bank = HashMap<String, ShortArray>()

    private fun render(vararg tones: Tone): ShortArray {
        var total = 0f
        for (t in tones) total = max(total, t.delay + t.dur + 0.03f)
        val n = (total * RATE).toInt()
        val mix = FloatArray(n)
        for (t in tones) {
            val start = (t.delay * RATE).toInt()
            val len = ((t.dur + 0.02f) * RATE).toInt()
            var phase = 0.0
            val f1 = max(40f, t.f1)
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val time = i.toFloat() / RATE
                val pos = min(1f, time / t.dur)
                val freq = t.f0 * (f1 / t.f0).pow(pos)
                phase += freq.toDouble() / RATE
                val ph = (phase - Math.floor(phase)).toFloat()
                val w = when (t.type) {
                    's' -> sin(2.0 * PI * ph).toFloat()
                    't' -> 4f * kotlin.math.abs(ph - 0.5f) - 1f
                    'w' -> 2f * ph - 1f
                    else -> if (ph < 0.5f) 1f else -1f // kare
                }
                // zarf: 15ms yükseliş, sonra üstel sönüm
                val g = if (time < 0.015f) 0.001f + (t.vol - 0.001f) * (time / 0.015f)
                else if (time < t.dur) t.vol * (0.001f / t.vol).pow((time - 0.015f) / max(0.001f, t.dur - 0.015f))
                else 0f
                mix[idx] += w * g
            }
        }
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (mix[i].coerceIn(-1f, 1f) * 32000f).toInt().toShort()
        return out
    }

    private fun build() {
        if (bank.isNotEmpty()) return
        bank["eat"] = render(Tone(340f, 620f, 0.08f, 't', 0.14f, 0f), Tone(520f, 880f, 0.09f, 's', 0.11f, 0.055f))
        bank["eatGold"] = render(
            Tone(340f, 620f, 0.08f, 't', 0.14f, 0f), Tone(520f, 880f, 0.09f, 's', 0.11f, 0.055f),
            Tone(880f, 1320f, 0.14f, 't', 0.12f, 0.11f), Tone(1174f, 1760f, 0.18f, 's', 0.10f, 0.18f)
        )
        bank["turn"] = render(Tone(250f, 410f, 0.065f, 's', 0.075f, 0f))
        bank["bounce"] = render(Tone(190f, 520f, 0.16f, 't', 0.14f, 0f), Tone(480f, 310f, 0.12f, 's', 0.10f, 0.12f))
        bank["bonk"] = render(
            Tone(160f, 65f, 0.14f, 'w', 0.13f, 0f), Tone(220f, 460f, 0.12f, 't', 0.13f, 0.06f),
            Tone(460f, 150f, 0.24f, 's', 0.14f, 0.16f)
        )
        bank["pop"] = render(Tone(420f, 840f, 0.09f, 's', 0.13f, 0f), Tone(660f, 1180f, 0.14f, 't', 0.11f, 0.07f))
        bank["hit"] = render(Tone(300f, 190f, 0.05f, 'q', 0.045f, 0f))
        bank["zap"] = render(Tone(1500f, 180f, 0.2f, 'w', 0.08f, 0f), Tone(900f, 120f, 0.14f, 'q', 0.05f, 0.05f))
        bank["boom"] = render(Tone(130f, 40f, 0.25f, 'w', 0.11f, 0f))
        bank["nope"] = render(Tone(210f, 140f, 0.12f, 'q', 0.07f, 0f))
        val notes = floatArrayOf(523f, 659f, 784f, 1046f)
        bank["rain"] = render(*Array(4) { i -> Tone(notes[i], notes[i] * 1.1f, 0.1f, 's', 0.09f, i * 0.05f) })
    }

    /** Uygulama açılırken bir kez çağrılır (sentez ~ birkaç ms). */
    fun init() = build()

    private fun play(key: String) {
        if (!enabled) return
        val data = bank[key] ?: return
        if (active >= 10) return
        try {
            val bytes = data.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            active++
            track.play()
            val ms = (data.size * 1000L / RATE) + 120L
            handler.postDelayed({
                try { track.release() } catch (_: Exception) {}
                active--
            }, ms)
        } catch (_: Exception) {
        }
    }

    fun eat(golden: Boolean = false) = play(if (golden) "eatGold" else "eat")
    fun turn() = play("turn")
    fun bounce() = play("bounce")
    fun bonk() = play("bonk")
    fun pop() = play("pop")
    fun hit() = play("hit")
    fun zap() = play("zap")
    fun boom() = play("boom")
    fun nope() = play("nope")
    fun rain() = play("rain")
}
