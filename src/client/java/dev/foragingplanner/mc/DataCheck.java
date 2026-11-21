package dev.foragingplanner.mc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Checks the mod's own data (gear, shards, trees, translations) for mistakes. */
public final class DataCheck {
	private static final Set<String> STATS = Set.of("sweep", "fortune", "wisdom", "chop", "whispers", "gifts", "firstsweep", "other");
	private static List<String> cached;

	private DataCheck() {}

	/** All problems found, worded for the detection check; empty when everything is fine. */
	public static List<String> problems() {
		if (cached != null) return cached;
		List<String> out = new ArrayList<>(gear(GearData.get()));
		trees(out);
		translations(out);
		cached = out;
		if (!out.isEmpty()) Planner.LOGGER.warn("[FP] Data check found {} problem(s): {}", out.size(), out);
		return out;
	}

	public static void reset() {
		cached = null;
	}

	/** Problems in a gear file; also used before a downloaded file is trusted. */
	public static List<String> gear(GearData data) {
		List<String> out = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (GearData.Item item : data.items) {
			if (item.id == null || item.id.isBlank() || item.name == null) {
				out.add("gear: item without id or name");
				continue;
			}
			if (!ids.add(item.id)) out.add("gear: " + item.id + " twice");
			if (!GearAdvisor.SLOTS.contains(item.slot)) out.add("gear: " + item.id + " has unknown slot " + item.slot);
			if (!Double.isFinite(item.sweep + item.fortune + item.wisdom) || item.sweep < 0 || item.fortune < 0 || item.wisdom < 0) {
				out.add("gear: " + item.id + " has invalid stats");
			}
		}
		Set<String> treeIds = new HashSet<>();
		for (var tree : Planner.data.trees) treeIds.add(tree.id);
		Set<String> shardIds = new HashSet<>();
		for (GearData.Shard shard : data.shards) {
			if (shard.id == null || !shard.id.startsWith("SHARD_")) out.add("shards: bad id " + shard.id);
			else if (!shardIds.add(shard.id)) out.add("shards: " + shard.id + " twice");
			if (!GearData.LEVELLING.containsKey(shard.rarity)) out.add("shards: " + shard.id + " has unknown rarity " + shard.rarity);
			if (shard.stat != null && !STATS.contains(shard.stat)) out.add("shards: " + shard.id + " has unknown stat " + shard.stat);
			if (shard.perLevel < 0 || !Double.isFinite(shard.perLevel)) out.add("shards: " + shard.id + " has an invalid value per level");
			if (shard.tree != null && !shard.tree.isBlank()) {
				for (String t : shard.tree.split(",")) if (!treeIds.isEmpty() && !treeIds.contains(t.trim())) out.add("shards: " + shard.id + " names unknown tree " + t);
			}
		}
		return out;
	}

	private static void trees(List<String> out) {
		for (var tree : Planner.data.trees) {
			var points = tree.sweepBreakpoints;
			if (points == null || points.isEmpty()) {
				out.add("trees: " + tree.id + " has no Sweep breakpoints");
				continue;
			}
			for (int i = 1; i < points.size(); i++) {
				if (points.get(i) < points.get(i - 1)) {
					out.add("trees: " + tree.id + " breakpoints are not ascending");
					break;
				}
			}
		}
	}

	private static void translations(List<String> out) {
		JsonObject de = lang("de_de"), en = lang("en_us");
		if (de == null || en == null) return;
		int missingDe = 0, missingEn = 0;
		for (String key : en.keySet()) if (!de.has(key)) missingDe++;
		for (String key : de.keySet()) if (!en.has(key)) missingEn++;
		if (missingDe > 0) out.add("lang: " + missingDe + " texts missing in German");
		if (missingEn > 0) out.add("lang: " + missingEn + " texts missing in English");
	}

	private static JsonObject lang(String code) {
		try (InputStream in = DataCheck.class.getResourceAsStream("/assets/foragingplanner/lang/" + code + ".json")) {
			return in == null ? null : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}
}
