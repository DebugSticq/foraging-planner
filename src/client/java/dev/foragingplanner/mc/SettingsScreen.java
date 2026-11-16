package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_DARK;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_SOFT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_FIELD;
import static dev.foragingplanner.mc.ForestTheme.UI_FIELD_LINE;
import static dev.foragingplanner.mc.ForestTheme.UI_KNOB;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_ON_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW_HOVER;
import static dev.foragingplanner.mc.ForestTheme.UI_SIDEBAR;
import static dev.foragingplanner.mc.ForestTheme.UI_SWITCH_OFF;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.UI_TILE;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.approach;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.easeOutBack;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.fract;
import static dev.foragingplanner.mc.ForestTheme.icon;
import static dev.foragingplanner.mc.ForestTheme.mixArgb;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import dev.foragingplanner.core.Goal;

/**
 * The settings screen: a glass panel over the blurred game with drifting fireflies behind it,
 * a sidebar whose highlight glides between pages, rows that slide in one after another,
 * and a live preview on wide screens. Every change is saved a moment after it is made.
 */
public final class SettingsScreen extends Screen {
	private static final String[] PAGES = {"display", "trees", "galatea", "alerts", "chat", "online", "planner", "stats", "safari", "style"};
	private static final Item[] PAGE_ICONS = {Items.PAINTING, Items.OAK_LOG, Items.SPYGLASS, Items.BELL, Items.PAPER, Items.GOLDEN_HOE, Items.OAK_SAPLING, Items.IRON_AXE, Items.BELL, Items.FLOWER_POT};

	private static final int ROW_HEIGHT = 36;
	private static final int ROW_STEP = ROW_HEIGHT + 5;
	private int NAV_HEIGHT = 21;
	private int NAV_STEP = 23;
	private static final int RADIUS = 10;
	private static final int FIREFLIES = 28;
	private static final long SAVE_DELAY_NS = 600_000_000L;
	private static final double TOAST_SECONDS = 4.0;

	private static KeyMapping key;
	private static boolean requested;

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	/** Search over every page; empty shows the normal page. */
	private EditBox searchBox;
	private String query = "";
	private final double[] navHover = new double[PAGES.length];
	private final long openedNs = System.nanoTime();

	private int page;
	private int panelX, panelY, panelW, panelH, sideW, contentX, contentW, viewTop, viewBottom, previewX, previewW;
	private int doneX, doneY, doneW;
	private long pageNs = openedNs, lastFrameNs = openedNs, dirtyNs, savedNs, toastNs;
	private double pill = -1, scroll, scrollTarget, doneHover, barHover;
	private boolean dirty, draggingBar;

