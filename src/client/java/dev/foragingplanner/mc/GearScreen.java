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
	private static final String[] TABS = {"picks", "plan", "gear", "upgrades", "shards", "tips", "whatif", "goals"};
	private static final net.minecraft.world.item.Item[] TAB_ICONS = {Items.NETHER_STAR, Items.GOLD_INGOT, Items.IRON_CHESTPLATE, Items.ENCHANTED_BOOK, Items.AMETHYST_SHARD, Items.BOOK, Items.COMPARATOR, Items.TARGET};
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
	private List<UpgradeAdvisor.Option> upgrades = List.of();
	private List<ShardAdvisor.Plan> shardPlans = List.of();
	private List<GearData.Tip> tips = List.of();
	// Unfiltered lists; the ones above are what the search lets through.
	private List<GearAdvisor.Pick> allPicks = List.of(), allPlan = List.of();
	private List<UpgradeAdvisor.Option> allUpgrades = List.of();
	private List<ShardAdvisor.Plan> allShards = List.of();
	private net.minecraft.client.gui.components.EditBox searchBox;
	private String query = "";
	private Map<String, GearData.Item> gear = Map.of();

	/** The tab and scroll position the gear menu was last left on. */
	private static int lastTab;
	private static double lastScroll;

	public GearScreen(Screen parent) {
		super(Component.literal("Foraging Planner"));
		this.parent = parent;
		this.tab = lastTab;
		this.scroll = this.scrollTarget = lastScroll;
	}

	public GearScreen(Screen parent, int tab) {
		this(parent);
		this.tab = Math.clamp(tab, 0, TABS.length - 1);
		this.scroll = this.scrollTarget = 0;
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
		applyScale();
		panelW = Math.min(760, width - 20);
		panelH = Math.min(380, height - 36);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2 + 8;
		sideW = panelW < 460 ? 44 : 132;
		contentX = panelX + sideW + 14;
		contentW = panelW - sideW - 30;
		viewTop = panelY + 52;
		viewBottom = panelY + panelH - 36;
		pill = tab;
		BazaarPrices.refreshIfDue();
		searchBox = new net.minecraft.client.gui.components.EditBox(font, contentX + contentW - 120, panelY + 14, 116, 12, Component.literal(Planner.tr("search.hint")));
		searchBox.setMaxLength(40);
		searchBox.setBordered(false);
		searchBox.setTextColor(UI_TEXT);
		searchBox.setHint(Component.literal("§7" + Planner.tr("gear.search_hint")));
		searchBox.setValue(query);
		searchBox.setResponder(value -> {
			if (value.trim().equalsIgnoreCase(query)) return;
			query = value.trim();
			scroll = scrollTarget = 0;
			applySearch();
		});
		addWidget(searchBox);
		refresh();
	}

	private void refresh() {
		gear = GearAdvisor.current();
		result = GearAdvisor.recommend();
		allPicks = result.picks();
		allUpgrades = UpgradeAdvisor.options();
		allShards = ShardAdvisor.plans();
		allPlan = GearAdvisor.plan(result, Progress.get().budget);
		applySearch();
		refreshedNs = System.nanoTime();
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
	}

	private void switchTab(int target) {
		if (target == tab || target < 0 || target >= TABS.length) return;
		tab = target;
		lastTab = target;
		tabNs = System.nanoTime();
		scroll = scrollTarget = 0;
		UiSound.page();
	}

	// Rows

	private int rowHeight() {
		return tab <= 4 || tab >= 6 ? 46 : 38;
	}

	private int rowCount() {
		return switch (tab) {
			case 0 -> Math.max(1, picks.size() + result.locked().size());
			case 1 -> 1 + Math.max(1, plan.size());
			case 2 -> GearAdvisor.SLOTS.size();
			case 3 -> Math.max(1, upgrades.size());
			case 4 -> shardPlans.size();
			case 6 -> WHATIF_HEAD + GearAdvisor.SLOTS.size() + WHATIF_TAIL;
			case 7 -> goalRows.size();
			default -> tips.size();
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

	private final Map<String, ItemStack> packIcons = new HashMap<>();
	private final Map<String, Long> packMisses = new HashMap<>();

	/** The real Hypixel look of a SkyBlock item when the resource pack has it, else the plain icon. */
	private ItemStack icon(String skyblockId, String fallback) {
		if (skyblockId == null || skyblockId.isEmpty()) return icon(fallback);
		ItemStack found = packIcons.get(skyblockId);
		if (found != null) return found;
		long now = System.currentTimeMillis();
		if (now - packMisses.getOrDefault(skyblockId, 0L) > 5_000) {
			ItemStack pack = LegacyItems.packIcon(skyblockId, icon(fallback).getItem());
			if (pack != null) {
				packIcons.put(skyblockId, pack);
				return pack;
			}
			packMisses.put(skyblockId, now);
		}
		return icon(fallback);
	}

	private void iconTile(GuiGraphicsExtractor g, String skyblockId, String fallback, int x, int y, int h, double a) {
		int top = y + (h - 22) / 2;
		round(g, x + 8, top, 22, 22, 5, alpha(UI_TILE, a));
		if (a > 0.5) g.item(icon(skyblockId, fallback), x + 11, top + 3);
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

	// Menu size: the screen is laid out for a virtual size and drawn scaled up or down.
	private float appliedScale = 1f;
	private int baseW, baseH, virtW = -1, virtH = -1;

	private void applyScale() {
		float s = MenuScale.get();
		if (width != virtW || height != virtH) {
			baseW = width;
			baseH = height;
		}
		appliedScale = s;
		virtW = Math.round(baseW / s);
		virtH = Math.round(baseH / s);
		width = virtW;
		height = virtH;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (MenuScale.get() != appliedScale) rebuildWidgets();
		float s = appliedScale;
		if (s == 1f) {
			extractScaled(g, mouseX, mouseY, delta);
			return;
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(s, s);
		extractScaled(g, (int) (mouseX / s), (int) (mouseY / s), delta);
		pose.popMatrix();
	}

	private void extractScaled(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
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
		MenuBar.draw(g, font, width, panelY - MenuBar.HEIGHT - 3, "gear", mouseX, mouseY, open);
		Why.draw(g, font, width, height);
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
		double appear = Motion.pages() ? easeOut((now - tabNs) / 220e6) : 1;
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

		// Search field top right: Ctrl+F or a click on it.
		if (searchBox != null) {
			boolean focused = getFocused() == searchBox;
			int sx = searchBox.getX() - 14, sy = searchBox.getY() - 3;
			round(g, sx, sy, searchBox.getWidth() + 18, 16, 6, alpha(focused ? 0x40FFFFFF : 0x1AFFFFFF, open));
			g.text(font, "⌕", sx + 4, sy + 4, alpha(UI_MUTED, open), false);
			searchBox.extractRenderState(g, -1, -1, 0);
		}
	}

	/** Lets only rows whose name contains the search text through. */
	private void applySearch() {
		String q = query.toLowerCase(Locale.ROOT);
		java.util.function.Predicate<String> hit = text -> q.isEmpty() || (text != null && text.toLowerCase(Locale.ROOT).contains(q));
		picks = allPicks.stream().filter(p -> hit.test(p.item().name) || hit.test(p.item().slot)).toList();
		plan = allPlan.stream().filter(p -> hit.test(p.item().name) || hit.test(p.item().slot)).toList();
		upgrades = allUpgrades.stream().filter(o -> hit.test(o.title()) || hit.test(o.piece()) || hit.test(o.detail())).toList();
		shardPlans = allShards.stream().filter(p -> hit.test(p.shard().name) || hit.test(p.shard().ability) || hit.test(p.shard().effect)).toList();
		tips = GearData.get().tips.stream().filter(t -> hit.test(t.name) || hit.test(t.effect)).toList();
		buildWhatIfOptions();
		buildGoals();
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

			double appear = Motion.pages() ? easeOut((now - tabNs - Math.min(i, 12) * 35e6) / 240e6) : 1;
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
				case 3 -> drawUpgrade(g, i, x, y, a);
				case 4 -> drawShard(g, shardPlans.get(i), x, y, a);
				case 6 -> drawWhatIf(g, i, x, y, a, mouseX);
				case 7 -> drawGoal(g, i, x, y, a, mouseX);
				default -> drawTip(g, tips.get(i), x, y, a);
			}
			if (hasWhy(i)) {
				int bx = x + 23, by = y + h - 17;
				Why.badge(g, font, bx, by, a, inside(mouseX, mouseY, bx - 1, by - 1, 12, 12));
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

		iconTile(g, item.id, item.icon, x, y, h, a);

		// Right column: price, improvement, value-for-money bar.
		String price = item.free ? tr("free") : pick.price() != null ? coins(pick.price())
				: AuctionPrices.noListing(item.id) ? tr("no_listing") : tr("price_loading");
		String gain = "+" + String.format(Locale.US, "%.1f", pick.gainPercent()) + "%";
		int colW = Math.max(74, Math.max(font.width(price), font.width(gain)) + 4);
		int colX = right - colW;
		g.text(font, Motion.countUp(price, tabNs), right - font.width(price), y + 7, alpha(pick.price() == null ? UI_DIM : UI_AMBER, a), false);
		g.text(font, Motion.countUp(gain, tabNs), right - font.width(gain), y + 19, alpha(UI_ACCENT, a), false);

		String barKey = "bar" + item.id;
		double target = maxPerMillion <= 0 ? 0 : pick.perMillion() / maxPerMillion;
		double bar = Motion.bars() ? approach(bars.getOrDefault(barKey, 0.0), target, dt, 6) : target;
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
		iconTile(g, pick.item().id, pick.item().icon, x, y, h, a);
		String price = pick.item().free ? tr("free") : coins(pick.price());
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
		iconTile(g, item.id, item.icon, x, y, h, dim);

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
		iconTile(g, item != null ? item.id : null, item != null ? item.icon : "barrier", x, y, h, item != null ? a : a * 0.5);

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

	private void drawShard(GuiGraphicsExtractor g, ShardAdvisor.Plan p, int x, int y, double a) {
		GearData.Shard shard = p.shard();
		int h = rowHeight();
		double dim = p.done() || (!p.onFarmedTree() && p.counted()) ? a * 0.6 : a;
		iconTile(g, shard.id, "amethyst_shard", x, y, h, dim);
		int right = x + contentW - 10;

		// Right: gain for the goal, then the cost of the next level / all levels.
		String value = p.done() ? "✔ " + tr("shard_done") : p.gainMax() > 0.005 ? String.format(Locale.US, "+%.1f%%", p.gainMax())
				: !p.onFarmedTree() && p.counted() ? tr("shard_other_tree") : tr("shard_no_number");
		int valueColor = p.done() ? UI_ACCENT : p.gainMax() > 0.005 ? UI_ACCENT : UI_DIM;
		g.text(font, Motion.countUp(value, tabNs), right - font.width(value), y + 7, alpha(valueColor, a), false);
		String cost = p.done() ? "" : p.price() == null ? tr("price_unknown")
				: tr("shard_cost", coins(p.costToNext()), coins(p.costToMax()));
		if (!cost.isEmpty()) g.text(font, cost, right - font.width(cost), y + 19, alpha(p.price() == null ? UI_DIM : UI_AMBER, a), false);
		String need = p.done() ? "" : tr("shard_need", p.toNext(), p.toMax()) + (p.inBox() > 0 ? " · " + tr("shard_owned", p.inBox()) : "");
		if (!need.isEmpty()) g.text(font, need, right - font.width(need), y + 31, alpha(UI_DIM, a), false);
		int colW = Math.max(font.width(value), Math.max(font.width(cost), font.width(need))) + 12;

		// Left: name, priority chip, effect, level bar.
		int textW = contentW - 48 - colW;
		String title = shard.name + " · " + shard.ability;
		g.text(font, fit(font, title, textW - 50), x + 38, y + 7, alpha(rarityColor(shard.rarity), dim), false);
		int priorityColor = switch (shard.priority) {
			case "high" -> UI_ACCENT;
			case "medium" -> UI_AMBER;
			default -> UI_DIM;
		};
		String priority = tr("priority." + shard.priority);
		int px = x + 38 + Math.min(font.width(title), textW - 50) + 6;
		round(g, px, y + 5, font.width(priority) + 8, 12, 6, alpha(priorityColor & 0x00FFFFFF | 0x2A000000, dim));
		g.text(font, priority, px + 4, y + 7, alpha(priorityColor, dim), false);
		String effect = shard.effect + (p.statPerLevel() > 0 && p.counted() && !"chop".equals(shard.stat) ? "  §8(" + tr("shard_now_per_level", Planner.decimal(p.statPerLevel())) + ")" : "");
		g.text(font, fit(font, effect, textW), x + 38, y + 19, alpha(UI_MUTED, dim), false);

		int barX = x + 38, barW = Math.min(120, textW - 40), barY = y + 33;
		round(g, barX, barY, barW, 4, 2, alpha(0x22FFFFFF, a));
		round(g, barX, barY, Math.max(2, (int) Math.round(barW * p.level() / 10.0)), 4, 2, alpha(p.done() ? UI_ACCENT : rarityColor(shard.rarity), a));
		g.text(font, tr("shard_level", p.level()) + "/10", barX + barW + 6, barY - 2, alpha(UI_DIM, a), false);
	}

	private void drawUpgrade(GuiGraphicsExtractor g, int index, int x, int y, double a) {
		if (upgrades.isEmpty()) {
			g.text(font, tr("upgrades_empty"), x + 12, y + 12, alpha(UI_MUTED, a), false);
			g.text(font, fit(font, tr("upgrades_empty_hint"), contentW - 24), x + 12, y + 26, alpha(UI_DIM, a), false);
			return;
		}
		UpgradeAdvisor.Option o = upgrades.get(index);
		int h = rowHeight();
		double dim = o.done() ? a * 0.55 : a;
		iconTile(g, o.itemId(), o.icon(), x, y, h, dim);
		int right = x + contentW - 10;

		String value = o.done() ? "✔" : o.gainPercent() == null ? tr("upgrade_info") : String.format(Locale.US, "+%.1f%%", o.gainPercent());
		String cost = o.done() ? "" : o.price() == null ? (o.search() == null ? "" : tr("price_unknown")) : coins(o.price());
		g.text(font, value, right - font.width(value), y + 8, alpha(o.done() ? UI_ACCENT : o.gainPercent() == null ? UI_DIM : UI_ACCENT, a), false);
		if (!cost.isEmpty()) g.text(font, cost, right - font.width(cost), y + 20, alpha(o.price() == null ? UI_DIM : UI_AMBER, a), false);
		if (o.perMillion() > 0) {
			String per = String.format(Locale.US, "%.2f%%/M", o.perMillion());
			g.text(font, per, right - font.width(per), y + 32, alpha(UI_DIM, a), false);
		}

		int textW = contentW - 50 - Math.max(font.width(value), Math.max(font.width(cost), 60)) - 12;
		g.text(font, fit(font, tr("slot." + o.slot()) + "  ·  " + o.piece(), textW), x + 38, y + 7, alpha(UI_MUTED, dim), false);
		g.text(font, fit(font, o.title(), textW), x + 38, y + 19, alpha(UI_TEXT, dim), false);
		if (!o.detail().isEmpty()) g.text(font, fit(font, o.detail(), textW), x + 38, y + 31, alpha(UI_DIM, dim), false);
	}

	private void drawTip(GuiGraphicsExtractor g, GearData.Tip tip, int x, int y, double a) {
		int h = rowHeight();
		iconTile(g, tip.id, tip.icon, x, y, h, a);

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
			case 3 -> index < upgrades.size() && upgrades.get(index).search() != null;
			case 4, 5 -> true;
			case 6 -> index >= 3;
			case 7 -> index < goalRows.size() && goalRows.get(index).clickable();
			default -> false;
		};
	}

	/** Puts a search into the chat box. The player sends it themselves. */
	private void search(String command) {
		search(command, 0);
	}

	/** "/bz Name" goes to the Bazaar with the helper (amount filled in), "/ahs Name" opens the auction search. */
	private void search(String command, long amount) {
		if (command == null) return;
		if (command.startsWith("/bz ")) BazaarHelper.open(command.substring(4), amount);
		else if (command.startsWith("/ahs ")) BazaarHelper.auction(command.substring(5));
		else minecraft.gui.setScreen(new ChatScreen(command, false));
	}

	/** Opens the Bazaar or auction search for a pick; free items (David's Cloak) tell where to get them. */
	private void openPick(GearAdvisor.Pick pick) {
		if (pick.item().free) {
			Toasts.show(Toasts.Kind.INFO, pick.item().icon, pick.item().name, tr("free_where"));
			return;
		}
		search(pick.bazaar() ? "/bz " + pick.item().name : "/ahs " + pick.item().name);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		event = MenuScale.event(event, appliedScale);
		if (Why.isOpen()) {
			Why.close();
			return true;
		}
		if (MenuBar.click(event.x(), event.y(), width, panelY - MenuBar.HEIGHT - 3, "gear", parent)) return true;
		if (searchBox != null && inside(event.x(), event.y(), searchBox.getX() - 14, searchBox.getY() - 3, searchBox.getWidth() + 18, 16)) {
			setFocused(searchBox);
			searchBox.setFocused(true);
			return super.mouseClicked(event, doubled);
		}
		if (searchBox != null && getFocused() == searchBox) {
			searchBox.setFocused(false);
			setFocused(null);
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inside(event.x(), event.y(), contentX, viewTop, contentW, viewBottom - viewTop)) {
			boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
			for (int i = 0; i < rowCount(); i++) {
				int y = rowY(i);
				if (event.y() < y || event.y() >= y + rowHeight() || !hasWhy(i)) continue;
				int bx = contentX + 23, by = y + rowHeight() - 17;
				if (shift || inside(event.x(), event.y(), bx - 2, by - 2, 14, 14)) {
					openWhy(i);
					return true;
				}
			}
		}
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

		boolean planBudget = event.button() == InputConstants.MOUSE_BUTTON_RIGHT && (tab <= 1 || tab == 4 || tab >= 6);
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT && !planBudget) return super.mouseClicked(event, doubled);
		double mx = event.x();
		double my = event.y();

		if (inside(mx, my, doneX, doneY - 1, doneW, 19)) {
			UiSound.click();
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
				UiSound.click();

				switch (tab) {
					case 0 -> {
						if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) toggleWish(picks.get(i).item());
						else openPick(picks.get(i));
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
							if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) toggleWish(plan.get(i - 1).item());
							else openPick(plan.get(i - 1));
						}
					}
					case 3 -> search(upgrades.get(i).search(), upgrades.get(i).search().startsWith("/bz ") ? 1 : 0);
					case 4 -> {
						// Left click buys what the next level needs, right click everything up to level 10.
						ShardAdvisor.Plan sp = shardPlans.get(i);
						long amount = event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? sp.toMax() : sp.toNext();
						if (!sp.done() && amount > 0) search("/bz " + sp.shard().name + " Shard", amount);
					}
					case 5 -> search("/bz " + tips.get(i).name);
					case 6 -> whatIfClick(i, event.button() != InputConstants.MOUSE_BUTTON_RIGHT, mx);
					case 7 -> goalClick(i, event.button() != InputConstants.MOUSE_BUTTON_RIGHT, mx);
					default -> { }
				}

				return true;
			}
		}

		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		mouseX /= appliedScale;
		mouseY /= appliedScale;
		if (inside(mouseX, mouseY, contentX, viewTop, contentW + 12, viewBottom - viewTop)) {
			scrollTarget = Math.clamp(scrollTarget - vertical * 30, 0, maxScroll());
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int pressed = event.key();
		if (Why.isOpen() && pressed == InputConstants.KEY_ESCAPE) {
			Why.close();
			return true;
		}
		if (event.hasControlDown() && pressed == InputConstants.KEY_F && searchBox != null) {
			setFocused(searchBox);
			searchBox.setFocused(true);
			return true;
		}
		if (searchBox != null && getFocused() == searchBox) {
			if (pressed == InputConstants.KEY_ESCAPE) {
				searchBox.setValue("");
				searchBox.setFocused(false);
				setFocused(null);
				return true;
			}
			return searchBox.keyPressed(event) || true;
		}

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
		lastTab = tab;
		lastScroll = scrollTarget;
		Why.close();
		minecraft.gui.setScreen(parent);
	}

	// Why?

	private boolean hasWhy(int i) {
		return switch (tab) {
			case 0 -> i < picks.size();
			case 1 -> i >= 1 && i - 1 < plan.size();
			case 3 -> i < upgrades.size() && (upgrades.get(i).sweep() != 0 || upgrades.get(i).fortune() != 0 || upgrades.get(i).wisdom() != 0);
			case 4 -> i < shardPlans.size();
			default -> false;
		};
	}

	private void openWhy(int i) {
		switch (tab) {
			case 0 -> Why.pick(picks.get(i));
			case 1 -> Why.pick(plan.get(i - 1));
			case 3 -> Why.upgrade(upgrades.get(i));
			case 4 -> Why.shard(shardPlans.get(i));
			default -> { }
		}
	}

	// What if

	/** Rows above the slots: before/after stats, per hour and cost, actions. Below: perk, perk levels, upgrade. */
	private static final int WHATIF_HEAD = 4, WHATIF_TAIL = 3;
	private static final int[] PERK_STEPS = {1, 5, 10, 25, 50};
	private final Map<String, GearData.Item> whatGear = new java.util.LinkedHashMap<>();
	private int whatPerk = -1, whatPerkStep = 0, whatUpgrade = -1;
	private Map<String, List<GearData.Item>> slotOptionCache = Map.of();
	private List<dev.foragingplanner.core.GameData.Perk> perkCache = List.of();
	private List<UpgradeAdvisor.Option> upgradeCache = List.of();

	/** Options per slot, best gain first; only items that improve on what is worn (David's Cloak rule included). */
	private void buildWhatIfOptions() {
		deltaCache.clear();
		totalsCache = null;
		Map<String, List<GearData.Item>> out = new java.util.LinkedHashMap<>();
		for (String slot : GearAdvisor.SLOTS) {
			List<GearData.Item> list = new java.util.ArrayList<>();
			GearData.Item worn = gear.get(slot);
			boolean bestSlot = false;
			for (GearData.Item item : GearData.get().items) if (item.slot.equals(slot) && item.best) bestSlot = true;
			for (GearData.Item item : GearData.get().items) {
				if (!item.slot.equals(slot) || (worn != null && worn.id.equals(item.id))) continue;
				if (bestSlot && (!item.best || (worn != null && worn.best))) continue;
				double[] d = swapDelta(slot, item);
				if (GearAdvisor.gainPercent(d[0], d[1], d[2]) <= 0.05) continue;
				list.add(item);
			}
			list.sort(java.util.Comparator.comparingDouble((GearData.Item item) -> {
				double[] d = swapDelta(slot, item);
				return -GearAdvisor.gainPercent(d[0], d[1], d[2]);
			}));
			out.put(slot, list);
		}
		slotOptionCache = out;

		List<dev.foragingplanner.core.GameData.Perk> perks = new java.util.ArrayList<>();
		for (var u : Planner.upgrades()) if (!perks.contains(u.perk())) perks.add(u.perk());
		for (var perk : Planner.data.hotf.perks) {
			int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
			if (perk.hasValuedEffects() && level < perk.maxLevel && !perks.contains(perk)) perks.add(perk);
		}
		perkCache = perks;

		List<UpgradeAdvisor.Option> ups = new java.util.ArrayList<>();
		for (UpgradeAdvisor.Option o : allUpgrades) if (!o.done() && (o.sweep() != 0 || o.fortune() != 0 || o.wisdom() != 0)) ups.add(o);
		ups.sort(java.util.Comparator.comparingDouble((UpgradeAdvisor.Option o) -> o.gainPercent() == null ? 0 : -o.gainPercent()));
		upgradeCache = ups;
		if (whatPerk >= perkCache.size()) whatPerk = -1;
		if (whatUpgrade >= upgradeCache.size()) whatUpgrade = -1;
	}

	private int perkCount() {
		if (whatPerk < 0 || whatPerk >= perkCache.size()) return 0;
		var perk = perkCache.get(whatPerk);
		int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
		return Math.max(0, Math.min(PERK_STEPS[whatPerkStep], perk.maxLevel - level));
	}

	/** Total stat change and cost of everything chosen on the page: {sweep, fortune, wisdom, coins, whispers, unpriced}. */
	private double[] totalsCache;
	private long totalsNs;
	private final Map<String, double[]> deltaCache = new java.util.HashMap<>();

	/** Stat change of one swap, remembered until the next refresh (it reads the whole gear). */
	private double[] swapDelta(String slot, GearData.Item item) {
		return deltaCache.computeIfAbsent(slot + "|" + item.id, k -> GearAdvisor.gearDelta(Map.of(slot, item)));
	}

	private double[] whatIfTotals() {
		long now = System.nanoTime();
		if (totalsCache != null && now - totalsNs < 200_000_000L) return totalsCache;
		totalsCache = computeTotals();
		totalsNs = now;
		return totalsCache;
	}

	private double[] computeTotals() {
		double coinsCost = 0, unpriced = 0;
		for (GearData.Item item : whatGear.values()) {
			if (item.free) continue;
			Double price = GearAdvisor.price(item.id);
			if (price != null) coinsCost += price;
			else unpriced++;
		}
		double[] d = whatGear.isEmpty() ? new double[3] : GearAdvisor.gearDelta(whatGear);
		double whispers = 0;
		int count = perkCount();
		if (count > 0) {
			var perk = perkCache.get(whatPerk);
			int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
			var stats = SkyBlockReader.stats();
			var after = dev.foragingplanner.core.HotfPlanner.advance(Planner.data, Planner.config.perkLevels, stats, perk, count);
			d[0] += after.sweep() - stats.sweep();
			d[1] += after.fortune() - stats.fortune();
			d[2] += after.wisdom() - stats.wisdom();
			whispers = dev.foragingplanner.core.PerkMath.costBetween(perk, level, level + count);
		}
		if (whatUpgrade >= 0 && whatUpgrade < upgradeCache.size()) {
			UpgradeAdvisor.Option o = upgradeCache.get(whatUpgrade);
			d[0] += o.sweep();
			d[1] += o.fortune();
			d[2] += o.wisdom();
			if (o.price() != null) coinsCost += o.price();
			else unpriced++;
		}
		return new double[] {d[0], d[1], d[2], coinsCost, whispers, unpriced};
	}

	private void whatIfClick(int index, boolean forward, double mouseX) {
		totalsCache = null;
		int slots = GearAdvisor.SLOTS.size();
		if (index == 3) {
			// Left half: take over the suggestions (budget plan, else all picks); right half: reset.
			if (mouseX < contentX + contentW / 2.0) {
				whatGear.clear();
				List<GearAdvisor.Pick> source = plan.isEmpty() ? picks : plan;
				for (GearAdvisor.Pick pick : source) whatGear.put(pick.item().slot, pick.item());
				UiSound.success();
			} else {
				whatGear.clear();
				whatPerk = whatUpgrade = -1;
				whatPerkStep = 0;
			}
			return;
		}
		if (index >= WHATIF_HEAD && index < WHATIF_HEAD + slots) {
			String slot = GearAdvisor.SLOTS.get(index - WHATIF_HEAD);
			List<GearData.Item> options = slotOptionCache.getOrDefault(slot, List.of());
			if (options.isEmpty()) return;
			int at = options.indexOf(whatGear.get(slot));
			at = forward ? at + 1 : at - 1;
			if (at >= options.size()) at = -1;
			if (at < -1) at = options.size() - 1;
			if (at < 0) whatGear.remove(slot);
			else whatGear.put(slot, options.get(at));
			return;
		}
		switch (index - WHATIF_HEAD - slots) {
			case 0 -> whatPerk = cycle(whatPerk, perkCache.size(), forward);
			case 1 -> whatPerkStep = Math.floorMod(whatPerkStep + (forward ? 1 : -1), PERK_STEPS.length);
			case 2 -> whatUpgrade = cycle(whatUpgrade, upgradeCache.size(), forward);
			default -> { }
		}
	}

	/** -1 (nothing) then 0..size-1, wrapping around. */
	private static int cycle(int value, int size, boolean forward) {
		if (size <= 0) return -1;
		int next = value + (forward ? 1 : -1);
		if (next >= size) return -1;
		if (next < -1) return size - 1;
		return next;
	}

	private static String arrow(String before, String after, boolean changed) {
		return changed ? before + " §7→ §a" + after : before;
	}

	private void drawWhatIf(GuiGraphicsExtractor g, int index, int x, int y, double a, int mouseX) {
		int slots = GearAdvisor.SLOTS.size();
		int right = x + contentW - 10;
		double[] t = whatIfTotals();
		boolean changed = Math.abs(t[0]) > 0.01 || Math.abs(t[1]) > 0.01 || Math.abs(t[2]) > 0.01;

		if (index == 0) {
			// Stats before and after, and what Sweep means on the farmed tree.
			var stats = SkyBlockReader.stats();
			iconTile(g, "comparator", x, y, rowHeight(), a);
			String line1 = tr("whatif_stats",
					arrow(Planner.whole(stats.sweep()), Planner.whole(stats.sweep() + t[0]), Math.abs(t[0]) > 0.01),
					arrow(Planner.whole(stats.fortune()), Planner.whole(stats.fortune() + t[1]), Math.abs(t[1]) > 0.01),
					arrow(Planner.decimal(stats.wisdom()), Planner.decimal(stats.wisdom() + t[2]), Math.abs(t[2]) > 0.01));
			g.text(font, fit(font, line1, contentW - 50), x + 38, y + 7, alpha(UI_TEXT, a), false);
			var tree = Why.farmedTree();
			if (tree != null) {
				int before = dev.foragingplanner.core.TreeMath.logsPerHit(tree, stats.sweep());
				int after = dev.foragingplanner.core.TreeMath.logsPerHit(tree, stats.sweep() + t[0]);
				double next = dev.foragingplanner.core.TreeMath.sweepToNextBreakpoint(tree, stats.sweep() + t[0]);
				String line2 = tr("whatif_breakpoint", tree.name, arrow(String.valueOf(before), String.valueOf(after), after != before),
						next >= 0 ? Planner.whole(Math.ceil(next)) : "–");
				g.text(font, fit(font, line2, contentW - 50), x + 38, y + 19, alpha(after > before ? UI_ACCENT : UI_MUTED, a), false);
			}
			String gain = String.format(Locale.US, "%+.1f%%", GearAdvisor.gainPercent(t[0], t[1], t[2]));
			g.text(font, gain, right - font.width(gain), y + 7, alpha(changed ? UI_ACCENT : UI_DIM, a), false);
			g.text(font, fit(font, tr("whatif_goal", Planner.tr("option." + Planner.goal().id())), contentW - 50), x + 38, y + 31, alpha(UI_DIM, a), false);
			return;
		}
		if (index == 1) {
			// Logs per hit on every tree, before and after.
			var stats = SkyBlockReader.stats();
			iconTile(g, "oak_log", x, y, rowHeight(), a);
			g.text(font, tr("whatif_trees"), x + 38, y + 7, alpha(UI_MUTED, a), false);
			StringBuilder b = new StringBuilder();
			var farmed = Why.farmedTree();
			for (var tree : Planner.data.trees) {
				int before = dev.foragingplanner.core.TreeMath.logsPerHit(tree, stats.sweep());
				int after = dev.foragingplanner.core.TreeMath.logsPerHit(tree, stats.sweep() + t[0]);
				if (!b.isEmpty()) b.append("§7 · ");
				b.append(tree == farmed ? "§e" : "§f").append(tree.name).append(" §f").append(arrow(String.valueOf(before), String.valueOf(after), after != before));
			}
			g.text(font, fit(font, b.toString(), contentW - 50), x + 38, y + 19, alpha(UI_TEXT, a), false);
			StringBuilder n = new StringBuilder();
			for (var tree : Planner.data.trees) {
				double next = dev.foragingplanner.core.TreeMath.sweepToNextBreakpoint(tree, stats.sweep() + t[0]);
				if (!n.isEmpty()) n.append(" · ");
				n.append(tree.name).append(" ").append(next >= 0 ? "+" + Planner.whole(Math.ceil(next)) : "max");
			}
			g.text(font, fit(font, tr("whatif_next_log", n), contentW - 50), x + 38, y + 31, alpha(UI_DIM, a), false);
			return;
		}
		if (index == 2) {
			Why.Estimate e = Why.estimate(t[0], t[1], t[2]);
			Why.Rates r = e.rates();
			iconTile(g, "clock", x, y, rowHeight(), a);
			String rates = r.known()
					? tr("whatif_rates2", arrow(Planner.whole(r.logs()), Planner.whole(r.logs() * e.logsRatio()), changed),
						arrow(coins(r.coins()), coins(r.coins() * e.logsRatio()), changed),
						arrow(Planner.whole(r.xp()), Planner.whole(r.xp() * e.xpRatio()), changed))
					: tr("whatif_per_hit", Planner.decimal(e.dropsAfter()), Why.percent(e.logsRatio()), Planner.decimal(e.xpAfter()), Why.percent(e.xpRatio()));
			g.text(font, fit(font, rates, contentW - 50), x + 38, y + 7, alpha(UI_TEXT, a), false);
			String cost = tr("whatif_cost2", coins(t[3]), Planner.whole(t[4])) + (t[5] > 0 ? " · " + tr("whatif_unpriced", (int) t[5]) : "");
			g.text(font, fit(font, cost, contentW - 50), x + 38, y + 19, alpha(UI_AMBER, a), false);
			double extra = r.coins() * (e.logsRatio() - 1);
			String payback = t[3] > 0 && extra > 0 ? tr("whatif_payback", GearScreenFormat.eta(t[3] / extra))
					: t[3] > 0 ? tr("whatif_payback_none") : "";
			if (!payback.isEmpty()) g.text(font, fit(font, payback, contentW - 50), x + 38, y + 31, alpha(UI_DIM, a), false);
			return;
		}
		if (index == 3) {
			int half = contentW / 2;
			boolean leftHover = mouseX < contentX + half;
			String take = "★ " + tr(plan.isEmpty() ? "whatif_take_picks" : "whatif_take_plan");
			String reset = "↺ " + tr("whatif_reset");
			g.centeredText(font, take, x + half / 2, y + 19, alpha(leftHover ? UI_AMBER : UI_TEXT, a));
			g.centeredText(font, reset, x + half + half / 2, y + 19, alpha(!leftHover ? UI_AMBER : UI_TEXT, a));
			g.fill(x + half, y + 8, x + half + 1, y + rowHeight() - 8, alpha(0x22FFFFFF, a));
			return;
		}
		if (index < WHATIF_HEAD + slots) {
			String slot = GearAdvisor.SLOTS.get(index - WHATIF_HEAD);
			GearData.Item now = gear.get(slot);
			GearData.Item chosen = whatGear.get(slot);
			GearData.Item shown = chosen != null ? chosen : now;
			List<GearData.Item> options = slotOptionCache.getOrDefault(slot, List.of());
			iconTile(g, shown == null ? null : shown.id, shown == null ? "barrier" : shown.icon, x, y, rowHeight(), a);
			String head = tr("slot." + slot) + (options.isEmpty() ? "  §8" + tr("whatif_best_already")
					: "  §8" + (chosen == null ? 0 : options.indexOf(chosen) + 1) + "/" + options.size());
			g.text(font, head, x + 38, y + 7, alpha(UI_MUTED, a), false);
			String line = (now == null ? tr("whatif_nothing") : now.name) + (chosen != null ? "  →  " + chosen.name : "");
			g.text(font, fit(font, line, contentW - 150), x + 38, y + 19, alpha(chosen != null ? rarityColor(chosen.rarity) : UI_TEXT, a), false);
			if (chosen != null) {
				double[] d = swapDelta(slot, chosen);
				int level = GearAdvisor.foragingLevel();
				String sub = Why.stats(d[0], d[1], d[2]) + (level >= 0 && chosen.foraging > level ? "  §c" + tr("whatif_locked", chosen.foraging) : "");
				g.text(font, fit(font, sub, contentW - 150), x + 38, y + 31, alpha(0xFFFFFFFF, a), false);
				Double price = chosen.free ? Double.valueOf(0) : GearAdvisor.price(chosen.id);
				String p = chosen.free ? tr("free") : price == null ? "?" : coins(price);
				g.text(font, p, right - font.width(p), y + 7, alpha(UI_AMBER, a), false);
				String gain = String.format(Locale.US, "%+.1f%%", GearAdvisor.gainPercent(d[0], d[1], d[2]));
				g.text(font, gain, right - font.width(gain), y + 19, alpha(UI_ACCENT, a), false);
			} else if (!options.isEmpty()) {
				g.text(font, tr("whatif_click"), x + 38, y + 31, alpha(UI_DIM, a), false);
			}
			return;
		}
		switch (index - WHATIF_HEAD - slots) {
			case 0 -> {
				String name = whatPerk >= 0 && whatPerk < perkCache.size() ? perkCache.get(whatPerk).name : tr("whatif_none");
				iconTile(g, "oak_sapling", x, y, rowHeight(), a);
				g.text(font, tr("whatif_perk") + "  §8" + (whatPerk + 1) + "/" + perkCache.size(), x + 38, y + 7, alpha(UI_MUTED, a), false);
				g.text(font, fit(font, name, contentW - 150), x + 38, y + 19, alpha(UI_TEXT, a), false);
				g.text(font, tr("whatif_perk_order"), x + 38, y + 31, alpha(UI_DIM, a), false);
			}
			case 1 -> {
				iconTile(g, "experience_bottle", x, y, rowHeight(), a);
				g.text(font, tr("whatif_perk_levels"), x + 38, y + 7, alpha(UI_MUTED, a), false);
				int count = perkCount();
				String levels = "+" + PERK_STEPS[whatPerkStep] + (whatPerk >= 0 && count < PERK_STEPS[whatPerkStep] ? "  §8(" + tr("whatif_perk_capped", count) + ")" : "");
				g.text(font, levels, x + 38, y + 19, alpha(UI_TEXT, a), false);
			}
			default -> {
				String name = whatUpgrade >= 0 && whatUpgrade < upgradeCache.size() ? upgradeCache.get(whatUpgrade).piece() + " · " + upgradeCache.get(whatUpgrade).title() : tr("whatif_none");
				iconTile(g, "enchanted_book", x, y, rowHeight(), a);
				g.text(font, tr("whatif_upgrade") + "  §8" + (whatUpgrade + 1) + "/" + upgradeCache.size(), x + 38, y + 7, alpha(UI_MUTED, a), false);
				g.text(font, fit(font, name, contentW - 60), x + 38, y + 19, alpha(UI_TEXT, a), false);
				g.text(font, tr("whatif_click"), x + 38, y + 31, alpha(UI_DIM, a), false);
			}
		}
	}


	// Goals: roadmap to a number of logs per hit, and the wishlist

	/** One row of the goals page. */
	private record GoalRow(String kind, Object data, boolean clickable) {}

	private List<GoalRow> goalRows = List.of();
	private static int goalTree = -1;
	private static int goalLogs = -1;
	private Roadmap.Result roadmap;

	private dev.foragingplanner.core.GameData.Tree goalTree() {
		var trees = Planner.data.trees;
		if (trees.isEmpty()) return null;
		if (goalTree < 0 || goalTree >= trees.size()) {
			var farmed = Why.farmedTree();
			goalTree = farmed == null ? 0 : Math.max(0, trees.indexOf(farmed));
		}
		return trees.get(goalTree);
	}

	private void buildGoals() {
		List<GoalRow> rows = new java.util.ArrayList<>();
		var tree = goalTree();
		rows.add(new GoalRow("target", null, true));
		if (tree != null) {
			int now = Roadmap.currentLogs(tree);
			if (goalLogs <= now || goalLogs > Roadmap.maxLogs(tree)) goalLogs = Math.min(Roadmap.maxLogs(tree), now + 1);
			roadmap = Roadmap.plan(tree, goalLogs);
			rows.add(new GoalRow("summary", null, false));
			if (roadmap.neededSweep() > 0) {
				rows.add(new GoalRow("head_coins", null, false));
				for (Roadmap.Step step : roadmap.coinSteps()) rows.add(new GoalRow("step", step, false));
				rows.add(new GoalRow("head_whispers", null, false));
				for (Roadmap.Step step : roadmap.whisperSteps()) rows.add(new GoalRow("step", step, false));
			}
		}
		rows.add(new GoalRow("head_wish", null, false));
		GearData data = GearData.get();
		for (String id : Progress.get().wishlist) {
			GearData.Item item = data.item(id);
			if (item != null) rows.add(new GoalRow("wish", item, true));
		}
		if (Progress.get().wishlist.isEmpty()) rows.add(new GoalRow("wish_empty", null, false));
		goalRows = rows;
	}

	private void toggleWish(GearData.Item item) {
		var list = Progress.get().wishlist;
		boolean added = !list.remove(item.id);
		if (added) list.add(item.id);
		Progress.changed();
		buildGoals();
		Toasts.show(Toasts.Kind.INFO, item.icon, item.name, tr(added ? "wish_added" : "wish_removed"));
		if (added) UiSound.success();
	}

	private double wishTotal() {
		double total = 0;
		for (String id : Progress.get().wishlist) {
			GearData.Item item = GearData.get().item(id);
			if (item == null || item.free) continue;
			Double price = GearAdvisor.price(id);
			if (price != null) total += price;
		}
		return total;
	}

	private void goalClick(int index, boolean forward, double mouseX) {
		GoalRow row = goalRows.get(index);
		switch (row.kind()) {
			case "target" -> {
				var tree = goalTree();
				if (tree == null) return;
				if (mouseX < contentX + contentW / 2.0) {
					goalTree = Math.floorMod(goalTree + (forward ? 1 : -1), Planner.data.trees.size());
					goalLogs = -1;
				} else {
					int now = Roadmap.currentLogs(tree);
					int max = Roadmap.maxLogs(tree);
					goalLogs = forward ? (goalLogs >= max ? now + 1 : goalLogs + 1) : (goalLogs <= now + 1 ? max : goalLogs - 1);
				}
				buildGoals();
			}
			case "wish" -> {
				GearData.Item item = (GearData.Item) row.data();
				if (forward) {
					if (item.free) Toasts.show(Toasts.Kind.INFO, item.icon, item.name, tr("free_where"));
					else search(BazaarPrices.BUY.containsKey(item.id) ? "/bz " + item.name : "/ahs " + item.name);
				} else {
					toggleWish(item);
				}
			}
			default -> { }
		}
	}

	private void drawGoal(GuiGraphicsExtractor g, int index, int x, int y, double a, int mouseX) {
		if (index >= goalRows.size()) return;
		GoalRow row = goalRows.get(index);
		int right = x + contentW - 10;
		var tree = goalTree();
		switch (row.kind()) {
			case "target" -> {
				int half = contentW / 2;
				boolean left = mouseX < contentX + half;
				iconTile(g, "target", x, y, rowHeight(), a);
				g.text(font, tr("goal_tree"), x + 38, y + 9, alpha(UI_MUTED, a), false);
				g.text(font, tree == null ? "?" : tree.name, x + 38, y + 22, alpha(left ? UI_AMBER : UI_TEXT, a), false);
				g.text(font, tr("goal_logs"), x + half + 8, y + 9, alpha(UI_MUTED, a), false);
				String logs = tree == null ? "?" : tr("goal_logs_value", goalLogs, Roadmap.currentLogs(tree));
				g.text(font, logs, x + half + 8, y + 22, alpha(!left ? UI_AMBER : UI_TEXT, a), false);
				g.text(font, tr("goal_click"), x + 38, y + 34, alpha(UI_DIM, a), false);
			}
			case "summary" -> {
				iconTile(g, "iron_axe", x, y, rowHeight(), a);
				String text = roadmap.neededSweep() < 0 ? tr("goal_out_of_range")
						: roadmap.neededSweep() == 0 ? tr("goal_reached")
						: tr("goal_missing", Planner.whole(Math.ceil(roadmap.neededSweep())), Planner.whole(roadmap.currentSweep()), Planner.whole(roadmap.currentSweep() + roadmap.neededSweep()));
				g.text(font, fit(font, text, contentW - 50), x + 38, y + 10, alpha(roadmap.neededSweep() == 0 ? UI_ACCENT : UI_TEXT, a), false);
				g.text(font, fit(font, tr("goal_note"), contentW - 50), x + 38, y + 24, alpha(UI_DIM, a), false);
			}
			case "head_coins", "head_whispers", "head_wish" -> {
				String head;
				String sub;
				if (row.kind().equals("head_coins")) {
					double total = roadmap.coinSteps().isEmpty() ? 0 : roadmap.coinSteps().get(roadmap.coinSteps().size() - 1).total();
					head = tr("goal_with_coins", coins(total));
					sub = roadmap.coinsReach() ? tr("goal_reaches") : tr("goal_not_enough");
				} else if (row.kind().equals("head_whispers")) {
					double total = roadmap.whisperSteps().isEmpty() ? 0 : roadmap.whisperSteps().get(roadmap.whisperSteps().size() - 1).total();
					head = tr("goal_with_whispers", Planner.whole(total));
					sub = roadmap.whispersReach() ? tr("goal_reaches") : tr("goal_not_enough");
				} else {
					double total = wishTotal();
					double purse = SkyBlockReader.purse();
					Why.Rates r = Why.rates();
					double missing = Math.max(0, total - Math.max(0, purse));
					head = tr("goal_wishlist", coins(total));
					sub = (purse >= 0 ? tr("goal_purse", coins(purse)) + " · " : "")
							+ (missing <= 0 ? tr("goal_wish_affordable") : r.coins() > 0 ? tr("goal_wish_eta", GearScreenFormat.eta(missing / r.coins())) : tr("goal_wish_hint"));
				}
				g.fill(x + 8, y + rowHeight() - 6, x + contentW - 8, y + rowHeight() - 5, alpha(0x22FFFFFF, a));
				g.text(font, head, x + 10, y + 12, alpha(UI_AMBER, a), false);
				g.text(font, fit(font, sub, contentW - 20), x + 10, y + 25, alpha(UI_DIM, a), false);
			}
			case "step" -> {
				Roadmap.Step step = (Roadmap.Step) row.data();
				iconTile(g, step.icon(), x, y, rowHeight(), a);
				g.text(font, fit(font, step.name(), contentW - 170), x + 38, y + 8, alpha(UI_TEXT, a), false);
				g.text(font, fit(font, step.detail(), contentW - 170), x + 38, y + 20, alpha(UI_MUTED, a), false);
				g.text(font, tr("goal_step_sweep", Planner.decimal(step.sweep()), Planner.whole(step.totalSweep())), x + 38, y + 32, alpha(UI_ACCENT, a), false);
				String cost = step.whispers() > 0 ? Planner.whole(step.whispers()) + " W" : step.coins() <= 0 ? tr("free") : coins(step.coins());
				String total = "Σ " + (step.whispers() > 0 ? Planner.whole(step.total()) + " W" : coins(step.total()));
				g.text(font, cost, right - font.width(cost), y + 8, alpha(UI_AMBER, a), false);
				g.text(font, total, right - font.width(total), y + 20, alpha(UI_DIM, a), false);
			}
			case "wish" -> {
				GearData.Item item = (GearData.Item) row.data();
				iconTile(g, item.id, item.icon, x, y, rowHeight(), a);
				g.text(font, fit(font, item.name, contentW - 150), x + 38, y + 9, alpha(rarityColor(item.rarity), a), false);
				double[] d = swapDelta(item.slot, item);
				g.text(font, fit(font, Why.stats(d[0], d[1], d[2]), contentW - 150), x + 38, y + 22, alpha(UI_MUTED, a), false);
				g.text(font, tr("wish_click"), x + 38, y + 34, alpha(UI_DIM, a), false);
				Double price = item.free ? Double.valueOf(0) : GearAdvisor.price(item.id);
				String p = item.free ? tr("free") : price == null ? tr("price_unknown") : coins(price);
				g.text(font, p, right - font.width(p), y + 9, alpha(UI_AMBER, a), false);
				String gain = String.format(Locale.US, "%+.1f%%", GearAdvisor.gainPercent(d[0], d[1], d[2]));
				g.text(font, gain, right - font.width(gain), y + 22, alpha(UI_ACCENT, a), false);
			}
			default -> g.text(font, fit(font, tr("wish_empty"), contentW - 20), x + 12, y + 18, alpha(UI_DIM, a), false);
		}
	}


	@Override
	public void removed() {
		lastTab = tab;
		lastScroll = scrollTarget;
		super.removed();
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return super.mouseDragged(MenuScale.event(event, appliedScale), dragX / appliedScale, dragY / appliedScale);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(MenuScale.event(event, appliedScale));
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		super.mouseMoved(mouseX / appliedScale, mouseY / appliedScale);
	}
}
