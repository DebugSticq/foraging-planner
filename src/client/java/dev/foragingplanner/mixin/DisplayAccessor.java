package dev.foragingplanner.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the scale of a display entity, which tells flying log blocks apart from the tree itself. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3fc> foragingplanner$scaleId() {
        throw new AssertionError();
    }
}
