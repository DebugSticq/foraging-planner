package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Shows what the mod could read and what is missing, with a hint how to fix each gap, and the
 * backups of statistics and settings.
 */
public final class CheckScreen extends ForestScreen {
	private static final String[] PAGES = {"check", "backups"};
	private String armed = "";
	private long armedAt;

	public CheckScreen(Screen parent) {
		this(parent, 0);
	}

	public CheckScreen(Screen parent, int page) {
		super(parent, PAGES, new Item[] {Items.SPYGLASS, Items.CHEST}, page);
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("check." + key, args);
	}

	@Override
	protected String title() {
		return tr("title");
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
		return Items.SPYGLASS;
	}

	@Override
	protected List<Line> build(int page) {
		return page == 0 ? check() : backups();
	}

	/** ok = 1 green, 0 amber (works, but could be better), -1 red (missing). */
	private Line status(String icon, String key, int ok, String detail) {
		String mark = ok > 0 ? "✔" : ok == 0 ? "!" : "✘";
		int color = ok > 0 ? UI_ACCENT : ok == 0 ? UI_AMBER : UI_DANGER;
		String sub = ok > 0 ? detail : tr(key + "_fix") + (detail.isEmpty() ? "" : " · " + detail);
		return row(icon, tr(key), sub, mark, color, null);
	}

	private static String age(long ms) {
		if (ms <= 0) return tr("never");
		long min = (System.currentTimeMillis() - ms) / 60_000;
		if (min < 60) return tr("minutes_ago", min);
		if (min < 48 * 60) return tr("hours_ago", min / 60);
		return tr("days_ago", min / 1440);
	}

	private List<Line> check() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about");
		Minecraft mc = Minecraft.getInstance();
		Progress p = Progress.get();
		ModConfig c = Planner.config;

		out.add(header(tr("section_game")));
		boolean sky = SkyBlockReader.inSkyBlock();
		out.add(status("grass_block", "skyblock", sky ? 1 : -1, sky ? String.valueOf(SkyBlockReader.area()) : ""));
		boolean forest = SkyBlockReader.inForagingArea();
		out.add(status("oak_log", "area", forest ? 1 : 0, forest ? HudPresets.place() : ""));
		boolean stats = SkyBlockReader.statsKnown();
		out.add(status("iron_axe", "stats", stats ? 1 : -1, stats ? tr("stats_value", Planner.whole(SkyBlockReader.stats().sweep()), Planner.whole(SkyBlockReader.stats().fortune())) : ""));
		out.add(status("sunflower", "profile", ProfileStore.label().isEmpty() ? 0 : 1, ProfileStore.label()));

		out.add(header(tr("section_menus")));
		out.add(status("oak_sapling", "hotf", c.perkLevels.isEmpty() ? -1 : 1, tr("count_age", c.perkLevels.size(), age(p.hotfRead))));
		out.add(status("chest", "hunting_box", p.huntingBoxRead == 0 ? -1 : 1, tr("count_age", p.huntingBox.size(), age(p.huntingBoxRead))));
		out.add(status("prismarine_shard", "attributes", p.attributeLevels.isEmpty() ? -1 : 1, tr("count", p.attributeLevels.size())));
		int worn = 0;
		if (mc.player != null) {
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				if (!GearAdvisor.itemId(mc.player.getItemBySlot(slot)).isEmpty()) worn++;
			}
		}
		out.add(status("iron_chestplate", "armor", worn == 4 ? 1 : 0, tr("count", worn)));
		out.add(status("book", "collections", p.collections.isEmpty() ? -1 : 1, tr("count", p.collections.size())));

		out.add(header(tr("section_data")));
		int bz = BazaarPrices.BUY.size();
		out.add(status("emerald", "bazaar", bz > 100 ? 1 : bz > 0 ? 0 : -1, tr("count", bz)));
		int pack = LegacyItems.packIndexSize();
		out.add(status("painting", "pack", pack > 0 ? 1 : 0, tr("count", pack)));
		out.add(status("name_tag", "seen", p.seenItems.size() + p.seenMobs.size() > 0 ? 1 : 0, tr("seen_value", p.seenItems.size(), p.seenMobs.size())));
		out.add(status("gold_ingot", "leaderboard", !c.leaderboardShare ? 1 : Leaderboard.verified() ? 1 : 0,
				c.leaderboardShare ? (Leaderboard.verified() ? tr("verified") : "") : tr("off")));
		out.add(status("feather", "perf", Perf.light() ? 0 : 1, Perf.light() ? tr("light_on") : tr("light_off")));
		List<String> backups = Backups.list();
		out.add(status("chest", "backup", backups.isEmpty() ? (c.autoBackup ? 0 : -1) : 1, backups.isEmpty() ? "" : Backups.pretty(backups.get(0))));

		out.add(spacer(4));
		out.add(button(tr("copy_report"), () -> {
			StringBuilder b = new StringBuilder("Foraging Planner " + ModVersion.get() + " check\n");
			for (Line line : check()) if (line.key() != null) b.append(line.key().trim()).append('\n');
			Minecraft.getInstance().keyboardHandler.setClipboard(b.toString());
			Toasts.show(Toasts.Kind.INFO, "paper", tr("copied"), "");
		}));
		return out;
	}

	private List<Line> backups() {
		List<Line> out = new ArrayList<>();
		subtitle = tr("about_backups");
		out.add(toggle("chest", tr("auto_backup"), tr("auto_backup_sub"), () -> Planner.config.autoBackup, v -> {
			Planner.config.autoBackup = v;
			Planner.config.save();
		}));
		out.add(button(tr("backup_now"), () -> {
			String name = Backups.make("manual");
			Toasts.show(name == null ? Toasts.Kind.ALARM : Toasts.Kind.INFO, "chest", tr(name == null ? "backup_failed" : "backup_done"),
					name == null ? "" : Backups.pretty(name));
		}));
		out.add(header(tr("backup_list")));
		List<String> all = Backups.list();
		if (all.isEmpty()) out.add(text(tr("backup_none"), UI_DIM));
		boolean expired = System.currentTimeMillis() - armedAt > 5_000;
		for (String name : all) {
			boolean confirm = name.equals(armed) && !expired;
			out.add(row("clock", Backups.pretty(name), confirm ? tr("restore_confirm") : tr("restore_hint"),
					confirm ? tr("restore_now") : "↺", confirm ? UI_DANGER : UI_MUTED, (lx, w, button) -> {
						if (name.equals(armed) && System.currentTimeMillis() - armedAt <= 5_000) {
							armed = "";
							boolean ok = Backups.restore(name);
							Toasts.show(ok ? Toasts.Kind.INFO : Toasts.Kind.ALARM, "chest", tr(ok ? "restored" : "restore_failed"), Backups.pretty(name));
						} else {
							armed = name;
							armedAt = System.currentTimeMillis();
						}
					}));
		}
		return out;
	}

	@Override
	protected void closed() {
		Planner.config.save();
	}
}
