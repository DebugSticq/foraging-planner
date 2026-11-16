package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_DARK;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_SOFT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_ON_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW_HOVER;
import static dev.foragingplanner.mc.ForestTheme.UI_SIDEBAR;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.UI_TILE;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.approach;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.easeOutBack;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.mixArgb;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Gear advice: what to buy next and what it would bring, with live prices.
 *
 * <p>Four tabs: ranked recommendations, the gear the mod sees on the player, foraging attribute
 * shards with their cost to max, and upgrade tips. Clicking an entry puts the matching Bazaar or
 * auction house search into the chat box; the player still presses Enter themselves.
 */
public final class GearScreen extends Screen {
	private static final String[] TABS = {"picks", "plan", "gear", "shards", "tips"};
	private static final net.minecraft.world.item.Item[] TAB_ICONS = {Items.NETHER_STAR, Items.GOLD_INGOT, Items.IRON_CHESTPLATE, Items.AMETHYST_SHARD, Items.BOOK};
	private static final int RADIUS = 10;
	private static final int NAV_HEIGHT = 26;
	private static final int NAV_STEP = 30;
	private static final int ROW_GAP = 5;
	private static final long REFRESH_NS = 1_000_000_000L;

	private static boolean requested;

	private final Screen parent;
	private final long openedNs = System.nanoTime();
	private final double[] navHover = new double[TABS.length];
	private final Map<String, ItemStack> icons = new HashMap<>();
	private final Map<String, Double> rowHover = new HashMap<>();
	private final Map<String, Double> bars = new HashMap<>();

	private int tab;
	private int panelX, panelY, panelW, panelH, sideW, contentX, contentW, viewTop, viewBottom;
	private int doneX, doneY, doneW;
	private long tabNs = openedNs, lastFrameNs = openedNs, refreshedNs;
	private double pill, scroll, scrollTarget, doneHover;

	private GearAdvisor.Result result = new GearAdvisor.Result(List.of(), List.of(), -1, 0, 0, Map.of());
	private List<GearAdvisor.Pick> plan = List.of();
	private List<GearAdvisor.Pick> picks = List.of();
	private Map<String, GearData.Item> gear = Map.of();

	public GearScreen(Screen parent) {
		super(Component.literal("Foraging Planner"));
		this.parent = parent;
	}

	public static void requestOpen() {
		requested = true;
	}

	/** Opens the screen requested by a command once the chat screen has closed. */
	public static void tick(Minecraft client) {
		if (!requested) return;
		requested = false;
		client.gui.setScreen(new GearScreen(null));
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("gear." + key, args);
	}

	private static boolean animated() {
		return Planner.config.animations;
	}

	@Override
	protected void init() {
		panelW = Math.min(760, width - 20);
		panelH = Math.min(380, height - 20);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		sideW = panelW < 460 ? 44 : 132;
		contentX = panelX + sideW + 14;
		contentW = panelW - sideW - 30;
		viewTop = panelY + 52;
		viewBottom = panelY + panelH - 36;
		pill = tab;
		BazaarPrices.refreshIfDue();
		refresh();
	}

	private void refresh() {
		gear = GearAdvisor.current();
		result = GearAdvisor.recommend();
		picks = result.picks();
		plan = GearAdvisor.plan(result, Progress.get().budget);
		refreshedNs = System.nanoTime();
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
	}

	private void switchTab(int target) {
		if (target == tab || target < 0 || target >= TABS.length) return;
		tab = target;
		tabNs = System.nanoTime();
		scroll = scrollTarget = 0;
	}

	// Rows

	private int rowHeight() {
		return tab == 0 || tab == 1 || tab == 2 ? 46 : 38;
	}

	private int rowCount() {
		return switch (tab) {
			case 0 -> Math.max(1, picks.size() + result.locked().size());
			case 1 -> 1 + Math.max(1, plan.size());
			case 2 -> GearAdvisor.SLOTS.size();
			case 3 -> GearData.get().shards.size();
			default -> GearData.get().tips.size();
		};
	}

	private int maxScroll() {
		int content = rowCount() * (rowHeight() + ROW_GAP) - ROW_GAP;
		return Math.max(0, content - (viewBottom - viewTop));
	}

