package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_ON_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_ROW;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.approach;
import static dev.foragingplanner.mc.ForestTheme.easeOutBack;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.mixArgb;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** First start: pick how much the mod shows. Every choice can be changed later in the settings. */
public final class SetupScreen extends Screen {
	private static final List<String> CHOICES = List.of("minimal", "normal", "all");
	private static final net.minecraft.world.item.Item[] ICONS = {Items.OAK_SAPLING, Items.OAK_LOG, Items.NETHER_STAR};

	private final Screen parent;
	private final double[] hover = new double[3];
	private final long openedNs = System.nanoTime();
	private long lastNs = System.nanoTime();
	private int px, py, pw, ph, cardW, cardH, cardY;

	public SetupScreen(Screen parent) {
		super(Component.literal("Foraging Planner"));
		this.parent = parent;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		extractBlurredBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		double dt = Math.min(0.1, (now - lastNs) / 1e9);
		lastNs = now;
		pw = Math.min(420, width - 20);
		ph = 210;
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		cardW = (pw - 40) / 3;
		cardH = 110;
		cardY = py + 56;

		double open = Math.clamp(easeOutBack((now - openedNs) / 380e6), 0, 1.05);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f);
		pose.scale((float) (0.95 + 0.05 * open), (float) (0.95 + 0.05 * open));
		pose.translate(-width / 2f, -height / 2f);
		shadow(g, px, py, pw, ph, 11, 1);
		round(g, px, py, pw, ph, 11, UI_PANEL);
		ForestTheme.leaves(g, px, py, pw, ph, (now - openedNs) / 1e9, 1);
		roundOutline(g, px, py, pw, ph, 11, 0x26FFFFFF);
		g.fill(px + 11, py, px + pw - 11, py + 1, alpha(UI_ACCENT, 0.35));

		var p2 = g.pose();
		p2.pushMatrix();
		p2.translate(px + 14, py + 12);
		p2.scale(1.4f, 1.4f);
		g.text(font, Planner.tr("setup.title"), 0, 0, UI_TEXT, false);
		p2.popMatrix();
		g.text(font, fit(font, Planner.tr("setup.subtitle"), pw - 28), px + 14, py + 32, UI_MUTED, false);

