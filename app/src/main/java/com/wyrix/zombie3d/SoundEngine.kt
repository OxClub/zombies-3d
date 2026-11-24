package com.wyrix.zombie3d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * All sounds are synthesised in code. Everything heavy happens on a background thread
 * so the game window opens instantly (this used to freeze start-up for 10+ seconds).
 */
class SoundEngine(private val context: Context) {
    var enabled = true
    private val sampleRate = 32000
    private val playExec = Executors.newFixedThreadPool(2)
    private val releaser: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val activeStreams = AtomicInteger(0)
    private val maxConcurrent = 10
    @Volatile private var bgmPlayer: MediaPlayer? = null
    @Volatile private var musicWanted = false

    private var lastShotMs = 0L
    private var lastHitMs = 0L
    private var lastGroanMs = 0L
    private var lastHurtMs = 0L

    @Volatile private var gunPcm: Array<ShortArray>? = null
    @Volatile private var hitPcm: ShortArray? = null
    @Volatile private var boomPcm: ShortArray? = null
    @Volatile private var hurtPcm: ShortArray? = null
    @Volatile private var pickupPcm: ShortArray? = null
    @Volatile private var groan1Pcm: ShortArray? = null
    @Volatile private var groan2Pcm: ShortArray? = null
    @Volatile private var roarPcm: ShortArray? = null
    @Volatile private var reloadPcm: ShortArray? = null
    @Volatile private var clickPcm: ShortArray? = null

    init {
        Thread({
            try {
                // order matters: gun + hit sounds first so they are ready almost immediately
                // Real recorded shots (assets/sfx/shot_0..4.wav); synthesized sound is only a fallback.
                val synth = arrayOf(
                    buildGunPcm(0.40f, 150f, 1.00f, 150f, 0.55f, 16f, 0.65f, 70f, 0.30f),
                    buildGunPcm(0.30f, 170f, 0.80f, 190f, 0.40f, 20f, 0.50f, 45f, 0.25f),
                    buildGunPcm(0.45f, 120f, 0.95f, 120f, 0.70f, 13f, 0.80f, 85f, 0.35f),
                    buildGunPcm(0.55f, 100f, 1.00f, 85f, 0.95f, 10f, 0.90f, 110f, 0.40f),
                    buildGunPcm(1.10f, 70f, 1.00f, 60f, 1.15f, 5f, 1.00f, 170f, 0.50f)
                )
                gunPcm = Array(synth.size) { i -> loadWav("sfx/shot_$i.wav") ?: synth[i] }
                hitPcm = buildFleshHitPcm()
                hurtPcm = buildHurtPcm()
                pickupPcm = buildPickupPcm()
                clickPcm = buildClickPcm(1)
                reloadPcm = buildClickPcm(3)
                boomPcm = buildExplosionPcm()
                groan1Pcm = buildZombieGroanPcm(62.0, 44.0, 0.65)
                groan2Pcm = buildZombieGroanPcm(84.0, 52.0, 0.55)
                roarPcm = buildBossRoarPcm()
            } catch (_: Throwable) {
            }
            try {
                val mp = MediaPlayer.create(context, R.raw.horror_bgm)?.apply {
                    isLooping = true
                    setVolume(0.35f, 0.35f)
                }
                bgmPlayer = mp
                if (musicWanted && enabled) mp?.start()
            } catch (_: Throwable) {
            }
        }, "sound-init").start()
    }

