package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.Precision;
import dev.foragingplanner.mc.TreeAssistant;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public abstract class TreeDisplayMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$BlockDisplay;Lnet/minecraft/client/renderer/entity/state/BlockDisplayEntityRenderState;F)V", at = @At("RETURN"))
    private void foragingplanner$highlight(Display.BlockDisplay entity, BlockDisplayEntityRenderState state, float delta, CallbackInfo ci) {
        int color = Precision.color(entity);
        if (color == 0) color = TreeAssistant.color(entity);
        if (color != 0) state.outlineColor = color;
    }
}
