package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_DARK;
import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT_SOFT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
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
import static dev.foragingplanner.mc.ForestTheme.mixArgb;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shared base for the mod's large screens: glass panel with drifting leaves, animated sidebar,
 * page transitions that slide in the direction of the new page, rows that rise in one after the
 * other, hover lift, click ripples, counting numbers, smooth scrolling and a fade-out on close.
 *
 * <p>A page is a list of {@link Line}s built by {@link #build(int)}; the helpers below make the
 * usual kinds of rows. Pages are rebuilt once a second so that live values stay current.
 */
public abstract class ForestScreen extends Screen {
	protected static final int RADIUS = 11;
	private static final int NAV_HEIGHT = 22;
	private static final int NAV_STEP = 25;
	private static final int GAP = 5;
	private static final long CLOSE_NS = 170_000_000L;

	/** Draws a row: graphics, x, y, width, opacity, hover 0..1. */
	public interface Drawer {
		void draw(GuiGraphicsExtractor g, int x, int y, int w, double a, double hover);
	}

	/** Mouse x inside the row, row width, mouse button. */
	public interface Clicker {
		void click(double localX, int w, int button);
	}

	/** One row of a page. {@code field} is an optional text box placed inside the row. */
	public record Line(int height, Drawer drawer, Clicker click, EditBox field, String key) {
		public Line(int height, Drawer drawer, Clicker click) {
			this(height, drawer, click, null, null);
		}
	}

	private record Ripple(int line, double x, double y, long startNs) {}

	private record Leaf(double x, double y, double speed, double sway, double size, double phase) {}

	protected final Screen parent;
	private final String[] pages;
	private final Item[] pageIcons;
	private final long openedNs = System.nanoTime();
	private final double[] navHover;
	private final Map<String, ItemStack> icons = new HashMap<>();
	private final Map<String, Double> hover = new HashMap<>();
	private final Map<String, Double> counters = new HashMap<>();
	private final Map<String, Double> switches = new HashMap<>();
	private final List<Ripple> ripples = new ArrayList<>();
	private final List<Leaf> leaves = new ArrayList<>();

	protected int page;
	protected int panelX, panelY, panelW, panelH, sideW, contentX, contentW, viewTop, viewBottom;
	private int doneX, doneY, doneW, direction = 1;
	private long pageNs = openedNs, lastFrameNs = openedNs, builtNs, closingNs;
	private double pill, scroll, scrollTarget, doneHover, underline;
	protected List<Line> lines = List.of();
	private EditBox searchBox;
	private String query = "";
	protected String subtitle = "";

	protected ForestScreen(Screen parent, String[] pages, Item[] pageIcons, int page) {
		super(Component.literal("Foraging Planner"));
		this.parent = parent;
		this.pages = pages;
		this.pageIcons = pageIcons;
		this.navHover = new double[pages.length];
		this.page = Math.clamp(page, 0, pages.length - 1);
	}

	/** Builds the rows of a page. Called when the page opens and then once a second. */
	protected abstract List<Line> build(int page);

	/** Title shown above the sidebar. */
	protected abstract String title();

	/** Small text under the title. */
	protected abstract String tagline();

	/** Name of a page in the sidebar and as headline. */
	protected abstract String pageName(int page);

	/** Item shown as the logo. */
	protected Item logo() {
		return Items.OAK_SAPLING;
	}

	/** Text left of the "Done" button. */
	protected String footerHint() {
		return "";
	}

	/** How often the page is rebuilt; pages with text boxes should not rebuild while typing. */
	protected long rebuildNs() {
		return 1_000_000_000L;
	}

	/** When the current page was opened, for page animations in subclasses. */
	protected long pageStartNs() {
		return pageNs;
	}

	protected static boolean animated() {
		return Planner.config.animations;
	}

	protected ItemStack icon(String id) {
		return icons.computeIfAbsent(id, key -> new ItemStack(BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(key)).orElse(Items.PAPER)));
	}

	@Override
	protected void init() {
		panelW = Math.min(800, width - 20);
		panelH = Math.min(420, height - 20);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		sideW = panelW < 480 ? 44 : 140;
		contentX = panelX + sideW + 16;
		contentW = panelW - sideW - 32;
		viewTop = panelY + 54;
		viewBottom = panelY + panelH - 38;
		pill = page;
		leaves.clear();
		java.util.Random random = new java.util.Random(7);
		for (int i = 0; i < 14; i++) {
			leaves.add(new Leaf(random.nextDouble(), random.nextDouble(), 0.015 + random.nextDouble() * 0.025,
					6 + random.nextDouble() * 12, 2 + random.nextInt(3), random.nextDouble() * Math.PI * 2));
		}
		rebuild();
	}

	/** Rebuilds the current page now, keeping the scroll position. */
	protected void rebuild() {
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
				rebuild();
			});
		}
		addWidget(searchBox);
		lines = query.isEmpty() ? build(page) : search();
		for (Line line : lines) if (line.field() != null) addWidget(line.field());
		builtNs = System.nanoTime();
		scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
	}

	protected void openPage(int target) {
		if (target == page || target < 0 || target >= pages.length) return;
		direction = target > page ? 1 : -1;
		page = target;
		pageNs = System.nanoTime();
		scroll = scrollTarget = 0;
		hover.clear();
		ripples.clear();
		setFocused(null);
		rebuild();
		UiSound.page();
	}

	protected void click() {
		UiSound.click();
	}

	/** When a tick was first shown, so it pops in once. */
	private final Map<String, Long> ticks = new HashMap<>();
	private String hoverSoundKey = "";

	/** The right-hand text of a row, counted up and with a popping tick. */
	private void drawRight(GuiGraphicsExtractor g, String right, int rx, int y, int color, double a, String key) {
		String text = Motion.countUp(right, pageNs);
		if (right.startsWith("✔") && Motion.checks()) {
			long since = ticks.computeIfAbsent(key, k -> Math.max(System.nanoTime(), pageNs + 120_000_000L));
			double s = Motion.pop(since);
			if (s <= 0.01) return;
			var pose = g.pose();
			pose.pushMatrix();
			float cx = rx + font.width(right) / 2f, cy = y + 4;
			pose.translate(cx, cy);
			pose.scale((float) s, (float) s);
			pose.translate(-cx, -cy);
			g.text(font, text, rx, y, alpha(color, a));
			pose.popMatrix();
			return;
		}
		g.text(font, text, rx, y, alpha(color, a));
	}

	// Row helpers

	protected Line header(String text) {
		return new Line(18, (g, x, y, w, a, h) -> {
			g.text(font, text, x + 2, y + 6, alpha(UI_AMBER, a), false);
			int tw = font.width(text);
			g.fill(x + tw + 8, y + 10, x + w - 2, y + 11, alpha(0x18FFFFFF, a));
		}, null, null, HEADER_KEY);
	}

	/** Marks header lines, so that search results can keep the section they belong to. */
	private static final String HEADER_KEY = "\u0000header";

	private static String searchable(String... parts) {
		StringBuilder b = new StringBuilder();
		for (String part : parts) if (part != null) b.append(Planner.strip(part)).append(' ');
		return b.toString().toLowerCase(java.util.Locale.ROOT);
	}

	protected Line text(String text, int color) {
		return withKey(plainText(text, color), searchable(text));
	}

	private static Line withKey(Line line, String key) {
		return new Line(line.height(), line.drawer(), line.click(), line.field(), key);
	}

	private Line plainText(String text, int color) {
		List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(Component.literal(text), Math.max(40, contentW - 6));
		int h = Math.max(12, wrapped.size() * 10 + 2);
		return new Line(h, (g, x, y, w, a, hv) -> {
			for (int i = 0; i < wrapped.size(); i++) g.text(font, wrapped.get(i), x + 2, y + 2 + i * 10, alpha(color, a), false);
		}, null);
	}

	protected Line spacer(int height) {
		return new Line(height, (g, x, y, w, a, h) -> { }, null);
	}

	protected void tile(GuiGraphicsExtractor g, String icon, int x, int y, double a) {
		round(g, x, y, 22, 22, 6, alpha(UI_TILE, a));
		if (a > 0.45) g.item(icon(icon), x + 3, y + 3);
	}

	protected void card(GuiGraphicsExtractor g, int x, int y, int w, int h, double a, double hover) {
		int lift = (int) Math.round(hover * 1.5);
		if (hover > 0.01) round(g, x - 1, y - 1 - lift, w + 2, h + 2, 8, alpha(UI_ACCENT, 0.12 * hover * a));
		round(g, x, y - lift, w, h, 7, alpha(mixArgb(UI_ROW, UI_ROW_HOVER, hover), a));
		g.fill(x + 1, y - lift, x + 3, y + h - lift, alpha(UI_ACCENT, hover * 0.8 * a));
	}

	protected Line row(String icon, String titleText, String sub, String right, int rightColor, Clicker click) {
		String title = titleText == null ? "" : titleText;
		return new Line(32, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 32, a, h);
			int lift = (int) Math.round(h * 1.5);
			int tx = x + 9;
			if (icon != null) {
				tile(g, icon, x + 6, y + 5 - lift, a);
				tx = x + 35;
			}
			int rw = right == null ? 0 : font.width(right) + 14;
			g.text(font, fit(font, title, w - (tx - x) - rw - 4), tx, y + (sub == null ? 12 : 6) - lift, alpha(UI_TEXT, a));
			if (sub != null) g.text(font, fit(font, sub, w - (tx - x) - rw - 4), tx, y + 18 - lift, alpha(UI_MUTED, a), false);
			if (right != null) drawRight(g, right, x + w - 9 - font.width(right), y + 12 - lift, rightColor, a, page + ":" + title);
		}, click, null, searchable(title, sub, right));
	}

	protected Line bar(String icon, String titleText, String rightText, double fraction, int color, Clicker click) {
		// Other mods hook Font.width and break on null, so missing texts become empty ones.
		String title = titleText == null ? "" : titleText;
		String right = rightText == null ? "" : rightText;
		String key = "bar:" + title;
		return new Line(32, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 32, a, h);
			int lift = (int) Math.round(h * 1.5);
			tile(g, icon, x + 6, y + 5 - lift, a);
			int bx = x + 35, bw = w - 44;
			g.text(font, fit(font, title, bw - font.width(right) - 8), bx, y + 6 - lift, alpha(UI_TEXT, a));
			String shownRight = Motion.countUp(right, pageNs);
			g.text(font, shownRight, x + w - 9 - font.width(right), y + 6 - lift, alpha(fraction >= 1 ? UI_ACCENT : UI_MUTED, a), false);
			double shown = Motion.bars() ? counter(key, Math.clamp(fraction, 0, 1), 5) : Math.clamp(fraction, 0, 1);
			round(g, bx, y + 20 - lift, bw, 5, 2, alpha(0x1FFFFFFF, a));
			int fill = (int) Math.round(bw * shown);
			if (fill > 0) {
				round(g, bx, y + 20 - lift, fill, 5, 2, alpha(shown >= 0.999 ? UI_ACCENT : color, a));
				// A soft light that runs along the filled part.
				if (Motion.bars() && fill > 12) {
					int glow = bx + (int) ((System.nanoTime() / 6e6) % (fill + 30)) - 15;
					for (int i = 0; i < 12; i++) {
						int gx = glow + i;
						if (gx > bx && gx < bx + fill) g.fill(gx, y + 20 - lift, gx + 1, y + 25 - lift, alpha(0xFFFFFFFF, a * 0.18 * (1 - Math.abs(i - 6) / 6.0)));
					}
				}
			}
		}, click, null, searchable(title, right));
	}

	/** Pill buttons in one row. */
	protected Line buttons(String[] labels, Runnable[] actions) {
		int[] widths = new int[labels.length];
		for (int i = 0; i < labels.length; i++) widths[i] = font.width(labels[i]) + 22;
		return new Line(24, (g, x, y, w, a, h) -> {
			int bx = x;
			for (int i = 0; i < labels.length; i++) {
				boolean over = h > 0.01 && mouseXInContent >= bx - x && mouseXInContent < bx - x + widths[i];
				double hv = over ? h : 0;
				int lift = (int) Math.round(hv * 1.5);
				round(g, bx, y + 2 - lift, widths[i], 19, 7, alpha(mixArgb(0xFF24412D, UI_ACCENT_DARK | 0xFF000000, hv), a));
				roundOutline(g, bx, y + 2 - lift, widths[i], 19, 7, alpha(UI_ACCENT, (0.25 + 0.5 * hv) * a));
				g.text(font, labels[i], bx + 11, y + 8 - lift, alpha(mixArgb(UI_TEXT, 0xFFFFFFFF, hv), a), false);
				bx += widths[i] + 6;
			}
		}, (lx, w, button) -> {
			int bx = 0;
			for (int i = 0; i < labels.length; i++) {
				if (lx >= bx && lx < bx + widths[i]) {
					actions[i].run();
					return;
				}
				bx += widths[i] + 6;
			}
		}, null, searchable(labels));
	}

	protected Line button(String label, Runnable action) {
		return buttons(new String[] {label}, new Runnable[] {action});
	}

	/** A switch row with an animated knob. */
	protected Line toggle(String icon, String title, String sub, BooleanSupplier get, Consumer<Boolean> set) {
		String key = "switch:" + title;
		return new Line(32, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 32, a, h);
			int lift = (int) Math.round(h * 1.5);
			tile(g, icon, x + 6, y + 5 - lift, a);
			g.text(font, fit(font, title, w - 90), x + 35, y + (sub == null ? 12 : 6) - lift, alpha(UI_TEXT, a));
			if (sub != null) g.text(font, fit(font, sub, w - 90), x + 35, y + 18 - lift, alpha(UI_MUTED, a), false);
			double on = switches.compute(key, (k, v) -> {
				double target = get.getAsBoolean() ? 1 : 0;
				return animated() ? approach(v == null ? target : v, target, 0.016, 18) : target;
			});
			int sx = x + w - 40, sy = y + 10 - lift;
			round(g, sx, sy, 28, 13, 6, alpha(mixArgb(UI_SWITCH_OFF, UI_ACCENT, on), a));
			int kx = sx + 2 + (int) Math.round(on * 15);
			double knobPop = on > 0.5 && Motion.checks() ? 1 + 0.25 * Math.sin(Math.clamp((on - 0.5) * 2, 0, 1) * Math.PI) : 1;
			int ks = (int) Math.round(9 * knobPop);
			round(g, kx + (9 - ks) / 2, sy + 2 + (9 - ks) / 2, ks, ks, ks / 2, alpha(0xFFFFFFFF, a));
			if (on > 0.6) g.text(font, "✓", sx + 4, sy + 3, alpha(0xFFFFFFFF, a * (on - 0.6) / 0.4), false);
		}, (lx, w, button) -> {
			boolean next = !get.getAsBoolean();
			set.accept(next);
			UiSound.toggle(next);
		}, null, searchable(title, sub));
	}

	/** Tiles with big numbers that count up when they change. */
	protected Line tiles(String[] icons, String[] labels, DoubleSupplier[] values, Supplier<String>[] subs, java.util.function.DoubleFunction<String> format) {
		int cols = contentW >= 460 ? 3 : 2;
		int rows = (labels.length + cols - 1) / cols;
		int th = 52;
		return new Line(rows * (th + 6) - 6, (g, x, y, w, a, h) -> {
			int cw = (w - (cols - 1) * 6) / cols;
			for (int i = 0; i < labels.length; i++) {
				int tx = x + i % cols * (cw + 6), ty = y + i / cols * (th + 6);
				round(g, tx, ty, cw, th, 8, alpha(UI_ROW, a));
				roundOutline(g, tx, ty, cw, th, 8, alpha(0x14FFFFFF, a));
				tile(g, icons[i], tx + 7, ty + 7, a);
				g.text(font, fit(font, labels[i], cw - 40), tx + 34, ty + 9, alpha(UI_MUTED, a), false);
				double shown = counter("tile:" + labels[i], values[i].getAsDouble(), 7);
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(tx + 8, ty + 25);
				pose.scale(1.35f, 1.35f);
				g.text(font, fit(font, format.apply(shown), (int) ((cw - 16) / 1.35)), 0, 0, alpha(UI_TEXT, a), false);
				pose.popMatrix();
				String sub = subs[i].get();
				if (sub != null) g.text(font, fit(font, sub, cw - 16), tx + 8, ty + 40, alpha(UI_ACCENT, a), false);
			}
		}, null);
	}

	/** A text box with a label above it. */
	protected Line field(String label, String value, int maxLength, Consumer<String> change) {
		EditBox box = new EditBox(font, 0, 0, 100, 18, Component.literal(label));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setResponder(change);
		box.setBordered(false);
		box.setTextColor(UI_TEXT);
		return new Line(38, (g, x, y, w, a, h) -> {
			g.text(font, label, x + 2, y + 2, alpha(UI_MUTED, a), false);
			boolean focused = getFocused() == box;
			round(g, x, y + 13, w, 22, 6, alpha(focused ? 0xFF1C3324 : UI_ROW, a));
			roundOutline(g, x, y + 13, w, 22, 6, alpha(focused ? UI_ACCENT : 0x22FFFFFF, a));
			box.setX(x + 7);
			box.setY(y + 20);
			box.setWidth(w - 14);
			box.visible = a > 0.3;
			box.extractRenderState(g, -1, -1, 0);
		}, null, box, label);
	}

	/** Rows of every page that contain the search text, under the name of their page and section. */
	private List<Line> search() {
		String q = query.toLowerCase(java.util.Locale.ROOT);
		List<Line> out = new ArrayList<>();
		int found = 0;
		for (int p = 0; p < pages.length; p++) {
			List<Line> pageLines = build(p);
			List<Line> hits = new ArrayList<>();
			Line section = null;
			Line lastSection = null;
			for (Line line : pageLines) {
				if (HEADER_KEY.equals(line.key())) {
					section = line;
					continue;
				}
				if (line.key() == null || line.field() != null || !line.key().contains(q)) continue;
				if (section != null && section != lastSection) {
					hits.add(section);
					lastSection = section;
				}
				hits.add(line);
				found++;
			}
			if (hits.isEmpty()) continue;
			int target = p;
			out.add(new Line(20, (g, x, y, w, a, h) -> {
				g.item(new ItemStack(pageIcons[target]), x, y + 2);
				g.text(font, pageName(target), x + 20, y + 6, alpha(UI_TEXT, a), true);
				String go = Planner.tr("search.open_page");
				g.text(font, go, x + w - font.width(go) - 4, y + 6, alpha(mixArgb(UI_MUTED, UI_ACCENT, h), a), false);
			}, (lx, w, button) -> {
				searchBox.setValue("");
				openPage(target);
			}));
			out.addAll(hits);
		}
		subtitle = found == 0 ? Planner.tr("search.none", query) : Planner.tr("search.results", found, query);
		return out;
	}

	/** Smoothly moves a shown number toward its real value. */
	protected double counter(String key, double target, double speed) {
		if (!animated()) return target;
		return counters.compute(key, (k, v) -> {
			if (v == null) return target * (Math.abs(target) > 1 ? 0 : 1);
			double next = approach(v, target, 0.016, speed);
			return Math.abs(next - target) < Math.max(0.0005, Math.abs(target) * 0.0005) ? target : next;
		});
	}

	// Layout

	private int[] offsets() {
		int[] y = new int[lines.size() + 1];
		for (int i = 0; i < lines.size(); i++) y[i + 1] = y[i] + lines.get(i).height() + GAP;
		return y;
	}

	protected int maxScroll() {
		int[] y = offsets();
		return Math.max(0, y[lines.size()] - GAP - (viewBottom - viewTop));
	}

	private double mouseXInContent;
	/** Mouse position of the current frame, for tooltips in subclasses. */
	protected int mouseX, mouseY;

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

		if (closingNs != 0 && now - closingNs >= CLOSE_NS) {
			finishClose();
			return;
		}
		if (getFocused() == null && now - builtNs > rebuildNs()) rebuild();

		double open = animated() ? easeOut((now - openedNs) / 300e6) : 1;
		if (closingNs != 0) open *= 1 - easeOut((now - closingNs) / (double) CLOSE_NS);
		mouseXInContent = mouseX - contentX;
		this.mouseX = mouseX;
		this.mouseY = mouseY;

		g.fillGradient(0, 0, width, height, alpha(0xFF06100C, 0.72 * open), alpha(0xFF020604, 0.9 * open));
		float scale = (float) (animated() ? (closingNs != 0 ? 0.97 + 0.03 * open : 0.955 + 0.045 * easeOutBack((now - openedNs) / 380e6)) : 1);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, height / 2f);
		pose.scale(scale, scale);
		pose.translate(-width / 2f, -height / 2f);

		shadow(g, panelX, panelY, panelW, panelH, RADIUS, open);
		round(g, panelX, panelY, panelW, panelH, RADIUS, alpha(UI_PANEL, open));
		drawLeaves(g, now, open);
		round(g, panelX + 4, panelY + 4, sideW - 4, panelH - 8, RADIUS - 3, alpha(UI_SIDEBAR, open * 0.95));
		roundOutline(g, panelX, panelY, panelW, panelH, RADIUS, alpha(0x26FFFFFF, open));
		// A thin accent line along the top edge.
		g.fill(panelX + RADIUS, panelY, panelX + panelW - RADIUS, panelY + 1, alpha(UI_ACCENT, 0.35 * open));

		drawSidebar(g, mouseX, mouseY, dt, open);
		drawHeader(g, now, dt, open);
		drawLines(g, mouseX, mouseY, dt, now, open);
		drawFooter(g, mouseX, mouseY, dt, open);
		drawOverlay(g, mouseX, mouseY, open);
		pose.popMatrix();
		Why.draw(g, font, width, height);
	}

	/** Extra drawing on top of everything, for subclasses. */
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, double open) {}

	private void drawLeaves(GuiGraphicsExtractor g, long now, double open) {
		if (!animated()) return;
		double t = (now - openedNs) / 1e9;
		g.enableScissor(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + panelH - 2);
		for (Leaf leaf : leaves) {
			double fy = (leaf.y() + t * leaf.speed()) % 1.0;
			double fx = leaf.x() + Math.sin(t * 0.8 + leaf.phase()) * leaf.sway() / panelW;
			int x = panelX + (int) (fx * panelW);
			int y = panelY + (int) (fy * panelH);
			double fade = Math.sin(fy * Math.PI);
			int size = (int) leaf.size();
			int color = alpha(leaf.phase() > Math.PI ? UI_ACCENT : UI_AMBER, 0.07 * fade * open);
			round(g, x, y, size + 1, size, 1, color);
		}
		g.disableScissor();
	}

	private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int x = panelX + 4;
		int w = sideW - 4;
		boolean compact = sideW < 100;
		int logoX = compact ? x + (w - 22) / 2 : x + 9;
		double pulse = animated() ? 0.5 + 0.5 * Math.sin((System.nanoTime() - openedNs) / 1e9 * 2) : 0.5;
		round(g, logoX - 1, panelY + 12, 24, 24, 7, alpha(UI_ACCENT, 0.15 * pulse * open));
		round(g, logoX, panelY + 13, 22, 22, 6, alpha(UI_ACCENT_DARK, open));
		g.item(new ItemStack(logo()), logoX + 3, panelY + 16);
		if (!compact) {
			g.text(font, fit(font, title(), w - 42), x + 37, panelY + 14, alpha(UI_TEXT, open), false);
			g.text(font, fit(font, tagline(), w - 42), x + 37, panelY + 25, alpha(UI_ACCENT, open), false);
		}

		int navTop = panelY + 48;
		pill = animated() ? approach(pill, page, dt, 16) : page;
		int pillY = navTop + (int) Math.round(pill * NAV_STEP);
		round(g, x + 6, pillY, w - 12, NAV_HEIGHT, 7, alpha(UI_ACCENT_SOFT, open));
		g.fill(x + 6, pillY + 6, x + 8, pillY + NAV_HEIGHT - 6, alpha(UI_AMBER, open));

		for (int i = 0; i < pages.length; i++) {
			int itemY = navTop + i * NAV_STEP;
			if (itemY + NAV_HEIGHT > panelY + panelH - 8) break;
			boolean hovered = inside(mouseX, mouseY, x + 6, itemY, w - 12, NAV_HEIGHT) && i != page;
			navHover[i] = animated() ? approach(navHover[i], hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
			if (navHover[i] > 0.01) round(g, x + 6, itemY, w - 12, NAV_HEIGHT, 7, alpha(0x12FFFFFF, navHover[i] * open));
			int iconX = compact ? x + (w - 16) / 2 : x + 13 + (int) Math.round(navHover[i] * 3);
			g.item(new ItemStack(pageIcons[i]), iconX, itemY + 3);
			if (!compact) {
				int color = i == page ? UI_TEXT : mixArgb(UI_MUTED, UI_TEXT, navHover[i]);
				g.text(font, fit(font, pageName(i), w - 46), iconX + 21, itemY + 7, alpha(color, open), false);
			}
		}
	}

	private void drawHeader(GuiGraphicsExtractor g, long now, double dt, double open) {
		double appear = Motion.pages() ? easeOut((now - pageNs) / 260e6) : 1;
		int shift = (int) Math.round((1 - appear) * 14 * direction);
		double a = open * appear;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(contentX + shift, panelY + 13);
		pose.scale(1.45f, 1.45f);
		String name = query.isEmpty() ? pageName(page) : Planner.tr("search.title");
		g.text(font, name, 0, 0, alpha(UI_TEXT, a), false);
		pose.popMatrix();
		int target = (int) (font.width(name) * 1.45);
		underline = animated() ? approach(underline, target * appear, dt, 10) : target;
		round(g, contentX + shift, panelY + 28, (int) underline, 2, 1, alpha(UI_ACCENT, a));
		g.text(font, fit(font, subtitle, contentW), contentX + shift, panelY + 35, alpha(UI_MUTED, a), false);
		drawSearch(g, open);
	}

	private void drawSearch(GuiGraphicsExtractor g, double open) {
		int w = Math.min(150, contentW / 2);
		int x = contentX + contentW - w;
		int y = panelY + 11;
		boolean focused = getFocused() == searchBox;
		round(g, x, y, w, 18, 6, alpha(focused ? 0xFF1C3324 : UI_ROW, open));
		roundOutline(g, x, y, w, 18, 6, alpha(focused ? UI_ACCENT : 0x22FFFFFF, open));
		g.text(font, "⌕", x + 6, y + 5, alpha(focused ? UI_ACCENT : UI_MUTED, open), false);
		searchBox.setX(x + 16);
		searchBox.setY(y + 5);
		searchBox.setWidth(w - 22);
		searchBox.extractRenderState(g, -1, -1, 0);
	}

	private void drawLines(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, long now, double open) {
		scroll = animated() ? approach(scroll, scrollTarget, dt, 16) : scrollTarget;
		int[] offsets = offsets();
		g.enableScissor(contentX - 6, viewTop, contentX + contentW + 12, viewBottom);

		for (int i = 0; i < lines.size(); i++) {
			Line line = lines.get(i);
			int y = viewTop + offsets[i] - (int) Math.round(scroll);
			int h = line.height();
			if (y + h <= viewTop || y >= viewBottom) {
				if (line.field() != null) line.field().visible = false;
				continue;
			}

			double appear = Motion.pages() ? easeOut((now - pageNs - Math.min(i, 16) * 28e6) / 280e6) : 1;
			double edge = Math.clamp(Math.min((y + h - viewTop) / 14.0, (viewBottom - y) / 14.0), 0.0, 1.0);
			double a = open * appear * edge;
			if (a <= 0.01) continue;

			String key = page + ":" + i;
			boolean hovered = line.click() != null && closingNs == 0
					&& inside(mouseX, mouseY, contentX, Math.max(y, viewTop), contentW, Math.min(y + h, viewBottom) - Math.max(y, viewTop));
			if (hovered && !key.equals(hoverSoundKey)) {
				hoverSoundKey = key;
				UiSound.hover();
			} else if (!hovered && key.equals(hoverSoundKey)) {
				hoverSoundKey = "";
			}
			double hv = animated() ? approach(hover.getOrDefault(key, 0.0), hovered ? 1 : 0, dt, 18) : hovered ? 1 : 0;
			hover.put(key, hv);
			int x = contentX + (int) Math.round((1 - appear) * 18 * direction);
			line.drawer().draw(g, x, y + (int) Math.round((1 - appear) * 6), contentW, a, hv);

			for (Ripple ripple : ripples) {
				if (ripple.line() != i) continue;
				double t = (now - ripple.startNs()) / 420e6;
				if (t >= 1) continue;
				int r = (int) (6 + t * 60);
				g.enableScissor(contentX, Math.max(y, viewTop), contentX + contentW, Math.min(y + h, viewBottom));
				round(g, (int) ripple.x() - r, (int) ripple.y() - r, r * 2, r * 2, r, alpha(0xFFFFFFFF, 0.12 * (1 - t) * a));
				g.disableScissor();
				g.enableScissor(contentX - 6, viewTop, contentX + contentW + 12, viewBottom);
			}
		}

		ripples.removeIf(r -> now - r.startNs() > 420e6);
		g.disableScissor();

		int max = maxScroll();
		if (max > 0) {
			int track = viewBottom - viewTop;
			int thumb = Math.max(22, track * track / (track + max));
			int ty = viewTop + (int) Math.round((track - thumb) * scroll / max);
			round(g, contentX + contentW + 6, viewTop, 3, track, 1, alpha(0x14FFFFFF, open));
			round(g, contentX + contentW + 6, ty, 3, thumb, 1, alpha(UI_ACCENT, open));
		}
	}

	private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY, double dt, double open) {
		int y = panelY + panelH - 28;
		g.fill(contentX, y - 7, contentX + contentW, y - 6, alpha(0x14FFFFFF, open));
		String done = Planner.tr("settings.done");
		doneW = Math.max(70, font.width(done) + 28);
		doneX = panelX + panelW - 16 - doneW;
		doneY = y - 1;
		g.text(font, fit(font, footerHint(), doneX - contentX - 10), contentX, y + 4, alpha(UI_DIM, open), false);
		boolean hovered = inside(mouseX, mouseY, doneX, doneY, doneW, 19);
		doneHover = animated() ? approach(doneHover, hovered ? 1 : 0, dt, 16) : hovered ? 1 : 0;
		int lift = (int) Math.round(doneHover * 1.5);
		if (doneHover > 0.01) round(g, doneX - 2, doneY - 2 - lift, doneW + 4, 23, 8, alpha(UI_ACCENT, 0.2 * doneHover * open));
		round(g, doneX, doneY - lift, doneW, 19, 7, alpha(mixArgb(0xFF6FA35B, UI_ACCENT, doneHover), open));
		g.centeredText(font, Component.literal(done), doneX + doneW / 2, doneY + 6 - lift, alpha(UI_ON_ACCENT, open));
	}

	protected static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (closingNs != 0) return true;
		if (Why.isOpen()) {
			Why.close();
			return true;
		}
		double mx = event.x(), my = event.y();
		int button = event.button();

		if (button == InputConstants.MOUSE_BUTTON_LEFT && inside(mx, my, doneX, doneY - 2, doneW, 21)) {
			click();
			onClose();
			return true;
		}

		if (searchBox != null && inside(mx, my, searchBox.getX() - 16, searchBox.getY() - 5, searchBox.getWidth() + 22, 18)) {
			setFocused(searchBox);
			searchBox.setFocused(true);
			return super.mouseClicked(event, doubled);
		}

		int navTop = panelY + 48;
		for (int i = 0; i < pages.length; i++) {
			if (inside(mx, my, panelX + 10, navTop + i * NAV_STEP, sideW - 12, NAV_HEIGHT)) {
				if (searchBox != null) searchBox.setValue("");
				openPage(i);
				return true;
			}
		}

		if (inside(mx, my, contentX, viewTop, contentW, viewBottom - viewTop)) {
			int[] offsets = offsets();
			for (int i = 0; i < lines.size(); i++) {
				int y = viewTop + offsets[i] - (int) Math.round(scroll);
				Line line = lines.get(i);
				if (my < y || my >= y + line.height()) continue;

				if (line.field() != null) {
					setFocused(line.field());
					line.field().setFocused(true);
					return super.mouseClicked(event, doubled);
				}
				if (line.click() == null) break;
				setFocused(null);
				ripples.add(new Ripple(i, mx, my, System.nanoTime()));
				long before = UiSound.stamp();
				line.click().click(mx - contentX, contentW, button);
				// A row that made its own sound (a switch, a success) needs no click on top.
				if (UiSound.stamp() == before) click();
				rebuild();
				return true;
			}
		}

		setFocused(null);
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		scrollTarget = Math.clamp(scrollTarget - vertical * 32, 0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		boolean typing = getFocused() instanceof EditBox;
		if (Why.isOpen() && key == InputConstants.KEY_ESCAPE) {
			Why.close();
			return true;
		}

		if (event.hasControlDown() && key == InputConstants.KEY_F && searchBox != null) {
			setFocused(searchBox);
			searchBox.setFocused(true);
			return true;
		}
		if (getFocused() == searchBox && key == InputConstants.KEY_ESCAPE && !searchBox.getValue().isEmpty()) {
			searchBox.setValue("");
			return true;
		}
		if (typing && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_ESCAPE)) {
			setFocused(null);
			return true;
		}
		if (!typing) {
			if (key == InputConstants.KEY_0 && pages.length >= 10) { openPage(9); return true; }
			if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9 && key - InputConstants.KEY_1 < pages.length) {
				openPage(key - InputConstants.KEY_1);
				return true;
			}
			if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
				scrollTarget = Math.clamp(scrollTarget + (key == InputConstants.KEY_DOWN ? 36 : -36), 0, maxScroll());
				return true;
			}
			if (key == InputConstants.KEY_PAGEDOWN || key == InputConstants.KEY_PAGEUP) {
				scrollTarget = Math.clamp(scrollTarget + (key == InputConstants.KEY_PAGEDOWN ? 1 : -1) * (viewBottom - viewTop - 30), 0, maxScroll());
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (!animated()) {
			finishClose();
			return;
		}
		if (closingNs == 0) closingNs = System.nanoTime();
	}

	private void finishClose() {
		closingNs = 0;
		Why.close();
		closed();
		minecraft.gui.setScreen(parent);
	}

	/** Called once when the screen really closes, to save. */
	protected void closed() {}
}
