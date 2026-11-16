package dev.foragingplanner.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

/**
 * Global leaderboards over all players of the mod who turned sharing on.
 *
 * <p>The account is proven with Mojang's normal server check: the game tells Mojang it joins a
 * "server" with a random id, and the mod's server asks Mojang whether that happened. The login
 * itself only ever goes to Mojang. XP per hour and logs per contest are measured by the server from
 * the Hypixel API; the mod only says when a session or contest starts and ends.
 */
public final class Leaderboard {
	public static final List<String> BOARDS = List.of("contest_points", "contest_logs", "xp_hour", "tpm");

	public record Board(List<Entry> entries, Entry you, int players, boolean verifiedBoard, String error, long timeMs) {}

	public record Entry(int rank, String name, double value, boolean verified) {}

	private static final Map<String, Board> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
	public static volatile String status = "";
	private static volatile boolean verifying;
	private static long lastVerifyTry;
	private static long lastCheck;
	private static long sessionStarted;
	private static long lastCheckpoint;
	private static int sentTpm;
	private static long contestEndDue;
	private static int contestEndPoints;

	private Leaderboard() {}

	public static boolean verified() {
		return !Planner.config.leaderboardUuid.isEmpty();
	}

	/** Called once a second. */
	public static void tick() {
		if (!Planner.config.leaderboardShare) return;
		long now = System.currentTimeMillis();

		if (contestEndDue > 0 && now >= contestEndDue) {
			contestEndDue = 0;
			JsonObject body = new JsonObject();
			body.addProperty("action", "end");
			body.addProperty("points", contestEndPoints);
			post("/v1/lb/contest", body);
		}
		if (now - lastCheck < 60_000) return;
		lastCheck = now;

		if (!verified()) {
			if (!verifying && now - lastVerifyTry > 10 * 60_000L) verify();
			return;
		}

		// Session: the server takes a Hypixel snapshot at the start and every 30 minutes.
		var current = JournalStore.journal.current;
		boolean foraging = SkyBlockReader.inForagingArea() && current.started != 0 && !Afk.idle();
		if (foraging && current.started != sessionStarted) {
			sessionStarted = current.started;
			lastCheckpoint = now;
			JsonObject body = new JsonObject();
			body.addProperty("action", "start");
			post("/v1/lb/session", body);
		} else if (foraging && now - lastCheckpoint >= 30 * 60_000L) {
			lastCheckpoint = now;
			JsonObject body = new JsonObject();
			body.addProperty("action", "checkpoint");
			post("/v1/lb/session", body);
		}

		int tpm = Progress.get().bestTpm;
		if (tpm > sentTpm) {
			sentTpm = tpm;
			JsonObject body = new JsonObject();
			body.addProperty("tpm", tpm);
			post("/v1/lb/submit", body);
		}
	}

	/** A new contest began: the server notes the player's log collections now. */
	public static void contestStarted(String host) {
		if (!Planner.config.leaderboardShare || !verified()) return;
		JsonObject body = new JsonObject();
		body.addProperty("action", "start");
		body.addProperty("host", host);
		post("/v1/lb/contest", body);
	}

	/** The contest ended: three minutes later, when the Hypixel API has the new numbers, the server measures. */
	public static void contestEnded(int points) {
		if (!Planner.config.leaderboardShare || !verified()) return;
		contestEndPoints = points;
		contestEndDue = Math.max(System.currentTimeMillis(), ContestTracker.endedAt()) + 180_000;
	}

