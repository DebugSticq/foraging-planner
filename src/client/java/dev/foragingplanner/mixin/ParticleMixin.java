package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Precision;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands particle packets to the Precision Cutting helper. Nothing is changed or cancelled. */
@Mixin(ClientPacketListener.class)
public abstract class ParticleMixin {
    @Inject(method = "handleParticleEvent", at = @At("HEAD"))
    private void foragingplanner$particle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        Precision.onParticle(packet);
    }
}
