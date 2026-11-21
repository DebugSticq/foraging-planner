package dev.foragingplanner.mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the logs of Galatea trees, both as real blocks and as block display entities, and works out
 * which logs one axe hit cuts. Only reads the world; never sends input.
 */
public final class LogScan {
	private LogScan() {}

	/** Tree kind of a block: fig, mangrove or helix, or null for anything else. */
	public static String treeOf(BlockState state) {
		if (state == null) return null;
		if (state.is(Blocks.STRIPPED_BIRCH_WOOD) || state.is(Blocks.STRIPPED_BIRCH_LOG)
				|| state.is(Blocks.STRIPPED_MANGROVE_WOOD) || state.is(Blocks.STRIPPED_MANGROVE_LOG)) return "helix";
		if (state.is(Blocks.STRIPPED_SPRUCE_WOOD) || state.is(Blocks.STRIPPED_SPRUCE_LOG)) return "fig";
		if (state.is(Blocks.MANGROVE_WOOD) || state.is(Blocks.MANGROVE_LOG)
				|| state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS)) return "mangrove";
		return null;
	}

	/**
	 * Which logs one hit can spread through. Helix trees are cut one colour at a time and mangrove
	 * roots are a separate step, so those are their own groups.
	 */
	public static String group(BlockState state) {
		if (state.is(Blocks.STRIPPED_BIRCH_WOOD) || state.is(Blocks.STRIPPED_BIRCH_LOG)) return "beige";
		if (state.is(Blocks.STRIPPED_MANGROVE_WOOD) || state.is(Blocks.STRIPPED_MANGROVE_LOG)) return "red";
		if (state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS)) return "roots";
		String tree = treeOf(state);
		return tree == null ? null : tree;
	}

	public static boolean vertical(BlockState state) {
		return !state.hasProperty(RotatedPillarBlock.AXIS) || state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
	}

	/** All tree logs in a box around {@code center}: real blocks first, display entities fill gaps. */
	public static Map<BlockPos, BlockState> around(Minecraft client, BlockPos center, int side, int down, int up) {
		Map<BlockPos, BlockState> out = new HashMap<>();
		if (client.level == null) return out;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -side; x <= side; x++) {
			for (int z = -side; z <= side; z++) {
				for (int y = -down; y <= up; y++) {
					pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					BlockState state = client.level.getBlockState(pos);
					if (treeOf(state) != null) out.put(pos.immutable(), state);
				}
			}
		}
		var box = new net.minecraft.world.phys.AABB(center).inflate(side + 1, Math.max(down, up) + 1, side + 1);
		for (var entity : client.level.getEntitiesOfClass(Display.BlockDisplay.class, box)) {
			var render = entity.blockRenderState();
			if (render == null || treeOf(render.blockState()) == null) continue;
			out.putIfAbsent(BlockPos.containing(entity.position().add(0.5, 0.5, 0.5)), render.blockState());
		}
		return out;
	}

	/** Number of tree logs (real blocks only) in a box, for telling whether a tree is standing. */
	public static int count(Minecraft client, int cx, int cy, int cz, int side, int down, int up) {
		if (client.level == null) return 0;
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -side; x <= side; x++) {
			for (int z = -side; z <= side; z++) {
				for (int y = -down; y <= up; y++) {
					pos.set(cx + x, cy + y, cz + z);
					if (treeOf(client.level.getBlockState(pos)) != null) n++;
				}
			}
		}
		return n;
	}

	/** The log under the crosshair, as a block or as a display entity, or null. */
	public static BlockPos aimed(Minecraft client, Map<BlockPos, BlockState> logs, double reach) {
		if (client.player == null || client.level == null) return null;
		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && logs.containsKey(hit.getBlockPos())) {
			return hit.getBlockPos();
		}
		Vec3 eye = client.player.getEyePosition();
		Vec3 look = client.player.getLookAngle();
		for (double t = 0; t <= reach; t += 0.05) {
			BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
			if (logs.containsKey(pos)) return pos;
			if (client.level.getBlockState(pos).isSolidRender() && !logs.containsKey(pos)) return null;
		}
		return null;
	}

	/**
	 * Logs one hit on {@code start} cuts, nearest first, up to {@code max}. Spreads to all 26
	 * neighbours, but only through logs of the same group as the start log.
	 */
	public static List<BlockPos> sweep(Map<BlockPos, BlockState> logs, BlockPos start, int max) {
		List<BlockPos> out = new ArrayList<>();
		BlockState first = logs.get(start);
		if (first == null || max <= 0) return out;
		String group = group(first);
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		queue.add(start);
		seen.add(start);
		while (!queue.isEmpty() && out.size() < max) {
			BlockPos pos = queue.poll();
			out.add(pos);
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dy == 0 && dz == 0) continue;
				BlockPos next = pos.offset(dx, dy, dz);
				if (seen.contains(next)) continue;
				BlockState state = logs.get(next);
				if (state == null || !group.equals(group(state))) continue;
				seen.add(next);
				queue.add(next);
			}
		}
		return out;
	}

	/** All logs of the tree that {@code start} belongs to (any group of the same tree kind). */
	public static Map<BlockPos, BlockState> tree(Map<BlockPos, BlockState> logs, BlockPos start, int cap) {
		Map<BlockPos, BlockState> out = new HashMap<>();
		BlockState first = logs.get(start);
		if (first == null) return out;
		String kind = treeOf(first);
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		out.put(start, first);
		while (!queue.isEmpty() && out.size() < cap) {
			BlockPos pos = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos next = pos.offset(dx, dy, dz);
				if (out.containsKey(next)) continue;
				BlockState state = logs.get(next);
				if (state == null || !kind.equals(treeOf(state))) continue;
				out.put(next, state);
				queue.add(next);
			}
		}
		return out;
	}
}
