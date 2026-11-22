package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Quiet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pushes the air fog out past the render distance on the foraging islands, when wanted. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class FogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void foragingplanner$noFog(FogData data, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
        if (!Quiet.noFog()) return;
        data.environmentalStart = Float.MAX_VALUE / 4;
        data.environmentalEnd = Float.MAX_VALUE / 2;
    }
}
