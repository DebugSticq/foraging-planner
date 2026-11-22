package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/** Everything drawn on top of the map, shared by the minimap and the full screen map. */
final class MapMarkers {
	/** A marker that can show a name when the mouse is over it. */
	record Hit(double x, double y, String text) {}

	/** Marker types the player can switch on and off, in the order of the filter buttons. */
	static final String[] FILTERS = {"trees", "area", "npc", "poi", "honeyhive", "fairy_soul", "fishing", "protected", "resources"};

	private MapMarkers() {}

	static boolean shown(String filter) {
		return !Planner.config.mapHidden.contains(filter);
	}

	static int color(String type) {
		return switch (type) {
			case "npc" -> 0xFFFFFFFF;
			case "poi", "place", "warp", "jump_pad" -> 0xFF7FC8FF;
			case "honeyhive" -> 0xFFFFB347;
			case "fairy_soul" -> 0xFFFF7FE0;
			case "fishing" -> 0xFF4FA3FF;
			case "protected" -> 0xFFFF5555;
			default -> 0xFFDDDDDD;
		};
	}

	private static String filterOf(String type) {
		return switch (type) {
			case "place", "warp", "jump_pad" -> "poi";
			default -> type;
		};
	}

	/**
	 * Draws markers with world point (cx, cz) at screen (sx, sy). {@code big} adds names and area
	 * labels. Returns the markers the mouse can point at.
	 */
	static List<Hit> draw(GuiGraphicsExtractor g, double cx, double cz, double sx, double sy, double scale, boolean big) {
		Minecraft client = Minecraft.getInstance();
		List<Hit> hits = new ArrayList<>();
		String area = SkyBlockReader.area();
		var font = client.font;

		// Area names first, so everything else is drawn over them.
		if (big && shown("area")) {
			for (MapPoints.Point p : MapPoints.forArea(area)) {
				if (!"area".equals(p.type)) continue;
				int x = (int) Math.round(sx + (p.x + 0.5 - cx) * scale), y = (int) Math.round(sy + (p.z + 0.5 - cz) * scale);
				int w = font.width(p.name);
				g.text(font, p.name, x - w / 2, y - 4, 0xCCFFFFFF, true);
			}
		}

		// Route
		Routes.Route route = Routes.recording() != null ? Routes.recording() : Routes.active();
		if (route != null && route.points.size() > 1) {
			for (int i = 0; i < route.points.size() - (route.loop ? 0 : 1); i++) {
				Vec3 a = route.points.get(i).pos(), b = route.points.get((i + 1) % route.points.size()).pos();
				MiniMap.line(g, sx + (a.x - cx) * scale, sy + (a.z - cz) * scale, sx + (b.x - cx) * scale, sy + (b.z - cz) * scale, 0xCC86BD6E);
			}
		}

		// Places: NPCs, Honeyhives, fairy souls, ...
		for (MapPoints.Point p : MapPoints.forArea(area)) {
			if ("area".equals(p.type) || !shown(filterOf(p.type))) continue;
			double x = sx + (p.x + 0.5 - cx) * scale, y = sy + (p.z + 0.5 - cz) * scale;
			int r = big ? 2 : 1;
			MiniMap.dot(g, x, y, r, 0xFF000000);
			MiniMap.dot(g, x, y, Math.max(1, r - 1), color(p.type));
			hits.add(new Hit(x, y, p.name));
			if (big && scale >= 3 && "npc".equals(p.type)) g.text(font, p.name, (int) x + 4, (int) y - 4, 0xAAFFFFFF, true);
		}

		// Ruby Veilshroom and full Honeyhives found nearby
		if (shown("resources")) {
			for (TorrhusScanner.Found f : TorrhusScanner.found()) {
				double x = sx + (f.pos().getX() + 0.5 - cx) * scale, y = sy + (f.pos().getZ() + 0.5 - cz) * scale;
				MiniMap.dot(g, x, y, big ? 2 : 1, f.color());
				hits.add(new Hit(x, y, f.name()));
			}
		}

		// Trees: known positions, then what the tracker knows about each.
		if (shown("trees")) {
			TreeTracker.Spot best = TreeChoice.best();
			for (KnownTrees.Entry e : KnownTrees.forArea(area)) {
				double x = sx + (e.x + 0.5 - cx) * scale, y = sy + (e.z + 0.5 - cz) * scale;
				MiniMap.dot(g, x, y, big ? 2 : 1, 0x88B0B0B0);
			}
			for (TreeTracker.Spot spot : TreeTracker.spots()) {
				double x = sx + (spot.cx - cx) * scale, y = sy + (spot.cz - cz) * scale;
				int color = switch (spot.state) {
					case READY -> 0xFF8CD58B;
					case CUTTING -> ForestTheme.UI_AMBER;
					default -> 0xFF6E7672;
				};
				int r = (spot == best ? 4 : 2) + (big ? 1 : 0);
				MiniMap.dot(g, x, y, r + 1, 0xFF000000);
				MiniMap.dot(g, x, y, r, spot == best ? 0xFFFFD34D : color);
				String name = (spot.tree == null || spot.tree.isEmpty() ? Planner.tr("map.tree") : Planner.tr("tree_order.tree_" + spot.tree))
						+ " · " + Planner.tr("map.state_" + spot.state.name().toLowerCase(java.util.Locale.ROOT));
				String colours = HelixProgress.text(spot);
				hits.add(new Hit(x, y, colours.isEmpty() ? name : name + " · " + colours));
			}
		}

		// Marked mobs and critters, Safari
		for (Extras.Mark mark : Extras.marks()) {
			double x = sx + (mark.pos().x - cx) * scale, y = sy + (mark.pos().z - cz) * scale;
			MiniMap.dot(g, x, y, 2, mark.color());
			hits.add(new Hit(x, y, mark.name()));
		}
		if (Safari.inside() || Safari.nearSafari()) {
			for (Safari.Mark mark : Safari.marks()) {
				double x = sx + (mark.pos().x - cx) * scale, y = sy + (mark.pos().z - cz) * scale;
				MiniMap.dot(g, x, y, mark.alert() ? 3 : 1, mark.color());
				hits.add(new Hit(x, y, mark.name()));
			}
		}
		if (client.player != null && Safari.inside()) {
			Vec3 me = client.player.position();
			Bells.drawMap(g, (int) Math.round(sx + (me.x - cx) * scale), (int) Math.round(sy + (me.z - cz) * scale), scale, me);
		}

		// The player: an arrow pointing where they look.
		if (client.player != null) {
			Vec3 me = client.player.position();
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate((float) (sx + (me.x - cx) * scale), (float) (sy + (me.z - cz) * scale));
			pose.rotate((float) Math.toRadians(client.player.getYRot() + 180));
			float s = big ? 1.5f : 1f;
			pose.scale(s, s);
			for (int i = 0; i < 7; i++) g.fill(-i / 2 - 2, -5 + i, i / 2 + 2, -4 + i, 0xFF000000);
			for (int i = 0; i < 6; i++) g.fill(-i / 2 - 1, -4 + i, i / 2 + 1, -3 + i, 0xFFFFFFFF);
			pose.popMatrix();
		}
		return hits;
	}

	/** The marker nearest to the mouse, within a few pixels, or null. */
	static Hit nearest(List<Hit> hits, double mx, double my) {
		Hit best = null;
		double bestD = 7 * 7;
		for (Hit h : hits) {
			double d = (h.x() - mx) * (h.x() - mx) + (h.y() - my) * (h.y() - my);
			if (d < bestD) {
				bestD = d;
				best = h;
			}
		}
		return best;
	}
}
