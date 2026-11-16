package dev.foragingplanner.mc;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import dev.foragingplanner.core.GameData;

/** /fp wiki <term>: a short explanation of a perk, item, pet, shard, tree or upgrade, right in chat. */
public final class Lexicon {
	/** One entry: name, kind, and the lines to show. */
	public record Entry(String name, String kind, List<String> lines) {}

	private Lexicon() {}

	private static String norm(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static String num(double value) {
		return Planner.decimal(Math.round(value * 100) / 100.0);
	}

	/** Every entry the mod knows about. */
	public static List<Entry> all() {
		List<Entry> out = new ArrayList<>();

		for (GameData.Perk perk : Planner.data.hotf.perks) {
			List<String> lines = new ArrayList<>();
			if (perk.effects != null) {
				perk.effects.forEach((stat, perLevel) -> lines.add(Planner.tr("wiki.per_level", "+" + num(perLevel), stat(stat), num(perLevel * perk.maxLevel), perk.maxLevel)));
			}
			if (perk.note != null && !perk.note.isBlank()) lines.add("§7" + perk.note);
			lines.add(Planner.tr("wiki.perk_info", perk.requiredTier, Planner.tr("wiki.currency." + (perk.currency == null ? "forest" : perk.currency))));
			int level = Planner.config.perkLevels.getOrDefault(perk.name, -1);
			if (level >= 0) lines.add(Planner.tr("wiki.your_level", level, perk.maxLevel));
			out.add(new Entry(perk.name, Planner.tr("wiki.kind.perk"), lines));
		}

		GearData gear = GearData.get();
		for (GearData.Item item : gear.items) {
			List<String> lines = new ArrayList<>();
			StringBuilder stats = new StringBuilder();
			if (item.sweep > 0) stats.append("§2+").append(num(item.sweep)).append(" Sweep  ");
			if (item.fortune > 0) stats.append("§6+").append(num(item.fortune)).append(" Foraging Fortune  ");
			if (item.wisdom > 0) stats.append("§3+").append(num(item.wisdom)).append(" Foraging Wisdom");
			if (!stats.isEmpty()) lines.add(stats.toString().trim());
			if (item.fortunePerDigit > 0) lines.add(Planner.tr("wiki.per_digit", num(item.fortunePerDigit), "Fortune"));
			if (item.sweepPerDigit > 0) lines.add(Planner.tr("wiki.per_digit", num(item.sweepPerDigit), "Sweep"));
			if (item.fortunePerSweep > 0) lines.add(Planner.tr("wiki.per_sweep", num(item.fortunePerSweep)));
			if (item.foraging > 0) lines.add(Planner.tr("wiki.requires", item.foraging));
			if (item.note != null && !item.note.isBlank()) lines.add("§7" + item.note);
			Double price = Watchers.price(item.id);
			if (price != null) lines.add(Planner.tr("wiki.price", GearScreenFormat.coins(price)));
			String kind = item.slot.equals("pet") ? Planner.tr("wiki.kind.pet") : Planner.tr("gear.slot." + item.slot);
			out.add(new Entry(item.name + (item.slot.equals("pet") ? " (" + StatsScreen.capital(item.rarity.toLowerCase(Locale.ROOT)) + ")" : ""), kind, lines));
		}

		for (GearData.Shard shard : gear.shards) {
			List<String> lines = new ArrayList<>();
			lines.add("§b" + shard.ability + "§7: " + shard.effect);
			lines.add(Planner.tr("wiki.shard_info", StatsScreen.capital(shard.rarity.toLowerCase(Locale.ROOT)), shard.toMax, Planner.tr("gear.priority." + shard.priority)));
			Double price = BazaarPrices.BUY.get(shard.id);
			if (price != null) lines.add(Planner.tr("wiki.price_each", GearScreenFormat.coins(price), GearScreenFormat.coins(price * shard.toMax)));
			out.add(new Entry(shard.name + " Shard", Planner.tr("wiki.kind.shard"), lines));
		}

		for (GearData.Tip tip : gear.tips) {
			List<String> lines = new ArrayList<>();
			lines.add("§7" + tip.effect);
			Double price = Watchers.price(tip.id);
			if (price != null) lines.add(Planner.tr("wiki.price", GearScreenFormat.coins(price)));
			out.add(new Entry(tip.name, Planner.tr("wiki.kind.upgrade"), lines));
		}

		for (GameData.Tree tree : Planner.data.trees) {
			List<String> lines = new ArrayList<>();
			lines.add(Planner.tr("wiki.tree_xp", num(tree.xpPerLog)));
			if (!tree.sweepBreakpoints.isEmpty()) {
				StringBuilder steps = new StringBuilder();
				for (int i = 0; i < Math.min(6, tree.sweepBreakpoints.size()); i++) steps.append(i == 0 ? "" : " · ").append(i + 1).append(": ").append(num(tree.sweepBreakpoints.get(i)));
				lines.add(Planner.tr("wiki.tree_sweep", steps));
			}
			double size = TreeProgress.treeSize(tree.id).logs();
			if (size > 0) lines.add(Planner.tr("wiki.tree_size", Planner.whole(size)));
			if (SkyBlockReader.statsKnown()) {
				double sweep = SkyBlockReader.stats().sweep();
				lines.add(Planner.tr("wiki.tree_you", dev.foragingplanner.core.TreeMath.logsPerHit(tree, sweep), num(sweep)));
			}
			out.add(new Entry(tree.name, Planner.tr("wiki.kind.tree"), lines));
		}
		return out;
	}

	private static String stat(String key) {
		return switch (key) {
			case "sweep" -> "Sweep";
			case "fortune" -> "Foraging Fortune";
			case "wisdom" -> "Foraging Wisdom";
			case "dailyWhispers" -> Planner.tr("wiki.stat.whispers");
			case "giftBonus" -> Planner.tr("wiki.stat.gifts");
			case "strength" -> "Strength";
			case "speed" -> "Speed";
			default -> StatsScreen.capital(key);
		};
	}

	/** Names for tab completion. */
	public static List<String> names() {
		List<String> names = new ArrayList<>();
		for (Entry e : all()) names.add(e.name().replaceAll("\\s*\\(.*\\)", "").replace(' ', '_'));
		return names.stream().distinct().toList();
	}

	/** Best matches: exact name first, then names that start with or contain the term. */
	public static List<Entry> search(String term) {
		String q = norm(term.replace('_', ' '));
		List<Entry> exact = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
		for (Entry e : all()) {
			String n = norm(e.name());
			String base = norm(e.name().replaceAll("\\s*\\(.*\\)", ""));
			if (n.equals(q) || base.equals(q)) exact.add(e);
			else if (n.startsWith(q)) starts.add(e);
			else if (n.contains(q) || e.lines().stream().anyMatch(l -> norm(Planner.strip(l)).contains(q) && q.length() >= 4)) contains.add(e);
		}
		List<Entry> out = new ArrayList<>(exact);
		out.addAll(starts);
		out.addAll(contains);
		return out;
	}

	/** Sends the answer to chat. */
	public static void show(String term, java.util.function.Consumer<Component> chat) {
		List<Entry> found = search(term);
		if (found.isEmpty()) {
			chat.accept(Component.literal("§c[FP] " + Planner.tr("wiki.none", term)));
			chat.accept(link(term));
			return;
		}

		Entry e = found.getFirst();
		chat.accept(Component.literal("§8§m                                        "));
		chat.accept(Component.literal("§a§l" + e.name() + " §8· §7" + e.kind()));
		for (String line : e.lines()) chat.accept(Component.literal("  " + (line.startsWith("§") ? line : "§f" + line)));
		chat.accept(link(e.name().replaceAll("\\s*\\(.*\\)", "")));

		if (found.size() > 1) {
			MutableComponent more = Component.literal("§7" + Planner.tr("wiki.more") + " ");
			for (int i = 1; i < Math.min(6, found.size()); i++) {
				String name = found.get(i).name();
				String command = "/fp wiki " + name.replaceAll("\\s*\\(.*\\)", "").replace(' ', '_');
				more.append(Component.literal("§b" + name).withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))));
				if (i < Math.min(6, found.size()) - 1) more.append(Component.literal("§7, "));
			}
			chat.accept(more);
		}
		chat.accept(Component.literal("§8§m                                        "));
	}

	private static Component link(String name) {
		String url = "https://hypixelskyblock.minecraft.wiki/w/" + URLEncoder.encode(name.replace(' ', '_'), StandardCharsets.UTF_8).replace("%27", "'");
		return Component.literal("  §9§n" + Planner.tr("wiki.open")).withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
	}
}
