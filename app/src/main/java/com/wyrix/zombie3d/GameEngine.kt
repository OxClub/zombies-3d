package com.wyrix.zombie3d

import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameEngine(context: Context, private val sfx: SoundEngine) {

    enum class State { INTRO, MAP, PLAYING, STAGE_CLEAR, GAME_OVER }

    class GunSpec(
        val id: String,
        val name: String,
        val damage: Float,
        val fireRate: Float,
        val magSize: Int,
        val unlockRoom: Int,
        val price: Int,
        val r: Float,
        val g: Float,
        val b: Float,
        val lengthScale: Float,
        var unlocked: Boolean
    )

    class HeroSpec(
        val id: String,
        val name: String,
        val role: String,
        val maxHp: Float,
        val moveSpeed: Float,
        val armorR: Float,
        val armorG: Float,
        val armorB: Float,
        val price: Int,
        var unlocked: Boolean
    )

    class Bullet3D(
        var x: Float,
        var y: Float,
        var z: Float,
        val vx: Float,
        val vz: Float,
        val yaw: Float,
        var life: Float,
        val damage: Float,
        val r: Float,
        val g: Float,
        val b: Float
    )

    class Zombie3D(
        var x: Float,
        var z: Float,
        var hp: Float,
        val maxHp: Float,
        val speed: Float,
        val scale: Float,
        val type: Int,
        val gaitStyle: Int = 0,
        var yaw: Float = 0f,
        var animPhase: Float = Random.nextFloat() * 6.28f,
        var hitFlash: Float = 0f,
        var isAttacking: Boolean = false
    )

    class Corpse3D(
        val x: Float,
        val z: Float,
        val yaw: Float,
        val type: Int,
        val gait: Int,
        val scale: Float,
        var age: Float = 0f
    )

    class DamagePopup(
        val amount: Int,
        val isHeadshot: Boolean,
        val screenOffsetX: Float,
        var life: Float = 0.85f
    )

    class Particle3D(
        var x: Float,
        var y: Float,
        var z: Float,
        var vx: Float,
        var vy: Float,
        var vz: Float,
        var life: Float,
        val size: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val rotDeg: Float = Random.nextFloat() * 360f
    )

    class BloodDecal3D(val x: Float, val z: Float, val radius: Float, val rotDeg: Float)
    class Crate3D(val x: Float, val z: Float, val w: Float, val h: Float, val d: Float, val kind: Int, val colorIdx: Int = 0)

    private val prefs = context.getSharedPreferences("wyrix_zombie_3d", Context.MODE_PRIVATE)
    var cash = prefs.getInt("cash", 600)
    var unlockedStage = prefs.getInt("unlocked_stage", 1)
    var selectedStage = unlockedStage
    var mapPageOffset = ((selectedStage - 1) / 20) * 20
    var selectedTab = 2 // 0=STORE, 1=HEROES, 2=MAP, 3=GUNS

    val totalRoomsCount = 1200

    val guns = listOf(
        GunSpec("m1911", "M1911 TACTICAL", 52f, 0.22f, 8, 1, 0, 0.34f, 0.38f, 0.44f, 0.72f, true),
        GunSpec("mp5", "MP5 NAVY SMG", 38f, 0.10f, 25, 2, 350, 0.22f, 0.26f, 0.32f, 0.90f, prefs.getBoolean("gun_mp5", false)),
        GunSpec("m4a1", "M4A1 CARBINE", 56f, 0.12f, 30, 3, 700, 0.48f, 0.42f, 0.30f, 1.05f, prefs.getBoolean("gun_m4a1", false)),
        GunSpec("scar", "SCAR-H HEAVY", 78f, 0.15f, 20, 5, 1200, 0.62f, 0.52f, 0.34f, 1.12f, prefs.getBoolean("gun_scar", false)),
        GunSpec("awm", "AWM SNIPER", 185f, 0.42f, 6, 8, 2000, 0.24f, 0.36f, 0.26f, 1.28f, prefs.getBoolean("gun_awm", false))
    )

    val heroes = listOf(
        HeroSpec("vikram", "CMDR. VIKRAM", "ASSAULT RECON", 110f, 4.4f, 0.24f, 0.32f, 0.42f, 0, true),
        HeroSpec("ananya", "CAPT. ANANYA", "BIO-MEDIC", 150f, 5.0f, 0.62f, 0.20f, 0.24f, 500, prefs.getBoolean("hero_ananya", false)),
        HeroSpec("kabir", "SGT. KABIR", "HEAVY BREACHER", 220f, 4.0f, 0.26f, 0.34f, 0.24f, 1200, prefs.getBoolean("hero_kabir", false))
    )

    var equippedGunIndex = prefs.getInt("equipped_gun", 0).coerceIn(0, guns.size - 1)
    var equippedHeroIndex = prefs.getInt("equipped_hero", 0).coerceIn(0, heroes.size - 1)

    @Volatile var state = State.INTRO
    var worldTime = 0f

    var playerX = 0f
    var playerZ = 12f
    var playerYaw = 180f
    var playerPitch = 0f
    var walkCycle = 0f
    var playerStrafeVel = 0f
    var recoilKick = 0f
    var muzzleFlashTimer = 0f
    var isScoped = false
    var isTppMode = true // TPP ON by default so player body & gun are clearly visible from above/behind!

    // Virtual joystick axes: x = strafe right (+), y = forward (+). Range -1..1.
    @Volatile var playerIsMoving = false
    @Volatile var moveAxisX = 0f
    @Volatile var moveAxisY = 0f
    @Volatile var shotCounter = 0
    val corpses = ArrayList<Corpse3D>()

    // Distance from the player centre to the muzzle of each gun (index matches `guns`).
    private val gunMuzzleFwd = floatArrayOf(0.56f, 0.86f, 1.14f, 1.16f, 1.49f)

    @Volatile var moveForwardPressed = false
    @Volatile var moveBackwardPressed = false

    var maxHp = 110f
    var hp = 110f
    var kills = 0

    var maxAmmo = 8
    var currentAmmo = 8
    var reloadTimer = 0f

    var activeRoomNumber = 1
    var roomHalfWidth = 6.2f
    var roomKillsTarget = 6
    var roomRemainingToClear = 6
    var lastStageReward = 300
    var lastUnlockedGunName: String? = null

    var headshotBannerTimer = 0f
    var hitMarkerTimer = 0f
    var shakeTimer = 0f
    @Volatile var isFiring = false
    private var fireCooldown = 0f
    private var toSpawn = 0
    private var spawnTimer = 0f

    val arenaHalf = 40f
    val crates = ArrayList<Crate3D>()
    val bullets = ArrayList<Bullet3D>()
    val zombies = ArrayList<Zombie3D>()
    val particles = ArrayList<Particle3D>()
    val bloodDecals = ArrayList<BloodDecal3D>()
    val damagePopups = ArrayList<DamagePopup>()

    init {
        syncEquipmentStats()
        buildProceduralRoomLayout(1)
    }

    fun syncEquipmentStats() {
        val h = heroes[equippedHeroIndex]
        maxHp = h.maxHp
        val g = guns[equippedGunIndex]
        maxAmmo = g.magSize
        currentAmmo = maxAmmo
    }

    fun selectOrBuyGun(idx: Int) {
        if (idx !in guns.indices) return
        val g = guns[idx]
        if (g.unlocked) {
            equippedGunIndex = idx
            syncEquipmentStats()
            saveProgress()
            sfx.playPickup()
        } else if (cash >= g.price) {
            cash -= g.price
            g.unlocked = true
            equippedGunIndex = idx
            syncEquipmentStats()
            saveProgress()
            sfx.playPickup()
        }
    }

    fun selectOrBuyHero(idx: Int) {
        if (idx !in heroes.indices) return
        val h = heroes[idx]
        if (h.unlocked) {
            equippedHeroIndex = idx
            syncEquipmentStats()
            saveProgress()
            sfx.playPickup()
        } else if (cash >= h.price) {
            cash -= h.price
            h.unlocked = true
            equippedHeroIndex = idx
            syncEquipmentStats()
            saveProgress()
            sfx.playPickup()
        }
    }

    fun buyStoreItem(itemIndex: Int) {
        when (itemIndex) {
            0 -> if (cash >= 200) { cash -= 200; maxHp += 25f; hp = maxHp; sfx.playPickup() }
            1 -> if (cash >= 300) { cash -= 300; guns.forEach { it.unlocked = true }; sfx.playPickup() }
            2 -> if (cash >= 250) { cash -= 250; heroes.forEach { it.unlocked = true }; sfx.playPickup() }
            3 -> { cash += 500; sfx.playPickup() } // Free Supply Drop
        }
        saveProgress()
    }

    fun cycleUnlockedWeaponInCombat() {
        val unlockedIndices = guns.indices.filter { guns[it].unlocked }
        if (unlockedIndices.isEmpty()) return
        val curPos = unlockedIndices.indexOf(equippedGunIndex)
        equippedGunIndex = unlockedIndices[(curPos + 1) % unlockedIndices.size]
        syncEquipmentStats()
        reloadTimer = 0f
        sfx.playPickup()
    }

    fun toggleCameraPerspective() {
        isTppMode = !isTppMode
        sfx.playPickup()
    }

    private fun saveProgress() {
        val ed = prefs.edit()
            .putInt("cash", cash)
            .putInt("unlocked_stage", unlockedStage)
            .putInt("equipped_gun", equippedGunIndex)
            .putInt("equipped_hero", equippedHeroIndex)
        for (g in guns) ed.putBoolean("gun_${g.id}", g.unlocked)
        for (h in heroes) ed.putBoolean("hero_${h.id}", h.unlocked)
        ed.apply()
    }

    fun roomThemeTitle(room: Int): String {
        val sectors = arrayOf(
            "HOSPITAL LOBBY", "EMERGENCY WARD", "QUARANTINE CELL", "SUBWAY PLATFORM",
            "METRO TUNNEL", "POLICE ARMORY", "CELL BLOCK B", "BIO-LAB SECTOR",
            "CRYO STORAGE", "REACTOR HALL", "EXTRACTION DECK", "FINAL BUNKER"
        )
        return sectors[(room - 1) % sectors.size]
    }

    // Procedural abandoned small-town street. Deterministic per stage so the layout stays stable.
    private fun buildProceduralRoomLayout(room: Int) {
        crates.clear()
        val rng = java.util.Random(room.toLong() * 48271L)
        roomHalfWidth = 18f

        // Ruined storefronts / houses line both sides; central roadway remains open for combat.
        for (side in listOf(-1f, 1f)) {
            for (i in 0..2) {
                val z = -12f + i * 12f + (rng.nextFloat() - 0.5f) * 2f
                val x = side * (12.8f + rng.nextFloat() * 1.4f)
                crates.add(Crate3D(x, z, 5.8f, 5.4f + rng.nextFloat() * 1.4f, 7.2f, 9, i))
                // Wrecked cars parked between the road and buildings.
                if (i != 1 || room % 2 == 0) {
                    val carZ = z + (rng.nextFloat() - 0.5f) * 5f
                    crates.add(Crate3D(side * (7.4f + rng.nextFloat() * 0.6f), carZ, 2.35f, 1.35f, 4.5f, 10, rng.nextInt(4)))
                }
                // Dead trees and overgrown roadside vegetation.
                val treeZ = z + (rng.nextFloat() - 0.5f) * 5f
                crates.add(Crate3D(side * (9.8f + rng.nextFloat()), treeZ, 1.15f, 4.4f, 1.15f, 11, rng.nextInt(3)))
            }
        }

        // Scattered debris / cover in the road, never blocking the whole street.
        val debrisCount = 3 + (room % 3)
        for (i in 0 until debrisCount) {
            val x = (rng.nextFloat() * 2f - 1f) * 4.1f
            val z = -13f + rng.nextFloat() * 26f
            crates.add(Crate3D(x, z, 0.8f + rng.nextFloat() * 0.8f, 0.45f + rng.nextFloat() * 0.5f, 0.8f + rng.nextFloat(), 0, 0))
        }
    }

    fun skipIntroToMap() {
        state = State.MAP
        sfx.playPickup()
    }

    fun toggleScopeZoom() {
        isScoped = !isScoped
        sfx.playPickup()
    }

    fun triggerReload() {
        if (reloadTimer <= 0f && currentAmmo < maxAmmo) {
            reloadTimer = 0.95f
            sfx.playReload()
        }
    }

    fun applyAimDrag(dxNorm: Float, dyNorm: Float) {
        val sens = if (isScoped) 72f else 135f
        playerYaw -= dxNorm * sens
        playerPitch = (playerPitch - dyNorm * (sens * 0.65f)).coerceIn(-24f, 24f)
    }

    fun deploySelectedStage() {
        bullets.clear()
        zombies.clear()
        particles.clear()
        bloodDecals.clear()
        damagePopups.clear()

        bloodDecals.add(BloodDecal3D(-1.2f, -2.5f, 1.5f, 20f))
        bloodDecals.add(BloodDecal3D(1.8f, 1.5f, 1.4f, 110f))

        activeRoomNumber = selectedStage.coerceIn(1, totalRoomsCount)
        buildProceduralRoomLayout(activeRoomNumber)

        syncEquipmentStats()
        playerX = 0f
        playerZ = 12.2f
        playerYaw = 180f
        playerPitch = 0f
        playerStrafeVel = 0f
        moveForwardPressed = false
        moveBackwardPressed = false
        moveAxisX = 0f
        moveAxisY = 0f
        corpses.clear()
        hp = maxHp
        reloadTimer = 0f
        isFiring = false
        headshotBannerTimer = 0f
        hitMarkerTimer = 0f

        roomKillsTarget = (8 + (activeRoomNumber / 3)).coerceAtMost(28)
        roomRemainingToClear = roomKillsTarget
        toSpawn = roomKillsTarget
        spawnTimer = 0.15f

        state = State.PLAYING
        sfx.playHorrorGroan(false)
    }

    fun claimStageClearReward(tripleBonus: Boolean) {
        val gain = if (tripleBonus) lastStageReward * 3 else lastStageReward
        cash += gain

        // Permanently unlock all guns whose unlockRoom <= activeRoomNumber!
        lastUnlockedGunName = null
        for (i in guns.indices) {
            if (!guns[i].unlocked && activeRoomNumber >= guns[i].unlockRoom) {
                guns[i].unlocked = true
                equippedGunIndex = i
                lastUnlockedGunName = guns[i].name
            }
        }
        syncEquipmentStats()

        if (selectedStage < totalRoomsCount) {
            selectedStage++
            if (selectedStage > unlockedStage) unlockedStage = selectedStage
            mapPageOffset = ((selectedStage - 1) / 20) * 20
        }
        saveProgress()
        state = State.MAP
        sfx.playPickup()
    }

    fun returnToMapFromGameOver() {
        moveAxisX = 0f
        moveAxisY = 0f
        moveForwardPressed = false
        moveBackwardPressed = false
        state = State.MAP
    }

    fun relativeAngleToZombie(z: Zombie3D): Float {
        val dx = z.x - playerX
        val dz = z.z - playerZ
        val targetYaw = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        var diff = (targetYaw - playerYaw) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    // Solid AABB Circle-vs-Box collision solver so entities slide smoothly around pillars, crates & walls!
    private fun resolveSolidCollisions(x: Float, z: Float, radius: Float): Pair<Float, Float> {
        var nx = x.coerceIn(-roomHalfWidth + 0.45f + radius, roomHalfWidth - 0.45f - radius)
        var nz = z.coerceIn(-17.2f + radius, 17.2f - radius)

        for (c in crates) {
            if (c.kind == 4 && kotlin.math.abs(c.x) >= roomHalfWidth - 0.1f) continue // outer walls already clamped
            val minX = c.x - c.w * 0.5f
            val maxX = c.x + c.w * 0.5f
            val minZ = c.z - c.d * 0.5f
            val maxZ = c.z + c.d * 0.5f

            val closestX = nx.coerceIn(minX, maxX)
            val closestZ = nz.coerceIn(minZ, maxZ)
            val dx = nx - closestX
            val dz = nz - closestZ
            val distSq = dx * dx + dz * dz
            if (distSq < radius * radius) {
                if (distSq > 0.00001f) {
                    val dist = kotlin.math.sqrt(distSq)
                    val push = radius - dist
                    nx += (dx / dist) * push
                    nz += (dz / dist) * push
                } else {
                    // Center inside box fallback
                    nz += radius
                }
            }
        }
        return nx to nz
    }

    @Synchronized
    fun update(dt: Float) {
        worldTime += dt
        if (shakeTimer > 0f) shakeTimer = max(0f, shakeTimer - dt)
        if (recoilKick > 0f) recoilKick = max(0f, recoilKick - dt * 4.8f)
        if (muzzleFlashTimer > 0f) muzzleFlashTimer = max(0f, muzzleFlashTimer - dt)
        if (headshotBannerTimer > 0f) headshotBannerTimer = max(0f, headshotBannerTimer - dt)
        if (hitMarkerTimer > 0f) hitMarkerTimer = max(0f, hitMarkerTimer - dt)
        updateParticles(dt)

        val popIter = damagePopups.iterator()
        while (popIter.hasNext()) {
            val dp = popIter.next()
            dp.life -= dt
            if (dp.life <= 0f) popIter.remove()
        }

        val cIter = corpses.iterator()
        while (cIter.hasNext()) {
            val cp = cIter.next()
            cp.age += dt
            if (cp.age > 9f) cIter.remove()
        }

        if (state != State.PLAYING) return

        if (fireCooldown > 0f) fireCooldown -= dt
        if (reloadTimer > 0f) {
            reloadTimer -= dt
            if (reloadTimer <= 0f) {
                currentAmmo = maxAmmo
            }
        }

        // Player Forward / Backward / Strafe locomotion!
        val heroSpeed = heroes[equippedHeroIndex].moveSpeed
        val yawRad = Math.toRadians(playerYaw.toDouble())
        val fwdX = sin(yawRad).toFloat()
        val fwdZ = cos(yawRad).toFloat()
        val rightX = cos(yawRad).toFloat()
        val rightZ = -sin(yawRad).toFloat()

        // True screen-right direction (forward x up) for a camera looking along `fwd`.
        val trueRightX = -cos(yawRad).toFloat()
        val trueRightZ = sin(yawRad).toFloat()
        var ax = moveAxisX
        var ay = moveAxisY
        val axLen = hypot(ax, ay)
        if (axLen > 1f) { ax /= axLen; ay /= axLen }
        var isMoving = false
        if (axLen > 0.08f) {
            playerX += (fwdX * ay + trueRightX * ax) * heroSpeed * dt
            playerZ += (fwdZ * ay + trueRightZ * ax) * heroSpeed * dt
            isMoving = true
        }
        playerIsMoving = isMoving

        val resolvedPlayer = resolveSolidCollisions(playerX, playerZ, 0.44f)
        playerX = resolvedPlayer.first
        playerZ = resolvedPlayer.second

        walkCycle += dt * (if (isMoving) 13.5f else 2.2f)

        if (isFiring && fireCooldown <= 0f && reloadTimer <= 0f) {
            if (currentAmmo > 0) {
                fireWeapon()
            } else {
                triggerReload()
            }
        }

        if (toSpawn > 0) {
            spawnTimer -= dt
            if (spawnTimer <= 0f && zombies.size < 7) {
                spawnZombieInRoom()
                toSpawn--
                spawnTimer = max(0.45f, 1.25f - (activeRoomNumber % 20) * 0.03f)
            }
        } else if (zombies.isEmpty()) {
            lastStageReward = 250 + activeRoomNumber * 35
            state = State.STAGE_CLEAR
            sfx.playPickup()
            return
        }

        updateBullets(dt)
        updateZombies(dt)

        if (hp <= 0f) {
            hp = 0f
            state = State.GAME_OVER
            sfx.playExplosion()
        }
    }

    private fun fireWeapon() {
        val g = guns[equippedGunIndex]
        currentAmmo--
        if (currentAmmo == 0) {
            reloadTimer = 0.95f
            sfx.playReload()
        }

        val rad = Math.toRadians(playerYaw.toDouble())
        val fx = sin(rad).toFloat()
        val fz = cos(rad).toFloat()
        val fwdLen = gunMuzzleFwd[equippedGunIndex.coerceIn(0, gunMuzzleFwd.size - 1)]
        // gun is held in the right hand, 0.13 m to the true right of the body centre
        val muzzleX = playerX + fx * fwdLen - cos(rad).toFloat() * 0.13f
        val muzzleZ = playerZ + fz * fwdLen + sin(rad).toFloat() * 0.13f

        recoilKick = 0.25f
        muzzleFlashTimer = 0.07f
        shotCounter++
        fireCooldown = g.fireRate
        sfx.playShot(equippedGunIndex)

        val spread = if (equippedGunIndex == 1) (Random.nextFloat() - 0.5) * 0.04 else 0.0
        val a = rad + spread
        bullets.add(
            Bullet3D(
                muzzleX, 1.32f, muzzleZ,
                sin(a).toFloat() * 52f, cos(a).toFloat() * 52f,
                Math.toDegrees(a).toFloat(),
                1.0f, g.damage, 1.0f, 0.90f, 0.55f
            )
        )
    }

    private fun spawnZombieInRoom() {
        val x = (Random.nextFloat() * 2f - 1f) * 5.8f
        val z = -10.5f - Random.nextFloat() * 6.5f
        val roll = Random.nextFloat()
        val type = when {
            activeRoomNumber % 5 == 0 && toSpawn == 1 -> 3 // Mutant Boss
            activeRoomNumber % 3 == 0 && toSpawn == 1 -> 2 // Heavy Brute
            roll < 0.22f -> 6 // Hellhound Dog
            roll < 0.58f -> 0 // Lurcher Clothed Zombie (ga024_lurcher.glb!)
            else -> 1         // Lurcher Infected Variant
        }
        val gait = Random.nextInt(4)
        val hpScale = 1f + (activeRoomNumber - 1) * 0.05f
        val vary = 0.96f + Random.nextFloat() * 0.10f
        zombies.add(
            when (type) {
                0 -> Zombie3D(x, z, 65f * hpScale, 65f * hpScale, 1.80f, 1.00f * vary, 0, gait)
                1 -> Zombie3D(x, z, 72f * hpScale, 72f * hpScale, 2.15f, 1.00f * vary, 1, 2)
                2 -> Zombie3D(x, z, 195f * hpScale, 195f * hpScale, 1.50f, 1.00f, 2, 3)
                3 -> Zombie3D(x, z, 260f * hpScale, 260f * hpScale, 1.65f, 1.28f, 3, 3)
                else -> Zombie3D(x, z, 52f * hpScale, 52f * hpScale, 2.45f, 1.15f, 6, 2)
            }
        )
    }

    private fun updateBullets(dt: Float) {
        for (b in bullets) {
            b.x += b.vx * dt
            b.z += b.vz * dt
            b.life -= dt

            val crateIter = crates.iterator()
            while (crateIter.hasNext()) {
                val c = crateIter.next()
                if (c.kind == 8) continue
                if (b.x > c.x - c.w * 0.5f && b.x < c.x + c.w * 0.5f && b.z > c.z - c.d * 0.5f && b.z < c.z + c.d * 0.5f) {
                    b.life = 0f
                    if (c.kind == 7) {
                        sfx.playExplosion()
                        shakeTimer = 0.40f
                        spawnBurst(c.x, 0.8f, c.z, 24, 1.0f, 0.45f, 0.10f)
                        for (z in zombies) {
                            if (hypot(z.x - c.x, z.z - c.z) < 5.5f) {
                                z.hp -= 220f
                                z.hitFlash = 0.14f
                            }
                        }
                        crateIter.remove()
                    }
                    break
                }
            }
            if (b.life <= 0f) continue

            for (z in zombies) {
                if (z.hp <= 0f) continue
                val hitR = 0.72f * z.scale
                if (hypot(z.x - b.x, z.z - b.z) < hitR) {
                    val isHeadshot = playerPitch > 1.8f || z.type == 6
                    val finalDmg = if (isHeadshot) b.damage * 1.85f else b.damage
                    z.hp -= finalDmg
                    z.hitFlash = 0.10f
                    b.life = 0f
                    hitMarkerTimer = 0.14f
                    if (isHeadshot) {
                        headshotBannerTimer = 0.95f
                    }
                    damagePopups.add(
                        DamagePopup(
                            amount = finalDmg.toInt(),
                            isHeadshot = isHeadshot,
                            screenOffsetX = (Random.nextFloat() - 0.5f) * 32f
                        )
                    )
                    sfx.playHit()
                    spawnBloodSpray(b.x, if (z.type == 6) 0.52f else 1.35f, b.z, b.vx * 0.10f, b.vz * 0.10f, 10)
                    break
                }
            }
        }
        bullets.removeAll { it.life <= 0f }
        cleanDeadZombies()
    }

    private fun cleanDeadZombies() {
        val iter = zombies.iterator()
        while (iter.hasNext()) {
            val z = iter.next()
            if (z.hp <= 0f) {
                kills++
                roomRemainingToClear = max(0, roomRemainingToClear - 1)
                if (corpses.size > 8) corpses.removeAt(0)
                corpses.add(Corpse3D(z.x, z.z, z.yaw, z.type, z.gaitStyle, z.scale))
                if (bloodDecals.size > 45) bloodDecals.removeAt(0)
                bloodDecals.add(BloodDecal3D(z.x, z.z, 1.65f * z.scale, Random.nextFloat() * 360f))
                spawnBloodSpray(z.x, 0.9f, z.z, 0f, 0f, 16)
                iter.remove()
            }
        }
    }

    private fun updateZombies(dt: Float) {
        for (i in zombies.indices) {
            val z = zombies[i]
            val dx = playerX - z.x
            val dz = playerZ - z.z
            val dist = max(0.01f, hypot(dx, dz))
            val minStop = 1.65f
            z.isAttacking = dist <= minStop + 0.45f

            if (dist > minStop) {
                val gaitMult = when (z.gaitStyle) {
                    1 -> 0.72f + 0.55f * max(0f, sin(z.animPhase))
                    2 -> 1.30f
                    else -> 1.0f
                }
                z.x += (dx / dist) * z.speed * gaitMult * dt
                z.z += (dz / dist) * z.speed * gaitMult * dt
            }

            // Zombie-to-zombie separation so they don't stack inside each other
            for (j in zombies.indices) {
                if (i == j) continue
                val other = zombies[j]
                val sdx = z.x - other.x
                val sdz = z.z - other.z
                val sdist = hypot(sdx, sdz)
                if (sdist in 0.001f..0.85f) {
                    val push = (0.85f - sdist) * 0.5f
                    z.x += (sdx / sdist) * push
                    z.z += (sdz / sdist) * push
                }
            }

            // Enforce solid obstacle & wall collision so zombies NEVER walk through objects!
            val resolvedZ = resolveSolidCollisions(z.x, z.z, 0.48f * z.scale)
            z.x = resolvedZ.first
            z.z = resolvedZ.second

            z.yaw = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            z.animPhase += dt * (if (z.type == 6) 15f else if (z.gaitStyle == 2) 10.5f else 7.5f)
            if (z.hitFlash > 0f) z.hitFlash -= dt

            if (z.isAttacking) {
                hp -= 12f * dt
                shakeTimer = max(shakeTimer, 0.08f)
                if (Random.nextFloat() < dt * 2.0f) sfx.playHurt()
            }
        }
    }

    private fun spawnBloodSpray(x: Float, y: Float, z: Float, impVx: Float, impVz: Float, count: Int) {
        if (particles.size > 140) return
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.28f
            val spd = 1.4f + Random.nextFloat() * 3.8f
            particles.add(
                Particle3D(
                    x, y, z,
                    cos(a) * spd + impVx, 1.2f + Random.nextFloat() * 3.2f, sin(a) * spd + impVz,
                    0.34f + Random.nextFloat() * 0.22f,
                    0.07f + Random.nextFloat() * 0.06f,
                    0.68f, 0.03f, 0.04f
                )
            )
        }
    }

    private fun spawnBurst(x: Float, y: Float, z: Float, count: Int, r: Float, g: Float, b: Float) {
        if (particles.size > 140) return
        for (i in 0 until count) {
            val a = Random.nextFloat() * 6.28f
            val spd = 1.6f + Random.nextFloat() * 4.2f
            particles.add(
                Particle3D(
                    x, y, z,
                    cos(a) * spd, 1.5f + Random.nextFloat() * 3.5f, sin(a) * spd,
                    0.32f + Random.nextFloat() * 0.22f,
                    0.08f + Random.nextFloat() * 0.07f,
                    r, g, b
                )
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
            p.vy -= 14f * dt
            p.life -= dt
            if (p.life <= 0f) iter.remove()
        }
    }
}
