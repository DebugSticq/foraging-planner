package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.mixArgb;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.HotfPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * A custom look for Hypixel's /hotf menu. The real menu stays open underneath: every click on a
 * card is sent to the matching slot, so buying and unlocking work exactly as before. The perks keep
 * Hypixel's own layout, drawn as cards with levels, the planner's ranking and a detail panel.
 * Holding Alt shows the original menu.
 */
public final class HotfGui {
	private enum Kind { PERK, INFO, PANE, BUTTON }

	private record Cell(Slot slot, ItemStack stack, Kind kind, int col, int row, GameData.Perk perk) {}

	private static final Map<Integer, Double> HOVER = new HashMap<>();
	private static Map<String, Integer> ranks = Map.of();
	private static Map<String, Double> gains = Map.of();
	private static long ranksAt, lastNs, openedNs;
	private static AbstractContainerScreen<?> current;
	private static boolean original;

	// Layout of the last frame, for clicks.
	private static int gridX, gridY, cell, panelX, panelY, panelW, panelH, toggleX, toggleY, toggleW;

	private HotfGui() {}

	/** True when this menu should be drawn by the mod instead of the game. */
	public static boolean active(AbstractContainerScreen<?> screen) {
		if (!Planner.config.customHotf) return false;
		if (!Planner.strip(screen.getTitle().getString()).trim().equalsIgnoreCase(Planner.data.hotf.menuTitle)) return false;
		if (current != screen) {
			current = screen;
			original = false;
			openedNs = System.nanoTime();
			HOVER.clear();
		}
		if (original) return false;
		return !InputConstants.isKeyDown(InputConstants.KEY_LALT);
	}

	/** Lets the "show original" button work from the original menu too. */
	public static void showCustomAgain() {
		original = false;
	}

	public static boolean showingOriginal(AbstractContainerScreen<?> screen) {
		return current == screen && original && Planner.config.customHotf;
	}

	// Reading the menu

	private static List<Cell> cells(AbstractContainerScreen<?> screen) {
		List<Cell> out = new ArrayList<>();
		var player = Minecraft.getInstance().player;
		for (Slot slot : screen.getMenu().slots) {
			if (player != null && slot.container == player.getInventory()) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = Planner.strip(stack.getHoverName().getString()).trim();
			String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			GameData.Perk perk = Planner.data.perk(name);
			Kind kind = perk != null ? Kind.PERK
					: name.equalsIgnoreCase(Planner.data.hotf.menuTitle) ? Kind.INFO
					: item.contains("glass_pane") && name.isBlank() || item.contains("glass_pane") && lore(stack).isEmpty() ? Kind.PANE
					: Kind.BUTTON;
			out.add(new Cell(slot, stack, kind, slot.index % 9, slot.index / 9, perk));
		}
		return out;
	}

	private static List<Component> lore(ItemStack stack) {
		return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines();
	}

	private static void refreshRanks() {
		long now = System.currentTimeMillis();
		if (now - ranksAt < 1_000) return;
		ranksAt = now;
		Map<String, Integer> r = new HashMap<>();
		Map<String, Double> gn = new HashMap<>();
		try {
			List<HotfPlanner.Upgrade> list = Planner.upgrades();
			for (int i = 0; i < list.size(); i++) {
				String name = list.get(i).perk().name;
				if (!r.containsKey(name)) {
					r.put(name, i + 1);
					gn.put(name, list.get(i).gainPercent());
				}
			}
		} catch (RuntimeException e) {
			// The planner can fail while data is still loading; show the menu without ranks.
		}
		ranks = r;
		gains = gn;
	}

	// Drawing

	public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		int width = screen.width, height = screen.height;
		long nowNs = System.nanoTime();
		double dt = lastNs == 0 ? 0 : Math.min(0.1, (nowNs - lastNs) / 1e9);
		lastNs = nowNs;
		double open = Planner.config.animations ? Math.clamp((nowNs - openedNs) / 220e6, 0, 1) : 1;
		refreshRanks();

		g.fillGradient(0, 0, width, height, alpha(0xFF06100C, 0.82 * open), alpha(0xFF020604, 0.94 * open));

		List<Cell> cells = cells(screen);
		int rows = Math.max(1, (screen.getMenu().slots.size() - 36 + 8) / 9);

