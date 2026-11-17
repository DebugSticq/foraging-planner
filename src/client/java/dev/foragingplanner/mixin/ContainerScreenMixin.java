package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.HotfGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the custom /hotf menu instead of the chest, and sends its clicks to the real slots. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    private AbstractContainerScreen<?> foragingplanner$self() {
        return (AbstractContainerScreen<?>) (Object) this;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$render(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (HotfGui.active(foragingplanner$self())) {
            HotfGui.render(foragingplanner$self(), g, mouseX, mouseY);
            ci.cancel();
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void foragingplanner$renderBack(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (HotfGui.showingOriginal(foragingplanner$self())) HotfGui.renderBack(foragingplanner$self(), g, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$click(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = foragingplanner$self();
        if (HotfGui.showingOriginal(self) && HotfGui.clickBack(event.x(), event.y())) {
            cir.setReturnValue(true);
        } else if (HotfGui.active(self)) {
            cir.setReturnValue(HotfGui.click(self, event.x(), event.y(), event.button()));
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$release(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (HotfGui.active(foragingplanner$self())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void foragingplanner$drag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (HotfGui.active(foragingplanner$self())) cir.setReturnValue(true);
    }
}
