package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.Goal;
import dev.foragingplanner.core.TreeMath;

/**
 * Works out, for every foraging attribute shard, the current level, how many shards the next level
 * and level 10 still need (counting the ones waiting in the Hunting Box), what that costs on the
 * Bazaar and how much it improves the player's goal on the tree they farm.
 */
public final class ShardAdvisor {
	private ShardAdvisor() {}

	/** One attribute with everything the shard page shows. */
	public record Plan(GearData.Shard shard, int level, long inBox, long toNext, long toMax, Double price,
					   double statPerLevel, double gainNext, double gainMax, boolean onFarmedTree, boolean counted) {
		public boolean done() {
			return level >= 10;
		}

		public Double costToMax() {
			return price == null ? null : price * toMax;
		}

		public Double costToNext() {
			return price == null ? null : price * toNext;
		}

		/** Gain for the goal per million coins, to max; 0 when unknown. */
		public double perMillion() {
			Double cost = costToMax();
			if (cost == null || cost <= 0) return gainMax > 0 && toMax == 0 ? 0 : 0;
			return gainMax / (cost / 1e6);
		}
	}

	public static List<Plan> plans() {
		List<Plan> out = new ArrayList<>();
		GearData data = GearData.get();
		GameData.Tree farmed = Why.farmedTree();
		String treeId = farmed == null ? "" : farmed.id;
		for (GearData.Shard shard : data.shards) out.add(plan(data, shard, farmed, treeId));
		// Useful ones first (best value for money), then the rest by gain, finished ones last.
		out.sort(Comparator.comparing(Plan::done)
				.thenComparing((Plan p) -> !(p.gainMax() > 0.005))
				.thenComparing((Plan p) -> p.price() == null)
				.thenComparing(Comparator.comparingDouble(Plan::perMillion).reversed())
				.thenComparing(Comparator.comparingDouble(Plan::gainMax).reversed())
				.thenComparing(p -> priorityRank(p.shard().priority)));
		return out;
	}

	private static int priorityRank(String priority) {
		return switch (priority == null ? "" : priority) {
			case "high" -> 0;
			case "medium" -> 1;
			default -> 2;
		};
	}

	/** The attribute's level as read from the attribute menu; 0 when not unlocked or not read yet. */
	public static int level(GearData.Shard shard) {
		var levels = Progress.get().attributeLevels;
		for (String key : new String[] {shard.ability, shard.name, shard.name + " Shard"}) {
			Integer level = levels.get(key);
			if (level != null) return Math.clamp(level, 0, 10);
		}
		for (var e : levels.entrySet()) {
			if (e.getKey().equalsIgnoreCase(shard.ability)) return Math.clamp(e.getValue(), 0, 10);
		}
		return 0;
	}

	static Plan plan(GearData data, GearData.Shard shard, GameData.Tree farmed, String treeId) {
		int level = level(shard);
		long inBox = Progress.get().huntingBox.getOrDefault(shard.id, 0L);
		int[] steps = data.levelling(shard.rarity);
		long toNext = 0, toMax = 0;
		if (level < 10) {
			toNext = Math.max(0, steps[level] - inBox);
			long remaining = 0;
			for (int i = level; i < 10; i++) remaining += steps[i];
			toMax = Math.max(0, remaining - inBox);
		}
		Double price = GearAdvisor.bazaarPrice(shard.id);

		boolean onTree = farmed == null || shard.onTree(treeId);
		double perLevel = statPerLevel(shard, farmed);
		boolean counted = !"other".equals(shard.stat);
		double gainNext = 0, gainMax = 0;
		if (onTree && counted && level < 10) {
			gainNext = gain(shard, perLevel, 1, farmed);
			gainMax = gain(shard, perLevel, 10 - level, farmed);
		}
		return new Plan(shard, level, inBox, toNext, toMax, price, perLevel, gainNext, gainMax, onTree, counted);
	}

	/** What one level gives, with collection digits, day/night and pet conditions applied. */
	static double statPerLevel(GearData.Shard shard, GameData.Tree farmed) {
		double v = shard.perLevel;
		if (shard.perDigit) v *= digits(farmed);
		switch (shard.condition == null ? "" : shard.condition) {
			// Day and night each last about half the time.
			case "day", "night" -> v *= 0.5;
			case "frog" -> {
				GearData.Item pet = GearAdvisor.current().get("pet");
				if (pet == null || !pet.id.toUpperCase(Locale.ROOT).contains("FROG")) v = 0;
			}
			case "commons" -> v *= commonAttributes();
			default -> { }
		}
		return v;
	}

	/** Digits of the farmed tree's log collection ("Mangrove Log" 1,234,567 = 7), from /collections. */
	static int digits(GameData.Tree tree) {
		if (tree != null) {
			for (var c : Progress.get().collections.values()) {
				if (c.name != null && c.name.toLowerCase(Locale.ROOT).startsWith(tree.name.toLowerCase(Locale.ROOT)) && c.total > 0) {
					return Math.min(8, String.valueOf((long) Math.min(c.total, 10_000_000)).length());
				}
			}
		}
		return GearData.get().assumptions.mangroveDigits;
	}

	/** Unique Common attributes the player has, as far as the attribute menu showed. */
	static int commonAttributes() {
		int count = 0;
		for (var e : Progress.get().attributeLevels.entrySet()) if (e.getValue() > 0) count++;
		// Without a read attribute menu, assume a typical number.
		return count == 0 ? 20 : Math.max(1, (int) Math.round(count * 0.45));
	}

	/** Gain for the goal in percent from {@code levels} more levels. */
	static double gain(GearData.Shard shard, double perLevel, int levels, GameData.Tree farmed) {
		double amount = perLevel * levels;
		Goal goal = Planner.goal();
		return switch (shard.stat) {
			case "sweep" -> GearAdvisor.gainPercent(amount, 0, 0);
			case "fortune" -> GearAdvisor.gainPercent(0, amount, 0);
			case "wisdom" -> GearAdvisor.gainPercent(0, 0, amount);
			case "chop" -> {
				// An extra log with this chance on every hit, on top of the swept ones.
				if (farmed == null || goal == Goal.WHISPERS || goal == Goal.GIFTS || goal == Goal.SWEEP) yield 0;
				double logs = Math.max(0.1, TreeMath.smoothLogsPerHit(farmed, SkyBlockReader.stats().sweep()));
				yield Math.min(amount, 1.5) / logs * 100;
			}
			case "whispers" -> goal == Goal.WHISPERS ? amount : 0;
			case "gifts" -> goal == Goal.GIFTS ? amount : 0;
			default -> 0;
		};
	}
}