		// Panel: grid on the left, details on the right.
		int w = Math.min(width - 16, 620), h = Math.min(height - 16, 360);
		int x0 = (width - w) / 2, y0 = (height - h) / 2 + (int) Math.round((1 - open) * 10);
		shadow(g, x0, y0, w, h, 12, open);
		round(g, x0, y0, w, h, 12, alpha(UI_PANEL, open));
		ForestTheme.leaves(g, x0, y0, w, h, (nowNs - openedNs) / 1e9, open);
		roundOutline(g, x0, y0, w, h, 12, alpha(0x22FFFFFF, open));

		// Header
		g.item(new ItemStack(net.minecraft.world.item.Items.OAK_SAPLING), x0 + 12, y0 + 10);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x0 + 34, y0 + 10);
		pose.scale(1.3f, 1.3f);
		g.text(font, Planner.data.hotf.menuTitle, 0, 0, alpha(UI_TEXT, open), false);
		pose.popMatrix();
		ModConfig c = Planner.config;
		String budget = Planner.tr("hotfgui.header", c.hotfTier, c.whispers < 0 ? "?" : Planner.whole(c.whispers),
				c.desertWhispers < 0 ? "?" : Planner.whole(c.desertWhispers), Math.max(0, c.tokens));
		g.text(font, fit(font, budget, w - 160), x0 + 34, y0 + 25, alpha(UI_MUTED, open), false);

		// "Original" button
		String toggle = Planner.tr("hotfgui.original");
		toggleW = font.width(toggle) + 14;
		toggleX = x0 + w - toggleW - 12;
		toggleY = y0 + 12;
		boolean toggleHover = inside(mouseX, mouseY, toggleX, toggleY, toggleW, 16);
		round(g, toggleX, toggleY, toggleW, 16, 6, alpha(toggleHover ? 0x33FFFFFF : 0x18FFFFFF, open));
		g.text(font, toggle, toggleX + 7, toggleY + 4, alpha(toggleHover ? UI_TEXT : UI_MUTED, open), false);

		// Grid
		panelW = Math.min(200, w / 3);
		int gridW = w - panelW - 36, gridH = h - 70;
		cell = Math.max(18, Math.min(gridW / 9, gridH / rows));
		gridX = x0 + 14 + (gridW - cell * 9) / 2;
		gridY = y0 + 46 + (gridH - cell * rows) / 2;
		panelX = x0 + w - panelW - 12;
		panelY = y0 + 46;
		panelH = h - 58;

		Map<Integer, Cell> byIndex = new HashMap<>();
		for (Cell cl : cells) byIndex.put(cl.slot().index, cl);

		// Connections: panes link the cells next to them.
		for (Cell cl : cells) {
			if (cl.kind() != Kind.PANE) continue;
			int color = paneColor(cl.stack());
			int cx = gridX + cl.col() * cell + cell / 2, cy = gridY + cl.row() * cell + cell / 2;
			int thick = Math.max(2, cell / 9);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nc = cl.col() + d[0], nr = cl.row() + d[1];
				if (nc < 0 || nc > 8 || nr < 0) continue;
				Cell n = byIndex.get(nr * 9 + nc);
				if (n == null || n.kind() == Kind.BUTTON || n.kind() == Kind.INFO) continue;
				int ex = cx + d[0] * cell / 2, ey = cy + d[1] * cell / 2;
				g.fill(Math.min(cx, ex) - thick / 2, Math.min(cy, ey) - thick / 2, Math.max(cx, ex) + (thick + 1) / 2, Math.max(cy, ey) + (thick + 1) / 2, alpha(color, open));
			}
			round(g, cx - thick, cy - thick, thick * 2, thick * 2, thick, alpha(color, open));
		}

		Cell hovered = null;
		for (Cell cl : cells) {
			if (cl.kind() == Kind.PANE) continue;
			int x = gridX + cl.col() * cell + 1, y = gridY + cl.row() * cell + 1, s = cell - 2;
			boolean over = inside(mouseX, mouseY, x, y, s, s);
			if (over) hovered = cl;
			double hv = HOVER.getOrDefault(cl.slot().index, 0.0);
			hv = Planner.config.animations ? hv + ((over ? 1 : 0) - hv) * Math.min(1, dt * 14) : over ? 1 : 0;
			HOVER.put(cl.slot().index, hv);
			drawCard(g, font, cl, x, y - (int) Math.round(hv * 2), s, hv, open, nowNs);
		}

		drawPanel(g, font, hovered, open);

		g.text(font, fit(font, Planner.tr("hotfgui.footer"), w - 24), x0 + 14, y0 + h - 14, alpha(UI_DIM, open), false);
	}

	private static int paneColor(ItemStack stack) {
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (id.startsWith("lime") || id.startsWith("green")) return UI_ACCENT;
		if (id.startsWith("yellow") || id.startsWith("orange")) return UI_AMBER;
		if (id.startsWith("red")) return 0xFFC0574A;
		return 0xFF3A4A40;
	}

	private static void drawCard(GuiGraphicsExtractor g, Font font, Cell cl, int x, int y, int s, double hover, double open, long nowNs) {
		int fill, border;
		String line = null;
		int rank = 0;
		if (cl.kind() == Kind.PERK) {
			GameData.Perk perk = cl.perk();
			int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
			boolean locked = lore(cl.stack()).stream().anyMatch(l -> {
				String t = Planner.strip(l.getString());
				return t.startsWith("Requires") || t.contains("Click to unlock");
			}) && level == 0;
			if (level >= perk.maxLevel) {
				fill = 0xFF4A3D1C;
				border = UI_AMBER;
			} else if (level > 0) {
				fill = 0xFF1F3A26;
				border = UI_ACCENT;
			} else if (locked) {
				fill = 0xFF1A201C;
				border = 0xFF34403A;
			} else {
				fill = 0xFF22302A;
				border = 0xFF5D7A64;
			}
			line = level >= perk.maxLevel ? "MAX" : level + "/" + perk.maxLevel;
			rank = ranks.getOrDefault(perk.name, 0);
		} else if (cl.kind() == Kind.INFO) {
			fill = 0xFF2A2414;
			border = UI_AMBER;
		} else {
			fill = 0xFF1D2622;
			border = 0xFF3C4A42;
		}
		round(g, x, y, s, s, Math.max(3, s / 6), alpha(mixArgb(fill, 0xFF3A5A44, hover * 0.4), open));
		roundOutline(g, x, y, s, s, Math.max(3, s / 6), alpha(mixArgb(border, 0xFFFFFFFF, hover * 0.4), open));
		if (rank >= 1 && rank <= 3) {
			double pulse = Planner.config.animations ? 0.6 + 0.4 * Math.sin(nowNs / 2.5e8) : 1;
			roundOutline(g, x - 1, y - 1, s + 2, s + 2, Math.max(3, s / 6) + 1, alpha(UI_AMBER, pulse * open));
		}

		// Item picture, scaled with the card.
		float scale = Math.min(2f, (s - (line != null && s >= 30 ? 10 : 4)) / 16f);
		var pose = g.pose();
		pose.pushMatrix();
		int iconY = line != null && s >= 30 ? y + 3 : y + (s - Math.round(16 * scale)) / 2;
		pose.translate(x + (s - 16 * scale) / 2f, iconY);
		pose.scale(scale, scale);
		if (open > 0.5) g.item(cl.stack(), 0, 0);
		pose.popMatrix();

		if (line != null) {
			int tw = font.width(line);
			if (s >= 30) {
				g.text(font, line, x + (s - tw) / 2, y + s - 10, alpha(line.equals("MAX") ? UI_AMBER : UI_TEXT, open), true);
			} else {
				pose.pushMatrix();
				pose.translate(x + s - 1, y + s - 6);
				pose.scale(0.6f, 0.6f);
				g.text(font, line, -tw, 0, alpha(UI_TEXT, open), true);
				pose.popMatrix();
			}
		}
		if (rank >= 1 && rank <= 3) {
			String badge = "#" + rank;
			int bw = font.width(badge) + 4;
			round(g, x + s - bw + 2, y - 4, bw, 9, 3, alpha(UI_AMBER, open));
			pose.pushMatrix();
			pose.translate(x + s - bw + 4, y - 3);
			pose.scale(0.8f, 0.8f);
			g.text(font, badge, 0, 0, alpha(0xFF1A1206, open), false);
			pose.popMatrix();
		}
	}

	private static void drawPanel(GuiGraphicsExtractor g, Font font, Cell cl, double open) {
		round(g, panelX, panelY, panelW, panelH, 8, alpha(0x40000000, open));
		int x = panelX + 8, y = panelY + 8, w = panelW - 16, bottom = panelY + panelH - 8;
		if (cl == null) {
			g.text(font, Planner.tr("hotfgui.next"), x, y, alpha(UI_AMBER, open), false);
			y += 14;
			List<Map.Entry<String, Integer>> top = new ArrayList<>(ranks.entrySet());
			top.sort(Map.Entry.comparingByValue());
			for (int i = 0; i < Math.min(5, top.size()); i++) {
				String name = top.get(i).getKey();
				double gain = gains.getOrDefault(name, 0.0);
				g.text(font, fit(font, "#" + (i + 1) + "  " + name, w - 44), x, y, alpha(UI_TEXT, open), false);
				String pct = String.format(Locale.US, "+%.2f%%", gain);
				g.text(font, pct, x + w - font.width(pct), y, alpha(UI_ACCENT, open), false);
				y += 12;
			}
			if (top.isEmpty()) g.text(font, fit(font, Planner.tr("hotfgui.no_plan"), w), x, y, alpha(UI_DIM, open), false);
			y += 8;
			g.textWithWordWrap(font, Component.literal(Planner.tr("hotfgui.hint")), x, y, w, alpha(UI_MUTED, open), false);
			return;
		}

		g.text(font, fit(font, cl.stack().getHoverName().getString(), w), x, y, alpha(UI_TEXT, open), false);
		y += 13;
		if (cl.perk() != null) {
			GameData.Perk perk = cl.perk();
			int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
			int rank = ranks.getOrDefault(perk.name, 0);
			String status = level + "/" + perk.maxLevel + "  ·  " + ("desert".equals(perk.currency) ? "Desert" : "Forest");
			g.text(font, status, x, y, alpha(UI_MUTED, open), false);
			y += 11;
			if (rank > 0) {
				g.text(font, fit(font, Planner.tr("hotfgui.rank", rank, String.format(Locale.US, "%.2f", gains.getOrDefault(perk.name, 0.0))), w), x, y, alpha(UI_AMBER, open), false);
				y += 11;
			}
			if (level < perk.maxLevel) {
				try {
					var stats = SkyBlockReader.stats();
					var after = dev.foragingplanner.core.HotfPlanner.advance(Planner.data, Planner.config.perkLevels, stats, perk, 1);
					String change = Why.stats(after.sweep() - stats.sweep(), after.fortune() - stats.fortune(), after.wisdom() - stats.wisdom());
					long cost = dev.foragingplanner.core.PerkMath.costToReach(perk, level + 1);
					g.text(font, fit(font, Planner.tr("hotfgui.why", change, Planner.whole(cost)), w), x, y, alpha(UI_MUTED, open), false);
					y += 11;
				} catch (RuntimeException ignored) {
					// Data still loading.
				}
			}
			y += 3;
		}
		// The item's own description, word-wrapped, with Hypixel's colours.
		g.enableScissor(panelX, y, panelX + panelW, bottom);
		for (Component line : lore(cl.stack())) {
			if (line.getString().isBlank()) {
				y += 5;
				continue;
			}
			for (FormattedCharSequence part : font.split(line, w)) {
				if (y > bottom) break;
				g.text(font, part, x, y, alpha(0xFFFFFFFF, open), false);
				y += 10;
			}
		}
		g.disableScissor();
	}

	/** In the original menu: a small button to go back to the custom one. */
	public static void renderBack(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Font font = Minecraft.getInstance().font;
		String label = Planner.tr("hotfgui.custom");
		toggleW = font.width(label) + 14;
		toggleX = (screen.width - toggleW) / 2;
		toggleY = 6;
		boolean over = inside(mouseX, mouseY, toggleX, toggleY, toggleW, 16);
		round(g, toggleX, toggleY, toggleW, 16, 6, over ? 0xEE2A4A34 : 0xCC14221A);
		g.text(font, label, toggleX + 7, toggleY + 4, over ? UI_TEXT : UI_MUTED, false);
	}

	public static boolean clickBack(double mx, double my) {
		if (!inside(mx, my, toggleX, toggleY, toggleW, 16)) return false;
		original = false;
		sound();
		return true;
	}

	// Input

	/** A click while the custom menu is shown. Always handled, so the game's own menu never sees it. */
	public static boolean click(AbstractContainerScreen<?> screen, double mx, double my, int button) {
		Minecraft client = Minecraft.getInstance();
		if (inside(mx, my, toggleX, toggleY, toggleW, 16)) {
			original = true;
			sound();
			return true;
		}
		for (Cell cl : cells(screen)) {
			if (cl.kind() == Kind.PANE) continue;
			int x = gridX + cl.col() * cell + 1, y = gridY + cl.row() * cell + 1, s = cell - 2;
			if (!inside(mx, my, x, y, s, s)) continue;
			if (client.gameMode != null && client.player != null) {
				client.gameMode.handleContainerInput(screen.getMenu().containerId, cl.slot().index, button == 1 ? 1 : 0, ContainerInput.PICKUP, client.player);
				sound();
				ranksAt = 0;
			}
			return true;
		}
		return true;
	}

	private static void sound() {
		UiSound.click();
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
