package dev.foragingplanner.mc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * Asks Modrinth once per game start whether a newer version for this Minecraft version exists and
 * says so in chat. Nothing is downloaded or installed.
 */
public final class UpdateChecker {
	/** The project's slug on Modrinth. */
	public static final String PROJECT = "foraging-planner";
	private static final String PAGE = "https://modrinth.com/mod/" + PROJECT;

	private static boolean checked;
	private static volatile String newer;
	private static volatile String changelog;
	private static boolean told;

	private UpdateChecker() {}

	/** Called once a second; checks after joining SkyBlock and tells the player once. */
	public static void tick(Minecraft client) {
		if (!Planner.config.updateCheck || client.player == null) return;
		if (!checked && SkyBlockReader.inSkyBlock()) {
			checked = true;
			check(client);
		}
		if (newer != null && !told) {
			told = true;
			String current = ModVersion.plain();
			Component link = Component.literal("§b§n" + Planner.tr("update.open")).withStyle(style -> style
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(PAGE + "/versions")))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(changelog == null || changelog.isBlank() ? PAGE : changelog))));
			client.player.sendSystemMessage(Component.literal("§a[FP] §f" + Planner.tr("update.available", newer, current) + " ").append(link));
		}
	}

	private static void check(Minecraft client) {
		String mc = net.minecraft.SharedConstants.getCurrentVersion().id();
		String query = "?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
				+ (mc.isEmpty() ? "" : "&game_versions=" + URLEncoder.encode("[\"" + mc + "\"]", StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + PROJECT + "/version" + query))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Flori/foraging-planner/" + ModVersion.plain())
				.GET().build();
		HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() != 200) return;
			JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
			String current = ModVersion.plain();
			for (var element : versions) {
				JsonObject v = element.getAsJsonObject();
				if (!"release".equals(v.has("version_type") ? v.get("version_type").getAsString() : "release")) continue;
				String number = v.get("version_number").getAsString();
				int plus = number.indexOf('+');
				String plain = plus > 0 ? number.substring(0, plus) : number;
				if (compare(plain, current) > 0) {
					String text = v.has("changelog") && !v.get("changelog").isJsonNull() ? v.get("changelog").getAsString() : "";
					changelog = text.length() > 400 ? text.substring(0, 400) + "…" : text;
					newer = plain;
				}
				break; // Modrinth lists the newest first.
			}
		}).exceptionally(error -> {
			Planner.LOGGER.debug("Update check failed", error);
			return null;
		});
	}

	/** Compares "1.2.3" style versions; missing parts count as 0. */
	static int compare(String a, String b) {
		String[] x = a.split("[.-]"), y = b.split("[.-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int number(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
