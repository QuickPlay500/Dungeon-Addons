package com.github.noamm9.dungeonaddons.mixins;

import com.github.noamm9.dungeonaddons.features.impl.addons.Aura;
import com.github.noamm9.utils.MathUtils;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends Aura's rotation to the server instead of the real one.
 * It's only swapped in while the position packet gets built, so the camera never sees it.
 */
@Mixin(LocalPlayer.class)
public class MixinLocalPlayer {
    @Unique private float dungeonaddons$realYaw;
    @Unique private float dungeonaddons$realPitch;
    @Unique private boolean dungeonaddons$swapped;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void onSendPositionHead(CallbackInfo ci) {
        MathUtils.Rotation rotation = Aura.serverRotation;
        if (rotation == null) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        dungeonaddons$realYaw = self.getYRot();
        dungeonaddons$realPitch = self.getXRot();
        self.setYRot(rotation.getYaw());
        self.setXRot(rotation.getPitch());
        dungeonaddons$swapped = true;
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void onSendPositionReturn(CallbackInfo ci) {
        if (! dungeonaddons$swapped) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        self.setYRot(dungeonaddons$realYaw);
        self.setXRot(dungeonaddons$realPitch);
        dungeonaddons$swapped = false;
    }
}
