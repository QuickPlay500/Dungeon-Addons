package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.NoammAddons
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.WorldUtils
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.location.LocationUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.ToggleKeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Etherwarps you through the Ice Fill puzzle one block at a time with your AOTV.
 * Stand on the start of a puzzle and it goes, then on through the puzzles after it. The solver is the same one NoammAddons' IceFillSolver uses.
 */
object AutoIceFill: Feature(
    description = "Etherwarps you block by block through Ice Fill with your AOTV. Stand on the start of a puzzle to begin.",
    name = "Auto Ice Fill"
) {
    private val warpDelay by SliderSetting("Warp Delay", 50, 0, 500, 5, "ms")
        .withDescription("Time between two etherwarps.")
    private val rotationTime by SliderSetting("Rotation Time", 0, 0, 200, 1, "ms")
        .withDescription("Time (ms) to turn onto the next block. &eSet to 0 to snap onto it.")
    private val waitForLanding by ToggleSetting("Wait For Landing", false)
        .withDescription("Waits for the server to put you on each block before the next warp. Off sends the warps right away, each from where the last one lands, so server lag doesn't slow it down.")
    private val landTimeout by SliderSetting("Land Timeout", 3000, 200, 10000, 100, "ms")
        .withDescription("Stops when an etherwarp didn't land in this time.").showIf { waitForLanding.value }
    private val chainPuzzles by ToggleSetting("Chain Ice Fill", true)
        .withDescription("After a part, warps onto the start of the next one and keeps going until all 3 parts are done.")

    private val puzzles = CopyOnWriteArrayList<List<BlockPos>>()
    private val donePuzzles: MutableSet<List<BlockPos>> = ConcurrentHashMap.newKeySet()
    private var job: Job? = null
    // the puzzles get solved one after another, the last one (the biggest) takes the longest
    @Volatile private var solving = false

    /** The last block of part 3, where you stand once Ice Fill is done. Null until every part is solved. */
    val finalBlock: BlockPos? get() = if (solving) null else puzzles.lastOrNull()?.lastOrNull()

    override fun init() {
        register<DungeonEvent.RoomEvent.onEnter> { onRoom(event.room) }
        register<DungeonEvent.RoomEvent.RotationFound> { onRoom(event.room) }
        register<DungeonEvent.RoomEvent.onExit> { if (event.room.name == "Ice Fill") reset() }
        register<WorldChangeEvent> { reset() }

        register<TickEvent.Start> {
            if (job?.isActive == true) return@register
            if (mc.gui.screen() != null) return@register
            val player = mc.player ?: return@register
            if (! player.onGround()) return@register

            val feet = player.blockPosition()
            val path = puzzles.firstOrNull { it.first() == feet && it !in donePuzzles } ?: return@register
            val slot = (0 .. 8).firstOrNull { EtherwarpHelper.getEtherwarpDistance(player.inventory.getItem(it)) != null }
                ?: return@register ChatUtils.modMessage("§cAuto Ice Fill: no etherwarp item in your hotbar.").also { donePuzzles.add(path) }

            job = NoammAddons.scope.launch { warpThrough(path, slot) }
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
    }

    private fun onRoom(room: UniqueRoom) {
        if (room.name != "Ice Fill" || LocationUtils.inBoss) return
        val rotation = room.rotation ?: return
        if (solving || puzzles.isNotEmpty()) return
        solving = true
        NoammAddons.scope.launch {
            try {
                solve(room.centerPos, 360 - rotation)
            }
            finally {
                solving = false
            }
        }
    }

    // The puzzle after this one, waits for it while it's still being solved
    private suspend fun nextPuzzle(path: List<BlockPos>): List<BlockPos>? {
        val index = puzzles.indexOf(path)
        if (index == - 1) return null
        while (true) {
            puzzles.getOrNull(index + 1)?.let { return it.takeUnless { it in donePuzzles } }
            if (! solving) return null
            delay(50)
        }
    }

    private fun reset() {
        job?.cancel()
        job = null
        puzzles.clear()
        donePuzzles.clear()
    }

    // You stand on the start of the first puzzle, every later puzzle begins with a warp onto its start
    private suspend fun warpThrough(firstPath: List<BlockPos>, slot: Int) {
        donePuzzles.add(firstPath)

        val previousSlot = onMain { mc.player?.inventory?.selectedSlot } ?: return
        val sneakChanged = onMain {
            mc.player?.inventory?.selectedSlot = slot
            setSneak(true)
        }

        try {
            // the server has to see the sneak and the AOTV before the first warp
            delay(100)

            // where the server has (or will have) put you, the next warp goes from there
            var from = onMain { mc.player?.position() } ?: return

            var path = firstPath
            var targets = firstPath.drop(1)
            var index = 0
            var warps = 0

            while (true) {
                // the next puzzle gets looked up when we get there, it may not be solved yet when we start
                if (index >= targets.size) {
                    path = (if (chainPuzzles.value) nextPuzzle(path) else null) ?: break
                    targets = path
                    index = 0
                }
                val target = targets[index ++]

                if (warps ++ > 0 && warpDelay.value > 0) delay(warpDelay.value.toLong())
                // a puzzle only counts as done once we got to it, else standing on its start still starts it
                donePuzzles.add(path)

                // you stand in the air above the ice, the warp goes onto the ice itself
                val ice = target.below()
                val eyeHeight = onMain { mc.player?.eyeHeight?.toDouble() } ?: return
                val rotation = rotationTo(from.add(0.0, eyeHeight, 0.0), Vec3(ice.x + 0.5, ice.y + 1.0, ice.z + 0.5))
                if (rotationTime.value > 0) PlayerUtils.rotateSmoothly(rotation, rotationTime.value.toLong())

                val warpFrom = from
                val warped = onMain {
                    val player = mc.player ?: return@onMain false
                    val gameMode = mc.gameMode ?: return@onMain false
                    if (! enabled || mc.gui.screen() != null) return@onMain false
                    val distance = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return@onMain false

                    PlayerUtils.rotate(rotation.yaw, rotation.pitch)
                    // the use packet carries the rotation, so the server warps exactly where we checked
                    val look = MathUtils.getLookVec(player.yRot, player.xRot)
                    val ether = EtherwarpHelper.getEtherPos(warpFrom, look, distance)
                    if (! ether.succeeded || ether.pos != ice) return@onMain false

                    gameMode.useItem(player, InteractionHand.MAIN_HAND)
                    true
                }
                if (warped != true) return ChatUtils.modMessage("§cAuto Ice Fill: can't etherwarp onto the next block, stopped.")

                // etherwarp puts you in the middle of the block, just above it
                from = Vec3(ice.x + 0.5, ice.y + 1.05, ice.z + 0.5)

                if (! waitForLanding.value) continue
                val start = System.currentTimeMillis()
                while (onMain { mc.player?.blockPosition() } != target) {
                    if (System.currentTimeMillis() - start > landTimeout.value) return ChatUtils.modMessage("§cAuto Ice Fill: etherwarp didn't land, stopped.")
                    delay(10)
                }
                from = onMain { mc.player?.position() } ?: return
            }
        }
        finally {
            mc.submit(Runnable {
                if (sneakChanged == true) setSneak(false)
                mc.player?.inventory?.selectedSlot = previousSlot
            })
        }
    }

    private fun rotationTo(eye: Vec3, target: Vec3): MathUtils.Rotation {
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = - Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        return MathUtils.Rotation(yaw, pitch)
    }

    private fun <T> onMain(block: () -> T): T? {
        var result: T? = null
        mc.submit(Runnable { result = block() }).join()
        return result
    }

    // Returns whether the key had to change, so it only gets put back then
    private fun setSneak(down: Boolean): Boolean {
        val key = mc.options.keyShift
        if (key.isDown == down) return false
        // in toggle mode every press flips it
        if (key is ToggleKeyMapping && mc.options.toggleCrouch().get()) key.setDown(true)
        else key.setDown(down)
        return true
    }

    private fun solve(center: BlockPos, rotation: Int) {
        val checkpoints = listOf(
            ScanUtils.getRealCoord(BlockPos(0, 69, - 8), center, rotation),
            ScanUtils.getRealCoord(BlockPos(0, 70, - 3), center, rotation),
            ScanUtils.getRealCoord(BlockPos(0, 71, 4), center, rotation),
            ScanUtils.getRealCoord(BlockPos(0, 71, 11), center, rotation)
        )

        val allIceBlocks = mutableSetOf<BlockPos>()

        for (dx in - 22 .. 22) for (dz in - 22 .. 22) for (dy in 68 .. 73) {
            val pos = center.offset(dx, dy - center.y, dz)
            val state = WorldUtils.getStateAt(pos)

            if (! state.`is`(Blocks.ICE) && ! state.`is`(Blocks.PACKED_ICE)) continue
            if (! WorldUtils.getStateAt(pos.above()).isAir) continue

            allIceBlocks.add(pos.above())
        }

        if (allIceBlocks.isEmpty()) return

        val clusters = mutableListOf<MutableSet<BlockPos>>()
        val visited = mutableSetOf<BlockPos>()

        for (pos in allIceBlocks) {
            if (pos in visited) continue

            val cluster = mutableSetOf<BlockPos>()
            val queue = ArrayDeque<BlockPos>()
            queue.add(pos)
            visited.add(pos)

            while (! queue.isEmpty()) {
                val current = queue.removeFirst()
                cluster.add(current)
                for (dir in Direction.Plane.HORIZONTAL) {
                    val next = current.relative(dir)
                    if (next in allIceBlocks && next !in visited) {
                        visited.add(next)
                        queue.add(next)
                    }
                }
            }

            clusters.add(cluster)
        }

        clusters.sortBy { cluster -> cluster.minOf { it.distSqr(checkpoints[0]) } }

        for ((i, cluster) in clusters.withIndex()) {
            if (i >= 3) break

            val spaces = cluster.toHashSet()
            val start = spaces.minBy { it.distSqr(checkpoints[i]) }
            val end = spaces.minBy { it.distSqr(checkpoints[i + 1]) }

            val path = IceFillPuzzle(spaces, start, end).solve()

            if (path.isNotEmpty()) puzzles.add(path)
            else ChatUtils.modMessage("§cAuto Ice Fill: Failed to find path for Puzzle $i.")
        }
    }

    private class IceFillPuzzle(val spaces: HashSet<BlockPos>, val start: BlockPos, val end: BlockPos) {
        private val graph = spaces.associateWith { pos ->
            Direction.Plane.HORIZONTAL.mapNotNull { dir ->
                val next = pos.relative(dir)
                if (next in spaces) dir to next else null
            }
        }
        private val seen = HashSet<BlockPos>(spaces.size)
        private val stack = ArrayDeque<BlockPos>()

        private val NO_WALL_PENALTY = 5
        private val DOUBLE_TURN_PENALTY = 2
        private val TURN_PENALTY = 1

        private fun dirBetween(a: BlockPos, b: BlockPos) = when {
            b.x > a.x -> Direction.EAST
            b.x < a.x -> Direction.WEST
            b.z > a.z -> Direction.SOUTH
            else -> Direction.NORTH
        }

        private fun isBackedByWall(pos: BlockPos, dir: Direction) = pos.relative(dir) !in spaces

        private fun remainingConnected(current: BlockPos, visited: Set<BlockPos>): Boolean {
            val remaining = spaces.size - visited.size + 1
            if (remaining <= 1) return true

            seen.clear()
            stack.clear()
            stack.add(current)
            seen.add(current)

            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                var degree = 0
                for ((_, next) in graph.getValue(cur)) {
                    if (next == current || next !in visited) {
                        degree ++
                        if (seen.add(next)) stack.add(next)
                    }
                }
                if (cur != current && cur != end && degree < 2) return false
            }
            return seen.size == remaining
        }

        private fun pathCost(fullPath: List<BlockPos>): Int {
            if (fullPath.size < 3) return 0
            var total = 0
            var prevDir: Direction? = null
            var prevWasTurn = false
            for (i in 1 until fullPath.size) {
                val dir = dirBetween(fullPath[i - 1], fullPath[i])
                if (prevDir != null && dir != prevDir) {
                    total += TURN_PENALTY
                    if (! isBackedByWall(fullPath[i - 1], prevDir)) total += NO_WALL_PENALTY
                    if (prevWasTurn) total += DOUBLE_TURN_PENALTY
                    prevWasTurn = true
                }
                else prevWasTurn = false
                prevDir = dir
            }
            return total
        }

        private fun search(stopAtFirst: Boolean, costBound: Int): List<BlockPos>? {
            var bestCost = costBound
            var bestPath: List<BlockPos>? = null

            val visited = mutableSetOf(start)
            val tempPath = ArrayList<BlockPos>(spaces.size).apply { add(start) }

            fun dfs(current: BlockPos, lastDir: Direction?, lastWasTurn: Boolean, cost: Int): Boolean {
                if (cost >= bestCost) return false

                if (visited.size == spaces.size) {
                    if (current == end) {
                        bestCost = cost
                        bestPath = tempPath.toList()
                        return stopAtFirst
                    }
                    return false
                }
                if (current == end) return false

                val candidates = graph.getValue(current)
                    .filter { (_, pos) -> pos !in visited }
                    .sortedWith(
                        compareBy(
                            { (_, pos) -> graph.getValue(pos).count { (_, next) -> next !in visited } },
                            { (dir, _) -> if (dir == lastDir) 0 else 1 }
                        )
                    )

                for ((dir, nextPos) in candidates) {
                    var stepCost = cost
                    var turned = false
                    if (lastDir != null && dir != lastDir) {
                        turned = true
                        stepCost += TURN_PENALTY
                        if (! isBackedByWall(current, lastDir)) stepCost += NO_WALL_PENALTY
                        if (lastWasTurn) stepCost += DOUBLE_TURN_PENALTY
                    }
                    if (stepCost >= bestCost) continue

                    visited.add(nextPos)
                    tempPath.add(nextPos)

                    val stop = remainingConnected(nextPos, visited) && dfs(nextPos, dir, turned, stepCost)

                    tempPath.removeAt(tempPath.size - 1)
                    visited.remove(nextPos)

                    if (stop) return true
                }
                return false
            }

            dfs(start, null, false, 0)
            return bestPath
        }

        fun solve(): List<BlockPos> {
            if (start !in spaces || end !in spaces) return emptyList()
            val fallback = search(stopAtFirst = true, costBound = Int.MAX_VALUE) ?: return emptyList()
            return search(stopAtFirst = false, costBound = pathCost(fallback)) ?: fallback
        }
    }
}
