package dev.foragingplanner.mc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

/** Pet levels and pet head textures for foraging pets, from the NotEnoughUpdates repository (pets.json). */
public final class PetData {
	public List<String> foragingPets = new ArrayList<>();
	public Map<String, Integer> rarityOffset = new LinkedHashMap<>();
	public List<Long> levels = new ArrayList<>();
	public Map<String, Custom> custom = new LinkedHashMap<>();
	public Map<String, String> textures = new LinkedHashMap<>();

	public static final class Custom {
		public int type;
		public List<Long> levels = new ArrayList<>();
		public int maxLevel = 100;
	}

	/** Level, progress to the next level (0..1) and the maximum level. */
	public record Level(int level, double progress, int max) {}

	private static PetData data;

	public static PetData get() {
		if (data != null) return data;
		try (InputStream in = PetData.class.getResourceAsStream("/foragingplanner/pets.json")) {
			data = in == null ? new PetData() : new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), PetData.class);
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read pets.json", e);
			data = new PetData();
		}
		return data;
	}

	public boolean foraging(String type) {
		return foragingPets.contains(type);
	}

	public Level level(String type, String tier, double exp) {
		int offset = rarityOffset.getOrDefault(tier, 0);
		int max = 100;
		List<Long> table = new ArrayList<>();
		for (int i = offset; i < levels.size() && table.size() < 99; i++) table.add(levels.get(i));

		Custom c = custom.get(type);
		if (c != null) {
			max = c.maxLevel;
			// Type 1: the custom list continues the normal table after level 100.
			if (c.type == 1) table.addAll(c.levels);
			else if (!c.levels.isEmpty()) table = new ArrayList<>(c.levels);
		}

		int level = 1;
		double left = exp;
		for (long need : table) {
			if (level >= max) break;
			if (left < need) return new Level(level, need <= 0 ? 0 : left / need, max);
			left -= need;
			level++;
		}
		return new Level(Math.min(level, max), 1, max);
	}
}
