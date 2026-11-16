package dev.foragingplanner.core;

import java.util.List;

/** Bracket and pace calculations for Starlyn contests (Agatha's and Miria's). */
public final class ContestMath {
	private ContestMath() {}

	/**
	 * @param current        bracket reached so far, or null below the first bracket
	 * @param next           next bracket, or null when the highest is reached
	 * @param pointsToNext   points missing for {@code next}, 0 when there is none
	 * @param neededPerMin   points per minute needed to reach {@code next} in the time left; infinite when time is up
	 * @param projected      points expected at the end at the current pace
	 * @param projectedBracket bracket expected at the end, or null
	 */
	public record Status(GameData.Bracket current, GameData.Bracket next, int pointsToNext, double neededPerMin,
						double projected, GameData.Bracket projectedBracket) {}

	/** Highest bracket whose minimum is reached, or null. Brackets must be ascending. */
	public static GameData.Bracket bracketFor(List<GameData.Bracket> brackets, double points) {
		GameData.Bracket reached = null;

		for (GameData.Bracket bracket : brackets) {
			if (points >= bracket.minPoints) reached = bracket;
		}

		return reached;
	}

	public static GameData.Bracket nextBracket(List<GameData.Bracket> brackets, double points) {
		for (GameData.Bracket bracket : brackets) {
			if (points < bracket.minPoints) return bracket;
		}

		return null;
	}

	public static Status status(List<GameData.Bracket> brackets, int points, int secondsLeft, double pointsPerMin) {
		GameData.Bracket current = bracketFor(brackets, points);
		GameData.Bracket next = nextBracket(brackets, points);
		int toNext = next == null ? 0 : next.minPoints - points;
		double needed = next == null ? 0 : secondsLeft <= 0 ? Double.POSITIVE_INFINITY : toNext / (secondsLeft / 60.0);
		double projected = points + Math.max(0, pointsPerMin) * Math.max(0, secondsLeft) / 60.0;

		return new Status(current, next, toNext, needed, projected, bracketFor(brackets, projected));
	}

	/**
	 * Parses a number as Hypixel prints it: "1,234", "17.5k", "3m".
	 * Returns -1 when the text is not a number.
	 */
	public static double parseNumber(String text) {
		if (text == null) return -1;
		String cleaned = text.trim().replace(",", "").toLowerCase(java.util.Locale.ROOT);
		if (cleaned.isEmpty()) return -1;

		double factor = 1;
		char last = cleaned.charAt(cleaned.length() - 1);

		if (last == 'k' || last == 'm' || last == 'b') {
			factor = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		try {
			return Double.parseDouble(cleaned) * factor;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
