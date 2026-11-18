package com.wyrix.zombie3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.*

class SkinnedZombieModel private constructor(
    val name: String,
    private val vertexBuffer: FloatBuffer,
    private val vertexCount: Int,
    private val materialBatches: List<MaterialBatch>,
    private val bones: List<Bone>,
    private val inverseBindMatrices: Array<FloatArray>,
    private val animations: Map<String, AnimationClip>
) {
    data class Bone(
        val name: String,
        val parent: Int,
        val bindPos: FloatArray,
        val bindRot: FloatArray,
        val bindScale: FloatArray
    )

    data class Keyframe(
        val time: Float,
        val pos: FloatArray?,
        val rot: FloatArray?,
        val scale: FloatArray?
    )

    data class AnimationClip(
        val name: String,
        val duration: Float,
        val loop: Boolean,
        val tracks: Map<String, List<Keyframe>>
    )

    data class MaterialBatch(
        val materialName: String,
        val indexBuffer: ShortBuffer,
        val indexCount: Int
    )

    // Interleaved vertex layout: pos(3) + normal(3) + uv(2) + joints(4) + weights(4) = 16 floats = 64 bytes
    private val strideBytes = 16 * 4

    companion object {
        private var skinnedProgram = 0
        private var aPosition = 0
        private var aNormal = 0
        private var aTexCoord = 0
        private var aJoints = 0
        private var aWeights = 0
        private var uMVP = 0
        private var uModel = 0
        private var uBones = 0
        private var uDiffuse = 0
        private var uLightDirection = 0
        private var uTint = 0
        private var uFogColor = 0
        private var uCamPos = 0

        private const val VERT_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aTexCoord;
            attribute vec4 aJoints;
            attribute vec4 aWeights;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform mat4 uBones[18];
            varying vec2 vTexCoord;
            varying vec3 vWorldNormal;
            varying vec3 vWorldPos;
            void main() {
                mat4 skin =
                    uBones[int(aJoints.x)] * aWeights.x +
                    uBones[int(aJoints.y)] * aWeights.y +
                    uBones[int(aJoints.z)] * aWeights.z +
                    uBones[int(aJoints.w)] * aWeights.w;
                vec4 skinnedPos = skin * vec4(aPosition, 1.0);
                vec3 skinnedNormal = mat3(skin) * aNormal;
                vec4 worldPos = uModel * skinnedPos;
                vWorldPos = worldPos.xyz;
                vWorldNormal = normalize(mat3(uModel) * skinnedNormal);
                vTexCoord = aTexCoord;
                gl_Position = uMVP * skinnedPos;
            }
        """

        private const val FRAG_SHADER = """
            precision mediump float;
            uniform sampler2D uDiffuse;
            uniform vec3 uLightDirection;
            uniform vec4 uTint;
            uniform vec3 uFogColor;
            uniform vec3 uCamPos;
            varying vec2 vTexCoord;
            varying vec3 vWorldNormal;
            varying vec3 vWorldPos;
            void main() {
                vec3 n = normalize(vWorldNormal);
                float diffuse = max(dot(n, normalize(uLightDirection)), 0.0);
                float lighting = 0.42 + 0.65 * diffuse;
                vec4 tex = texture2D(uDiffuse, vTexCoord);
                if (tex.a < 0.08) discard;
                vec3 lit = tex.rgb * uTint.rgb * lighting;
                float dist = length(uCamPos - vWorldPos);
                float fog = clamp((dist - 18.0) / 38.0, 0.0, 0.50);
                gl_FragColor = vec4(mix(lit, uFogColor, fog), tex.a * uTint.a);
            }
        """

        fun initShader() {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERT_SHADER)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG_SHADER)
            skinnedProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }
            aPosition = GLES20.glGetAttribLocation(skinnedProgram, "aPosition")
            aNormal = GLES20.glGetAttribLocation(skinnedProgram, "aNormal")
            aTexCoord = GLES20.glGetAttribLocation(skinnedProgram, "aTexCoord")
            aJoints = GLES20.glGetAttribLocation(skinnedProgram, "aJoints")
            aWeights = GLES20.glGetAttribLocation(skinnedProgram, "aWeights")
            uMVP = GLES20.glGetUniformLocation(skinnedProgram, "uMVP")
            uModel = GLES20.glGetUniformLocation(skinnedProgram, "uModel")
            uBones = GLES20.glGetUniformLocation(skinnedProgram, "uBones")
            uDiffuse = GLES20.glGetUniformLocation(skinnedProgram, "uDiffuse")
            uLightDirection = GLES20.glGetUniformLocation(skinnedProgram, "uLightDirection")
            uTint = GLES20.glGetUniformLocation(skinnedProgram, "uTint")
            uFogColor = GLES20.glGetUniformLocation(skinnedProgram, "uFogColor")
            uCamPos = GLES20.glGetUniformLocation(skinnedProgram, "uCamPos")
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        fun load(context: Context, assetPath: String): SkinnedZombieModel? {
            return try {
                val jsonStr = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val name = root.optString("name", assetPath)
                val meshObj = root.getJSONObject("mesh")

                val verts = meshObj.getJSONArray("vertices")
                val norms = meshObj.getJSONArray("normals")
                val uvs = meshObj.getJSONArray("uv0")
                val joints = meshObj.getJSONArray("joints_u16")
                val weights = meshObj.getJSONArray("weights")
                val indices = meshObj.getJSONArray("indices_u32")
                val matIds = meshObj.optJSONArray("material_ids")
                val materialsJson = meshObj.optJSONArray("materials")

                val vCount = verts.length()
                val interleaved = FloatArray(vCount * 16)
                for (i in 0 until vCount) {
                    val v = verts.getJSONArray(i)
                    val n = norms.getJSONArray(i)
                    val t = uvs.getJSONArray(i)
                    val j = joints.getJSONArray(i)
                    val w = weights.getJSONArray(i)
                    val base = i * 16
                    interleaved[base + 0] = v.getDouble(0).toFloat()
                    interleaved[base + 1] = v.getDouble(1).toFloat()
                    interleaved[base + 2] = v.getDouble(2).toFloat()
                    interleaved[base + 3] = n.getDouble(0).toFloat()
                    interleaved[base + 4] = n.getDouble(1).toFloat()
                    interleaved[base + 5] = n.getDouble(2).toFloat()
                    interleaved[base + 6] = t.getDouble(0).toFloat()
                    interleaved[base + 7] = 1f - t.getDouble(1).toFloat()
                    interleaved[base + 8] = j.getInt(0).coerceIn(0, 17).toFloat()
                    interleaved[base + 9] = j.getInt(1).coerceIn(0, 17).toFloat()
                    interleaved[base + 10] = j.getInt(2).coerceIn(0, 17).toFloat()
                    interleaved[base + 11] = j.getInt(3).coerceIn(0, 17).toFloat()
                    interleaved[base + 12] = w.getDouble(0).toFloat()
                    interleaved[base + 13] = w.getDouble(1).toFloat()
                    interleaved[base + 14] = w.getDouble(2).toFloat()
                    interleaved[base + 15] = w.getDouble(3).toFloat()
                }

                val vBuf = ByteBuffer.allocateDirect(interleaved.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply { put(interleaved); position(0) }

                val matNames = ArrayList<String>()
                if (materialsJson != null) {
                    for (m in 0 until materialsJson.length()) matNames.add(materialsJson.getString(m))
                }
                if (matNames.isEmpty()) matNames.add("skin")

                val buckets = Array(matNames.size) { ArrayList<Short>() }
                val idxLen = indices.length()
                var t = 0
                while (t + 2 < idxLen) {
                    val i0 = indices.getInt(t)
                    val i1 = indices.getInt(t + 1)
                    val i2 = indices.getInt(t + 2)
                    val mId = if (matIds != null && i0 < matIds.length()) {
                        matIds.getInt(i0).coerceIn(0, matNames.size - 1)
                    } else 0
                    buckets[mId].add(i0.toShort())
                    buckets[mId].add(i1.toShort())
                    buckets[mId].add(i2.toShort())
                    t += 3
                }

                val batches = ArrayList<MaterialBatch>()
                for (m in matNames.indices) {
                    val list = buckets[m]
                    if (list.isEmpty()) continue
                    val sb = ByteBuffer.allocateDirect(list.size * 2)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer()
                    for (s in list) sb.put(s)
                    sb.position(0)
                    batches.add(MaterialBatch(matNames[m], sb, list.size))
                }

                val bonesJson = root.getJSONObject("skeleton").getJSONArray("bones")
                val boneList = ArrayList<Bone>()
                for (b in 0 until bonesJson.length()) {
                    val bo = bonesJson.getJSONObject(b)
                    boneList.add(
                        Bone(
                            name = bo.getString("name"),
                            parent = bo.getInt("parent"),
                            bindPos = readFloat3(bo.getJSONArray("bind_position"), 0f),
                            bindRot = readFloat4(bo.getJSONArray("bind_rotation")),
                            bindScale = readFloat3(bo.getJSONArray("bind_scale"), 1f)
                        )
                    )
                }

                // Compute inverseBindMatrices
                val bindGlobal = Array(boneList.size) { FloatArray(16) }
                val invBind = Array(boneList.size) { FloatArray(16) }
                val localTmp = FloatArray(16)
                for (b in boneList.indices) {
                    val bone = boneList[b]
                    composeTRS(localTmp, bone.bindPos, bone.bindRot, bone.bindScale)
                    if (bone.parent >= 0 && bone.parent < b) {
                        Matrix.multiplyMM(bindGlobal[b], 0, bindGlobal[bone.parent], 0, localTmp, 0)
                    } else {
                        System.arraycopy(localTmp, 0, bindGlobal[b], 0, 16)
                    }
                    Matrix.invertM(invBind[b], 0, bindGlobal[b], 0)
                }

                val animMap = HashMap<String, AnimationClip>()
                val animsJson = root.optJSONArray("animations")
                if (animsJson != null) {
                    for (a in 0 until animsJson.length()) {
                        val ao = animsJson.getJSONObject(a)
                        val aName = ao.getString("name")
                        val dur = ao.optDouble("duration", 1.0).toFloat().coerceAtLeast(0.01f)
                        val loop = ao.optBoolean("loop", true)
                        val tracksObj = ao.optJSONObject("tracks")
                        val tracks = HashMap<String, List<Keyframe>>()
                        if (tracksObj != null) {
                            val keys = tracksObj.keys()
                            while (keys.hasNext()) {
                                val bName = keys.next()
                                val kfArr = tracksObj.getJSONArray(bName)
                                val kfList = ArrayList<Keyframe>()
                                for (k in 0 until kfArr.length()) {
                                    val ko = kfArr.getJSONObject(k)
                                    val time = ko.optDouble("time", 0.0).toFloat()
                                    val pos = ko.optJSONArray("translation")?.let { readFloat3(it, 0f) }
                                    val rot = ko.optJSONArray("rotation")?.let { readFloat4(it) }
                                    val scl = ko.optJSONArray("scale")?.let { readFloat3(it, 1f) }
                                    kfList.add(Keyframe(time, pos, rot, scl))
                                }
                                tracks[bName] = kfList
                            }
                        }
                        animMap[aName] = AnimationClip(aName, dur, loop, tracks)
                    }
                }

                SkinnedZombieModel(name, vBuf, vCount, batches, boneList, invBind, animMap)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun readFloat3(arr: JSONArray, def: Float): FloatArray = floatArrayOf(
            arr.optDouble(0, def.toDouble()).toFloat(),
            arr.optDouble(1, def.toDouble()).toFloat(),
            arr.optDouble(2, def.toDouble()).toFloat()
        )

        private fun readFloat4(arr: JSONArray): FloatArray = floatArrayOf(
            arr.optDouble(0, 0.0).toFloat(),
            arr.optDouble(1, 0.0).toFloat(),
            arr.optDouble(2, 0.0).toFloat(),
            arr.optDouble(3, 1.0).toFloat()
        )

        private fun composeTRS(out: FloatArray, t: FloatArray, q: FloatArray, s: FloatArray) {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z

            out[0] = (1f - 2f * (yy + zz)) * s[0]
            out[1] = (2f * (xy + wz)) * s[0]
            out[2] = (2f * (xz - wy)) * s[0]
            out[3] = 0f

            out[4] = (2f * (xy - wz)) * s[1]
            out[5] = (1f - 2f * (xx + zz)) * s[1]
            out[6] = (2f * (yz + wx)) * s[1]
            out[7] = 0f

            out[8] = (2f * (xz + wy)) * s[2]
            out[9] = (2f * (yz - wx)) * s[2]
            out[10] = (1f - 2f * (xx + yy)) * s[2]
            out[11] = 0f

            out[12] = t[0]
            out[13] = t[1]
            out[14] = t[2]
            out[15] = 1f
        }

        private fun slerp(q1: FloatArray, q2: FloatArray, t: Float, out: FloatArray) {
            var dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
            var b0 = q2[0]; var b1 = q2[1]; var b2 = q2[2]; var b3 = q2[3]
            if (dot < 0f) {
                dot = -dot
                b0 = -b0; b1 = -b1; b2 = -b2; b3 = -b3
            }
            if (dot > 0.9995f) {
                out[0] = q1[0] + t * (b0 - q1[0])
                out[1] = q1[1] + t * (b1 - q1[1])
                out[2] = q1[2] + t * (b2 - q1[2])
                out[3] = q1[3] + t * (b3 - q1[3])
            } else {
                val theta0 = acos(dot.coerceIn(-1f, 1f))
                val sinTheta0 = sin(theta0)
                val s0 = sin((1f - t) * theta0) / sinTheta0
                val s1 = sin(t * theta0) / sinTheta0
                out[0] = s0 * q1[0] + s1 * b0
                out[1] = s0 * q1[1] + s1 * b1
                out[2] = s0 * q1[2] + s1 * b2
                out[3] = s0 * q1[3] + s1 * b3
            }
            val len = hypot(hypot(out[0], out[1]), hypot(out[2], out[3])).coerceAtLeast(0.0001f)
            out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len
        }
    }

    private val bonePalette = FloatArray(18 * 16)
    private val animGlobal = Array(18) { FloatArray(16) }
    private val localMat = FloatArray(16)
    private val skinMat = FloatArray(16)
    private val tmpPos = FloatArray(3)
    private val tmpRot = FloatArray(4)
    private val tmpScale = FloatArray(3)

    fun draw(
        vpMatrix: FloatArray,
        x: Float, y: Float, z: Float,
        yawDeg: Float,
        scale: Float,
        clipName: String,
        animTime: Float,
        texturesByMaterial: Map<String, Int>,
        fallbackTexId: Int,
        tintR: Float, tintG: Float, tintB: Float,
        camX: Float, camY: Float, camZ: Float,
        fogR: Float, fogG: Float, fogB: Float
    ) {
        if (skinnedProgram == 0) return

        val clip = animations[clipName] ?: animations["walk"] ?: animations["idle"] ?: animations.values.firstOrNull()
        val tSample = if (clip != null) {
            if (clip.loop) animTime.mod(clip.duration) else animTime.coerceIn(0f, clip.duration)
        } else 0f

        for (i in bones.indices.take(18)) {
            val bone = bones[i]
            System.arraycopy(bone.bindPos, 0, tmpPos, 0, 3)
            System.arraycopy(bone.bindRot, 0, tmpRot, 0, 4)
            System.arraycopy(bone.bindScale, 0, tmpScale, 0, 3)

            val track = clip?.tracks?.get(bone.name)
            if (!track.isNullOrEmpty()) {
                sampleTrack(track, tSample, tmpPos, tmpRot, tmpScale)
            }

            composeTRS(localMat, tmpPos, tmpRot, tmpScale)
            if (bone.parent >= 0 && bone.parent < i) {
                Matrix.multiplyMM(animGlobal[i], 0, animGlobal[bone.parent], 0, localMat, 0)
            } else {
                System.arraycopy(localMat, 0, animGlobal[i], 0, 16)
            }
            Matrix.multiplyMM(skinMat, 0, animGlobal[i], 0, inverseBindMatrices[i], 0)
            System.arraycopy(skinMat, 0, bonePalette, i * 16, 16)
        }

        // Fill remaining bone slots with identity
        for (i in bones.size until 18) {
            Matrix.setIdentityM(bonePalette, i * 16)
        }

        val modelMat = FloatArray(16)
        val mvpMat = FloatArray(16)
        Matrix.setIdentityM(modelMat, 0)
        Matrix.translateM(modelMat, 0, x, y, z)
        // Rotate 180 because JSON models face -Z while GameEngine yaw=0 faces +Z
        Matrix.rotateM(modelMat, 0, yawDeg + 180f, 0f, 1f, 0f)
        val modelScale = scale * 0.82f
        Matrix.scaleM(modelMat, 0, modelScale, modelScale, modelScale)
        Matrix.multiplyMM(mvpMat, 0, vpMatrix, 0, modelMat, 0)

        GLES20.glUseProgram(skinnedProgram)
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelMat, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvpMat, 0)
        GLES20.glUniformMatrix4fv(uBones, 18, false, bonePalette, 0)
        GLES20.glUniform3f(uLightDirection, 0.35f, 0.90f, 0.28f)
        GLES20.glUniform4f(uTint, tintR, tintG, tintB, 1.0f)
        GLES20.glUniform3f(uFogColor, fogR, fogG, fogB)
        GLES20.glUniform3f(uCamPos, camX, camY, camZ)
        GLES20.glUniform1i(uDiffuse, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(6)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(8)
        GLES20.glEnableVertexAttribArray(aJoints)
        GLES20.glVertexAttribPointer(aJoints, 4, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(12)
        GLES20.glEnableVertexAttribArray(aWeights)
        GLES20.glVertexAttribPointer(aWeights, 4, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (batch in materialBatches) {
            val texId = texturesByMaterial[batch.materialName] ?: fallbackTexId
            if (texId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            }
            batch.indexBuffer.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, batch.indexCount, GLES20.GL_UNSIGNED_SHORT, batch.indexBuffer)
        }

        GLES20.glDisableVertexAttribArray(aJoints)
        GLES20.glDisableVertexAttribArray(aWeights)
    }

    private fun sampleTrack(
        track: List<Keyframe>,
        t: Float,
        outPos: FloatArray,
        outRot: FloatArray,
        outScale: FloatArray
    ) {
        if (track.size == 1) {
            track[0].pos?.let { System.arraycopy(it, 0, outPos, 0, 3) }
            track[0].rot?.let { System.arraycopy(it, 0, outRot, 0, 4) }
            track[0].scale?.let { System.arraycopy(it, 0, outScale, 0, 3) }
            return
        }
        var idx = 0
        while (idx < track.size - 2 && track[idx + 1].time <= t) {
            idx++
        }
        val k0 = track[idx]
        val k1 = track[idx + 1]
        val span = (k1.time - k0.time).coerceAtLeast(0.0001f)
        val alpha = ((t - k0.time) / span).coerceIn(0f, 1f)

        if (k0.pos != null && k1.pos != null) {
            for (c in 0..2) outPos[c] = k0.pos[c] + (k1.pos[c] - k0.pos[c]) * alpha
        }
        if (k0.rot != null && k1.rot != null) {
            slerp(k0.rot, k1.rot, alpha, outRot)
        }
        if (k0.scale != null && k1.scale != null) {
            for (c in 0..2) outScale[c] = k0.scale[c] + (k1.scale[c] - k0.scale[c]) * alpha
        }
    }
}
