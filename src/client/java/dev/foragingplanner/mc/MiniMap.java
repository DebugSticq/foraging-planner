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

/** A small north-up map of the trees around the player, the active route and marked mobs. N enlarges it. */
public final class MiniMap {
	private static KeyMapping key;
	private static boolean big;
	private static double grow;

	private MiniMap() {}

	public static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.map", InputConstants.KEY_N, KeyMapping.Category.MISC));
	}

	public static void keys() {
		if (key != null) while (key.consumeClick()) big = !big;
	}

	public static void extract(GuiGraphicsExtractor g) {
		var client = Minecraft.getInstance();
		if (!Planner.config.minimap || client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null || !SkyBlockReader.inForagingArea()) return;
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

		// Grid every 20 blocks, so distances can be judged.
		for (double gx = Math.floor((me.x - range) / 20) * 20; gx <= me.x + range; gx += 20) {
			int px = cx + (int) Math.round((gx - me.x) * scale);
			g.fill(px, y, px + 1, y + size, 0x14FFFFFF);
		}
		for (double gz = Math.floor((me.z - range) / 20) * 20; gz <= me.z + range; gz += 20) {
			int pz = cy + (int) Math.round((gz - me.z) * scale);
			g.fill(x, pz, x + size, pz + 1, 0x14FFFFFF);
		}

		// Route
		Routes.Route route = Routes.recording() != null ? Routes.recording() : Routes.active();
		if (route != null && route.points.size() > 1) {
			for (int i = 0; i < route.points.size() - (route.loop ? 0 : 1); i++) {
				Vec3 a = route.points.get(i).pos(), b = route.points.get((i + 1) % route.points.size()).pos();
				line(g, cx + (a.x - me.x) * scale, cy + (a.z - me.z) * scale, cx + (b.x - me.x) * scale, cy + (b.z - me.z) * scale, 0xAA86BD6E);
			}
			Routes.Point next = route.points.get(Math.floorMod(Routes.target(), route.points.size()));
			dot(g, cx + (next.pos().x - me.x) * scale, cy + (next.pos().z - me.z) * scale, 3, 0xFFFFFFFF);
		}

		// Trees
		TreeTracker.Spot best = TreeChoice.best();
		for (TreeTracker.Spot spot : TreeTracker.spots()) {
			double px = cx + (spot.cx - me.x) * scale, pz = cy + (spot.cz - me.z) * scale;
			int color = switch (spot.state) {
				case READY -> 0xFF8CD58B;
				case CUTTING -> UI_AMBER;
				default -> 0xFF6E7672;
			};
			dot(g, px, pz, spot == best ? 4 : 2, spot == best ? 0xFFFFD34D : color);
		}

		// Marked mobs and critters
		for (Extras.Mark mark : Extras.marks()) dot(g, cx + (mark.pos().x - me.x) * scale, cy + (mark.pos().z - me.z) * scale, 2, mark.color());

		// The player: an arrow pointing where they look.
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate((float) Math.toRadians(client.player.getYRot() + 180));
		for (int i = 0; i < 6; i++) g.fill(-i / 2 - 1, -4 + i, i / 2 + 1, -3 + i, 0xFFFFFFFF);
		pose.popMatrix();
		g.disableScissor();

		g.text(client.font, "N", cx - 2, y + 3, 0xAAFFFFFF, true);
	}

	private static void dot(GuiGraphicsExtractor g, double px, double pz, int r, int color) {
		int ix = (int) Math.round(px), iz = (int) Math.round(pz);
		g.fill(ix - r, iz - r, ix + r, iz + r, color);
	}

	private static void line(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
		int steps = (int) Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2);
		for (int i = 0; i <= steps; i += 2) {
			int px = (int) Math.round(x1 + (x2 - x1) * i / steps), py = (int) Math.round(y1 + (y2 - y1) * i / steps);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}
}
