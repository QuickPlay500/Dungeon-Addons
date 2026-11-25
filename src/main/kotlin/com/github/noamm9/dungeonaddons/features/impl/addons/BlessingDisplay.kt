package com.github.noamm9.dungeonaddons.features.impl.addons

import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ColorUtils.toColor
import com.github.noamm9.utils.dungeons.enums.Blessing
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.RenderHelper.width
import net.minecraft.ChatFormatting

/**
 * NoammAddons' BlessingDisplay, plus an option to count Time V as 2.5 power.
 * The blessing levels come from NoammAddons, which reads them from the tab list.
 */
object BlessingDisplay: Feature(
    description = "Displays the current active blessings in the dungeon.",
    name = "Blessing Display",
) {
    private val power by ToggleSetting("Power Blessing", true).section("Blessings")
    private val time by ToggleSetting("Time Blessing", true)
    private val wisdom by ToggleSetting("Wisdom Blessing", false)
    private val life by ToggleSetting("Life Blessing", false)
    private val stone by ToggleSetting("Stone Blessing", false)

    private val timeAsPower by ToggleSetting("Time As Power", false).section("Options")
        .withDescription("Adds time blessings to the power blessing instead of showing it on its own.")

    private val powerColor by ColorSetting("Power Color", ChatFormatting.DARK_RED.toColor()).showIf { power.value }.section("Colors")
    private val timeColor by ColorSetting("Time Color", ChatFormatting.DARK_PURPLE.toColor()).showIf { time.value && ! timeAsPower.value }
    private val wisdomColor by ColorSetting("Wisdom Color", ChatFormatting.AQUA.toColor()).showIf { wisdom.value }
    private val lifeColor by ColorSetting("Red Color", ChatFormatting.RED.toColor()).showIf { life.value }
    private val stoneColor by ColorSetting("Stone Color", ChatFormatting.GRAY.toColor()).showIf { stone.value }

    private fun getBlessingConfig(type: Blessing) = when (type) {
        Blessing.POWER -> power.value to powerColor.value
        Blessing.TIME -> (time.value && ! timeAsPower.value) to timeColor.value
        Blessing.STONE -> stone.value to stoneColor.value
        Blessing.LIFE -> life.value to lifeColor.value
        Blessing.WISDOM -> wisdom.value to wisdomColor.value
    }

    private fun getValue(blessing: Blessing, example: Boolean): Double {
        val value = if (example) 5 else blessing.current
        if (blessing != Blessing.POWER || ! timeAsPower.value) return value.toDouble()
        val timeValue = if (example) 5 else Blessing.TIME.current
        return value + timeValue / 2.0
    }

    private fun format(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    override fun init() {
        hudElement("NABlessingDisplay") { context, example ->
            var maxWidth = 0
            var currentY = 0

            Blessing.entries.forEach { blessing ->
                val (enabled, color) = getBlessingConfig(blessing)

                val value = getValue(blessing, example)
                if (! enabled || value <= 0) return@forEach

                val text = "${blessing.displayString} §f${format(value)}"

                context.drawString(text, 0, currentY, color)

                maxWidth = maxOf(maxWidth, text.width())
                currentY += 9
            }

            return@hudElement maxWidth to currentY
        }
    }
}
