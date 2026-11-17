package dev.foragingplanner.mixin;

import dev.foragingplanner.mc.AxeSkins;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets the mod draw a different model for foraging axes (custom axe skins). */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelMixin {
    @Redirect(method = "appendItemLayers", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object foragingplanner$axeSkin(ItemStack stack, DataComponentType<?> type) {
        if (type == DataComponents.ITEM_MODEL) {
            Identifier skin = AxeSkins.override(stack);
            if (skin != null) return skin;
        }
        return stack.get(type);
    }
}
