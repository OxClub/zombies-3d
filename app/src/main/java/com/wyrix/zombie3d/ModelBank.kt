package com.wyrix.zombie3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** One named piece of a model (e.g. "arm_upper_L") with its joint pivot in model space. */
class ModelPart(
    val name: String,
    val mesh: MeshLibrary.Mesh,
    val px: Float,
    val py: Float,
    val pz: Float
)

/** A multi-part OBJ model. Texture is uploaded to GL later on the GL thread. */
class PartModel(val parts: Map<String, ModelPart>, var bitmap: Bitmap?, val jpeg: ByteArray?) {
    var texId = 0
    var texGen = -1
    fun part(name: String): ModelPart? = parts[name]
}

/**
 * Loads every model on a background thread so the game never freezes on start-up.
 * Only the cheap GL texture upload happens later on the GL thread.
 */
object ModelBank {

    val names = listOf(
        "zombie_male", "zombie_female", "zombie_brute", "infected_dog", "player_soldier",
        "gun_m1911", "gun_mp5", "gun_m4a1", "gun_scar", "gun_awm"
    )

    private val models = HashMap<String, PartModel>()

    @Volatile var ready = false
        private set
    @Volatile private var started = false
    @Volatile var progress = 0f
        private set

    fun get(name: String): PartModel? = synchronized(models) { models[name] }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({
            try {
                for ((i, n) in names.withIndex()) {
                    try {
                        val m = loadModel(app, n)
                        if (m != null) synchronized(models) { models[n] = m }
                    } catch (_: Throwable) {
                    }
                    progress = (i + 1f) / names.size
                }
            } finally {
                ready = true
            }
        }, "model-loader").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    /** Called on the GL thread: (re)uploads every model texture for the current GL context. */
    fun uploadTextures(gen: Int) {
        synchronized(models) {
            for (m in models.values) {
                if (m.texGen == gen) continue
                val bmp = m.bitmap ?: m.jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) {
                    m.texId = TextureGenerator.uploadBitmap(bmp, repeat = false) // recycles the bitmap
                    m.bitmap = null
                    m.texGen = gen
                }
            }
        }
    }

    private fun loadModel(context: Context, name: String): PartModel? {
        val bytes = context.assets.open("models/$name.obj").use { it.readBytes() }
        val parts = parseObj(bytes, smoothNormals = !name.startsWith("gun_"))
        if (parts.isEmpty()) return null
        val jpeg = try {
            context.assets.open("models/$name.jpg").use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
        val bmp = jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return PartModel(parts, bmp, jpeg)
    }

    // ---- fast OBJ parser -------------------------------------------------------------------

    private class Cursor(val b: ByteArray) {
        var i = 0
        fun skipSpaces() { while (i < b.size && (b[i] == ' '.code.toByte() || b[i] == '\t'.code.toByte() || b[i] == '\r'.code.toByte())) i++ }
        fun atEol() = i >= b.size || b[i] == '\n'.code.toByte()
        fun nextLine() { while (i < b.size && b[i] != '\n'.code.toByte()) i++; i++ }
        fun token(): String {
            skipSpaces()
            val s = i
            while (i < b.size) {
                val c = b[i]
                if (c == ' '.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte() || c == '\t'.code.toByte()) break
                i++
            }
            return String(b, s, i - s, Charsets.US_ASCII)
        }
    }

    private class PartBuilder(val name: String) {
        var pivot = floatArrayOf(0f, 0f, 0f)
        val faces = ArrayList<IntArray>() // each: v0,t0,v1,t1,v2,t2
    }

    private fun parseObj(bytes: ByteArray, smoothNormals: Boolean): Map<String, ModelPart> {
        val pos = FloatList()
        val uv = FloatList()
        val builders = ArrayList<PartBuilder>()
        var cur: PartBuilder? = null
        var pendingPivot: FloatArray? = null
        val c = Cursor(bytes)
        while (c.i < bytes.size) {
            c.skipSpaces()
            if (c.atEol()) { c.nextLine(); continue }
            val tag = c.token()
            when (tag) {
                "v" -> { pos.add(c.token().toFloat()); pos.add(c.token().toFloat()); pos.add(c.token().toFloat()) }
                "vt" -> { uv.add(c.token().toFloat()); uv.add(c.token().toFloat()) }
                "#" -> {
                    if (c.token() == "pivot") {
                        pendingPivot = floatArrayOf(c.token().toFloat(), c.token().toFloat(), c.token().toFloat())
                    }
                }
                "o", "g" -> {
                    val b = PartBuilder(c.token())
                    if (pendingPivot != null) { b.pivot = pendingPivot; pendingPivot = null }
                    builders.add(b); cur = b
                }
                "f" -> {
                    val b = cur ?: PartBuilder("main").also { builders.add(it); cur = it }
                    val vs = ArrayList<Int>(); val ts = ArrayList<Int>()
                    while (!c.atEol()) {
                        val t = c.token()
                        if (t.isEmpty()) break
                        val sl = t.indexOf('/')
                        if (sl < 0) { vs.add(t.toInt() - 1); ts.add(-1) } else {
                            vs.add(t.substring(0, sl).toInt() - 1)
                            val rest = t.substring(sl + 1)
                            val sl2 = rest.indexOf('/')
                            val ts1 = if (sl2 < 0) rest else rest.substring(0, sl2)
                            ts.add(if (ts1.isEmpty()) -1 else ts1.toInt() - 1)
                        }
                    }
                    for (k in 1 until vs.size - 1) {
                        b.faces.add(intArrayOf(vs[0], ts[0], vs[k], ts[k], vs[k + 1], ts[k + 1]))
                    }
                }
            }
            c.nextLine()
        }

        val p = pos.toArray()
        val t = uv.toArray()
        val out = LinkedHashMap<String, ModelPart>()
        for (b in builders) {
            if (b.faces.isEmpty()) continue
            out[b.name] = buildPart(b, p, t, smoothNormals)
        }
        return out
    }

    private fun key(x: Float, y: Float, z: Float): Long {
        val a = Math.round(x * 2000f).toLong() and 0x1FFFFFL
        val b = Math.round(y * 2000f).toLong() and 0x1FFFFFL
        val c = Math.round(z * 2000f).toLong() and 0x1FFFFFL
        return (a shl 42) or (b shl 21) or c
    }

    private fun buildPart(b: PartBuilder, p: FloatArray, t: FloatArray, smoothNormals: Boolean): ModelPart {
        val nTri = b.faces.size
        val verts = FloatArray(nTri * 3 * 8)
        val faceN = FloatArray(nTri * 3)
        var cx = 0f; var cy = 0f; var cz = 0f; var cn = 0
        val smooth = HashMap<Long, FloatArray>()

        for ((fi, f) in b.faces.withIndex()) {
            val i0 = f[0] * 3; val i1 = f[2] * 3; val i2 = f[4] * 3
            val ux = p[i1] - p[i0]; val uy = p[i1 + 1] - p[i0 + 1]; val uz = p[i1 + 2] - p[i0 + 2]
            val vx = p[i2] - p[i0]; val vy = p[i2 + 1] - p[i0 + 1]; val vz = p[i2 + 2] - p[i0 + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            faceN[fi * 3] = nx; faceN[fi * 3 + 1] = ny; faceN[fi * 3 + 2] = nz
            for (k in 0..2) {
                val vi = f[k * 2] * 3
                cx += p[vi]; cy += p[vi + 1]; cz += p[vi + 2]; cn++
                val kk = key(p[vi], p[vi + 1], p[vi + 2])
                val acc = smooth.getOrPut(kk) { FloatArray(3) }
                acc[0] += nx; acc[1] += ny; acc[2] += nz
            }
        }
        cx /= cn; cy /= cn; cz /= cn

        // Decide whether winding points outward (so lighting is never inverted).
        var outward = 0f
        for ((fi, f) in b.faces.withIndex()) {
            val vi = f[0] * 3
            outward += faceN[fi * 3] * (p[vi] - cx) + faceN[fi * 3 + 1] * (p[vi + 1] - cy) + faceN[fi * 3 + 2] * (p[vi + 2] - cz)
        }
        val flip = if (outward < 0f) -1f else 1f

        var o = 0
        for ((fi, f) in b.faces.withIndex()) {
            for (k in 0..2) {
                val vi = f[k * 2] * 3
                val ti = f[k * 2 + 1] * 2
                val acc = smooth[key(p[vi], p[vi + 1], p[vi + 2])]!!
                var nx: Float; var ny: Float; var nz: Float
                if (smoothNormals) {
                    nx = acc[0] * flip; ny = acc[1] * flip; nz = acc[2] * flip
                } else {
                    nx = faceN[fi * 3] * flip; ny = faceN[fi * 3 + 1] * flip; nz = faceN[fi * 3 + 2] * flip
                }
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= l; ny /= l; nz /= l
                verts[o++] = p[vi]; verts[o++] = p[vi + 1]; verts[o++] = p[vi + 2]
                verts[o++] = nx; verts[o++] = ny; verts[o++] = nz
                if (ti >= 0 && ti + 1 < t.size) {
                    verts[o++] = t[ti]; verts[o++] = 1f - t[ti + 1]
                } else { verts[o++] = 0f; verts[o++] = 0f }
            }
        }
        val fb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(verts); fb.position(0)
        return ModelPart(b.name, MeshLibrary.Mesh(fb, nTri * 3), b.pivot[0], b.pivot[1], b.pivot[2])
    }

    private class FloatList {
        private var a = FloatArray(1 shl 16)
        private var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): FloatArray = a.copyOf(n)
    }
}
