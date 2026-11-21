package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.foragingplanner.core.ContestMath;

/** Time to the next Foraging level and the next pet level, from the current rates. */
public final class Levels {
	private static final Pattern SKILL = Pattern.compile("Foraging (\\d+)");
	private static final Pattern PET = Pattern.compile("\\[Lvl (\\d+)](?:\\s*\\[[^\\]]*])*\\s*([A-Za-z][A-Za-z' ]*?)\\s*(?:[^A-Za-z' ].*)?$");
	private static final Pattern PET_XP = Pattern.compile("^([\\d,.]+[kKmMbB]?)/([\\d,.]+[kKmMbB]?) XP");

	private static String petName = "";
	private static int petLevel = -1;
	private static double petXp = -1, petCap = -1;
	/** Pet XP gained: [time ms, total gained] at the first and last reading of this pet level run. */
	private static double petGained;
	private static long petStart;
	private static double lastPetXp = -1;

	private Levels() {}

	/** Called once a second. */
	public static void tick() {
		List<String> tab = SkyBlockReader.tab();
		boolean found = false;
		for (int i = 0; i < tab.size(); i++) {
			Matcher pet = PET.matcher(Planner.strip(tab.get(i)));
			if (!pet.find()) continue;
			String name = pet.group(2).trim();
			int level = Integer.parseInt(pet.group(1));
			if (!name.equals(petName)) {
				petName = name;
				petGained = 0;
				petStart = System.currentTimeMillis();
				lastPetXp = -1;
			}
			petLevel = level;
			petXp = petCap = -1;
			// The XP line follows the pet line, but the tab list can split a widget across columns.
			for (int j = 0; j < tab.size(); j++) {
				Matcher xp = PET_XP.matcher(Planner.strip(tab.get(j)).trim());
				if (xp.find()) {
					petXp = ContestMath.parseNumber(xp.group(1));
					petCap = ContestMath.parseNumber(xp.group(2));
					break;
				}
			}
			if (petXp >= 0) {
				if (lastPetXp >= 0) {
					double change = petXp - lastPetXp;
					if (change > 0 && change < petCap) petGained += change;
				}
				lastPetXp = petXp;
			}
			found = true;
			break;
		}
		if (!found) petLevel = -1;
	}

	/** HUD lines for the "levels" module. */
	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		if (!Planner.config.levelEta) return out;
		double rate = JournalStore.journal.current.rate("xp");
		double left = SessionRates.xpRemaining();
		String level = skillLevel();
		if (left >= 0) {
			String eta = rate > 0 ? GearScreenFormat.duration((long) (left / rate * 3600)) : "–";
			out.add("§aForaging §7" + Planner.tr("levels.foraging", level.isEmpty() ? "?" : String.valueOf(Integer.parseInt(level) + 1))
					+ " §f" + Planner.whole(left) + " XP §7· §e" + eta);
		}
		if (petLevel > 0 && petLevel < 200) {
			String line = "§d" + petName + " §7" + Planner.tr("levels.pet", petLevel, petLevel + 1);
			if (petXp >= 0 && petCap > 0) {
				double petLeft = Math.max(0, petCap - petXp);
				double hours = (System.currentTimeMillis() - petStart) / 3_600_000.0;
				double petRate = hours > 0.02 ? petGained / hours : 0;
				line += " §f" + Planner.whole(petLeft) + " XP §7· §e" + (petRate > 0 ? GearScreenFormat.duration((long) (petLeft / petRate * 3600)) : Planner.tr("levels.measuring"));
			}
			out.add(line);
		}
		return out;
	}

	private static String skillLevel() {
		for (String line : SkyBlockReader.tab()) {
			Matcher m = SKILL.matcher(Planner.strip(line));
			if (m.find()) return m.group(1);
		}
		return "";
	}
}
