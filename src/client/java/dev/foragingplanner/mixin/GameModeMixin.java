package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Safeguard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Misclick protection: protected item drops and Huntrap clicks. */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeMixin {
    @Inject(method = "dropItem(Lnet/minecraft/client/player/LocalPlayer;Z)V", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$drop(LocalPlayer player, boolean all, CallbackInfo ci) {
        if (Safeguard.blockDrop(player.getMainHandItem())) ci.cancel();
    }

    @Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$attack(Player player, Entity target, CallbackInfo ci) {
        if (Safeguard.blockEntity(target)) ci.cancel();
    }

    @Inject(method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$interact(Player player, Entity target, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (Safeguard.blockEntity(target)) cir.setReturnValue(InteractionResult.FAIL);
    }
}
