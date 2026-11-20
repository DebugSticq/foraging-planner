package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.UI_TILE;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Chooses the entries of a name list from things the mod has seen (drops, shards, mobs, Bazaar
 * products) instead of typing them. Rare drops have three states: always, never and "auto" (counted
 * when the value is high enough).
 */
public final class PickerScreen extends ForestScreen {
	/** A name the player can pick, with its SkyBlock id ("" when unknown) for icon and price. */
	public record Candidate(String name, String id) {}

	private static final String[] TWO_PAGES = {"selected", "available"};
	private static final String[] THREE_PAGES = {"always", "never", "available", "bazaar"};

	private final String name;
	private final boolean three;
	private final Supplier<List<String>> getOn;
	private final Consumer<List<String>> setOn;
	private final Supplier<List<String>> getOff;
	private final Consumer<List<String>> setOff;
	private final Supplier<Map<String, List<Candidate>>> sections;
	private final Map<String, ItemStack> stacks = new HashMap<>();
	private String pending = "";

	private PickerScreen(Screen parent, String name, boolean three, Supplier<List<String>> getOn, Consumer<List<String>> setOn,
						Supplier<List<String>> getOff, Consumer<List<String>> setOff, Supplier<Map<String, List<Candidate>>> sections) {
		super(parent, three ? THREE_PAGES : TWO_PAGES,
				three ? new Item[] {Items.GOLD_INGOT, Items.BARRIER, Items.CHEST, Items.EMERALD} : new Item[] {Items.SLIME_BALL, Items.CHEST}, 0);
		this.name = name;
		this.three = three;
		this.getOn = getOn;
		this.setOn = setOn;
		this.getOff = getOff;
		this.setOff = setOff;
		this.sections = sections;
	}

	// Factories

	/** Rare drops: always / never / by value. */
	public static PickerScreen rare(Screen parent) {
		ModConfig c = Planner.config;
		return new PickerScreen(parent, "rare_items", true, () -> c.rareItems, v -> c.rareItems = v, () -> c.rareIgnore, v -> c.rareIgnore = v,
				PickerScreen::itemSections);
	}

	/** A list of item names (protected items, Safari ground items). */
	public static PickerScreen items(Screen parent, String name, Supplier<List<String>> get, Consumer<List<String>> set) {
		return new PickerScreen(parent, name, false, get, set, null, null, PickerScreen::itemSections);
	}

	/** A list of mob or NPC names; defaults are offered too. */
	public static PickerScreen mobs(Screen parent, String name, Supplier<List<String>> get, Consumer<List<String>> set, List<String> defaults) {
		return new PickerScreen(parent, name, false, get, set, null, null, () -> {
			Map<String, List<Candidate>> out = new LinkedHashMap<>();
			List<Candidate> suggested = new ArrayList<>();
			for (String d : defaults) suggested.add(new Candidate(d, ""));
			if (name.startsWith("critter")) {
				for (String n : Progress.get().critterCaught) suggested.add(new Candidate(n, ""));
				for (String n : Progress.get().critterMissing) suggested.add(new Candidate(n, ""));
			}
			out.put("suggested", suggested);
			List<Candidate> seen = new ArrayList<>();
			for (String m : Progress.get().seenMobs) seen.add(new Candidate(m, ""));
			out.put("seen_mobs", seen);
			return out;
		});
	}

	private static Map<String, List<Candidate>> itemSections() {
		Map<String, List<Candidate>> out = new LinkedHashMap<>();
		List<Candidate> drops = new ArrayList<>();
		for (Progress.Drop d : Progress.get().drops) drops.add(new Candidate(d.name, d.id));
		out.put("drops", drops);
		List<Candidate> seen = new ArrayList<>();
		Progress.get().seenItems.forEach((n, id) -> seen.add(new Candidate(n, id)));
		seen.sort((a, b) -> Double.compare(Watchers.value(idFor(b), 1), Watchers.value(idFor(a), 1)));
		out.put("seen_items", seen);
		List<Candidate> shards = new ArrayList<>();
		for (GearData.Shard s : GearData.get().shards) shards.add(new Candidate(s.name + " Shard", s.id));
		out.put("shards", shards);
		return out;
	}

	// Rare state, also used by the statistics

	/** 1 = always, -1 = never, 0 = by value. */
	public static int rareState(String itemName) {
		ModConfig c = Planner.config;
		if (contains(c.rareIgnore, itemName)) return -1;
		if (contains(c.rareItems, itemName)) return 1;
		return 0;
	}

