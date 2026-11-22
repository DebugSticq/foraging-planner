package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

/**
 * Torrhus Canyon helpers, all from reading the world: learns Helix trees while you walk past them,
 * counts the beige and red logs left on each, marks Ruby Veilshrooms and full Honeyhives, and keeps
 * track of Helix trees smeared with honey and the critters honey brings.
 */
public final class TorrhusScanner {
	/** Critters that come to honeyed Helix trees. */
	static final List<String> HONEY_CRITTERS = List.of("Hivethief", "Parched", "Pollendart");

	public record Found(BlockPos pos, String name, int color) {}

	/** Beige and red logs left on one Helix tree. */
	record Colours(BlockPos base, int beige, int red, long seen) {}

	private static final Map<Long, Colours> COLOURS = new HashMap<>();
	private static volatile List<Found> found = List.of();
	/** Honeyed trees: position → [first seen, last seen, smeared by me (1/0)]. */
	private static final Map<Long, long[]> HONEY = new LinkedHashMap<>();
	private static final Map<Long, Vec3> HONEY_POS = new HashMap<>();
	private static final List<Found> CRITTERS = new ArrayList<>();
	private static int ticks;

	private TorrhusScanner() {}

	public static boolean here() {
		String area = SkyBlockReader.area();
		return area != null && area.toLowerCase(Locale.ROOT).contains("torrhus");
	}

	public static List<Found> found() {
		return found;
	}

	private static final java.util.regex.Pattern DROP = java.util.regex.Pattern.compile(
			"^(?:[A-Z ]+ )?DROP! (?:\\((\\d+)x\\) )?(.+?)(?: \\(\\+[\\d,]+% ✯ Magic Find\\))?!?$");

