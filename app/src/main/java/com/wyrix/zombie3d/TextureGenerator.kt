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

    fun loadAssetTexture(context: Context, path: String, repeat: Boolean = true): Int {
        return try {
            context.assets.open(path).use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
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
