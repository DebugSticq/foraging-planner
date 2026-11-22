package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Quiet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the player see far under water on the foraging islands, when wanted. */
@Mixin(WaterFogEnvironment.class)
public abstract class WaterFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void foragingplanner$clearWater(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
        if (!Quiet.clearWater()) return;
        data.environmentalStart = Math.max(data.environmentalStart, 24);
        data.environmentalEnd = Math.max(data.environmentalEnd, 160);
        data.skyEnd = data.environmentalEnd;
        data.cloudEnd = data.environmentalEnd;
    }
}
