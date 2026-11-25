package com.github.noamm9.dungeonaddons.utils

import com.github.noamm9.utils.MathUtils.aabb
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.decoration.ItemFrame

/**
 * The F7/M7 arrow align device: the 5x5 item frame grid and every pattern it can have,
 * the same solver NoammAddons' ArrowAlign uses. Shared by the Triggerbot and Aura.
 */
object ArrowAlignData {
    val gridCorner = BlockPos(- 2, 120, 75)
    val gridBox = aabb(
        gridCorner.x, gridCorner.y, gridCorner.z,
        gridCorner.x + 1, gridCorner.y + 5, gridCorner.z + 5
    )

    fun ItemFrame.index(): Int? {
        val pos = blockPosition()
        if (pos.x != gridCorner.x) return null
        return ((pos.y - gridCorner.y) + (pos.z - gridCorner.z) * 5).takeIf { it in 0 .. 24 }
    }

    // The pattern that fits the arrows we know, -1 is an empty spot
    fun findSolution(rotations: IntArray) = possibleSolutions.firstOrNull { arr ->
        arr.indices.none { i -> (arr[i] == - 1 || rotations[i] == - 1) && arr[i] != rotations[i] }
    }

    /**
     * The arrow left one click short during pre dev: the bottom left one. You look west at the grid,
     * so the bottom row is y 120 and its left end is z 79 (index 20), going right to z 75 (index 0).
     */
    fun bottomLeftArrow(solution: List<Int>) = listOf(20, 15, 10, 5, 0).firstOrNull { solution[it] != - 1 }

    fun getClicks(currentRotation: Int, targetRotation: Int): Int {
        if (targetRotation == - 1) return 0
        return (8 - currentRotation + targetRotation) % 8
    }

    private val possibleSolutions = listOf(
        listOf(7, 7, - 1, - 1, - 1, 1, - 1, - 1, - 1, - 1, 1, 3, 3, 3, 3, - 1, - 1, - 1, - 1, 1, - 1, - 1, - 1, 7, 1),
        listOf(- 1, - 1, 7, 7, 5, - 1, 7, 1, - 1, 5, - 1, - 1, - 1, - 1, - 1, - 1, 7, 5, - 1, 1, - 1, - 1, 7, 7, 1),
        listOf(7, 7, - 1, - 1, - 1, 1, - 1, - 1, - 1, - 1, 1, 3, - 1, 7, 5, - 1, - 1, - 1, - 1, 5, - 1, - 1, - 1, 3, 3),
        listOf(5, 3, 3, 3, - 1, 5, - 1, - 1, - 1, - 1, 7, 7, - 1, - 1, - 1, 1, - 1, - 1, - 1, - 1, 1, 3, 3, 3, - 1),
        listOf(5, 3, 3, 3, 3, 5, - 1, - 1, - 1, 1, 7, 7, - 1, - 1, 1, - 1, - 1, - 1, - 1, 1, - 1, 7, 7, 7, 1),
        listOf(7, 7, 7, 7, - 1, 1, - 1, - 1, - 1, - 1, 1, 3, 3, 3, 3, - 1, - 1, - 1, - 1, 1, - 1, 7, 7, 7, 1),
        listOf(- 1, - 1, - 1, - 1, - 1, 1, - 1, 1, - 1, 1, 1, - 1, 1, - 1, 1, 1, - 1, 1, - 1, 1, - 1, - 1, - 1, - 1, - 1),
        listOf(- 1, - 1, - 1, - 1, - 1, 1, 3, 3, 3, 3, - 1, - 1, - 1, - 1, 1, 7, 7, 7, 7, 1, - 1, - 1, - 1, - 1, - 1),
        listOf(- 1, - 1, - 1, - 1, - 1, - 1, 1, - 1, 1, - 1, 7, 1, 7, 1, 3, 1, - 1, 1, - 1, 1, - 1, - 1, - 1, - 1, - 1)
    )
}
