package com.wyrix.zombie3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameRenderer(
    private val context: Context,
    private val engine: GameEngine,
    private val onFrameRendered: () -> Unit
) : GLSurfaceView.Renderer {
    private var surfaceWidth = 0

    // ---- main shader ----------------------------------------------------------------------
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUv = 0
    private var uMvp = 0
    private var uModel = 0
    private var uUvScale = 0
    private var uUvOffset = 0
    private var uTex = 0
    private var uTint = 0
    private var uUseTex = 0
    private var uUnlit = 0
    private var uLightDir = 0
    private var uSunColor = 0
    private var uSkyAmbient = 0
    private var uCamPos = 0
    private var uFogColor = 0
    private var uMuzzlePos = 0
    private var uMuzzleIntensity = 0

    // ---- sky shader -----------------------------------------------------------------------
    private var skyProgram = 0
    private var skyAPos = 0
    private var skyUMvp = 0
    private var skyUHorizon = 0
    private var skyUZenith = 0
    private var skyUTime = 0
    private var skyUSunDir = 0

    private var texRoad = 0
    private var texCrate = 0
    private var texGround = 0
    private var texBlood = 0
    private var texShadow = 0
    private var texBuilding = 0
    private var texFlash = 0
    private var texSmoke = 0
    private var glGen = 0

    private var lastCamX = 0f
    private var lastCamY = 1.6f
    private var lastCamZ = 0f

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val skyMatrix = FloatArray(16)

    private var currentFov = 52f
    private var viewRatio = 0.56f
    private var lastNano = System.nanoTime()
    private var time = 0f

    // atmosphere (set per room)
    private var horR = 0.5f; private var horG = 0.5f; private var horB = 0.52f
    private var zenR = 0.2f; private var zenG = 0.26f; private var zenB = 0.36f
    private var sunR = 1f; private var sunG = 0.93f; private var sunB = 0.82f

    // scratch matrices (no allocation in the frame loop)
    private val baseM = FloatArray(16)
    private val m1 = FloatArray(16)
    private val m2 = FloatArray(16)
    private val m3 = FloatArray(16)
    private val m4 = FloatArray(16)
    private val m5 = FloatArray(16)
    private val m6 = FloatArray(16)
    private val m7 = FloatArray(16)
    private val m8 = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val rotM = FloatArray(16)
    private val v4a = FloatArray(4)
    private val v4b = FloatArray(4)
    private val upperArm = Array(2) { FloatArray(16) }
    private val lowerArm = Array(2) { FloatArray(16) }
    private val gunLocal = FloatArray(16)
    private val gunWorld = FloatArray(16)

    private val muzzleWorld = FloatArray(3)
    private var shellPortWorldX = 0f
    private var shellPortWorldY = 0f
    private var shellPortWorldZ = 0f
    private var lastShotSeen = 0
    private var lastFwdX = 0f
    private var lastFwdY = 0f
    private var lastFwdZ = 1f

    // ---- static scenery -------------------------------------------------------------------
    private class Building(
        val x: Float, val z: Float, val w: Float, val h: Float, val d: Float,
        val r: Float, val g: Float, val b: Float
    ) { val radius = max(max(w, d), h) * 0.75f }

    private class Wreck(val x: Float, val z: Float, val yaw: Float, val colorIdx: Int)

    private val farBuildings = ArrayList<Building>()
    private val wrecks = ArrayList<Wreck>()
    private val lampZ = ArrayList<Float>()

    // ---- effects --------------------------------------------------------------------------
    private class Fx(
        val kind: Int, // 0 spark, 1 smoke, 2 shell
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        var life: Float, val maxLife: Float, val size: Float, var rot: Float
    )
    private val fx = ArrayList<Fx>()

    private val vertexShaderCode =
        "uniform mat4 uMvp;\n" +
        "uniform mat4 uModel;\n" +
        "uniform vec2 uUvScale;\n" +
        "uniform vec2 uUvOffset;\n" +
        "attribute vec3 aPos;\n" +
        "attribute vec3 aNormal;\n" +
        "attribute vec2 aUv;\n" +
        "varying vec3 vNormal;\n" +
        "varying vec3 vWorldPos;\n" +
        "varying vec2 vUv;\n" +
        "void main() {\n" +
        "    vec4 wp = uModel * vec4(aPos, 1.0);\n" +
        "    vWorldPos = wp.xyz;\n" +
        "    vNormal = mat3(uModel) * aNormal;\n" +
        "    vUv = aUv * uUvScale + uUvOffset;\n" +
        "    gl_Position = uMvp * vec4(aPos, 1.0);\n" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform vec4 uTint;\n" +
        "uniform float uUseTex;\n" +
        "uniform float uUnlit;\n" +
        "uniform vec3 uLightDir;\n" +
        "uniform vec3 uSunColor;\n" +
        "uniform vec3 uSkyAmbient;\n" +
        "uniform vec3 uCamPos;\n" +
        "uniform vec3 uFogColor;\n" +
        "uniform vec3 uMuzzlePos;\n" +
        "uniform float uMuzzleIntensity;\n" +
        "varying vec3 vNormal;\n" +
        "varying vec3 vWorldPos;\n" +
        "varying vec2 vUv;\n" +
        "void main() {\n" +
        "    vec4 texCol = uUseTex > 0.5 ? texture2D(uTex, vUv) : vec4(1.0);\n" +
        "    if (texCol.a < 0.04) discard;\n" +
        "    vec3 baseCol = texCol.rgb * uTint.rgb;\n" +
        "    if (uUnlit > 0.5) {\n" +
        "        gl_FragColor = vec4(baseCol, texCol.a * uTint.a);\n" +
        "        return;\n" +
        "    }\n" +
        "    vec3 n = normalize(vNormal);\n" +
        "    vec3 l = normalize(uLightDir);\n" +
        "    vec3 v = normalize(uCamPos - vWorldPos);\n" +
        "    vec3 h = normalize(l + v);\n" +
        "    float diff = max(dot(n, l), 0.0);\n" +
        "    float hemi = n.y * 0.5 + 0.5;\n" +
        "    vec3 amb = mix(vec3(0.16, 0.15, 0.14), uSkyAmbient, hemi);\n" +
        "    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.10;\n" +
        "    float spec = pow(max(dot(n, h), 0.0), 20.0) * 0.07;\n" +
        "    float dMuzzle = length(uMuzzlePos - vWorldPos);\n" +
        "    float flash = uMuzzleIntensity / (1.0 + dMuzzle * dMuzzle * 0.45);\n" +
        "    vec3 lit = baseCol * (amb + uSunColor * diff * 0.78 + rim) + uSunColor * spec + vec3(1.0, 0.72, 0.30) * flash * (0.4 + 0.6 * diff);\n" +
        "    float dist = length(uCamPos - vWorldPos);\n" +
        "    float fog = smoothstep(18.0, 100.0, dist);\n" +
        "    gl_FragColor = vec4(mix(lit, uFogColor, fog), texCol.a * uTint.a);\n" +
        "}"

    private val skyVertexCode =
        "uniform mat4 uMvp;\n" +
        "attribute vec3 aPos;\n" +
        "varying vec3 vDir;\n" +
        "void main() {\n" +
        "    vDir = aPos;\n" +
        "    gl_Position = uMvp * vec4(aPos, 1.0);\n" +
        "}"

    private val skyFragmentCode =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "#endif\n" +
        "uniform vec3 uHorizon;\n" +
        "uniform vec3 uZenith;\n" +
        "uniform float uTime;\n" +
        "uniform vec3 uSunDir;\n" +
        "varying vec3 vDir;\n" +
        "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n" +
        "float noise(vec2 p) {\n" +
        "    vec2 i = floor(p);\n" +
        "    vec2 f = fract(p);\n" +
        "    f = f * f * (3.0 - 2.0 * f);\n" +
        "    float a = hash(i);\n" +
        "    float b = hash(i + vec2(1.0, 0.0));\n" +
        "    float c = hash(i + vec2(0.0, 1.0));\n" +
        "    float d = hash(i + vec2(1.0, 1.0));\n" +
        "    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
        "}\n" +
        "void main() {\n" +
        "    vec3 d = normalize(vDir);\n" +
        "    float h = clamp(d.y, 0.0, 1.0);\n" +
        "    vec3 col = mix(uHorizon, uZenith, pow(h, 0.55));\n" +
        "    if (d.y > 0.01) {\n" +
        "        vec2 uv = d.xz / (d.y + 0.22) * 1.7 + vec2(uTime * 0.012, uTime * 0.004);\n" +
        "        float c = noise(uv) * 0.55 + noise(uv * 2.1) * 0.30 + noise(uv * 4.3) * 0.15;\n" +
        "        c = smoothstep(0.40, 0.85, c);\n" +
        "        float fade = smoothstep(0.01, 0.28, d.y);\n" +
        "        col = mix(col, uHorizon * 0.72 + vec3(0.04), c * 0.65 * fade);\n" +
        "    }\n" +
        "    float sun = max(dot(d, normalize(uSunDir)), 0.0);\n" +
        "    col += vec3(1.0, 0.72, 0.45) * pow(sun, 18.0) * 0.30 + vec3(1.0, 0.85, 0.6) * pow(sun, 280.0) * 0.45;\n" +
        "    if (d.y < 0.0) col = uHorizon;\n" +
        "    gl_FragColor = vec4(col, 1.0);\n" +
        "}"

    init {
        buildScenery()
    }

    // ============================================================================================
    // Setup
    // ============================================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.5f, 0.5f, 0.52f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = linkProgram(vertexShaderCode, fragmentShaderCode)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uUvScale = GLES20.glGetUniformLocation(program, "uUvScale")
        uUvOffset = GLES20.glGetUniformLocation(program, "uUvOffset")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
        uUnlit = GLES20.glGetUniformLocation(program, "uUnlit")
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uSunColor = GLES20.glGetUniformLocation(program, "uSunColor")
        uSkyAmbient = GLES20.glGetUniformLocation(program, "uSkyAmbient")
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uMuzzlePos = GLES20.glGetUniformLocation(program, "uMuzzlePos")
        uMuzzleIntensity = GLES20.glGetUniformLocation(program, "uMuzzleIntensity")

        skyProgram = linkProgram(skyVertexCode, skyFragmentCode)
        skyAPos = GLES20.glGetAttribLocation(skyProgram, "aPos")
        skyUMvp = GLES20.glGetUniformLocation(skyProgram, "uMvp")
        skyUHorizon = GLES20.glGetUniformLocation(skyProgram, "uHorizon")
        skyUZenith = GLES20.glGetUniformLocation(skyProgram, "uZenith")
        skyUTime = GLES20.glGetUniformLocation(skyProgram, "uTime")
        skyUSunDir = GLES20.glGetUniformLocation(skyProgram, "uSunDir")

        // Large textures are loaded at half size (fast start-up); models load in the background.
        texRoad = TextureGenerator.loadAssetTexture(context, "textures/highway_2k.png", true, 2)
        texGround = TextureGenerator.loadAssetTexture(context, "textures/concrete_2k.png", true, 2)
        texCrate = TextureGenerator.loadAssetTexture(context, "textures/crate_1k.png", true, 1)
        texBlood = TextureGenerator.loadAssetTexture(context, "textures/blood_splatter.png", false, 1)
        texShadow = TextureGenerator.createShadowTexture()
        texBuilding = TextureGenerator.createBuildingTexture()
        texFlash = TextureGenerator.createFlashTexture()
        texSmoke = TextureGenerator.createSmokeTexture()
        glGen++ // new GL context: every VBO / model texture must be re-created
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        GLES20.glViewport(0, 0, width, height)
        viewRatio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projMatrix, 0, currentFov, viewRatio, 0.15f, 130f)
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compileShader(type: Int, code: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, code)
        GLES20.glCompileShader(id)
        return id
    }

    /** Deterministic far scenery: street frontage, second row, skyline, wrecks, lamp posts. */
    private fun buildScenery() {
        val rng = java.util.Random(2026L)
        fun tone(): Triple<Float, Float, Float> {
            val k = rng.nextInt(5)
            return when (k) {
                0 -> Triple(0.62f, 0.58f, 0.52f)
                1 -> Triple(0.55f, 0.58f, 0.56f)
                2 -> Triple(0.66f, 0.54f, 0.48f)
                3 -> Triple(0.50f, 0.54f, 0.60f)
                else -> Triple(0.58f, 0.56f, 0.50f)
            }
        }
        // Row A: frontage continuing the street past both ends of the playable arena
        for (side in intArrayOf(-1, 1)) for (dir in intArrayOf(-1, 1)) {
            var z = 20.5f
            while (z < 140f) {
                val w = 6f + rng.nextFloat() * 4f
                val d = 7f + rng.nextFloat() * 5f
                val h = 6f + rng.nextFloat() * 11f
                val t = tone()
                farBuildings.add(Building(side * (10.9f + d * 0.5f), dir * (z + w * 0.5f), d, h, w, t.first, t.second, t.third))
                z += w + 0.6f + rng.nextFloat() * 2.5f
            }
        }
        // Row B: second row, visible through gaps and behind the player when turning
        for (side in intArrayOf(-1, 1)) {
            var z = -140f
            while (z < 140f) {
                val w = 10f + rng.nextFloat() * 8f
                val d = 10f + rng.nextFloat() * 6f
                val h = 10f + rng.nextFloat() * 20f
                val t = tone()
                farBuildings.add(Building(side * (30f + rng.nextFloat() * 8f), z + w * 0.5f, d, h, w, t.first * 0.92f, t.second * 0.92f, t.third * 0.95f))
                z += w + 2f + rng.nextFloat() * 6f
            }
        }
        // Row C: distant skyline towers (mostly swallowed by fog = depth)
        for (side in intArrayOf(-1, 1)) {
            var z = -140f
            while (z < 140f) {
                val t = tone()
                farBuildings.add(Building(side * (56f + rng.nextFloat() * 30f), z, 14f + rng.nextFloat() * 8f, 26f + rng.nextFloat() * 36f, 14f + rng.nextFloat() * 8f, t.first * 0.85f, t.second * 0.85f, t.third * 0.9f))
                z += 18f + rng.nextFloat() * 8f
            }
        }
        // Street closers at both ends so the road never ends in a void
        farBuildings.add(Building(0f, 148f, 70f, 22f, 12f, 0.5f, 0.5f, 0.52f))
        farBuildings.add(Building(0f, -148f, 70f, 22f, 12f, 0.5f, 0.5f, 0.52f))

        var z = 25f
        while (z < 135f) {
            for (dir in intArrayOf(-1, 1)) {
                if (rng.nextFloat() < 0.55f) {
                    wrecks.add(Wreck((rng.nextFloat() * 2f - 1f) * 3.6f, dir * z, rng.nextFloat() * 70f - 35f, rng.nextInt(4)))
                }
            }
            z += 9f + rng.nextFloat() * 6f
        }
        var lz = -126f
        while (lz <= 126f) { lampZ.add(lz); lz += 18f }
    }

    // ============================================================================================
    // Frame
    // ============================================================================================

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNano) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNano = now
        time += dt

        if (ModelBank.ready) ModelBank.uploadTextures(glGen)

        engine.update(dt)

        val targetFov = if (engine.isScoped) 26f else 54f
        if (abs(targetFov - currentFov) > 0.2f) {
            currentFov += (targetFov - currentFov) * 12f * dt
            Matrix.perspectiveM(projMatrix, 0, currentFov, viewRatio, 0.15f, 130f)
        }

        applyAtmosphere(engine.activeRoomNumber)

        GLES20.glClearColor(horR, horG, horB, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        synchronized(engine) {
            val yawRad = Math.toRadians(engine.playerYaw.toDouble())
            val pitchRad = Math.toRadians(engine.playerPitch.toDouble())
            val fwdX = (sin(yawRad) * cos(pitchRad)).toFloat()
            val fwdY = sin(pitchRad).toFloat()
            val fwdZ = (cos(yawRad) * cos(pitchRad)).toFloat()
            // true screen-right of a camera looking along forward
            val rightX = -cos(yawRad).toFloat()
            val rightZ = sin(yawRad).toFloat()
            lastFwdX = fwdX; lastFwdY = fwdY; lastFwdZ = fwdZ

            val walkBobY = if (engine.playerIsMoving) abs(sin(engine.walkCycle)) * 0.035f else 0f
            val walkBobX = if (engine.playerIsMoving) cos(engine.walkCycle * 0.5f) * 0.02f else 0f
            val shakeX = if (engine.shakeTimer > 0f) (Random.nextFloat() - 0.5f) * 0.16f else 0f
            val shakeY = if (engine.shakeTimer > 0f) (Random.nextFloat() - 0.5f) * 0.16f else 0f

            val camX: Float
            val camY: Float
            val camZ: Float
            if (engine.isTppMode) {
                val backDist = if (engine.isScoped) 1.5f else 3.0f
                val sideOffset = if (engine.isScoped) 0.36f else 0.70f
                camX = engine.playerX - sin(yawRad).toFloat() * backDist + rightX * sideOffset + shakeX
                camY = (if (engine.isScoped) 1.72f else 2.10f) + walkBobY + shakeY
                camZ = engine.playerZ - cos(yawRad).toFloat() * backDist + rightZ * sideOffset
                // aim point lies on the bullet line (gun is 0.13 m to the right of the body centre)
                val tx = engine.playerX + rightX * 0.13f + fwdX * 14f
                // Aim a little lower in TPP so the player's feet stay inside the viewport.
                val ty = 1.05f + fwdY * 14f
                val tz = engine.playerZ + rightZ * 0.13f + fwdZ * 14f
                Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, tx, ty, tz, 0f, 1f, 0f)
            } else {
                camX = engine.playerX + walkBobX + shakeX
                camY = 1.58f + walkBobY + shakeY
                camZ = engine.playerZ
                Matrix.setLookAtM(
                    viewMatrix, 0, camX, camY, camZ,
                    camX + fwdX * 12f, camY + fwdY * 12f, camZ + fwdZ * 12f, 0f, 1f, 0f
                )
            }
            lastCamX = camX; lastCamY = camY; lastCamZ = camZ
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            drawSky(camX, camY, camZ)

            GLES20.glUseProgram(program)
            GLES20.glUniform3f(uLightDir, 0.30f, 0.78f, 0.50f)
            GLES20.glUniform3f(uSunColor, sunR, sunG, sunB)
            GLES20.glUniform3f(uSkyAmbient, zenR * 0.9f + 0.22f, zenG * 0.9f + 0.22f, zenB * 0.9f + 0.24f)
            GLES20.glUniform3f(uFogColor, horR, horG, horB)
            GLES20.glUniform3f(uCamPos, camX, camY, camZ)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform2f(uUvOffset, 0f, 0f)
            GLES20.glUniform1f(uUnlit, 0f)
            GLES20.glUniform3f(uMuzzlePos, 0f, -50f, 0f)
            GLES20.glUniform1f(uMuzzleIntensity, 0f)

            drawWorld()

            drawPlayer(engine.isTppMode)

            for (c in engine.corpses) drawCorpse(c)
            for (z in engine.zombies) drawZombie(z)

            handleShotEffects()
            drawEffects(dt)
        }

        onFrameRendered()
    }

    private fun applyAtmosphere(room: Int) {
        when (room.coerceAtLeast(1) % 6) {
            0 -> setAtm(0.52f, 0.52f, 0.54f, 0.20f, 0.26f, 0.36f, 1.00f, 0.93f, 0.82f)
            1 -> setAtm(0.45f, 0.52f, 0.42f, 0.18f, 0.26f, 0.24f, 0.92f, 0.98f, 0.80f)
            2 -> setAtm(0.72f, 0.50f, 0.36f, 0.22f, 0.24f, 0.34f, 1.10f, 0.82f, 0.56f)
            3 -> setAtm(0.14f, 0.17f, 0.25f, 0.03f, 0.05f, 0.12f, 0.46f, 0.56f, 0.82f)
            4 -> setAtm(0.64f, 0.66f, 0.68f, 0.42f, 0.48f, 0.56f, 0.92f, 0.94f, 0.96f)
            else -> setAtm(0.56f, 0.30f, 0.26f, 0.18f, 0.10f, 0.12f, 1.05f, 0.62f, 0.50f)
        }
    }

    private fun setAtm(hr: Float, hg: Float, hb: Float, zr: Float, zg: Float, zb: Float, sr: Float, sg: Float, sb: Float) {
        horR = hr; horG = hg; horB = hb
        zenR = zr; zenG = zg; zenB = zb
        sunR = sr; sunG = sg; sunB = sb
    }

    private fun drawSky(cx: Float, cy: Float, cz: Float) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(skyProgram)
        Matrix.setIdentityM(skyMatrix, 0)
        Matrix.translateM(skyMatrix, 0, cx, cy, cz)
        Matrix.scaleM(skyMatrix, 0, 170f, 170f, 170f)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, skyMatrix, 0)
        GLES20.glUniformMatrix4fv(skyUMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(skyUHorizon, horR, horG, horB)
        GLES20.glUniform3f(skyUZenith, zenR, zenG, zenB)
        GLES20.glUniform1f(skyUTime, time)
        GLES20.glUniform3f(skyUSunDir, 0.5f, 0.20f, -0.8f)
        val mesh = MeshLibrary.sphere
        bindVbo(mesh)
        GLES20.glEnableVertexAttribArray(skyAPos)
        GLES20.glVertexAttribPointer(skyAPos, 3, GLES20.GL_FLOAT, false, mesh.strideBytes, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(skyAPos)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ============================================================================================
    // World
    // ============================================================================================

    private fun inView(x: Float, y: Float, z: Float, radius: Float): Boolean {
        val vz = viewMatrix[2] * x + viewMatrix[6] * y + viewMatrix[10] * z + viewMatrix[14]
        if (vz > radius) return false // behind the camera
        val dx = x - lastCamX
        val dz = z - lastCamZ
        val lim = 112f + radius
        return dx * dx + dz * dz < lim * lim
    }

    private fun drawBuildingBox(x: Float, z: Float, w: Float, h: Float, d: Float, r: Float, g: Float, b: Float) {
        drawMesh(MeshLibrary.box, x, h * 0.5f, z, w, h, d, 0f, texBuilding, max(1f, max(w, d) / 3.6f), max(1f, h / 3.4f), r, g, b, 1f)
        // dark roof cap with parapet
        drawMesh(MeshLibrary.box, x, h + 0.15f, z, w + 0.3f, 0.3f, d + 0.3f, 0f, 0, 1f, 1f, 0.17f, 0.17f, 0.16f, 1f)
    }

    private fun drawWreck(x: Float, z: Float, yaw: Float, colorIdx: Int, w: Float = 2.0f, d: Float = 4.4f) {
        val car = when (colorIdx % 4) {
            0 -> Triple(0.34f, 0.28f, 0.22f)
            1 -> Triple(0.28f, 0.33f, 0.31f)
            2 -> Triple(0.40f, 0.36f, 0.30f)
            else -> Triple(0.27f, 0.28f, 0.32f)
        }
        // body, cabin, windows, tyres (all rotated by yaw around the car centre)
        Matrix.setIdentityM(baseM, 0)
        Matrix.translateM(baseM, 0, x, 0f, z)
        Matrix.rotateM(baseM, 0, yaw, 0f, 1f, 0f)
        drawLocalBox(baseM, 0f, 0.58f, 0f, w, 0.72f, d, texCrate, car.first, car.second, car.third)
        drawLocalBox(baseM, 0f, 1.06f, -0.15f, w * 0.80f, 0.50f, d * 0.48f, 0, 0.10f, 0.13f, 0.14f)
        for (wx in floatArrayOf(-1f, 1f)) for (wz in floatArrayOf(-1f, 1f)) {
            System.arraycopy(baseM, 0, modelMatrix, 0, 16)
            Matrix.translateM(modelMatrix, 0, wx * w * 0.50f, 0.34f, wz * d * 0.32f)
            Matrix.rotateM(modelMatrix, 0, 90f, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, 0.66f, 0.26f, 0.66f)
            submitMesh(MeshLibrary.cylinder, 0, 1f, 1f, 0.07f, 0.07f, 0.065f, 1f)
        }
    }

    private fun drawLocalBox(
        parent: FloatArray, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        tex: Int, r: Float, g: Float, b: Float
    ) {
        System.arraycopy(parent, 0, modelMatrix, 0, 16)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        submitMesh(MeshLibrary.box, tex, 1f, 1f, r, g, b, 1f)
    }

    private fun drawWorld() {
        // Endless ground so the horizon is never an empty void.
        drawMesh(MeshLibrary.box, 0f, -0.52f, 0f, 420f, 1f, 420f, 0f, texGround, 84f, 84f, 0.24f, 0.25f, 0.22f, 1f)
        // Road, centre lines and lane dashes running far down the street
        drawMesh(MeshLibrary.box, 0f, -0.005f, 0f, 10.5f, 0.035f, 300f, 0f, texRoad, 1.8f, 55f, 0.52f, 0.53f, 0.49f, 1f)
        drawMesh(MeshLibrary.box, -0.10f, 0.018f, 0f, 0.075f, 0.012f, 300f, 0f, 0, 1f, 1f, 0.72f, 0.57f, 0.27f, 0.72f)
        drawMesh(MeshLibrary.box, 0.10f, 0.018f, 0f, 0.075f, 0.012f, 300f, 0f, 0, 1f, 1f, 0.72f, 0.57f, 0.27f, 0.72f)
        var dz = -140
        while (dz <= 140) {
            drawMesh(MeshLibrary.box, -5.05f, 0.018f, dz.toFloat(), 0.10f, 0.012f, 2.8f, 0f, 0, 1f, 1f, 0.66f, 0.67f, 0.62f, 0.55f)
            drawMesh(MeshLibrary.box, 5.05f, 0.018f, dz.toFloat(), 0.10f, 0.012f, 2.8f, 0f, 0, 1f, 1f, 0.66f, 0.67f, 0.62f, 0.55f)
            dz += 7
        }
        for (side in floatArrayOf(-1f, 1f)) {
            drawMesh(MeshLibrary.box, side * 7.0f, 0.02f, 0f, 2.4f, 0.12f, 300f, 0f, texGround, 2f, 60f, 0.46f, 0.45f, 0.41f, 1f)
            drawMesh(MeshLibrary.box, side * 12f, -0.01f, 0f, 7f, 0.06f, 300f, 0f, texRoad, 3f, 40f, 0.20f, 0.27f, 0.17f, 1f)
        }

        for (bd in engine.bloodDecals) {
            drawMesh(MeshLibrary.box, bd.x, 0.025f, bd.z, bd.radius * 1.4f, 0.004f, bd.radius * 1.4f, bd.rotDeg, texBlood, 1f, 1f, 1f, 1f, 1f, 0.92f)
        }

        // Far buildings (frustum culled)
        for (b in farBuildings) {
            if (!inView(b.x, b.h * 0.5f, b.z, b.radius)) continue
            drawBuildingBox(b.x, b.z, b.w, b.h, b.d, b.r, b.g, b.b)
        }
        // wrecks and lamp posts down the street
        for (w in wrecks) {
            if (!inView(w.x, 1f, w.z, 4f)) continue
            drawWreck(w.x, w.z, w.yaw, w.colorIdx)
        }
        for (z in lampZ) {
            for (side in floatArrayOf(-1f, 1f)) {
                val x = side * 8.9f
                if (!inView(x, 3f, z, 4f)) continue
                drawMesh(MeshLibrary.cylinder, x, 3.1f, z, 0.14f, 6.2f, 0.14f, 0f, 0, 1f, 1f, 0.16f, 0.16f, 0.15f, 1f)
                drawMesh(MeshLibrary.box, x - side * 0.9f, 6.15f, z, 1.9f, 0.10f, 0.10f, 0f, 0, 1f, 1f, 0.16f, 0.16f, 0.15f, 1f)
            }
        }

        // Barricades closing both ends of the combat street
        for (end in floatArrayOf(-1f, 1f)) {
            val bz = end * 19.4f
            var x = -5.2f
            while (x <= 5.3f) {
                drawMesh(MeshLibrary.box, x, 0.45f, bz, 1.8f, 0.9f, 0.7f, 0f, texGround, 1f, 1f, 0.55f, 0.55f, 0.52f, 1f)
                x += 1.9f
            }
            drawMesh(MeshLibrary.box, -3.4f * end, 1.3f, bz + end * 2.8f, 2.6f, 2.6f, 9f, 0f, texCrate, 1f, 1f, 0.50f, 0.42f, 0.16f, 1f)
            drawMesh(MeshLibrary.box, -3.4f * end, 1.75f, bz + end * 2.8f, 2.65f, 0.7f, 8.6f, 0f, 0, 1f, 1f, 0.07f, 0.09f, 0.10f, 1f)
            drawWreck(3.0f * end, bz + end * 3.0f, 28f * end, 2, 2.0f, 4.4f)
        }

        // Near buildings / cars / trees generated by the engine for each stage
        for (c in engine.crates) {
            when (c.kind) {
                9 -> {
                    val tone = when (c.colorIdx % 4) {
                        0 -> Triple(0.70f, 0.64f, 0.56f)
                        1 -> Triple(0.60f, 0.64f, 0.60f)
                        2 -> Triple(0.72f, 0.60f, 0.54f)
                        else -> Triple(0.56f, 0.62f, 0.66f)
                    }
                    drawBuildingBox(c.x, c.z, c.w, c.h, c.d, tone.first, tone.second, tone.third)
                    // doorway on the road-facing wall
                    val face = if (c.x > 0f) -1f else 1f
                    drawMesh(MeshLibrary.box, c.x + face * (c.w * 0.5f + 0.02f), 1.05f, c.z, 0.06f, 2.0f, 1.1f, 0f, 0, 1f, 1f, 0.10f, 0.09f, 0.08f, 1f)
                }
                10 -> drawWreck(c.x, c.z, 90f * (if (c.colorIdx % 2 == 0) 1f else -1f) + c.colorIdx * 6f, c.colorIdx, c.w, c.d)
                11 -> {
                    drawMesh(MeshLibrary.cylinder, c.x, c.h * 0.42f, c.z, 0.22f, c.h * 0.84f, 0.22f, 0f, 0, 1f, 1f, 0.20f, 0.16f, 0.12f, 1f)
                    drawMesh(MeshLibrary.sphere, c.x, c.h * 0.82f, c.z, 1.15f, 1.55f, 1.15f, 0f, 0, 1f, 1f, 0.15f, 0.21f, 0.15f, 0.95f)
                    drawMesh(MeshLibrary.sphere, c.x - 0.62f, c.h * 0.72f, c.z + 0.25f, 0.78f, 1.1f, 0.78f, 0f, 0, 1f, 1f, 0.17f, 0.23f, 0.16f, 0.92f)
                }
                else -> drawMesh(MeshLibrary.box, c.x, c.h * 0.5f, c.z, c.w, c.h, c.d, 0f, texCrate, 1f, 1f, 0.48f, 0.43f, 0.35f, 1f)
            }
        }
    }

    // ============================================================================================
    // Skeleton helpers
    // ============================================================================================

    private fun joint(parent: FloatArray, p: ModelPart, rx: Float, ry: Float, rz: Float, out: FloatArray) {
        System.arraycopy(parent, 0, out, 0, 16)
        Matrix.translateM(out, 0, p.px, p.py, p.pz)
        if (rz != 0f) Matrix.rotateM(out, 0, rz, 0f, 0f, 1f)
        if (ry != 0f) Matrix.rotateM(out, 0, ry, 0f, 1f, 0f)
        if (rx != 0f) Matrix.rotateM(out, 0, rx, 1f, 0f, 0f)
        Matrix.translateM(out, 0, -p.px, -p.py, -p.pz)
    }

    private fun drawPartWith(model: PartModel, part: ModelPart?, mat: FloatArray, r: Float, g: Float, b: Float) {
        if (part == null) return
        System.arraycopy(mat, 0, modelMatrix, 0, 16)
        submitMesh(part.mesh, model.texId, 1f, 1f, r, g, b, 1f)
    }

    private fun rotFromTo(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
        val la = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
        val lb = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-6f)
        val nax = ax / la; val nay = ay / la; val naz = az / la
        val nbx = bx / lb; val nby = by / lb; val nbz = bz / lb
        val cx = nay * nbz - naz * nby
        val cy = naz * nbx - nax * nbz
        val cz = nax * nby - nay * nbx
        val s = sqrt(cx * cx + cy * cy + cz * cz)
        val c = (nax * nbx + nay * nby + naz * nbz).coerceIn(-1f, 1f)
        if (s < 1e-5f) {
            Matrix.setIdentityM(out, 0)
            return
        }
        val angle = Math.toDegrees(atan2(s, c).toDouble()).toFloat()
        Matrix.setRotateM(out, 0, angle, cx / s, cy / s, cz / s)
    }

    /**
     * Two-bone arm IK. Everything is in the player's local space. Produces the matrix for the
     * upper arm and the matrix shared by forearm + hand so the hand reaches (tx,ty,tz).
     */
    private fun solveArm(
        base: FloatArray, upper: ModelPart, lower: ModelPart, hand: ModelPart,
        tx: Float, ty: Float, tz: Float, poleX: Float, poleY: Float, poleZ: Float,
        outUpper: FloatArray, outLower: FloatArray
    ) {
        val sx = upper.px; val sy = upper.py; val sz = upper.pz
        val ex = lower.px; val ey = lower.py; val ez = lower.pz
        val wx = hand.px; val wy = hand.py; val wz = hand.pz
        val l1 = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz))
        val l2 = sqrt((wx - ex) * (wx - ex) + (wy - ey) * (wy - ey) + (wz - ez) * (wz - ez))
        var dx = tx - sx; var dy = ty - sy; var dz = tz - sz
        val dRaw = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        dx /= dRaw; dy /= dRaw; dz /= dRaw
        val d = dRaw.coerceIn(abs(l1 - l2) + 0.02f, l1 + l2 - 0.003f)
        val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
        val h = sqrt(max(0f, l1 * l1 - a * a))
        // pole vector made perpendicular to the shoulder->target direction
        val pd = poleX * dx + poleY * dy + poleZ * dz
        var qx = poleX - dx * pd; var qy = poleY - dy * pd; var qz = poleZ - dz * pd
        val ql = sqrt(qx * qx + qy * qy + qz * qz)
        if (ql < 1e-4f) { qx = 0f; qy = -1f; qz = 0f } else { qx /= ql; qy /= ql; qz /= ql }
        val elx = sx + dx * a + qx * h
        val ely = sy + dy * a + qy * h
        val elz = sz + dz * a + qz * h
        val wrx = sx + dx * d
        val wry = sy + dy * d
        val wrz = sz + dz * d

        rotFromTo(ex - sx, ey - sy, ez - sz, elx - sx, ely - sy, elz - sz, rotM)
        System.arraycopy(base, 0, outUpper, 0, 16)
        Matrix.translateM(outUpper, 0, sx, sy, sz)
        Matrix.multiplyMM(tmpM, 0, outUpper, 0, rotM, 0)
        System.arraycopy(tmpM, 0, outUpper, 0, 16)
        Matrix.translateM(outUpper, 0, -sx, -sy, -sz)

        rotFromTo(wx - ex, wy - ey, wz - ez, wrx - elx, wry - ely, wrz - elz, rotM)
        System.arraycopy(base, 0, outLower, 0, 16)
        Matrix.translateM(outLower, 0, elx, ely, elz)
        Matrix.multiplyMM(tmpM, 0, outLower, 0, rotM, 0)
        System.arraycopy(tmpM, 0, outLower, 0, 16)
        Matrix.translateM(outLower, 0, -ex, -ey, -ez)
    }

    // ---- humanoid pose ----------------------------------------------------------------------
    private class Pose {
        var hipL = 0f; var hipR = 0f
        var kneeL = 0f; var kneeR = 0f
        var shL = 0f; var shR = 0f
        var shSideL = 0f; var shSideR = 0f
        var elL = 0f; var elR = 0f
        var lean = 0f
        var headX = 0f; var headY = 0f
        var useIk = false
        var drawBody = true
        fun reset() {
            hipL = 0f; hipR = 0f; kneeL = 0f; kneeR = 0f; shL = 0f; shR = 0f
            shSideL = 0f; shSideR = 0f; elL = 0f; elR = 0f; lean = 0f; headX = 0f; headY = 0f
            useIk = false; drawBody = true
        }
    }
    private val pose = Pose()

    private fun drawHumanoid(model: PartModel, base: FloatArray, p: Pose, r: Float, g: Float, b: Float, humanSkin: Boolean = false) {
        // Hero color tints the uniform; keep exposed skin on a neutral, warm grade.
        val skinR = if (humanSkin) 1.04f else r
        val skinG = if (humanSkin) 1.01f else g
        val skinB = if (humanSkin) 0.96f else b
        val pelvis = model.part("pelvis")
        val torso = model.part("torso")
        val head = model.part("head")
        val auL = model.part("arm_upper_L"); val alL = model.part("arm_lower_L"); val hL = model.part("hand_L")
        val auR = model.part("arm_upper_R"); val alR = model.part("arm_lower_R"); val hR = model.part("hand_R")

        // upper-body root leans about the pelvis
        if (pelvis != null) joint(base, pelvis, p.lean, 0f, 0f, m1) else System.arraycopy(base, 0, m1, 0, 16)

        if (p.drawBody) {
            drawPartWith(model, pelvis, base, r, g, b)
            drawPartWith(model, torso, m1, r, g, b)
            if (head != null) {
                joint(m1, head, p.headX, p.headY, 0f, m2)
                drawPartWith(model, head, m2, skinR, skinG, skinB)
            }
            // legs
            val luL = model.part("leg_upper_L"); val llL = model.part("leg_lower_L"); val fL = model.part("foot_L")
            val luR = model.part("leg_upper_R"); val llR = model.part("leg_lower_R"); val fR = model.part("foot_R")
            if (luL != null && llL != null && fL != null) {
                joint(base, luL, p.hipL, 0f, 0f, m3)
                joint(m3, llL, p.kneeL, 0f, 0f, m4)
                joint(m4, fL, -(p.hipL + p.kneeL) * 0.7f, 0f, 0f, m5)
                drawPartWith(model, luL, m3, r, g, b); drawPartWith(model, llL, m4, r, g, b); drawPartWith(model, fL, m5, r, g, b)
            }
            if (luR != null && llR != null && fR != null) {
                joint(base, luR, p.hipR, 0f, 0f, m3)
                joint(m3, llR, p.kneeR, 0f, 0f, m4)
                joint(m4, fR, -(p.hipR + p.kneeR) * 0.7f, 0f, 0f, m5)
                drawPartWith(model, luR, m3, r, g, b); drawPartWith(model, llR, m4, r, g, b); drawPartWith(model, fR, m5, r, g, b)
            }
        }

        // arms
        if (p.useIk) {
            drawPartWith(model, auL, upperArm[0], r, g, b); drawPartWith(model, alL, lowerArm[0], r, g, b); drawPartWith(model, hL, lowerArm[0], skinR, skinG, skinB)
            drawPartWith(model, auR, upperArm[1], r, g, b); drawPartWith(model, alR, lowerArm[1], r, g, b); drawPartWith(model, hR, lowerArm[1], skinR, skinG, skinB)
        } else {
            if (auL != null && alL != null && hL != null) {
                joint(m1, auL, p.shL, 0f, p.shSideL, m6)
                joint(m6, alL, p.elL, 0f, 0f, m7)
                joint(m7, hL, 0f, 0f, 0f, m8)
                drawPartWith(model, auL, m6, r, g, b); drawPartWith(model, alL, m7, r, g, b); drawPartWith(model, hL, m8, skinR, skinG, skinB)
            }
            if (auR != null && alR != null && hR != null) {
                joint(m1, auR, p.shR, 0f, p.shSideR, m6)
                joint(m6, alR, p.elR, 0f, 0f, m7)
                joint(m7, hR, 0f, 0f, 0f, m8)
                drawPartWith(model, auR, m6, r, g, b); drawPartWith(model, alR, m7, r, g, b); drawPartWith(model, hR, m8, skinR, skinG, skinB)
            }
        }
    }

    // ============================================================================================
    // Player + gun
    // ============================================================================================

    private val gunNames = arrayOf("gun_m1911", "gun_mp5", "gun_m4a1", "gun_scar", "gun_awm")
    private val gunTipZ = floatArrayOf(0.21f, 0.51f, 0.79f, 0.81f, 1.14f)
    // grip (y,z) for the trigger hand and (y,z) for the support hand, in gun-local space
    private val gripR = arrayOf(floatArrayOf(-0.045f, 0.03f), floatArrayOf(-0.10f, -0.01f), floatArrayOf(-0.10f, -0.02f), floatArrayOf(-0.10f, -0.02f), floatArrayOf(-0.10f, -0.02f))
    private val gripL = arrayOf(floatArrayOf(-0.055f, 0.05f), floatArrayOf(-0.055f, 0.20f), floatArrayOf(-0.055f, 0.30f), floatArrayOf(-0.055f, 0.31f), floatArrayOf(-0.055f, 0.32f))

    private fun drawPlayer(tpp: Boolean) {
        val soldier = ModelBank.get("player_soldier")
        val suppliedPlayer = ModelBank.get(if (surfaceWidth >= 1080) "player_sara" else "player_sara_lite")
        val gunIdx = engine.equippedGunIndex.coerceIn(0, gunNames.size - 1)
        val gun = ModelBank.get(gunNames[gunIdx])
        val hero = engine.heroes[engine.equippedHeroIndex]
        val moving = engine.playerIsMoving
        val cyc = engine.walkCycle
        val bob = if (moving) abs(sin(cyc)) * 0.03f else 0f
        val kick = engine.recoilKick * 3.2f // 0..0.8 for a punchy visual recoil
        val pitch = engine.playerPitch
        val reloading = engine.reloadTimer > 0f

        // player base matrix: world position + facing
        Matrix.setIdentityM(baseM, 0)
        Matrix.translateM(baseM, 0, engine.playerX, bob, engine.playerZ)
        Matrix.rotateM(baseM, 0, engine.playerYaw, 0f, 1f, 0f)

        // gun local placement in player space (right hand side = -X)
        val reloadTilt = if (reloading) sin(min(1f, engine.reloadTimer / 0.95f) * 3.14159f) * 28f else 0f
        Matrix.setIdentityM(gunLocal, 0)
        if (tpp) {
            // Sara's supplied arms rest lower than the old soldier rig's weapon pose.
            Matrix.translateM(gunLocal, 0, -0.10f, 0.96f, 0.22f - kick * 0.10f)
            Matrix.rotateM(gunLocal, 0, -pitch - kick * 7f + reloadTilt, 1f, 0f, 0f)
        } else {
            // first person: hold the gun lower-right in the camera view
            // portrait screens are narrow: keep the gun close to the centre line and only slightly below eye level
            Matrix.translateM(gunLocal, 0, if (engine.isScoped) -0.02f else -0.085f, 1.455f - (if (reloading) 0.12f else 0f), 0.40f - kick * 0.12f)
            Matrix.rotateM(gunLocal, 0, -pitch - kick * 5f, 1f, 0f, 0f)
        }
        Matrix.multiplyMM(gunWorld, 0, baseM, 0, gunLocal, 0)

        // muzzle & ejection-port world positions for effects
        v4a[0] = 0f; v4a[1] = 0.04f; v4a[2] = gunTipZ[gunIdx]; v4a[3] = 1f
        Matrix.multiplyMV(v4b, 0, gunWorld, 0, v4a, 0)
        muzzleWorld[0] = v4b[0]; muzzleWorld[1] = v4b[1]; muzzleWorld[2] = v4b[2]
        v4a[0] = 0.02f; v4a[1] = 0.05f; v4a[2] = gunTipZ[gunIdx] * 0.25f
        Matrix.multiplyMV(v4b, 0, gunWorld, 0, v4a, 0)
        shellPortWorldX = v4b[0]; shellPortWorldY = v4b[1]; shellPortWorldZ = v4b[2]

        GLES20.glUniform3f(uMuzzlePos, muzzleWorld[0], muzzleWorld[1], muzzleWorld[2])
        GLES20.glUniform1f(uMuzzleIntensity, if (engine.muzzleFlashTimer > 0f) 2.6f else 0f)

        if (tpp) {
            drawMesh(MeshLibrary.disc, engine.playerX, 0.02f, engine.playerZ, 1.15f, 1f, 1.15f, 0f, texShadow, 1f, 1f, 1f, 1f, 1f, 0.85f)
        }

        // Draw Sara slightly above the floor so both feet remain clear.
        if (tpp && suppliedPlayer != null) {
            System.arraycopy(baseM, 0, m8, 0, 16)
            Matrix.translateM(m8, 0, 0f, 0.015f, 0f)
            drawPartWith(suppliedPlayer, suppliedPlayer.part("body"), m8, 1f, 1f, 1f)
        }

        if (soldier != null) {
            pose.reset()
            val amp = if (moving) 34f else 0f
            val s = sin(cyc)
            pose.hipL = s * amp
            pose.hipR = -s * amp
            pose.kneeL = if (moving) 6f + max(0f, -s) * 40f else 0f
            pose.kneeR = if (moving) 6f + max(0f, s) * 40f else 0f
            pose.headX = -pitch * 0.55f
            pose.drawBody = tpp && suppliedPlayer == null
            pose.useIk = true

            // targets in player-local space
            val gripRy = gripR[gunIdx][0]; val gripRz = gripR[gunIdx][1]
            val gripLy = gripL[gunIdx][0]; val gripLz = gripL[gunIdx][1]
            val armTrigger = soldier.part("arm_upper_L")   // pivots at -X (the character's right side)
            val armSupport = soldier.part("arm_upper_R")
            val lA = soldier.part("arm_lower_L"); val hA = soldier.part("hand_L")
            val lB = soldier.part("arm_lower_R"); val hB = soldier.part("hand_R")
            if (armTrigger != null && armSupport != null && lA != null && hA != null && lB != null && hB != null) {
                v4a[0] = 0f; v4a[1] = gripRy; v4a[2] = gripRz; v4a[3] = 1f
                Matrix.multiplyMV(v4b, 0, gunLocal, 0, v4a, 0)
                val trx = v4b[0]; val tryy = v4b[1]; val trz = v4b[2]
                v4a[0] = 0f; v4a[1] = gripLy; v4a[2] = gripLz; v4a[3] = 1f
                Matrix.multiplyMV(v4b, 0, gunLocal, 0, v4a, 0)
                val tlx = v4b[0]; val tly = v4b[1]; val tlz = v4b[2]
                solveArm(baseM, armTrigger, lA, hA, trx, tryy - 0.01f, trz - 0.05f, -0.55f, -1f, -0.25f, upperArm[0], lowerArm[0])
                solveArm(baseM, armSupport, lB, hB, tlx, tly - 0.01f, tlz - 0.05f, 0.55f, -1f, -0.15f, upperArm[1], lowerArm[1])
            } else {
                pose.useIk = false
            }
            val tr = min(0.80f + hero.armorR * 0.55f, 1.15f)
            val tg = min(0.80f + hero.armorG * 0.55f, 1.15f)
            val tb = min(0.80f + hero.armorB * 0.55f, 1.15f)
            // Avoid drawing the soldier's IK arms over Sara's own arms in third person.
            if (!(tpp && suppliedPlayer?.part("body") != null)) {
                drawHumanoid(soldier, baseM, pose, tr, tg, tb, true)
            }
        }

        // the gun itself (slide kicks back on each shot)
        if (gun != null) {
            for (part in gun.parts.values) {
                System.arraycopy(gunWorld, 0, modelMatrix, 0, 16)
                if (part.name == "slide_or_bolt" && kick > 0f) {
                    Matrix.translateM(modelMatrix, 0, 0f, 0f, -kick * 0.12f)
                }
                submitMesh(part.mesh, gun.texId, 1f, 1f, 1f, 1f, 1f, 1f)
            }
        }
    }

    // ============================================================================================
    // Enemies
    // ============================================================================================

    private fun zombieModelName(type: Int): String = when (type) {
        6 -> "infected_dog"
        else -> "zombie_re1"
    }

    // Keep skin variation subtle and earthy so gait styles do not read as bright color variants.
    private val variantTint = arrayOf(
        floatArrayOf(1.00f, 1.00f, 1.00f),
        floatArrayOf(0.97f, 0.99f, 0.94f),
        floatArrayOf(1.00f, 0.96f, 0.92f),
        floatArrayOf(0.95f, 0.98f, 0.99f)
    )

    private fun drawZombie(z: GameEngine.Zombie3D) {
        if (!inView(z.x, 1f, z.z, 2.5f)) return
        val s = z.scale
        drawMesh(MeshLibrary.disc, z.x, 0.02f, z.z, 1.35f * s, 1f, 1.35f * s, 0f, texShadow, 1f, 1f, 1f, 1f, 1f, 0.82f)
        val model = ModelBank.get(zombieModelName(z.type)) ?: return

        val flash = z.hitFlash > 0f
        val vt = variantTint[z.gaitStyle and 3]
        var r = vt[0]; var g = vt[1]; var b = vt[2]
        // Mute the red cast of the bloodied atlas for a colder, more natural infected skin tone.
        r *= 0.88f; g *= 0.93f; b *= 0.89f
        if (z.type == 3) { r *= 1.02f; g *= 0.91f; b *= 0.87f }
        if (flash) { r = 1.16f; g = 0.74f; b = 0.69f }

        Matrix.setIdentityM(baseM, 0)
        Matrix.translateM(baseM, 0, z.x, 0f, z.z)
        Matrix.rotateM(baseM, 0, z.yaw, 0f, 1f, 0f)

        val ph = z.animPhase
        if (z.type == 6) {
            drawDog(model, z, r, g, b)
            return
        }
        val suppliedBody = model.part("body")
        if (suppliedBody != null) {
            Matrix.scaleM(baseM, 0, s, s, s)
            Matrix.translateM(baseM, 0, 0f, 0.92f, 0f)
            val bodyLean = if (z.isAttacking) 13f + 3f * sin(ph * 2f) else 7f + 2f * sin(ph)
            Matrix.rotateM(baseM, 0, bodyLean, 1f, 0f, 0f)
            Matrix.translateM(baseM, 0, 0f, -0.92f, 0f)
            Matrix.translateM(baseM, 0, 0f, abs(sin(ph)) * 0.035f, 0f)
            drawPartWith(model, suppliedBody, baseM, r, g, b)
            return
        }
        Matrix.scaleM(baseM, 0, s, s, s)
        Matrix.translateM(baseM, 0, 0f, abs(sin(ph)) * 0.035f, 0f)

        pose.reset()
        val sw = sin(ph)
        when {
            z.isAttacking -> {
                // arms raised and clawing, body lunging
                val claw = sin(ph * 2.2f)
                pose.lean = 15f + 4f * abs(claw)
                pose.shL = -78f + claw * 16f
                pose.shR = -78f - claw * 16f
                pose.elL = -25f; pose.elR = -25f
                pose.headX = 14f
                pose.hipL = 6f; pose.hipR = -6f
                pose.kneeL = 10f; pose.kneeR = 10f
            }
            z.gaitStyle == 2 || z.type == 1 -> {
                // fast, frantic run
                pose.lean = 18f
                pose.hipL = sw * 36f; pose.hipR = -sw * 36f
                pose.kneeL = 8f + max(0f, -sw) * 48f; pose.kneeR = 8f + max(0f, sw) * 48f
                pose.shL = -sw * 42f; pose.shR = sw * 42f
                pose.elL = -75f; pose.elR = -75f
                pose.headX = 8f
            }
            z.gaitStyle == 1 -> {
                // dragging limp, one arm reaching
                pose.lean = 12f
                pose.hipL = sw * 18f; pose.hipR = -sw * 30f
                pose.kneeL = 4f; pose.kneeR = 6f + max(0f, sw) * 36f
                pose.shL = -78f + sin(ph * 0.5f) * 7f
                pose.shR = -12f + sw * 12f
                pose.elL = -12f; pose.elR = -30f
                pose.headX = 18f; pose.headY = sin(ph * 0.5f) * 14f
            }
            z.type == 2 || z.type == 3 -> {
                // heavy stomp
                pose.lean = 7f
                pose.hipL = sw * 24f; pose.hipR = -sw * 24f
                pose.kneeL = 5f + max(0f, -sw) * 24f; pose.kneeR = 5f + max(0f, sw) * 24f
                pose.shL = -38f + sw * 11f; pose.shR = -38f - sw * 11f
                pose.shSideL = -12f; pose.shSideR = 12f
                pose.elL = -30f; pose.elR = -30f
                pose.headX = 6f
            }
            else -> {
                // classic shamble, arms outstretched
                pose.lean = 9f
                pose.hipL = sw * 24f; pose.hipR = -sw * 24f
                pose.kneeL = 5f + max(0f, -sw) * 28f; pose.kneeR = 5f + max(0f, sw) * 28f
                pose.shL = -60f + sin(ph * 0.5f) * 7f
                pose.shR = -56f - sin(ph * 0.5f) * 7f
                pose.elL = -14f; pose.elR = -18f
                pose.headX = 12f; pose.headY = sin(ph * 0.5f) * 7f
            }
        }
        drawHumanoid(model, baseM, pose, r, g, b)
    }

    private fun drawDog(model: PartModel, z: GameEngine.Zombie3D, r: Float, g: Float, b: Float) {
        val s = z.scale
        Matrix.scaleM(baseM, 0, s, s, s)
        val ph = z.animPhase
        val sw = sin(ph)
        Matrix.translateM(baseM, 0, 0f, abs(sw) * 0.05f, 0f)
        val body = model.part("body")
        val head = model.part("head")
        val jaw = model.part("jaw")
        val tail = model.part("tail")
        if (body != null) {
            joint(baseM, body, sw * 3f, 0f, 0f, m1)
            drawPartWith(model, body, m1, r, g, b)
        } else System.arraycopy(baseM, 0, m1, 0, 16)
        if (head != null) {
            joint(m1, head, sin(ph * 2f) * 6f + (if (z.isAttacking) -6f else 0f), sin(ph) * 5f, 0f, m2)
            drawPartWith(model, head, m2, r, g, b)
            if (jaw != null) {
                val open = if (z.isAttacking) 24f + 12f * sin(ph * 3f) else 5f
                joint(m2, jaw, open, 0f, 0f, m3)
                drawPartWith(model, jaw, m3, r, g, b)
            }
        }
        if (tail != null) {
            joint(m1, tail, -20f, sin(ph * 1.5f) * 26f, 0f, m4)
            drawPartWith(model, tail, m4, r, g, b)
        }
        val legs = arrayOf("FL", "FR", "BL", "BR")
        val phaseOffset = floatArrayOf(0f, 3.14159f, 3.14159f, 0f)
        for (i in 0..3) {
            val up = model.part("leg_upper_" + legs[i]) ?: continue
            val lo = model.part("leg_lower_" + legs[i]) ?: continue
            val swing = sin(ph + phaseOffset[i]) * 44f
            joint(m1, up, -swing, 0f, 0f, m5)
            joint(m5, lo, 14f + max(0f, swing) * 0.9f, 0f, 0f, m6)
            drawPartWith(model, up, m5, r, g, b)
            drawPartWith(model, lo, m6, r, g, b)
        }
    }

    private fun drawCorpse(c: GameEngine.Corpse3D) {
        if (!inView(c.x, 0.3f, c.z, 2.5f)) return
        val model = ModelBank.get(zombieModelName(c.type)) ?: return
        val fall = (c.age / 0.55f).coerceIn(0f, 1f)
        val ease = 1f - (1f - fall) * (1f - fall)
        val sink = if (c.age > 6f) (c.age - 6f) * 0.25f else 0f
        Matrix.setIdentityM(baseM, 0)
        Matrix.translateM(baseM, 0, c.x, -sink, c.z)
        Matrix.rotateM(baseM, 0, c.yaw, 0f, 1f, 0f)
        Matrix.scaleM(baseM, 0, c.scale, c.scale, c.scale)
        val vt = variantTint[c.gait and 3]
        val dark = 0.72f
        if (c.type == 6) {
            Matrix.translateM(baseM, 0, 0f, 0.10f * ease, 0f)
            Matrix.rotateM(baseM, 0, 88f * ease, 0f, 0f, 1f)
            for (part in model.parts.values) drawPartWith(model, part, baseM, vt[0] * dark, vt[1] * dark, vt[2] * dark)
            return
        }
        Matrix.translateM(baseM, 0, 0f, 0.14f * ease, 0f)
        Matrix.rotateM(baseM, 0, -90f * ease, 1f, 0f, 0f)
        pose.reset()
        pose.shSideL = -28f * ease; pose.shSideR = 28f * ease
        pose.hipL = 6f * ease; pose.hipR = -10f * ease
        pose.elL = -10f; pose.elR = -10f
        drawHumanoid(model, baseM, pose, vt[0] * dark, vt[1] * dark, vt[2] * dark)
    }

    // ============================================================================================
    // Effects: muzzle flash, sparks, smoke, shell casings, tracers, blood
    // ============================================================================================

    private fun handleShotEffects() {
        if (engine.shotCounter == lastShotSeen) return
        lastShotSeen = engine.shotCounter
        val fx0 = lastFwdX; val fy0 = lastFwdY; val fz0 = lastFwdZ
        val rx = -fz0; val rz = fx0 // horizontal vector perpendicular to the aim direction
        val mx = muzzleWorld[0]; val my = muzzleWorld[1]; val mz = muzzleWorld[2]
        // sparks
        for (i in 0 until 8) {
            val sp = 4f + Random.nextFloat() * 7f
            fx.add(Fx(0, mx, my, mz,
                fx0 * sp + (Random.nextFloat() - 0.5f) * 3f,
                fy0 * sp + (Random.nextFloat() - 0.2f) * 2.5f,
                fz0 * sp + (Random.nextFloat() - 0.5f) * 3f,
                0.10f + Random.nextFloat() * 0.14f, 0.24f, 0.035f + Random.nextFloat() * 0.03f, 0f))
        }
        // smoke
        for (i in 0 until 3) {
            fx.add(Fx(1, mx, my, mz,
                fx0 * 0.9f + (Random.nextFloat() - 0.5f) * 0.6f,
                0.45f + Random.nextFloat() * 0.5f,
                fz0 * 0.9f + (Random.nextFloat() - 0.5f) * 0.6f,
                0.8f + Random.nextFloat() * 0.5f, 1.3f, 0.12f + Random.nextFloat() * 0.06f, Random.nextFloat() * 360f))
        }
        // ejected brass (flies out to the right of the gun)
        fx.add(Fx(2, shellPortWorldX, shellPortWorldY, shellPortWorldZ,
            rx * (1.6f + Random.nextFloat()) + (Random.nextFloat() - 0.5f) * 0.5f,
            1.6f + Random.nextFloat() * 1.2f,
            rz * (1.6f + Random.nextFloat()) + (Random.nextFloat() - 0.5f) * 0.5f,
            1.3f, 1.3f, 1f, Random.nextFloat() * 360f))
        while (fx.size > 90) fx.removeAt(0)
    }

    private fun billboard(x: Float, y: Float, z: Float, size: Float, rotDeg: Float) {
        val v = viewMatrix
        Matrix.setIdentityM(modelMatrix, 0)
        // camera right, up, back vectors (rows of the view matrix) form the sprite basis
        modelMatrix[0] = v[0]; modelMatrix[1] = v[4]; modelMatrix[2] = v[8]
        modelMatrix[4] = v[1]; modelMatrix[5] = v[5]; modelMatrix[6] = v[9]
        modelMatrix[8] = v[2]; modelMatrix[9] = v[6]; modelMatrix[10] = v[10]
        modelMatrix[12] = x; modelMatrix[13] = y; modelMatrix[14] = z
        if (rotDeg != 0f) Matrix.rotateM(modelMatrix, 0, rotDeg, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, size, size, size)
    }

    private fun drawEffects(dt: Float) {
        // --- tracers (unlit, additive) ---
        GLES20.glUniform1f(uUnlit, 1f)
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        for (bl in engine.bullets) {
            drawMesh(MeshLibrary.bulletMesh, bl.x, bl.y, bl.z, 0.022f, 0.022f, 1.6f, bl.yaw, 0, 1f, 1f, 1.0f, 0.82f, 0.45f, 0.9f)
        }

        // --- muzzle flash: rotated additive sprites + a cone of fire along the barrel ---
        if (engine.muzzleFlashTimer > 0f) {
            val k = (engine.muzzleFlashTimer / 0.07f).coerceIn(0f, 1f)
            val big = 0.50f + Random.nextFloat() * 0.25f
            billboard(muzzleWorld[0], muzzleWorld[1], muzzleWorld[2], big * (0.7f + 0.5f * k), Random.nextFloat() * 360f)
            submitMesh(MeshLibrary.quad, texFlash, 1f, 1f, 1f, 0.95f, 0.8f, 1f)
            billboard(muzzleWorld[0] + lastFwdX * 0.12f, muzzleWorld[1] + lastFwdY * 0.12f, muzzleWorld[2] + lastFwdZ * 0.12f, big * 0.55f, Random.nextFloat() * 360f)
            submitMesh(MeshLibrary.quad, texFlash, 1f, 1f, 1f, 1f, 0.9f, 1f)
            val yaw = Math.toDegrees(atan2(lastFwdX.toDouble(), lastFwdZ.toDouble())).toFloat()
            drawMesh(MeshLibrary.muzzleFlashMesh, muzzleWorld[0], muzzleWorld[1], muzzleWorld[2], 0.30f, 0.30f, 0.85f * (0.6f + 0.6f * k), yaw, 0, 1f, 1f, 1.0f, 0.70f, 0.25f, 0.95f)
        }

        // --- advance all effects, draw sparks (additive) ---
        var i = 0
        while (i < fx.size) {
            val f = fx[i]
            f.life -= dt
            if (f.life <= 0f) { fx.removeAt(i); continue }
            f.x += f.vx * dt; f.y += f.vy * dt; f.z += f.vz * dt
            if (f.kind == 0) {
                val a = f.life / f.maxLife
                billboard(f.x, f.y, f.z, f.size * (0.6f + a), 0f)
                submitMesh(MeshLibrary.quad, texFlash, 1f, 1f, 1f, 0.8f, 0.45f, a)
            }
            i++
        }

        // --- smoke (alpha blended) ---
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (f in fx) {
            if (f.kind != 1) continue
            val t = 1f - f.life / f.maxLife
            f.vy *= (1f - 0.8f * dt)
            val sz = f.size + t * 0.55f
            billboard(f.x, f.y, f.z, sz, f.rot)
            submitMesh(MeshLibrary.quad, texSmoke, 1f, 1f, 0.82f, 0.82f, 0.82f, (1f - t) * 0.55f)
        }
        GLES20.glDepthMask(true)

        // --- brass casings (lit, tiny boxes with gravity and a bounce) ---
        GLES20.glUniform1f(uUnlit, 0f)
        for (f in fx) {
            if (f.kind != 2) continue
            f.vy -= 9.8f * dt
            if (f.y < 0.02f) {
                f.y = 0.02f
                f.vy = abs(f.vy) * 0.35f
                f.vx *= 0.5f; f.vz *= 0.5f
            }
            f.rot += 540f * dt
            drawMesh(MeshLibrary.box, f.x, f.y, f.z, 0.012f, 0.012f, 0.042f, f.rot, 0, 1f, 1f, 0.88f, 0.68f, 0.24f, 1f)
        }

        // --- blood from the engine ---
        for (pt in engine.particles) {
            drawMesh(MeshLibrary.bloodShardMesh, pt.x, pt.y, pt.z, pt.size * 0.7f, pt.size * 1.5f, pt.size * 0.7f, pt.rotDeg, 0, 1f, 1f, pt.r, pt.g, pt.b, 0.92f)
        }
    }

    // ============================================================================================
    // Draw helpers
    // ============================================================================================

    private fun drawMesh(
        mesh: MeshLibrary.Mesh,
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        yawDeg: Float,
        texId: Int, uvX: Float, uvY: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        submitMesh(mesh, texId, uvX, uvY, r, g, b, a)
    }

    private var boundVbo = -1

    /** Uploads the mesh to the GPU once per GL context and binds it. */
    private fun bindVbo(mesh: MeshLibrary.Mesh) {
        if (mesh.vboGen != glGen || mesh.vbo == 0) {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            mesh.vbo = ids[0]
            mesh.vboGen = glGen
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
            mesh.buffer.position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertexCount * mesh.strideBytes, mesh.buffer, GLES20.GL_STATIC_DRAW)
            boundVbo = mesh.vbo
            return
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        boundVbo = mesh.vbo
    }

    private fun submitMesh(
        mesh: MeshLibrary.Mesh,
        texId: Int, uvX: Float, uvY: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        bindVbo(mesh)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, mesh.strideBytes, 0)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, mesh.strideBytes, 12)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, mesh.strideBytes, 24)

        if (texId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1f(uUseTex, 1f)
        } else {
            GLES20.glUniform1f(uUseTex, 0f)
        }

        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform2f(uUvScale, uvX, uvY)
        GLES20.glUniform4f(uTint, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
    }
}
