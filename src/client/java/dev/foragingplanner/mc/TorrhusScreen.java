package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.ObservationParser;

/** Torrhus Canyon: Helix trees, Torrhus Belt planner, drops of the island and Safari tickets. */
public final class TorrhusScreen extends ForestScreen {
	private static final String[] PAGES = {"overview", "belt", "drops", "safari"};

	/** Raw materials of the Torrhus Belt: name, Bazaar id, amount. */
	private static final Object[][] BELT = {
			{"Mangrove Log", "MANGROVE_LOG", 10_240},
			{"Helix Log", "HELIX_LOG", 51_200},
			{"Ruby Veilshroom", "RUBY_VEILSHROOM", 6_400},
			{"Sea Lumies", "SEA_LUMIES", 160},
			{"Stretching Sticks", "STRETCHING_STICKS", 64},
			{"Bottled Mischief", "BOTTLED_MISCHIEF", 16},
	};

	public TorrhusScreen(Screen parent) {
		this(parent, ForestScreen.lastPage(TorrhusScreen.class, 0));
	}

	public TorrhusScreen(Screen parent, int page) {
		super(parent, PAGES, new Item[] {Items.STRIPPED_BIRCH_WOOD, Items.LEATHER, Items.CHEST, Items.PAPER}, page);
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("torrhus." + key, args);
	}

	@Override
	protected String menuKey() {
		return "torrhus";
	}

	@Override
	protected String title() {
		return "Torrhus Canyon";
	}

	@Override
	protected String tagline() {
		return tr("subtitle");
	}

	@Override
	protected String pageName(int page) {
		return tr("page_" + PAGES[page]);
	}

	@Override
	protected Item logo() {
		return Items.STRIPPED_BIRCH_WOOD;
	}

	@Override
	protected List<Line> build(int page) {
		return switch (page) {
			case 1 -> belt();
			case 2 -> drops();
			case 3 -> safari();
			default -> overview();
		};
	}

	private List<Line> overview() {
		List<Line> out = new ArrayList<>();
		out.add(header(tr("trees")));
		int helix = TorrhusScanner.knownHelix();
		out.add(row("stripped_birch_wood", tr("helix_known", helix), tr("helix_known_sub"), null, 0, null));
		Map<String, Integer> learned = KnownTrees.learnedCounts();
		int total = learned.values().stream().mapToInt(Integer::intValue).sum();
		out.add(row("writable_book", tr("learned", total), tr("export_sub"), tr("export"), UI_ACCENT, (x, w, b) -> {
			Minecraft.getInstance().keyboardHandler.setClipboard(KnownTrees.exportJson());
			Toasts.show(Toasts.Kind.INFO, "writable_book", tr("export_done_title"), tr("export_done"));
			UiSound.success();
		}));
		out.add(row("filled_map", tr("map"), tr("map_sub"), tr("open"), UI_ACCENT, (x, w, b) -> MapScreen.open()));

		out.add(header(tr("nearby")));
		long shrooms = TorrhusScanner.found().stream().filter(f -> f.name().equals("Ruby Veilshroom")).count();
		long hives = TorrhusScanner.found().size() - shrooms;
		out.add(row("crimson_fungus", tr("veilshrooms", shrooms), null, null, 0, null));
		out.add(row("bee_nest", tr("honeyhives", hives), null, null, 0, null));
		out.add(row("honeycomb", tr("honey_critters", TorrhusScanner.critters().size()), String.join(", ", TorrhusScanner.HONEY_CRITTERS), null, 0, null));
		if (!TorrhusScanner.here()) out.add(text("§7" + tr("not_here"), UI_MUTED));
		return out;
	}

