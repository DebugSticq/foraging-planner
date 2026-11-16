package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.TreeMath;

/**
 * Tree markers, the walking route, the arrow to the next tree and the Sweep preview, drawn on the
 * HUD at the screen position of each tree.
 */
public final class WorldOverlay {
	private static final int READY = 0xFF8CD58B;
	private static final int GONE = 0xFF9AA3A0;

	private WorldOverlay() {}

	/** Screen position of a world point, or null when it is behind the camera. */
	private static float[] project(Minecraft client, Vec3 point) {
		var camera = client.gameRenderer.mainCamera();
		if (camera == null || !camera.isInitialized()) return null;

		Vec3 rel = point.subtract(camera.position());
		double yaw = Math.toRadians(camera.yRot());
		double pitch = Math.toRadians(camera.xRot());
		// Minecraft: yaw 0 looks toward +Z, positive pitch looks down.
		Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 up = forward.cross(left);
		double z = rel.dot(forward);
		if (z < 0.2) return null;

		double x = rel.dot(left);
		double y = rel.dot(up);
		int w = client.getWindow().getGuiScaledWidth();
		int h = client.getWindow().getGuiScaledHeight();
		double f = 1 / Math.tan(Math.toRadians(camera.getFov()) / 2) * (h / 2.0);
		return new float[] {(float) (w / 2.0 - x / z * f), (float) (h / 2.0 - y / z * f), (float) z};
	}

	public static void extract(GuiGraphicsExtractor g) {
		var client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null || !SkyBlockReader.inForagingArea()) return;

		long now = System.currentTimeMillis();
		Vec3 player = client.player.position();
		List<TreeTracker.Spot> spots = TreeTracker.spots();

		Routes.Route custom = Routes.recording() != null ? Routes.recording() : Routes.active();
		if (custom != null) drawCustomRoute(client, g, player, custom, Routes.recording() != null);
		else if (Planner.config.treeRoute) drawRoute(client, g, player);
		if (Planner.config.treeMarkers) {
			for (TreeTracker.Spot spot : spots) {
				double distance = spot.center().distanceTo(player);
				if (distance > 70) continue;
				float[] at = project(client, spot.center().add(0, 2.5, 0));
				if (at == null) continue;
				drawMarker(client, g, spot, at, distance, now);
			}
		}

