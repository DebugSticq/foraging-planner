package dev.foragingplanner.core;

/** Forest Whisper costs of Heart of the Forest perks. */
public final class PerkMath {
	private PerkMath() {}

	/**
	 * Whispers needed to go from {@code level - 1} to {@code level}.
	 * Level 1 is unlocked with a Token of the Forest and costs no whispers.
	 */
	public static long costToReach(GameData.Perk perk, int level) {
		if (level <= 1 || level > perk.maxLevel) return 0;

		if (perk.costExponent != null) {
			return (long) Math.floor(Math.pow(level + 1.0, perk.costExponent));
		}

		double base = perk.costBase == null ? 0 : perk.costBase;
		double perLevel = perk.costPerLevel == null ? 0 : perk.costPerLevel;
		return (long) Math.floor(base + perLevel * level);
	}

	/** Whispers needed to go from {@code fromLevel} to {@code toLevel}. */
	public static long costBetween(GameData.Perk perk, int fromLevel, int toLevel) {
		long total = 0;

		for (int level = Math.max(fromLevel, 1) + 1; level <= Math.min(toLevel, perk.maxLevel); level++) {
			total += costToReach(perk, level);
		}

		return total;
	}
}
