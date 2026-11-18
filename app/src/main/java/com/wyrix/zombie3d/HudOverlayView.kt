package com.wyrix.zombie3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.sin

class HudOverlayView(context: Context, private val engine: GameEngine) : View(context) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val cAmber = Color.rgb(245, 178, 48)
    private val cRed = Color.rgb(228, 46, 46)
    private val cNeon = Color.rgb(46, 204, 113)
    private val cCyan = Color.rgb(52, 172, 224)
    private val cWhite = Color.rgb(246, 248, 250)
    private val cDark = Color.rgb(16, 19, 24)

    private var aimPointer = -1
    private var movePointer = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private fun playBtnRect(): RectF {
        val w = width * 0.62f
        val h = 58f * density
        val cx = width * 0.5f
        val cy = height * 0.81f
        return RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
    }

    private fun prevRoomsBtnRect(): RectF = RectF(16f * density, height * 0.695f, 108f * density, height * 0.745f)
    private fun nextRoomsBtnRect(): RectF = RectF(width - 108f * density, height * 0.695f, width - 16f * density, height * 0.745f)

    private fun bonusBtnRect(): RectF {
        val w = width * 0.56f
        val h = 54f * density
        val cx = width * 0.5f
        val cy = height * 0.68f
        return RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
    }

    private fun continueBtnRect(): RectF {
        val w = width * 0.52f
        val h = 48f * density
        val cx = width * 0.5f
        val cy = height * 0.78f
        return RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
    }

    // Left-thumb Forward & Backward movement buttons
    private fun forwardBtnRect(): RectF {
        val r = 28f * density
        val cx = 48f * density
        val cy = height - 168f * density
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    private fun backwardBtnRect(): RectF {
        val r = 28f * density
        val cx = 48f * density
        val cy = height - 102f * density
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    // Right-side Tactical Controls: Reload, Scope, Gun Switch, TPP/FPP Toggle
    private fun reloadBtnRect(): RectF {
        val r = 28f * density
        val cx = width - 46f * density
        val cy = height - 56f * density
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    private fun scopeBtnRect(): RectF {
        val r = 25f * density
        val cx = width - 46f * density
        val cy = height - 120f * density
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    private fun gunSwitchBtnRect(): RectF = RectF(width - 134f * density, 56f * density, width - 12f * density, 88f * density)
    private fun tppToggleBtnRect(): RectF = RectF(width - 134f * density, 94f * density, width - 12f * density, 124f * density)

    private fun tabRect(index: Int): RectF {
        val tabW = width / 4f
        val top = height - 64f * density
        return RectF(index * tabW + 4f * density, top, (index + 1) * tabW - 4f * density, height - 6f * density)
    }

    private fun cardActionRect(index: Int): RectF {
        val top = (110f + index * 96f) * density
        return RectF(width - 126f * density, top + 18f * density, width - 22f * density, top + 64f * density)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val x = e.getX(idx)
        val y = e.getY(idx)
        val pid = e.getPointerId(idx)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                when (engine.state) {
                    GameEngine.State.INTRO -> {
                        engine.skipIntroToMap()
                        return true
                    }
                    GameEngine.State.MAP -> {
                        for (i in 0..3) {
                            if (tabRect(i).contains(x, y)) {
                                engine.selectedTab = i
                                return true
                            }
                        }
                        when (engine.selectedTab) {
                            0 -> { // STORE
                                for (i in 0..3) {
                                    if (cardActionRect(i).contains(x, y)) {
                                        engine.buyStoreItem(i)
                                        return true
                                    }
                                }
                            }
                            1 -> { // HEROES
                                for (i in engine.heroes.indices) {
                                    if (cardActionRect(i).contains(x, y)) {
                                        engine.selectOrBuyHero(i)
                                        return true
                                    }
                                }
                            }
                            3 -> { // GUNS
                                for (i in engine.guns.indices) {
                                    if (cardActionRect(i).contains(x, y)) {
                                        engine.selectOrBuyGun(i)
                                        return true
                                    }
                                }
                            }
                            else -> { // MAP (1..1200 Rooms)
                                if (prevRoomsBtnRect().contains(x, y)) {
                                    engine.mapPageOffset = (engine.mapPageOffset - 20).coerceAtLeast(0)
                                    engine.selectedStage = engine.mapPageOffset + 1
                                    return true
                                }
                                if (nextRoomsBtnRect().contains(x, y)) {
                                    engine.mapPageOffset = (engine.mapPageOffset + 20).coerceAtMost(engine.totalRoomsCount - 20)
                                    engine.selectedStage = engine.mapPageOffset + 1
                                    return true
                                }
                                if (playBtnRect().contains(x, y)) {
                                    engine.deploySelectedStage()
                                    return true
                                }
                                for (slot in 1..20) {
                                    val roomNum = engine.mapPageOffset + slot
                                    val (nx, ny) = stageNodeCoords(slot)
                                    if (hypot(x - nx, y - ny) < 24f * density) {
                                        engine.selectedStage = roomNum
                                        return true
                                    }
                                }
                            }
                        }
                        return true
                    }
                    GameEngine.State.STAGE_CLEAR -> {
                        if (bonusBtnRect().contains(x, y)) {
                            engine.claimStageClearReward(tripleBonus = true)
                            return true
                        }
                        if (continueBtnRect().contains(x, y) || y > height * 0.72f) {
                            engine.claimStageClearReward(tripleBonus = false)
                            return true
                        }
                        return true
                    }
                    GameEngine.State.GAME_OVER -> {
                        engine.returnToMapFromGameOver()
                        return true
                    }
                    GameEngine.State.PLAYING -> {
                        if (forwardBtnRect().contains(x, y)) {
                            movePointer = pid
                            engine.moveForwardPressed = true
                            engine.moveBackwardPressed = false
                            return true
                        }
                        if (backwardBtnRect().contains(x, y)) {
                            movePointer = pid
                            engine.moveBackwardPressed = true
                            engine.moveForwardPressed = false
                            return true
                        }
                        if (reloadBtnRect().contains(x, y)) {
                            engine.triggerReload()
                            return true
                        }
                        if (scopeBtnRect().contains(x, y)) {
                            engine.toggleScopeZoom()
                            return true
                        }
                        if (gunSwitchBtnRect().contains(x, y)) {
                            engine.cycleUnlockedWeaponInCombat()
                            return true
                        }
                        if (tppToggleBtnRect().contains(x, y)) {
                            engine.toggleCameraPerspective()
                            return true
                        }
                        aimPointer = pid
                        lastTouchX = x
                        lastTouchY = y
                        engine.isFiring = true
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (engine.state == GameEngine.State.PLAYING) {
                    if (movePointer != -1) {
                        val mIdx = e.findPointerIndex(movePointer)
                        if (mIdx >= 0) {
                            val mx = e.getX(mIdx)
                            val my = e.getY(mIdx)
                            engine.moveForwardPressed = forwardBtnRect().contains(mx, my) || my < forwardBtnRect().centerY()
                            engine.moveBackwardPressed = backwardBtnRect().contains(mx, my) || my > backwardBtnRect().centerY()
                        }
                    }
                    if (aimPointer != -1) {
                        val aIdx = e.findPointerIndex(aimPointer)
                        if (aIdx >= 0) {
                            val nx = e.getX(aIdx)
                            val ny = e.getY(aIdx)
                            val dx = nx - lastTouchX
                            val dy = ny - lastTouchY
                            lastTouchX = nx
                            lastTouchY = ny
                            engine.applyAimDrag(dx / width, dy / height)
                            engine.isFiring = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pid == movePointer) {
                    movePointer = -1
                    engine.moveForwardPressed = false
                    engine.moveBackwardPressed = false
                }
                if (pid == aimPointer || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    aimPointer = -1
                    engine.isFiring = false
                }
            }
        }
        return true
    }

    private fun stageNodeCoords(slot: Int): Pair<Float, Float> {
        val idx = (slot - 1).coerceIn(0, 19)
        val col = idx % 4
        val row = idx / 4
        val xNorm = 0.17f + col * 0.22f
        val yNorm = 0.61f - row * 0.11f
        return (width * xNorm) to (height * yNorm)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        when (engine.state) {
            GameEngine.State.INTRO -> drawIntroScreen(c)
            GameEngine.State.MAP -> drawMenuHubScreen(c)
            GameEngine.State.PLAYING -> drawPlayingHud(c)
            GameEngine.State.STAGE_CLEAR -> drawCompletedScreen(c)
            GameEngine.State.GAME_OVER -> drawGameOverScreen(c)
        }
    }

    private fun drawIntroScreen(c: Canvas) {
        fill.color = Color.rgb(12, 13, 15)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        text.textAlign = Paint.Align.RIGHT
        text.textSize = 14f * density
        text.color = cWhite
        c.drawText("SKIP", width - 22f * density, 44f * density, text)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 22f * density
        text.color = cRed
        c.drawText("WYRIX: DEAD RAID 3D", width * 0.5f, height * 0.28f, text)

        text.textSize = 15f * density
        text.color = cWhite
        c.drawText("1,200+ INFECTED ROOMS", width * 0.5f, height * 0.46f, text)
        c.drawText("TPP & FPP TACTICAL COMBAT", width * 0.5f, height * 0.51f, text)
        c.drawText("SURVIVE THE LURCHER HORDE...", width * 0.5f, height * 0.56f, text)

        val alpha = ((0.5f + 0.5f * sin(engine.worldTime * 5f)) * 255).toInt()
        text.textSize = 14f * density
        text.color = cAmber
        text.alpha = alpha
        c.drawText("[ TAP TO ENTER ARMORY & MAP ]", width * 0.5f, height * 0.82f, text)
        text.alpha = 255
    }

    private fun drawMenuHubScreen(c: Canvas) {
        fill.color = Color.rgb(22, 25, 30)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        drawCashBadge(c, 18f * density, 24f * density, engine.cash)

        when (engine.selectedTab) {
            0 -> drawStoreTab(c)
            1 -> drawHeroesTab(c)
            3 -> drawGunsTab(c)
            else -> drawMapTab(c)
        }

        val labels = arrayOf("STORE", "HEROES", "MAP", "GUNS")
        for (i in 0..3) {
            val tr = tabRect(i)
            fill.color = if (engine.selectedTab == i) Color.rgb(44, 50, 60) else Color.rgb(16, 18, 22)
            c.drawRoundRect(tr, 8f * density, 8f * density, fill)
            if (engine.selectedTab == i) {
                stroke.color = cAmber
                stroke.strokeWidth = 2f * density
                c.drawRoundRect(tr, 8f * density, 8f * density, stroke)
            }
            text.textAlign = Paint.Align.CENTER
            text.textSize = 12f * density
            text.color = if (engine.selectedTab == i) cAmber else Color.LTGRAY
            c.drawText(labels[i], tr.centerX(), tr.centerY() + 4f * density, text)
        }
    }

    private fun drawMapTab(c: Canvas) {
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 12f * density
        text.color = cAmber
        c.drawText("ROOMS ${engine.mapPageOffset + 1}-${engine.mapPageOffset + 20} / ${engine.totalRoomsCount}", width - 18f * density, 38f * density, text)

        stroke.color = Color.rgb(85, 92, 102)
        stroke.strokeWidth = 3f * density
        for (s in 1..19) {
            val (x1, y1) = stageNodeCoords(s)
            val (x2, y2) = stageNodeCoords(s + 1)
            c.drawLine(x1, y1, x2, y2, stroke)
        }

        for (slot in 1..20) {
            val roomNum = engine.mapPageOffset + slot
            val (nx, ny) = stageNodeCoords(slot)
            val isCompleted = roomNum < engine.unlockedStage
            val isSelected = roomNum == engine.selectedStage
            val isBoss = roomNum % 5 == 0

            val boxSize = 18f * density
            val r = RectF(nx - boxSize, ny - boxSize, nx + boxSize, ny + boxSize)
            fill.color = when {
                isSelected -> cAmber
                isCompleted -> cNeon
                else -> Color.rgb(46, 52, 60)
            }
            c.drawRoundRect(r, 6f * density, 6f * density, fill)

            if (isSelected) {
                stroke.color = cWhite
                stroke.strokeWidth = 2.2f * density
                c.drawRoundRect(r, 6f * density, 6f * density, stroke)
            }

            text.textAlign = Paint.Align.CENTER
            text.textSize = 11f * density
            text.color = if (isCompleted || isSelected) cDark else cWhite
            c.drawText("$roomNum", nx, ny + 4f * density, text)

            if (isBoss) {
                fill.color = cRed
                c.drawCircle(nx + boxSize, ny - boxSize, 5.5f * density, fill)
            }
        }

        // Prev 20 / Next 20 Room Sector Buttons
        drawRectButton(c, prevRoomsBtnRect(), "< PREV 20", Color.rgb(45, 52, 62), cWhite)
        drawRectButton(c, nextRoomsBtnRect(), "NEXT 20 >", Color.rgb(45, 52, 62), cWhite)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f * density
        text.color = cWhite
        c.drawText("ROOM #${engine.selectedStage}: ${engine.roomThemeTitle(engine.selectedStage)}", width * 0.5f, height * 0.725f, text)

        val pr = playBtnRect()
        fill.color = Color.rgb(18, 128, 62)
        c.drawRoundRect(pr, 12f * density, 12f * density, fill)
        stroke.color = cNeon
        stroke.strokeWidth = 2.5f * density
        c.drawRoundRect(pr, 12f * density, 12f * density, stroke)

        text.textSize = 24f * density
        text.color = cWhite
        c.drawText("PLAY ROOM #${engine.selectedStage}", pr.centerX(), pr.centerY() + 8f * density, text)
    }

    private fun drawStoreTab(c: Canvas) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20f * density
        text.color = cAmber
        c.drawText("TACTICAL SUPPLY STORE", width * 0.5f, 78f * density, text)

        val items = listOf(
            Triple("KEVLAR ARMOR +25 HP", "Permanent Max HP Boost", "$200"),
            Triple("UNLOCK ALL 5 GUNS", "Instant Full Armory Access", "$300"),
            Triple("UNLOCK ALL 3 HEROES", "Recruit Entire Squad", "$250"),
            Triple("AIRDROP CASH PACK", "Claim +$500 Tactical Cash", "FREE")
        )
        for (i in items.indices) {
            val top = (110f + i * 96f) * density
            val card = RectF(16f * density, top, width - 16f * density, top + 82f * density)
            fill.color = Color.rgb(34, 39, 48)
            c.drawRoundRect(card, 10f * density, 10f * density, fill)

            text.textAlign = Paint.Align.LEFT
            text.textSize = 14f * density
            text.color = cWhite
            c.drawText(items[i].first, 28f * density, top + 32f * density, text)

            text.textSize = 11f * density
            text.color = Color.LTGRAY
            c.drawText(items[i].second, 28f * density, top + 56f * density, text)

            drawRectButton(c, cardActionRect(i), items[i].third, Color.rgb(20, 125, 65), cWhite)
        }
    }

    private fun drawHeroesTab(c: Canvas) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20f * density
        text.color = cAmber
        c.drawText("SELECT TACTICAL HERO", width * 0.5f, 78f * density, text)

        for (i in engine.heroes.indices) {
            val h = engine.heroes[i]
            val top = (110f + i * 96f) * density
            val card = RectF(16f * density, top, width - 16f * density, top + 82f * density)
            val isEquipped = i == engine.equippedHeroIndex
            fill.color = if (isEquipped) Color.rgb(38, 52, 46) else Color.rgb(34, 39, 48)
            c.drawRoundRect(card, 10f * density, 10f * density, fill)
            if (isEquipped) {
                stroke.color = cNeon
                stroke.strokeWidth = 2f * density
                c.drawRoundRect(card, 10f * density, 10f * density, stroke)
            }

            text.textAlign = Paint.Align.LEFT
            text.textSize = 15f * density
            text.color = cWhite
            c.drawText(h.name, 28f * density, top + 32f * density, text)

            text.textSize = 11f * density
            text.color = cCyan
            c.drawText("${h.role} • HP: ${h.maxHp.toInt()} • SPD: ${h.moveSpeed}", 28f * density, top + 56f * density, text)

            val btnLabel = when {
                isEquipped -> "EQUIPPED"
                h.unlocked -> "EQUIP"
                else -> "$${h.price}"
            }
            val btnBg = if (isEquipped) Color.rgb(22, 130, 68) else if (h.unlocked) Color.rgb(48, 92, 140) else Color.rgb(150, 95, 24)
            drawRectButton(c, cardActionRect(i), btnLabel, btnBg, cWhite)
        }
    }

    private fun drawGunsTab(c: Canvas) {
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20f * density
        text.color = cAmber
        c.drawText("WEAPONS ARMORY (${engine.guns.count { it.unlocked }}/${engine.guns.size})", width * 0.5f, 78f * density, text)

        for (i in engine.guns.indices) {
            val g = engine.guns[i]
            val top = (110f + i * 96f) * density
            val card = RectF(16f * density, top, width - 16f * density, top + 82f * density)
            val isEquipped = i == engine.equippedGunIndex
            fill.color = if (isEquipped) Color.rgb(38, 52, 46) else Color.rgb(34, 39, 48)
            c.drawRoundRect(card, 10f * density, 10f * density, fill)
            if (isEquipped) {
                stroke.color = cNeon
                stroke.strokeWidth = 2f * density
                c.drawRoundRect(card, 10f * density, 10f * density, stroke)
            }

            text.textAlign = Paint.Align.LEFT
            text.textSize = 14f * density
            text.color = cWhite
            c.drawText(g.name, 28f * density, top + 30f * density, text)

            text.textSize = 11f * density
            text.color = cAmber
            c.drawText("DMG: ${g.damage.toInt()} • MAG: ${g.magSize} • UNLOCK ROOM #${g.unlockRoom}", 28f * density, top + 54f * density, text)

            val btnLabel = when {
                isEquipped -> "EQUIPPED"
                g.unlocked -> "EQUIP"
                else -> "$${g.price}"
            }
            val btnBg = if (isEquipped) Color.rgb(22, 130, 68) else if (g.unlocked) Color.rgb(48, 92, 140) else Color.rgb(150, 95, 24)
            drawRectButton(c, cardActionRect(i), btnLabel, btnBg, cWhite)
        }
    }

    private fun drawPlayingHud(c: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.46f

        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * density
        text.color = cWhite
        val cleared = engine.roomKillsTarget - engine.roomRemainingToClear
        c.drawText("ROOM #${engine.activeRoomNumber}/1200 • ${engine.roomThemeTitle(engine.activeRoomNumber)} [$cleared/${engine.roomKillsTarget}]", 14f * density, 32f * density, text)

        val hpW = 130f * density
        fill.color = Color.argb(170, 14, 16, 20)
        c.drawRoundRect(14f * density, 40f * density, 14f * density + hpW, 50f * density, 4f * density, 4f * density, fill)
        fill.color = if (engine.hp > 30f) cNeon else cRed
        c.drawRoundRect(14f * density, 40f * density, 14f * density + hpW * (engine.hp / engine.maxHp).coerceIn(0f, 1f), 50f * density, 4f * density, 4f * density, fill)

        // Top-Right Active Gun Switcher & TPP/FPP Toggle Buttons!
        val curGun = engine.guns[engine.equippedGunIndex]
        drawRectButton(c, gunSwitchBtnRect(), "GUN: ${curGun.id.uppercase()} ↻", Color.argb(195, 28, 34, 44), cAmber)
        drawRectButton(c, tppToggleBtnRect(), if (engine.isTppMode) "VIEW: TPP" else "VIEW: FPP", Color.argb(195, 28, 34, 44), cCyan)

        if (engine.headshotBannerTimer > 0f) {
            val by = height * 0.16f
            fill.color = Color.argb(215, 200, 26, 26)
            c.drawCircle(cx, by, 24f * density, fill)
            fill.color = cWhite
            c.drawCircle(cx, by - 2f * density, 13f * density, fill)
            fill.color = cRed
            c.drawCircle(cx - 5f * density, by - 3f * density, 3f * density, fill)
            c.drawCircle(cx + 5f * density, by - 3f * density, 3f * density, fill)

            text.textAlign = Paint.Align.CENTER
            text.textSize = 14f * density
            text.color = cWhite
            c.drawText("HEADSHOT!", cx, by + 36f * density, text)
        }

        val spread = (10f + engine.recoilKick * 28f) * density
        stroke.color = if (engine.hitMarkerTimer > 0f) cRed else cWhite
        stroke.strokeWidth = 2.2f * density
        c.drawLine(cx - spread - 8f * density, cy, cx - spread, cy, stroke)
        c.drawLine(cx + spread, cy, cx + spread + 8f * density, cy, stroke)
        c.drawLine(cx, cy - spread - 8f * density, cx, cy - spread, stroke)
        c.drawLine(cx, cy + spread, cx, cy + spread + 8f * density, stroke)

        if (engine.hitMarkerTimer > 0f) {
            val hm = 9f * density
            c.drawLine(cx - hm, cy - hm, cx - 3f * density, cy - 3f * density, stroke)
            c.drawLine(cx + hm, cy - hm, cx + 3f * density, cy - 3f * density, stroke)
            c.drawLine(cx - hm, cy + hm, cx - 3f * density, cy + 3f * density, stroke)
            c.drawLine(cx + hm, cy + hm, cx + 3f * density, cy + 3f * density, stroke)
        }

        for (dn in engine.damagePopups) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = (if (dn.isHeadshot) 18f else 14f) * density
            text.color = if (dn.isHeadshot) cAmber else cWhite
            text.alpha = (dn.life.coerceIn(0f, 1f) * 255).toInt()
            c.drawText("${dn.amount}", cx + dn.screenOffsetX * density, cy - (26f + (1f - dn.life) * 45f) * density, text)
        }
        text.alpha = 255

        for (z in engine.zombies) {
            if (z.hp <= 0f) continue
            val relAngle = engine.relativeAngleToZombie(z)
            if (relAngle < -26f) {
                drawSideThreatArrow(c, 16f * density, cy, left = true)
            } else if (relAngle > 26f) {
                drawSideThreatArrow(c, width - 16f * density, cy, left = false)
            }
        }

        // Left-Thumb Forward (▲) & Backward (▼) Movement Buttons!
        drawCircleButton(c, forwardBtnRect(), "▲", if (engine.moveForwardPressed) cNeon else cWhite)
        drawCircleButton(c, backwardBtnRect(), "▼", if (engine.moveBackwardPressed) cRed else cWhite)

        // Bottom-Left Ammo Counter
        val ammoLeft = 22f * density
        val ammoBaseY = height - 28f * density
        text.textAlign = Paint.Align.LEFT
        text.textSize = 22f * density
        text.color = if (engine.reloadTimer > 0f) cRed else cWhite
        c.drawText(if (engine.reloadTimer > 0f) "RELOADING..." else "${engine.currentAmmo} / ${engine.maxAmmo}", ammoLeft, ammoBaseY, text)

        // Right-Thumb Reload & Scope Buttons
        drawCircleButton(c, reloadBtnRect(), "RLD", cAmber)
        drawCircleButton(c, scopeBtnRect(), if (engine.isScoped) "1X" else "4X", cNeon)
    }

    private fun drawCompletedScreen(c: Canvas) {
        fill.color = Color.argb(235, 14, 16, 19)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        drawCashBadge(c, 18f * density, 24f * density, engine.cash)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 30f * density
        text.color = cWhite
        c.drawText("ROOM #${engine.activeRoomNumber} CLEARED!", width * 0.5f, height * 0.16f, text)

        val unlockedMsg = engine.lastUnlockedGunName ?: "EQUIPPED: ${engine.guns[engine.equippedGunIndex].name}"
        text.textSize = 15f * density
        text.color = cNeon
        c.drawText(if (engine.lastUnlockedGunName != null) "NEW GUN UNLOCKED: $unlockedMsg!" else unlockedMsg, width * 0.5f, height * 0.24f, text)

        val gx = width * 0.5f
        val gy = height * 0.36f
        fill.color = Color.rgb(55, 62, 72)
        c.drawRoundRect(gx - 85f * density, gy - 14f * density, gx + 85f * density, gy + 14f * density, 6f * density, 6f * density, fill)
        c.drawRoundRect(gx - 25f * density, gy + 10f * density, gx - 5f * density, gy + 44f * density, 4f * density, 4f * density, fill)
        c.drawRoundRect(gx - 95f * density, gy - 10f * density, gx - 65f * density, gy + 26f * density, 4f * density, 4f * density, fill)

        text.textSize = 18f * density
        text.color = cWhite
        c.drawText("REWARD:  $${engine.lastStageReward}", gx, height * 0.54f, text)

        val br = bonusBtnRect()
        fill.color = Color.rgb(34, 42, 48)
        c.drawRoundRect(br, 10f * density, 10f * density, fill)
        stroke.color = cNeon
        stroke.strokeWidth = 2f * density
        c.drawRoundRect(br, 10f * density, 10f * density, stroke)
        text.textSize = 13f * density
        text.color = cAmber
        c.drawText("GET BONUS (3X)", br.centerX(), br.centerY() - 3f * density, text)
        text.textSize = 18f * density
        text.color = cNeon
        c.drawText("$${engine.lastStageReward * 3}", br.centerX(), br.centerY() + 17f * density, text)

        val cr = continueBtnRect()
        text.textSize = 16f * density
        text.color = Color.LTGRAY
        c.drawText("NEXT ROOM →", cr.centerX(), cr.centerY() + 6f * density, text)
    }

    private fun drawGameOverScreen(c: Canvas) {
        fill.color = Color.argb(225, 16, 12, 14)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        text.textAlign = Paint.Align.CENTER
        text.textSize = 30f * density
        text.color = cRed
        c.drawText("OVERRUN IN ROOM #${engine.activeRoomNumber}", width * 0.5f, height * 0.36f, text)

        text.textSize = 16f * density
        text.color = cWhite
        c.drawText("TOTAL KILLS: ${engine.kills}   |   CASH: $${engine.cash}", width * 0.5f, height * 0.46f, text)

        text.textSize = 18f * density
        text.color = cAmber
        c.drawText("[ TAP TO RETURN TO MAP ]", width * 0.5f, height * 0.68f, text)
    }

    private fun drawCashBadge(c: Canvas, left: Float, top: Float, amount: Int) {
        fill.color = cNeon
        c.drawRoundRect(left, top, left + 26f * density, top + 18f * density, 4f * density, 4f * density, fill)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f * density
        text.color = cDark
        c.drawText("$", left + 13f * density, top + 13f * density, text)

        text.textAlign = Paint.Align.LEFT
        text.textSize = 16f * density
        text.color = cWhite
        c.drawText("$amount", left + 32f * density, top + 15f * density, text)
    }

    private fun drawSideThreatArrow(c: Canvas, x: Float, y: Float, left: Boolean) {
        val dir = if (left) -1f else 1f
        val path = Path().apply {
            moveTo(x + dir * 10f * density, y)
            lineTo(x - dir * 6f * density, y - 9f * density)
            lineTo(x - dir * 6f * density, y + 9f * density)
            close()
        }
        fill.color = cRed
        c.drawPath(path, fill)
    }

    private fun drawRectButton(c: Canvas, r: RectF, label: String, bgColor: Int, textColor: Int) {
        fill.color = bgColor
        c.drawRoundRect(r, 8f * density, 8f * density, fill)
        stroke.color = textColor
        stroke.strokeWidth = 1.5f * density
        c.drawRoundRect(r, 8f * density, 8f * density, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11f * density
        text.color = textColor
        c.drawText(label, r.centerX(), r.centerY() + 4f * density, text)
    }

    private fun drawCircleButton(c: Canvas, r: RectF, label: String, color: Int) {
        fill.color = Color.argb(180, 18, 22, 28)
        c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.5f, fill)
        stroke.color = color
        stroke.strokeWidth = 2f * density
        c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.5f, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f * density
        text.color = color
        c.drawText(label, r.centerX(), r.centerY() + 5f * density, text)
    }
}