    /** Minimal WAV reader: 16-bit PCM mono at the engine sample rate. Returns null on any problem. */
    private fun loadWav(path: String): ShortArray? {
        return try {
            val b = context.assets.open(path).use { it.readBytes() }
            var pos = 12
            var dataStart = -1
            var dataLen = 0
            var rate = 0
            var channels = 1
            var bits = 16
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val len = (b[pos + 4].toInt() and 0xFF) or ((b[pos + 5].toInt() and 0xFF) shl 8) or
                    ((b[pos + 6].toInt() and 0xFF) shl 16) or ((b[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt ") {
                    channels = (b[pos + 10].toInt() and 0xFF) or ((b[pos + 11].toInt() and 0xFF) shl 8)
                    rate = (b[pos + 12].toInt() and 0xFF) or ((b[pos + 13].toInt() and 0xFF) shl 8) or
                        ((b[pos + 14].toInt() and 0xFF) shl 16) or ((b[pos + 15].toInt() and 0xFF) shl 24)
                    bits = (b[pos + 22].toInt() and 0xFF) or ((b[pos + 23].toInt() and 0xFF) shl 8)
                } else if (id == "data") {
                    dataStart = pos + 8
                    dataLen = minOf(len, b.size - dataStart)
                    break
                }
                pos += 8 + len + (len and 1)
            }
            if (dataStart < 0 || bits != 16 || channels != 1 || rate != sampleRate) return null
            val n = dataLen / 2
            val out = ShortArray(n)
            for (i in 0 until n) {
                out[i] = ((b[dataStart + i * 2].toInt() and 0xFF) or (b[dataStart + i * 2 + 1].toInt() shl 8)).toShort()
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    fun resumeMusic() {
        musicWanted = true
        if (!enabled) return
        try {
            if (bgmPlayer?.isPlaying == false) bgmPlayer?.start()
        } catch (_: Exception) {
        }
    }

    fun pauseMusic() {
        musicWanted = false
        try {
            if (bgmPlayer?.isPlaying == true) bgmPlayer?.pause()
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
            bgmPlayer = null
        } catch (_: Exception) {
        }
        playExec.shutdown()
        releaser.shutdown()
    }

    /** gunIndex: 0 M1911, 1 MP5, 2 M4A1, 3 SCAR-H, 4 AWM */
    fun playShot(gunIndex: Int = 0) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotMs < 40L) return
        lastShotMs = now
        val arr = gunPcm ?: return
        enqueue(arr[gunIndex.coerceIn(0, arr.size - 1)])
    }

    fun playReload() {
        if (!enabled) return
        enqueue(reloadPcm)
    }

    fun playEmptyClick() {
        if (!enabled) return
        enqueue(clickPcm)
    }

    fun playHit() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHitMs < 65L) return
        lastHitMs = now
        enqueue(hitPcm)
    }

    fun playExplosion() {
        if (!enabled) return
        enqueue(boomPcm)
    }

