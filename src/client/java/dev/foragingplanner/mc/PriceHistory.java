package dev.foragingplanner.mc;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Hourly Bazaar prices of foraging items (logs, shards, gear, essence) for the last seven days,
 * so a Bazaar menu can say whether a price is low or high right now.
 */
public final class PriceHistory {
	private static final int KEEP = 7 * 24;
	private static Map<String, List<double[]>> history;
	private static long lastHour = -1;
	private static int ticks;

	private PriceHistory() {}

	private static Path file() {
		return ModConfig.directory().resolve("price_history.json");
	}

	private static Map<String, List<double[]>> data() {
		if (history != null) return history;
		history = new HashMap<>();
		try {
			if (Files.exists(file())) {
				Type type = new TypeToken<Map<String, List<double[]>>>() {}.getType();
				Map<String, List<double[]>> read = new Gson().fromJson(Files.readString(file()), type);
				if (read != null) history = read;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read the price history", e);
		}
		return history;
	}

	/** Items worth watching. */
	static Set<String> watched() {
		Set<String> ids = new LinkedHashSet<>();
		for (var tree : Planner.data.trees) {
			ids.add(tree.bazaarId);
			ids.add("ENCHANTED_" + tree.bazaarId);
		}
		ids.add("ESSENCE_FOREST");
		for (GearData.Shard shard : GearData.get().shards) ids.add(shard.id);
		for (GearData.Item item : GearData.get().items) if (BazaarPrices.BUY.containsKey(item.id)) ids.add(item.id);
		return ids;
	}

	/** Called once a second: one sample per hour once prices are there. */
	public static void tick() {
		if (++ticks % 60 != 0 || BazaarPrices.BUY.isEmpty()) return;
		long hour = System.currentTimeMillis() / 3_600_000L;
		if (hour == lastHour) return;
		lastHour = hour;
		Map<String, List<double[]>> data = data();
		for (String id : watched()) {
			Double buy = BazaarPrices.BUY.get(id);
			Double sell = Planner.PRICES.get(id);
			if (buy == null && sell == null) continue;
			List<double[]> list = data.computeIfAbsent(id, k -> new ArrayList<>());
			if (!list.isEmpty() && list.get(list.size() - 1)[0] == hour) continue;
			list.add(new double[] {hour, buy == null ? 0 : buy, sell == null ? 0 : sell});
			while (list.size() > KEEP) list.remove(0);
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new Gson().toJson(data));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save the price history", e);
		}
	}

	/** Current buy and sell price against the seven-day average; null without enough samples. */
	public record Trend(double buy, double avgBuy, double sell, double avgSell, int samples) {
		public double buyPercent() {
			return avgBuy <= 0 ? 0 : (buy / avgBuy - 1) * 100;
		}

		public double sellPercent() {
			return avgSell <= 0 ? 0 : (sell / avgSell - 1) * 100;
		}
	}

	public static Trend trend(String id) {
		List<double[]> list = data().get(id);
		Double buy = BazaarPrices.BUY.get(id), sell = Planner.PRICES.get(id);
		if (list == null || list.size() < 6 || buy == null || sell == null) return null;
		double b = 0, s = 0;
		for (double[] e : list) {
			b += e[1];
			s += e[2];
		}
		return new Trend(buy, b / list.size(), sell, s / list.size(), list.size());
	}
}
