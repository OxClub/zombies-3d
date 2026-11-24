package com.wyrix.zombie3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import kotlin.random.Random

object TextureGenerator {

    fun loadAssetTexture(context: Context, path: String, repeat: Boolean = true, sample: Int = 1): Int {
        return try {
            context.assets.open(path).use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inSampleSize = sample
                }
                val bmp = BitmapFactory.decodeStream(stream, null, opts) ?: return 0
                uploadTexture(bmp, repeat)
            }
        } catch (_: Exception) {
            0
        }
    }

    fun createZombieSkinTexture(): Int {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(142, 134, 124))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(505)
        for (i in 0 until 120) {
            p.color = when (i % 4) {
                0 -> Color.argb(145, 138, 18, 22)
                1 -> Color.argb(85, 66, 62, 58)
                2 -> Color.argb(80, 168, 158, 146)
                else -> Color.argb(105, 98, 22, 22)
            }
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, 4f + rnd.nextFloat() * 14f, p)
        }
        return uploadTexture(bmp, true)
    }

    fun createClothingTexture(): Int {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(198, 204, 210))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(38, 35, 42, 52)
        for (y in 0 until size step 6) {
            c.drawRect(0f, y.toFloat(), size.toFloat(), y + 2f, p)
        }
        p.color = Color.rgb(38, 42, 50)
        c.drawRect(size * 0.46f, 0f, size * 0.54f, size.toFloat(), p)
        val rnd = Random(777)
        for (i in 0 until 60) {
            p.color = Color.argb(140, 128, 14, 18)
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, 4f + rnd.nextFloat() * 12f, p)
        }
        return uploadTexture(bmp, true)
    }

    fun createShadowTexture(): Int {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        for (r in 60 downTo 2 step 2) {
            val alpha = ((1f - r / 60f) * 165).toInt()
            p.color = Color.argb(alpha, 0, 0, 0)
            c.drawCircle(cx, cx, r.toFloat(), p)
        }
        return uploadTexture(bmp, false)
    }

    /** Public upload used by ModelBank (keeps bitmap recycling in one place). */
    fun uploadBitmap(bmp: Bitmap, repeat: Boolean): Int = uploadTexture(bmp, repeat)

    /** Bright muzzle-flash star: hot white core, orange body, spiky rays. Used additively. */
    fun createFlashTexture(): Int {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        // soft orange glow
        for (r in 62 downTo 2 step 2) {
            val t = 1f - r / 62f
            p.color = Color.argb((t * t * 200).toInt().coerceIn(0, 255), 255, (120 + 90 * t).toInt(), (20 + 60 * t).toInt())
            c.drawCircle(cx, cx, r.toFloat(), p)
        }
        // rays
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        val rnd = Random(31)
        for (i in 0 until 9) {
            val a = i * (Math.PI * 2 / 9) + rnd.nextFloat() * 0.3
            val len = if (i % 2 == 0) 62f else 40f
            p.strokeWidth = if (i % 2 == 0) 5f else 3f
            p.color = Color.argb(235, 255, 200, 90)
            c.drawLine(cx, cx, cx + Math.cos(a).toFloat() * len, cx + Math.sin(a).toFloat() * len, p)
        }
        p.style = Paint.Style.FILL
        p.color = Color.argb(255, 255, 250, 225)
        c.drawCircle(cx, cx, 13f, p)
        return uploadTexture(bmp, false)
    }

    /** Soft grey puff for gun smoke. */
    fun createSmokeTexture(): Int {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        for (r in 31 downTo 1) {
            val t = 1f - r / 31f
            p.color = Color.argb((t * 70).toInt().coerceIn(0, 255), 205, 205, 205)
            c.drawCircle(cx, cx, r.toFloat(), p)
        }
        return uploadTexture(bmp, false)
    }

    /** Tiling facade: concrete / brick with dark broken windows and a few dim warm lights. */
    fun createBuildingTexture(): Int {
        val w = 256
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(150, 146, 138))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(909)
        // wall grime
        for (i in 0 until 260) {
            p.color = Color.argb(18 + rnd.nextInt(26), 40, 36, 30)
            c.drawRect(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * w, rnd.nextFloat() * h, p)
        }
        // windows: 4 columns x 4 rows
        for (row in 0 until 4) for (col in 0 until 4) {
            val x = col * 64f + 12f
            val y = row * 64f + 10f
            val lit = rnd.nextInt(14) == 0
            p.color = if (lit) Color.rgb(176, 138, 70) else Color.rgb(18 + rnd.nextInt(12), 24 + rnd.nextInt(12), 28 + rnd.nextInt(14))
            c.drawRect(x, y, x + 40f, y + 44f, p)
            p.color = Color.argb(150, 20, 20, 18)
            c.drawRect(x - 3f, y - 3f, x + 43f, y, p)
            p.color = Color.argb(110, 210, 205, 195)
            c.drawRect(x - 3f, y + 44f, x + 43f, y + 48f, p)
            if (!lit && rnd.nextBoolean()) { // cracked glass streak
                p.color = Color.argb(90, 190, 200, 205)
                c.drawLine(x + 4f, y + 4f, x + 30f + rnd.nextInt(8), y + 40f, p)
            }
        }
        // rain streaks
        for (i in 0 until 40) {
            val x = rnd.nextFloat() * w
            p.color = Color.argb(34, 20, 20, 20)
            c.drawRect(x, rnd.nextFloat() * h * 0.5f, x + 2f, h.toFloat(), p)
        }
        return uploadTexture(bmp, true)
    }

    private fun uploadTexture(bmp: Bitmap, repeat: Boolean): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        val wrap = if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return id
    }
}
