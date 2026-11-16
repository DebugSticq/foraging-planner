package dev.foragingplanner.mc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.foragingplanner.core.GameData;

/**
 * Credits what a finished Starlyn contest (Agatha's or Miria's) is worth: HOTF experience and
 * Forest Essence. The rewards are claimed from the NPC later, but they were earned now, so they
 * count towards the session at the moment the bracket is announced.
 */
public final class ContestRewards {
	/** "[NPC] Agatha: You reached the LEGENDARY Bracket in my contest!" */
	private static final Pattern RESULT = Pattern.compile("^\\[NPC] (\\w+): You reached the (\\w+) Bracket in my contest!$");
	/** The same result is never counted twice within this time. */
	private static final long REPEAT_MS = 60_000;

	/** HOTF experience and Forest Essence per bracket, from SkyHanni's public reward table. Used when gamedata.json has none. */
	private static final Map<String, int[]> DEFAULTS = Map.of(
			"COMMON", new int[] {100, 10},
			"UNCOMMON", new int[] {200, 20},
			"RARE", new int[] {350, 30},
			"EPIC", new int[] {500, 40},
			"LEGENDARY", new int[] {750, 50},
			"MYTHIC", new int[] {750, 60},
			"DIVINE", new int[] {750, 70},
			"SPECIAL", new int[] {750, 80});

	public record Result(String host, String bracket, int hotf, int essence, long timeMs) {}

	private static String lastKey = "";
	private static long lastMs;
	private static Result last;

	private ContestRewards() {}

	private static final Pattern TOTAL = Pattern.compile("^\\[NPC] (\\w+): You earned a total of ([\\d,]+) points!");

	private static final Pattern COLLECTION_PB = Pattern.compile("PERSONAL BEST: You increased your (\\w+) Collection by ([\\d,]+) during the contest!");
	private static final Pattern PREVIOUS_RECORD = Pattern.compile("surpassed your previous record of ([\\d,]+) (\\w+) logs collected");

	public static void register() {
		ServerMessages.register((message, overlay) -> {
			if (!overlay) read(Planner.strip(message.getString()).trim(), System.currentTimeMillis());
		});
	}

	static void read(String text, long now) {
		Matcher collection = COLLECTION_PB.matcher(text);
		if (collection.find()) {
			Watchers.contestCollection(collection.group(1), Long.parseLong(collection.group(2).replace(",", "")), -1);
			return;
		}
		Matcher previous = PREVIOUS_RECORD.matcher(text);
		if (previous.find()) {
			Watchers.contestCollection(previous.group(2), -1, Long.parseLong(previous.group(1).replace(",", "")));
			return;
		}

		Matcher total = TOTAL.matcher(text);
		if (total.find()) {
			int points = Integer.parseInt(total.group(2).replace(",", ""));
			Watchers.contestPoints(points, ContestTracker.raw());
			return;
		}

		Matcher match = RESULT.matcher(text);
		if (!match.matches()) return;

		String host = match.group(1);
		String bracket = match.group(2).toUpperCase(Locale.ROOT);
		String key = host + ":" + bracket;
		if (key.equals(lastKey) && now - lastMs < REPEAT_MS) return;

		lastKey = key;
		lastMs = now;

		int[] reward = reward(host, bracket);
		var journal = JournalStore.journal;
		journal.add("contests", 1, now);
		journal.add("hotf", reward[0], now);
		journal.add("hotf_contest", reward[0], now);
		journal.add("essence", reward[1], now);
		journal.item("ESSENCE_FOREST", reward[1], now);
		journal.dailyContests = Math.min(3, journal.dailyContests + 1);

		last = new Result(host, bracket, reward[0], reward[1], now);
		Watchers.contestResult(host, bracket);
		JournalStore.save();
		PlannerHud.rebuild();
	}

	/** HOTF experience and essence for a bracket: gamedata.json first, then the built-in table. */
	static int[] reward(String host, String bracket) {
		List<GameData.Bracket> brackets = Planner.data.contest.bracketsFor(host);

		if (brackets != null) {
			for (GameData.Bracket entry : brackets) {
				if (entry.name.equalsIgnoreCase(bracket) && (entry.hotfExp > 0 || entry.essence > 0)) {
					return new int[] {entry.hotfExp, entry.essence};
				}
			}
		}

		return DEFAULTS.getOrDefault(bracket, new int[2]).clone();
	}

	/** The last contest result seen this game session, or null. */
	public static Result last() {
		return last;
	}
}
