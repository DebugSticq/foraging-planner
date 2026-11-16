package dev.foragingplanner.mc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Talks to the mod's own PV API server. The first time it registers this installation (random id,
 * small proof of work, signed with the app key) and keeps the secret it gets back; every lookup is
 * then signed with that secret. No Minecraft login token is ever sent.
 */
public final class PvClient {
	/** Deployment settings are injected from an ignored local resource during release builds. */
	private static final JsonObject SERVICE_CONFIG = bundledConfig();
	public static final String DEFAULT_API = configValue("api").replaceAll("/+$", "");
	private static final String APP_KEY = configValue("appKey");
	private static final long CACHE_MS = 60_000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

	private record Cached(long timeMs, JsonObject data) {}

	private static JsonObject bundledConfig() {
		try (var in = PvClient.class.getResourceAsStream("/foragingplanner/pv.json")) {
			if (in != null) return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)
					.replace("\uFEFF", "")).getAsJsonObject();
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read PV service configuration", e);
		}
		return new JsonObject();
	}

	private static String configValue(String key) {
		var value = SERVICE_CONFIG.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
	}

	private static final class Auth {
		String installId;
		String secret;
	}

	private static Auth auth;
	private static CompletableFuture<Void> registering;

	private PvClient() {}

	private static Path file() {
		return ModConfig.directory().resolve("pv-auth.json");
	}

	private static synchronized Auth auth() {
		if (auth != null) return auth;
		auth = new Auth();
		try {
			if (Files.exists(file())) {
				Auth read = GSON.fromJson(Files.readString(file()), Auth.class);
				if (read != null) auth = read;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read pv-auth.json", e);
		}
		return auth;
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(auth()));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save pv-auth.json", e);
		}
	}

	public static String api() {
		return DEFAULT_API;
	}

	public static boolean configured() {
		return api().startsWith("https://") && !api().contains("CHANGE-ME") && !APP_KEY.isBlank();
	}

	static String hmac(String key, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
	}

	private static int leadingZeroBits(byte[] hash) {
		int bits = 0;
		for (byte b : hash) {
			int v = b & 0xFF;
			if (v == 0) { bits += 8; continue; }
			bits += Integer.numberOfLeadingZeros(v) - 24;
			break;
		}
		return bits;
	}

	/** Registers this installation once. Runs in the background. */
	private static synchronized CompletableFuture<Void> ensureRegistered() {
		Auth a = auth();
		if (a.installId != null && a.secret != null) return CompletableFuture.completedFuture(null);
		if (registering != null && !registering.isDone()) return registering;

		registering = CompletableFuture.runAsync(() -> {
			try {
				String id = UUID.randomUUID().toString();
				MessageDigest sha = MessageDigest.getInstance("SHA-256");
				long nonce = 0;
				// About a million tries on average for 20 bits; takes a second or two.
				while (leadingZeroBits(sha.digest((id + ":" + nonce).getBytes(StandardCharsets.UTF_8))) < 20) nonce++;

				long ts = System.currentTimeMillis();
				JsonObject body = new JsonObject();
				body.addProperty("installId", id);
				body.addProperty("nonce", String.valueOf(nonce));
				body.addProperty("ts", ts);
				HttpRequest request = HttpRequest.newBuilder(URI.create(api() + "/v1/register"))
						.timeout(Duration.ofSeconds(20))
						.header("Content-Type", "application/json")
						.header("X-FP-App", hmac(APP_KEY, "register\n" + id + "\n" + ts))
						.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				JsonObject answer = JsonParser.parseString(response.body()).getAsJsonObject();
				if (response.statusCode() != 200 || !answer.has("secret")) {
					throw new IllegalStateException((answer.has("error") ? answer.get("error").getAsString() : "HTTP " + response.statusCode())
							+ (answer.has("detail") ? ": " + answer.get("detail").getAsString() : ""));
				}
				synchronized (PvClient.class) {
					a.installId = id;
					a.secret = answer.get("secret").getAsString();
				}
				save();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
		return registering;
	}

	/**
	 * The player's UUID without dashes, if it can be found here: from the tab list when the player is
	 * online nearby, otherwise from Mojang directly from this PC. Mojang often refuses requests from
	 * the server, so this saves the server that step. Completes with null when unknown.
	 */
	private static CompletableFuture<String> uuidOf(String name) {
		try {
			var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
			if (connection != null) {
				var info = connection.getPlayerInfoIgnoreCase(name);
				if (info != null && info.getProfile() != null && info.getProfile().id() != null && info.getProfile().id().version() == 4) {
					return CompletableFuture.completedFuture(info.getProfile().id().toString().replace("-", ""));
				}
			}
		} catch (RuntimeException ignored) {
			// Fall back to the web lookup.
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/"
				+ URLEncoder.encode(name, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(8)).GET().build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle((response, failure) -> {
			if (failure != null || response.statusCode() != 200) return null;
			try {
				JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
				String id = json.has("id") ? json.get("id").getAsString().replace("-", "") : null;
				return id != null && id.matches("[0-9a-fA-F]{32}") ? id.toLowerCase(java.util.Locale.ROOT) : null;
			} catch (RuntimeException e) {
				return null;
			}
		});
	}

	private static final java.util.Deque<String> RECENT = new java.util.ArrayDeque<>();

	/** Names for tab completion: players viewed recently, then real players in the current lobby. */
	public static List<String> suggestions() {
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(RECENT);
		try {
			var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
			if (connection != null) {
				for (var info : connection.getOnlinePlayers()) {
					var profile = info.getProfile();
					// Hypixel fills the tab list with fake entries; real players have version 4 UUIDs.
					if (profile == null || profile.name() == null || profile.id() == null || profile.id().version() != 4) continue;
					if (profile.name().matches("[A-Za-z0-9_]{3,16}")) names.add(profile.name());
				}
			}
		} catch (RuntimeException ignored) {
			// Suggestions are a convenience only.
		}
		return new java.util.ArrayList<>(names);
	}

	private static void remember(String name) {
		synchronized (RECENT) {
			RECENT.removeIf(n -> n.equalsIgnoreCase(name));
			RECENT.addFirst(name);
			while (RECENT.size() > 10) RECENT.removeLast();
		}
	}

	/** Looks up a player. Completes with the server's answer or fails with a short reason. */
	public static CompletableFuture<JsonObject> lookup(String name) {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		Cached cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.timeMs() < CACHE_MS) return CompletableFuture.completedFuture(cached.data());
		if (!configured()) return CompletableFuture.failedFuture(new IllegalStateException("not_configured"));

		return ensureRegistered().thenCompose(ignored -> uuidOf(name)).thenCompose(uuid -> {
			try {
				Auth a = auth();
				String path = "/v1/pv/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
				long time = System.currentTimeMillis();
				String query = uuid == null ? "" : "?uuid=" + uuid;
				HttpRequest request = HttpRequest.newBuilder(URI.create(api() + path + query))
						.timeout(Duration.ofSeconds(20))
						.header("X-FP-Install", a.installId)
						.header("X-FP-Time", String.valueOf(time))
						.header("X-FP-Sig", hmac(a.secret, "GET\n" + path + "\n" + time))
						.GET().build();
				return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
					JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
					if (response.statusCode() == 401) {
						// The server no longer knows this install (new SERVER_SECRET): register again next time.
						synchronized (PvClient.class) {
							auth().installId = null;
							auth().secret = null;
						}
						save();
					}
					if (response.statusCode() != 200) {
						String reason = data.has("error") ? data.get("error").getAsString() : "HTTP " + response.statusCode();
						if (data.has("detail")) reason += ": " + data.get("detail").getAsString();
						throw new IllegalStateException(reason);
					}
					CACHE.put(key, new Cached(System.currentTimeMillis(), data));
					remember(data.has("name") ? data.get("name").getAsString() : name);
					return data;
				});
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		});
	}

	/** A signed request to the mod's own server (leaderboards). {@code body} null means GET. */
	public static CompletableFuture<JsonObject> signed(String path, String query, String body) {
		if (!configured()) return CompletableFuture.failedFuture(new IllegalStateException("not_configured"));
		return ensureRegistered().thenCompose(ignored -> {
			try {
				Auth a = auth();
				long time = System.currentTimeMillis();
				HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api() + path + (query == null ? "" : query)))
						.timeout(Duration.ofSeconds(20))
						.header("X-FP-Install", a.installId)
						.header("X-FP-Time", String.valueOf(time));
				if (body == null) {
					builder.header("X-FP-Sig", hmac(a.secret, "GET\n" + path + "\n" + time)).GET();
				} else {
					byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
					StringBuilder hex = new StringBuilder();
					for (byte b : digest) hex.append(String.format("%02x", b));
					builder.header("X-FP-Sig", hmac(a.secret, "POST\n" + path + "\n" + time + "\n" + hex))
							.header("Content-Type", "application/json")
							.POST(HttpRequest.BodyPublishers.ofString(body));
				}
				return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
					JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
					if (response.statusCode() != 200) {
						throw new IllegalStateException(data.has("error") ? data.get("error").getAsString() : "HTTP " + response.statusCode());
					}
					return data;
				});
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		});
	}
}
