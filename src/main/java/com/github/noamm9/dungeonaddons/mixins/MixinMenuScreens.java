package com.github.noamm9.dungeonaddons.mixins;

import com.github.noamm9.dungeonaddons.features.impl.addons.AutoTerminalWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
    Lets AutoTerminalWalk open a terminal menu without its screen
 */
@Mixin(MenuScreens.class)
public class MixinMenuScreens {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static <T extends AbstractContainerMenu> void onCreate(MenuType<T> type, Minecraft minecraft, int containerId, Component title, CallbackInfo ci) {
        if (AutoTerminalWalk.openHidden(type, minecraft, containerId, title)) ci.cancel();
    }
}
