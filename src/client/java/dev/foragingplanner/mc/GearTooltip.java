package dev.foragingplanner.mc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * Adds a line to foraging gear and attribute shard tooltips: what the item does for the player's
 * goal, or the attribute level and how many shards are still missing.
 */
public final class GearTooltip {
	private record Cached(long atMs, String[] lines) {}

	private static final Map<String, Cached> CACHE = new HashMap<>();

	private GearTooltip() {}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!Planner.config.tooltipHints || !SkyBlockReader.inSkyBlock() || stack.isEmpty()) return;
			String id = GearAdvisor.itemId(stack);
			if (id.isEmpty()) return;
			long now = System.currentTimeMillis();
			Cached cached = CACHE.get(id);
			if (cached == null || now - cached.atMs() > 4_000) {
				cached = new Cached(now, build(id));
				CACHE.put(id, cached);
				if (CACHE.size() > 200) CACHE.clear();
			}
			if (cached.lines().length == 0) return;
			lines.add(Component.literal(""));
			for (String line : cached.lines()) lines.add(Component.literal(line));
		});
	}

	private static String[] build(String id) {
		GearData data = GearData.get();
		GearData.Item item = data.item(id);
		if (item != null) {
			GearData.Item worn = GearAdvisor.current().get(item.slot);
			if (worn != null && worn.id.equals(item.id)) return new String[] {"§a[FP] §7" + Planner.tr("tooltip.worn")};
			double[] d = GearAdvisor.gearDelta(Map.of(item.slot, item));
			double gain = GearAdvisor.gainPercent(d[0], d[1], d[2]);
			String goal = Planner.tr("option." + Planner.goal().id());
			if (gain > 0.05) {
				return new String[] {"§a[FP] §7" + Planner.tr("tooltip.gain", String.format(Locale.US, "%.1f", gain), goal),
						"§a[FP] §8" + Why.stats(d[0], d[1], d[2])};
			}
			return new String[] {"§a[FP] §8" + Planner.tr("tooltip.no_gain", goal)};
		}
		for (GearData.Shard shard : data.shards) {
			if (!shard.id.equals(id)) continue;
			ShardAdvisor.Plan p = null;
			for (ShardAdvisor.Plan plan : ShardAdvisor.plans()) if (plan.shard() == shard) p = plan;
			if (p == null) return new String[0];
			String first = "§a[FP] §7" + Planner.tr("tooltip.shard_level", p.level()) + (p.done() ? "" : " · " + Planner.tr("tooltip.shard_need", p.toNext(), p.toMax()));
			String second = p.gainMax() > 0.005 ? "§a[FP] §7" + Planner.tr("tooltip.shard_gain", String.format(Locale.US, "%.1f", p.gainMax()))
					: !p.onFarmedTree() && p.counted() ? "§a[FP] §8" + Planner.tr("tooltip.shard_other_tree") : null;
			return second == null ? new String[] {first} : new String[] {first, second};
		}
		return new String[0];
	}
}
