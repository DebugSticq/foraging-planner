package dev.foragingplanner.mc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.GameData;

/**
 * Listens for the game's own "Sweep Details" chat lines. They state how many logs a hit
 * really cut, which beats the estimate from the breakpoint table.
 */
public final class SweepChatListener {
	private SweepChatListener() {}
	private static double sweep;
	private static long headerTime;

	public static void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !SkyBlockReader.inForagingArea()) return;

			String text = Planner.strip(message.getString());
            Pattern headerPattern = Patterns.get(Planner.data.sweepChat.sweepPattern);
            if (headerPattern == null) return;
            Matcher header = headerPattern.matcher(text);
            if (header.find()) {
                sweep = ContestMath.parseNumber(header.group(1));
                headerTime = System.currentTimeMillis();
                return;
            }
            if (System.currentTimeMillis() - headerTime > 2000) return;
            Pattern treePattern = Patterns.get(Planner.data.sweepChat.treePattern);
			if (treePattern == null) return;

			Matcher matcher = treePattern.matcher(Planner.strip(message.getString()));
			if (!matcher.matches()) return;

			double logs = ContestMath.parseNumber(matcher.group(3));
			if (logs < 0) return;

			for (GameData.Tree tree : Planner.data.trees) {
				if (tree.chatName != null && tree.chatName.equalsIgnoreCase(matcher.group(1).trim())) {
					Planner.recordMeasurement(tree.id, logs, sweep);
                    LearnedSweep.record(tree.id, sweep, logs, ContestMath.parseNumber(matcher.group(2)));
				}
			}
		});
	}
}
