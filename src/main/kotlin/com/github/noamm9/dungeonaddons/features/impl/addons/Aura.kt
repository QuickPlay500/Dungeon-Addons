package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.TerminalListener
import com.github.noamm9.init.DataDownloader
import com.github.noamm9.dungeonaddons.utils.ArrowAlignData
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.DungeonUtils
import com.github.noamm9.utils.dungeons.enums.Puzzle
import com.github.noamm9.utils.dungeons.map.core.RoomState
import com.github.noamm9.utils.dungeons.map.core.RoomType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Clicks things near you, through walls too. The click goes straight to the block or entity.
 * Clear: every dungeon secret once (the same blocks NoammAddons counts as secrets).
 * Puzzles: reward chests once a puzzle is done, and the right Quiz answer.
 * Boss: F7/M7 phase 3 terminals, levers, the lights device and the arrow device.
 */
object Aura: Feature(
    description = "&c&lWARNING! &cThis &ccan &cget &cyou &cbanned &cvery &cquickly!\n&r secret aura + p3 aura",
    name = "Aura"
) {
    private val range by SliderSetting("Range", 5.5, 2.0, 6.0, 0.1, " blocks")
    private val headRange by SliderSetting("Head Range", 4.5, 2.0, 6.0, 0.1, " blocks")
        .withDescription("Range for player heads (wither essence and redstone skulls), those can't be reached as far.")
    private val clickDelay by SliderSetting("Click Delay", 150, 50, 1000, 10, "ms")
    private val leverDelay by SliderSetting("Lever Delay", 200, 100, 1000, 10, "ms")
        .withDescription("Used for levers instead of Click Delay, before and after flipping one. Levers do not like getting flipped with less than 100ms delay.")

    private val chests by ToggleSetting("Chests", true).section("Clear")
    private val essence by ToggleSetting("Wither Essence", true)
    private val levers by ToggleSetting("Levers", true)
    private val redstoneKey by ToggleSetting("Redstone Skulls", true)
    private val redstoneBlocks by ToggleSetting("Redstone Blocks", true)
        .withDescription("Places the redstone key on the redstone block.")
    private val mushrooms by ToggleSetting("Mushrooms", true)

    private val abilityItems = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE", "ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")

    private val puzzleChests by ToggleSetting("Puzzles", true)
        .withDescription("Opens the reward chest of a puzzle once it's done (Boulder's any time). Never in Three Weirdos. Also clicks Quiz buttons.")

    private val terminals by ToggleSetting("Terminals", true).section("Boss")
    private val bossLevers by ToggleSetting("Section Levers", true)
    private val lightsDevice by ToggleSetting("Lights Device", true)
        .withDescription("Flips the six right levers of the lights device. Works on pre-dev")
    private val arrowDevice by ToggleSetting("Arrow Device", true)
        .withDescription("Once p3 began, turns the arrows that are still wrong. Works on pre-dev")

    private val closeChests by ToggleSetting("Close Chests", true).section("Options")
    private val noHeadsWithAbility by ToggleSetting("No Heads With Wither Blade/AOTV")
        .withDescription("Disables clicks on player heads (wither essence and redstone skulls) while holding a wither blade or AOTV, so you do not teleport.")
    private val serverRotate by ToggleSetting("Server Rotation", true)
        .withDescription("Turns your head onto each secret for the server and other players before clicking, your screen doesn't move.")
    private val rotationTicks by SliderSetting("Rotation Ticks", 2, 1, 5, 1, " ticks")
        .withDescription("Ticks to turn there and back again. &e1 is the quickest.").showIf { serverRotate.value }
    private val throughWalls by ToggleSetting("Through Walls", true)
    private val fov by SliderSetting("FOV", 180, 15, 180, 5, "°")
        .withDescription("How far left and right of your crosshair it clicks things. &e180 is every direction.")

    /** The rotation the server gets instead of the real one, null for the real one. Read by MixinLocalPlayer. */
    @JvmField var serverRotation: MathUtils.Rotation? = null
    private var aim: Aim? = null
    private var ticksBack = 0

    private val clicked = mutableSetOf<BlockPos>()
    private var lastClick = 0L
    private var lastClickWasLever = false
    private var chestClicked: BlockPos? = null
    private val tpMazesDone = mutableSetOf<BlockPos>()
    private val retryAt = mutableMapOf<BlockPos, Long>()
    private val failedTries = mutableMapOf<BlockPos, Int>()
    private var iceFillDone = false

    private val startRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private var p3Started = false
    private val bossLeversFlipped = mutableSetOf<BlockPos>()
    private var lastTerminalTry = 0L
    // the six levers that belong on, the same ones the Triggerbot uses
    private val lightsLevers = listOf(
        BlockPos(58, 133, 142), BlockPos(62, 133, 142),
        BlockPos(60, 134, 142), BlockPos(60, 135, 142),
        BlockPos(58, 136, 142), BlockPos(62, 136, 142)
    )
    private val lightsCompleteLever = BlockPos(60, 133, 142)
    private var lightsExtraDone = false
    // our arrow clicks by frame: the rotation it'll have and when we clicked
    private val arrowPredicted = mutableMapOf<Int, Pair<Int, Long>>()

    private val quizSolutions by lazy {
        runCatching { DataDownloader.loadJson<Map<String, List<String>>>("quizSolutions.json") }.getOrNull()
    }
    private val quizLetters = listOf('ⓐ', 'ⓑ', 'ⓒ')
    // the blocks in front of the three answer buttons, from NoammAddons' QuizSolver
    private val quizBlocks = listOf(BlockPos(5, 70, - 9), BlockPos(0, 70, - 6), BlockPos(- 5, 70, - 9))
    private var quizAnswers: List<String>? = null
    private var quizCorrect: Int? = null
    private var quizOptionsSeen = 0
    private var quizAnswerTick = Long.MAX_VALUE
    private var quizTries = 0
    private var quizClickedAt = 0L

    override fun init() {
        register<WorldChangeEvent> {
            clicked.clear()
            tpMazesDone.clear()
            retryAt.clear()
            failedTries.clear()
            iceFillDone = false
            p3Started = false
            bossLeversFlipped.clear()
            lightsExtraDone = false
            arrowPredicted.clear()
            resetQuiz()
            stopRotating()
        }

        register<TickEvent.Start> {
            checkOurChest()
            // Ice Fill is done once you stand on the last block of part 3 (Auto Ice Fill's solution)
            if (! iceFillDone && AutoIceFill.finalBlock?.equals(mc.player?.blockPosition()) == true) iceFillDone = true
            // still turning onto the last secret
            if (stepRotation()) return@register

            if (! LocationUtils.inDungeon) return@register
            // wait to see if the last chest opened before clicking anything else
            if (chestClicked != null) return@register
            if (mc.gui.screen() != null) return@register
            // after a lever the next click waits the Lever Delay too
            val delay = if (lastClickWasLever) maxOf(clickDelay.value, leverDelay.value) else clickDelay.value
            if (System.currentTimeMillis() - lastClick < delay) return@register

            if (LocationUtils.inBoss) return@register bossTick()
            if (LocationUtils.F7Phase != 3) bossLeversFlipped.clear()

            quizButton()?.let { return@register requestClick(it) }

            val pos = findSecret() ?: return@register
            clicked.add(pos)
            val block = mc.level?.getBlockState(pos)?.block
            // mushrooms get broken, not clicked
            if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM) return@register request(Vec3.atCenterOf(pos)) { breakBlock(pos) }

            // puzzle reward chests hand out their loot without a menu, so don't wait for one there
            val isChest = block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
            val waitForMenu = isChest && ScanUtils.getRoomFromPos(Vec3.atCenterOf(pos))?.data?.type != RoomType.PUZZLE
            requestClick(pos) { if (waitForMenu) chestClicked = pos }
        }

        register<ChatMessageEvent> {
            onQuizChat(event.unformattedText)
            if (startRegex.matches(event.unformattedText)) {
                p3Started = true
                // a new P3 has all its levers back
                bossLeversFlipped.clear()
                lightsExtraDone = false
            }
        }
    }

    // Boss: one thing per click, in order terminals, levers, lights device, arrow device
    private fun bossTick() {
        if (LocationUtils.F7Phase != 3) return bossLeversFlipped.clear()
        bossTerminal()?.let { return }
        bossLever()?.let { return requestClick(it) { bossLeversFlipped.add(it) } }
        lightsLever()?.let { return requestClick(it) }
        arrowFrame()?.let { (frame, index) -> return request(frame.boundingBox.center) { clickFrame(frame, index) } }
    }

    // In reach, inside the FOV and, without Through Walls, not behind a block
    private fun inRange(target: Vec3, reach: Double = range.value): Boolean {
        val player = mc.player ?: return false
        val eye = player.eyePosition
        val to = target.subtract(eye)
        if (to.lengthSqr() > reach * reach) return false
        if (fov.value < 180 && to.lengthSqr() > 1e-6) {
            val angle = Math.toDegrees(acos(player.lookAngle.dot(to.normalize()).coerceIn(- 1.0, 1.0)))
            if (angle > fov.value) return false
        }
        if (throughWalls.value) return true
        val hit = mc.level?.clip(ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)) ?: return false
        // hitting the target block itself is fine
        return hit.type == HitResult.Type.MISS || hit.blockPos == BlockPos.containing(target)
    }

    // The closest inactive terminal in range, opened right here. Not while one is open, a hidden Auto Terminal Walk one too.
    private fun bossTerminal(): Unit? {
        if (! terminals.value) return null
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (TerminalListener.inTerm || TerminalListener.lastWindowId != - 1) return null
        if (player.containerMenu !== player.inventoryMenu) return null
        // NoammAddons blocks terminal clicks for 15 ticks after one, don't try in that time
        if (System.currentTimeMillis() - lastTerminalTry < 1000) return null

        val stand = level.getEntitiesOfClass(ArmorStand::class.java, player.boundingBox.inflate(range.value + 1)) {
            it.customName?.unformattedText == "Inactive Terminal" && inRange(it.boundingBox.center)
        }.minByOrNull { it.distanceToSqr(player) } ?: return null

        lastTerminalTry = System.currentTimeMillis()
        return request(stand.boundingBox.center) { interactEntity(stand) }
    }

    // Levers need more time to the last click than the rest
    private fun leverReady() = System.currentTimeMillis() - lastClick >= leverDelay.value

    // A P3 lever outside the lights device that we haven't flipped this P3
    private fun bossLever(): BlockPos? {
        // not during pre dev, only once P3 started
        if (! bossLevers.value || ! p3Started || ! leverReady()) return null
        return nearestBlock { pos, state -> state.`is`(Blocks.LEVER) && ! pos.inLightsGrid() && pos !in bossLeversFlipped }
    }

    // A right lights lever that's off. Pre-devved before P3 the device isn't active yet with all six on,
    // so once P3 began the lowest middle lever gets clicked once to complete it.
    private fun lightsLever(): BlockPos? {
        if (! lightsDevice.value || ! leverReady()) return null
        val level = mc.level ?: return null
        fun on(pos: BlockPos) = level.getBlockState(pos).let { it.`is`(Blocks.LEVER) && it.getValue(LeverBlock.POWERED) }

        lightsLevers.firstOrNull { ! on(it) && inRange(Vec3.atCenterOf(it)) && level.getBlockState(it).`is`(Blocks.LEVER) }?.let { return it }
        if (! p3Started || lightsExtraDone || ! lightsLevers.all(::on) || lightsActive()) return null
        if (! level.getBlockState(lightsCompleteLever).`is`(Blocks.LEVER) || ! inRange(Vec3.atCenterOf(lightsCompleteLever))) return null

        lightsExtraDone = true
        return lightsCompleteLever
    }

    // the "Device Active" name under the levers once it's done
    private fun lightsActive(): Boolean {
        val level = mc.level ?: return false
        val area = AABB(Vec3(60.5, 134.5, 142.5), Vec3(60.5, 134.5, 142.5)).inflate(5.0)
        return level.getEntitiesOfClass(ArmorStand::class.java, area) { it.customName?.unformattedText == "Device Active" }.isNotEmpty()
    }

    private fun BlockPos.inLightsGrid() = x in 58 .. 62 && y in 133 .. 136 && z == 142

    // An arrow that's still wrong, once P3 began. Same solver as the Triggerbot, our own clicks show up a moment later.
    private fun arrowFrame(): Pair<ItemFrame, Int>? {
        if (! arrowDevice.value) return null
        val level = mc.level ?: return null
        val frames = level.getEntitiesOfClass(ItemFrame::class.java, ArrowAlignData.gridBox) { it.item.item == Items.ARROW }
        if (frames.isEmpty()) return null

        val rotations = IntArray(25) { - 1 }
        val byIndex = mutableMapOf<Int, ItemFrame>()
        with(ArrowAlignData) {
            frames.forEach { frame ->
                val index = frame.index() ?: return@forEach
                byIndex[index] = frame
                val predicted = arrowPredicted[index]
                rotations[index] = if (predicted != null && System.currentTimeMillis() - predicted.second < 1000) predicted.first else frame.rotation
            }
        }

        val solution = ArrowAlignData.findSolution(rotations) ?: return null

        // pre dev: the bottom left arrow always ends one click short until P3 starts, like with the Triggerbot
        val leftShort = ArrowAlignData.bottomLeftArrow(solution)
        fun targetFor(index: Int): Int {
            if (p3Started || index != leftShort || solution[index] == - 1) return solution[index]
            return (solution[index] + 7) % 8
        }

        return byIndex.entries
            .filter { (index, frame) -> ArrowAlignData.getClicks(rotations[index], targetFor(index)) > 0 && inRange(frame.boundingBox.center) }
            .minByOrNull { (_, frame) -> frame.distanceToSqr(mc.player !!) }
            ?.let { (index, frame) -> frame to index }
    }

    private fun clickFrame(frame: ItemFrame, index: Int) {
        val current = arrowPredicted[index]?.takeIf { System.currentTimeMillis() - it.second < 1000 }?.first ?: frame.rotation
        interactEntity(frame)
        arrowPredicted[index] = (current + 1) % 8 to System.currentTimeMillis()
    }

    private fun interactEntity(entity: Entity) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        lastClick = System.currentTimeMillis()
        lastClickWasLever = false
        gameMode.interact(player, entity, EntityHitResult(entity, entity.boundingBox.center), InteractionHand.MAIN_HAND)
        //? if <26.3 {
        player.swing(InteractionHand.MAIN_HAND)
        //?} else {
        /*player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)
        *///?}
    }

    // The closest block in range that matches
    private fun nearestBlock(match: (BlockPos, BlockState) -> Boolean): BlockPos? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val eye = player.eyePosition
        val r = ceil(range.value).toInt()
        val origin = BlockPos.containing(eye)
        return BlockPos.betweenClosed(origin.offset(- r, - r, - r), origin.offset(r, r, r))
            .asSequence()
            .filter { match(it, level.getBlockState(it)) && inRange(Vec3.atCenterOf(it)) }
            .map { it.immutable() }
            .minByOrNull { Vec3.atCenterOf(it).distanceToSqr(eye) }
    }

    private class Aim(val rotation: MathUtils.Rotation, var ticksLeft: Int, val action: () -> Unit)

    private fun requestClick(pos: BlockPos, afterClick: () -> Unit = {}) = request(Vec3.atCenterOf(pos)) {
        click(pos)
        afterClick()
    }

    // Does it right away, or with Server Rotation first turns the head the server sees onto the target
    private fun request(target: Vec3, action: () -> Unit) {
        val player = mc.player ?: return
        if (! serverRotate.value) return action()
        // block the next pick until this one is done
        lastClick = System.currentTimeMillis()
        aim = Aim(rotationTo(player.eyePosition, target, player.yRot), rotationTicks.value, action)
        // the first step goes out with this tick already
        stepRotation()
    }

    /**
     * Moves the server rotation one step, every tick. The position packet with the step goes out at the end of the tick,
     * so the click comes the tick after the last step, and then it turns back to where you really look the same way.
     * Returns true while it's still turning onto a block.
     */
    private fun stepRotation(): Boolean {
        val player = mc.player ?: return false
        val real = MathUtils.Rotation(player.yRot, player.xRot)
        val current = aim

        if (current != null) {
            if (current.ticksLeft <= 0) {
                aim = null
                current.action()
                ticksBack = rotationTicks.value
                return false
            }
            serverRotation = lerpRotation(serverRotation ?: real, current.rotation, 1f / current.ticksLeft)
            current.ticksLeft --
            return true
        }

        val sent = serverRotation ?: return false
        if (ticksBack <= 0) {
            serverRotation = null
            return false
        }
        serverRotation = lerpRotation(sent, real, 1f / ticksBack)
        ticksBack --
        return false
    }

    private fun stopRotating() {
        aim = null
        serverRotation = null
        ticksBack = 0
    }

    private fun rotationTo(eye: Vec3, target: Vec3, nearYaw: Float): MathUtils.Rotation {
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = - Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        // the shortest way around from where you look
        return MathUtils.Rotation(nearYaw + Mth.wrapDegrees(yaw - nearYaw), pitch)
    }

    private fun lerpRotation(from: MathUtils.Rotation, to: MathUtils.Rotation, t: Float) = MathUtils.Rotation(
        from.yaw + Mth.wrapDegrees(to.yaw - from.yaw) * t,
        from.pitch + (to.pitch - from.pitch) * t
    )

    private fun click(pos: BlockPos) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val eye = player.eyePosition
        val center = Vec3.atCenterOf(pos)
        // the face that looks at you, like a normal click would hit
        val face = Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z)

        lastClick = System.currentTimeMillis()
        lastClickWasLever = mc.level?.getBlockState(pos)?.`is`(Blocks.LEVER) == true
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, BlockHitResult(center, face, pos, false))
        //? if <26.3 {
        player.swing(InteractionHand.MAIN_HAND)
        //?} else {
        /*player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)
        *///?}
    }

    // Quiz, the same way NoammAddons' QuizSolver reads it: the question matches a list of answers,
    // and the ⓐ ⓑ ⓒ line with one of them is the right one
    private fun onQuizChat(message: String) {
        if (! LocationUtils.inDungeon) return
        val trimmed = message.trim()

        when {
            "I am Oruo the Omniscient. I have lived many lives." in message -> newQuizQuestion(220)
            "2 questions left... Then you will have proven your worth to me!" in message -> newQuizQuestion(100)
            "One more question!" in message -> newQuizQuestion(100)
            message.startsWith("[STATUE] Oruo the Omniscient: ") && "answered the final question" in message -> resetQuiz()

            trimmed.firstOrNull() in quizLetters -> {
                val option = quizLetters.indexOf(trimmed.first())
                quizOptionsSeen = maxOf(quizOptionsSeen, option + 1)
                if (quizAnswers?.any { message.endsWith(it) } == true) quizCorrect = option
            }

            trimmed == "What SkyBlock year is it?" -> {
                val year = (((System.currentTimeMillis() / 1000) - 1560276000) / 446400).toInt() + 1
                quizAnswers = listOf("Year $year")
            }

            else -> quizSolutions?.entries?.find { message.contains(it.key) }?.value?.let { quizAnswers = it }
        }
    }

    private fun newQuizQuestion(ticksUntilAnswer: Int) {
        quizAnswers = null
        quizCorrect = null
        quizOptionsSeen = 0
        quizTries = 0
        quizAnswerTick = DungeonListener.currentTime + ticksUntilAnswer
    }

    private fun resetQuiz() {
        newQuizQuestion(0)
        quizAnswerTick = Long.MAX_VALUE
    }

    // The button of the right answer, once all three answers are out and the timer is done.
    // Quiz is the only room where buttons get clicked.
    private fun quizButton(): BlockPos? {
        // part of the puzzle solutions
        if (! puzzleChests.value) return null
        val option = quizCorrect ?: return null
        if (quizOptionsSeen < 3 || DungeonListener.currentTime < quizAnswerTick) return null
        // a click that didn't count gets another go, the next question resets this
        if (quizTries >= 3 || System.currentTimeMillis() - quizClickedAt < 3000) return null

        val room = ScanUtils.currentRoom?.takeIf { it.name == "Quiz" } ?: return null
        val rotation = 360 - (room.rotation ?: return null)
        val answerBlock = ScanUtils.getRealCoord(quizBlocks[option], room.centerPos, rotation)
        val level = mc.level ?: return null

        val button = (listOf(answerBlock) + Direction.entries.map { answerBlock.relative(it) })
            .firstOrNull { level.getBlockState(it).block is ButtonBlock } ?: return null
        if (! inRange(Vec3.atCenterOf(button))) return null

        quizTries ++
        quizClickedAt = System.currentTimeMillis()
        return button
    }

    override fun onDisable() {
        super.onDisable()
        clicked.clear()
        chestClicked = null
        stopRotating()
    }

    // The closest secret in range that we haven't clicked yet
    private fun findSecret(): BlockPos? {
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val maxRange = maxOf(range.value, headRange.value)
        val r = ceil(maxRange).toInt()
        val maxSq = maxRange * maxRange
        val origin = BlockPos.containing(eye)
        val now = System.currentTimeMillis()

        return BlockPos.betweenClosed(origin.offset(- r, - r, - r), origin.offset(r, r, r))
            .asSequence()
            .filter { Vec3.atCenterOf(it).distanceToSqr(eye) <= maxSq }
            .filter { it !in clicked && (retryAt[it] ?: 0L) <= now && wanted(it) }
            .map { it.immutable() }
            .filter { inRange(Vec3.atCenterOf(it), if (isHead(it)) headRange.value else range.value) }
            .filter(::allowedInRoom)
            .minByOrNull { Vec3.atCenterOf(it).distanceToSqr(eye) }
    }

    // In puzzles: the room's own secrets any time (like Tic Tac Toe's side chest), Boulder's chest any time,
    // and the reward chest once the puzzle is done. Three Weirdos' chests are the puzzle itself,
    // and Water Board's levers move the water.
    private fun allowedInRoom(pos: BlockPos): Boolean {
        val room = ScanUtils.getRoomFromPos(Vec3.atCenterOf(pos)) ?: return true
        if (room.data.type != RoomType.PUZZLE) return true
        if (room.name == "Three Weirdos") return false
        if (room.secretCoords.values.any { pos in it }) return true
        if (! puzzleChests.value) return false
        if (mc.level?.getBlockState(pos)?.`is`(Blocks.CHEST) != true) return false
        // Water Board and Ice Path only get their ✔ once the chest is opened, so they're checked by their blocks
        return when (room.name) {
            "Boulder" -> true
            "Ice Fill" -> iceFillDone || puzzleDone(room)
            "Water Board" -> waterBoardOpen(room)
            "Ice Path" -> icePathOpen(pos)
            "Teleport Maze" -> tpMazeOpen(room)
            else -> puzzleDone(room)
        }
    }

    private fun puzzleDone(room: UniqueRoom): Boolean {
        val puzzleState = Puzzle.fromName(room.name)?.state
        return puzzleState == RoomState.GREEN || puzzleState == RoomState.CLEARED ||
            room.mainRoom.state == RoomState.GREEN || room.mainRoom.state == RoomState.CLEARED
    }

    // The five wool gates in front of the chest, the same ones NoammAddons' WaterBoardSolver reads
    private fun waterBoardOpen(room: UniqueRoom): Boolean {
        val rotation = 360 - (room.rotation ?: return false)
        val level = mc.level ?: return false
        return (0 .. 4).none {
            level.getBlockState(ScanUtils.getRealCoord(BlockPos(0, 56, it), room.centerPos, rotation)).`is`(BlockTags.WOOL)
        }
    }

    // The maze is 3x3 cells of 8 blocks around the middle of the room, the end with the chest is the middle cell.
    // It's walled off, the right pad (the green one) is the only way in. Once you were in there it stays open.
    private fun tpMazeOpen(room: UniqueRoom): Boolean {
        if (room.centerPos in tpMazesDone) return true
        val player = mc.player ?: return false
        val dx = abs(player.x - (room.centerPos.x + 0.5))
        val dz = abs(player.z - (room.centerPos.z + 0.5))
        if (dx > 4.5 || dz > 4.5) return false
        tpMazesDone.add(room.centerPos)
        return true
    }

    // A wall of normal ice in front of the chest (3 out, 1 up) melts once the silverfish is through.
    // The floor is packed ice, so only normal ice counts.
    private fun icePathOpen(chest: BlockPos): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(chest)
        if (! state.hasProperty(ChestBlock.FACING)) return false
        val front = state.getValue(ChestBlock.FACING)
        val side = front.clockWise

        for (out in 1 .. 4) for (across in - 2 .. 2) for (up in 0 .. 3) {
            val pos = chest.relative(front, out).relative(side, across).above(up)
            if (level.getBlockState(pos).`is`(Blocks.ICE)) return false
        }
        return true
    }

    private fun wanted(pos: BlockPos): Boolean {
        val block = mc.level?.getBlockState(pos)?.block ?: return false
        return when (block) {
            Blocks.CHEST, Blocks.TRAPPED_CHEST -> chests.value
            Blocks.LEVER -> levers.value && leverReady()
            // in clear they're only there to put the redstone key on, the key itself is kept by the server
            Blocks.REDSTONE_BLOCK -> redstoneBlocks.value
            Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM -> mushrooms.value
            is SkullBlock, is WallSkullBlock -> {
                if (! DungeonUtils.isSecret(pos)) return false
                // a head does nothing when clicked, so the click goes on to the item: wither impact or a teleport
                if (noHeadsWithAbility.value && holdingAbilityItem()) return false
                val id = (mc.level?.getBlockEntity(pos) as? net.minecraft.world.level.block.entity.SkullBlockEntity)
                    ?.ownerProfile?.partialProfile()?.id.toString()
                if (id in DungeonUtils.WITHER_ESSENCE) essence.value else redstoneKey.value
            }
            else -> false
        }
    }

    private fun isHead(pos: BlockPos) = mc.level?.getBlockState(pos)?.block.let { it is SkullBlock || it is WallSkullBlock }

    // the Triggerbot uses it too
    internal fun holdingAbilityItem() = mc.player?.mainHandItem?.skyblockId in abilityItems

    private fun breakBlock(pos: BlockPos) {
        val player = mc.player ?: return
        val gameMode = mc.gameMode ?: return
        val eye = player.eyePosition
        val center = Vec3.atCenterOf(pos)
        lastClick = System.currentTimeMillis()
        lastClickWasLever = false
        gameMode.startDestroyBlock(pos, Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z))
        //? if <26.3 {
        player.swing(InteractionHand.MAIN_HAND)
        //?} else {
        /*player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)
        *///?}
    }

    // A chest that didn't open (still locked, or the click got lost) gets tried again a bit later,
    // the other secrets in range go first meanwhile
    private fun checkOurChest() {
        val pos = chestClicked ?: return
        // reward chests don't all say "Chest", any menu counts
        if (mc.gui.screen() is AbstractContainerScreen<*> && mc.gui.screen() !is InventoryScreen) {
            chestClicked = null
            if (closeChests.value) mc.player?.closeContainer()
            return
        }
        if (System.currentTimeMillis() - lastClick < 2000) return
        chestClicked = null
        stopRotating()

        val tries = (failedTries[pos] ?: 0) + 1
        failedTries[pos] = tries
        // probably already looted, stop clicking it
        if (tries >= 3) return
        clicked.remove(pos)
        retryAt[pos] = System.currentTimeMillis() + 3000
    }
}
