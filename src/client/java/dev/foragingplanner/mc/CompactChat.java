package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import dev.foragingplanner.core.ObservationParser;

/**
 * Shorter chat on the foraging islands: a tree gift becomes one line (rewards on hover), Sweep
 * Details become one line, and the contest end messages are folded into the mod's summary.
 * Must be registered after every other chat listener, so that they still see every message.
 */
public final class CompactChat {
	private static final Pattern SWEEP = Pattern.compile("^Sweep Details: ([\\d,.]+)\\D{0,3} Sweep");
	private static final Pattern TOUGHNESS = Pattern.compile("^\\s*(.+?) Tree Toughness: ([\\d.]+) ([\\d.,]+) Logs");
	private static final Pattern WRONG = Pattern.compile("^\\s*Wrong Style: (.+)$");
	private static final Pattern REWARDS = Pattern.compile("^\\+(\\d[\\d,]*) rewards gained!");
	private static final Pattern CONTEST_RESULT = Pattern.compile("^\\[NPC] (Agatha|Miria): (You reached the \\w+ Bracket in my contest!|You earned a total of [\\d,]+ points!)$");

	private static boolean inGift;
	private static long giftStart;
	private static String giftTree = "";
	private static String giftShare = "";
	private static String giftRewards = "";
	private static final List<String> giftHover = new ArrayList<>();
	private static String sweep = "";

	private CompactChat() {}

	public static void register() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay) return true;
			try {
				return allow(message);
			} catch (RuntimeException e) {
				return true;
			}
		});
	}

	private static boolean allow(Component message) {
		String text = Planner.strip(message.getString()).trim();
		// HOTF lottery messages are only interesting on the foraging islands.
		if (Planner.config.hideLottery && !SkyBlockReader.inForagingArea() && SkyBlockReader.inSkyBlock()
				&& (text.startsWith("LOTTERY") || text.toUpperCase(java.util.Locale.ROOT).startsWith("LOTTERY!"))) return false;
		if (!SkyBlockReader.inForagingArea()) return true;
		long now = System.currentTimeMillis();

		if (Planner.config.compactGifts) {
			if (text.matches("▬{20,}")) {
				if (inGift && now - giftStart < 10_000) sendGift();
				return false;
			}
			if (text.equals("TREE GIFT")) {
				inGift = true;
				giftStart = now;
				giftTree = giftShare = giftRewards = "";
				giftHover.clear();
				return false;
			}
			if (inGift && now - giftStart < 10_000) {
				Matcher share = ObservationParser.CONTRIBUTION.matcher(text);
				if (share.matches()) {
					giftShare = share.group(1);
					giftTree = share.group(2);
					return false;
				}
				Matcher rewards = REWARDS.matcher(text);
				if (rewards.find()) {
					giftRewards = rewards.group(1);
					giftHover.addAll(hovers(message));
					return false;
				}
				if (text.equals("BONUS GIFT")) return false;
				giftHover.add("§6" + text);
				return false;
			}
		}

		if (Planner.config.compactSweep) {
			Matcher s = SWEEP.matcher(text);
			if (s.find()) {
				sweep = s.group(1);
				return false;
			}
			Matcher t = TOUGHNESS.matcher(text);
			if (t.find()) {
				send(Component.literal("§6Sweep §a" + sweep + " §8· §7" + t.group(1) + ": §a" + t.group(3) + " Logs"));
				return false;
			}
			Matcher w = WRONG.matcher(text);
			if (w.find()) {
				send(Component.literal("§c⚠ " + Planner.tr("chat.wrong_style") + " §7(" + w.group(1) + ")"));
				return false;
			}
		}

		if (Planner.config.compactContest && CONTEST_RESULT.matcher(text).matches()) return false;
		return true;
	}

	private static void sendGift() {
		inGift = false;
		StringBuilder hover = new StringBuilder();
		for (String line : giftHover) {
			if (!hover.isEmpty()) hover.append('\n');
			hover.append(line);
		}
		String tree = giftTree.isEmpty() ? "Tree" : giftTree;
		MutableComponent line = Component.literal("§2🎁 " + Planner.tr("chat.gift", tree) + (giftShare.isEmpty() ? "" : " §8· §a" + giftShare + "%")
				+ (giftRewards.isEmpty() ? "" : " §8· §e+" + giftRewards + " " + Planner.tr("chat.rewards")));
		if (!hover.isEmpty()) {
			String tip = hover.toString();
			line = line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(tip))));
		}
		send(line);
	}

	private static List<String> hovers(Component component) {
		List<String> out = new ArrayList<>();
		collect(component, out);
		return out;
	}

	private static void collect(Component c, List<String> out) {
		if (c.getStyle().getHoverEvent() instanceof HoverEvent.ShowText show) out.add(show.value().getString());
		for (Component sibling : c.getSiblings()) collect(sibling, out);
	}

	private static void send(Component line) {
		var player = Minecraft.getInstance().player;
		if (player != null) player.sendSystemMessage(line);
	}
}
