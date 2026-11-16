package dev.foragingplanner.core;

import java.util.*;
import java.util.regex.*;

/** Matches server evidence, never player chat. Hover text is supplied by the Minecraft adapter. */
public final class ObservationParser {
    public static final Pattern PROFILE = Pattern.compile("(?i)^(?:You are playing on profile:|Your profile was changed to:|Profile:)\\s*([a-z]+)(?:\\s*\\(Co-op\\))?$");
    public static final Pattern CONTRIBUTION = Pattern.compile("^You helped cut ([\\d.]+)% of the (Fig|Mangrove|Helix) Tree\\.$");
    public static final Pattern SACK = Pattern.compile("(?m)^\\s*([+-][\\d,]+) (.+?) \\([^\\n]*Sack[^\\n]*\\)\\s*$");
    public static final Pattern REWARD = Pattern.compile("^(.+?)\\s+x([\\d,]+)$");
    public static final Pattern BONUS = Pattern.compile("^(.+?) \\([\\d.]+%\\)$");
    public record ItemAmount(String name, long count) {}
    public static List<ItemAmount> sacks(String text) {
        List<ItemAmount> result = new ArrayList<>();
        Matcher m = SACK.matcher(text);
        while (m.find()) result.add(new ItemAmount(m.group(2).trim(), Long.parseLong(m.group(1).replace(",", ""))));
        return result;
    }
    public static ItemAmount reward(String line) {
        Matcher m = REWARD.matcher(line.trim());
        if (!m.matches()) return null; // Ranges/probabilities are not observed quantities.
        return new ItemAmount(m.group(1).trim(), Long.parseLong(m.group(2).replace(",", "")));
    }
    public static String itemId(String name) {
        return switch (name) {
            case "Forest Essence" -> "ESSENCE_FOREST";
            default -> name.toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "");
        };
    }
    private ObservationParser() {}
}