	public static void setRareState(String itemName, int state) {
		ModConfig c = Planner.config;
		List<String> on = new ArrayList<>(c.rareItems), off = new ArrayList<>(c.rareIgnore);
		remove(on, itemName);
		remove(off, itemName);
		if (state > 0) on.add(itemName);
		if (state < 0) off.add(itemName);
		c.rareItems = on;
		c.rareIgnore = off;
		c.save();
	}

	/** auto → always → never → auto (backwards with {@code forward = false}). */
	public static int cycleRare(String itemName, boolean forward) {
		int next = switch (rareState(itemName)) {
			case 0 -> forward ? 1 : -1;
			case 1 -> forward ? -1 : 0;
			default -> forward ? 0 : 1;
		};
		setRareState(itemName, next);
		return next;
	}

	public static String stateLabel(int state) {
		return Planner.tr("picker.state_" + (state > 0 ? "always" : state < 0 ? "never" : "auto"));
	}

	public static int stateColor(int state) {
		return state > 0 ? UI_AMBER : state < 0 ? UI_DANGER : UI_MUTED;
	}

	private static boolean contains(List<String> list, String n) {
		for (String e : list) if (e.trim().equalsIgnoreCase(n.trim())) return true;
		return false;
	}

	private static void remove(List<String> list, String n) {
		list.removeIf(e -> e.trim().equalsIgnoreCase(n.trim()));
	}

