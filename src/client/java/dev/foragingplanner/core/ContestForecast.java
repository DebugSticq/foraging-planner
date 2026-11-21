package dev.foragingplanner.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates the bracket a Starlyn contest will end in: from the pace in this contest (recent and
 * whole), the player's past contests at the same host, and how steady the pace has been.
 */
public final class ContestForecast {
	private ContestForecast() {}

	/**
	 * @param expected    points expected at the end
	 * @param low         a bad finish (about 1 in 6 contests end lower)
	 * @param high        a good finish (about 1 in 6 end higher)
	 * @param bracket     bracket of {@code expected}, or null
	 * @param next        the bracket above {@code bracket}, or null at the top
	 * @param nextChance  chance (0..1) to reach {@code next}
	 * @param keepChance  chance (0..1) to still reach {@code bracket}
	 * @param source      "pace", "mixed" or "history": what the estimate is mostly based on
	 */
	public record Result(double expected, double low, double high, GameData.Bracket bracket, GameData.Bracket next,
			double nextChance, double keepChance, String source) {}

	/**
	 * @param brackets     ascending brackets of this host
	 * @param points       points now
	 * @param secondsLeft  seconds left in the contest
	 * @param history      [seconds into the contest, points] samples of this contest, oldest first
	 * @param recentPace   points per minute over the last few minutes, 0 when unknown
	 * @param pastFinals   final points of earlier contests at this host (newest last); may be empty
	 * @return null when there is nothing to go on
	 */
	public static Result estimate(List<GameData.Bracket> brackets, int points, int secondsLeft, List<int[]> history,
			double recentPace, List<Integer> pastFinals) {
		double minutesLeft = Math.max(0, secondsLeft) / 60.0;

		// Pace over the whole tracked part of this contest.
		double wholePace = 0, spanMin = 0;
		if (history != null && history.size() >= 2) {
			int[] first = history.get(0), last = history.get(history.size() - 1);
			spanMin = (last[0] - first[0]) / 60.0;
			if (spanMin >= 1) wholePace = Math.max(0, (last[1] - first[1]) / spanMin);
		}
		double current = recentPace > 0 && wholePace > 0 ? 0.6 * recentPace + 0.4 * wholePace
				: recentPace > 0 ? recentPace : wholePace;

		// Pace of earlier contests: the median of their final points over 20 minutes.
		double pastPace = 0;
		double pastSpread = 0;
		if (pastFinals != null && !pastFinals.isEmpty()) {
			List<Integer> sorted = new ArrayList<>(pastFinals);
			sorted.sort(null);
			pastPace = sorted.get(sorted.size() / 2) / 20.0;
			if (sorted.size() >= 3) {
				double q1 = sorted.get(sorted.size() / 4) / 20.0, q3 = sorted.get(sorted.size() * 3 / 4) / 20.0;
				pastSpread = (q3 - q1) / 1.35;
			}
		}

		if (current <= 0 && pastPace <= 0) return null;

		// Early on the past contests count most; after about 8 tracked minutes only this contest counts.
		double trust = current <= 0 ? 0 : Math.min(1, spanMin / 8.0 + (recentPace > 0 ? 0.25 : 0));
		if (pastPace <= 0) trust = 1;
		double pace = trust * current + (1 - trust) * pastPace;
		String source = trust >= 0.8 ? "pace" : trust <= 0.2 ? "history" : "mixed";

		// How much the pace changes from minute to minute in this contest.
		double minuteSpread = minuteSpread(history);
		if (minuteSpread <= 0) minuteSpread = pastSpread > 0 ? pastSpread : pace * 0.35;
		// Spread of the finish: the minutes left vary on their own, and the pace itself may be off.
		double paceError = pace * (0.25 * (1 - trust) + 0.08);
		double sigma = Math.sqrt(minutesLeft * minuteSpread * minuteSpread + Math.pow(paceError * minutesLeft, 2));

		double expected = points + pace * minutesLeft;
		double low = Math.max(points, expected - sigma);
		double high = expected + sigma;
		GameData.Bracket bracket = ContestMath.bracketFor(brackets, expected);
		GameData.Bracket next = ContestMath.nextBracket(brackets, expected);
		double nextChance = next == null ? 0 : chance(expected, sigma, next.minPoints, points);
		double keepChance = bracket == null ? 1 : chance(expected, sigma, bracket.minPoints, points);
		return new Result(expected, low, high, bracket, next, nextChance, keepChance, source);
	}

	/** Chance that the finish is at least {@code target}, with the finish normally spread. */
	static double chance(double expected, double sigma, double target, int pointsNow) {
		if (target <= pointsNow) return 1;
		if (sigma <= 1e-6) return expected >= target ? 1 : 0;
		return 1 - phi((target - expected) / sigma);
	}

	/** Standard deviation of points per minute between one-minute stretches of the contest. */
	static double minuteSpread(List<int[]> history) {
		if (history == null || history.size() < 7) return 0;
		List<Double> rates = new ArrayList<>();
		int start = 0;
		for (int i = 1; i < history.size(); i++) {
			int dt = history.get(i)[0] - history.get(start)[0];
			if (dt >= 60) {
				rates.add((history.get(i)[1] - history.get(start)[1]) / (dt / 60.0));
				start = i;
			}
		}
		if (rates.size() < 3) return 0;
		double mean = 0;
		for (double r : rates) mean += r;
		mean /= rates.size();
		double var = 0;
		for (double r : rates) var += (r - mean) * (r - mean);
		return Math.sqrt(var / (rates.size() - 1));
	}

	/** Normal distribution function, accurate to about 1e-4. */
	static double phi(double z) {
		double t = 1 / (1 + 0.2316419 * Math.abs(z));
		double d = 0.3989423 * Math.exp(-z * z / 2);
		double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))));
		return z > 0 ? 1 - p : p;
	}
}
