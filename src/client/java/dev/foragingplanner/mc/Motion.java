package dev.foragingplanner.mc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small animation helpers for menus: numbers that count up, ticks that pop. Each can be turned off. */
public final class Motion {
	private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*");
	private static final long COUNT_NS = 650_000_000L;

	private Motion() {}

	public static boolean pages() {
		return Planner.config.animations && Planner.config.animPages;
	}

	public static boolean numbers() {
		return Planner.config.animations && Planner.config.animNumbers;
	}

	public static boolean bars() {
		return Planner.config.animations && Planner.config.animBars;
	}

	public static boolean checks() {
		return Planner.config.animations && Planner.config.animChecks;
	}

	static double easeOut(double t) {
		t = Math.clamp(t, 0, 1);
		return 1 - Math.pow(1 - t, 3);
	}

	static double easeOutBack(double t) {
		t = Math.clamp(t, 0, 1);
		double c1 = 1.70158, c3 = c1 + 1;
		return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
	}

	/**
	 * The text with its first number counted up from 0, {@code sinceNs} being when the page opened.
	 * Separators and decimals are kept, so "1,234.5k" counts as "617.3k" half way.
	 */
	public static String countUp(String text, long sinceNs) {
		if (text == null || !numbers()) return text;
		long elapsed = System.nanoTime() - sinceNs;
		if (elapsed >= COUNT_NS || elapsed < 0) return text;
		Matcher m = NUMBER.matcher(text);
		if (!m.find()) return text;
		String token = m.group();
		if (token.endsWith(".") || token.endsWith(",")) token = token.substring(0, token.length() - 1);
		double p = easeOut((double) elapsed / COUNT_NS);
		String shown = scale(token, p);
		if (shown == null) return text;
		return text.substring(0, m.start()) + shown + text.substring(m.start() + token.length());
	}

	/** Scales a number written with separators, keeping its style. Null when it cannot be read. */
	static String scale(String token, double p) {
		// 1,234,567 or 1.234.567: grouped whole number.
		if (token.matches("\\d{1,3}([.,]\\d{3})+")) {
			char sep = token.charAt(token.length() - 4);
			long value = Long.parseLong(token.replace(String.valueOf(sep), ""));
			String digits = Long.toString(Math.round(value * p));
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < digits.length(); i++) {
				if (i > 0 && (digits.length() - i) % 3 == 0) b.append(sep);
				b.append(digits.charAt(i));
			}
			return b.toString();
		}
		// 12.5 or 12,5: decimal.
		int dot = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
		if (dot > 0 && token.indexOf('.') == token.lastIndexOf('.') && token.indexOf(',') == token.lastIndexOf(',') && !(token.contains(".") && token.contains(","))) {
			int decimals = token.length() - dot - 1;
			char sep = token.charAt(dot);
			try {
				double value = Double.parseDouble(token.replace(sep, '.'));
				String out = String.format(java.util.Locale.ROOT, "%." + decimals + "f", value * p);
				return out.replace('.', sep);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (token.matches("\\d+")) {
			if (token.length() > 15) return null;
			return Long.toString(Math.round(Long.parseLong(token) * p));
		}
		return null;
	}

	/** Size of a tick that pops in: overshoots a little, then settles at 1. */
	public static double pop(long sinceNs) {
		if (!checks()) return 1;
		double t = (System.nanoTime() - sinceNs) / 320e6;
		if (t >= 1) return 1;
		return Math.max(0, easeOutBack(t));
	}
}
