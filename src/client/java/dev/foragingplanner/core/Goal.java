package dev.foragingplanner.core;

import java.util.Locale;

/** What the player wants to maximise. */
public enum Goal {
	/** Foraging XP per axe hit. */
	XP,
	/** Logs gained per axe hit, including Foraging Fortune. */
	LOGS,
	/** Coins per axe hit at Bazaar sell prices. Falls back to LOGS when no prices are known. */
	COINS,
	/** Raw Sweep, ignoring breakpoints. */
	SWEEP, WHISPERS, GIFTS;

	public static Goal parse(String text) {
		try {
			return valueOf(text.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