		for (int i = 0; i < 3; i++) {
			int x = px + 14 + i * (cardW + 6);
			boolean over = mouseX >= x && mouseX < x + cardW && mouseY >= cardY && mouseY < cardY + cardH;
			hover[i] = approach(hover[i], over ? 1 : 0, dt, 16);
			int lift = (int) Math.round(hover[i] * 2);
			round(g, x, cardY - lift, cardW, cardH, 8, mixArgb(UI_ROW, 0x30FFFFFF, hover[i]));
			if (hover[i] > 0.05) roundOutline(g, x, cardY - lift, cardW, cardH, 8, alpha(UI_ACCENT, hover[i]));
			g.item(new ItemStack(ICONS[i]), x + cardW / 2 - 8, cardY + 10 - lift);
			String name = Planner.tr("setup." + CHOICES.get(i));
			g.text(font, name, x + cardW / 2 - font.width(name) / 2, cardY + 32 - lift, i == 1 ? UI_AMBER : UI_TEXT, false);
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(Planner.tr("setup." + CHOICES.get(i) + ".hint")), cardW - 12);
			for (int l = 0; l < Math.min(5, lines.size()); l++) g.text(font, lines.get(l), x + 6, cardY + 48 + l * 10 - lift, UI_MUTED, false);
		}
		String later = Planner.tr("setup.later");
		int lw = font.width(later) + 16;
		boolean overLater = mouseX >= px + pw - 14 - lw && mouseX < px + pw - 14 && mouseY >= py + ph - 28 && mouseY < py + ph - 12;
		round(g, px + pw - 14 - lw, py + ph - 28, lw, 16, 5, overLater ? 0x30FFFFFF : UI_ROW);
		g.text(font, later, px + pw - 6 - lw, py + ph - 24, UI_MUTED, false);
		g.text(font, fit(font, Planner.tr("setup.footer"), pw - lw - 40), px + 14, py + ph - 24, UI_DIM, false);
		pose.popMatrix();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x(), my = event.y();
		for (int i = 0; i < 3; i++) {
			int x = px + 14 + i * (cardW + 6);
			if (mx >= x && mx < x + cardW && my >= cardY && my < cardY + cardH) {
				apply(CHOICES.get(i));
				finish();
				return true;
			}
		}
		String later = Planner.tr("setup.later");
		int lw = font.width(later) + 16;
		if (mx >= px + pw - 14 - lw && mx < px + pw - 14 && my >= py + ph - 28 && my < py + ph - 12) {
			finish();
			return true;
		}
		return super.mouseClicked(event, doubled);
	}

	private void finish() {
		Planner.config.setupDone = true;
		Planner.config.save();
		PlannerHud.rebuild();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		finish();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static final java.util.List<String> MODULES = java.util.List.of("trees", "upgrades", "contest", "session", "targets", "guide", "hunting", "reel", "progress", "gather", "levels", "safari");

	/**
	 * Sets the switches for one of the three choices. Minimal really is minimal: only the session
	 * and contest cards, no markers, no overlays, no sounds, no pop-ups.
	 */
	public static void apply(String choice) {
		ModConfig c = Planner.config;
		boolean minimal = choice.equals("minimal");
		boolean normal = !minimal;
		boolean all = choice.equals("all");

		c.hudEnabled = true;
		c.showRates = true;
		for (String id : MODULES) c.module(id).visible = normal || id.equals("session") || id.equals("contest");

		// Trees
		c.treeMarkers = normal;
		c.treeProgress = normal;
		c.navigator = all ? "all" : normal ? "arrow_star" : "off";
		c.treeArrow = normal;
		c.treeChoice = normal;
		c.treeRoute = all;
		c.treeSizeLabel = normal;
		c.sweepPreview = normal;
		c.precisionHelper = normal;
		c.cleanTrees = all;
		c.preventStripping = normal;
		c.ghostRoutes = normal;
		c.treeAssistant = false;
		c.helixGuide = normal;

		// HUD extras
		c.minimap = normal;
		c.levelEta = normal;
		c.gatherRates = normal;
		c.comboCounter = all;
		c.afkPause = normal;
		c.animations = normal;

		// Galatea helpers and alerts
		c.critterHighlight = normal;
		c.invisibugHelper = all;
		c.striderCounter = all;
		c.fallAlert = normal;
		c.inventoryWarning = normal;
		c.beaconAlert = normal;
		c.beaconSolver = normal;
		c.hotfCosts = normal;
		c.whisperReminder = normal;
		c.pauseReminder = all;
		c.contestSound = normal;
		c.contestTitle = normal;
		c.contestCountdown = normal;
		c.contestCountdownSound = normal;
		c.toastSound = normal;
		c.toastChat = false;
		c.toasts = normal;

		// Chat and sounds
		c.compactGifts = all;
		c.compactSweep = all;
		c.compactContest = all;
		c.hideLottery = normal;
		c.mutePhantoms = all;
		c.muteTreeBreak = false;
		c.muteMobs = normal;
		c.protectedTrees = normal;
		c.regrowWarning = normal;
		c.milestones = normal;
		c.attributeOverlay = normal;
		c.vendorTracker = normal;

		// Critter Safari
		c.safariTracker = normal;
		c.safariPrices = normal;
		c.sparklingAlert = normal;
		c.critterdexHelper = normal;
		c.safariMilestones = all;
		c.ticketReminder = normal;
		c.safariGroundItems = normal;
		c.honeyHelix = normal;
		c.partyProgress = all;
		c.safariBells = normal;
		c.bellRoute = normal;
		c.dropProtection = true;
		c.huntrapProtection = true;
	}
}
