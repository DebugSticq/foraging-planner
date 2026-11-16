package dev.foragingplanner.mc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import java.util.HashMap;
import java.util.Map;

/** Shared colors and pixel-edged surfaces for the settings and HUD editor. */
final class ForestTheme {
    static final int CANVAS = 0xFF101B18;
    static final int PANEL = 0xFF1B2A23;
    static final int CARD = 0xFF24362C;
    static final int BORDER = 0xFF405244;
    static final int GOLD = 0xFFE0BD7C;
    static final int LEAF = 0xFFABCA85;
    static final int TEXT = 0xFFF1E9D5;
    static final int MUTED = 0xFFA5B3A2;

    // Palette of the settings screen: deep forest glass, moss accent, warm amber highlights.
    static final int UI_PANEL = 0xE60E1914;
    static final int UI_SIDEBAR = 0xB0091310;
    static final int UI_ROW = 0x10FFFFFF;
    static final int UI_ROW_HOVER = 0x22FFFFFF;
    static final int UI_TILE = 0x30000000;
    static final int UI_FIELD = 0x40000000;
    static final int UI_FIELD_LINE = 0xFF3E5446;
    static final int UI_ACCENT = 0xFF86BD6E;
    static final int UI_ACCENT_DARK = 0xFF3F6B3A;
    static final int UI_ACCENT_SOFT = 0x3386BD6E;
    static final int UI_AMBER = 0xFFEBC678;
    static final int UI_TEXT = 0xFFF3EFE4;
    static final int UI_MUTED = 0xFF92A697;
    static final int UI_DIM = 0xFF5F7265;
    static final int UI_DANGER = 0xFFE58172;
    static final int UI_SWITCH_OFF = 0xFF2C3B33;
    static final int UI_KNOB = 0xFFF6F3EA;
    static final int UI_ON_ACCENT = 0xFF0B1610;

    private static final Map<Item, Identifier> ICONS = new HashMap<>();
    private ForestTheme() {}

    /** The colour with its alpha channel multiplied by {@code amount} (0 to 1). */
    static int alpha(int color, double amount) {
        int base = color >>> 24;
        int value = (int) Math.round(base * Math.clamp(amount, 0.0, 1.0));
        return value << 24 | (color & 0xFFFFFF);
    }

    /** Blends two colours including their alpha channels. */
    static int mixArgb(int from, int to, double amount) {
        double t = Math.clamp(amount, 0.0, 1.0);
        int result = 0;

        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255;
            int b = (to >>> shift) & 255;
            result |= ((int) Math.round(a + (b - a) * t) & 255) << shift;
        }

