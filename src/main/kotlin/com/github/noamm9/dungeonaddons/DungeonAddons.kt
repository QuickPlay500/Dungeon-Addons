package com.github.noamm9.dungeonaddons

import com.github.noamm9.NoammAddons
import net.fabricmc.api.ClientModInitializer

object DungeonAddons: ClientModInitializer {
    override fun onInitializeClient() {
        NoammAddons.logger.info("Hi from ${this.javaClass.simpleName}!")
    }
}