	public static void register() {
		// Drops on Torrhus Canyon ("RARE DROP! Bottled Mischief"), counted per item.
		ServerMessages.register((component, overlay) -> {
			if (overlay || !here()) return;
			String text = Planner.strip(component.getString()).trim();
			java.util.regex.Matcher m = DROP.matcher(text);
			if (!m.find()) return;
			long count = m.group(1) == null ? 1 : Long.parseLong(m.group(1));
			Progress.get().torrhusDrops.merge(m.group(2).trim(), count, Long::sum);
			Progress.save(false);
		});
		// Remember Helix trees the player smears with honey (right click with honey on a Helix log).
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || !here()) return InteractionResult.PASS;
			String held = Planner.strip(player.getItemInHand(hand).getHoverName().getString()).toLowerCase(Locale.ROOT);
			if (!held.contains("honey")) return InteractionResult.PASS;
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (!"helix".equals(LogScan.treeOf(state))) return InteractionResult.PASS;
			long now = System.currentTimeMillis();
			long k = column(hit.getBlockPos().getX() / 4, hit.getBlockPos().getZ() / 4);
			HONEY.put(k, new long[] {now, now, 1});
			HONEY_POS.put(k, Vec3.atCenterOf(hit.getBlockPos()));
			return InteractionResult.PASS;
		});
	}

	private static long column(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !here()) {
			if (!found.isEmpty()) {
				found = List.of();
				WorldBoxes.setTrees(List.of());
			}
			return;
		}
		ticks++;
		if (ticks % 20 == 0) Progress.get().torrhusSeconds++;
		if (ticks % 10 == 0) scanEntities(client);
		if (ticks % 40 == 0) scanTrees(client);
		if (ticks % 40 == 20) scanResources(client);
	}

	/** Learns Helix trees with both colours and counts the logs left on each. */
	private static void scanTrees(Minecraft client) {
		Map<BlockPos, BlockState> logs = LogScan.around(client, client.player.blockPosition(), 22, 10, 26);
		Set<BlockPos> seen = new HashSet<>();
		long now = System.currentTimeMillis();
		for (var entry : logs.entrySet()) {
			if (seen.contains(entry.getKey()) || !"helix".equals(LogScan.treeOf(entry.getValue()))) continue;
			Map<BlockPos, BlockState> tree = LogScan.tree(logs, entry.getKey(), 900);
			seen.addAll(tree.keySet());
			int beige = 0, red = 0;
			BlockPos base = entry.getKey();
			for (var log : tree.entrySet()) {
				if ("red".equals(LogScan.group(log.getValue()))) red++;
				else beige++;
				if (log.getKey().getY() < base.getY()) base = log.getKey();
			}
			if (tree.size() < 4) continue;
			if (Planner.config.helixLearn) KnownTrees.isTree(tree, "helix");
			COLOURS.put(column(base.getX() / 4, base.getZ() / 4), new Colours(base, beige, red, now));
		}
		COLOURS.values().removeIf(c -> now - c.seen() > 10 * 60_000L);
	}

	/** Beige and red logs left on the Helix tree at a spot, or null when unknown. */
	static Colours colours(double x, double z) {
		Colours best = null;
		double bestD = 7 * 7;
		for (Colours c : COLOURS.values()) {
			double d = (c.base().getX() + 0.5 - x) * (c.base().getX() + 0.5 - x) + (c.base().getZ() + 0.5 - z) * (c.base().getZ() + 0.5 - z);
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	/** Ruby Veilshrooms (crimson fungus) and full Honeyhives (bee nests with all honey) nearby. */
	private static void scanResources(Minecraft client) {
		if (!Planner.config.torrhusHighlights) {
			found = List.of();
			WorldBoxes.setTrees(List.of());
			return;
		}
		List<Found> list = new ArrayList<>();
		List<WorldBoxes.Box> boxes = new ArrayList<>();
		BlockPos center = client.player.blockPosition();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -26; x <= 26; x++) {
			for (int z = -26; z <= 26; z++) {
				for (int y = -10; y <= 14; y++) {
					pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					BlockState state = client.level.getBlockState(pos);
					if (state.is(Blocks.CRIMSON_FUNGUS)) {
						BlockPos p = pos.immutable();
						list.add(new Found(p, "Ruby Veilshroom", 0xFFE0443C));
						boxes.add(new WorldBoxes.Box(p, 0x30E0443C, 0xE0E0443C, 2f, -0.2f));
					} else if (state.is(Blocks.BEE_NEST) && state.hasProperty(BlockStateProperties.LEVEL_HONEY)
							&& state.getValue(BlockStateProperties.LEVEL_HONEY) >= 5
							&& !client.level.getBlockState(pos.above()).is(Blocks.BIRCH_FENCE)) {
						BlockPos p = pos.immutable();
						list.add(new Found(p, Planner.tr("torrhus.honeyhive_full"), 0xFFFFB347));
						boxes.add(new WorldBoxes.Box(p, 0x30FFB347, 0xE0FFB347, 2.5f, 0.01f));
					}
				}
			}
		}
		found = list;
		WorldBoxes.setTrees(boxes);
	}

	/** Honeyed Helix trees (their name tag) and the critters that honey brings. */
	private static void scanEntities(Minecraft client) {
		long now = System.currentTimeMillis();
		CRITTERS.clear();
		for (Entity e : client.level.getEntitiesOfClass(Entity.class, client.player.getBoundingBox().inflate(48))) {
			Component custom = e.getCustomName();
			if (custom == null) continue;
			String raw = Planner.strip(custom.getString()).trim();
			String lower = raw.toLowerCase(Locale.ROOT);
			if (lower.contains("honeyed")) {
				long k = column((int) Math.floor(e.getX()) / 4, (int) Math.floor(e.getZ()) / 4);
				long[] times = HONEY.computeIfAbsent(k, key -> new long[] {now, now, 0});
				times[1] = now;
				HONEY_POS.put(k, e.position());
				continue;
			}
			for (String critter : HONEY_CRITTERS) {
				if (lower.contains(critter.toLowerCase(Locale.ROOT))) {
					CRITTERS.add(new Found(e.blockPosition(), critter, 0xFFFFD34D));
					break;
				}
			}
		}
		HONEY.entrySet().removeIf(entry -> now - entry.getValue()[1] > 20 * 60_000L);
		HONEY_POS.keySet().retainAll(HONEY.keySet());
	}

	/** Labels in the world, called from the world overlay. */
	static void draw() {
		if (!here() || !Planner.config.honeyHelix) return;
		long now = System.currentTimeMillis();
		for (var entry : HONEY.entrySet()) {
			Vec3 pos = HONEY_POS.get(entry.getKey());
			if (pos == null) continue;
			long[] t = entry.getValue();
			String text = "🍯 " + Planner.tr("torrhus.honey", GearScreenFormat.duration((now - t[0]) / 1000))
					+ "\n§7" + Planner.tr("torrhus.honey_brings", String.join(", ", HONEY_CRITTERS));
			WorldText.label(pos.add(0, 2.6, 0), text, 0xFFFFB347);
		}
		for (Found c : CRITTERS) {
			WorldText.label(Vec3.atCenterOf(c.pos()).add(0, 1.6, 0), "★ " + c.name(), c.color());
			WorldText.cube(Vec3.atCenterOf(c.pos()), 0.6, 0x20FFD34D, 0xE0FFD34D, 2f);
		}
	}

	/** Helix trees known on this island (bundled, shared and learned). */
	static int knownHelix() {
		int n = 0;
		for (KnownTrees.Entry e : KnownTrees.forArea(SkyBlockReader.area())) if ("helix".equals(e.tree)) n++;
		return n;
	}

	static List<Found> critters() {
		return CRITTERS;
	}
}
