package dev.foragingplanner.mc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.google.gson.Gson;

/**
 * Downloads the gear and shard data from the mod's GitHub repository, so wrong values can be
 * fixed without a new mod version. A download is only used when it is newer than the bundled
 * data (higher dataVersion) and passes the data check; a gear.json of the player's own always wins.
 */
public final class DataUpdater {
	private static final String URL = "https://raw.githubusercontent.com/LootingVI/foraging-planner/main/src/client/resources/foragingplanner/gear.json";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	/** What happened with the last download, for the detection check. */
	private static volatile String status = "";

	private DataUpdater() {}

	public static String status() {
		return status;
	}

	public static void start() {
		if (!Planner.config.remoteData) {
			status = "off";
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20))
				.header("User-Agent", "ForagingPlanner/" + ModVersion.get()).GET().build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() != 200) {
				status = "http " + response.statusCode();
				return;
			}
			try {
				GearData remote = new Gson().fromJson(response.body(), GearData.class);
				GearData bundled = GearData.bundled();
				int have = bundled == null ? -1 : bundled.dataVersion;
				if (remote == null || remote.items == null || remote.items.isEmpty()) {
					status = "empty";
					return;
				}
				var problems = DataCheck.gear(remote);
				if (!problems.isEmpty()) {
					status = "invalid";
					Planner.LOGGER.warn("[FP] Downloaded gear data has problems, not used: {}", problems);
					return;
				}
				if (remote.dataVersion <= have) {
					status = "current";
					return;
				}
				Path file = ModConfig.directory().resolve("remote").resolve("gear.json");
				Files.createDirectories(file.getParent());
				Files.writeString(file, response.body());
				status = "updated " + remote.dataVersion;
				Planner.LOGGER.info("[FP] Using downloaded gear data version {}", remote.dataVersion);
				net.minecraft.client.Minecraft.getInstance().execute(() -> {
					GearData.reload();
					DataCheck.reset();
				});
			} catch (Exception e) {
				status = "error";
				Planner.LOGGER.warn("[FP] Could not use the downloaded gear data", e);
			}
		}).exceptionally(error -> {
			status = "offline";
			return null;
		});
	}
}