        return result;
    }

    /** Filled rectangle with rounded corners. Rows never overlap, so translucent colours stay even. */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || color >>> 24 == 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);

        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }

        g.fill(x, y + r, x + w, y + h - r, color);
    }

    /** One-pixel outline that follows the same corners as {@link #round}. */
    static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || color >>> 24 == 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        g.fill(x + cornerInset(r, 0), y, x + w - cornerInset(r, 0), y + 1, color);
        g.fill(x + cornerInset(r, 0), y + h - 1, x + w - cornerInset(r, 0), y + h, color);

        for (int i = 1; i < r; i++) {
            int inset = cornerInset(r, i);
            int run = Math.max(inset + 1, cornerInset(r, i - 1));
            g.fill(x + inset, y + i, x + run, y + i + 1, color);
            g.fill(x + w - run, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + run, y + h - i, color);
            g.fill(x + w - run, y + h - 1 - i, x + w - inset, y + h - i, color);
        }

        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
    }

    private static int cornerInset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** Soft drop shadow made of a few widening, faint layers. */
    static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, double strength) {
        for (int k = 6; k >= 1; k--) {
            round(g, x - k, y - k + 3, w + 2 * k, h + 2 * k, radius + k, alpha(0xFF000000, strength * 0.07 * (1 - k / 7.0)));
        }
    }

    /** Item icon drawn with transparency, for fading content in and out. */
    static void icon(GuiGraphicsExtractor g, Item item, int x, int y, double opacity) {
        if (opacity >= 0.999) {
            icon(g, item, x, y);
            return;
        }

        if (opacity <= 0.01) return;
        g.blit(RenderPipelines.GUI_TEXTURED, texture(item), x, y, 0, 0, 16, 16, 16, 16, alpha(0xFFFFFFFF, opacity));
    }

    static double easeOut(double t) {
        double c = Math.clamp(t, 0.0, 1.0);
        return 1 - Math.pow(1 - c, 3);
    }

    /** Ease-out with a small overshoot, for things that should feel like they settle into place. */
    static double easeOutBack(double t) {
        double c = Math.clamp(t, 0.0, 1.0) - 1;
        return 1 + 2.2 * c * c * c + 1.2 * c * c;
    }

    /** Moves {@code value} towards {@code target}; {@code speed} is how quickly, independent of frame rate. */
    static double approach(double value, double target, double seconds, double speed) {
        return value + (target - value) * (1 - Math.exp(-seconds * speed));
    }

    static double fract(double value) {
        return value - Math.floor(value);
    }

    /** Cuts text to a width and marks the cut with an ellipsis. */
    static String fit(net.minecraft.client.gui.Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    /** Menu icons use textures directly so they also work before server components are bound. */
    static void icon(GuiGraphicsExtractor g, Item item, int x, int y) {
        Identifier texture = texture(item);
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, 16, 16, 16, 16);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x40000000);
        g.fill(x + 1, y, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + w, y + h - 1, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    private static Identifier texture(Item item) {
        return ICONS.computeIfAbsent(item, key -> {
            String name = BuiltInRegistries.ITEM.getKey(key).getPath();
            String path = switch (name) {
                case "sand" -> "block/sand";
                case "target" -> "block/target_side";
                case "chest" -> "block/barrel_side";
                case "player_head" -> "item/name_tag";
                case "oak_log", "mangrove_log", "oak_sapling", "note_block" -> "block/" + name;
                case "stripped_birch_wood" -> "block/stripped_birch_log";
                case "stripped_mangrove_wood" -> "block/stripped_mangrove_log";
                case "sunflower" -> "block/sunflower_front";
                case "clock" -> "item/clock_00";
                case "compass" -> "item/compass_16";
                default -> "item/" + name;
            };
            return Identifier.fromNamespaceAndPath("minecraft", "textures/" + path + ".png");
        });
    }

    static int mix(int from, int to, double amount) {
        int red = (int) (((from >> 16) & 255) * (1 - amount) + ((to >> 16) & 255) * amount);
        int green = (int) (((from >> 8) & 255) * (1 - amount) + ((to >> 8) & 255) * amount);
        int blue = (int) ((from & 255) * (1 - amount) + (to & 255) * amount);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static void canopy(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.enableScissor(x, y, x + width, y + height);
        g.fill(x, y, x + width, y + height, 0xFF1D3028);
        for (int i = 0; i < 8; i++) {
            int treeX = x + i * width / 7 - 8;
            int crownY = y + 8 + (i * 13 % 23);
            g.fill(treeX + 9, crownY + 12, treeX + 13, y + height, 0xFF384333);
            g.fill(treeX - 4, crownY + 9, treeX + 26, crownY + 24, 0xFF294337);
            g.fill(treeX + 1, crownY + 2, treeX + 21, crownY + 18, 0xFF35513D);
            g.fill(treeX + 5, crownY, treeX + 17, crownY + 7, 0xFF4D6446);
        }
        g.disableScissor();
    }

	/** Faint leaves drifting down a panel. {@code seconds} is the time since the screen opened. */
	public static void leaves(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, double seconds, double opacity) {
		if (!Planner.config.animations) return;
		g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
		for (int i = 0; i < 14; i++) {
			double seedX = fract(Math.sin(i * 12.9898) * 43758.5453);
			double seedY = fract(Math.sin(i * 78.233) * 12543.123);
			double speed = 0.015 + fract(Math.sin(i * 3.17) * 911.7) * 0.025;
			double phase = fract(Math.sin(i * 5.71) * 331.3) * Math.PI * 2;
			double fy = (seedY + seconds * speed) % 1.0;
			double fx = seedX + Math.sin(seconds * 0.8 + phase) * 12.0 / w;
			int size = 2 + i % 3;
			int color = alpha(phase > Math.PI ? UI_ACCENT : UI_AMBER, 0.07 * Math.sin(fy * Math.PI) * opacity);
			round(g, x + (int) (fx * w), y + (int) (fy * h), size + 1, size, 1, color);
		}
		g.disableScissor();
	}
}