    fun playHurt() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHurtMs < 380L) return
        lastHurtMs = now
        enqueue(hurtPcm)
    }

    fun playPickup() {
        if (!enabled) return
        enqueue(pickupPcm)
    }

    fun playHorrorGroan(isBoss: Boolean = false) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (!isBoss && now - lastGroanMs < 1800L) return
        lastGroanMs = now
        val clip = when {
            isBoss -> roarPcm
            Random.nextBoolean() -> groan1Pcm
            else -> groan2Pcm
        }
        enqueue(clip)
    }

    private fun enqueue(pcm: ShortArray?) {
        if (pcm == null) return
        if (activeStreams.get() >= maxConcurrent) return
        activeStreams.incrementAndGet()
        try {
            playExec.execute {
                var track: AudioTrack? = null
                try {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                    track = AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    val t = track
                    val durationMs = (pcm.size * 1000L / sampleRate) + 40L
                    // release later WITHOUT blocking the worker, so sounds overlap naturally
                    releaser.schedule(Runnable {
                        try { t.stop(); t.release() } catch (_: Exception) { }
                        activeStreams.decrementAndGet()
                    }, durationMs, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                    try { track?.release() } catch (_: Exception) { }
                    activeStreams.decrementAndGet()
                }
            }
        } catch (_: Throwable) {
            activeStreams.decrementAndGet()
        }
    }

    /**
     * Layered gunshot: sharp high-passed crack + pitched-down body thump + low rumbling tail + echo.
     */
    private fun buildGunPcm(
        durSec: Float, crackDecay: Float, crackAmp: Float,
        boomHz: Float, boomAmp: Float, boomDecay: Float,
        tailAmp: Float, tailDecay: Float, echoGain: Float
    ): ShortArray {
        val echoDelay = (sampleRate * 0.085f).toInt()
        val dry = (sampleRate * durSec).toInt()
        val total = dry + echoDelay * 2
        val buf = FloatArray(total)
        var phase = 0.0
        var lp = 0f
        var hpPrevIn = 0f
        var hpPrevOut = 0f
        for (i in 0 until dry) {
            val t = i.toFloat() / sampleRate
            val n = Random.nextFloat() * 2f - 1f
            // 1-pole high-pass for the crack
            val hp = 0.92f * (hpPrevOut + n - hpPrevIn)
            hpPrevIn = n; hpPrevOut = hp
            val crack = hp * exp(-t * crackDecay) * crackAmp
            // thump with downward pitch sweep
            val f = boomHz * (0.55f + 0.9f * exp(-t * 38f))
            phase += 2.0 * PI * f / sampleRate
            val boom = sin(phase).toFloat() * exp(-t * boomDecay) * boomAmp
            // rumbling low-passed noise tail
            lp += (n - lp) * 0.07f
            val tail = lp * exp(-t * tailDecay * 0.08f * 10f) * tailAmp * 1.6f
            buf[i] = crack * 0.9f + boom + tail
        }
        // two soft echoes (distant reverb feel)
        for (i in dry + echoDelay * 2 - 1 downTo echoDelay) {
            val src = buf[i - echoDelay]
            buf[i] += src * echoGain * (if (i >= echoDelay * 2) 0.55f else 1f) * 0.5f
        }
        val out = ShortArray(total)
        var fade = 1f
        for (i in 0 until total) {
            if (i > total - 800) fade = (total - i) / 800f
            out[i] = (tanh(buf[i] * 1.6f) * 30000f * fade).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun buildClickPcm(clicks: Int): ShortArray {
        val gap = (sampleRate * 0.16f).toInt()
        val total = gap * clicks + (sampleRate * 0.06f).toInt()
        val out = ShortArray(total)
        for (c in 0 until clicks) {
            val start = c * gap
            val freq = if (c % 2 == 0) 2300.0 else 1500.0
            for (i in 0 until (sampleRate * 0.05f).toInt()) {
                val t = i.toFloat() / sampleRate
                val env = exp(-t * 110f)
                val v = (sin(2.0 * PI * freq * t).toFloat() * 0.6f + (Random.nextFloat() * 2f - 1f) * 0.4f) * env
                val idx = start + i
                if (idx < total) out[idx] = (v * 14000f).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private fun buildFleshHitPcm(): ShortArray {
        val len = (sampleRate * 0.055).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 52f)
            val thud = sin(2.0 * PI * (140.0 - t * 600.0) * t).toFloat()
            (thud * env * 15000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildExplosionPcm(): ShortArray {
        val len = (sampleRate * 0.45).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 7.2f)
            val sub = sin(2.0 * PI * (56.0 - t * 28.0) * t).toFloat()
            val debris = (Random.nextFloat() * 2f - 1f) * 0.85f
            ((sub * 0.8f + debris) * env * 27000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildHurtPcm(): ShortArray {
        val len = (sampleRate * 0.16).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 15f)
            val low = sin(2.0 * PI * (110.0 - t * 180.0) * t).toFloat()
            (low * env * 19000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildPickupPcm(): ShortArray {
        val len = (sampleRate * 0.12).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val freq = if (t < 0.06f) 480.0 else 720.0
            val env = exp(-t * 15f)
            (sin(2.0 * PI * freq * t).toFloat() * env * 13000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildZombieGroanPcm(startHz: Double, endHz: Double, sec: Double): ShortArray {
        val len = (sampleRate * sec).toInt()
        var lpNoise = 0f
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val norm = (t / sec).toFloat()
            val env = sin(PI * norm).toFloat() * (0.75f + 0.25f * sin(2.0 * PI * 7.5 * t).toFloat())
            val pitch = startHz + (endHz - startHz) * norm + sin(2.0 * PI * 5.2 * t) * 4.5
            val f1 = sin(2.0 * PI * pitch * t).toFloat()
            val f2 = sin(2.0 * PI * pitch * 2.01 * t).toFloat() * 0.55f
            val formant = cos(2.0 * PI * 310.0 * t).toFloat() * 0.30f
            val rawNoise = Random.nextFloat() * 2f - 1f
            lpNoise = lpNoise * 0.86f + rawNoise * 0.14f
            val wave = (f1 + f2 + formant + lpNoise * 0.65f) * env
            (wave * 15500f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildBossRoarPcm(): ShortArray {
        val sec = 0.85
        val len = (sampleRate * sec).toInt()
        var lp = 0f
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val norm = (t / sec).toFloat()
            val env = sin(PI * norm).toFloat()
            val freq = 74.0 - norm * 32.0 + sin(2.0 * PI * 11.0 * t) * 6.0
            val sub = sin(2.0 * PI * freq * t).toFloat()
            val growl = sin(2.0 * PI * freq * 1.5 * t).toFloat() * 0.65f
            val raw = Random.nextFloat() * 2f - 1f
            lp = lp * 0.82f + raw * 0.18f
            ((sub + growl + lp * 0.8f) * env * 19500f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }
}
