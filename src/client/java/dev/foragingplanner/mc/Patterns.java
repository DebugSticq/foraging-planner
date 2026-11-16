package dev.foragingplanner.mc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Compiles the regular expressions from gamedata.json once, and survives a broken one. */
public final class Patterns {
	private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();
	/** Matches nothing. Stored for expressions that do not compile, so they are reported only once. */
	private static final Pattern NEVER = Pattern.compile("(?!)");

	private Patterns() {}

	/** Returns the compiled pattern, or null when the text is missing or not a valid expression. */
	public static Pattern get(String regex) {
		if (regex == null || regex.isEmpty()) return null;

		Pattern pattern = CACHE.computeIfAbsent(regex, text -> {
			try {
				return Pattern.compile(text);
			} catch (PatternSyntaxException e) {
				Planner.LOGGER.warn("Invalid pattern in gamedata.json: {}", text, e);
				return NEVER;
			}
		});

		return pattern == NEVER ? null : pattern;
	}

	public static void clear() {
		CACHE.clear();
	}
}
