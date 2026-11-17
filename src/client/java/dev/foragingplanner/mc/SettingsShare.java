package dev.foragingplanner.mc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

/**
 * Settings as one line of text: copy it, send it to a friend or another instance, paste it there.
 * Personal data (HOTF levels, budgets, stats, leaderboard id) is never part of it, and importing
 * never turns on leaderboard sharing.
 */
public final class SettingsShare {
	private static final String PREFIX = "FP1:";
	private static final Gson GSON = new Gson();
	/** Fields that belong to the player, not to their taste. */
	private static final Set<String> PRIVATE = Set.of(
			"perkLevels", "perkSlots", "perkCurrencies", "requirements", "requiredTiers",
			"whispers", "desertWhispers", "tokens", "hotfTier",
			"manualStats", "manualSweep", "manualFortune", "manualWisdom", "manualStrength", "manualSpeed",
			"leaderboardShare", "leaderboardUuid", "setupDone");

	private SettingsShare() {}

	public static String export() {
		JsonObject json = GSON.toJsonTree(Planner.config).getAsJsonObject();
		for (String key : PRIVATE) json.remove(key);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (GZIPOutputStream zip = new GZIPOutputStream(bytes)) {
				zip.write(GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
			}
			return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Copies the settings to the clipboard; returns the length of the text. */
	public static int exportToClipboard() {
		String text = export();
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		return text.length();
	}

	/**
	 * Takes settings from the clipboard. The current settings are saved as a backup first.
	 *
	 * @return how many settings were taken over, or -1 when the clipboard holds no settings
	 */
	public static int importFromClipboard() {
		String text = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
		int start = text.indexOf(PREFIX);
		if (start < 0) return -1;
		text = text.substring(start + PREFIX.length()).split("\\s")[0];
		JsonObject incoming;
		try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(text)))) {
			incoming = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read the shared settings", e);
			return -1;
		}

		backup();
		JsonObject merged = GSON.toJsonTree(Planner.config).getAsJsonObject();
		int count = 0;
		for (var entry : incoming.entrySet()) {
			if (PRIVATE.contains(entry.getKey()) || !merged.has(entry.getKey())) continue;
			JsonElement old = merged.get(entry.getKey());
			if (!old.equals(entry.getValue())) count++;
			merged.add(entry.getKey(), entry.getValue());
		}
		try {
			ModConfig next = GSON.fromJson(merged, ModConfig.class);
			if (next == null) return -1;
			apply(next);
		} catch (RuntimeException e) {
			Planner.LOGGER.warn("Shared settings did not fit", e);
			return -1;
		}
		return count;
	}

	private static Path backupFile() {
		return ModConfig.profileDirectory().resolve("config.before-import.json");
	}

	private static void backup() {
		try {
			Files.createDirectories(backupFile().getParent());
			Files.writeString(backupFile(), GSON.toJson(Planner.config));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save the settings backup", e);
		}
	}

	/** Puts back the settings from before the last import. */
	public static boolean undo() {
		try {
			if (!Files.exists(backupFile())) return false;
			ModConfig old = GSON.fromJson(Files.readString(backupFile()), ModConfig.class);
			if (old == null) return false;
			apply(old);
			return true;
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not restore the settings backup", e);
			return false;
		}
	}

	private static void apply(ModConfig next) {
		Planner.config = next;
		next.save();
		Planner.config = ModConfig.load();
		PlannerHud.rebuild();
	}
}
