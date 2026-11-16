package dev.foragingplanner.mc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.foragingplanner.core.GameData;

/** Fetches log sell prices from Hypixel's public Bazaar API in the background. */
public final class BazaarPrices {
	/** Price to buy one item instantly, by Bazaar product id. Used for gear and shard costs. */
	public static final java.util.Map<String, Double> BUY = new java.util.concurrent.ConcurrentHashMap<>();

	private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private static long lastAttemptMs;

	private BazaarPrices() {}

	/** Starts a fetch when the last one is old enough. Returns at once; never blocks the game. */
	public static void refreshIfDue() {
		long now = System.currentTimeMillis();
		if (lastAttemptMs != 0 && now - lastAttemptMs < REFRESH_INTERVAL_MS) return;
		lastAttemptMs = now;

		HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build();

		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() == 200) {
						read(response.body());
					} else {
						Planner.LOGGER.warn("Bazaar request answered {}", response.statusCode());
					}
				})
				.exceptionally(error -> {
					Planner.LOGGER.warn("Bazaar request failed: {}", error.toString());
					return null;
				});
	}

	private static void read(String body) {
		try {
			JsonObject products = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("products");
			if (products == null) return;

            for (var entry : products.entrySet()) {
                JsonObject status = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                if (status != null && status.has("sellPrice")) {
                    double price = status.get("sellPrice").getAsDouble();
                    if (Double.isFinite(price) && price > 0) Planner.PRICES.put(entry.getKey(), price);
                    double buy = status.has("buyPrice") ? status.get("buyPrice").getAsDouble() : 0;
                    if (Double.isFinite(buy) && buy > 0) BUY.put(entry.getKey(), buy);
                }
            }
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read the Bazaar answer", e);
		}
	}
}
