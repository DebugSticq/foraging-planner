package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The seven hidden bells of the Critter Safari. Ringing all of them and talking to "Hunter" Tobias
 * plays a cutscene and gives Rainbug Shards. The mod marks every bell, remembers which ones were
 * rung and plans the shortest way through the rest.
 */
public final class Bells {
	/** A bell: where it is and a short hint how to reach it. */
	public record Bell(String key, BlockPos pos, String hint) {}

	/** Known places (community list). Exact positions are corrected when a bell block is seen. */
	private static final int[][] KNOWN = {
			{-3, 96, -41}, {-29, 125, 59}, {47, 55, -6}, {-89, 109, 16}, {-67, 65, -42}, {-95, 46, -56}, {-49, 81, 0}};
	private static final BlockPos[] EXACT = new BlockPos[KNOWN.length];

	private static List<Bell> route = List.of();
	private static long routeAt;
	private static long scanAt;
	private static boolean allDoneShown;

	private Bells() {}

	public static List<Bell> all() {
		List<Bell> out = new ArrayList<>();
		for (int i = 0; i < KNOWN.length; i++) {
			BlockPos pos = EXACT[i] != null ? EXACT[i] : new BlockPos(KNOWN[i][0], KNOWN[i][1], KNOWN[i][2]);
			out.add(new Bell(String.valueOf(i), pos, Planner.tr("bell.hint." + i)));
		}
		for (String found : Progress.get().safariBellsFound) {
			String[] p = found.split(",");
			try {
				out.add(new Bell(found, new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), Planner.tr("bell.hint.found")));
			} catch (RuntimeException ignored) {
				// A broken entry is skipped.
			}
		}
		return out;
	}

	public static boolean rung(Bell bell) {
		return Progress.get().safariBellsRung.contains(bell.key());
	}

	public static int rungCount() {
		int n = 0;
		for (Bell b : all()) if (rung(b)) n++;
		return n;
	}

	/** Called by the sound hook for every sound. A bell sound right next to a bell means it was rung. */
	public static void sound(String id, double x, double y, double z) {
		if (!id.contains("bell") || !Safari.inside()) return;
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		Vec3 at = new Vec3(x, y, z);
		if (at.distanceTo(player.position()) > 10) return;
		Bell nearest = nearest(at, 8);
		if (nearest == null) {
			// A bell that is not on the list: remember it.
			BlockPos pos = BlockPos.containing(x, y, z);
			markFound(pos);
			nearest = nearest(at, 3);
		}
		if (nearest != null) setRung(nearest, true);
	}

	/** A chat line from the Safari; some bells may answer in chat instead of with a sound. */
	public static void chat(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (!lower.contains("bell") || !(lower.contains("rang") || lower.contains("ring") || lower.contains("ding") || lower.contains("found"))) return;
		var player = Minecraft.getInstance().player;
		if (player == null || !Safari.inside()) return;
		Bell nearest = nearest(player.position(), 10);
		if (nearest != null) setRung(nearest, true);
	}

	public static void setRung(Bell bell, boolean value) {
		var rung = Progress.get().safariBellsRung;
		boolean changed = value ? rung.add(bell.key()) : rung.remove(bell.key());
		if (!changed) return;
		Progress.changed();
		routeAt = 0;
		if (!value) return;
		int done = rungCount(), total = all().size();
		Toasts.show(Toasts.Kind.MILESTONE, "bell", Planner.tr("bell.rung_title", done, total), done >= total ? Planner.tr("bell.all_text") : Planner.tr("bell.rung_text", total - done));
		if (done >= total && !allDoneShown) {
			allDoneShown = true;
			var client = Minecraft.getInstance();
			client.gui.hud.setTitle(net.minecraft.network.chat.Component.literal("§e" + Planner.tr("bell.all_title")));
		}
	}

	public static void reset() {
		Progress.get().safariBellsRung.clear();
		Progress.changed();
		allDoneShown = false;
		routeAt = 0;
	}

	private static void markFound(BlockPos pos) {
		for (Bell b : all()) if (b.pos().distSqr(pos) < 9) return;
		Progress.get().safariBellsFound.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
		Progress.changed();
		Safari.debug("new bell at " + pos.toShortString());
	}

	private static Bell nearest(Vec3 at, double max) {
		Bell best = null;
		double bestD = max * max;
		for (Bell b : all()) {
			double d = Vec3.atCenterOf(b.pos()).distanceToSqr(at);
			if (d <= bestD) {
				bestD = d;
				best = b;
			}
		}
		return best;
	}

	// Every half second: fix exact positions and plan the route

	public static void tick(Minecraft client) {
		if (!Planner.config.safariBells || client.player == null || client.level == null || !Safari.inside()) return;
		long now = System.currentTimeMillis();
		Vec3 me = client.player.position();
		if (now - scanAt > 2_000) {
			scanAt = now;
			scan(client, me);
		}
		if (now - routeAt > 1_000) {
			routeAt = now;
			route = plan(me);
		}
	}

	/** Looks for bell blocks near the player and near the known places that are close. */
	private static void scan(Minecraft client, Vec3 me) {
		BlockPos center = BlockPos.containing(me);
		int r = 8;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (!client.level.getBlockState(pos).is(Blocks.BELL)) continue;
			BlockPos found = pos.immutable();
			boolean known = false;
			for (int i = 0; i < KNOWN.length; i++) {
				BlockPos k = new BlockPos(KNOWN[i][0], KNOWN[i][1], KNOWN[i][2]);
				if (k.distSqr(found) <= 8 * 8) {
					EXACT[i] = found;
					known = true;
				}
			}
			if (!known) markFound(found);
		}
	}

	/** The shortest order through the bells not rung yet (exact for up to 8, greedy above). */
	private static List<Bell> plan(Vec3 from) {
		List<Bell> left = new ArrayList<>();
		for (Bell b : all()) if (!rung(b)) left.add(b);
		if (left.size() <= 1) return left;
		if (left.size() > 8) return greedy(from, left);
		int n = left.size();
		int[] order = new int[n];
		for (int i = 0; i < n; i++) order[i] = i;
		int[] best = order.clone();
		double[] bestCost = {Double.MAX_VALUE};
		permute(order, 0, from, left, best, bestCost);
		List<Bell> out = new ArrayList<>();
		for (int i : best) out.add(left.get(i));
		return out;
	}

	private static void permute(int[] order, int k, Vec3 from, List<Bell> bells, int[] best, double[] bestCost) {
		if (k == order.length) {
			double cost = 0;
			Vec3 at = from;
			for (int i : order) {
				Vec3 next = Vec3.atCenterOf(bells.get(i).pos());
				cost += cost(at, next);
				at = next;
				if (cost >= bestCost[0]) return;
			}
			bestCost[0] = cost;
			System.arraycopy(order, 0, best, 0, order.length);
			return;
		}
		for (int i = k; i < order.length; i++) {
			int t = order[k]; order[k] = order[i]; order[i] = t;
			permute(order, k + 1, from, bells, best, bestCost);
			t = order[k]; order[k] = order[i]; order[i] = t;
		}
	}

	private static List<Bell> greedy(Vec3 from, List<Bell> left) {
		List<Bell> rest = new ArrayList<>(left), out = new ArrayList<>();
		Vec3 at = from;
		while (!rest.isEmpty()) {
			Bell next = rest.getFirst();
			for (Bell b : rest) if (cost(at, Vec3.atCenterOf(b.pos())) < cost(at, Vec3.atCenterOf(next.pos()))) next = b;
			rest.remove(next);
			out.add(next);
			at = Vec3.atCenterOf(next.pos());
		}
		return out;
	}

	/** Walking cost: climbing counts more than flat distance. */
	private static double cost(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z, dy = b.y - a.y;
		return Math.sqrt(dx * dx + dz * dz) + (dy > 0 ? dy * 1.5 : -dy * 0.5);
	}

	public static List<Bell> route() {
		return route;
	}

	/** "/fp bells": every bell with its state and distance. */
	public static void list(java.util.function.Consumer<net.minecraft.network.chat.Component> out) {
		var player = Minecraft.getInstance().player;
		List<Bell> bells = all();
		out.accept(net.minecraft.network.chat.Component.literal("§a[FP] §f" + Planner.tr("bell.list_title", rungCount(), bells.size())));
		for (int i = 0; i < bells.size(); i++) {
			Bell b = bells.get(i);
			String dist = player == null ? "" : " §8" + Math.round(Vec3.atCenterOf(b.pos()).distanceTo(player.position())) + "m";
			out.accept(net.minecraft.network.chat.Component.literal((rung(b) ? " §a✔ " : " §e○ ") + "§7" + (i + 1) + ". §f" + b.hint()
					+ " §8(" + b.pos().getX() + " " + b.pos().getY() + " " + b.pos().getZ() + ")" + dist));
		}
		out.accept(net.minecraft.network.chat.Component.literal("§8" + Planner.tr("bell.list_help")));
	}

	/** "/fp bells toggle 3": marks a bell as rung or not by hand. */
	public static void toggle(String number, java.util.function.Consumer<net.minecraft.network.chat.Component> out) {
		List<Bell> bells = all();
		try {
			Bell b = bells.get(Integer.parseInt(number.trim()) - 1);
			setRung(b, !rung(b));
			out.accept(net.minecraft.network.chat.Component.literal("§a[FP] §7" + Planner.tr(rung(b) ? "bell.marked" : "bell.unmarked", number)));
		} catch (RuntimeException e) {
			out.accept(net.minecraft.network.chat.Component.literal("§c[FP] " + Planner.tr("bell.bad_number", bells.size())));
		}
	}

	// HUD and world

	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		var player = Minecraft.getInstance().player;
		if (!Planner.config.safariBells || player == null) return out;
		int done = rungCount(), total = all().size();
		out.add("§7" + Planner.tr("bell.hud") + " §e" + done + "§7/" + total);
		if (!route.isEmpty()) {
			Bell next = route.getFirst();
			out.add("§7→ §f" + next.hint() + " §8" + Math.round(Vec3.atCenterOf(next.pos()).distanceTo(player.position())) + "m");
		} else if (done >= total) {
			out.add("§a" + Planner.tr("bell.all_short"));
		}
		return out;
	}

	static void draw(Minecraft client, GuiGraphicsExtractor g, Vec3 player, long now) {
		if (!Planner.config.safariBells) return;
		var font = client.font;

		if (Planner.config.bellRoute && !route.isEmpty()) {
			Vec3 previous = player.add(0, 0.2, 0);
			for (int i = 0; i < route.size(); i++) {
				Vec3 next = Vec3.atCenterOf(route.get(i).pos());
				WorldOverlay.dottedLine(client, g, previous, next, alpha(0xFFFFD34D, Math.max(0.25, 0.85 - i * 0.12)));
				previous = next;
			}
			Bell first = route.getFirst();
			Vec3 target = Vec3.atCenterOf(first.pos());
			double distance = target.distanceTo(player);
			if (distance > 5) WorldOverlay.arrowTo(client, g, player, target.x, target.z, distance, 0xFFFFD34D);
		}

		List<Bell> bells = all();
		for (Bell bell : bells) {
			Vec3 at3 = Vec3.atCenterOf(bell.pos());
			double distance = at3.distanceTo(player);
			boolean done = rung(bell);
			if (done && distance > 40) continue;
			float[] at = WorldOverlay.project(client, at3.add(0, 1.2, 0));
			if (at == null) continue;
			int x = Math.round(at[0]), y = Math.round(at[1]);
			int index = route.indexOf(bell);
			boolean next = index == 0;
			int color = done ? 0xFF7FD48A : next ? 0xFFFFD34D : 0xFFFFE9A8;
			double pulse = next && Planner.config.animations ? 0.7 + 0.3 * Math.sin(now / 200.0) : 1;
			round(g, x - 5, y - 5, 10, 10, 5, alpha(color, (done ? 0.5 : 0.95) * pulse));
			round(g, x - 2, y - 2, 4, 4, 2, alpha(0xFFFFFFFF, 0.9));
			String label = done ? "✔ " + Planner.tr("bell.done") : (index >= 0 ? "#" + (index + 1) + " " : "") + Planner.tr("bell.name");
			String text = label + " · " + Math.round(distance) + "m";
			int tw = font.width(text);
			round(g, x - tw / 2 - 4, y - 19, tw + 8, 11, 4, alpha(UI_PANEL, 0.8));
			g.text(font, text, x - tw / 2, y - 17, alpha(color, done ? 0.7 : 1), false);
			if (next && distance < 60) {
				String hint = bell.hint();
				int hw = font.width(hint);
				round(g, x - hw / 2 - 4, y + 8, hw + 8, 11, 4, alpha(UI_PANEL, 0.7));
				g.text(font, hint, x - hw / 2, y + 10, 0xFFDDDDDD, false);
			}
		}
	}

	/** Bell dots and the route for the minimap. */
	public static void drawMap(GuiGraphicsExtractor g, int cx, int cy, double scale, Vec3 me) {
		if (!Planner.config.safariBells) return;
		Vec3 prev = me;
		if (Planner.config.bellRoute) {
			for (Bell b : route) {
				Vec3 p = Vec3.atCenterOf(b.pos());
				MiniMap.line(g, cx + (prev.x - me.x) * scale, cy + (prev.z - me.z) * scale, cx + (p.x - me.x) * scale, cy + (p.z - me.z) * scale, 0xAAFFD34D);
				prev = p;
			}
		}
		for (Bell b : all()) {
			Vec3 p = Vec3.atCenterOf(b.pos());
			boolean next = !route.isEmpty() && route.getFirst().equals(b);
			MiniMap.dot(g, cx + (p.x - me.x) * scale, cy + (p.z - me.z) * scale, next ? 3 : 2, rung(b) ? 0xFF7FD48A : next ? 0xFFFFFFFF : 0xFFFFD34D);
		}
	}
}
