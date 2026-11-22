package dev.foragingplanner.mc;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Cheapest way to get attribute shards: buy them, or fuse them from cheaper shards, as deep as it
 * pays off. Recipes from SkyShards (MIT); prices from the Bazaar (instant buy or buy order).
 * Only calculates and shows; never buys or fuses anything.
 */
public final class ShardFusion {
	/** One shard: SkyShards key (C1, U5, ...), name, rarity, Bazaar id and how many one fusion takes. */
	public record Shard(String key, String name, String rarity, String id, int fuse) {}

	/** A recipe: {@code fuse(a)} of a plus {@code fuse(b)} of b give {@code out} of the result. */
	public record Recipe(String a, String b, int out) {}

	/** Step of a plan: buy {@code amount} of a shard, or fuse it with a recipe. */
	public record Node(Shard shard, long amount, double cost, Recipe recipe, List<Node> parts) {
		public boolean bought() {
			return recipe == null;
		}
	}

	private static final String URL = "https://skyshards.com/fusion-data.json";
	private static final long REFRESH_MS = 3L * 24 * 3600 * 1000;

	private static volatile Map<String, Shard> shards = Map.of();
	private static volatile Map<String, List<Recipe>> recipes = Map.of();
	private static volatile boolean loading;

	/** Cheapest cost per shard (instant buy or buy order prices), with the recipe used. */
	private static final Map<Boolean, Map<String, Double>> COST = new HashMap<>();
	private static final Map<Boolean, Map<String, Recipe>> BEST = new HashMap<>();
	private static final Map<Boolean, Integer> PRICE_HASH = new HashMap<>();

	private ShardFusion() {}

	public static boolean ready() {
		return !shards.isEmpty();
	}

	public static List<Shard> all() {
		List<Shard> list = new ArrayList<>(shards.values());
		list.sort((x, y) -> x.name().compareToIgnoreCase(y.name()));
		return list;
	}

	public static Shard byName(String name) {
		String n = name.trim().replaceAll("(?i)\\s+shards?$", "");
		for (Shard s : shards.values()) if (s.name().equalsIgnoreCase(n)) return s;
		for (Shard s : shards.values()) if (s.name().toLowerCase(Locale.ROOT).startsWith(n.toLowerCase(Locale.ROOT))) return s;
		return null;
	}

	public static Shard byId(String id) {
		for (Shard s : shards.values()) if (s.id().equalsIgnoreCase(id)) return s;
		return null;
	}

