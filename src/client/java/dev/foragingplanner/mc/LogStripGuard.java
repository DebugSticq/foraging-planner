package dev.foragingplanner.mc;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.RotatedPillarBlock;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

/** Stops a right click with an axe from stripping logs on SkyBlock, which only looks wrong on the client. */
public final class LogStripGuard {
	private LogStripGuard() {}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || !Planner.config.preventStripping || !SkyBlockReader.inSkyBlock()) return InteractionResult.PASS;
			if (!player.getItemInHand(hand).is(net.minecraft.tags.ItemTags.AXES)) return InteractionResult.PASS;
			var state = level.getBlockState(hit.getBlockPos());
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			boolean log = state.getBlock() instanceof RotatedPillarBlock && (id.endsWith("_log") || id.endsWith("_wood")) && !id.startsWith("stripped_");
			return log ? InteractionResult.FAIL : InteractionResult.PASS;
		});
	}
}