	public SettingsScreen(Screen parent) {
		super(Component.literal("Foraging Planner"));
		this.parent = parent;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
				Screens.getWidgets(screen).add(new ForestButton(width - 148, 6, 142, 20,
						Component.literal("Foraging Planner"), button -> client.gui.setScreen(new SettingsScreen(screen)), () -> false));
			}
		});
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.settings", InputConstants.KEY_P, KeyMapping.Category.MISC));
	}

	public static void requestOpen() {
		requested = true;
	}

	public static void tick(Minecraft client) {
		if (setupRequested) {
			setupRequested = false;
			client.gui.setScreen(new SetupScreen(null));
			return;
		}
		JournalScreen.tick(client);
		GearScreen.tick(client);
		StatsScreen.tick(client);
		PvScreen.tick(client);
		while (key.consumeClick()) requested = true;

		if (requested) {
			requested = false;
			client.gui.setScreen(new SettingsScreen(null));
		}
	}

	private static Component text(String name) {
		return Component.literal(Planner.tr("settings." + name));
	}

	private static boolean animated() {
		return Planner.config.animations;
	}

	// Layout

	@Override
	protected void init() {
		clearWidgets();
		if (searchBox == null) {
			searchBox = new EditBox(font, 0, 0, 120, 12, Component.literal(Planner.tr("search.hint")));
			searchBox.setMaxLength(40);
			searchBox.setBordered(false);
			searchBox.setTextColor(UI_TEXT);
			searchBox.setHint(Component.literal("§7" + Planner.tr("search.hint")));
			searchBox.setResponder(value -> {
				if (value.trim().equals(query)) return;
				query = value.trim();
				scroll = scrollTarget = 0;
				pageNs = System.nanoTime();
				init();
			});
		}
		addWidget(searchBox);
		rows.clear();

		boolean wide = width >= 600 && height >= 300;
		panelW = Math.min(wide ? 720 : 540, width - 20);
		panelH = Math.min(360, height - 20);
		NAV_STEP = Math.clamp((panelH - 58) / PAGES.length, 15, 23);
		NAV_HEIGHT = NAV_STEP - 2;
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		sideW = panelW < 430 ? 44 : 128;
		previewW = wide && panelW >= 620 ? 186 : 0;
		contentX = panelX + sideW + 14;
		contentW = panelW - sideW - 30 - (previewW > 0 ? previewW + 12 : 0);
		previewX = contentX + contentW + 14;
		viewTop = panelY + 50;
		viewBottom = panelY + panelH - 36;

		buildRows();
		if (pill < 0) pill = page;
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	private boolean compactSidebar() {
		return sideW < 100;
	}

	private void buildRows() {
		if (query.isEmpty()) {
			buildPage(page);
			return;
		}

		// Search: rows of every page whose name or explanation contains the text.
		for (int p = 0; p < PAGES.length; p++) buildPage(p);
		String q = query.toLowerCase(java.util.Locale.ROOT);
		List<Row> all = new ArrayList<>(rows);
		rows.clear();
		for (Row row : all) {
			String text = (Planner.tr("settings." + row.name) + " " + Planner.tr("settings." + row.name + ".hint")).toLowerCase(java.util.Locale.ROOT);
			if (text.contains(q)) {
				row.index = rows.size();
				rows.add(row);
			} else {
				row.hide(true);
			}
		}
	}

	private static boolean setupRequested;

	/** Opens the first-start choice on the next tick (commands run while the chat is open). */
	public static void requestSetup() {
		setupRequested = true;
	}

	private void buildPage(int target) {
		ModConfig c = Planner.config;

		switch (PAGES[target]) {
			case "display" -> {
				new ActionRow("journal", "open", Items.WRITABLE_BOOK, () -> minecraft.gui.setScreen(new JournalScreen(this)));
				new ToggleRow("hud", Items.PAINTING, () -> c.hudEnabled, value -> c.hudEnabled = value);
				new ActionRow("position", "move", Items.COMPASS, () -> minecraft.gui.setScreen(new HudEditorScreen(this)));
				new ToggleRow("animations", Items.GLOW_BERRIES, () -> c.animations, value -> c.animations = value);
				new ToggleRow("rates", Items.EXPERIENCE_BOTTLE, () -> c.showRates, value -> c.showRates = value);
				new ActionRow("session", "reset", Items.CLOCK, SessionRates::reset);
				new ActionRow("stats", "open", Items.SPYGLASS, () -> minecraft.gui.setScreen(new StatsScreen(this)));
				new ToggleRow("tree_progress", Items.EXPERIENCE_BOTTLE, () -> c.treeProgress, value -> c.treeProgress = value);
				new ToggleRow("combo", Items.BLAZE_POWDER, () -> c.comboCounter, value -> c.comboCounter = value);
				new ToggleRow("level_eta", Items.EXPERIENCE_BOTTLE, () -> c.levelEta, value -> c.levelEta = value);
				new ToggleRow("gather", Items.CHEST, () -> c.gatherRates, value -> c.gatherRates = value);
				new ToggleRow("minimap", Items.MAP, () -> c.minimap, value -> c.minimap = value);
				new ToggleRow("afk", Items.CLOCK, () -> c.afkPause, value -> c.afkPause = value);
				new CycleRow("preset", Items.ITEM_FRAME, () -> c.activePreset, HudPresets::apply, HudPresets.NAMES);
				new ActionRow("preset_save", "save", Items.WRITABLE_BOOK, () -> HudPresets.save(c.activePreset));
				new ToggleRow("preset_contest", Items.GOLDEN_HOE, () -> c.contestPreset, value -> c.contestPreset = value);
			}
			case "trees" -> {
				new CycleRow("navigator", Items.COMPASS, () -> c.navigator, value -> {
					c.navigator = value;
					c.treeArrow = value.contains("arrow") || value.equals("all");
					c.treeChoice = value.contains("star") || value.equals("all");
					c.treeRoute = value.equals("route") || value.equals("all");
				}, List.of("off", "arrow", "star", "arrow_star", "route", "all"));
				new ToggleRow("protected_trees", Items.BARRIER, () -> c.protectedTrees, value -> c.protectedTrees = value);
				new ToggleRow("regrow_warning", Items.OAK_SAPLING, () -> c.regrowWarning, value -> c.regrowWarning = value);
				new ToggleRow("big_trees_only", Items.DARK_OAK_LOG, () -> c.bigTreesOnly, value -> c.bigTreesOnly = value);
				new ToggleRow("tree_markers", Items.SLIME_BALL, () -> c.treeMarkers, value -> c.treeMarkers = value);
				new ToggleRow("tree_size", Items.OAK_LOG, () -> c.treeSizeLabel, value -> c.treeSizeLabel = value);
				new ToggleRow("sweep_preview", Items.IRON_AXE, () -> c.sweepPreview, value -> c.sweepPreview = value);
				new ToggleRow("precision", Items.SPECTRAL_ARROW, () -> c.precisionHelper, value -> c.precisionHelper = value);
				new ToggleRow("clean_trees", Items.FEATHER, () -> c.cleanTrees, value -> c.cleanTrees = value);
				new ToggleRow("prevent_strip", Items.IRON_AXE, () -> c.preventStripping, value -> c.preventStripping = value);
				new ToggleRow("helix", Items.STRIPPED_BIRCH_WOOD, () -> c.helixGuide, value -> c.helixGuide = value);
				new ToggleRow("ghost_routes", Items.PHANTOM_MEMBRANE, () -> c.ghostRoutes, value -> c.ghostRoutes = value);
			}
			case "galatea" -> {
				new ToggleRow("milestones", Items.OAK_SAPLING, () -> c.milestones, value -> c.milestones = value);
				new ToggleRow("attribute_overlay", Items.PRISMARINE_SHARD, () -> c.attributeOverlay, value -> c.attributeOverlay = value);
				new ToggleRow("vendor_tracker", Items.EMERALD, () -> c.vendorTracker, value -> c.vendorTracker = value);
				new ToggleRow("drop_protection", Items.GOLD_NUGGET, () -> c.dropProtection, value -> c.dropProtection = value);
				new ToggleRow("huntrap_protection", Items.TRIPWIRE_HOOK, () -> c.huntrapProtection, value -> c.huntrapProtection = value);
				new ToggleRow("critters", Items.SPYGLASS, () -> c.critterHighlight, value -> c.critterHighlight = value);
				new ToggleRow("invisibug", Items.GLOW_BERRIES, () -> c.invisibugHelper, value -> c.invisibugHelper = value);
				new ToggleRow("strider", Items.STRING, () -> c.striderCounter, value -> c.striderCounter = value);
				new ToggleRow("fall_alert", Items.BELL, () -> c.fallAlert, value -> c.fallAlert = value);
				new ToggleRow("inventory_warning", Items.CHEST, () -> c.inventoryWarning, value -> c.inventoryWarning = value);
				new ToggleRow("beacon_alert", Items.BEACON, () -> c.beaconAlert, value -> c.beaconAlert = value);
				new ToggleRow("beacon_solver", Items.NOTE_BLOCK, () -> c.beaconSolver, value -> c.beaconSolver = value);
				new ToggleRow("hotf_costs", Items.OAK_SAPLING, () -> c.hotfCosts, value -> c.hotfCosts = value);
				new ToggleRow("whisper_reminder", Items.OAK_SAPLING, () -> c.whisperReminder, value -> c.whisperReminder = value);
				new ToggleRow("pause_reminder", Items.CAKE, () -> c.pauseReminder, value -> c.pauseReminder = value);
			}
			case "alerts" -> {
				new ToggleRow("sound", Items.BELL, () -> c.contestSound, value -> c.contestSound = value);
				new ToggleRow("title", Items.OAK_SIGN, () -> c.contestTitle, value -> c.contestTitle = value);
				new ToggleRow("countdown", Items.CLOCK, () -> c.contestCountdown, value -> c.contestCountdown = value);
				new ToggleRow("countdown_sound", Items.NOTE_BLOCK, () -> c.contestCountdownSound, value -> c.contestCountdownSound = value);
				new ToggleRow("toasts", Items.ITEM_FRAME, () -> c.toasts, value -> c.toasts = value);
				new ToggleRow("toast_sound", Items.BELL, () -> c.toastSound, value -> c.toastSound = value);
				new ToggleRow("toast_chat", Items.PAPER, () -> c.toastChat, value -> c.toastChat = value);
				new ActionRow("alarms", "open", Items.GOLD_INGOT, () -> minecraft.gui.setScreen(new StatsScreen(this, 8)));
				new ActionRow("test_alert", "play", Items.NOTE_BLOCK, () -> {
					toastNs = System.nanoTime();
					if (c.contestSound) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f));
				});
			}
			case "chat" -> {
				new ToggleRow("compact_gifts", Items.CHEST, () -> c.compactGifts, value -> c.compactGifts = value);
				new ToggleRow("compact_sweep", Items.PAPER, () -> c.compactSweep, value -> c.compactSweep = value);
				new ToggleRow("compact_contest", Items.GOLDEN_HOE, () -> c.compactContest, value -> c.compactContest = value);
				new ToggleRow("hide_lottery", Items.PAPER, () -> c.hideLottery, value -> c.hideLottery = value);
				new ToggleRow("mute_phantoms", Items.PHANTOM_MEMBRANE, () -> c.mutePhantoms, value -> c.mutePhantoms = value);
				new ToggleRow("mute_mobs", Items.DEAD_BUSH, () -> c.muteMobs, value -> c.muteMobs = value);
				new ToggleRow("mute_tree_break", Items.NOTE_BLOCK, () -> c.muteTreeBreak, value -> c.muteTreeBreak = value);
				new ActionRow("sounds_list", "open", Items.NOTE_BLOCK, () -> {
					onClose();
					if (minecraft.player != null) Sounds.list(minecraft.player::sendSystemMessage);
				});
			}
			case "online" -> {
				new ToggleRow("leaderboard", Items.GOLDEN_HOE, () -> c.leaderboardShare, value -> {
					c.leaderboardShare = value;
					if (value && !Leaderboard.verified()) Leaderboard.verify();
					if (!value) Leaderboard.forget();
				});
				new ActionRow("leaderboard_open", "open", Items.GOLD_INGOT, () -> minecraft.gui.setScreen(new StatsScreen(this, 11)));
				new ToggleRow("update_check", Items.CLOCK, () -> c.updateCheck, value -> c.updateCheck = value);
				new ActionRow("setup", "open", Items.NETHER_STAR, () -> minecraft.gui.setScreen(new SetupScreen(this)));
			}
			case "planner" -> {
				new ActionRow("perk_tree", "open", Items.OAK_SAPLING, () -> minecraft.gui.setScreen(new PerkTreeScreen(this)));
				new ActionRow("gear", "open", Items.IRON_CHESTPLATE, () -> minecraft.gui.setScreen(new GearScreen(this)));
				new CycleRow("goal", Items.NETHER_STAR, () -> c.goal, value -> c.goal = value,
						Arrays.stream(Goal.values()).map(Goal::id).toList());
				List<String> trees = new ArrayList<>(List.of("auto"));
				Planner.data.trees.forEach(tree -> trees.add(tree.id));
				new CycleRow("tree", Items.OAK_LOG, () -> c.tree, value -> c.tree = value, trees);
				new ToggleRow("learning", Items.BOOK, () -> c.learnSweep, value -> {
					c.learnSweep = value;
					LearnedSweep.apply();
				});
				new NumberRow("tokens", Items.SUNFLOWER, c.tokens, 0, 100, true, value -> c.tokens = (int) value, () -> true);
				new NumberRow("tier", Items.OAK_SAPLING, c.hotfTier, 1, 8, true, value -> c.hotfTier = (int) value, () -> true);
				new NumberRow("forest_budget", Items.OAK_LOG, c.whispers, 0, 2_000_000_000, true, value -> c.whispers = (long) value, () -> true);
				new NumberRow("desert_budget", Items.SAND, c.desertWhispers, 0, 2_000_000_000, true, value -> c.desertWhispers = (long) value, () -> true);
			}
			case "stats" -> {
				new ToggleRow("manual", Items.WRITABLE_BOOK, () -> c.manualStats, value -> c.manualStats = value);
				BooleanSupplier manual = () -> c.manualStats;
				new NumberRow("sweep", Items.IRON_AXE, c.manualSweep, 0, 100_000, false, value -> c.manualSweep = value, manual);
				new NumberRow("fortune", Items.GOLD_INGOT, c.manualFortune, 0, 100_000, false, value -> c.manualFortune = value, manual);
				new NumberRow("wisdom", Items.EXPERIENCE_BOTTLE, c.manualWisdom, 0, 100_000, false, value -> c.manualWisdom = value, manual);
				new NumberRow("strength", Items.IRON_SWORD, c.manualStrength, 0, 100_000, false, value -> c.manualStrength = value, manual);
				new NumberRow("speed", Items.SUGAR, c.manualSpeed, 0, 100_000, false, value -> c.manualSpeed = value, manual);
			}
			case "safari" -> {
				new ToggleRow("safari_tracker", Items.CLOCK, () -> c.safariTracker, value -> c.safariTracker = value);
				new NumberRow("capsule_warn", Items.ENDER_PEARL, c.capsuleWarnAt, 0, 20, true, value -> c.capsuleWarnAt = (int) value, () -> true);
				new ToggleRow("safari_bells", Items.BELL, () -> c.safariBells, value -> c.safariBells = value);
				new ToggleRow("bell_route", Items.COMPASS, () -> c.bellRoute, value -> c.bellRoute = value);
				new ActionRow("bell_reset", "reset", Items.BELL, Bells::reset);
				new ToggleRow("safari_prices", Items.GOLD_NUGGET, () -> c.safariPrices, value -> c.safariPrices = value);
				new ToggleRow("sparkling_alert", Items.GLOWSTONE_DUST, () -> c.sparklingAlert, value -> c.sparklingAlert = value);
				new ToggleRow("critterdex_helper", Items.BOOK, () -> c.critterdexHelper, value -> c.critterdexHelper = value);
				new ToggleRow("safari_milestones", Items.OAK_SAPLING, () -> c.safariMilestones, value -> c.safariMilestones = value);
				new ToggleRow("ticket_reminder", Items.PAPER, () -> c.ticketReminder, value -> c.ticketReminder = value);
				new ToggleRow("safari_ground", Items.HOPPER, () -> c.safariGroundItems, value -> c.safariGroundItems = value);
				new ToggleRow("honey_helix", Items.HONEYCOMB, () -> c.honeyHelix, value -> c.honeyHelix = value);
				new ToggleRow("party_progress", Items.PLAYER_HEAD, () -> c.partyProgress, value -> c.partyProgress = value);
				new ToggleRow("safari_debug", Items.WRITABLE_BOOK, () -> c.safariDebug, value -> c.safariDebug = value);
			}
			case "style" -> {
				new SliderRow("hud_radius", Items.SNOWBALL, 0, 12, 1, "px", () -> c.hudRadius, value -> c.hudRadius = value);
				new SliderRow("hud_opacity", Items.SPYGLASS, 0, 100, 5, "%", () -> c.hudOpacity, value -> c.hudOpacity = value);
				new ColorRow("hud_color", Items.INK_SAC, BACKGROUNDS, () -> c.hudColor, value -> c.hudColor = value);
				new SliderRow("hud_padding", Items.ITEM_FRAME, 2, 10, 1, "px", () -> c.hudPadding, value -> c.hudPadding = value);
				new ToggleRow("hud_border", Items.PAINTING, () -> c.hudBorder, value -> c.hudBorder = value);
				new ColorRow("hud_border_color", Items.GLOW_INK_SAC, ACCENTS, () -> c.hudBorderColor, value -> c.hudBorderColor = value);
				new ColorRow("hud_label_color", Items.NAME_TAG, LABELS, () -> c.hudLabelColor, value -> c.hudLabelColor = value);
				new ToggleRow("hud_shadow", Items.COAL, () -> c.hudShadow, value -> c.hudShadow = value);
				new SliderRow("map_size", Items.MAP, 60, 200, 10, "px", () -> c.mapSize, value -> c.mapSize = value);
				new SliderRow("map_range", Items.COMPASS, 30, 150, 10, "m", () -> c.mapRange, value -> c.mapRange = value);
				new CycleRow("map_corner", Items.ITEM_FRAME, () -> c.mapCorner, value -> c.mapCorner = value, List.of("top_right", "top_left", "bottom_right", "bottom_left"));
				new SliderRow("session_columns", Items.CLOCK, 1, 3, 1, "", () -> c.sessionColumns, value -> c.sessionColumns = value);
				new ActionRow("hud_style_reset", "restore", Items.BARRIER, () -> {
					c.hudRadius = 0; c.hudOpacity = 47; c.hudColor = 0x000000; c.hudPadding = 4; c.hudBorder = false;
					c.hudBorderColor = 0x55FF55; c.hudLabelColor = 0xAAAAAA; c.hudShadow = true; c.sessionColumns = 2;
				});
				new ActionRow("position", "move", Items.COMPASS, () -> minecraft.gui.setScreen(new HudEditorScreen(this)));
			}
			default -> {}
		}
	}


	private void switchPage(int target) {
		if (target == page || target < 0 || target >= PAGES.length) return;

		page = target;
		pageNs = System.nanoTime();
		scroll = 0;
		scrollTarget = 0;
		setFocused(null);
		init();
	}

	private int rowY(Row row) {
		return viewTop + row.index * ROW_STEP - (int) Math.round(scroll);
	}

	private int maxScroll() {
		int content = rows.size() * ROW_STEP - 5;
		return Math.max(0, content - (viewBottom - viewTop));
	}

	private void markDirty() {
		dirty = true;
		dirtyNs = System.nanoTime();
	}

	private void save(long now) {
		Planner.config.save();
		dirty = false;
		savedNs = now;
	}

	private void playClick() {
		AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// Drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// The game behind stays visible but blurred; the panel itself provides the darkening.
		if (minecraft.level == null) extractPanorama(g, delta);
		extractBlurredBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - lastFrameNs) / 1e9);
		lastFrameNs = now;

		if (dirty && now - dirtyNs > SAVE_DELAY_NS) save(now);

		double open = animated() ? easeOut((now - openedNs) / 280e6) : 1;
		drawBackdrop(g, now, open);

		float scale = (float) (animated() ? 0.965 + 0.035 * easeOutBack((now - openedNs) / 340e6) : 1);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f);
		pose.scale(scale, scale);
		pose.translate(-width / 2f, -height / 2f);

		drawPanel(g, open);
		drawSidebar(g, mouseX, mouseY, dt, open);
		drawHeader(g, now, open);
		drawRows(g, mouseX, mouseY, delta, dt, now, open);
		if (previewW > 0) drawPreview(g, now, open);
		drawFooter(g, mouseX, mouseY, dt, open);

		pose.popMatrix();
		drawToast(g, now);
	}

	private void drawBackdrop(GuiGraphicsExtractor g, long now, double open) {
		g.fillGradient(0, 0, width, height, alpha(0xFF06100C, 0.70 * open), alpha(0xFF020604, 0.88 * open));
		if (!animated()) return;

		// Fireflies: each one drifts slowly upwards on its own path and pulses at its own pace.
		double t = (now - openedNs) / 1e9;

		for (int i = 0; i < FIREFLIES; i++) {
			double seed = (i + 1) * 12.9898;
			double baseX = fract(Math.sin(seed) * 43758.5453);
			double baseY = fract(Math.sin(seed * 1.7) * 24634.6345);
			double speed = 0.4 + fract(Math.sin(seed * 2.3) * 9631.13) * 0.8;
			int x = (int) ((baseX + Math.sin(t * 0.35 * speed + i) * 0.025) * width);
			int y = (int) (fract(baseY - t * 0.012 * speed) * height);
			double glow = (0.5 + 0.5 * Math.sin(t * (1.2 + speed) + i * 1.3)) * open;
			int color = i % 3 == 0 ? UI_ACCENT : UI_AMBER;

			g.fill(x - 2, y - 2, x + 3, y + 3, alpha(color, 0.08 * glow));
			g.fill(x - 1, y - 1, x + 2, y + 2, alpha(color, 0.22 * glow));
			g.fill(x, y, x + 1, y + 1, alpha(color, 0.9 * glow));
		}
	}

	private void drawPanel(GuiGraphicsExtractor g, double open) {
		shadow(g, panelX, panelY, panelW, panelH, RADIUS, open);
		round(g, panelX, panelY, panelW, panelH, RADIUS, alpha(UI_PANEL, open));
		round(g, panelX + 4, panelY + 4, sideW - 4, panelH - 8, RADIUS - 3, alpha(UI_SIDEBAR, open));
		ForestTheme.leaves(g, panelX, panelY, panelW, panelH, (System.nanoTime() - openedNs) / 1e9, open);
		roundOutline(g, panelX, panelY, panelW, panelH, RADIUS, alpha(0x22FFFFFF, open));
		g.fill(panelX + RADIUS, panelY, panelX + panelW - RADIUS, panelY + 1, alpha(ForestTheme.UI_ACCENT, 0.35 * open));
		g.fill(panelX + RADIUS, panelY + 1, panelX + panelW - RADIUS, panelY + 2, alpha(0x12FFFFFF, open));
	}

	private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int x = panelX + 4;
		int w = sideW - 4;
		boolean compact = compactSidebar();

		int logoX = compact ? x + (w - 22) / 2 : x + 9;
		round(g, logoX, panelY + 13, 22, 22, 6, alpha(UI_ACCENT_DARK, open));
		icon(g, Items.IRON_AXE, logoX + 3, panelY + 16, open);

		if (!compact) {
			g.text(font, "Foraging", x + 37, panelY + 14, alpha(UI_TEXT, open), false);
			g.text(font, "Planner", x + 37, panelY + 25, alpha(UI_ACCENT, open), false);
		}

		int navTop = panelY + 50;
		pill = animated() ? approach(pill, page, dt, 14) : page;
		int pillY = navTop + (int) Math.round(pill * NAV_STEP);
		round(g, x + 6, pillY, w - 12, NAV_HEIGHT, 6, alpha(UI_ACCENT_SOFT, open));
		g.fill(x + 6, pillY + 7, x + 8, pillY + NAV_HEIGHT - 7, alpha(UI_AMBER, open));

		for (int i = 0; i < PAGES.length; i++) {
			int itemY = navTop + i * NAV_STEP;
			boolean hovered = inside(mouseX, mouseY, x + 6, itemY, w - 12, NAV_HEIGHT) && i != page;
			navHover[i] = animated() ? approach(navHover[i], hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;

			if (navHover[i] > 0.01) round(g, x + 6, itemY, w - 12, NAV_HEIGHT, 6, alpha(0x12FFFFFF, navHover[i] * open));

			int iconX = compact ? x + (w - 16) / 2 : x + 13 + (int) Math.round(navHover[i] * 2);
			icon(g, PAGE_ICONS[i], iconX, itemY + 2, open * (i == page ? 1 : 0.7 + 0.3 * navHover[i]));

			if (!compact) {
				int color = i == page ? UI_TEXT : mixArgb(UI_MUTED, UI_TEXT, navHover[i]);
				g.text(font, fit(font, text(PAGES[i]).getString(), w - 46), iconX + 21, itemY + 7, alpha(color, open), false);
			}
		}

		if (!compact && panelH >= 240) {
			int bottom = panelY + panelH - 40;
			g.fill(x + 10, bottom - 6, x + w - 10, bottom - 5, alpha(0x14FFFFFF, open));
			icon(g, Items.NETHER_STAR, x + 10, bottom, open * 0.9);
			g.text(font, fit(font, Planner.tr("option." + Planner.config.goal), w - 40), x + 31, bottom + 1, alpha(UI_AMBER, open), false);
			g.text(font, fit(font, Planner.tr("option." + Planner.config.tree), w - 40), x + 31, bottom + 11, alpha(UI_DIM, open), false);
		}
	}

	private void drawHeader(GuiGraphicsExtractor g, long now, double open) {
		double appear = animated() ? easeOut((now - pageNs) / 220e6) : 1;
		int shift = (int) Math.round((1 - appear) * 8);
		double a = open * appear;

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(contentX + shift, panelY + 13);
		pose.scale(1.4f, 1.4f);
		g.text(font, query.isEmpty() ? text(PAGES[page]) : Component.literal(Planner.tr("search.title")), 0, 0, alpha(UI_TEXT, a), false);
		pose.popMatrix();

		String subtitle = query.isEmpty() ? Planner.tr("settings.page." + PAGES[page]) : Planner.tr("search.results", rows.size(), query);
		g.text(font, fit(font, subtitle, contentW - 90), contentX + shift, panelY + 31, alpha(UI_MUTED, a), false);

		drawSearch(g, open);

		if (savedNs > 0) {
			double age = (now - savedNs) / 1e9;
			double shown = age < 1.6 ? easeOut(age / 0.2) : Math.max(0, 1 - (age - 1.6) / 0.6);

			if (shown > 0.01) {
				String saved = "✔ " + Planner.tr("settings.saved_now");
				int right = previewW > 0 ? previewX + previewW : contentX + contentW;
				g.text(font, saved, right - font.width(saved), panelY + 34, alpha(UI_ACCENT, open * shown), false);
			}
		}
	}

	private void drawSearch(GuiGraphicsExtractor g, double open) {
		int w = Math.min(150, contentW / 2);
		int x = contentX + contentW - w;
		int y = panelY + 12;
		boolean focused = getFocused() == searchBox;
		round(g, x, y, w, 18, 6, alpha(focused ? 0xFF1C3324 : UI_ROW, open));
		roundOutline(g, x, y, w, 18, 6, alpha(focused ? UI_ACCENT : 0x22FFFFFF, open));
		g.text(font, "⌕", x + 6, y + 5, alpha(focused ? UI_ACCENT : UI_MUTED, open), false);
		searchBox.setX(x + 16);
		searchBox.setY(y + 5);
		searchBox.setWidth(w - 22);
		searchBox.extractRenderState(g, -1, -1, 0);
	}

	private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, double dt, long now, double open) {
		scroll = animated() ? approach(scroll, scrollTarget, dt, 16) : scrollTarget;
		g.enableScissor(contentX - 4, viewTop, contentX + contentW + 12, viewBottom);

		for (Row row : rows) {
			int y = rowY(row);
			boolean inView = y + ROW_HEIGHT > viewTop && y < viewBottom;
			double appear = animated() ? easeOut((now - pageNs - row.index * 35e6) / 240e6) : 1;
			double edge = Math.clamp(Math.min((y + ROW_HEIGHT - viewTop) / 14.0, (viewBottom - y) / 14.0), 0.0, 1.0);
			double a = open * appear * edge;
			row.hide(!inView || a < 0.6);
			if (!inView || a <= 0.01) continue;

			double rowA = a * (row.enabled() ? 1 : 0.45);
			int x = contentX + (int) Math.round((1 - appear) * 14);
			boolean hovered = !draggingBar && row.enabled() && inside(mouseX, mouseY, contentX, Math.max(y, viewTop), contentW,
					Math.min(y + ROW_HEIGHT, viewBottom) - Math.max(y, viewTop));
			row.hover = animated() ? approach(row.hover, hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;

			round(g, x, y, contentW, ROW_HEIGHT, 7, alpha(mixArgb(UI_ROW, UI_ROW_HOVER, row.hover), rowA));
			round(g, x + 8, y + 8, 20, 20, 5, alpha(UI_TILE, rowA));
			icon(g, row.icon, x + 10, y + 10, rowA);

			int textWidth = contentW - 36 - row.controlWidth() - 18;
			g.text(font, fit(font, text(row.name).getString(), textWidth), x + 36, y + 8, alpha(UI_TEXT, rowA), false);
			g.text(font, fit(font, Planner.tr("settings." + row.name + ".hint"), textWidth), x + 36, y + 20, alpha(UI_MUTED, rowA), false);

			row.drawControl(g, x + contentW - 10, y + ROW_HEIGHT / 2, rowA, mouseX, mouseY, dt, delta, now);
		}

		g.disableScissor();
		drawScrollbar(g, mouseX, mouseY, dt, open);
	}

	private void drawScrollbar(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int max = maxScroll();
		if (max <= 0) return;

		int track = viewBottom - viewTop;
		int content = track + max;
		int thumb = Math.max(20, track * track / content);
		int y = viewTop + (int) Math.round((track - thumb) * scroll / max);
		int x = contentX + contentW + 5;
		boolean hovered = draggingBar || inside(mouseX, mouseY, x - 3, viewTop, 9, track);
		barHover = animated() ? approach(barHover, hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;

		round(g, x, viewTop, 3, track, 1, alpha(0x14FFFFFF, open));
		round(g, x, y, 3, thumb, 1, alpha(mixArgb(0x9986BD6E, UI_ACCENT, barHover), open));
	}

	private void drawPreview(GuiGraphicsExtractor g, long now, double open) {
		int x = previewX;
		int y = viewTop;
		int w = previewW;
		int h = viewBottom - viewTop;
		double appear = animated() ? easeOut((now - pageNs - 60e6) / 280e6) : 1;
		double a = open * appear;

		round(g, x, y, w, h, 8, alpha(0x1AFFFFFF, open));
		roundOutline(g, x, y, w, h, 8, alpha(0x14FFFFFF, open));

		double pulse = animated() ? 0.55 + 0.45 * Math.sin((now - openedNs) / 1e9 * 3) : 1;
		round(g, x + w - 14, y + 10, 5, 5, 2, alpha(UI_ACCENT, open * pulse));
		g.text(font, fit(font, Planner.tr("settings.preview_" + PAGES[page]).toUpperCase(), w - 34), x + 10, y + 9, alpha(UI_DIM, open), false);

		int top = y + 26 + (int) Math.round((1 - appear) * 6);

		int kind = switch (PAGES[page]) { case "planner" -> 1; case "alerts" -> 2; case "stats" -> 3; case "style" -> 4; default -> 0; };
		switch (kind) {
			case 0 -> {
				List<String> lines = PlannerHud.previewLines();
				int boxH = Math.min(h - 36, lines.size() * (font.lineHeight + 2) + 10);
				round(g, x + 8, top, w - 16, boxH, 5, alpha(0x90000000, a));
				int lineY = top + 6;

				for (String line : lines) {
					if (lineY + font.lineHeight > top + boxH - 4) break;
					g.text(font, fit(font, line, w - 30), x + 14, lineY, alpha(0xFFFFFFFF, a));
					lineY += font.lineHeight + 2;
				}
			}
			case 1 -> {
				long whispers = Planner.config.whispers;
				g.text(font, Planner.tr("settings.forest_budget"), x + 10, top + 2, alpha(UI_MUTED, a), false);
				bigNumber(g, whispers < 0 ? "?" : Planner.whole(whispers), x + 10, top + 14, UI_AMBER, a);
				g.text(font, fit(font, Planner.tr("settings.perks_read", Planner.config.perkLevels.size()), w - 20), x + 10, top + 40, alpha(UI_TEXT, a), false);
				chip(g, x + 10, top + 58, Items.NETHER_STAR, Planner.tr("option." + Planner.config.goal), w - 20, a);
				chip(g, x + 10, top + 80, Items.OAK_LOG, Planner.tr("option." + Planner.config.tree), w - 20, a);
			}
			case 2 -> {
				round(g, x + 8, top, w - 16, 40, 6, alpha(0xE0122019, a));
				g.fill(x + 8, top + 6, x + 10, top + 34, alpha(UI_AMBER, a));
				icon(g, Items.BELL, x + 16, top + 12, a);
				g.text(font, fit(font, Planner.tr("settings.alert_example", "Agatha"), w - 54), x + 38, top + 9, alpha(UI_AMBER, a), false);
				g.text(font, fit(font, Planner.tr("settings.alert_explanation"), w - 54), x + 38, top + 22, alpha(UI_MUTED, a), false);
				g.text(font, fit(font, Planner.tr("settings.test_alert.hint"), w - 20), x + 10, top + 52, alpha(UI_DIM, a), false);
			}
			case 4 -> drawStylePreview(g, x + 8, top, w - 16, a);
			default -> {
				var stats = SkyBlockReader.stats();
				boolean known = SkyBlockReader.statsKnown();
				g.text(font, Planner.tr("settings.sweep"), x + 10, top + 2, alpha(UI_MUTED, a), false);
				bigNumber(g, known ? Planner.decimal(stats.sweep()) : "?", x + 10, top + 14, UI_ACCENT, a);
				statLine(g, x + 10, top + 42, w - 20, "fortune", known ? Planner.decimal(stats.fortune()) : "?", a);
				statLine(g, x + 10, top + 56, w - 20, "wisdom", known ? Planner.decimal(stats.wisdom()) : "?", a);
				String source = Planner.tr(Planner.config.manualStats ? "settings.manual" : "settings.tablist");
				g.text(font, fit(font, Planner.tr("settings.stat_source", source), w - 20), x + 10, top + 76, alpha(UI_DIM, a), false);
			}
		}
	}

	/** A small card drawn with the current HUD style. */
	private void drawStylePreview(GuiGraphicsExtractor g, int x, int y, int w, double a) {
		ModConfig c = Planner.config;
		int pad = Math.clamp(c.hudPadding, 2, 10);
		int h = 2 * pad + 12 + 3 * 11;
		int fill = ((int) Math.round(Math.clamp(c.hudOpacity, 0, 100) * 2.55) << 24) | (c.hudColor & 0xFFFFFF);
		int radius = Math.clamp(c.hudRadius, 0, 12);
		// Checkered backdrop, so the opacity is visible.
		for (int cx = 0; cx < w; cx += 8) for (int cy = 0; cy < h + 8; cy += 8) {
			if (((cx + cy) / 8) % 2 == 0) g.fill(x + cx, y - 4 + cy, x + Math.min(w, cx + 8), y - 4 + Math.min(h + 8, cy + 8), alpha(0x22FFFFFF, a));
		}
		if (radius <= 0) g.fill(x, y, x + w, y + h, alpha(fill, a));
		else round(g, x, y, w, h, radius, alpha(fill, a));
		if (c.hudBorder) roundOutline(g, x, y, w, h, radius, alpha(0xFF000000 | c.hudBorderColor, a));
		boolean shadow = c.hudShadow;
		int label = 0xFF000000 | c.hudLabelColor;
		g.text(font, Planner.tr("journal.module_session"), x + pad, y + pad, alpha(0xFF55FFFF, a), shadow);
		String[] names = {"hud.session_xp", "hud.session_coins", "hud.session_gifts"};
		String[] values = {"178.3k", "1.2M", "191"};
		int[] colors = {0xFF55FF55, 0xFFFFAA00, 0xFFFFFFFF};
		for (int i = 0; i < names.length; i++) {
			String name = Planner.tr(names[i]) + " ";
			int ty = y + pad + 12 + i * 11;
			g.text(font, name, x + pad, ty, alpha(label, a), shadow);
			g.text(font, values[i], x + pad + font.width(name), ty, alpha(colors[i], a), shadow);
		}
	}

	private void bigNumber(GuiGraphicsExtractor g, String value, int x, int y, int color, double a) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(1.8f, 1.8f);
		g.text(font, value, 0, 0, alpha(color, a), false);
		pose.popMatrix();
	}

	private void chip(GuiGraphicsExtractor g, int x, int y, Item item, String label, int maxWidth, double a) {
		int w = Math.min(maxWidth, font.width(label) + 30);
		round(g, x, y, w, 18, 9, alpha(0x22FFFFFF, a));
		icon(g, item, x + 3, y + 1, a);
		g.text(font, fit(font, label, w - 28), x + 22, y + 5, alpha(UI_TEXT, a), false);
	}

	private void statLine(GuiGraphicsExtractor g, int x, int y, int w, String name, String value, double a) {
		g.text(font, Planner.tr("settings." + name), x, y, alpha(UI_MUTED, a), false);
		g.text(font, value, x + w - font.width(value), y, alpha(UI_TEXT, a), false);
	}

	private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int lineRight = previewW > 0 ? previewX + previewW : contentX + contentW;
		int y = panelY + panelH - 27;
		g.fill(contentX, y - 7, lineRight, y - 6, alpha(0x14FFFFFF, open));

		String hint = "v" + ModVersion.plain() + "  ·  " + Planner.tr(maxScroll() > 0 ? "settings.keys_scroll" : "settings.keys");
		String done = text("done").getString();
		doneW = Math.max(68, font.width(done) + 26);
		doneX = panelX + panelW - 14 - doneW;
		doneY = y - 1;
		g.text(font, fit(font, hint, doneX - contentX - 10), contentX, y + 4, alpha(UI_DIM, open), false);

		boolean hovered = inside(mouseX, mouseY, doneX, doneY, doneW, 18);
		doneHover = animated() ? approach(doneHover, hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
		int lift = (int) Math.round(doneHover);
		round(g, doneX, doneY - lift, doneW, 18, 6, alpha(mixArgb(0xFF6FA35B, UI_ACCENT, doneHover), open));
		g.centeredText(font, Component.literal(done), doneX + doneW / 2, doneY + 5 - lift, alpha(UI_ON_ACCENT, open));
	}

	private void drawToast(GuiGraphicsExtractor g, long now) {
		if (toastNs == 0) return;

		double age = (now - toastNs) / 1e9;
		if (age > TOAST_SECONDS) return;

		double in = animated() ? easeOutBack(age / 0.35) : 1;
		double out = age > TOAST_SECONDS - 0.4 ? easeOut((age - (TOAST_SECONDS - 0.4)) / 0.4) : 0;
		double a = Math.min(1, age / 0.15) * (1 - out);

		String title = Planner.tr("alert.start", "Agatha");
		String sub = Planner.tr("settings.alert_explanation");
		int w = Math.min(width - 20, Math.max(180, Math.max(font.width(title), font.width(sub)) + 50));
		int h = 36;
		int x = (width - w) / 2;
		int y = (int) Math.round(-h + (h + 10) * in - out * 14);

		shadow(g, x, y, w, h, 8, a);
		round(g, x, y, w, h, 8, alpha(0xF0122019, a));
		roundOutline(g, x, y, w, h, 8, alpha(0x33EBC678, a));
		round(g, x + 8, y + 8, 20, 20, 5, alpha(UI_TILE, a));
		icon(g, Items.BELL, x + 10, y + 10, a);
		g.text(font, fit(font, title, w - 46), x + 36, y + 8, alpha(UI_AMBER, a), false);
		g.text(font, fit(font, sub, w - 46), x + 36, y + 20, alpha(UI_MUTED, a), false);

		int bar = (int) Math.round((w - 16) * (1 - age / TOAST_SECONDS));
		g.fill(x + 8, y + h - 3, x + 8 + bar, y + h - 2, alpha(UI_ACCENT, a));
	}

	// Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled);
		double mx = event.x();
		double my = event.y();

		if (inside(mx, my, doneX, doneY - 1, doneW, 19)) {
			playClick();
			onClose();
			return true;
		}

		int navTop = panelY + 50;

		for (int i = 0; i < PAGES.length; i++) {
			if (inside(mx, my, panelX + 10, navTop + i * NAV_STEP, sideW - 12, NAV_HEIGHT)) {
				if (i != page) {
					playClick();
					switchPage(i);
				}

				return true;
			}
		}

		if (maxScroll() > 0 && inside(mx, my, contentX + contentW + 1, viewTop, 10, viewBottom - viewTop)) {
			draggingBar = true;
			scrollFromMouse(my);
			return true;
		}

		if (super.mouseClicked(event, doubled)) return true;

		if (inside(mx, my, contentX, viewTop, contentW, viewBottom - viewTop)) {
			for (Row row : rows) {
				int y = rowY(row);
				if (my < y || my >= y + ROW_HEIGHT) continue;
				if (!row.enabled()) return true;

				if (!(row instanceof NumberRow)) setFocused(null);

				if (row.click(mx, my, contentX + contentW - 10, y + ROW_HEIGHT / 2)) {
					playClick();
					markDirty();
					PlannerHud.rebuild();
				}

				return true;
			}
		}

		setFocused(null);
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingSlider != null) {
			draggingSlider.drag(event.x());
			markDirty();
			PlannerHud.rebuild();
			return true;
		}
		if (!draggingBar) return super.mouseDragged(event, dragX, dragY);
		scrollFromMouse(event.y());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingBar = false;
		draggingSlider = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (inside(mouseX, mouseY, contentX, viewTop, contentW + 12, viewBottom - viewTop)) {
			scrollTarget = Math.clamp(scrollTarget - vertical * 28, 0, maxScroll());
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	private void scrollFromMouse(double mouseY) {
		int track = viewBottom - viewTop;
		int max = maxScroll();
		int thumb = Math.max(20, track * track / (track + max));
		scrollTarget = Math.clamp((mouseY - viewTop - thumb / 2.0) * max / Math.max(1, track - thumb), 0, max);
		scroll = scrollTarget;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox;
		int pressed = event.key();

		if (event.hasControlDown() && pressed == InputConstants.KEY_F) {
			setFocused(searchBox);
			searchBox.setFocused(true);
			return true;
		}
		if (getFocused() == searchBox && pressed == InputConstants.KEY_ESCAPE) {
			if (!searchBox.getValue().isEmpty()) searchBox.setValue("");
			else setFocused(null);
			return true;
		}
		if (getFocused() == searchBox && pressed == InputConstants.KEY_RETURN) {
			setFocused(null);
			return true;
		}

		if (!typing && pressed >= InputConstants.KEY_1 && pressed < InputConstants.KEY_1 + PAGES.length) {
			switchPage(pressed - InputConstants.KEY_1);
			return true;
		}

		if (pressed == InputConstants.KEY_PAGEDOWN || pressed == InputConstants.KEY_PAGEUP
				|| (!typing && (pressed == InputConstants.KEY_DOWN || pressed == InputConstants.KEY_UP))) {
			boolean down = pressed == InputConstants.KEY_PAGEDOWN || pressed == InputConstants.KEY_DOWN;
			int step = pressed == InputConstants.KEY_PAGEDOWN || pressed == InputConstants.KEY_PAGEUP ? viewBottom - viewTop - 20 : ROW_STEP;
			scrollTarget = Math.clamp(scrollTarget + (down ? step : -step), 0, maxScroll());
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		if (dirty) save(System.nanoTime());
		PlannerHud.rebuild();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// Rows

	/** One line of the settings list: icon, name, hint, and a control on the right. */
	private abstract class Row {
		final String name;
		final Item icon;
		int index;
		double hover;

		Row(String name, Item icon) {
			this.name = name;
			this.icon = icon;
			this.index = rows.size();
			rows.add(this);
		}

		boolean enabled() {
			return true;
		}

		/** Width the control needs at the right edge, so the text can be cut before it. */
		abstract int controlWidth();

		abstract void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY,
								double dt, float delta, long now);

		/** Handles a click inside the row. Returns true when something changed. */
		abstract boolean click(double mouseX, double mouseY, int right, int midY);

		/** Called every frame; rows with input fields hide them while off screen. */
		void hide(boolean hidden) {}
	}

	private final class ToggleRow extends Row {
		private final BooleanSupplier get;
		private final Consumer<Boolean> set;
		private double knob;

		ToggleRow(String name, Item icon, BooleanSupplier get, Consumer<Boolean> set) {
			super(name, icon);
			this.get = get;
			this.set = set;
			this.knob = get.getAsBoolean() ? 1 : 0;
		}

		@Override
		int controlWidth() {
			return 30;
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			boolean on = get.getAsBoolean();
			knob = animated() ? approach(knob, on ? 1 : 0, dt, 18) : on ? 1 : 0;

			int x = right - 28;
			int y = midY - 7;
			round(g, x, y, 28, 14, 7, alpha(mixArgb(UI_SWITCH_OFF, UI_ACCENT, knob), a));
			int knobX = x + 2 + (int) Math.round(knob * 14);
			round(g, knobX, y + 2, 10, 10, 5, alpha(UI_KNOB, a));
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			set.accept(!get.getAsBoolean());
			return true;
		}
	}

	private final class ActionRow extends Row {
		private final String verb;
		private final Runnable action;

		ActionRow(String name, String verb, Item icon, Runnable action) {
			super(name, icon);
			this.verb = verb;
			this.action = action;
		}

		private String label() {
			return Planner.tr("settings." + verb) + "  ›";
		}

		@Override
		int controlWidth() {
			return font.width(label()) + 16;
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			String label = label();
			int w = font.width(label) + 16;
			int x = right - w;
			int lift = (int) Math.round(hover);
			round(g, x, midY - 8 - lift, w, 16, 5, alpha(mixArgb(0x26FFFFFF, UI_ACCENT_DARK | 0xFF000000, hover), a));
			g.text(font, label, x + 8, midY - 4 - lift, alpha(mixArgb(UI_TEXT, 0xFFFFFFFF, hover), a), false);
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			action.run();
			return true;
		}
	}

	private static final int[] BACKGROUNDS = {0x000000, 0x101418, 0x0E1A12, 0x0B1530, 0x1E1030, 0x2A0E14, 0x2A1B0E, 0x1F2A1F, 0x303030, 0x808080, 0xFFFFFF};
	private static final int[] ACCENTS = {0x55FF55, 0x86BD6E, 0x55FFFF, 0x5599FF, 0xC266FF, 0xFF66FF, 0xFF5555, 0xFFAA00, 0xFFFF55, 0xFFFFFF, 0x555555, 0x000000};
	private static final int[] LABELS = {0xAAAAAA, 0xFFFFFF, 0x777777, 0xC8D6C0, 0x9DB4D8, 0xE0C890, 0x55FFFF, 0x55FF55};

	private SliderRow draggingSlider;

	/** A number picked on a bar; click or drag. Clicking the number steps it up. */
	private final class SliderRow extends Row {
		private static final int WIDTH = 116;
		private static final int BAR = 76;

		private final int min, max, step;
		private final String suffix;
		private final java.util.function.IntSupplier get;
		private final java.util.function.IntConsumer set;
		private int barX;
		private double shown;

		SliderRow(String name, Item icon, int min, int max, int step, String suffix, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
			super(name, icon);
			this.min = min;
			this.max = max;
			this.step = step;
			this.suffix = suffix;
			this.get = get;
			this.set = set;
			this.shown = get.getAsInt();
		}

		@Override
		int controlWidth() {
			return WIDTH;
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			int value = get.getAsInt();
			shown = animated() ? approach(shown, value, dt, 18) : value;
			barX = right - WIDTH;
			double f = Math.clamp((shown - min) / (double) Math.max(1, max - min), 0, 1);
			round(g, barX, midY - 2, BAR, 4, 2, alpha(UI_SWITCH_OFF, a));
			round(g, barX, midY - 2, (int) Math.round(BAR * f), 4, 2, alpha(UI_ACCENT, a));
			int knob = barX + (int) Math.round(BAR * f) - 5;
			round(g, knob, midY - 5, 10, 10, 5, alpha(UI_KNOB, a));
			String text = value + suffix;
			g.text(font, text, right - font.width(text), midY - 4, alpha(UI_TEXT, a), false);
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			barX = right - WIDTH;
			if (mouseX > barX + BAR + 6) {
				int next = get.getAsInt() + step;
				set.accept(next > max ? min : next);
				return true;
			}
			draggingSlider = this;
			drag(mouseX);
			return true;
		}

		void drag(double mouseX) {
			double f = Math.clamp((mouseX - barX) / BAR, 0, 1);
			int value = min + (int) Math.round(f * (max - min) / step) * step;
			set.accept(Math.clamp(value, min, max));
		}
	}

	/** A colour picked from a palette with ‹ ›, shown as a swatch. */
	private final class ColorRow extends Row {
		private static final int WIDTH = 116;

		private final int[] palette;
		private final java.util.function.IntSupplier get;
		private final java.util.function.IntConsumer set;

		ColorRow(String name, Item icon, int[] palette, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
			super(name, icon);
			this.palette = palette;
			this.get = get;
			this.set = set;
		}

		@Override
		int controlWidth() {
			return WIDTH;
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			int x = right - WIDTH;
			int y = midY - 8;
			round(g, x, y, WIDTH, 16, 8, alpha(UI_FIELD, a));
			boolean overLeft = inside(mouseX, mouseY, x, y, WIDTH / 2, 16);
			boolean overRight = inside(mouseX, mouseY, x + WIDTH / 2, y, WIDTH / 2, 16);
			g.text(font, "‹", x + 7, y + 4, alpha(overLeft ? UI_AMBER : UI_DIM, a), false);
			g.text(font, "›", x + WIDTH - 11, y + 4, alpha(overRight ? UI_AMBER : UI_DIM, a), false);
			int color = get.getAsInt() & 0xFFFFFF;
			round(g, x + 20, y + 3, 10, 10, 3, alpha(0xFF000000 | color, a));
			roundOutline(g, x + 20, y + 3, 10, 10, 3, alpha(0x55FFFFFF, a));
			g.text(font, String.format(Locale.ROOT, "#%06X", color), x + 36, y + 4, alpha(UI_TEXT, a), false);
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			int x = right - WIDTH;
			int index = -1;
			int current = get.getAsInt() & 0xFFFFFF;
			for (int i = 0; i < palette.length; i++) if (palette[i] == current) index = i;
			int delta = mouseX >= x && mouseX < x + WIDTH / 2 ? -1 : 1;
			set.accept(palette[Math.floorMod(index < 0 ? 0 : index + delta, palette.length)]);
			return true;
		}
	}

	private final class CycleRow extends Row {
		private static final int WIDTH = 116;

		private final Supplier<String> get;
		private final Consumer<String> set;
		private final List<String> values;
		private int direction = 1;
		private long changedNs;

		CycleRow(String name, Item icon, Supplier<String> get, Consumer<String> set, List<String> values) {
			super(name, icon);
			this.get = get;
			this.set = set;
			this.values = values;
		}

		@Override
		int controlWidth() {
			return WIDTH;
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			int x = right - WIDTH;
			int y = midY - 8;
			round(g, x, y, WIDTH, 16, 8, alpha(UI_FIELD, a));

			boolean overLeft = inside(mouseX, mouseY, x, y, WIDTH / 2, 16);
			boolean overRight = inside(mouseX, mouseY, x + WIDTH / 2, y, WIDTH / 2, 16);
			g.text(font, "‹", x + 7, y + 4, alpha(overLeft ? UI_AMBER : UI_DIM, a), false);
			g.text(font, "›", x + WIDTH - 11, y + 4, alpha(overRight ? UI_AMBER : UI_DIM, a), false);

			double slide = animated() ? easeOut((now - changedNs) / 200e6) : 1;
			int offset = (int) Math.round((1 - slide) * 10 * direction);
			String value = fit(font, Planner.tr("option." + get.get()), WIDTH - 30);
			g.enableScissor(x + 14, y, x + WIDTH - 14, y + 16);
			g.centeredText(font, Component.literal(value), x + WIDTH / 2 + offset, y + 4, alpha(UI_TEXT, a * (0.3 + 0.7 * slide)));
			g.disableScissor();
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			int x = right - WIDTH;
			step(mouseX >= x && mouseX < x + WIDTH / 2 ? -1 : 1);
			return true;
		}

		private void step(int delta) {
			int index = Math.max(0, values.indexOf(get.get()));
			set.accept(values.get(Math.floorMod(index + delta, values.size())));
			direction = delta;
			changedNs = System.nanoTime();
		}
	}

	private final class NumberRow extends Row {
		private final EditBox box;
		private final double min;
		private final double max;
		private final boolean integer;
		private final boolean steppers;
		private final DoubleConsumer set;
		private final BooleanSupplier enabled;
		private boolean valid = true;

		NumberRow(String name, Item icon, double initial, double min, double max, boolean integer, DoubleConsumer set, BooleanSupplier enabled) {
			super(name, icon);
			this.min = min;
			this.max = max;
			this.integer = integer;
			this.steppers = integer && max - min <= 200;
			this.set = set;
			this.enabled = enabled;

			box = new EditBox(font, 0, 0, fieldWidth() - 10, 10, text(name));
			box.setBordered(false);
			box.setTextShadow(false);
			box.setMaxLength(12);
			box.setHint(Component.literal("—"));
			box.setValue(initial < min ? "" : Planner.decimal(initial).replace(",", ""));
			box.setResponder(this::accept);
			box.visible = false;
			addWidget(box);
		}

		private int fieldWidth() {
			return steppers ? 46 : 96;
		}

		@Override
		boolean enabled() {
			return enabled.getAsBoolean();
		}

		@Override
		int controlWidth() {
			return fieldWidth() + (steppers ? 40 : 0);
		}

		private void accept(String value) {
			if (value.isBlank()) {
				valid = true;
				return;
			}

			try {
				double parsed = Double.parseDouble(value.trim());
				valid = Double.isFinite(parsed) && parsed >= min && parsed <= max && (!integer || parsed == Math.rint(parsed));

				if (valid) {
					set.accept(parsed);
					markDirty();
					PlannerHud.rebuild();
				}
			} catch (NumberFormatException e) {
				valid = false;
			}
		}

		@Override
		void hide(boolean hidden) {
			box.visible = !hidden;
			if (hidden && box.isFocused()) setFocused(null);
		}

		@Override
		void drawControl(GuiGraphicsExtractor g, int right, int midY, double a, int mouseX, int mouseY, double dt, float delta, long now) {
			int fieldRight = right - (steppers ? 20 : 0);
			int fw = fieldWidth();
			int fx = fieldRight - fw;
			int fy = midY - 8;
			boolean focused = box.isFocused();

			round(g, fx, fy, fw, 16, 4, alpha(UI_FIELD, a));
			int line = !valid ? UI_DANGER : focused ? UI_AMBER : UI_FIELD_LINE;
			g.fill(fx + 3, fy + 15, fx + fw - 3, fy + 16, alpha(line, a));

			box.setEditable(enabled());
			box.setTextColor(alpha(valid ? UI_TEXT : UI_DANGER, a));
			box.setTextColorUneditable(alpha(UI_DIM, a));
			box.setX(fx + 5);
			box.setY(fy + 4);
			box.extractRenderState(g, mouseX, mouseY, delta);

			if (steppers) {
				stepper(g, fx - 18, fy, "−", mouseX, mouseY, a);
				stepper(g, fieldRight + 2, fy, "+", mouseX, mouseY, a);
			}
		}

		private void stepper(GuiGraphicsExtractor g, int x, int y, String sign, int mouseX, int mouseY, double a) {
			boolean over = enabled() && inside(mouseX, mouseY, x, y, 16, 16);
			round(g, x, y, 16, 16, 5, alpha(over ? 0x40FFFFFF : 0x1AFFFFFF, a));
			g.centeredText(font, Component.literal(sign), x + 8, y + 4, alpha(over ? UI_AMBER : UI_TEXT, a));
		}

		@Override
		boolean click(double mouseX, double mouseY, int right, int midY) {
			int fieldRight = right - (steppers ? 20 : 0);
			int fx = fieldRight - fieldWidth();
			int fy = midY - 8;

			if (steppers && inside(mouseX, mouseY, fx - 18, fy, 16, 16)) return step(-1);
			if (steppers && inside(mouseX, mouseY, fieldRight + 2, fy, 16, 16)) return step(1);

			setFocused(box);
			return false;
		}

		private boolean step(int delta) {
			double current;

			try {
				current = box.getValue().isBlank() ? min : Double.parseDouble(box.getValue().trim());
			} catch (NumberFormatException e) {
				current = min;
			}

			double next = Math.clamp(Math.rint(current) + delta, min, max);
			box.setValue(String.valueOf((long) next));
			return true;
		}
	}
}