	/** Proves the account with Mojang. */
	public static void verify() {
		verifying = true;
		lastVerifyTry = System.currentTimeMillis();
		status = Planner.tr("stats.lb_verifying");
		PvClient.signed("/v1/lb/challenge", null, "{}").thenCompose(data -> CompletableFuture.supplyAsync(() -> {
			String serverId = data.get("serverId").getAsString();
			var client = Minecraft.getInstance();
			var user = client.getUser();
			try {
				client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
			} catch (Exception e) {
				throw new IllegalStateException("mojang_login");
			}
			JsonObject body = new JsonObject();
			body.addProperty("name", user.getName());
			body.addProperty("serverId", serverId);
			return body.toString();
		})).thenCompose(body -> PvClient.signed("/v1/lb/verify", null, body)).whenComplete((data, error) -> {
			verifying = false;
			if (error != null) {
				status = reason(error);
				return;
			}
			status = "";
			Planner.config.leaderboardUuid = data.get("uuid").getAsString();
			Planner.config.save();
			CACHE.clear();
			lastCheck = 0;
		});
	}

	/** Removes this player from all boards (when sharing is turned off). */
	public static void forget() {
		PvClient.signed("/v1/lb/forget", null, "{}").whenComplete((data, error) -> {
			status = error == null ? "" : reason(error);
			CACHE.clear();
		});
		Planner.config.leaderboardUuid = "";
		sentTpm = 0;
		sessionStarted = 0;
	}

	private static void post(String path, JsonObject body) {
		PvClient.signed(path, null, body.toString()).whenComplete((data, error) -> {
			if (error == null) {
				status = "";
				CACHE.clear();
				return;
			}
			status = reason(error);
			if (status.equals("not_verified")) {
				Planner.config.leaderboardUuid = "";
				Planner.config.save();
			}
		});
	}

	/** This player's own bests, shown next to the boards. */
	public static Map<String, Double> stats() {
		Map<String, Double> out = new LinkedHashMap<>();
		Progress p = Progress.get();
		out.put("contest_points", (double) p.contests.stream().mapToInt(c -> c.points).max().orElse(0));
		out.put("contest_logs", (double) p.contests.stream().mapToLong(c -> c.logs).max().orElse(0));
		out.put("tpm", (double) p.bestTpm);
		double xp = 0;
		var j = JournalStore.journal;
		for (var s : j.history) if (s.elapsedMs >= 30 * 60_000L) xp = Math.max(xp, s.rate("xp"));
		if (j.current.elapsedMs >= 30 * 60_000L) xp = Math.max(xp, j.current.rate("xp"));
		out.put("xp_hour", Math.floor(xp));
		return out;
	}

	/** The board from the cache; loads it in the background when missing or older than a minute. */
	public static Board get(String board, Runnable loaded) {
		Board cached = CACHE.get(board);
		if ((cached == null || System.currentTimeMillis() - cached.timeMs() > 60_000) && LOADING.putIfAbsent(board, true) == null) {
			PvClient.signed("/v1/lb/" + board, null, null).whenComplete((data, error) -> {
				LOADING.remove(board);
				if (error != null) {
					CACHE.put(board, new Board(List.of(), null, 0, false, reason(error), System.currentTimeMillis()));
				} else {
					List<Entry> entries = new java.util.ArrayList<>();
					for (var e : data.getAsJsonArray("entries")) {
						JsonObject o = e.getAsJsonObject();
						entries.add(new Entry(o.get("rank").getAsInt(), o.get("name").getAsString(), o.get("value").getAsDouble(),
								o.has("verified") && o.get("verified").getAsBoolean()));
					}
					Entry you = null;
					if (data.has("you") && data.get("you").isJsonObject()) {
						JsonObject y = data.getAsJsonObject("you");
						you = new Entry(y.get("rank").getAsInt(), Minecraft.getInstance().getUser().getName(), y.get("value").getAsDouble(), true);
					}
					boolean verifiedBoard = data.has("verifiedBoard") && data.get("verifiedBoard").getAsBoolean();
					CACHE.put(board, new Board(entries, you, data.get("players").getAsInt(), verifiedBoard, null, System.currentTimeMillis()));
				}
				if (loaded != null) Minecraft.getInstance().execute(loaded);
			});
		}
		return cached;
	}

	private static String reason(Throwable error) {
		Throwable t = error;
		while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}
}
