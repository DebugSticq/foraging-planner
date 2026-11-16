package dev.foragingplanner.mc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** NPC sell prices from Hypixel's public item list. Loaded once per game start. */
public final class NpcPrices {
	private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final Map<String, Double> PRICES = new ConcurrentHashMap<>();
	private static volatile boolean requested;

	private NpcPrices() {}

	public static Double get(String id) {
		load();
		return PRICES.get(id);
	}

	public static void load() {
		if (requested) return;
		requested = true;

		HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
				.sendAsync(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() != 200) return;
					JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
					for (JsonElement element : root.getAsJsonArray("items")) {
						JsonObject item = element.getAsJsonObject();
						if (item.has("id") && item.has("npc_sell_price")) PRICES.put(item.get("id").getAsString(), item.get("npc_sell_price").getAsDouble());
					}
				})
				.exceptionally(error -> {
					Planner.LOGGER.warn("NPC prices unavailable: {}", error.toString());
					requested = false;
					return null;
				});
	}
}
