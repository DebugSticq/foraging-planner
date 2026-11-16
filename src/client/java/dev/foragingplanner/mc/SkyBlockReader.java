package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.PlayerStats;

/** Reads what Hypixel shows in the sidebar and the tab list. Refreshed once per second. */
public final class SkyBlockReader {
	private static final Pattern AREA = Pattern.compile("Area: (.+)");

	private static List<String> sidebar = List.of();
	private static List<String> tab = List.of();
	private static boolean inForagingArea = false;
	private static double sweep = -1;
	private static double fortune = -1;
	private static double wisdom = -1;
	private static double strength = -1;
	private static double speed = -1;
	private static String currentArea = "";

	private SkyBlockReader() {}

	public static void refresh(Minecraft client) {
		sidebar = readSidebar(client);
		tab = readTab(client);

		String area = find(tab, AREA);
		currentArea = area == null ? "" : area.trim();
		inForagingArea = area != null && Planner.data.areas.stream().anyMatch(area.trim()::equalsIgnoreCase);

		sweep = stat("sweep");
		fortune = stat("fortune");
		wisdom = stat("wisdom");
		strength = stat("strength");
		speed = stat("speed");
	}

	/** Sidebar lines from top to bottom, without colour codes. */
	private static List<String> readSidebar(Minecraft client) {
		List<String> lines = new ArrayList<>();
		if (client.level == null) return lines;

		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) return lines;

		List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(objective));
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

		for (PlayerScoreEntry entry : entries) {
			// Hypixel puts the visible text into the prefix and suffix of a team per line.
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			if (team == null) continue;

			String line = Planner.strip(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
			if (!line.isBlank()) lines.add(line);
		}

		return lines;
	}

	/** Tab list entries that carry text, without colour codes. Order is not meaningful. */
	private static List<String> readTab(Minecraft client) {
		List<String> lines = new ArrayList<>();
		ClientPacketListener connection = client.getConnection();
		if (connection == null) return lines;

		for (PlayerInfo info : connection.getOnlinePlayers()) {
			Component name = info.getTabListDisplayName();
			if (name == null) continue;

			String line = Planner.strip(name.getString()).strip();
			if (!line.isEmpty()) lines.add(line);
		}

		return lines;
	}

	private static String find(List<String> lines, Pattern pattern) {
		for (String line : lines) {
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) return matcher.group(1);
		}

		return null;
	}

	private static double stat(String key) {
		Pattern pattern = Patterns.get(Planner.data.statPatterns.get(key));
		if (pattern == null) return -1;

		return ContestMath.parseNumber(find(tab, pattern));
	}

	public static List<String> sidebar() {
		return sidebar;
	}

	public static List<String> tab() {
		return tab;
	}

	public static boolean inForagingArea() {
		return inForagingArea;
	}

	/** True when Sweep comes from the tab list or was set by hand. Without it nothing can be calculated. */
	public static boolean statsKnown() {
		return Planner.config.manualStats || sweep >= 0;
	}

	/** Stats set by hand if the player chose that, otherwise the tab list values (0 for a stat the tab list lacks). */
    public static String area() { return currentArea; }
    public static boolean inSkyBlock() {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return false;
        var objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return objective != null && Planner.strip(objective.getDisplayName().getString()).startsWith("SKYBLOCK");
    }
    public static PlayerStats stats() {
        ModConfig c = Planner.config;
        double daily = 0, gifts = 0;
        for (var perk : Planner.data.hotf.perks) {
            int level = c.perkLevels.getOrDefault(perk.name, 0);
            daily += level * perk.effects.getOrDefault("dailyWhispers", 0.0);
            gifts += level * perk.effects.getOrDefault("giftBonus", 0.0);
        }
        return new PlayerStats(c.manualStats ? c.manualSweep : Math.max(0, sweep),
                c.manualStats ? c.manualFortune : Math.max(0, fortune),
                c.manualStats ? c.manualWisdom : Math.max(0, wisdom),
                c.manualStats ? c.manualStrength : Math.max(0, strength),
                c.manualStats ? c.manualSpeed : Math.max(0, speed), daily, gifts);
    }
}