	private int rowY(int index) {
		return viewTop + index * (rowHeight() + ROW_GAP) - (int) Math.round(scroll);
	}

	private ItemStack icon(String id) {
		return icons.computeIfAbsent(id, key -> {
			var item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(key)).orElse(Items.PAPER);
			return new ItemStack(item);
		});
	}

	private static int rarityColor(String rarity) {
		return switch (rarity == null ? "" : rarity) {
			case "UNCOMMON" -> 0xFF55FF55;
			case "RARE" -> 0xFF5599FF;
			case "EPIC" -> 0xFFC266FF;
			case "LEGENDARY" -> 0xFFFFAA00;
			case "MYTHIC" -> 0xFFFF66FF;
			default -> UI_TEXT;
		};
	}

	private static String coins(double value) {
		double v = Math.abs(value);
		if (v >= 1e9) return String.format(Locale.US, "%.2fB", value / 1e9);
		if (v >= 1e6) return String.format(Locale.US, "%.2fM", value / 1e6);
		if (v >= 1e3) return String.format(Locale.US, "%.1fk", value / 1e3);
		return Planner.whole(value);
	}

	private static String signed(double value, String stat) {
		return (value >= 0 ? "+" : "") + Planner.decimal(value) + " " + stat;
	}

	// Drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (minecraft.level == null) extractPanorama(g, delta);
		extractBlurredBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - lastFrameNs) / 1e9);
		lastFrameNs = now;

		// Prices arrive in the background; pick them up once a second.
		if (now - refreshedNs > REFRESH_NS) refresh();

		double open = animated() ? easeOut((now - openedNs) / 280e6) : 1;
		g.fillGradient(0, 0, width, height, alpha(0xFF06100C, 0.70 * open), alpha(0xFF020604, 0.88 * open));

		float scale = (float) (animated() ? 0.965 + 0.035 * easeOutBack((now - openedNs) / 340e6) : 1);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f);
		pose.scale(scale, scale);
		pose.translate(-width / 2f, -height / 2f);

		shadow(g, panelX, panelY, panelW, panelH, RADIUS, open);
		round(g, panelX, panelY, panelW, panelH, RADIUS, alpha(UI_PANEL, open));
		round(g, panelX + 4, panelY + 4, sideW - 4, panelH - 8, RADIUS - 3, alpha(UI_SIDEBAR, open));
		ForestTheme.leaves(g, panelX, panelY, panelW, panelH, (System.nanoTime() - openedNs) / 1e9, open);
		roundOutline(g, panelX, panelY, panelW, panelH, RADIUS, alpha(0x22FFFFFF, open));
		g.fill(panelX + RADIUS, panelY, panelX + panelW - RADIUS, panelY + 1, alpha(ForestTheme.UI_ACCENT, 0.35 * open));

		drawSidebar(g, mouseX, mouseY, dt, open);
		drawHeader(g, now, open);
		drawRows(g, mouseX, mouseY, dt, now, open);
		drawFooter(g, mouseX, mouseY, dt, open);
		pose.popMatrix();
	}

	private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int x = panelX + 4;
		int w = sideW - 4;
		boolean compact = sideW < 100;

		int logoX = compact ? x + (w - 22) / 2 : x + 9;
		round(g, logoX, panelY + 13, 22, 22, 6, alpha(UI_ACCENT_DARK, open));
		g.item(icon("iron_axe"), logoX + 3, panelY + 16);

		if (!compact) {
			g.text(font, tr("title"), x + 37, panelY + 14, alpha(UI_TEXT, open), false);
			g.text(font, tr("subtitle"), x + 37, panelY + 25, alpha(UI_ACCENT, open), false);
		}

		int navTop = panelY + 50;
		pill = animated() ? approach(pill, tab, dt, 14) : tab;
		int pillY = navTop + (int) Math.round(pill * NAV_STEP);
		round(g, x + 6, pillY, w - 12, NAV_HEIGHT, 6, alpha(UI_ACCENT_SOFT, open));
		g.fill(x + 6, pillY + 7, x + 8, pillY + NAV_HEIGHT - 7, alpha(UI_AMBER, open));

		for (int i = 0; i < TABS.length; i++) {
			int itemY = navTop + i * NAV_STEP;
			boolean hovered = inside(mouseX, mouseY, x + 6, itemY, w - 12, NAV_HEIGHT) && i != tab;
			navHover[i] = animated() ? approach(navHover[i], hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
			if (navHover[i] > 0.01) round(g, x + 6, itemY, w - 12, NAV_HEIGHT, 6, alpha(0x12FFFFFF, navHover[i] * open));

			int iconX = compact ? x + (w - 16) / 2 : x + 13 + (int) Math.round(navHover[i] * 2);
			g.item(new ItemStack(TAB_ICONS[i]), iconX, itemY + 5);

			if (!compact) {
				int color = i == tab ? UI_TEXT : mixArgb(UI_MUTED, UI_TEXT, navHover[i]);
				g.text(font, fit(font, tr("tab." + TABS[i]), w - 46), iconX + 21, itemY + 9, alpha(color, open), false);
			}
		}

		if (!compact && panelH >= 250) {
			int bottom = panelY + panelH - 42;
			g.fill(x + 10, bottom - 6, x + w - 10, bottom - 5, alpha(0x14FFFFFF, open));
			g.text(font, fit(font, Planner.tr("option." + Planner.config.goal), w - 20), x + 10, bottom, alpha(UI_AMBER, open), false);
			g.text(font, fit(font, priceState(), w - 20), x + 10, bottom + 11, alpha(UI_DIM, open), false);
		}
	}

	private String priceState() {
		String ah = switch (AuctionPrices.state()) {
			case READY -> "✔";
			case FAILED -> "✖";
			default -> "…";
		};
		return tr("prices", BazaarPrices.BUY.isEmpty() ? "…" : "✔", ah);
	}

	private void drawHeader(GuiGraphicsExtractor g, long now, double open) {
		double appear = animated() ? easeOut((now - tabNs) / 220e6) : 1;
		int shift = (int) Math.round((1 - appear) * 8);
		double a = open * appear;

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(contentX + shift, panelY + 13);
		pose.scale(1.4f, 1.4f);
		g.text(font, tr("tab." + TABS[tab]), 0, 0, alpha(UI_TEXT, a), false);
		pose.popMatrix();

		String subtitle;
		int color = UI_MUTED;

		if (tab == 0 && !SkyBlockReader.statsKnown()) {
			subtitle = tr("no_stats");
			color = UI_DANGER;
		} else if (tab == 0 && !picks.isEmpty()) {
			String level = result.level() >= 0 ? tr("level", result.level()) : tr("level_unknown");
			subtitle = tr("summary", String.format(Locale.US, "%.1f", result.combinedGain()), coins(result.totalCost())) + "  ·  " + level;
			color = UI_AMBER;
		} else {
			subtitle = tr("about." + TABS[tab], GearData.get().assumptions.mangroveDigits);
		}

		g.text(font, fit(font, subtitle, contentW), contentX + shift, panelY + 31, alpha(color, a), false);
	}

	private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, long now, double open) {
		scroll = animated() ? approach(scroll, scrollTarget, dt, 16) : scrollTarget;
		int h = rowHeight();
		double maxPerMillion = 0;
		for (GearAdvisor.Pick pick : picks) maxPerMillion = Math.max(maxPerMillion, pick.perMillion());

		g.enableScissor(contentX - 4, viewTop, contentX + contentW + 12, viewBottom);

		for (int i = 0; i < rowCount(); i++) {
			int y = rowY(i);
			if (y + h <= viewTop || y >= viewBottom) continue;

			double appear = animated() ? easeOut((now - tabNs - Math.min(i, 12) * 35e6) / 240e6) : 1;
			double edge = Math.clamp(Math.min((y + h - viewTop) / 14.0, (viewBottom - y) / 14.0), 0.0, 1.0);
			double a = open * appear * edge;
			if (a <= 0.01) continue;

			int x = contentX + (int) Math.round((1 - appear) * 14);
			String key = TABS[tab] + i;
			boolean hovered = clickable(i) && inside(mouseX, mouseY, contentX, Math.max(y, viewTop), contentW, Math.min(y + h, viewBottom) - Math.max(y, viewTop));
			double hover = animated() ? approach(rowHover.getOrDefault(key, 0.0), hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
			rowHover.put(key, hover);

			round(g, x, y, contentW, h, 7, alpha(mixArgb(UI_ROW, UI_ROW_HOVER, hover), a));

			switch (tab) {
				case 0 -> drawPick(g, i, x, y, a, hover, maxPerMillion, dt, now);
				case 1 -> drawPlan(g, i, x, y, a, hover);
				case 2 -> drawGear(g, GearAdvisor.SLOTS.get(i), x, y, a);
				case 3 -> drawShard(g, GearData.get().shards.get(i), x, y, a);
				default -> drawTip(g, GearData.get().tips.get(i), x, y, a);
			}
		}

		g.disableScissor();
		drawScrollbar(g, open);
	}

	private void iconTile(GuiGraphicsExtractor g, String icon, int x, int y, int h, double a) {
		int top = y + (h - 22) / 2;
		round(g, x + 8, top, 22, 22, 5, alpha(UI_TILE, a));
		if (a > 0.5) g.item(icon(icon), x + 11, top + 3);
	}

	private void drawPick(GuiGraphicsExtractor g, int index, int x, int y, double a, double hover, double maxPerMillion, double dt, long now) {
		if (index >= picks.size() && index - picks.size() < result.locked().size()) {
			drawLocked(g, result.locked().get(index - picks.size()), index - picks.size() == 0, x, y, a);
			return;
		}

		if (picks.isEmpty()) {
			g.text(font, tr("empty"), x + 12, y + 12, alpha(UI_MUTED, a), false);
			g.text(font, fit(font, tr("empty_hint"), contentW - 24), x + 12, y + 26, alpha(UI_DIM, a), false);
			return;
		}

		GearAdvisor.Pick pick = picks.get(index);
		GearData.Item item = pick.item();
		int h = rowHeight();
		int right = x + contentW - 10;

		if (index == 0 && pick.price() != null) {
			double pulse = animated() ? 0.55 + 0.45 * Math.sin((now - openedNs) / 1e9 * 3) : 1;
			roundOutline(g, x, y, contentW, h, 7, alpha(UI_AMBER, a * pulse));
		}

		iconTile(g, item.icon, x, y, h, a);

		// Right column: price, improvement, value-for-money bar.
		String price = pick.price() != null ? coins(pick.price())
				: AuctionPrices.noListing(item.id) ? tr("no_listing") : tr("price_loading");
		String gain = "+" + String.format(Locale.US, "%.1f", pick.gainPercent()) + "%";
		int colW = Math.max(74, Math.max(font.width(price), font.width(gain)) + 4);
		int colX = right - colW;
		g.text(font, price, right - font.width(price), y + 7, alpha(pick.price() == null ? UI_DIM : UI_AMBER, a), false);
		g.text(font, gain, right - font.width(gain), y + 19, alpha(UI_ACCENT, a), false);

		String barKey = "bar" + item.id;
		double target = maxPerMillion <= 0 ? 0 : pick.perMillion() / maxPerMillion;
		double bar = animated() ? approach(bars.getOrDefault(barKey, 0.0), target, dt, 6) : target;
		bars.put(barKey, bar);
		round(g, colX, y + 33, colW, 4, 2, alpha(0x1FFFFFFF, a));
		int fill = (int) Math.round(colW * bar);
		if (fill > 0) round(g, colX, y + 33, fill, 4, 2, alpha(mixArgb(UI_ACCENT_DARK | 0xFF000000, UI_ACCENT, bar), a));

		// Left: name, slot, what changes.
		int textX = x + 38;
		int textW = colX - textX - 10;
		String rank = (index + 1) + ". ";
		g.text(font, rank, textX, y + 7, alpha(UI_DIM, a), false);
		int nameX = textX + font.width(rank);
		String name = fit(font, item.name, textW - font.width(rank) - 60);
		g.text(font, name, nameX, y + 7, alpha(rarityColor(item.rarity), a), false);

		String slot = tr("slot." + item.slot);
		int chipX = nameX + font.width(name) + 6;
		int chipW = font.width(slot) + 8;
		if (chipX + chipW < colX - 6) {
			round(g, chipX, y + 5, chipW, 12, 6, alpha(0x1CFFFFFF, a));
			g.text(font, slot, chipX + 4, y + 7, alpha(UI_MUTED, a), false);
		}

		if (index == 0 && pick.price() != null && chipX + chipW + 70 < colX) {
			String best = tr("best");
			int bx = chipX + chipW + 4;
			round(g, bx, y + 5, font.width(best) + 8, 12, 6, alpha(0x33EBC678, a));
			g.text(font, best, bx + 4, y + 7, alpha(UI_AMBER, a), false);
		}

		StringBuilder change = new StringBuilder();
		if (Math.abs(pick.sweep()) >= 0.05) change.append("§a").append(signed(pick.sweep(), "Sweep")).append("  ");
		if (Math.abs(pick.fortune()) >= 0.05) change.append("§6").append(signed(pick.fortune(), "Fortune")).append("  ");
		if (Math.abs(pick.wisdom()) >= 0.05) change.append("§b").append(signed(pick.wisdom(), "Wisdom"));
		g.text(font, fit(font, change.toString().trim(), textW), textX, y + 19, alpha(0xFFFFFFFF, a), false);

		String replaces = pick.replaces() == null ? tr("replaces_nothing") : tr("replaces", pick.replaces().name);
		String note = pick.bazaar() ? "" : AuctionPrices.note(item.id);
		if (!note.isEmpty()) replaces += "  ·  " + tr("pet_level", note);
		else if (!pick.bazaar() && AuctionPrices.ageSeconds(item.id) >= 0) replaces += "  ·  " + tr("live", AuctionPrices.ageSeconds(item.id));

		if (pick.stronger() != null) {
			GearAdvisor.Pick strong = pick.stronger();
			replaces += "  ·  " + tr("stronger", strong.item().name, String.format(Locale.US, "%.1f", strong.gainPercent()),
					strong.price() == null ? "?" : coins(strong.price()));
		}
		g.text(font, fit(font, replaces, textW), textX, y + 31, alpha(UI_DIM, a), false);

		if (hover > 0.5) {
			String hint = tr(pick.bazaar() ? "click_bz" : "click_ah");
			g.text(font, hint, colX - font.width(hint) - 8, y + 31, alpha(UI_AMBER, a * hover), false);
		}
	}

	private void drawPlan(GuiGraphicsExtractor g, int index, int x, int y, double a, double hover) {
		int h = rowHeight();
		if (index == 0) {
			double total = 0, gain = 0;
			for (GearAdvisor.Pick pick : plan) { total += pick.price(); gain += pick.gainPercent(); }
			iconTile(g, "gold_ingot", x, y, h, a);
			g.text(font, tr("plan_budget", coins(Progress.get().budget)), x + 38, y + 8, alpha(UI_AMBER, a), false);
			g.text(font, fit(font, tr("plan_summary", plan.size(), coins(total), String.format(Locale.US, "%.1f", gain), coins(Math.max(0, Progress.get().budget - total))), contentW - 50),
					x + 38, y + 21, alpha(UI_TEXT, a), false);
			g.text(font, fit(font, tr("plan_hint"), contentW - 50), x + 38, y + 32, alpha(UI_DIM, a), false);
			return;
		}

		if (plan.isEmpty()) {
			g.text(font, tr("plan_empty"), x + 12, y + 18, alpha(UI_MUTED, a), false);
			return;
		}

		GearAdvisor.Pick pick = plan.get(index - 1);
		iconTile(g, pick.item().icon, x, y, h, a);
		String price = coins(pick.price());
		String gain = "+" + String.format(Locale.US, "%.1f", pick.gainPercent()) + "%";
		int right = x + contentW - 10;
		g.text(font, price, right - font.width(price), y + 8, alpha(UI_AMBER, a), false);
		g.text(font, gain, right - font.width(gain), y + 21, alpha(UI_ACCENT, a), false);
		int textW = contentW - 60 - Math.max(font.width(price), font.width(gain));
		g.text(font, fit(font, index + ". " + pick.item().name, textW), x + 38, y + 8, alpha(rarityColor(pick.item().rarity), a), false);
		g.text(font, fit(font, tr("slot." + pick.item().slot) + "  ·  " + (pick.replaces() == null ? tr("replaces_nothing") : tr("replaces", pick.replaces().name)), textW),
				x + 38, y + 21, alpha(UI_MUTED, a), false);
		if (hover > 0.5) g.text(font, tr(pick.bazaar() ? "click_bz" : "click_ah"), x + 38, y + 32, alpha(UI_AMBER, a * hover), false);
	}

	private void drawLocked(GuiGraphicsExtractor g, GearAdvisor.Pick pick, boolean first, int x, int y, double a) {
		GearData.Item item = pick.item();
		int h = rowHeight();
		double dim = a * 0.6;
		iconTile(g, item.icon, x, y, h, dim);

		String need = tr("requires", item.foraging);
		int right = x + contentW - 10;
		g.text(font, need, right - font.width(need), y + 7, alpha(UI_DANGER, a), false);
		String gain = "+" + String.format(Locale.US, "%.1f", pick.gainPercent()) + "%";
		g.text(font, gain, right - font.width(gain), y + 19, alpha(UI_MUTED, a), false);

		int textW = contentW - 50 - Math.max(font.width(need), font.width(gain)) - 16;
		String title = (first ? tr("locked_header") + "  " : "") + item.name;
		g.text(font, fit(font, title, textW), x + 38, y + 7, alpha(first ? UI_AMBER : rarityColor(item.rarity), dim), false);
		g.text(font, fit(font, tr("slot." + item.slot) + "  ·  " + statLine(item), textW), x + 38, y + 19, alpha(UI_TEXT, dim), false);
		g.text(font, fit(font, tr("locked_hint"), textW), x + 38, y + 31, alpha(UI_DIM, a), false);
	}

	private void drawGear(GuiGraphicsExtractor g, String slot, int x, int y, double a) {
		GearData.Item item = gear.get(slot);
		int h = rowHeight();
		iconTile(g, item != null ? item.icon : "barrier", x, y, h, item != null ? a : a * 0.5);

		g.text(font, tr("slot." + slot), x + 38, y + 8, alpha(UI_MUTED, a), false);

		if (item == null) {
			g.text(font, fit(font, tr("unknown") + "  ·  " + tr("hint." + hintKey(slot)), contentW - 50), x + 38, y + 21, alpha(UI_DIM, a), false);
			return;
		}

		g.text(font, item.name, x + 38, y + 19, alpha(rarityColor(item.rarity), a), false);
		String stats = statLine(item);
		g.text(font, stats, x + contentW - 10 - font.width(stats), y + 8, alpha(UI_TEXT, a));

		int cx = x + 38;
		for (UpgradeCheck.Chip chip : UpgradeCheck.forSlot(slot, item)) {
			String label = (chip.done() ? "✔ " : "✖ ") + chip.text();
			int cw = font.width(label) + 8;
			if (cx + cw > x + contentW - 8) break;
			int color = chip.done() ? UI_ACCENT : UI_AMBER;
			round(g, cx, y + 30, cw, 11, 5, alpha(color & 0x00FFFFFF | 0x22000000, a));
			g.text(font, label, cx + 4, y + 32, alpha(color, a), false);
			cx += cw + 4;
		}
	}

	private static String hintKey(String slot) {
		return switch (slot) {
			case "axe" -> "axe";
			case "pet" -> "pet";
			case "helmet", "chestplate", "leggings", "boots" -> "armor";
			default -> "equipment";
		};
	}

	private static String statLine(GearData.Item item) {
		StringBuilder line = new StringBuilder();
		if (item.sweep > 0) line.append("§a").append(Planner.decimal(item.sweep)).append(" Sweep  ");
		if (item.fortune > 0) line.append("§6").append(Planner.decimal(item.fortune)).append(" Fortune  ");
		if (item.wisdom > 0) line.append("§b").append(Planner.decimal(item.wisdom)).append(" Wisdom");
		if (item.fortunePerDigit > 0) line.append("§7 (+").append(Planner.decimal(item.fortunePerDigit)).append("/Stelle)");
		return line.toString().trim();
	}

	private void drawShard(GuiGraphicsExtractor g, GearData.Shard shard, int x, int y, double a) {
		int h = rowHeight();
		iconTile(g, "amethyst_shard", x, y, h, a);

		Double each = GearAdvisor.bazaarPrice(shard.id);
		long owned = Progress.get().huntingBox.getOrDefault(shard.id, 0L);
		Integer level = Progress.get().attributeLevels.get(shard.ability);
		long missing = level != null && level >= 10 ? 0 : Math.max(0, shard.toMax - owned);
		String cost = missing == 0 ? tr("shard_done") : each == null ? tr("price_unknown") : coins(each * missing);
		int right = x + contentW - 10;
		g.text(font, cost, right - font.width(cost), y + 8, alpha(missing == 0 ? UI_ACCENT : each == null ? UI_DIM : UI_AMBER, a), false);
		String toMax = (level != null ? tr("shard_level", level) + " · " : "") + (owned > 0 ? tr("shard_owned", owned) + " · " : "") + tr("to_max", missing);
		g.text(font, toMax, right - font.width(toMax), y + 21, alpha(UI_DIM, a), false);

		int priorityColor = switch (shard.priority) {
			case "high" -> UI_ACCENT;
			case "medium" -> UI_AMBER;
			default -> UI_DIM;
		};
		String title = shard.name + " · " + shard.ability;
		int textW = contentW - 50 - Math.max(font.width(cost), font.width(toMax)) - 16;
		g.text(font, fit(font, title, textW - 60), x + 38, y + 8, alpha(rarityColor(shard.rarity), a), false);
		String priority = tr("priority." + shard.priority);
		int px = x + 38 + Math.min(font.width(title), textW - 60) + 6;
		round(g, px, y + 6, font.width(priority) + 8, 12, 6, alpha(priorityColor & 0x00FFFFFF | 0x2A000000, a));
		g.text(font, priority, px + 4, y + 8, alpha(priorityColor, a), false);
		g.text(font, fit(font, shard.effect, textW), x + 38, y + 21, alpha(UI_MUTED, a), false);
	}

	private void drawTip(GuiGraphicsExtractor g, GearData.Tip tip, int x, int y, double a) {
		int h = rowHeight();
		iconTile(g, tip.icon, x, y, h, a);

		Double price = GearAdvisor.bazaarPrice(tip.id);
		if (price == null) price = AuctionPrices.get(tip.id);
		String cost = price == null ? tr("price_unknown") : coins(price);
		int right = x + contentW - 10;
		g.text(font, cost, right - font.width(cost), y + 14, alpha(price == null ? UI_DIM : UI_AMBER, a), false);

		int textW = contentW - 50 - font.width(cost) - 16;
		g.text(font, fit(font, tip.name, textW), x + 38, y + 8, alpha(UI_TEXT, a), false);
		g.text(font, fit(font, tip.effect, textW), x + 38, y + 21, alpha(UI_MUTED, a), false);
	}

	private void drawScrollbar(GuiGraphicsExtractor g, double open) {
		int max = maxScroll();
		if (max <= 0) return;

		int track = viewBottom - viewTop;
		int thumb = Math.max(20, track * track / (track + max));
		int y = viewTop + (int) Math.round((track - thumb) * scroll / max);
		int x = contentX + contentW + 5;
		round(g, x, viewTop, 3, track, 1, alpha(0x14FFFFFF, open));
		round(g, x, y, 3, thumb, 1, alpha(UI_ACCENT, open));
	}

	private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int y = panelY + panelH - 27;
		g.fill(contentX, y - 7, contentX + contentW, y - 6, alpha(0x14FFFFFF, open));

		String done = Planner.tr("settings.done");
		doneW = Math.max(68, font.width(done) + 26);
		doneX = panelX + panelW - 14 - doneW;
		doneY = y - 1;
		g.text(font, fit(font, tr("keys"), doneX - contentX - 10), contentX, y + 4, alpha(UI_DIM, open), false);

		boolean hovered = inside(mouseX, mouseY, doneX, doneY, doneW, 18);
		doneHover = animated() ? approach(doneHover, hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
		int lift = (int) Math.round(doneHover);
		round(g, doneX, doneY - lift, doneW, 18, 6, alpha(mixArgb(0xFF6FA35B, UI_ACCENT, doneHover), open));
		g.centeredText(font, Component.literal(done), doneX + doneW / 2, doneY + 5 - lift, alpha(UI_ON_ACCENT, open));
	}

	// Input

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private boolean clickable(int index) {
		return switch (tab) {
			case 0 -> index < picks.size();
			case 1 -> index == 0 || index - 1 < plan.size();
			case 3, 4 -> true;
			default -> false;
		};
	}

	/** Puts a search into the chat box. The player sends it themselves. */
	private void search(String command) {
		minecraft.gui.setScreen(new ChatScreen(command, false));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && tab == 0 && inside(event.x(), event.y(), contentX, viewTop, contentW, viewBottom - viewTop)) {
			for (int i = 0; i < picks.size(); i++) {
				int y = rowY(i);
				if (event.y() < y || event.y() >= y + rowHeight()) continue;
				GearAdvisor.Pick pick = picks.get(i);
				if (pick.price() == null) return true;
				Watchers.watch(pick.item().id, pick.item().name, Math.round(pick.price() * 0.9));
				Toasts.show(Toasts.Kind.ALARM, "bell", Planner.tr("toast.alarm_set"), Planner.tr("toast.alarm_set_text", pick.item().name, GearScreenFormat.coins(pick.price() * 0.9)));
				return true;
			}
		}

		boolean planBudget = event.button() == InputConstants.MOUSE_BUTTON_RIGHT && tab == 1;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT && !planBudget) return super.mouseClicked(event, doubled);
		double mx = event.x();
		double my = event.y();

		if (inside(mx, my, doneX, doneY - 1, doneW, 19)) {
			onClose();
			return true;
		}

		int navTop = panelY + 50;

		for (int i = 0; i < TABS.length; i++) {
			if (inside(mx, my, panelX + 10, navTop + i * NAV_STEP, sideW - 12, NAV_HEIGHT)) {
				switchTab(i);
				return true;
			}
		}

		if (inside(mx, my, contentX, viewTop, contentW, viewBottom - viewTop)) {
			for (int i = 0; i < rowCount(); i++) {
				int y = rowY(i);
				if (my < y || my >= y + rowHeight() || !clickable(i)) continue;

				switch (tab) {
					case 0 -> {
						GearAdvisor.Pick pick = picks.get(i);
						search(pick.bazaar() ? "/bz " + pick.item().name : "/ahs " + pick.item().name);
					}
					case 1 -> {
						if (i == 0) {
							double[] steps = {1e6, 2.5e6, 5e6, 10e6, 25e6, 50e6, 100e6, 250e6, 500e6};
							double budget = Progress.get().budget;
							int at = 0;
							for (int k = 0; k < steps.length; k++) if (Math.abs(steps[k] - budget) < 1) at = k;
							at = event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? Math.max(0, at - 1) : Math.min(steps.length - 1, at + 1);
							Progress.get().budget = steps[at];
							Progress.changed();
							refresh();
						} else if (i - 1 < plan.size()) {
							GearAdvisor.Pick pick = plan.get(i - 1);
							search(pick.bazaar() ? "/bz " + pick.item().name : "/ahs " + pick.item().name);
						}
					}
					case 3 -> search("/bz " + GearData.get().shards.get(i).name + " Shard");
					case 4 -> search("/bz " + GearData.get().tips.get(i).name);
					default -> { }
				}

				return true;
			}
		}

		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (inside(mouseX, mouseY, contentX, viewTop, contentW + 12, viewBottom - viewTop)) {
			scrollTarget = Math.clamp(scrollTarget - vertical * 30, 0, maxScroll());
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int pressed = event.key();

		if (pressed >= InputConstants.KEY_1 && pressed < InputConstants.KEY_1 + TABS.length) {
			switchTab(pressed - InputConstants.KEY_1);
			return true;
		}

		if (pressed == InputConstants.KEY_DOWN || pressed == InputConstants.KEY_UP) {
			scrollTarget = Math.clamp(scrollTarget + (pressed == InputConstants.KEY_DOWN ? 1 : -1) * (rowHeight() + ROW_GAP), 0, maxScroll());
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
