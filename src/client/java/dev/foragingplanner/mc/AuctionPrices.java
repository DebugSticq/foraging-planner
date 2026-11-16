package dev.foragingplanner.mc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Live lowest Buy-It-Now prices from the auction house.
 *
 * <p>Only items that are actually being recommended are looked up, one at a time, from Coflnet's
 * public auction API (the active BIN listings right now), and each price is refreshed after a few
 * minutes. If that service cannot be reached, a bulk lowest-BIN list is used as a fallback.
 */
public final class AuctionPrices {
	private static final String LIVE_URL = "https://sky.coflnet.com/api/auctions/tag/%s/active/bin";
	private static final String BULK_URL = "https://moulberry.codes/lowestbin.json";
	private static final long STALE_MS = 3 * 60 * 1000L;
	private static final long RETRY_MS = 60 * 1000L;
	private static final long BULK_REFRESH_MS = 10 * 60 * 1000L;
	/** Pause between two lookups, to stay well inside the service's rate limit. */
	private static final long SPACING_MS = 500;
	private static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Map<String, Quote> LIVE = new ConcurrentHashMap<>();
	private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
	private static final Map<String, Double> BULK = new ConcurrentHashMap<>();
	private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
	private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();

	/**
	 * @param price  lowest BIN in coins, or -1 when nothing is listed
	 * @param timeMs when it was looked up
	 * @param note   extra information, such as the pet level the price is for; may be empty
	 */
	public record Quote(double price, long timeMs, String note) {}

	public enum State { IDLE, LOADING, READY, FAILED }

	private static volatile State state = State.IDLE;
	private static volatile int failures;
	private static long bulkAttemptMs;
	private static ScheduledExecutorService worker;

	private AuctionPrices() {}

	/** Asks for a fresh price of an item. Returns at once; the price arrives in the background. */
	public static void request(String id) {
		if (id == null || id.isBlank()) return;
		long now = System.currentTimeMillis();
		Quote quote = LIVE.get(id);
		if (quote != null && now - quote.timeMs() < STALE_MS) return;
		Long failed = FAILED_AT.get(id);
		if (failed != null && now - failed < RETRY_MS) return;

		startWorker();
		if (QUEUED.add(id)) QUEUE.add(id);
	}

	/** Lowest BIN price, live if known, otherwise from the bulk list; null when unknown. */
	public static Double get(String id) {
		Quote quote = LIVE.get(id);
		if (quote != null && quote.price() > 0) return quote.price();
		return BULK.get(id);
	}

	/** True when a live lookup found no BIN listing at all. */
	public static boolean noListing(String id) {
		Quote quote = LIVE.get(id);
		return quote != null && quote.price() <= 0;
	}

	/** Seconds since the live price was looked up, or -1 when there is no live price. */
	public static long ageSeconds(String id) {
		Quote quote = LIVE.get(id);
		return quote == null || quote.price() <= 0 ? -1 : (System.currentTimeMillis() - quote.timeMs()) / 1000;
	}

	public static String note(String id) {
		Quote quote = LIVE.get(id);
		return quote == null ? "" : quote.note();
	}

	public static State state() {
		return state;
	}

	public static boolean busy() {
		return !QUEUE.isEmpty();
	}

	private static synchronized void startWorker() {
		if (worker != null) return;

		worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ForagingPlanner-AuctionPrices");
			thread.setDaemon(true);
			return thread;
		});
		worker.scheduleWithFixedDelay(AuctionPrices::next, 0, SPACING_MS, TimeUnit.MILLISECONDS);
	}

	private static void next() {
		String id = QUEUE.poll();
		if (id == null) return;

		try {
			if (state != State.READY) state = State.LOADING;
			LIVE.put(id, lookUp(id));
			FAILED_AT.remove(id);
			failures = 0;
			state = State.READY;
		} catch (Exception e) {
			FAILED_AT.put(id, System.currentTimeMillis());
			failures++;
			if (failures >= 3) state = State.FAILED;
			if (failures == 3) Planner.LOGGER.warn("Live auction prices unavailable: {}", e.toString());
			loadBulkIfDue();
		} finally {
			QUEUED.remove(id);
		}
	}

	/** Looks up one item. A pet id such as "MONKEY;4" is searched as PET_MONKEY of that rarity, preferring level 100. */
	private static Quote lookUp(String id) throws Exception {
		String tag = id;
		String tier = null;
		int split = id.indexOf(';');

		if (split > 0) {
			tag = "PET_" + id.substring(0, split);
			int index = Integer.parseInt(id.substring(split + 1));
			tier = index >= 0 && index < TIERS.size() ? TIERS.get(index) : null;
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(LIVE_URL, tag)))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "ForagingPlanner/0.1 (Minecraft mod)")
				.GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) return new Quote(-1, System.currentTimeMillis(), "");
		if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());

		JsonElement root = JsonParser.parseString(response.body());
		JsonArray auctions = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
		double best = -1;
		double bestAnyLevel = -1;
		String anyLevelName = "";

		for (JsonElement element : auctions) {
			if (!element.isJsonObject()) continue;
			JsonObject auction = element.getAsJsonObject();
			if (auction.has("bin") && auction.get("bin").isJsonPrimitive() && !auction.get("bin").getAsBoolean()) continue;
			if (tier != null && auction.has("tier") && !tier.equalsIgnoreCase(auction.get("tier").getAsString())) continue;

			double price = price(auction);
			if (price <= 0) continue;

			String name = auction.has("itemName") ? Planner.strip(auction.get("itemName").getAsString()) : "";

			if (tier == null || name.contains("[Lvl 100]")) {
				if (best < 0 || price < best) best = price;
			} else if (bestAnyLevel < 0 || price < bestAnyLevel) {
				bestAnyLevel = price;
				anyLevelName = name;
			}
		}

		long now = System.currentTimeMillis();
		if (best > 0) return new Quote(best, now, "");

		// No level 100 pet listed: show the cheapest of that rarity and say which level it is.
		if (bestAnyLevel > 0) {
			int start = anyLevelName.indexOf("[Lvl ");
			int end = start >= 0 ? anyLevelName.indexOf(']', start) : -1;
			return new Quote(bestAnyLevel, now, end > start ? anyLevelName.substring(start, end + 1) : "");
		}

		return new Quote(-1, now, "");
	}

	private static double price(JsonObject auction) {
		for (String key : new String[] {"startingBid", "starting_bid", "price", "highestBidAmount"}) {
			if (auction.has(key) && auction.get(key).isJsonPrimitive()) {
				double value = auction.get(key).getAsDouble();
				if (value > 0) return value;
			}
		}

		return -1;
	}

	private static void loadBulkIfDue() {
		long now = System.currentTimeMillis();
		if (bulkAttemptMs != 0 && now - bulkAttemptMs < BULK_REFRESH_MS) return;
		bulkAttemptMs = now;

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(BULK_URL)).timeout(Duration.ofSeconds(30)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return;

			for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(response.body()).getAsJsonObject().entrySet()) {
				double price = entry.getValue().getAsDouble();
				if (Double.isFinite(price) && price > 0) BULK.put(entry.getKey(), price);
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Bulk auction prices unavailable: {}", e.toString());
		}
	}
}
