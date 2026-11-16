package dev.foragingplanner.mc;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import dev.foragingplanner.mixin.DisplayAccessor;

/**
 * Clean tree view: hides the small log blocks that fly off a tree while it is cut. They are block
 * displays that are scaled down and not rotated up or down, which the tree itself never is.
 */
public final class CleanTrees {
	private CleanTrees() {}

	private static boolean treeBlock(BlockState state) {
		return state.is(Blocks.STRIPPED_SPRUCE_WOOD) || state.is(Blocks.MANGROVE_WOOD) || state.is(Blocks.MANGROVE_LEAVES)
				|| state.is(Blocks.AZALEA_LEAVES) || state.is(Blocks.STRIPPED_BIRCH_WOOD) || state.is(Blocks.STRIPPED_MANGROVE_WOOD)
				|| state.is(Blocks.OAK_LEAVES);
	}

	public static boolean hide(Entity entity) {
		if (!Planner.config.cleanTrees || !(entity instanceof Display.BlockDisplay display)) return false;
		try {
			var render = display.blockRenderState();
			if (render == null || !treeBlock(render.blockState())) return false;
			if (!SkyBlockReader.inForagingArea()) return false;
			if (Math.abs(display.getLookAngle().y) > 1e-6) return false;
			var scale = display.getEntityData().get(DisplayAccessor.foragingplanner$scaleId());
			float s = scale.x();
			return s >= 0.3f && s < 0.98f && Math.abs(scale.x() - scale.y()) < 1e-4 && Math.abs(scale.x() - scale.z()) < 1e-4;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
