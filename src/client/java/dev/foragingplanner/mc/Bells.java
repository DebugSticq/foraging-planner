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

	// Walking path to the next bell
	private static Pathfinder finder;
	private static List<BlockPos> path = List.of();
	private static boolean pathReached;
	private static BlockPos pathGoal;
	private static long pathAt;
	private static int pathStart;
	private static final java.util.Set<Long> HIDDEN = new java.util.HashSet<>();
	private static int ticks;

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

	/** Every tick: works on the walking path to the next bell a little. */
	public static void pathTick(Minecraft client) {
		if (!Planner.config.safariBells || !Planner.config.bellRoute || client.player == null || client.level == null || !Safari.inside() || route.isEmpty()) {
			finder = null;
			path = List.of();
			pathGoal = null;
			return;
		}
		long now = System.currentTimeMillis();
		BlockPos goal = route.getFirst().pos();
		Vec3 me = client.player.position();

		if (finder != null) {
			finder.step();
			if (finder.done()) {
				path = finder.result();
				pathReached = finder.reached();
				pathGoal = finder.goal();
				pathAt = now;
				finder = null;
			}
		}

		pathStart = nearestIndex(me);
		boolean offPath = !path.isEmpty() && Vec3.atBottomCenterOf(path.get(pathStart)).distanceTo(me) > 4.5;
		boolean stale = !pathReached && now - pathAt > 8_000;
		boolean otherGoal = pathGoal == null || !pathGoal.equals(goal);
		if (finder == null && (otherGoal || offPath || stale || path.isEmpty() && now - pathAt > 2_000)) {
			finder = new Pathfinder(client.level, client.player.blockPosition(), goal);
			if (otherGoal) path = List.of();
		}

		// Which parts of the path are behind blocks, four times a second.
		if (++ticks % 5 == 0) {
			HIDDEN.clear();
			for (int i = pathStart; i < path.size(); i++) {
				Vec3 at = Vec3.atBottomCenterOf(path.get(i)).add(0, 0.15, 0);
				if (at.distanceTo(me) > 56) break;
				if (!WorldOverlay.visible(client, at)) HIDDEN.add(path.get(i).asLong());
			}
		}
	}

	private static int nearestIndex(Vec3 me) {
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = Vec3.atBottomCenterOf(path.get(i)).distanceToSqr(me);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
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
			boolean walk = !path.isEmpty() && pathReached && route.getFirst().pos().equals(pathGoal);
			String way = walk ? "~" + Math.max(0, path.size() - pathStart) + "m " + Planner.tr("bell.walk") : Math.round(Vec3.atCenterOf(next.pos()).distanceTo(player.position())) + "m";
			out.add("§7→ §f" + next.hint() + " §8" + way);
		} else if (done >= total) {
			out.add("§a" + Planner.tr("bell.all_short"));
		}
		return out;
	}

	static void draw(Minecraft client, GuiGraphicsExtractor g, Vec3 player, long now) {
		if (!Planner.config.safariBells) return;
		var font = client.font;

		if (Planner.config.bellRoute && !route.isEmpty()) {
			Bell first = route.getFirst();
			Vec3 target = Vec3.atCenterOf(first.pos());
			boolean current = !path.isEmpty() && first.pos().equals(pathGoal);
			if (current) {
				drawPath(client, g, player, now);
				if (!pathReached) {
					// The path stops where the known world ends; the rest is a faint straight hint.
					WorldOverlay.dottedLine(client, g, Vec3.atBottomCenterOf(path.getLast()).add(0, 0.2, 0), target, alpha(0xFFFFD34D, 0.3));
				}
				int ahead = Math.min(path.size() - 1, pathStart + 6);
				Vec3 aim = Vec3.atBottomCenterOf(path.get(ahead));
				double left = Math.max(0, path.size() - pathStart) + 0.0;
				if (target.distanceTo(player) > 5) WorldOverlay.arrowTo(client, g, player, aim.x, aim.z, pathReached ? left : target.distanceTo(player), 0xFFFFD34D);
			} else if (target.distanceTo(player) > 5) {
				WorldOverlay.arrowTo(client, g, player, target.x, target.z, target.distanceTo(player), 0xFFFFD34D);
			}
		}

		List<Bell> bells = all();
		for (Bell bell : bells) {
			Vec3 at3 = Vec3.atCenterOf(bell.pos());
			double distance = at3.distanceTo(player);
			boolean done = rung(bell);
			if (done && distance > 40) continue;
			int index = route.indexOf(bell);
			boolean next = index == 0;
			int color = done ? 0xFF7FD48A : next ? 0xFFFFD34D : 0xFFFFE9A8;
			double pulse = next && Planner.config.animations ? 0.7 + 0.3 * Math.sin(now / 200.0) : 1;
			WorldText.dot(at3.add(0, 1.2, 0), alpha(color, (done ? 0.5 : 0.95) * pulse), 1.6f);
			String label = done ? "✔ " + Planner.tr("bell.done") : (index >= 0 ? "#" + (index + 1) + " " : "") + Planner.tr("bell.name");
			String text = label + " · " + Math.round(distance) + "m";
			if (next && distance < 60) text += "\n§7" + bell.hint();
			WorldText.label(at3.add(0, 1.85, 0), text, alpha(color, done ? 0.7 : 1));
		}
	}

	/** The walking path as a glowing line on the ground, with light running along it. */
	private static void drawPath(Minecraft client, GuiGraphicsExtractor g, Vec3 player, long now) {
		double along = 0;
		double flow = now / 1000.0 * 5;
		for (int i = pathStart; i < path.size() - 1; i++) {
			BlockPos pa = path.get(i), pb = path.get(i + 1);
			Vec3 a = Vec3.atBottomCenterOf(pa).add(0, 0.06, 0), b = Vec3.atBottomCenterOf(pb).add(0, 0.06, 0);
			double len = a.distanceTo(b);
			if (a.distanceTo(player) > 56) break;
			if (HIDDEN.contains(pa.asLong()) && HIDDEN.contains(pb.asLong())) {
				along += len;
				continue;
			}
			// A glowing line on the ground; a bright piece runs along it.
			double fade = Math.clamp(1.2 - a.distanceTo(player) / 56, 0.35, 1);
			WorldText.line(a, b, alpha(0xFFFFC23D, fade), 3f);
			double phase = ((along - flow) % 3 + 3) % 3;
			double from = (3 - phase) % 3;
			if (from < len) WorldText.line(a.lerp(b, from / len), a.lerp(b, Math.min(1, (from + 0.7) / len)), alpha(0xFFFFF4B8, fade), 5f);
			along += len;
		}
	}

	/** Bell dots and the route for the minimap. */
	public static void drawMap(GuiGraphicsExtractor g, int cx, int cy, double scale, Vec3 me) {
		if (!Planner.config.safariBells) return;
		Vec3 prev = me;
		if (Planner.config.bellRoute && !path.isEmpty()) {
			for (int i = pathStart; i < path.size() - 1; i++) {
				Vec3 a = Vec3.atBottomCenterOf(path.get(i)), b = Vec3.atBottomCenterOf(path.get(i + 1));
				MiniMap.line(g, cx + (a.x - me.x) * scale, cy + (a.z - me.z) * scale, cx + (b.x - me.x) * scale, cy + (b.z - me.z) * scale, 0xFFFFD34D);
			}
		}
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
