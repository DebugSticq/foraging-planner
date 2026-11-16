package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Sounds;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the mod mute some Galatea sounds and listen to the beacon's notes. */
@Mixin(SoundManager.class)
public abstract class SoundMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (Sounds.onPlay(sound)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    /** Far away sounds are played with a delay and do not pass through play(). */
    @Inject(method = "playDelayed(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$playDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (Sounds.onPlay(sound)) ci.cancel();
    }
}
