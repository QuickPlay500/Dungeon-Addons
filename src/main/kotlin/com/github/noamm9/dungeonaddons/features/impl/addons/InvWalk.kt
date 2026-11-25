package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.compat.KEYBOARD_TYPE
import com.github.noamm9.compat.isKeyDown
import com.github.noamm9.compat.isMouseDown
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.ToggleKeyMapping
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen

/**
 * Keeps WASD, jump and sneak working while an inventory is open.
 * Vanilla releases every key when a screen opens and sends key presses to the screen,
 * so the movement keys are read straight from the keyboard every tick instead.
 * The mouse stays with the inventory, it doesn't turn the camera.
 */
object InvWalk: Feature(
    description = "Walk, jump and sneak with your inventory open.",
    name = "Inv Walk"
) {
    private val containers by ToggleSetting("Containers")
        .withDescription("Also walk in chests and other menus, not only your own inventory.")
    private val disableSneak by ToggleSetting("Disable Sneak")

    private val movementKeys get() = with(mc.options) {
        listOfNotNull(keyUp, keyDown, keyLeft, keyRight, keyJump, keyShift.takeUnless { disableSneak.value })
    }

    private val lastDown = mutableMapOf<KeyMapping, Boolean>()
    private var driving = false

    override fun init() {
        register<TickEvent.Start> {
            if (! canWalk()) return@register stopDriving()
            driving = true

            // let go of a sneak held from before the setting got turned on
            val sneak = mc.options.keyShift
            if (disableSneak.value && sneak !is ToggleKeyMapping && sneak.isDown) sneak.setDown(false)

            movementKeys.forEach { key ->
                val down = key.isPhysicallyDown()
                // toggle keys flip on every press, so only hand them the edges
                if (key !is ToggleKeyMapping || lastDown[key] != down) key.setDown(down)
                lastDown[key] = down
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        stopDriving()
    }

    private fun canWalk(): Boolean {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        // creative search and anvil names take typing
        if (screen is CreativeModeInventoryScreen) return false
        if ((screen.focused as? EditBox)?.isFocused == true) return false
        return screen is InventoryScreen || containers.value
    }

    // Hands the keys back as they really are, so nothing stays held after the screen closes
    private fun stopDriving() {
        if (! driving) return
        driving = false
        lastDown.clear()
        if (mc.gui.screen() != null) KeyMapping.releaseAll()
        else movementKeys.forEach { if (it !is ToggleKeyMapping) it.setDown(it.isPhysicallyDown()) }
    }

    private fun KeyMapping.isPhysicallyDown(): Boolean {
        val key = KeyMappingHelper.getBoundKeyOf(this)
        if (key.value == InputConstants.UNKNOWN.value) return false
        return when (key.type) {
            KEYBOARD_TYPE -> isKeyDown(key.value)
            InputConstants.Type.MOUSE -> isMouseDown(key.value)
            else -> false
        }
    }
}
