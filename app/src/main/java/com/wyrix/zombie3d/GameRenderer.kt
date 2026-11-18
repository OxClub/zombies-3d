package com.wyrix.zombie3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GameRenderer(
    private val context: Context,
    private val engine: GameEngine,
    private val onFrameRendered: () -> Unit
) : GLSurfaceView.Renderer {

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
    private var uLightDir = 0
    private var uCamPos = 0
    private var uFogColor = 0
    private var uMuzzlePos = 0
    private var uMuzzleIntensity = 0

    private var texCarpet = 0
    private var texCrate = 0
    private var texWall = 0
    private var texBlood = 0
    private var texZombie = 0
    private var texClothes = 0
    private var texLurcherAtlas = 0
    private var texShadow = 0

    private val riggedZombieModels = ArrayList<SkinnedZombieModel>()
    private val zombieMaterialTextures = HashMap<String, Int>()
    private var lastCamX = 0f
    private var lastCamY = 1.6f
    private var lastCamZ = 0f
    private var lastFogR = 0.5f
    private var lastFogG = 0.6f
    private var lastFogB = 0.7f
    private val lurcherWalkFrames = ArrayList<MeshLibrary.Mesh>()
    private val lurcherAttackFrames = ArrayList<MeshLibrary.Mesh>()
    private var hunyuanMutantMesh: MeshLibrary.Mesh? = null
    private var headObjMesh: MeshLibrary.Mesh? = null
    private var torsoObjMesh: MeshLibrary.Mesh? = null
    private var forearmHandObjMesh: MeshLibrary.Mesh? = null
    private var rifleObjMesh: MeshLibrary.Mesh? = null

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var currentFov = 50f
    private var viewRatio = 0.56f
    private var lastNano = System.nanoTime()

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
        "uniform vec3 uLightDir;\n" +
        "uniform vec3 uCamPos;\n" +
        "uniform vec3 uFogColor;\n" +
        "uniform vec3 uMuzzlePos;\n" +
        "uniform float uMuzzleIntensity;\n" +
        "varying vec3 vNormal;\n" +
        "varying vec3 vWorldPos;\n" +
        "varying vec2 vUv;\n" +
        "void main() {\n" +
        "    vec3 n = normalize(vNormal);\n" +
        "    vec3 l = normalize(uLightDir);\n" +
        "    vec3 v = normalize(uCamPos - vWorldPos);\n" +
        "    vec3 h = normalize(l + v);\n" +
        "    float diff = max(dot(n, l), 0.0);\n" +
        "    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.22;\n" +
        "    float spec = pow(max(dot(n, h), 0.0), 28.0) * 0.34;\n" +
        "    vec4 texCol = uUseTex > 0.5 ? texture2D(uTex, vUv) : vec4(1.0);\n" +
        "    if (texCol.a < 0.08) discard;\n" +
        "    vec3 baseCol = texCol.rgb * uTint.rgb;\n" +
        "    float dMuzzle = length(uMuzzlePos - vWorldPos);\n" +
        "    float flash = uMuzzleIntensity / (1.0 + dMuzzle * dMuzzle * 0.30);\n" +
        "    vec3 lit = baseCol * (0.48 + diff * 0.60 + rim) + vec3(1.0, 0.96, 0.90) * spec + vec3(1.0, 0.78, 0.32) * flash;\n" +
        "    float dist = length(uCamPos - vWorldPos);\n" +
        "    float fog = clamp((dist - 18.0) / 38.0, 0.0, 0.50);\n" +
        "    gl_FragColor = vec4(mix(lit, uFogColor, fog), texCol.a * uTint.a);\n" +
        "}"

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.52f, 0.62f, 0.74f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
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
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos")
        uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
        uMuzzlePos = GLES20.glGetUniformLocation(program, "uMuzzlePos")
        uMuzzleIntensity = GLES20.glGetUniformLocation(program, "uMuzzleIntensity")

        texCarpet = TextureGenerator.loadAssetTexture(context, "textures/highway_2k.png", true)
        texCrate = TextureGenerator.loadAssetTexture(context, "textures/crate_1k.png", true)
        texWall = TextureGenerator.loadAssetTexture(context, "textures/concrete_2k.png", true)
        texBlood = TextureGenerator.loadAssetTexture(context, "textures/blood_splatter.png", false)
        texLurcherAtlas = TextureGenerator.loadAssetTexture(context, "textures/ga024_atlas.png", false)
        texZombie = TextureGenerator.createZombieSkinTexture()
        texClothes = TextureGenerator.createClothingTexture()
        texShadow = TextureGenerator.createShadowTexture()

        SkinnedZombieModel.initShader()
        zombieMaterialTextures["skin"] = TextureGenerator.loadAssetTexture(context, "textures/skin.png", false)
        zombieMaterialTextures["eyes"] = TextureGenerator.loadAssetTexture(context, "textures/eyes.png", false)
        zombieMaterialTextures["wound"] = TextureGenerator.loadAssetTexture(context, "textures/wound.png", false)
        zombieMaterialTextures["shirt"] = TextureGenerator.loadAssetTexture(context, "textures/shirt.png", false)
        zombieMaterialTextures["pants"] = TextureGenerator.loadAssetTexture(context, "textures/pants.png", false)
        zombieMaterialTextures["cloth_light"] = TextureGenerator.loadAssetTexture(context, "textures/cloth_light.png", false)
        zombieMaterialTextures["boots"] = TextureGenerator.loadAssetTexture(context, "textures/boots.png", false)

        riggedZombieModels.clear()
        for (i in 1..5) {
            SkinnedZombieModel.load(context, "models/zombie_0$i.wzombie.json")?.let {
                riggedZombieModels.add(it)
            }
        }

        lurcherWalkFrames.clear()
        for (i in 0..5) {
            MeshLibrary.loadObj(context, "models/lurcher_walk_$i.obj")?.let { lurcherWalkFrames.add(it) }
        }
        lurcherAttackFrames.clear()
        for (i in 0..1) {
            MeshLibrary.loadObj(context, "models/lurcher_attack_$i.obj")?.let { lurcherAttackFrames.add(it) }
        }

        hunyuanMutantMesh = MeshLibrary.loadObj(context, "models/hunyuan_mutant.obj")
        headObjMesh = MeshLibrary.loadObj(context, "models/human_head.obj")
        torsoObjMesh = MeshLibrary.loadObj(context, "models/human_torso.obj")
        forearmHandObjMesh = MeshLibrary.loadObj(context, "models/forearm_hand.obj")
        rifleObjMesh = MeshLibrary.loadObj(context, "models/tactical_rifle.obj")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewRatio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projMatrix, 0, currentFov, viewRatio, 0.2f, 120f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNano) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNano = now

        engine.update(dt)

        val targetFov = if (engine.isScoped) 25f else 52f
        if (kotlin.math.abs(targetFov - currentFov) > 0.2f) {
            currentFov += (targetFov - currentFov) * 12f * dt
            Matrix.perspectiveM(projMatrix, 0, currentFov, viewRatio, 0.2f, 120f)
        }

        val skyR = 0.46f + (engine.activeRoomNumber % 3) * 0.06f
        val skyG = 0.54f + (engine.activeRoomNumber % 2) * 0.08f
        val skyB = 0.66f
        GLES20.glClearColor(skyR, skyG, skyB, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLightDir, 0.35f, 0.90f, 0.28f)
        GLES20.glUniform3f(uFogColor, skyR, skyG, skyB)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uUvOffset, 0f, 0f)

        synchronized(engine) {
            val yawRad = Math.toRadians(engine.playerYaw.toDouble())
            val pitchRad = Math.toRadians(engine.playerPitch.toDouble())
            val fwdX = (sin(yawRad) * cos(pitchRad)).toFloat()
            val fwdY = sin(pitchRad).toFloat()
            val fwdZ = (cos(yawRad) * cos(pitchRad)).toFloat()
            val rightX = cos(yawRad).toFloat()
            val rightZ = -sin(yawRad).toFloat()

            val walkBobY = kotlin.math.abs(sin(engine.walkCycle)) * 0.045f
            val walkBobX = cos(engine.walkCycle * 0.5f) * 0.025f
            val shakeX = if (engine.shakeTimer > 0f) (Random.nextFloat() - 0.5f) * 0.16f else 0f
            val shakeY = if (engine.shakeTimer > 0f) (Random.nextFloat() - 0.5f) * 0.16f else 0f

            val camX: Float
            val camY: Float
            val camZ: Float

            if (engine.isTppMode) {
                // Elevated Over-The-Shoulder TPP Camera so you see the full player body + gun + zombies!
                val backDist = if (engine.isScoped) 1.55f else 2.85f
                val sideOffset = if (engine.isScoped) 0.38f else 0.52f
                camX = engine.playerX - sin(yawRad).toFloat() * backDist + rightX * sideOffset + shakeX
                camY = (if (engine.isScoped) 1.85f else 2.28f) + walkBobY + shakeY
                camZ = engine.playerZ - cos(yawRad).toFloat() * backDist + rightZ * sideOffset
                GLES20.glUniform3f(uCamPos, camX, camY, camZ)

                val lookTargetX = engine.playerX + fwdX * 14f
                val lookTargetY = 1.35f + fwdY * 14f
                val lookTargetZ = engine.playerZ + fwdZ * 14f
                Matrix.setLookAtM(viewMatrix, 0, camX, camY, camZ, lookTargetX, lookTargetY, lookTargetZ, 0f, 1f, 0f)
            } else {
                camX = engine.playerX + walkBobX + shakeX
                camY = 1.58f + walkBobY + shakeY
                camZ = engine.playerZ
                GLES20.glUniform3f(uCamPos, camX, camY, camZ)
                Matrix.setLookAtM(
                    viewMatrix, 0,
                    camX, camY, camZ,
                    camX + fwdX * 12f, camY + fwdY * 12f, camZ + fwdZ * 12f,
                    0f, 1f, 0f
                )
            }

            val mx = engine.playerX + sin(yawRad).toFloat() * 1.15f
            val mz = engine.playerZ + cos(yawRad).toFloat() * 1.15f
            GLES20.glUniform3f(uMuzzlePos, mx, 1.35f, mz)
            GLES20.glUniform1f(uMuzzleIntensity, if (engine.muzzleFlashTimer > 0f) 2.2f else 0f)
            lastCamX = camX; lastCamY = camY; lastCamZ = camZ
            lastFogR = skyR; lastFogG = skyG; lastFogB = skyB
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            renderCorridorEnvironment()

            if (engine.isTppMode) {
                renderFullTppPlayerCharacter()
            } else {
                renderFirstPersonGunAndGlovedHands()
            }

            for (z in engine.zombies) {
                renderEnemyEntity(z)
            }

            if (engine.muzzleFlashTimer > 0f) {
                drawMesh(MeshLibrary.muzzleFlashMesh, mx, 1.34f, mz, 0.42f, 0.42f, 0.78f, engine.playerYaw, 0, 1f, 1f, 1.0f, 0.84f, 0.32f, 0.95f)
            }

            for (b in engine.bullets) {
                drawMesh(MeshLibrary.bulletMesh, b.x, b.y, b.z, 0.025f, 0.025f, 1.45f, b.yaw, 0, 1f, 1f, b.r, b.g, b.b, 0.95f)
            }

            for (pt in engine.particles) {
                drawMesh(MeshLibrary.bloodShardMesh, pt.x, pt.y, pt.z, pt.size * 0.7f, pt.size * 1.5f, pt.size * 0.7f, pt.rotDeg, 0, 1f, 1f, pt.r, pt.g, pt.b, 0.92f)
            }
        }

        onFrameRendered()
    }

    // Full 3D Player Character Model in TPP Mode (Legs, Boots, Armored Torso, Head, Helmet, Arms & Detailed Rifle!)
    private fun renderFullTppPlayerCharacter() {
        val px = engine.playerX
        val pz = engine.playerZ
        val yaw = engine.playerYaw
        val hero = engine.heroes[engine.equippedHeroIndex]
        val gun = engine.guns[engine.equippedGunIndex]

        val isMoving = engine.moveForwardPressed || engine.moveBackwardPressed
        val legSwing = if (isMoving) sin(engine.walkCycle) * 32f else 0f
        val bobY = if (isMoving) kotlin.math.abs(sin(engine.walkCycle)) * 0.04f else 0f
        val kick = engine.recoilKick * 0.14f

        drawMesh(MeshLibrary.disc, px, 0.02f, pz, 1.15f, 1f, 1.15f, 0f, texShadow, 1f, 1f, 1f, 1f, 1f, 0.85f)

        // Tactical Cargo Pants & Combat Boots
        drawPart(MeshLibrary.humanLimbMesh, px, pz, yaw, -0.10f, 0.44f + bobY, 0f,  legSwing, 0f, 0.54f, 0.88f, 0.54f, texClothes, 0.18f, 0.22f, 0.26f)
        drawPart(MeshLibrary.humanLimbMesh, px, pz, yaw,  0.10f, 0.44f + bobY, 0f, -legSwing, 0f, 0.54f, 0.88f, 0.54f, texClothes, 0.18f, 0.22f, 0.26f)
        drawPart(MeshLibrary.bootMesh, px, pz, yaw, -0.10f, 0.06f,  sin(engine.walkCycle) * (if (isMoving) 0.18f else 0f), 0f, 0f, 0.52f, 0.44f, 0.82f, 0, 0.12f, 0.12f, 0.14f)
        drawPart(MeshLibrary.bootMesh, px, pz, yaw,  0.10f, 0.06f, -sin(engine.walkCycle) * (if (isMoving) 0.18f else 0f), 0f, 0f, 0.52f, 0.44f, 0.82f, 0, 0.12f, 0.12f, 0.14f)

        // Hero Armored Vest Torso
        torsoObjMesh?.let {
            drawPart(it, px, pz, yaw, 0f, 0.86f + bobY, 0f, 4f, 0f, 0.98f, 0.98f, 0.98f, texClothes, hero.armorR, hero.armorG, hero.armorB)
        }

        // Head & Tactical Helmet
        headObjMesh?.let {
            drawPart(it, px, pz, yaw, 0f, 1.38f + bobY, 0.04f, -engine.playerPitch * 0.4f, 0f, 0.96f, 0.96f, 0.96f, 0, 0.78f, 0.62f, 0.52f)
        }
        drawPart(MeshLibrary.sphere, px, pz, yaw, 0f, 1.64f + bobY, 0.04f, 0f, 0f, 0.23f, 0.16f, 0.25f, 0, 0.16f, 0.20f, 0.24f)

        // Arms & Detailed Multi-Part Tactical Gun held in Shooting Stance
        val gunY = 1.30f + bobY
        val gunZ = 0.46f - kick
        forearmHandObjMesh?.let {
            drawPart(it, px, pz, yaw,  0.18f, gunY - 0.02f, 0.16f - kick, -engine.playerPitch - 8f, -10f, 0.95f, 0.95f, 0.95f, 0, hero.armorR, hero.armorG, hero.armorB)
            drawPart(it, px, pz, yaw, -0.14f, gunY - 0.02f, 0.22f - kick, -engine.playerPitch - 6f,  26f, 0.95f, 0.95f, 0.95f, 0, hero.armorR, hero.armorG, hero.armorB)
        }
        rifleObjMesh?.let {
            drawPart(it, px, pz, yaw, 0.10f, gunY, gunZ, -engine.playerPitch, 0f, 1.05f, 1.05f, gun.lengthScale, 0, gun.r, gun.g, gun.b)
        }
    }

    private fun renderFirstPersonGunAndGlovedHands() {
        val px = engine.playerX
        val pz = engine.playerZ
        val yaw = engine.playerYaw
        val gun = engine.guns[engine.equippedGunIndex]
        val swayX = sin(engine.walkCycle * 0.5f) * 0.016f
        val swayY = cos(engine.walkCycle) * 0.014f
        val kick = engine.recoilKick * 0.32f
        val reloadDip = if (engine.reloadTimer > 0f) -0.18f else 0f

        val gx = (if (engine.isScoped) 0.02f else 0.16f) + swayX
        val gy = 1.38f + swayY + reloadDip
        val gz = 0.58f - kick

        rifleObjMesh?.let {
            drawPart(it, px, pz, yaw, gx, gy, gz, -engine.playerPitch, 0f, 1.15f, 1.15f, gun.lengthScale, 0, gun.r, gun.g, gun.b)
        }
        forearmHandObjMesh?.let {
            drawPart(it, px, pz, yaw, gx + 0.02f, gy - 0.06f, gz - 0.34f, -engine.playerPitch - 12f, -6f, 1.05f, 1.05f, 1.05f, 0, 0.20f, 0.22f, 0.26f)
            drawPart(it, px, pz, yaw, gx - 0.06f, gy - 0.07f, gz - 0.18f, -engine.playerPitch - 10f, 18f, 1.05f, 1.05f, 1.05f, 0, 0.20f, 0.22f, 0.26f)
        }
    }

    private fun renderCorridorEnvironment() {
        val half = engine.arenaHalf
        val floorTint = when (engine.activeRoomNumber % 4) {
            1 -> Triple(0.88f, 0.94f, 1.0f)
            2 -> Triple(0.96f, 0.90f, 0.84f)
            3 -> Triple(0.84f, 0.96f, 0.88f)
            else -> Triple(0.90f, 0.88f, 0.96f)
        }
        drawMesh(MeshLibrary.box, 0f, -0.25f, 0f, half * 2f, 0.5f, half * 2f, 0f, texCarpet, 14f, 14f, floorTint.first, floorTint.second, floorTint.third, 1f)

        drawMesh(MeshLibrary.box, 0f, 3.85f, -4f, 18.5f, 0.25f, 38f, 0f, texWall, 6f, 12f, 0.72f, 0.76f, 0.80f, 1f)
        for (lz in -12..8 step 6) {
            drawMesh(MeshLibrary.box, 0f, 3.70f, lz.toFloat(), 2.2f, 0.05f, 1.1f, 0f, 0, 1f, 1f, 0.96f, 0.98f, 1.0f, 1f)
        }

        for (bd in engine.bloodDecals) {
            drawMesh(MeshLibrary.box, bd.x, 0.012f, bd.z, bd.radius * 1.4f, 0.004f, bd.radius * 1.4f, bd.rotDeg, texBlood, 1f, 1f, 1f, 1f, 1f, 0.92f)
        }

        for (c in engine.crates) {
            when (c.kind) {
                4 -> {
                    val (wr, wg, wb) = when (c.colorIdx) {
                        0 -> Triple(0.96f, 0.98f, 1.0f)
                        1 -> Triple(0.84f, 0.92f, 0.98f)
                        2 -> Triple(0.96f, 0.92f, 0.84f)
                        3 -> Triple(0.84f, 0.96f, 0.88f)
                        else -> Triple(0.90f, 0.86f, 0.94f)
                    }
                    drawMesh(MeshLibrary.box, c.x, c.h * 0.5f, c.z, c.w, c.h, c.d, 0f, texWall, 4f, 1f, wr, wg, wb, 1f)
                    if (c.colorIdx == 1 && c.w > 8f) {
                        drawMesh(MeshLibrary.box, c.x - 1.6f, 2.2f, c.z + 0.32f, 2.4f, 1.6f, 0.08f, 0f, 0, 1f, 1f, 0.76f, 0.86f, 0.98f, 0.95f)
                        drawMesh(MeshLibrary.box, c.x + 1.6f, 2.2f, c.z + 0.32f, 2.4f, 1.6f, 0.08f, 0f, 0, 1f, 1f, 0.76f, 0.86f, 0.98f, 0.95f)
                    }
                }
                8 -> {
                    drawMesh(MeshLibrary.box, c.x, 1.02f, c.z, c.w, 0.07f, 0.10f, 0f, 0, 1f, 1f, 0.52f, 0.56f, 0.62f, 1f)
                    drawMesh(MeshLibrary.box, c.x, 0.12f, c.z, c.w, 0.07f, 0.10f, 0f, 0, 1f, 1f, 0.52f, 0.56f, 0.62f, 1f)
                    val bars = 11
                    for (b in 0 until bars) {
                        val bx = c.x - c.w * 0.48f + (b.toFloat() / (bars - 1)) * (c.w * 0.96f)
                        drawMesh(MeshLibrary.cylinder, bx, 0.56f, c.z, 0.045f, 0.96f, 0.045f, 0f, 0, 1f, 1f, 0.58f, 0.62f, 0.68f, 1f)
                    }
                }
                7 -> {
                    drawMesh(MeshLibrary.cylinder, c.x, 0.58f, c.z, 0.72f, 1.15f, 0.72f, 0f, 0, 1f, 1f, 0.88f, 0.18f, 0.14f, 1f)
                    drawMesh(MeshLibrary.cylinder, c.x, 0.58f, c.z, 0.75f, 0.24f, 0.75f, 0f, 0, 1f, 1f, 0.94f, 0.88f, 0.82f, 1f)
                }
                else -> {
                    drawMesh(MeshLibrary.box, c.x, c.h * 0.5f, c.z, c.w, c.h, c.d, 0f, texCrate, 1f, 1f, 0.88f, 0.90f, 0.92f, 1f)
                }
            }
        }
    }

    private fun renderEnemyEntity(z: GameEngine.Zombie3D) {
        val s = z.scale
        val flash = z.hitFlash > 0f

        drawMesh(MeshLibrary.disc, z.x, 0.02f, z.z, 1.35f * s, 1f, 1.35f * s, 0f, texShadow, 1f, 1f, 1f, 1f, 1f, 0.82f)

        // 1. Quad-Leg Hellhound Zombie Dog
        if (z.type == 6) {
            val legSwing = sin(z.animPhase) * 38f
            val bodyBounce = kotlin.math.abs(sin(z.animPhase)) * 0.06f * s
            val headBob = cos(z.animPhase) * 10f
            val tailWag = sin(z.animPhase * 1.5f) * 28f
            val dr = if (flash) 1.0f else 0.28f
            val dg = if (flash) 0.35f else 0.20f
            val db = if (flash) 0.35f else 0.18f

            drawPart(MeshLibrary.humanLimbMesh, z.x, z.z, z.yaw, 0f, 0.44f * s + bodyBounce, 0f, 90f, 0f, 0.95f * s, 0.82f * s, 1.05f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.cylinder, z.x, z.z, z.yaw, 0f, 0.56f * s + bodyBounce, 0.32f * s, 42f + headBob * 0.5f, 0f, 0.18f * s, 0.28f * s, 0.20f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.sphere, z.x, z.z, z.yaw, 0f, 0.68f * s + bodyBounce, 0.45f * s, headBob, 0f, 0.26f * s, 0.24f * s, 0.30f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.box, z.x, z.z, z.yaw, 0f, 0.64f * s + bodyBounce, 0.62f * s, headBob, 0f, 0.14f * s, 0.12f * s, 0.22f * s, texZombie, dr * 0.85f, dg * 0.85f, db * 0.85f)
            drawPart(MeshLibrary.sphere, z.x, z.z, z.yaw, -0.07f * s, 0.71f * s + bodyBounce, 0.56f * s, 0f, 0f, 0.04f * s, 0.04f * s, 0.04f * s, 0, 1.0f, 0.15f, 0.10f)
            drawPart(MeshLibrary.sphere, z.x, z.z, z.yaw,  0.07f * s, 0.71f * s + bodyBounce, 0.56f * s, 0f, 0f, 0.04f * s, 0.04f * s, 0.04f * s, 0, 1.0f, 0.15f, 0.10f)
            drawPart(MeshLibrary.box, z.x, z.z, z.yaw, -0.09f * s, 0.81f * s + bodyBounce, 0.42f * s, -12f, 0f, 0.05f * s, 0.14f * s, 0.06f * s, 0, dr, dg, db)
            drawPart(MeshLibrary.box, z.x, z.z, z.yaw,  0.09f * s, 0.81f * s + bodyBounce, 0.42f * s, -12f, 0f, 0.05f * s, 0.14f * s, 0.06f * s, 0, dr, dg, db)
            drawPart(MeshLibrary.cylinder, z.x, z.z, z.yaw, 0f, 0.52f * s + bodyBounce, -0.44f * s, -55f, tailWag, 0.05f * s, 0.30f * s, 0.05f * s, 0, dr, dg, db)
            drawPart(MeshLibrary.humanLimbMesh, z.x, z.z, z.yaw, -0.14f * s, 0.22f * s,  0.26f * s,  legSwing, 0f, 0.38f * s, 0.44f * s, 0.38f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.humanLimbMesh, z.x, z.z, z.yaw,  0.14f * s, 0.22f * s,  0.26f * s, -legSwing, 0f, 0.38f * s, 0.44f * s, 0.38f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.humanLimbMesh, z.x, z.z, z.yaw, -0.14f * s, 0.22f * s, -0.26f * s, -legSwing, 0f, 0.42f * s, 0.44f * s, 0.42f * s, texZombie, dr, dg, db)
            drawPart(MeshLibrary.humanLimbMesh, z.x, z.z, z.yaw,  0.14f * s, 0.22f * s, -0.26f * s,  legSwing, 0f, 0.42f * s, 0.44f * s, 0.42f * s, texZombie, dr, dg, db)
            return
        }

        // 2. Native OpenGL ES 2.0 18-Bone Rigged Skeletal Zombies (zombie_01..zombie_05)
        if (riggedZombieModels.isNotEmpty()) {
            val modelIdx = when (z.type) {
                3 -> 4 // zombie_05 for Boss
                2 -> 3 // zombie_04 for Heavy Brute
                1 -> (z.gaitStyle % 2) + 1 // zombie_02 or zombie_03
                else -> z.gaitStyle % riggedZombieModels.size
            }.coerceIn(0, riggedZombieModels.size - 1)

            val clipName = if (z.isAttacking) "attack" else "walk"
            val (zr, zg, zb) = when {
                flash -> Triple(1.0f, 0.32f, 0.32f)
                z.type == 3 -> Triple(0.95f, 0.65f, 0.65f)
                z.type == 2 -> Triple(0.92f, 0.85f, 0.78f)
                z.type == 1 -> Triple(0.84f, 1.0f, 0.86f)
                else -> Triple(1.0f, 1.0f, 1.0f)
            }
            riggedZombieModels[modelIdx].draw(
                vpMatrix = vpMatrix,
                x = z.x, y = 0f, z = z.z,
                yawDeg = z.yaw,
                scale = s,
                clipName = clipName,
                animTime = z.animPhase * 0.16f,
                texturesByMaterial = zombieMaterialTextures,
                fallbackTexId = texZombie,
                tintR = zr, tintG = zg, tintB = zb,
                camX = lastCamX, camY = lastCamY, camZ = lastCamZ,
                fogR = lastFogR, fogG = lastFogG, fogB = lastFogB
            )
            GLES20.glUseProgram(program)
            return
        }

        // Fallback: Heavy / Boss Mutant using Hunyuan3D Sculpted Mesh (hunyuan_mutant.obj)
        if ((z.type == 2 || z.type == 3) && hunyuanMutantMesh != null) {
            val stride = kotlin.math.abs(sin(z.animPhase)) * 0.05f * s
            val swayRoll = sin(z.animPhase) * 4.5f
            val (tr, tg, tb) = when {
                flash -> Triple(1.0f, 0.35f, 0.35f)
                z.type == 3 -> Triple(0.86f, 0.56f, 0.66f)
                else -> Triple(0.95f, 0.76f, 0.68f)
            }
            drawPart(hunyuanMutantMesh!!, z.x, z.z, z.yaw, 0f, stride, 0f, 5f, swayRoll, s * 1.15f, s, s * 1.15f, texZombie, tr, tg, tb)
            return
        }

        // 3. Realistic Clothed Lurcher Zombie (ga024_lurcher.glb) with baked skeletal Walk & Attack animation + ga024_atlas.png!
        val meshToDraw = if (z.isAttacking && lurcherAttackFrames.isNotEmpty()) {
            val aIdx = ((z.animPhase * 0.5f).toInt().mod(lurcherAttackFrames.size))
            lurcherAttackFrames[aIdx]
        } else if (lurcherWalkFrames.isNotEmpty()) {
            val wIdx = ((z.animPhase * 0.85f).toInt().mod(lurcherWalkFrames.size))
            lurcherWalkFrames[wIdx]
        } else {
            hunyuanMutantMesh
        }

        if (meshToDraw != null) {
            val (lr, lg, lb) = when {
                flash -> Triple(1.0f, 0.35f, 0.35f)
                z.type == 1 -> Triple(0.85f, 1.0f, 0.88f) // Infected pale-green variant
                else -> Triple(1.0f, 1.0f, 1.0f)        // Original Workwear Lurcher Atlas
            }
            drawPart(meshToDraw, z.x, z.z, z.yaw, 0f, 0f, 0f, 0f, 0f, s * 1.08f, s * 1.08f, s * 1.08f, texLurcherAtlas, lr, lg, lb)
        }
    }

    private fun drawPart(
        mesh: MeshLibrary.Mesh,
        cx: Float, cz: Float, yawDeg: Float,
        lx: Float, ly: Float, lz: Float,
        pitchDeg: Float, rollOrWagDeg: Float,
        sx: Float, sy: Float, sz: Float,
        texId: Int, r: Float, g: Float, b: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cx, 0f, cz)
        Matrix.rotateM(modelMatrix, 0, yawDeg, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, lx, ly, lz)
        if (rollOrWagDeg != 0f) Matrix.rotateM(modelMatrix, 0, rollOrWagDeg, 0f, 1f, 0f)
        if (pitchDeg != 0f) Matrix.rotateM(modelMatrix, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        submitMesh(mesh, texId, 1f, 1f, r, g, b, 1f)
    }

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

    private fun submitMesh(
        mesh: MeshLibrary.Mesh,
        texId: Int, uvX: Float, uvY: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        mesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, mesh.strideBytes, mesh.buffer)

        mesh.buffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, mesh.strideBytes, mesh.buffer)

        mesh.buffer.position(6)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, mesh.strideBytes, mesh.buffer)

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

    private fun compileShader(type: Int, code: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, code)
        GLES20.glCompileShader(id)
        return id
    }
}
