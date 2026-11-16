package dev.foragingplanner.mc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;

import dev.foragingplanner.core.GameData;

/**
 * Loads gamedata.json. A copy in config/foragingplanner/ wins over the one inside the jar,
 * so values can be corrected after a Hypixel update without rebuilding the mod.
 */
public final class DataLoader {
	private static final String BUNDLED = "/foragingplanner/gamedata.json";

	private DataLoader() {}

	public static Path overrideFile() {
		return ModConfig.directory().resolve("gamedata.json");
	}

	private static String bundledText() throws IOException {
		try (InputStream in = DataLoader.class.getResourceAsStream(BUNDLED)) {
			if (in == null) throw new IOException("Missing " + BUNDLED + " inside the mod jar");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Returns the override file's data when it exists and parses, otherwise the bundled data. */
	public static GameData load() {
		Gson gson = new Gson();

		try {
			if (Files.exists(overrideFile())) {
				GameData data = gson.fromJson(Files.readString(overrideFile()), GameData.class);

				if (data != null && !data.trees.isEmpty()) {
					if (data.dataVersion < 2) {
                        GameData bundled = gson.fromJson(bundledText(), GameData.class);
                        data.contest.miriaBrackets = bundled.contest.miriaBrackets;
                        bundled.statPatterns.forEach(data.statPatterns::putIfAbsent);
                        for (GameData.Perk added : bundled.hotf.perks) {
                            GameData.Perk existing = data.perk(added.name);
                            if (existing == null) data.hotf.perks.add(added);
                            else {
                                added.effects.forEach(existing.effects::putIfAbsent);
                                added.unlockEffects.forEach(existing.unlockEffects::putIfAbsent);
                                existing.unlockKnown |= added.unlockKnown;
                            }
                        }
                        data.dataVersion = 2;
                    }
                    if (data.dataVersion < 3) {
                        GameData bundled = gson.fromJson(bundledText(), GameData.class);
                        for (var perk : data.hotf.perks) {
                            var defaults = bundled.perk(perk.name);
                            if (defaults != null) { perk.currency = defaults.currency; perk.requiredTier = defaults.requiredTier; perk.treeColumn = defaults.treeColumn; perk.planEligible = defaults.planEligible; }
                        }
                        for (var defaults : bundled.hotf.perks) if (data.perk(defaults.name) == null) data.hotf.perks.add(defaults);
                        data.dataVersion = 3;
                    }
                    Planner.LOGGER.info("Using game data from {}", overrideFile());
					return data;
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read {}, using bundled game data", overrideFile(), e);
		}

		try {
			return gson.fromJson(bundledText(), GameData.class);
		} catch (Exception e) {
			Planner.LOGGER.error("Could not read bundled game data", e);
			return new GameData();
		}
	}

	/** Writes the bundled data to the config folder for editing. Does not overwrite an existing file. */
	public static boolean export() throws IOException {
		if (Files.exists(overrideFile())) return false;

		Files.createDirectories(ModConfig.directory());
		Files.writeString(overrideFile(), bundledText());
		return true;
	}
}
