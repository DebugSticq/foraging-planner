package dev.foragingplanner.mc;

import java.util.Locale;

/** Short coin amounts such as 1.25M. */
public final class GearScreenFormat {
	private GearScreenFormat() {}

	public static String coins(double value) {
		double v = Math.abs(value);
		if (v >= 1e9) return String.format(Locale.US, "%.2fB", value / 1e9);
		if (v >= 1e6) return String.format(Locale.US, "%.2fM", value / 1e6);
		if (v >= 1e3) return String.format(Locale.US, "%.1fk", value / 1e3);
		return Planner.whole(value);
	}

	/** "1:05:30" or "12:04" for a duration in seconds. */
	public static String duration(long seconds) {
		if (seconds < 0) return "–";
		long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
		return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
	}

	/** "3 h 20 min", "45 min" or "2 d 4 h" for an estimate in hours. */
	public static String eta(double hours) {
		if (!Double.isFinite(hours) || hours < 0) return "–";
		long minutes = Math.round(hours * 60);
		if (minutes < 60) return Math.max(1, minutes) + " min";
		if (minutes < 48 * 60) return minutes / 60 + " h " + (minutes % 60 > 0 ? minutes % 60 + " min" : "");
		return minutes / 1440 + " d " + minutes / 60 % 24 + " h";
	}
}