	/** How many of an item the player has: sacks (read from the sack menus) plus inventory. */
	private static long have(String name) {
		long n = Progress.get().sackCounts.getOrDefault(name, 0L);
		var player = Minecraft.getInstance().player;
		if (player != null) {
			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				ItemStack stack = player.getInventory().getItem(i);
				if (!stack.isEmpty() && Planner.strip(stack.getHoverName().getString()).trim().equalsIgnoreCase(name)) n += stack.getCount();
			}
		}
		return n;
	}

	private List<Line> belt() {
		List<Line> out = new ArrayList<>();
		out.add(header(tr("belt_title")));
		out.add(text("§7" + tr("belt_effect"), UI_MUTED));
		double missingCost = 0;
		boolean unknownPrice = false;
		int done = 0;
		for (Object[] row : BELT) {
			String name = (String) row[0];
			String id = (String) row[1];
			int need = (Integer) row[2];
			long have = have(name);
			double fraction = Math.min(1, have / (double) need);
			if (have >= need) done++;
			long missing = Math.max(0, need - have);
			Double price = BazaarPrices.BUY.get(id);
			if (missing > 0) {
				if (price == null) unknownPrice = true;
				else missingCost += price * missing;
			}
			String right = Planner.whole(have) + " / " + Planner.whole(need);
			out.add(bar(beltIcon(name), name + (missing > 0 && price != null ? " §8· " + GearScreenFormat.coins(price * missing) : ""), right, fraction,
					have >= need ? UI_ACCENT : UI_AMBER, null));
		}
		out.add(spacer(4));
		out.add(row("emerald", tr("belt_missing_cost", GearScreenFormat.coins(missingCost) + (unknownPrice ? " + ?" : "")),
				tr("belt_done", done, BELT.length), null, 0, null));
		out.add(text("§7" + tr("belt_hint"), UI_MUTED));
		return out;
	}

	private static String beltIcon(String name) {
		return switch (name) {
			case "Mangrove Log" -> "mangrove_log";
			case "Helix Log" -> "stripped_birch_log";
			case "Ruby Veilshroom" -> "crimson_fungus";
			case "Sea Lumies" -> "sea_pickle";
			case "Stretching Sticks" -> "stick";
			default -> "potion";
		};
	}

	private List<Line> drops() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		double hours = Math.max(1 / 60.0, p.torrhusSeconds / 3600.0);
		out.add(header(tr("drops_title", GearScreenFormat.duration(p.torrhusSeconds))));
		if (p.torrhusDrops.isEmpty()) out.add(text("§7" + tr("drops_none"), UI_MUTED));
		double total = 0;
		List<Map.Entry<String, Long>> list = new ArrayList<>(p.torrhusDrops.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (var e : list) {
			String id = ObservationParser.itemId(e.getKey());
			Double price = BazaarPrices.BUY.get(id);
			double value = price == null ? 0 : price * e.getValue();
			total += value;
			String per = String.format(Locale.ROOT, "%.1f", e.getValue() / hours);
			out.add(row("chest", e.getKey(), tr("drops_per_hour", per) + (price == null ? "" : " · " + GearScreenFormat.coins(value)),
					Planner.whole(e.getValue()) + "x", UI_ACCENT, null));
		}
		if (total > 0) out.add(row("emerald", tr("drops_value", GearScreenFormat.coins(total), GearScreenFormat.coins(total / hours)), null, null, 0, null));
		out.add(button(tr("drops_reset"), () -> {
			p.torrhusDrops.clear();
			p.torrhusSeconds = 0;
			Progress.save(true);
			rebuild();
		}));
		return out;
	}

	private List<Line> safari() {
		List<Line> out = new ArrayList<>();
		Progress p = Progress.get();
		out.add(header(tr("tickets")));
		out.add(row("paper", tr("tickets_count", p.safariTickets < 0 ? "?" : String.valueOf(p.safariTickets)), tr("tickets_sub"), null, 0, null));
		boolean perk = Planner.config.perkLevels.getOrDefault("Free Trial", 0) > 0;
		boolean today = p.freeTicketDay == java.time.LocalDate.now().toEpochDay();
		out.add(row("oak_sapling", tr("free_trial"), perk ? (today ? tr("free_trial_done") : tr("free_trial_open")) : tr("free_trial_no_perk"),
				perk ? (today ? "✔" : "!") : "-", perk && !today ? UI_AMBER : UI_ACCENT, null));

		out.add(header(tr("miria")));
		for (String[] t : new String[][] {{"UNCOMMON", "Basic"}, {"RARE", "Economy"}, {"EPIC", "Premium"}, {"LEGENDARY", "First-Class"}}) {
			out.add(row("paper", t[1] + " Safari Ticket", tr("from_bracket", t[0]), null, 0, null));
		}
		var guess = ContestTracker.forecast();
		if (guess != null && guess.bracket() != null && ContestTracker.host().toLowerCase(Locale.ROOT).contains("miria")) {
			String ticket = PlannerHud.ticketFor(guess.bracket().name);
			out.add(row("clock", tr("miria_forecast", guess.bracket().name), ticket == null ? tr("miria_no_ticket") : ticket + " Safari Ticket", null, 0, null));
		}

		out.add(header(tr("runs")));
		List<Progress.SafariRun> runs = p.safariRuns;
		if (runs.isEmpty()) out.add(text("§7" + tr("runs_none"), UI_MUTED));
		for (int i = runs.size() - 1; i >= Math.max(0, runs.size() - 5); i--) {
			Progress.SafariRun r = runs.get(i);
			out.add(row("ender_pearl", tr("run", GearScreenFormat.duration(r.durationMs / 1000), r.catches), tr("run_sub", r.capsulesUsed, Planner.whole(r.essence)), null, 0, null));
		}
		return out;
	}

	@SuppressWarnings("unused")
	private static long parse(String s) {
		return (long) ContestMath.parseNumber(s);
	}
}
