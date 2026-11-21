package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.PerkMath;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeMath;

/**
 * The cheapest way to a number of logs per hit on a tree: how much Sweep is missing, then the
 * steps that bring it for the fewest coins (gear, reforges, enchants, attribute shards) and,
 * separately, for the fewest Whispers (Heart of the Forest perks).
 */
public final class Roadmap {
	private Roadmap() {}

	/** One step; {@code coins} or {@code whispers} is what it costs. */
	public record Step(String icon, String name, String detail, double sweep, double coins, double whispers, double totalSweep, double total) {}

	public record Result(GameData.Tree tree, int targetLogs, double currentSweep, double neededSweep,
						 List<Step> coinSteps, boolean coinsReach, List<Step> whisperSteps, boolean whispersReach) {}

	/** Sweep needed on {@code tree} for {@code logs} logs per hit; -1 when out of the tree's range. */
	public static double sweepFor(GameData.Tree tree, int logs) {
		int index = logs - 1;
		if (index < 0 || index >= tree.sweepBreakpoints.size()) return -1;
		return tree.sweepBreakpoints.get(index);
	}

	public static int maxLogs(GameData.Tree tree) {
		return tree.sweepBreakpoints.size();
	}

	private record Option(String key, String group, String icon, String name, String detail, double sweep, double cost) {}

