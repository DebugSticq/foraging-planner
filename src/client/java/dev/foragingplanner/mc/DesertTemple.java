package dev.foragingplanner.mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Desert Temple on Torrhus Canyon: numbers the coloured buttons in the order to press them (fewest
 * glass blocks of a colour on the floor first) and shows where each statue has to be moved.
 * Only shows hints; never presses anything. Positions from Skyblocker (LGPL-3.0).
 */
public final class DesertTemple {
	private static final Map<DyeColor, BlockPos> BUTTONS = new EnumMap<>(Map.ofEntries(
			Map.entry(DyeColor.BROWN, new BlockPos(-611, 49, 232)),
			Map.entry(DyeColor.RED, new BlockPos(-612, 49, 232)),
			Map.entry(DyeColor.ORANGE, new BlockPos(-613, 49, 232)),
			Map.entry(DyeColor.YELLOW, new BlockPos(-614, 48, 232)),
			Map.entry(DyeColor.LIME, new BlockPos(-614, 47, 232)),
			Map.entry(DyeColor.GREEN, new BlockPos(-614, 46, 232)),
			Map.entry(DyeColor.CYAN, new BlockPos(-613, 45, 232)),
			Map.entry(DyeColor.LIGHT_BLUE, new BlockPos(-612, 45, 232)),
			Map.entry(DyeColor.BLUE, new BlockPos(-611, 45, 232)),
			Map.entry(DyeColor.PURPLE, new BlockPos(-610, 46, 232)),
			Map.entry(DyeColor.MAGENTA, new BlockPos(-610, 47, 232)),
			Map.entry(DyeColor.PINK, new BlockPos(-610, 48, 232))));
	private static final BlockPos FLOOR = new BlockPos(-612, 42, 227);
	private static final BlockPos WHEEL = new BlockPos(-612, 47, 233);
	private static final BlockPos CENTER = new BlockPos(-612, 46, 238);
	/** Statue → where it has to go (bird, snake, frog, fly). */
	private static final Map<BlockPos, BlockPos> STATUES = Map.of(
			new BlockPos(-618, 46, 248), new BlockPos(-616, 46, 248),
			new BlockPos(-607, 46, 249), new BlockPos(-607, 46, 247),
			new BlockPos(-606, 46, 240), new BlockPos(-608, 46, 240),
			new BlockPos(-617, 46, 241), new BlockPos(-617, 46, 239));

	private static List<DyeColor> order = List.of();
	private static int ticks;

	private DesertTemple() {}

	private static boolean near(Minecraft client) {
		return Planner.config.desertTemple && client.player != null && client.level != null && TorrhusScanner.here()
				&& client.player.position().distanceTo(Vec3.atCenterOf(CENTER)) < 48;
	}

	public static void tick(Minecraft client) {
		if (!near(client)) {
			order = List.of();
			return;
		}
		if (++ticks % 100 == 1) order = solve(client);
	}

	/** Colours of the glass floor, fewest blocks first. */
	private static List<DyeColor> solve(Minecraft client) {
		Map<DyeColor, Integer> count = new EnumMap<>(DyeColor.class);
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(FLOOR);
		seen.add(FLOOR);
		while (!queue.isEmpty() && seen.size() < 4000) {
			BlockPos pos = queue.poll();
			if (client.level.getBlockState(pos).getBlock() instanceof StainedGlassBlock glass) count.merge(glass.getColor(), 1, Integer::sum);
			for (BlockPos next : List.of(pos.north(), pos.east(), pos.south(), pos.west())) {
				if (seen.contains(next) || !(client.level.getBlockState(next).getBlock() instanceof StainedGlassBlock)) continue;
				seen.add(next);
				queue.add(next);
			}
		}
		List<DyeColor> list = new ArrayList<>(count.keySet());
		list.sort((a, b) -> Integer.compare(count.get(a), count.get(b)));
		return list;
	}

	/** Numbers and arrows in the world; called from the world overlay. */
	static void draw() {
		Minecraft client = Minecraft.getInstance();
		if (!near(client)) return;
		if (!order.isEmpty() && !client.level.getBlockState(WHEEL).isAir()) {
			for (int i = 0; i < order.size(); i++) {
				BlockPos pos = BUTTONS.get(order.get(i));
				if (pos == null) continue;
				WorldText.add(Vec3.atCenterOf(pos).add(0, 0, -0.6), String.valueOf(i + 1), 0xFFFFFFFF, 0xC0000000, 1.4f, true);
			}
		}
		for (var entry : STATUES.entrySet()) {
			Vec3 from = Vec3.atCenterOf(entry.getKey()), to = Vec3.atCenterOf(entry.getValue());
			WorldText.line(from, to, 0xFFFFFFFF, 4f);
			Vec3 dir = to.subtract(from).normalize();
			Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(0.3);
			Vec3 back = to.subtract(dir.scale(0.4));
			WorldText.line(to, back.add(side), 0xFFFFFFFF, 4f);
			WorldText.line(to, back.subtract(side), 0xFFFFFFFF, 4f);
			WorldText.line(to, back.add(0, 0.3, 0), 0xFFFFFFFF, 4f);
		}
	}
}
