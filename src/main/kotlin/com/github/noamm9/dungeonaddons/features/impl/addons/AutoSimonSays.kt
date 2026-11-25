package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.NoammAddons
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.*
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.location.LocationUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Does the F7/M7 Simon Says device for you: turns onto each button and right clicks it,
 * the same way you would. The solver is the same one NoammAddons' SimonSays uses:
 * the obsidian that lights up as sea lantern marks the button in front of it.
 */
object AutoSimonSays: Feature(
    description = "Turns onto the F7/M7 Simon Says buttons and clicks them for you.",
    name = "Auto Simon Says"
) {
    private val autoClick by ToggleSetting("Auto Click", true).section("Auto")
    private val rotationTime by SliderSetting("Rotation Time", 150, 0, 500, 1, "ms")
        .withDescription("Time (ms) to turn onto each button. &eSet to 0 to snap onto it.")
    private val clickDelay by SliderSetting("Click Delay", 3, 1, 10, 1, " ticks")
        .withDescription("Server ticks between two button clicks.").showIf { autoClick.value }
    private val preAimSetting by ToggleSetting("Pre Aim", true)
        .withDescription("Turns to the first button while the pattern still shows.")
    private val ssSkip by ToggleSetting("SS skip Compatibility", true)
        .withDescription("Always assume at the start that you perfectly SS skip.")

    private val autoStart by ToggleSetting("Auto Start").section("Start")
        .withDescription("Once phase 3 began, turns onto the start button and clicks it as soon as you stand on the blocks in front of the device, unless it's already running.")
    private val startClicks by SliderSetting("Start Clicks", 3, 1, 10, 1)
        .withDescription("Amount of clicks to start the device.").showIf { autoStart.value }
    private val startClickDelay by SliderSetting("Start Click Delay", 3, 1, 25, 1, " ticks")
        .withDescription("Delay in ticks between each start click.").showIf { autoStart.value }

    private val startRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val sectionRegex = Regex("^\\w{1,16} (?:activated|completed) a \\w+! \\((\\d)/(\\d)\\)$")
    private val obsidians = (120 .. 123).flatMap { y -> (92 .. 95).map { z -> BlockPos(111, y, z) } }
    private val startButton = BlockPos(110, 121, 91)

    private val solution = ArrayList<BlockPos>()
    private var skipOver = false
    private var lastClickTick = 0L
    private var job: Job? = null

    // the stage the way NoammAddons' SimonSays counts it (the pattern length once the buttons come,
    // after the SS skip took one off), and whether we already turned for the pattern showing now
    private var stage = 0
    private var preAimed = false

    // set when P3 begins, cleared once the device got started or section 1 is over
    private var waitingToStart = false
    private var lastStartTry = 0L

    override fun init() {
        register<WorldChangeEvent> {
            resetSolver()
            waitingToStart = false
        }

        register<ChatMessageEvent> {
            val msg = event.unformattedText
            if (startRegex.matches(msg)) {
                resetSolver()
                waitingToStart = true
                return@register
            }

            val (done, total) = sectionRegex.matchEntire(msg)?.destructured ?: return@register
            if (done == total) waitingToStart = false
        }

        register<TickEvent.Start> {
            if (LocationUtils.F7Phase != 3) return@register
            if (job?.isActive == true) return@register
            tryStart()
            if (job?.isActive == true) return@register

            if (! autoClick.value) return@register
            if (DungeonListener.currentTime - lastClickTick < clickDelay.value) return@register

            val expected = solution.firstOrNull() ?: return@register
            // the buttons only exist once the sequence is done showing
            if (mc.level?.getBlockState(expected)?.`is`(Blocks.STONE_BUTTON) != true) return@register
            if (! canClick(expected)) return@register

            job = NoammAddons.scope.launch { turnAndClick(expected) }
        }

        register<BlockChangeEvent> {
            if (event.pos !in obsidians) return@register
            if (event.newBlock != Blocks.SEA_LANTERN) return@register
            if (ssSkip.value && solution.size == 2 && ! skipOver) solution.removeFirst()

            val button = event.pos.west()
            if (button !in solution) solution.add(button)

            // it's running, whoever started it, so the start button is done with
            waitingToStart = false

            // once per pattern, onto the button Auto Click starts with. Stage 2 done means phase 3 shows now.
            if (! preAimed && stage >= 2) solution.firstOrNull()?.let {
                preAimed = true
                preAim(it)
            }
        }

        // the buttons come once the pattern is done showing and go away when the next one starts
        register<BlockChangeEvent> {
            if (event.pos != BlockPos(110, 120, 93)) return@register
            if (event.newBlock != Blocks.STONE_BUTTON) return@register
            skipOver = true
            // the same stage NoammAddons' SimonSays progress display shows (the 2 in 2/4)
            stage = maxOf(stage, solution.size)
            preAimed = false
        }

        // every click goes through the normal right click, ours included
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> { onClicked(event.pos) }
        register<PlayerInteractEvent.LEFT_CLICK.BLOCK> { onClicked(event.pos) }
    }

    override fun onDisable() {
        super.onDisable()
        resetSolver()
        waitingToStart = false
    }

    private fun tryStart() {
        if (! autoStart.value || ! waitingToStart) return
        // someone else started it already
        if (solution.isNotEmpty() || deviceRunning()) return
        // don't turn onto it every tick while something is in the way
        if (DungeonListener.currentTime - lastStartTry < 10) return
        if (! onStartBlocks()) return
        if (! canClick(startButton)) return
        lastStartTry = DungeonListener.currentTime

        job = NoammAddons.scope.launch {
            repeat(startClicks.value) {
                if (it > 0) delay(startClickDelay.value * 50L)
                if (! turnAndClick(startButton)) return@launch
            }
        }
    }

    // Turns to where the button will come, it isn't there yet while the pattern shows
    private fun preAim(button: BlockPos) {
        if (! preAimSetting.value || LocationUtils.F7Phase != 3) return
        if (job?.isActive == true || mc.gui.screen() != null) return
        if (mc.player?.isWithinBlockInteractionRange(button, 0.0) != true) return
        job = NoammAddons.scope.launch {
            PlayerUtils.rotateSmoothly(Vec3(button.x + 0.9375, button.y + 0.5, button.z + 0.5), rotationTime.value.toLong())
        }
    }

    // Standing on the blocks right in front of the device, x 107-109, y 119, z 93-94
    private fun onStartBlocks(): Boolean {
        val player = mc.player ?: return false
        if (! player.onGround()) return false
        val below = player.blockPosition().below()
        return below.y == 119 && below.x in 107 .. 109 && below.z in 93 .. 94
    }

    private fun deviceRunning(): Boolean {
        val level = mc.level ?: return false
        return obsidians.any {
            level.getBlockState(it).`is`(Blocks.SEA_LANTERN) || level.getBlockState(it.west()).`is`(Blocks.STONE_BUTTON)
        }
    }

    private fun canClick(pos: BlockPos): Boolean {
        val player = mc.player ?: return false
        if (mc.gui.screen() != null) return false
        if (! player.isWithinBlockInteractionRange(pos, 0.0)) return false
        // sneaking with an item uses the item instead of the button
        return ! (player.isSecondaryUseActive && ! player.mainHandItem.isEmpty)
    }

    // The middle of the button itself, whichever way it's facing
    private fun aimPoint(pos: BlockPos): Vec3 {
        val level = mc.level ?: return Vec3.atCenterOf(pos)
        val shape = level.getBlockState(pos).getShape(level, pos)
        if (shape.isEmpty) return Vec3.atCenterOf(pos)
        return Vec3.atLowerCornerOf(pos).add(shape.bounds().center)
    }

    // Turns onto the button, then right clicks on the main thread if the crosshair landed on it
    private suspend fun turnAndClick(pos: BlockPos): Boolean {
        PlayerUtils.rotateSmoothly(aimPoint(pos), rotationTime.value.toLong())

        var clicked = false
        mc.submit(Runnable {
            val player = mc.player ?: return@Runnable
            if (! enabled || ! canClick(pos)) return@Runnable
            // the crosshair target only updates next tick, so look again from where we turned to
            val hit = player.pick(player.blockInteractionRange(), 1f, false) as? BlockHitResult ?: return@Runnable
            if (hit.type != HitResult.Type.BLOCK || hit.blockPos != pos) return@Runnable
            mc.hitResult = hit
            lastClickTick = DungeonListener.currentTime
            PlayerUtils.rightClick()
            clicked = true
        }).join()
        return clicked
    }

    private fun onClicked(pos: BlockPos) {
        if (LocationUtils.F7Phase != 3) return
        if (pos == startButton) {
            waitingToStart = false
            // the start clicks are still running, so only the solution resets
            return resetSolver(cancelJob = false)
        }
        if (solution.isEmpty()) return
        if (mc.level?.getBlockState(pos)?.`is`(Blocks.STONE_BUTTON) != true) return

        val expected = solution.first()
        if (pos == expected) solution.removeFirst()
        else if (solution.size == 3 && pos == solution[1]) repeat(2) { solution.removeFirst() }
    }

    private fun resetSolver(cancelJob: Boolean = true) {
        if (cancelJob) {
            job?.cancel()
            job = null
        }
        solution.clear()
        skipOver = false
        lastClickTick = 0L
        stage = 0
        preAimed = false
    }
}
