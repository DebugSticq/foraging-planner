package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Setup in five steps: SkyBlock and profile, stats from the tab list, Heart of the Forest, Hunting
 * Box and attributes, resource pack. Each step checks itself live and says what to do.
 */
public final class WizardScreen extends ForestScreen {
	private static final String[] STEPS = {"join", "tab", "hotf", "hunting", "pack"};
	private static final Item[] ICONS = {Items.GRASS_BLOCK, Items.IRON_AXE, Items.OAK_SAPLING, Items.CHEST, Items.PAINTING};

	public WizardScreen(Screen parent) {
		this(parent, firstOpenStep());
	}

	public WizardScreen(Screen parent, int step) {
		super(parent, STEPS, ICONS, step);
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("wizard." + key, args);
	}

	/** The first step that is not done yet. */
	private static int firstOpenStep() {
		for (int i = 0; i < STEPS.length; i++) if (state(i) < 1) return i;
		return STEPS.length - 1;
	}

	/** 1 done, 0 optional / partly, -1 missing. */
	static int state(int step) {
		Progress p = Progress.get();
		return switch (STEPS[step]) {
			case "join" -> SkyBlockReader.inSkyBlock() ? 1 : -1;
			case "tab" -> SkyBlockReader.statsKnown() ? 1 : -1;
			case "hotf" -> Planner.config.perkLevels.isEmpty() ? -1 : 1;
			case "hunting" -> p.huntingBoxRead > 0 && !p.attributeLevels.isEmpty() ? 1 : p.huntingBoxRead > 0 || !p.attributeLevels.isEmpty() ? 0 : -1;
			default -> LegacyItems.packIndexSize() > 0 ? 1 : 0;
		};
	}

	@Override
	protected String title() {
		return tr("title");
	}

	@Override
	protected String tagline() {
		int done = 0;
		for (int i = 0; i < STEPS.length; i++) if (state(i) > 0) done++;
		return tr("progress", done, STEPS.length);
	}

	@Override
	protected String pageName(int page) {
		int st = state(page);
		return (st > 0 ? "✔ " : st == 0 ? "• " : "") + tr("step_" + STEPS[page]);
	}

	@Override
	protected Item logo() {
		return Items.NETHER_STAR;
	}

	@Override
	protected String footerHint() {
		return tr("footer");
	}

	/** Last known state per step, so finishing a step while the screen is open gets a little sound. */
	private final int[] lastStates = {-2, -2, -2, -2, -2};

	@Override
	protected List<Line> build(int page) {
		List<Line> out = new ArrayList<>();
		String step = STEPS[page];
		int st = state(page);
		if (lastStates[page] != -2 && lastStates[page] < 1 && st == 1) UiSound.success();
		lastStates[page] = st;
		subtitle = tr("about_" + step);

		String mark = st > 0 ? "✔" : st == 0 ? "!" : "✘";
		int color = st > 0 ? UI_ACCENT : st == 0 ? UI_AMBER : UI_DANGER;
		out.add(row(iconFor(page), tr("status_" + step), tr(st > 0 ? "done" : st == 0 ? "partly" : "missing"), mark, color, null));
		out.add(header(tr("how")));
		for (int i = 1; i <= 4; i++) {
			String key = "foragingplanner.wizard." + step + "_" + i;
			String line = tr(step + "_" + i);
			if (line.equals(key)) break;
			out.add(text(i + ".  " + line, UI_TEXT));
		}
		String extra = detail(step);
		if (!extra.isEmpty()) out.add(text(extra, UI_MUTED));
		out.add(spacer(6));

		List<String> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();
		if (page > 0) {
			labels.add("‹ " + tr("back"));
			actions.add(() -> openPage(page - 1));
		}
		if (step.equals("tab")) {
			labels.add(tr("manual"));
			actions.add(() -> minecraft.gui.setScreen(SettingsScreen.showing(this, "manual")));
		}
		if (page < STEPS.length - 1) {
			labels.add((st > 0 ? tr("next") : tr("skip")) + " ›");
			actions.add(() -> openPage(page + 1));
		} else {
			labels.add(tr("finish"));
			actions.add(this::onClose);
		}
		out.add(buttons(labels.toArray(String[]::new), actions.toArray(Runnable[]::new)));
		if (page == STEPS.length - 1) {
			out.add(spacer(4));
			out.add(button(tr("open_check"), () -> minecraft.gui.setScreen(new CheckScreen(this))));
		}
		return out;
	}

	private static String iconFor(int page) {
		return switch (STEPS[page]) {
			case "join" -> "grass_block";
			case "tab" -> "iron_axe";
			case "hotf" -> "oak_sapling";
			case "hunting" -> "chest";
			default -> "painting";
		};
	}

	private static String detail(String step) {
		Progress p = Progress.get();
		return switch (step) {
			case "join" -> SkyBlockReader.inSkyBlock() ? tr("detail_join", String.valueOf(SkyBlockReader.area()), ProfileStore.label() == null ? "" : ProfileStore.label()) : "";
			case "tab" -> SkyBlockReader.statsKnown() ? tr("detail_tab", Planner.whole(SkyBlockReader.stats().sweep()), Planner.whole(SkyBlockReader.stats().fortune())) : "";
			case "hotf" -> Planner.config.perkLevels.isEmpty() ? "" : tr("detail_hotf", Planner.config.perkLevels.size());
			case "hunting" -> tr("detail_hunting", p.huntingBox.size(), p.attributeLevels.size());
			default -> LegacyItems.packIndexSize() > 0 ? tr("detail_pack", LegacyItems.packIndexSize()) : "";
		};
	}

	@Override
	protected void closed() {
		Planner.config.save();
	}
}
