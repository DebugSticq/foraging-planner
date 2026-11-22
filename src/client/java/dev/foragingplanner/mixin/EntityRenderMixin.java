package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.CleanTrees;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skips drawing the flying log blocks of a tree being cut, when the player wants a clean view. */
@Mixin(EntityRenderer.class)
public abstract class EntityRenderMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$shouldRender(Entity entity, Frustum frustum, double x, double y, double z, float delta, CallbackInfoReturnable<Boolean> cir) {
        if (CleanTrees.hide(entity) || dev.foragingplanner.mc.Quiet.hide(entity)) cir.setReturnValue(false);
    }
}