		if (Routes.active() != null && Routes.recording() == null) drawRouteArrow(client, g, player, Routes.active());
		else if (Planner.config.treeArrow) drawArrow(client, g, player);
		if (Planner.config.sweepPreview) drawSweep(client, g);
		drawChoice(client, g, player, now);
		drawGhost(client, g, now);
		drawPrecision(client, g);
		drawExtras(client, g, player, now);
		Combo.draw(g);
	}

	private static void drawMarker(Minecraft client, GuiGraphicsExtractor g, TreeTracker.Spot spot, float[] at, double distance, long now) {
		var font = client.font;
		double a = Math.clamp(1.2 - distance / 70, 0.25, 1);
		int x = Math.round(at[0]);
		int y = Math.round(at[1]);

		String label;
		int color;

		switch (spot.state) {
			case READY -> {
				label = Planner.tr("tree.ready");
				color = READY;
			}
			case CUTTING -> {
				label = Planner.tr("tree.cutting");
				color = UI_AMBER;
			}
			default -> {
				long since = (now - spot.cutAt) / 1000;
				long regrow = spot.regrow();
				label = regrow > 0 ? Planner.tr("tree.regrow", GearScreenFormat.duration(Math.max(0, regrow / 1000 - since)))
						: Planner.tr("tree.cut_ago", GearScreenFormat.duration(since));
				color = GONE;
			}
		}

		double pulse = spot.state == TreeTracker.State.READY && Planner.config.animations ? 0.75 + 0.25 * Math.sin(now / 300.0 + spot.x) : 1;
		int dot = 5;
		round(g, x - dot, y - dot, dot * 2, dot * 2, dot, alpha(color, a * pulse));
		round(g, x - 2, y - 2, 4, 4, 2, alpha(0xFFFFFFFF, a * 0.9));

		String size = Planner.config.treeSizeLabel ? sizeLabel(spot) : "";
		String text = label + (size.isEmpty() ? "" : " · " + size) + " · " + Math.round(distance) + "m";
		int tw = font.width(text);
		round(g, x - tw / 2 - 4, y - dot - 14, tw + 8, 11, 4, alpha(UI_PANEL, a * 0.75));
		g.text(font, text, x - tw / 2, y - dot - 12, alpha(color, a), false);
	}

	/** "Big" or "small" compared with the other trees of the same kind on this island. */
	private static String sizeLabel(TreeTracker.Spot spot) {
		if (spot.maxBlocks <= 0) return "";
		List<Integer> sizes = new java.util.ArrayList<>();
		for (TreeTracker.Spot other : TreeTracker.spots()) if (other.tree.equals(spot.tree) && other.maxBlocks > 0) sizes.add(other.maxBlocks);
		if (sizes.size() < 4) return "";
		sizes.sort(null);
		int median = sizes.get(sizes.size() / 2);
		if (spot.maxBlocks >= median * 1.3) return Planner.tr("tree.big");
		if (spot.maxBlocks <= median * 0.75) return Planner.tr("tree.small");
		return "";
	}

	/** A star over the tree the choice assistant recommends. */
	private static void drawChoice(Minecraft client, GuiGraphicsExtractor g, Vec3 player, long now) {
		TreeTracker.Spot best = TreeChoice.best();
		if (best == null) return;
		float[] at = project(client, best.center().add(0, 4.2, 0));
		if (at == null) return;
		var font = client.font;
		int x = Math.round(at[0]);
		int y = Math.round(at[1] + (Planner.config.animations ? (float) Math.sin(now / 250.0) * 2 : 0));
		String text = "★ " + Planner.tr("tree.choice", Math.round(TreeChoice.seconds()));
		int tw = font.width(text);
		round(g, x - tw / 2 - 4, y - 6, tw + 8, 12, 4, alpha(UI_PANEL, 0.82));
		g.text(font, text, x - tw / 2, y - 4, 0xFFFFD34D, false);
	}

	/** The best lap of the active route as a moving dot. */
	private static void drawGhost(Minecraft client, GuiGraphicsExtractor g, long now) {
		Vec3 ghost = Routes.ghost();
		if (ghost == null) return;
		float[] at = project(client, ghost.add(0, 1.0, 0));
		if (at == null) return;
		int x = Math.round(at[0]), y = Math.round(at[1]);
		int color = Routes.ahead() ? 0xFF7FD48A : 0xFFE07A6A;
		round(g, x - 5, y - 5, 10, 10, 5, alpha(0xFFB8C7FF, 0.55));
		round(g, x - 2, y - 2, 4, 4, 2, alpha(color, 0.95));
		String text = Planner.tr("route.ghost");
		g.text(client.font, text, x - client.font.width(text) / 2, y - 15, alpha(0xFFB8C7FF, 0.9), false);
	}

	/** Falling mobs, critters and Invisibug crits. */
	private static void drawExtras(Minecraft client, GuiGraphicsExtractor g, Vec3 player, long now) {
		var font = client.font;
		for (Extras.Mark mark : Extras.marks()) {
			float[] at = project(client, mark.pos().add(0, 1.2, 0));
			if (at == null) continue;
			int x = Math.round(at[0]), y = Math.round(at[1]);
			double pulse = mark.alert() && Planner.config.animations ? 0.6 + 0.4 * Math.sin(now / 120.0) : 1;
			round(g, x - 4, y - 4, 8, 8, 4, alpha(mark.color(), pulse));
			String text = (mark.alert() ? "⚠ " : "") + mark.name() + " · " + Math.round(mark.pos().distanceTo(player)) + "m";
			int tw = font.width(text);
			round(g, x - tw / 2 - 3, y - 17, tw + 6, 11, 4, alpha(UI_PANEL, 0.75));
			g.text(font, text, x - tw / 2, y - 15, alpha(mark.color(), 1), false);
		}
		if (Planner.config.invisibugHelper) {
			for (Vec3 crit : Precision.crits()) {
				float[] at = project(client, crit);
				if (at == null) continue;
				int x = Math.round(at[0]), y = Math.round(at[1]);
				round(g, x - 3, y - 3, 6, 6, 3, 0xFFFF66FF);
			}
		}
	}

	/** Frame around each log Precision Cutting marked. */
	private static void drawPrecision(Minecraft client, GuiGraphicsExtractor g) {
		for (Vec3 marked : Precision.marked()) {
			float[] at = project(client, marked);
			if (at == null) continue;
			int x = Math.round(at[0]), y = Math.round(at[1]);
			int s = (int) Math.clamp(60 / Math.max(1, at[2]), 6, 26);
			int c = 0xFFFFE14D;
			g.fill(x - s, y - s, x + s, y - s + 1, c);
			g.fill(x - s, y + s - 1, x + s, y + s, c);
			g.fill(x - s, y - s, x - s + 1, y + s, c);
			g.fill(x + s - 1, y - s, x + s, y + s, c);
		}
	}

	private static void drawRoute(Minecraft client, GuiGraphicsExtractor g, Vec3 player) {
		List<TreeTracker.Spot> path = TreeTracker.route(player, 6);
		Vec3 previous = player.add(0, 0.2, 0);
		var font = client.font;

		for (int i = 0; i < path.size(); i++) {
			Vec3 next = path.get(i).center().add(0, 0.2 - (path.get(i).cy - path.get(i).y), 0);
			dottedLine(client, g, previous, next, alpha(UI_ACCENT, 0.8 - i * 0.1));
			float[] at = project(client, path.get(i).center().add(0, 2.5, 0));
			if (at != null) {
				String number = String.valueOf(i + 1);
				round(g, Math.round(at[0]) + 7, Math.round(at[1]) - 4, font.width(number) + 6, 10, 5, alpha(UI_ACCENT, 0.9));
				g.text(font, number, Math.round(at[0]) + 10, Math.round(at[1]) - 3, 0xFF0B1A10, false);
			}
			previous = next;
		}
	}

	private static void drawCustomRoute(Minecraft client, GuiGraphicsExtractor g, Vec3 player, Routes.Route route, boolean recording) {
		var font = client.font;
		int n = route.points.size();
		if (n == 0) return;
		int target = recording ? n - 1 : Math.floorMod(Routes.target(), n);
		int lineColor = recording ? UI_AMBER : UI_ACCENT;

		// The whole route as a faint line, the next leg brighter.
		for (int i = 0; i < n - (route.loop && !recording ? 0 : 1); i++) {
			Routes.Point to = route.points.get((i + 1) % n);
			Vec3 a = route.points.get(i).pos().add(0, 0.15, 0);
			Vec3 b = to.pos().add(0, 0.15, 0);
			if (a.distanceTo(player) > 90 && b.distanceTo(player) > 90) continue;
			boolean next = !recording && i == Math.floorMod(target - 1, n);
			// An etherwarp leg flies from eye height straight to the target block.
			if (to.etherwarp()) dottedLine(client, g, a.add(0, 1.5, 0), to.pos(), alpha(WARP, next ? 0.9 : 0.4));
			else dottedLine(client, g, a, b, alpha(lineColor, next ? 0.9 : 0.35));
		}
		if (!recording) {
			Routes.Point next = route.points.get(target);
			if (next.etherwarp()) dottedLine(client, g, client.player.getEyePosition().add(0, -0.4, 0), next.pos(), alpha(WARP, 0.9));
			else dottedLine(client, g, player.add(0, 0.15, 0), next.pos().add(0, 0.15, 0), alpha(UI_AMBER, 0.85));
		}

		for (int i = 0; i < n; i++) {
			Routes.Point point = route.points.get(i);
			double distance = point.pos().distanceTo(player);
			if (distance > 90) continue;
			float[] at = project(client, point.pos().add(0, 1.2, 0));
			if (at == null) continue;
			boolean isTarget = i == target;
			if (point.etherwarp()) blockOutline(client, g, point, alpha(WARP, isTarget ? 1 : 0.55));
			String name = point.etherwarp() ? "⇢ Etherwarp" + (point.name.isEmpty() || point.name.toLowerCase(java.util.Locale.ROOT).contains("ether") ? "" : " · " + point.name)
					: point.name.isEmpty() ? "" : point.name;
			String label = (i + 1) + (name.isEmpty() ? "" : " · " + name) + (isTarget ? " · " + Math.round(distance) + "m" : "");
			int color = point.etherwarp() ? WARP : isTarget ? UI_AMBER : lineColor;
			int x = Math.round(at[0]), y = Math.round(at[1]);
			int r = isTarget ? 5 : 3;
			round(g, x - r, y - r, r * 2, r * 2, r, alpha(color, isTarget ? 1 : 0.7));
			int tw = font.width(label);
			round(g, x - tw / 2 - 3, y - r - 13, tw + 6, 11, 4, alpha(UI_PANEL, 0.7));
			g.text(font, label, x - tw / 2, y - r - 11, alpha(color, 1), false);
		}

		if (!recording) drawWarpAim(client, g, route.points.get(target));

		String title = recording ? Planner.tr("route.hud_recording", route.name, n) : Planner.tr("route.hud_active", route.name, target + 1, n);
		int w = font.width(title) + 10;
		int sx = client.getWindow().getGuiScaledWidth() / 2 - w / 2;
		round(g, sx, 4, w, 13, 5, alpha(UI_PANEL, 0.75));
		g.text(font, title, sx + 5, 7, recording ? UI_AMBER : UI_ACCENT, false);
	}

	private static final int WARP = 0xFFC266FF;

	/** The 12 edges of the target block, projected onto the screen. */
	private static void blockOutline(Minecraft client, GuiGraphicsExtractor g, Routes.Point point, int color) {
		float[][] c = new float[8][];
		for (int i = 0; i < 8; i++) {
			c[i] = project(client, new Vec3(point.x + (i & 1), point.y + (i >> 1 & 1), point.z + (i >> 2 & 1)));
			if (c[i] == null) return;
		}
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) screenLine(g, c[e[0]], c[e[1]], color);
	}

	private static void screenLine(GuiGraphicsExtractor g, float[] a, float[] b, int color) {
		float dx = b[0] - a[0], dy = b[1] - a[1];
		int steps = (int) Math.min(400, Math.max(Math.abs(dx), Math.abs(dy)));
		for (int i = 0; i <= steps; i++) {
			int x = Math.round(a[0] + dx * i / Math.max(1, steps));
			int y = Math.round(a[1] + dy * i / Math.max(1, steps));
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	/** Next to the crosshair: whether the player aims at the etherwarp block right now. */
	private static void drawWarpAim(Minecraft client, GuiGraphicsExtractor g, Routes.Point next) {
		if (!next.etherwarp()) return;
		double distance = next.pos().distanceTo(client.player.getEyePosition());
		var aimed = Routes.aimedBlock();
		boolean onTarget = aimed != null && aimed.getX() == (int) next.x && aimed.getY() == (int) next.y && aimed.getZ() == (int) next.z;
		String text = onTarget ? Planner.tr("route.warp_aimed") : distance > Routes.WARP_RANGE - 4 ? Planner.tr("route.warp_far", Math.round(distance)) : Planner.tr("route.warp_aim");
		int color = onTarget ? 0xFF8CD58B : distance > Routes.WARP_RANGE - 4 ? 0xFFE06C6C : WARP;
		int cx = client.getWindow().getGuiScaledWidth() / 2;
		int cy = client.getWindow().getGuiScaledHeight() / 2;
		if (onTarget) {
			g.fill(cx - 6, cy - 1, cx - 3, cy + 1, color);
			g.fill(cx + 4, cy - 1, cx + 7, cy + 1, color);
			g.fill(cx - 1, cy - 6, cx + 1, cy - 3, color);
			g.fill(cx - 1, cy + 4, cx + 1, cy + 7, color);
		}
		int w = client.font.width(text) + 8;
		round(g, cx - w / 2, cy - 30, w, 12, 4, alpha(UI_PANEL, 0.75));
		g.text(client.font, text, cx - w / 2 + 4, cy - 28, color, false);
	}

	private static void drawRouteArrow(Minecraft client, GuiGraphicsExtractor g, Vec3 player, Routes.Route route) {
		if (route.points.isEmpty()) return;
		Routes.Point next = route.points.get(Math.floorMod(Routes.target(), route.points.size()));
		arrowTo(client, g, player, next.x + 0.5, next.z + 0.5, next.pos().distanceTo(player), next.etherwarp() ? WARP : UI_AMBER);
	}

	/** A line on the ground drawn as dots, sampled in the world so that it follows perspective. */
	private static void dottedLine(Minecraft client, GuiGraphicsExtractor g, Vec3 from, Vec3 to, int color) {
		double length = from.distanceTo(to);
		int steps = (int) Math.min(80, Math.max(2, length * 1.5));

		for (int i = 0; i <= steps; i++) {
			Vec3 p = from.lerp(to, i / (double) steps);
			float[] at = project(client, p);
			if (at == null) continue;
			int size = at[2] < 8 ? 2 : 1;
			g.fill(Math.round(at[0]) - size, Math.round(at[1]) - size, Math.round(at[0]) + size, Math.round(at[1]) + size, color);
		}
	}

	private static void drawArrow(Minecraft client, GuiGraphicsExtractor g, Vec3 player) {
		TreeTracker.Spot target = null;
		double best = Double.MAX_VALUE;

		for (TreeTracker.Spot spot : TreeTracker.spots()) {
			if (spot.state != TreeTracker.State.READY) continue;
			double d = spot.center().distanceToSqr(player);
			if (d > 6 * 6 && d < best) { best = d; target = spot; }
		}

		if (target == null) return;
		arrowTo(client, g, player, target.cx, target.cz, Math.sqrt(best), READY);
	}

	private static void arrowTo(Minecraft client, GuiGraphicsExtractor g, Vec3 player, double tx, double tz, double distance, int color) {
		double dx = tx - player.x;
		double dz = tz - player.z;
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double angle = Math.toRadians(net.minecraft.util.Mth.wrapDegrees(targetYaw - client.player.getYRot()));

		int cx = client.getWindow().getGuiScaledWidth() / 2;
		int cy = client.getWindow().getGuiScaledHeight() / 2 + 26;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate((float) angle);
		for (int row = 0; row < 7; row++) g.fill(-row, -6 + row, row + 1, -5 + row, alpha(color, 0.9));
		g.fill(-1, 1, 2, 6, alpha(color, 0.9));
		pose.popMatrix();

		String text = Math.round(distance) + "m";
		g.text(client.font, text, cx - client.font.width(text) / 2, cy + 9, alpha(UI_TEXT, 0.9), true);
	}

	private static void drawSweep(Minecraft client, GuiGraphicsExtractor g) {
		TreeTracker.Spot spot = TreeTracker.looked();
		GameData.Tree tree = TreeTracker.data(spot);
		if (tree == null || !SkyBlockReader.statsKnown()) return;

		double sweep = SkyBlockReader.stats().sweep();
		int logs = TreeMath.logsPerHit(tree, sweep);
		double missing = TreeMath.sweepToNextBreakpoint(tree, sweep);
		var font = client.font;
		String first = Planner.tr("tree.sweep_logs", tree.name, logs);
		String second = missing < 0 ? Planner.tr("tree.sweep_max") : Planner.tr("tree.sweep_next", Planner.decimal(Math.ceil(missing * 10) / 10), logs + 1);
		int w = Math.max(font.width(first), font.width(second)) + 10;
		int x = client.getWindow().getGuiScaledWidth() / 2 + 12;
		int y = client.getWindow().getGuiScaledHeight() / 2 - 12;
		round(g, x, y, w, 24, 5, alpha(UI_PANEL, 0.75));
		g.text(font, first, x + 5, y + 4, READY, false);
		g.text(font, second, x + 5, y + 14, missing >= 0 && missing <= 5 ? UI_AMBER : UI_MUTED, false);
	}
}
