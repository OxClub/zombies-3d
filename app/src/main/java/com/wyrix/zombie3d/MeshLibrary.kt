package com.wyrix.zombie3d

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object MeshLibrary {

    class Mesh(val buffer: FloatBuffer, val vertexCount: Int) {
        val strideBytes = 8 * 4
    }

    val box: Mesh = buildBoxMesh()
    val cylinder: Mesh = buildCylinderMesh(24)
    val sphere: Mesh = buildSphereMesh(24, 18)
    val disc: Mesh = buildDiscMesh(24)
    val bulletMesh: Mesh = buildBulletMesh()
    val muzzleFlashMesh: Mesh = buildMuzzleFlashMesh()
    val bloodShardMesh: Mesh = buildBloodShardMesh()

    val humanLimbMesh: Mesh = buildLoftedMesh(
        rings = listOf(
            Triple(-0.50f, 0.15f, 0.17f),
            Triple(-0.22f, 0.20f, 0.22f),
            Triple( 0.00f, 0.17f, 0.18f),
            Triple( 0.28f, 0.24f, 0.25f),
            Triple( 0.48f, 0.26f, 0.26f),
            Triple( 0.50f, 0.04f, 0.04f)
        ),
        slices = 18
    )

    val bootMesh: Mesh = buildLoftedMesh(
        rings = listOf(
            Triple(-0.50f, 0.16f, 0.28f),
            Triple(-0.10f, 0.15f, 0.26f),
            Triple( 0.30f, 0.14f, 0.20f),
            Triple( 0.50f, 0.12f, 0.15f)
        ),
        slices = 14
    )

    fun loadObj(context: Context, assetPath: String): Mesh? {
        return try {
            val positions = ArrayList<FloatArray>()
            val normals = ArrayList<FloatArray>()
            val uvs = ArrayList<FloatArray>()
            val out = ArrayList<Float>()

            BufferedReader(InputStreamReader(context.assets.open(assetPath))).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0]) {
                        "v" -> positions.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                        "vn" -> normals.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                        "vt" -> uvs.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat()))
                        "f" -> {
                            val faceVerts = parts.drop(1)
                            for (t in 1 until faceVerts.size - 1) {
                                val tri = arrayOf(faceVerts[0], faceVerts[t], faceVerts[t + 1])
                                val p0 = positions[tri[0].split("/")[0].toInt() - 1]
                                val p1 = positions[tri[1].split("/")[0].toInt() - 1]
                                val p2 = positions[tri[2].split("/")[0].toInt() - 1]
                                val ux = p1[0] - p0[0]; val uy = p1[1] - p0[1]; val uz = p1[2] - p0[2]
                                val vx = p2[0] - p0[0]; val vy = p2[1] - p0[1]; val vz = p2[2] - p0[2]
                                var fnx = uy * vz - uz * vy
                                var fny = uz * vx - ux * vz
                                var fnz = ux * vy - uy * vx
                                val fl = maxOf(0.0001f, hypot(hypot(fnx, fny), fnz))
                                fnx /= fl; fny /= fl; fnz /= fl

                                for (token in tri) {
                                    val idx = token.split("/")
                                    val vi = idx[0].toInt() - 1
                                    val ti = if (idx.size > 1 && idx[1].isNotEmpty()) idx[1].toInt() - 1 else -1
                                    val ni = if (idx.size > 2 && idx[2].isNotEmpty()) idx[2].toInt() - 1 else -1
                                    val p = positions[vi]
                                    val uv = if (ti in uvs.indices) uvs[ti] else floatArrayOf((p[0] + 0.5f), 1f - (p[1] * 0.55f))
                                    val n = if (ni in normals.indices) normals[ni] else floatArrayOf(fnx, fny, fnz)
                                    out.add(p[0]); out.add(p[1]); out.add(p[2])
                                    out.add(n[0]); out.add(n[1]); out.add(n[2])
                                    out.add(uv[0]); out.add(uv[1])
                                }
                            }
                        }
                    }
                }
            }
            val arr = out.toFloatArray()
            Mesh(toFloatBuffer(arr), arr.size / 8)
        } catch (_: Exception) {
            null
        }
    }

    // Jagged asymmetric 3D blood droplet/splatter shard (replaces round spheres!)
    private fun buildBloodShardMesh(): Mesh {
        val verts = ArrayList<Float>()
        fun p(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            verts.add(x); verts.add(y); verts.add(z)
            verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(0.5f); verts.add(0.5f)
        }
        p(0f, 0.7f, 0f, 0f, 1f, 0f); p(-0.45f, -0.3f, 0.35f, -0.7f, 0f, 0.7f); p(0.55f, -0.2f, 0.25f, 0.7f, 0f, 0.7f)
        p(0f, 0.7f, 0f, 0f, 1f, 0f); p(0.55f, -0.2f, 0.25f, 0.7f, 0f, 0.7f); p(0.1f, -0.4f, -0.6f, 0f, 0f, -1f)
        p(0f, 0.7f, 0f, 0f, 1f, 0f); p(0.1f, -0.4f, -0.6f, 0f, 0f, -1f); p(-0.45f, -0.3f, 0.35f, -0.7f, 0f, 0.7f)
        p(0f, -0.8f, 0f, 0f, -1f, 0f); p(0.55f, -0.2f, 0.25f, 0.7f, -0.5f, 0.7f); p(-0.45f, -0.3f, 0.35f, -0.7f, -0.5f, 0.7f)
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun buildBulletMesh(): Mesh {
        val verts = ArrayList<Float>()
        fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            verts.add(x); verts.add(y); verts.add(z)
            verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(0.5f); verts.add(0.5f)
        }
        val segs = 10
        val r = 0.5f
        for (i in 0 until segs) {
            val a0 = (i.toFloat() / segs) * 2.0 * PI
            val a1 = ((i + 1).toFloat() / segs) * 2.0 * PI
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()

            push(c0 * r, s0 * r, -0.5f, c0, s0, 0f)
            push(c1 * r, s1 * r, -0.5f, c1, s1, 0f)
            push(c1 * r, s1 * r,  0.15f, c1, s1, 0f)

            push(c0 * r, s0 * r, -0.5f, c0, s0, 0f)
            push(c1 * r, s1 * r,  0.15f, c1, s1, 0f)
            push(c0 * r, s0 * r,  0.15f, c0, s0, 0f)

            push(c0 * r, s0 * r, 0.15f, c0, s0, 0.4f)
            push(c1 * r, s1 * r, 0.15f, c1, s1, 0.4f)
            push(0f, 0f, 0.55f, 0f, 0f, 1f)
        }
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun buildMuzzleFlashMesh(): Mesh {
        val verts = ArrayList<Float>()
        fun addFin(rx: Float, ry: Float, len: Float) {
            fun p(x: Float, y: Float, z: Float) {
                verts.add(x); verts.add(y); verts.add(z)
                verts.add(0f); verts.add(1f); verts.add(0f)
                verts.add(0.5f); verts.add(0.5f)
            }
            p(0f, 0f, 0f); p(rx, ry, len * 0.35f); p(0f, 0f, len)
            p(0f, 0f, 0f); p(0f, 0f, len); p(-rx, -ry, len * 0.35f)
            p(0f, 0f, 0.05f); p(rx * 1.5f, ry * 1.5f, 0.18f); p(0f, 0f, 0.35f)
            p(0f, 0f, 0.05f); p(0f, 0f, 0.35f); p(-rx * 1.5f, -ry * 1.5f, 0.18f)
        }
        addFin(0.45f, 0f, 1.0f)
        addFin(0f, 0.45f, 1.0f)
        addFin(0.32f, 0.32f, 0.85f)
        addFin(-0.32f, 0.32f, 0.85f)
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun buildLoftedMesh(rings: List<Triple<Float, Float, Float>>, slices: Int): Mesh {
        val verts = ArrayList<Float>()
        fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            val len = maxOf(0.0001f, hypot(hypot(nx, ny), nz))
            verts.add(x); verts.add(y); verts.add(z)
            verts.add(nx / len); verts.add(ny / len); verts.add(nz / len)
            verts.add(u); verts.add(v)
        }

        for (rIdx in 0 until rings.size - 1) {
            val (y0, rx0, rz0) = rings[rIdx]
            val (y1, rx1, rz1) = rings[rIdx + 1]
            val v0 = 1f - rIdx.toFloat() / (rings.size - 1)
            val v1 = 1f - (rIdx + 1).toFloat() / (rings.size - 1)
            val dy = y1 - y0
            val dr = ((rx1 - rx0) + (rz1 - rz0)) * 0.5f

            for (s in 0 until slices) {
                val u0 = s.toFloat() / slices
                val u1 = (s + 1).toFloat() / slices
                val a0 = u0 * 2.0 * PI
                val a1 = u1 * 2.0 * PI
                val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
                val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()

                val p00x = c0 * rx0; val p00z = s0 * rz0
                val p10x = c1 * rx0; val p10z = s1 * rz0
                val p11x = c1 * rx1; val p11z = s1 * rz1
                val p01x = c0 * rx1; val p01z = s0 * rz1

                val slopeY = -dr / maxOf(0.01f, dy)

                push(p00x, y0, p00z, c0, slopeY, s0, u0, v0)
                push(p10x, y0, p10z, c1, slopeY, s1, u1, v0)
                push(p11x, y1, p11z, c1, slopeY, s1, u1, v1)

                push(p00x, y0, p00z, c0, slopeY, s0, u0, v0)
                push(p11x, y1, p11z, c1, slopeY, s1, u1, v1)
                push(p01x, y1, p01z, c0, slopeY, s0, u0, v1)
            }
        }
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun toFloatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(arr)
                position(0)
            }

    private fun buildBoxMesh(): Mesh {
        val d = floatArrayOf(
            -0.5f, -0.5f,  0.5f,  0f,  0f,  1f, 0f, 1f,
             0.5f, -0.5f,  0.5f,  0f,  0f,  1f, 1f, 1f,
             0.5f,  0.5f,  0.5f,  0f,  0f,  1f, 1f, 0f,
            -0.5f, -0.5f,  0.5f,  0f,  0f,  1f, 0f, 1f,
             0.5f,  0.5f,  0.5f,  0f,  0f,  1f, 1f, 0f,
            -0.5f,  0.5f,  0.5f,  0f,  0f,  1f, 0f, 0f,

             0.5f, -0.5f, -0.5f,  0f,  0f, -1f, 0f, 1f,
            -0.5f, -0.5f, -0.5f,  0f,  0f, -1f, 1f, 1f,
            -0.5f,  0.5f, -0.5f,  0f,  0f, -1f, 1f, 0f,
             0.5f, -0.5f, -0.5f,  0f,  0f, -1f, 0f, 1f,
            -0.5f,  0.5f, -0.5f,  0f,  0f, -1f, 1f, 0f,
             0.5f,  0.5f, -0.5f,  0f,  0f, -1f, 0f, 0f,

            -0.5f, -0.5f, -0.5f, -1f,  0f,  0f, 0f, 1f,
            -0.5f, -0.5f,  0.5f, -1f,  0f,  0f, 1f, 1f,
            -0.5f,  0.5f,  0.5f, -1f,  0f,  0f, 1f, 0f,
            -0.5f, -0.5f, -0.5f, -1f,  0f,  0f, 0f, 1f,
            -0.5f,  0.5f,  0.5f, -1f,  0f,  0f, 1f, 0f,
            -0.5f,  0.5f, -0.5f, -1f,  0f,  0f, 0f, 0f,

             0.5f, -0.5f,  0.5f,  1f,  0f,  0f, 0f, 1f,
             0.5f, -0.5f, -0.5f,  1f,  0f,  0f, 1f, 1f,
             0.5f,  0.5f, -0.5f,  1f,  0f,  0f, 1f, 0f,
             0.5f, -0.5f,  0.5f,  1f,  0f,  0f, 0f, 1f,
             0.5f,  0.5f, -0.5f,  1f,  0f,  0f, 1f, 0f,
             0.5f,  0.5f,  0.5f,  1f,  0f,  0f, 0f, 0f,

            -0.5f,  0.5f,  0.5f,  0f,  1f,  0f, 0f, 1f,
             0.5f,  0.5f,  0.5f,  0f,  1f,  0f, 1f, 1f,
             0.5f,  0.5f, -0.5f,  0f,  1f,  0f, 1f, 0f,
            -0.5f,  0.5f,  0.5f,  0f,  1f,  0f, 0f, 1f,
             0.5f,  0.5f, -0.5f,  0f,  1f,  0f, 1f, 0f,
            -0.5f,  0.5f, -0.5f,  0f,  1f,  0f, 0f, 0f,

            -0.5f, -0.5f, -0.5f,  0f, -1f,  0f, 0f, 1f,
             0.5f, -0.5f, -0.5f,  0f, -1f,  0f, 1f, 1f,
             0.5f, -0.5f,  0.5f,  0f, -1f,  0f, 1f, 0f,
            -0.5f, -0.5f, -0.5f,  0f, -1f,  0f, 0f, 1f,
             0.5f, -0.5f,  0.5f,  0f, -1f,  0f, 1f, 0f,
            -0.5f, -0.5f,  0.5f,  0f, -1f,  0f, 0f, 0f
        )
        return Mesh(toFloatBuffer(d), 36)
    }

    private fun buildCylinderMesh(segments: Int): Mesh {
        val verts = ArrayList<Float>()
        fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            verts.add(x); verts.add(y); verts.add(z)
            verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(u); verts.add(v)
        }
        val r = 0.5f
        for (i in 0 until segments) {
            val u0 = i.toFloat() / segments
            val u1 = (i + 1).toFloat() / segments
            val a0 = u0 * 2.0 * PI
            val a1 = u1 * 2.0 * PI
            val nx0 = cos(a0).toFloat(); val nz0 = sin(a0).toFloat()
            val nx1 = cos(a1).toFloat(); val nz1 = sin(a1).toFloat()
            val x0 = nx0 * r; val z0 = nz0 * r
            val x1 = nx1 * r; val z1 = nz1 * r

            push(x0, -0.5f, z0, nx0, 0f, nz0, u0, 1f)
            push(x1, -0.5f, z1, nx1, 0f, nz1, u1, 1f)
            push(x1,  0.5f, z1, nx1, 0f, nz1, u1, 0f)

            push(x0, -0.5f, z0, nx0, 0f, nz0, u0, 1f)
            push(x1,  0.5f, z1, nx1, 0f, nz1, u1, 0f)
            push(x0,  0.5f, z0, nx0, 0f, nz0, u0, 0f)

            push(0f, 0.5f, 0f, 0f, 1f, 0f, 0.5f, 0.5f)
            push(x0, 0.5f, z0, 0f, 1f, 0f, nx0 * 0.5f + 0.5f, nz0 * 0.5f + 0.5f)
            push(x1, 0.5f, z1, 0f, 1f, 0f, nx1 * 0.5f + 0.5f, nz1 * 0.5f + 0.5f)

            push(0f, -0.5f, 0f, 0f, -1f, 0f, 0.5f, 0.5f)
            push(x1, -0.5f, z1, 0f, -1f, 0f, nx1 * 0.5f + 0.5f, nz1 * 0.5f + 0.5f)
            push(x0, -0.5f, z0, 0f, -1f, 0f, nx0 * 0.5f + 0.5f, nz0 * 0.5f + 0.5f)
        }
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun buildSphereMesh(slices: Int, stacks: Int): Mesh {
        val verts = ArrayList<Float>()
        fun push(nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
            verts.add(nx * 0.5f); verts.add(ny * 0.5f); verts.add(nz * 0.5f)
            verts.add(nx); verts.add(ny); verts.add(nz)
            verts.add(u); verts.add(v)
        }
        for (st in 0 until stacks) {
            val v0 = st.toFloat() / stacks
            val v1 = (st + 1).toFloat() / stacks
            val phi0 = (v0 - 0.5) * PI
            val phi1 = (v1 - 0.5) * PI
            val y0 = sin(phi0).toFloat(); val r0 = cos(phi0).toFloat()
            val y1 = sin(phi1).toFloat(); val r1 = cos(phi1).toFloat()

            for (sl in 0 until slices) {
                val u0 = sl.toFloat() / slices
                val u1 = (sl + 1).toFloat() / slices
                val th0 = u0 * 2.0 * PI
                val th1 = u1 * 2.0 * PI
                val c0 = cos(th0).toFloat(); val s0 = sin(th0).toFloat()
                val c1 = cos(th1).toFloat(); val s1 = sin(th1).toFloat()

                push(r0 * c0, y0, r0 * s0, u0, 1f - v0)
                push(r0 * c1, y0, r0 * s1, u1, 1f - v0)
                push(r1 * c1, y1, r1 * s1, u1, 1f - v1)

                push(r0 * c0, y0, r0 * s0, u0, 1f - v0)
                push(r1 * c1, y1, r1 * s1, u1, 1f - v1)
                push(r1 * c0, y1, r1 * s0, u0, 1f - v1)
            }
        }
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }

    private fun buildDiscMesh(segments: Int): Mesh {
        val verts = ArrayList<Float>()
        fun push(x: Float, z: Float, u: Float, v: Float) {
            verts.add(x); verts.add(0f); verts.add(z)
            verts.add(0f); verts.add(1f); verts.add(0f)
            verts.add(u); verts.add(v)
        }
        for (i in 0 until segments) {
            val a0 = (i.toFloat() / segments) * 2.0 * PI
            val a1 = ((i + 1).toFloat() / segments) * 2.0 * PI
            val x0 = cos(a0).toFloat() * 0.5f; val z0 = sin(a0).toFloat() * 0.5f
            val x1 = cos(a1).toFloat() * 0.5f; val z1 = sin(a1).toFloat() * 0.5f
            push(0f, 0f, 0.5f, 0.5f)
            push(x0, z0, x0 + 0.5f, z0 + 0.5f)
            push(x1, z1, x1 + 0.5f, z1 + 0.5f)
        }
        val arr = verts.toFloatArray()
        return Mesh(toFloatBuffer(arr), arr.size / 8)
    }
}
