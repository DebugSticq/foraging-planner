package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * The row of tabs above the mod's big menus (statistics, gear, check, settings), so they work
 * like one window: switching keeps the same "back" target.
 */
public final class MenuBar {
	public static final String[] MENUS = {"stats", "gear", "check", "settings"};
	public static final int HEIGHT = 13;

	private MenuBar() {}

	private static int[] layout(Font font, int screenWidth) {
		int[] widths = new int[MENUS.length];
		int total = 0;
		for (int i = 0; i < MENUS.length; i++) {
			widths[i] = font.width(Planner.tr("menubar." + MENUS[i])) + 14;
			total += widths[i] + (i > 0 ? 3 : 0);
		}
		int[] out = new int[MENUS.length + 1];
		out[0] = (screenWidth - total) / 2;
		for (int i = 0; i < MENUS.length; i++) out[i + 1] = out[i] + widths[i] + 3;
		return out;
	}

	/** Draws the tabs with their top at {@code y}; {@code current} is one of {@link #MENUS}. */
	public static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int y, String current, int mouseX, int mouseY, double a) {
		if (a <= 0.05) return;
		int[] xs = layout(font, screenWidth);
		for (int i = 0; i < MENUS.length; i++) {
			int x = xs[i], w = xs[i + 1] - xs[i] - 3;
			boolean on = MENUS[i].equals(current);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + HEIGHT;
			round(g, x, y, w, HEIGHT, 5, alpha(on ? 0xFF2F5A3A : hover ? 0xE0263B2C : 0xC0182A1F, a));
			String label = Planner.tr("menubar." + MENUS[i]);
			g.text(font, label, x + 7, y + 3, alpha(on ? UI_ACCENT : hover ? 0xFFFFFFFF : UI_MUTED, a), false);
		}
	}

	/** Opens the clicked menu; true if a tab was hit. */
	public static boolean click(double mouseX, double mouseY, int screenWidth, int y, String current, Screen backTo) {
		if (mouseY < y || mouseY >= y + HEIGHT) return false;
		Font font = Minecraft.getInstance().font;
		int[] xs = layout(font, screenWidth);
		for (int i = 0; i < MENUS.length; i++) {
			if (mouseX < xs[i] || mouseX >= xs[i + 1] - 3) continue;
			if (MENUS[i].equals(current)) return true;
			UiSound.page();
			Screen next = switch (MENUS[i]) {
				case "stats" -> new StatsScreen(backTo);
				case "gear" -> new GearScreen(backTo);
				case "check" -> new CheckScreen(backTo);
				default -> new SettingsScreen(backTo);
			};
			Minecraft.getInstance().gui.setScreen(next);
			return true;
		}
		return false;
	}
}
