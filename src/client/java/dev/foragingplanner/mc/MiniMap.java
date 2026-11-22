package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * A small north-up map in a corner of the screen, with the real terrain (see {@link WorldMap}), trees,
 * places, the route and marked mobs. N opens the full screen map.
 */
public final class MiniMap {
	private static KeyMapping key;
	private static boolean big;
	private static double grow;

	private MiniMap() {}

	public static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.map", InputConstants.KEY_N, KeyMapping.Category.MISC));
	}

	public static void keys() {
		if (key == null) return;
		while (key.consumeClick()) {
			var client = Minecraft.getInstance();
			if (client.player != null && client.gui.screen() == null) MapScreen.open();
		}
	}

	static boolean isMapKey(net.minecraft.client.input.KeyEvent event) {
		return key != null && key.matches(event);
	}

	public static void extract(GuiGraphicsExtractor g) {
		var client = Minecraft.getInstance();
		if (!Planner.config.minimap || client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null || !SkyBlockReader.inForagingArea() && !Safari.inside()) return;
		grow = Planner.config.animations ? grow + ((big ? 1 : 0) - grow) * 0.2 : (big ? 1 : 0);

		int size = (int) Math.round(Math.clamp(Planner.config.mapSize, 60, 200) * (1 + grow));
		double range = Math.clamp(Planner.config.mapRange, 30, 150) * (1 + grow);
		int sw = client.getWindow().getGuiScaledWidth(), sh = client.getWindow().getGuiScaledHeight();
		int x = Planner.config.mapCorner.endsWith("left") ? 6 : sw - size - 6;
		int y = Planner.config.mapCorner.startsWith("bottom") ? sh - size - 6 : 6;
		int radius = Math.clamp(Planner.config.hudRadius, 0, 12);
		int fill = ((int) Math.round(Math.clamp(Math.max(Planner.config.hudOpacity, 40), 0, 100) * 2.55) << 24) | (Planner.config.hudColor & 0xFFFFFF);
		round(g, x, y, size, size, radius, fill);
		if (Planner.config.hudBorder) roundOutline(g, x, y, size, size, radius, 0xFF000000 | Planner.config.hudBorderColor);

		Vec3 me = client.player.position();
		double scale = size / (2 * range);
		int cx = x + size / 2, cy = y + size / 2;
		g.enableScissor(x + 1, y + 1, x + size - 1, y + size - 1);

		// Real terrain, with a light grid every 20 blocks where nothing is mapped yet.
		if (Planner.config.worldMap && WorldMap.hasTiles()) {
			WorldMap.draw(g, me.x, me.z, cx, cy, scale, x, y, x + size, y + size);
		} else {
			for (double gx = Math.floor((me.x - range) / 20) * 20; gx <= me.x + range; gx += 20) {
				int px = cx + (int) Math.round((gx - me.x) * scale);
				g.fill(px, y, px + 1, y + size, 0x14FFFFFF);
			}
			for (double gz = Math.floor((me.z - range) / 20) * 20; gz <= me.z + range; gz += 20) {
				int pz = cy + (int) Math.round((gz - me.z) * scale);
				g.fill(x, pz, x + size, pz + 1, 0x14FFFFFF);
			}
		}
		MapMarkers.draw(g, me.x, me.z, cx, cy, scale, false);
		g.disableScissor();

		g.text(client.font, "N", cx - 2, y + 3, 0xAAFFFFFF, true);
	}

	static void dot(GuiGraphicsExtractor g, double px, double pz, int r, int color) {
		int ix = (int) Math.round(px), iz = (int) Math.round(pz);
		g.fill(ix - r, iz - r, ix + r, iz + r, color);
	}

	static void line(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
		int steps = (int) Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2);
		for (int i = 0; i <= steps; i += 2) {
			int px = (int) Math.round(x1 + (x2 - x1) * i / steps), py = (int) Math.round(y1 + (y2 - y1) * i / steps);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}
}
