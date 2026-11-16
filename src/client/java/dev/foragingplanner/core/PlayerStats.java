package dev.foragingplanner.core;

/** Observed stats and modeled daily Whispers / Tree Gift bonus percent. */
public record PlayerStats(double sweep, double fortune, double wisdom, double strength, double speed,
                          double dailyWhispers, double giftBonus) {
    public static final PlayerStats ZERO = new PlayerStats(0, 0, 0);
    public PlayerStats(double sweep, double fortune, double wisdom) {
        this(sweep, fortune, wisdom, 0, 0, 0, 0);
    }
    public PlayerStats plus(GameData.Perk perk, int levels) {
        double conversion = perk.effects.getOrDefault("strengthConversion", 0.0) * strength
                + perk.effects.getOrDefault("speedConversion", 0.0) * speed;
        return new PlayerStats(sweep + levels * (perk.effects.getOrDefault("sweep", 0.0) + conversion),
                fortune + levels * (perk.effects.getOrDefault("fortune", 0.0) + conversion),
                wisdom + levels * perk.effects.getOrDefault("wisdom", 0.0),
                strength + levels * perk.effects.getOrDefault("strength", 0.0),
                speed + levels * perk.effects.getOrDefault("speed", 0.0),
                dailyWhispers + levels * perk.effects.getOrDefault("dailyWhispers", 0.0),
                giftBonus + levels * perk.effects.getOrDefault("giftBonus", 0.0));
    }
}
