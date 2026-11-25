package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.TerminalListener
import com.github.noamm9.dungeonaddons.utils.ArrowAlignData
import com.github.noamm9.dungeonaddons.utils.ArrowAlignData.index
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils.toVec
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.DungeonUtils
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * Clicks what your crosshair is on, like you would:
 * Secrets in clear, and in F7/M7 phase 3 the terminals, the levers and the arrow align device.
 */
object Triggerbot: Feature(
    description = "Clicks when your crosshair is on things you need to click.",
    name = "Triggerbot"
) {
    private val secrets by ToggleSetting("Secrets", true).section("Secrets")
        .withDescription("Clicks chests, levers, wither essence and redstone keys.")
    private val secretDelay by SliderSetting("Secret Delay", 150, 50, 700, 5, "ms")
        .withDescription("Least time between two secret clicks.").showIf { secrets.value }

    private val terminals by ToggleSetting("Terminals", true).section("Terminals")
        .withDescription("Opens the F7/M7 terminals.")
    private val terminalDelay by SliderSetting("Terminal Delay", 500, 100, 2000, 50, "ms")
        .withDescription("Least time between two tries to open a terminal.").showIf { terminals.value }

    private val lightsDevice by ToggleSetting("Lights Device", true).section("Levers")
        .withDescription("Flips the six lights device levers.")
    private val sectionLevers by ToggleSetting("Section Levers", true)
        .withDescription("Flips the two levers of every section.")
    private val leverDelay by SliderSetting("Lever Delay", 150, 50, 700, 5, "ms")
        .withDescription("Least time between two flips.").showIf { lightsDevice.value || sectionLevers.value }

    private val arrowAlign by ToggleSetting("Arrow Align", true).section("Arrow Align")
        .withDescription("Turns the arrows.")
    private val arrowDelay by SliderSetting("Arrow Delay", 100, 50, 500, 5, "ms")
        .withDescription("Least time between two clicks.").showIf { arrowAlign.value }
    private val leaveOne by ToggleSetting("Leave One Click", true)
        .withDescription("One arrow is left one click short during pre dev.")
        .showIf { arrowAlign.value }


    private val noHeadsWithAbility by ToggleSetting("No Heads With Wither Blade/AOTV").section("Options")
        .withDescription("Disables clicks on player heads (wither essence and redstone skulls) while holding a wither blade or AOTV, so the click does not teleport you.")

    private val startRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private var p3Started = false

    private val clickedSecrets = mutableSetOf<BlockPos>()
    private var lastSecretClick = 0L
    private var lastTerminalTry = 0L
    private var lastLeverFlip = 0L
    private var lastArrowClick = 0L

    // the six levers that belong on, the rest of the 5x4 grid stays off
    private val lightsLevers = setOf(
        BlockPos(58, 133, 142), BlockPos(62, 133, 142),
        BlockPos(60, 134, 142), BlockPos(60, 135, 142),
        BlockPos(58, 136, 142), BlockPos(62, 136, 142)
    )
    private val flippedLevers = mutableSetOf<BlockPos>()

    private val arrowClickTimes = mutableMapOf<Int, Long>()
    private val frameRotations = IntArray(25) { - 1 }
    private var arrowSolution: List<Int>? = null
    private val leaving get() = leaveOne.value && ! p3Started

    override fun init() {
        register<ChatMessageEvent> {
            if (! startRegex.matches(event.unformattedText)) return@register
            p3Started = true
            // a new P3 has all its levers back, flip them again
            flippedLevers.clear()
        }

        register<WorldChangeEvent> {
            p3Started = false
            clickedSecrets.clear()
            flippedLevers.clear()
        }

        register<TickEvent.Start> {
            if (LocationUtils.F7Phase != 3) {
                flippedLevers.clear()
                resetArrows()
            }
            else updateArrows()

            if (mc.gui.screen() != null) return@register
            if (! LocationUtils.inDungeon) return@register

            if (! LocationUtils.inBoss) secretTick()
            else if (LocationUtils.F7Phase == 3) {
                terminalTick()
                leverTick()
                arrowTick()
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        clickedSecrets.clear()
        flippedLevers.clear()
        resetArrows()
    }

    // Secrets

    private fun secretTick() {
        if (! secrets.value) return
        if (System.currentTimeMillis() - lastSecretClick < secretDelay.value) return

        val pos = PlayerUtils.getSelectionBlock() ?: return
        if (pos in clickedSecrets) return
        val block = mc.level?.getBlockState(pos)?.block ?: return
        // never buttons, isSecret doesn't count them either
        if (! DungeonUtils.isSecret(pos)) return
        // a head does nothing when clicked, so the click goes on to the item: wither impact or a teleport
        if (noHeadsWithAbility.value && (block is SkullBlock || block is WallSkullBlock) && Aura.holdingAbilityItem()) return

        when (ScanUtils.getRoomFromPos(Vec3.atCenterOf(pos))?.name) {
            // the levers move the water
            "Water Board" -> if (block == Blocks.LEVER) return
            // the chests are the puzzle itself
            "Three Weirdos" -> if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return
        }

        PlayerUtils.rightClick()
        clickedSecrets.add(pos)
        lastSecretClick = System.currentTimeMillis()
    }

    // Terminals

    private fun terminalTick() {
        if (! terminals.value) return
        if (System.currentTimeMillis() - lastTerminalTry < terminalDelay.value) return
        val player = mc.player ?: return
        // a terminal is already open
        if (TerminalListener.inTerm || TerminalListener.lastWindowId != - 1) return
        if (player.containerMenu !== player.inventoryMenu) return

        val stand = (mc.hitResult as? EntityHitResult)?.entity as? ArmorStand ?: return
        if (stand.customName?.unformattedText != "Inactive Terminal") return

        PlayerUtils.rightClick()
        lastTerminalTry = System.currentTimeMillis()
    }

    // Levers: the lights device ones only while off, every other one once per P3

    private fun leverTick() {
        if (System.currentTimeMillis() - lastLeverFlip < leverDelay.value) return
        val pos = PlayerUtils.getSelectionBlock() ?: return
        val state = mc.level?.getBlockState(pos) ?: return
        if (! state.`is`(Blocks.LEVER)) return

        val inLights = pos.x in 58 .. 62 && pos.y in 133 .. 136 && pos.z == 142
        val shouldFlip = if (inLights) lightsDevice.value && pos in lightsLevers && ! state.getValue(LeverBlock.POWERED)
        else sectionLevers.value && pos !in flippedLevers
        if (! shouldFlip) return

        PlayerUtils.rightClick()
        lastLeverFlip = System.currentTimeMillis()
        if (! inLights) flippedLevers.add(pos)
    }

    // Arrow align, the same solver NoammAddons' ArrowAlign uses

    private fun updateArrows() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (! arrowAlign.value || player.distanceToSqr(ArrowAlignData.gridCorner.toVec()) > 200) return resetArrows()

        level.getEntitiesOfClass(ItemFrame::class.java, ArrowAlignData.gridBox) { it.item.item == Items.ARROW }.forEach { frame ->
            val index = frame.index() ?: return@forEach
            // the server takes a moment to show our clicks, keep the predicted rotation until then
            if (System.currentTimeMillis() - (arrowClickTimes[index] ?: 0L) > 1000) frameRotations[index] = frame.rotation
        }

        arrowSolution = ArrowAlignData.findSolution(frameRotations)
    }

    private fun arrowTick() {
        if (! arrowAlign.value) return
        if (System.currentTimeMillis() - lastArrowClick < arrowDelay.value) return
        val target = arrowSolution ?: return
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return

        val hit = mc.hitResult as? EntityHitResult ?: return
        val frame = hit.entity as? ItemFrame ?: return
        if (frame.item.item != Items.ARROW) return
        val index = frame.index() ?: return
        if (frameRotations[index] == - 1) return
        if (ArrowAlignData.getClicks(frameRotations[index], targetFor(index, target)) == 0) return

        gameMode.interact(player, frame, hit, InteractionHand.MAIN_HAND)
        //? if <26.3 {
        player.swing(InteractionHand.MAIN_HAND)
        //?} else {
        /*player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)
        *///?}

        lastArrowClick = System.currentTimeMillis()
        arrowClickTimes[index] = lastArrowClick
        frameRotations[index] = (frameRotations[index] + 1) % 8
    }

    // The bottom left arrow always ends one click short of its spot, even if it already sat right
    private fun targetFor(index: Int, target: List<Int>): Int {
        if (! leaving || index != ArrowAlignData.bottomLeftArrow(target) || target[index] == - 1) return target[index]
        return (target[index] + 7) % 8
    }

    private fun resetArrows() {
        frameRotations.fill(- 1)
        arrowSolution = null
        arrowClickTimes.clear()
    }
}