	/** Bazaar shard ids are "SHARD_X"; a seen "X Shard" without id gets that form. */
	private static String idFor(Candidate c) {
		if (c.id() != null && !c.id().isEmpty()) return c.id();
		String n = c.name();
		if (n.endsWith(" Shard")) return "SHARD_" + n.substring(0, n.length() - 6).toUpperCase(Locale.ROOT).replace(' ', '_');
		return n.toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "");
	}

	// Screen

	@Override
	protected String title() {
		return Planner.tr("picker.title");
	}

	@Override
	protected String tagline() {
		return Planner.tr("settings." + name);
	}

	@Override
	protected String pageName(int page) {
		String key = (three ? THREE_PAGES : TWO_PAGES)[page];
		int count = switch (key) {
			case "selected", "always" -> getOn.get().size();
			case "never" -> getOff.get().size();
			default -> -1;
		};
		return Planner.tr("picker.page_" + key) + (count >= 0 ? " (" + count + ")" : "");
	}

	@Override
	protected Item logo() {
		return three ? Items.GOLD_INGOT : Items.NAME_TAG;
	}

	@Override
	protected String footerHint() {
		return Planner.tr(three ? "picker.keys_three" : "picker.keys_two");
	}

	@Override
	protected long rebuildNs() {
		return 60_000_000_000L;
	}

	@Override
	protected void closed() {
		Planner.config.save();
		PlannerHud.rebuild();
	}

	private int state(String n) {
		if (three) return rareState(n);
		return contains(getOn.get(), n) ? 1 : 0;
	}

	private void set(String n, int state) {
		if (three) {
			setRareState(n, state);
			return;
		}
		List<String> on = new ArrayList<>(getOn.get());
		remove(on, n);
		if (state > 0) on.add(n);
		setOn.accept(on);
		Planner.config.save();
	}

	private void cycle(String n, boolean forward) {
		if (three) cycleRare(n, forward);
		else set(n, state(n) > 0 ? 0 : 1);
	}

	@Override
	protected List<Line> build(int page) {
		String key = (three ? THREE_PAGES : TWO_PAGES)[page];
		List<Line> out = new ArrayList<>();
		subtitle = Planner.tr("picker.about_" + (three ? "three" : "two"));

		switch (key) {
			case "selected", "always" -> {
				out.add(field(Planner.tr("picker.add_label"), pending, 40, v -> pending = v));
				out.add(button(Planner.tr("picker.add"), () -> {
					String n = pending.trim();
					if (n.isEmpty()) return;
					set(n, 1);
					pending = "";
					UiSound.success();
				}));
				List<String> on = getOn.get();
				out.add(header(Planner.tr("picker.page_" + key)));
				if (on.isEmpty()) out.add(text(Planner.tr("picker.empty"), UI_DIM));
				for (String n : List.copyOf(on)) out.add(entry(new Candidate(n, lookupId(n)), "custom"));
			}
			case "never" -> {
				List<String> off = getOff.get();
				if (off.isEmpty()) out.add(text(Planner.tr("picker.empty"), UI_DIM));
				for (String n : List.copyOf(off)) out.add(entry(new Candidate(n, lookupId(n)), "custom"));
			}
			case "available" -> {
				java.util.Set<String> shown = new java.util.HashSet<>();
				for (Map.Entry<String, List<Candidate>> section : sections.get().entrySet()) {
					List<Line> lines = new ArrayList<>();
					for (Candidate cand : section.getValue()) {
						String low = cand.name().toLowerCase(Locale.ROOT);
						if (cand.name().isBlank() || !shown.add(low) || state(cand.name()) != 0) continue;
						lines.add(entry(cand, section.getKey()));
					}
					if (lines.isEmpty()) continue;
					out.add(header(Planner.tr("picker.section_" + section.getKey()) + " · " + lines.size()));
					out.addAll(lines);
				}
				if (out.isEmpty()) out.add(text(Planner.tr("picker.nothing_seen"), UI_DIM));
			}
			default -> {
				java.util.Set<String> listed = new java.util.HashSet<>();
				for (List<Candidate> list : sections.get().values()) for (Candidate cand : list) listed.add(cand.name().toLowerCase(Locale.ROOT));
				List<String> ids = new ArrayList<>(BazaarPrices.BUY.keySet());
				ids.sort(null);
				if (ids.isEmpty()) out.add(text(Planner.tr("picker.no_bazaar"), UI_DIM));
				for (String id : ids) {
					String n = StatsScreen.prettyName(id.startsWith("SHARD_") ? id.substring(6) + "_SHARD" : id);
					if (state(n) != 0 || listed.contains(n.toLowerCase(Locale.ROOT))) continue;
					out.add(entry(new Candidate(n, id), "bazaar"));
				}
			}
		}
		return out;
	}

	private static String lookupId(String n) {
		String id = Progress.get().seenItems.get(n);
		if (id != null && !id.isEmpty()) return id;
		for (Progress.Drop d : Progress.get().drops) if (d.name.equalsIgnoreCase(n)) return d.id;
		for (GearData.Shard s : GearData.get().shards) if ((s.name + " Shard").equalsIgnoreCase(n)) return s.id;
		return "";
	}

	private ItemStack stack(Candidate c) {
		String id = idFor(c);
		return stacks.computeIfAbsent(id, key -> {
			Item fallback = key.startsWith("SHARD_") ? Items.PRISMARINE_SHARD : Items.PAPER;
			try {
				ItemStack icon = LegacyItems.packIcon(key, fallback);
				return icon != null ? icon : new ItemStack(fallback);
			} catch (RuntimeException e) {
				return new ItemStack(fallback);
			}
		});
	}

	private Line entry(Candidate c, String source) {
		String n = c.name();
		double price = Watchers.value(idFor(c), 1);
		String sub = (price > 0 ? GearScreenFormat.coins(price) + " · " : "") + Planner.tr("picker.section_" + source);
		String searchKey = (n + " " + sub).toLowerCase(Locale.ROOT);
		return new Line(32, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 32, a, h);
			int lift = (int) Math.round(h * 1.5);
			round(g, x + 6, y + 5 - lift, 22, 22, 6, alpha(UI_TILE, a));
			if (a > 0.45) g.item(stack(c), x + 9, y + 8 - lift);
			int st = state(n);
			String chip = three ? stateLabel(st) : Planner.tr(st > 0 ? "picker.state_on" : "picker.state_off");
			int color = three ? stateColor(st) : st > 0 ? UI_ACCENT : UI_MUTED;
			int cw = font.width(chip) + 14;
			int cx = x + w - cw - 8;
			round(g, cx, y + 8 - lift, cw, 16, 6, alpha(st != 0 ? (color & 0x00FFFFFF) | 0x33000000 : 0x1AFFFFFF, a));
			g.text(font, chip, cx + 7, y + 12 - lift, alpha(color, a), false);
			g.text(font, fit(font, n, cx - x - 44), x + 35, y + 6 - lift, alpha(UI_TEXT, a));
			g.text(font, fit(font, sub, cx - x - 44), x + 35, y + 18 - lift, alpha(UI_MUTED, a), false);
		}, (lx, w, button) -> cycle(n, button != 1), null, searchKey);
	}
}
