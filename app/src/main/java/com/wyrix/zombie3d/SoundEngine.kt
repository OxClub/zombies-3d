package com.wyrix.zombie3d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SoundEngine(context: Context) {
    var enabled = true
    private val sampleRate = 22050
    private val executor = Executors.newSingleThreadExecutor()
    private val activeStreams = AtomicInteger(0)
    private val maxConcurrent = 5
    private var bgmPlayer: MediaPlayer? = null

    private var lastShotMs = 0L
    private var lastHitMs = 0L
    private var lastGroanMs = 0L
    private var lastHurtMs = 0L

    private val riflePcm = buildRiflePcm()
    private val shotgunPcm = buildShotgunPcm()
    private val hitPcm = buildFleshHitPcm()
    private val boomPcm = buildExplosionPcm()
    private val hurtPcm = buildHurtPcm()
    private val pickupPcm = buildPickupPcm()
    private val groan1Pcm = buildZombieGroanPcm(62.0, 44.0, 0.65)
    private val groan2Pcm = buildZombieGroanPcm(84.0, 52.0, 0.55)
    private val roarPcm = buildBossRoarPcm()

    init {
        try {
            bgmPlayer = MediaPlayer.create(context, R.raw.horror_bgm)?.apply {
                isLooping = true
                setVolume(0.42f, 0.42f)
            }
        } catch (_: Exception) {
        }
    }

    fun resumeMusic() {
        if (!enabled) return
        try {
            if (bgmPlayer?.isPlaying == false) bgmPlayer?.start()
        } catch (_: Exception) {
        }
    }

    fun pauseMusic() {
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
    }

    fun playShot(isShotgun: Boolean = false) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotMs < 55L) return
        lastShotMs = now
        enqueue(if (isShotgun) shotgunPcm else riflePcm)
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

    private fun enqueue(pcm: ShortArray) {
        if (activeStreams.get() >= maxConcurrent) return
        activeStreams.incrementAndGet()
        executor.execute {
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
                val durationMs = (pcm.size * 1000L / sampleRate) + 15L
                Thread.sleep(durationMs)
            } catch (_: Exception) {
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {
                }
                activeStreams.decrementAndGet()
            }
        }
    }

    private fun buildRiflePcm(): ShortArray {
        val len = (sampleRate * 0.11).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 36f)
            val snap = (Random.nextFloat() * 2f - 1f) * 0.72f
            val bass = sin(2.0 * PI * (185.0 * exp(-t * 22f)) * t).toFloat() * 0.85f
            ((snap + bass) * env * 22000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildShotgunPcm(): ShortArray {
        val len = (sampleRate * 0.22).toInt()
        return ShortArray(len) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 16f)
            val blast = (Random.nextFloat() * 2f - 1f) * 0.88f
            val boom = sin(2.0 * PI * (92.0 * exp(-t * 8f)) * t).toFloat() * 0.90f
            ((blast + boom) * env * 26000f).toInt().coerceIn(-32767, 32767).toShort()
        }
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