	/** Loads the recipes once in the background: downloaded copy if fresh, else the bundled one. */
	public static void load() {
		if (loading || ready()) return;
		loading = true;
		Thread thread = new Thread(() -> {
			try {
				Path cache = ModConfig.directory().resolve("remote").resolve("fusion-data.json");
				JsonObject json = null;
				if (Files.exists(cache)) {
					try {
						json = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
					} catch (Exception e) {
						Planner.LOGGER.warn("Could not read the downloaded fusion recipes", e);
					}
				}
				if (json == null) {
					try (var in = ShardFusion.class.getResourceAsStream("/foragingplanner/fusion-data.json.gz")) {
						if (in != null) json = JsonParser.parseReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)).getAsJsonObject();
					}
				}
				if (json != null) read(json);
				if (Planner.config.fusionDownload && (!Files.exists(cache) || System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() > REFRESH_MS)) {
					download(cache);
				}
			} catch (Exception e) {
				Planner.LOGGER.warn("Could not load the fusion recipes", e);
			} finally {
				loading = false;
			}
		}, "foragingplanner-fusion");
		thread.setDaemon(true);
		thread.start();
	}

	private static void download(Path cache) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30))
					.header("User-Agent", "ForagingPlanner/" + ModVersion.get()).GET().build();
			HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
					.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return;
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!json.has("shards") || !json.has("recipes") || json.getAsJsonObject("shards").size() < 100) return;
			Files.createDirectories(cache.getParent());
			Files.writeString(cache, response.body());
			read(json);
			Planner.LOGGER.info("[FP] Fusion recipes updated from SkyShards ({} shards)", shards.size());
		} catch (Exception e) {
			Planner.LOGGER.debug("Fusion recipe download failed", e);
		}
	}

	/** Reads both the SkyShards format and the compact bundled one. */
	private static void read(JsonObject json) {
		Map<String, Shard> s = new LinkedHashMap<>();
		for (var entry : json.getAsJsonObject("shards").entrySet()) {
			JsonObject o = entry.getValue().getAsJsonObject();
			String id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString()
					: o.has("internal_id") && !o.get("internal_id").isJsonNull() ? o.get("internal_id").getAsString() : null;
			if (id == null) continue;
			int fuse = o.has("fuse") ? o.get("fuse").getAsInt() : o.has("fuse_amount") && !o.get("fuse_amount").isJsonNull() ? o.get("fuse_amount").getAsInt() : 1;
			s.put(entry.getKey(), new Shard(entry.getKey(), o.get("name").getAsString(), o.has("rarity") ? o.get("rarity").getAsString() : "", id, Math.max(1, fuse)));
		}
		Map<String, List<Recipe>> r = new HashMap<>();
		for (var entry : json.getAsJsonObject("recipes").entrySet()) {
			List<Recipe> list = new ArrayList<>();
			for (var byQty : entry.getValue().getAsJsonObject().entrySet()) {
				int out = Integer.parseInt(byQty.getKey());
				for (var pair : byQty.getValue().getAsJsonArray()) {
					JsonArray p = pair.getAsJsonArray();
					if (p.size() == 2) list.add(new Recipe(p.get(0).getAsString(), p.get(1).getAsString(), out));
				}
			}
			r.put(entry.getKey(), list);
		}
		synchronized (COST) {
			shards = s;
			recipes = r;
			COST.clear();
			BEST.clear();
			PRICE_HASH.clear();
		}
	}

	/** Price of one shard at the Bazaar: instant buy, or the buy order price (what sellers get now). */
	public static double price(Shard s, boolean order) {
		Double p = order ? Planner.PRICES.get(s.id()) : BazaarPrices.BUY.get(s.id());
		return p == null || p <= 0 ? Double.POSITIVE_INFINITY : p;
	}

	/** Works out the cheapest cost of every shard (relaxing fusions until nothing gets cheaper). */
	private static void solve(boolean order) {
		int hash = (order ? Planner.PRICES : BazaarPrices.BUY).hashCode() ^ shards.size();
		synchronized (COST) {
			if (COST.containsKey(order) && PRICE_HASH.getOrDefault(order, 0) == hash) return;
			Map<String, Double> cost = new HashMap<>();
			Map<String, Recipe> best = new HashMap<>();
			for (Shard s : shards.values()) cost.put(s.key(), price(s, order));
			for (int round = 0; round < 40; round++) {
				boolean changed = false;
				for (var entry : recipes.entrySet()) {
					double now = cost.getOrDefault(entry.getKey(), Double.POSITIVE_INFINITY);
					for (Recipe r : entry.getValue()) {
						Shard a = shards.get(r.a()), b = shards.get(r.b());
						if (a == null || b == null) continue;
						double c = (a.fuse() * cost.get(r.a()) + b.fuse() * cost.get(r.b())) / r.out();
						if (c < now * 0.9999) {
							now = c;
							best.put(entry.getKey(), r);
							changed = true;
						}
					}
					cost.put(entry.getKey(), now);
				}
				if (!changed) break;
			}
			COST.put(order, cost);
			BEST.put(order, best);
			PRICE_HASH.put(order, hash);
		}
	}

	/** Cheapest cost of one shard, buying or fusing. */
	public static double cheapest(Shard s, boolean order) {
		solve(order);
		return COST.get(order).getOrDefault(s.key(), Double.POSITIVE_INFINITY);
	}

	/** The cheapest plan for {@code amount} shards, as a tree. */
	public static Node plan(Shard s, long amount, boolean order) {
		solve(order);
		return expand(s, amount, order, 0);
	}

	private static Node expand(Shard s, long amount, boolean order, int depth) {
		Recipe r = BEST.get(order).get(s.key());
		double buy = price(s, order) * amount;
		if (r == null || depth > 10) return new Node(s, amount, buy, null, List.of());
		Shard a = shards.get(r.a()), b = shards.get(r.b());
		long crafts = (amount + r.out() - 1) / r.out();
		Node left = expand(a, crafts * a.fuse(), order, depth + 1);
		Node right = expand(b, crafts * b.fuse(), order, depth + 1);
		double fused = left.cost() + right.cost();
		if (!(fused < buy)) return new Node(s, amount, buy, null, List.of());
		return new Node(s, amount, fused, r, List.of(left, right));
	}

	/** Shards to buy for a plan, added up. */
	public static Map<Shard, Long> shopping(Node node) {
		Map<Shard, Long> out = new LinkedHashMap<>();
		collect(node, out);
		return out;
	}

	private static void collect(Node node, Map<Shard, Long> out) {
		if (node.bought()) out.merge(node.shard(), node.amount(), Long::sum);
		else for (Node part : node.parts()) collect(part, out);
	}

	/** Fusions to do for a plan, deepest first. */
	public static int fusions(Node node) {
		if (node.bought()) return 0;
		int n = (int) ((node.amount() + node.recipe().out() - 1) / node.recipe().out());
		for (Node part : node.parts()) n += fusions(part);
		return n;
	}
}