	public static Result plan(GameData.Tree tree, int targetLogs) {
		PlayerStats stats = SkyBlockReader.stats();
		double sweep = stats.sweep();
		double target = sweepFor(tree, targetLogs);
		double needed = target < 0 ? -1 : Math.max(0, target - sweep);

		// Coins: one item per slot (the best Sweep per coin), each upgrade, each next shard level.
		List<Option> coinOptions = new ArrayList<>();
		Map<String, GearData.Item> now = GearAdvisor.current();
		Map<String, Option> bestPerSlot = new LinkedHashMap<>();
		boolean davidSlot = false;
		for (GearData.Item item : GearData.get().items) if (item.best) davidSlot = true;
		for (GearData.Item item : GearData.get().items) {
			GearData.Item worn = now.get(item.slot);
			if (worn != null && worn.id.equals(item.id)) continue;
			if (davidSlot && "cloak".equals(item.slot) && (!item.best || (worn != null && worn.best))) continue;
			double s = GearAdvisor.gearDelta(Map.of(item.slot, item))[0];
			if (s <= 0.01) continue;
			Double price = item.free ? Double.valueOf(0) : GearAdvisor.price(item.id);
			if (price == null) continue;
			Option o = new Option("slot:" + item.slot, "gear", item.icon, item.name, Planner.tr("gear.slot." + item.slot), s, price);
			Option known = bestPerSlot.get(item.slot);
			if (known == null || ratio(o) > ratio(known)) bestPerSlot.put(item.slot, o);
		}
		coinOptions.addAll(bestPerSlot.values());
		for (UpgradeAdvisor.Option u : UpgradeAdvisor.options()) {
			if (u.done() || u.sweep() <= 0.01 || u.price() == null) continue;
			coinOptions.add(new Option("up:" + u.piece() + u.title(), "upgrade", u.icon(), u.title(), u.piece(), u.sweep(), u.price()));
		}

		List<Step> coinSteps = new ArrayList<>();
		double got = 0, spent = 0;
		// Shards go level by level, so they are offered one level at a time.
		Map<String, Integer> shardLevels = new HashMap<>();
		List<GearData.Shard> sweepShards = new ArrayList<>();
		for (GearData.Shard shard : GearData.get().shards) {
			if (!"sweep".equals(shard.stat) || !shard.onTree(tree.id)) continue;
			sweepShards.add(shard);
			shardLevels.put(shard.id, ShardAdvisor.level(shard));
		}
		Map<String, Long> boxLeft = new HashMap<>();
		for (GearData.Shard shard : sweepShards) boxLeft.put(shard.id, Progress.get().huntingBox.getOrDefault(shard.id, 0L));
		List<Option> pool = new ArrayList<>(coinOptions);
		for (int guard = 0; guard < 200 && needed > 0 && got < needed; guard++) {
			List<Option> candidates = new ArrayList<>(pool);
			for (GearData.Shard shard : sweepShards) {
				int level = shardLevels.get(shard.id);
				if (level >= 10) continue;
				Double price = GearAdvisor.bazaarPrice(shard.id);
				if (price == null) continue;
				long need = GearData.get().levelling(shard.rarity)[level];
				long fromBox = Math.min(need, boxLeft.get(shard.id));
				double per = ShardAdvisor.statPerLevel(shard, tree);
				if (per <= 0) continue;
				candidates.add(new Option("shard:" + shard.id, "shard", "amethyst_shard", shard.name + " · " + shard.ability,
						Planner.tr("gear.roadmap_level", level + 1, need - fromBox), per, price * (need - fromBox)));
			}
			Option best = null;
			for (Option o : candidates) if (best == null || ratio(o) > ratio(best)) best = o;
			if (best == null) break;
			got += best.sweep();
			spent += best.cost();
			coinSteps.add(new Step(best.icon(), best.name(), best.detail(), best.sweep(), best.cost(), 0, got, spent));
			if (best.key().startsWith("shard:")) {
				String id = best.key().substring(6);
				int level = shardLevels.get(id);
				for (GearData.Shard shard : sweepShards) {
					if (!shard.id.equals(id)) continue;
					long need = GearData.get().levelling(shard.rarity)[level];
					boxLeft.put(id, Math.max(0, boxLeft.get(id) - need));
				}
				shardLevels.put(id, level + 1);
			} else {
				pool.remove(best);
			}
		}

		// Whispers: perk levels with the most Sweep per Whisper, merged per perk.
		List<Step> whisperSteps = new ArrayList<>();
		Map<String, Integer> levels = new HashMap<>(Planner.config.perkLevels);
		double wGot = 0, wSpent = 0;
		PlayerStats current = stats;
		Map<String, double[]> merged = new LinkedHashMap<>();
		for (int guard = 0; guard < 600 && needed > 0 && wGot < needed; guard++) {
			GameData.Perk bestPerk = null;
			double bestRatio = 0, bestSweep = 0, bestCost = 0;
			for (GameData.Perk perk : Planner.data.hotf.perks) {
				int level = levels.getOrDefault(perk.name, 0);
				if (level >= perk.maxLevel || !perk.hasValuedEffects()) continue;
				PlayerStats after = HotfPlanner.advance(Planner.data, levels, current, perk, 1);
				double s = after.sweep() - current.sweep();
				if (s <= 0.001) continue;
				double cost = Math.max(1, PerkMath.costBetween(perk, level, level + 1));
				if (s / cost > bestRatio) {
					bestRatio = s / cost;
					bestPerk = perk;
					bestSweep = s;
					bestCost = cost;
				}
			}
			if (bestPerk == null) break;
			current = HotfPlanner.advance(Planner.data, levels, current, bestPerk, 1);
			levels.merge(bestPerk.name, 1, Integer::sum);
			wGot += bestSweep;
			wSpent += bestCost;
			double[] m = merged.computeIfAbsent(bestPerk.name, k -> new double[3]);
			m[0]++;
			m[1] += bestSweep;
			m[2] += bestCost;
		}
		double runSweep = 0, runCost = 0;
		for (var e : merged.entrySet()) {
			runSweep += e.getValue()[1];
			runCost += e.getValue()[2];
			whisperSteps.add(new Step("oak_sapling", e.getKey(), Planner.tr("gear.roadmap_perk_levels", (int) e.getValue()[0]),
					e.getValue()[1], 0, e.getValue()[2], runSweep, runCost));
		}
		return new Result(tree, targetLogs, sweep, needed, coinSteps, needed >= 0 && got >= needed, whisperSteps, needed >= 0 && wGot >= needed);
	}

	private static double ratio(Option o) {
		return o.cost() <= 0 ? o.sweep() * 1e9 : o.sweep() / o.cost();
	}

	/** Current logs per hit on a tree. */
	public static int currentLogs(GameData.Tree tree) {
		return TreeMath.logsPerHit(tree, SkyBlockReader.stats().sweep());
	}
}
