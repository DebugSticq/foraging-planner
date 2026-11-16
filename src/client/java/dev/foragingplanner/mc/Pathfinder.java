package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A walking path over the blocks the client knows: steps up one block, falls up to four, swims,
 * climbs ladders and vines, and never cuts corners. It runs a little every tick so the game does
 * not stutter, and when the goal is not reachable (or not loaded yet) it returns the way to the
 * closest point it found.
 */
public final class Pathfinder {
	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	/** Time per tick the search may use: 2 ms, or 0.6 ms in light mode. */
	private static long budgetNs() {
		return Perf.light() ? 600_000L : 2_000_000L;
	}
	private static final int MAX_NODES = 90_000;
	private static final int MAX_FALL = 4;

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		Node parent;
		double g, f;
		boolean closed, seen;

		Node(BlockPos pos) {
			this.pos = pos;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(f, o.f);
		}
	}

	private final ClientLevel level;
	private final BlockPos goal;
	private final PriorityQueue<Node> open = new PriorityQueue<>();
	private final Map<Long, Node> nodes = new HashMap<>();
	private Node best;
	private double bestH = Double.MAX_VALUE;
	private int expanded;
	private boolean done, reached;
	private List<BlockPos> result = List.of();

	public Pathfinder(ClientLevel level, BlockPos start, BlockPos goal) {
		this.level = level;
		this.goal = goal;
		BlockPos s = ground(start);
		Node n = new Node(s);
		n.f = h(s);
		n.seen = true;
		nodes.put(s.asLong(), n);
		open.add(n);
		best = n;
	}

	public boolean done() {
		return done;
	}

	/** True when the path ends at the goal, false when it only gets as close as possible. */
	public boolean reached() {
		return reached;
	}

	public BlockPos goal() {
		return goal;
	}

	public List<BlockPos> result() {
		return result;
	}

	/** Works for one tick. */
	public void step() {
		if (done) return;
		long until = System.nanoTime() + budgetNs();
		int count = 0;
		while ((++count & 63) != 0 || System.nanoTime() < until) {
			Node current = open.poll();
			if (current == null || expanded > MAX_NODES) {
				finish(best, false);
				return;
			}
			if (current.closed) continue;
			current.closed = true;
			expanded++;
			double hc = h(current.pos);
			if (hc < bestH) {
				bestH = hc;
				best = current;
			}
			if (atGoal(current.pos)) {
				finish(current, true);
				return;
			}
			for (BlockPos next : neighbours(current.pos)) {
				double cost = current.g + moveCost(current.pos, next);
				Node n = nodes.computeIfAbsent(next.asLong(), k -> new Node(next));
				if (n.closed || n.seen && cost >= n.g) continue;
				n.seen = true;
				n.parent = current;
				n.g = cost;
				n.f = cost + h(next) * 1.4;
				open.add(n);
			}
		}
	}

	private void finish(Node end, boolean ok) {
		done = true;
		reached = ok;
		List<BlockPos> path = new ArrayList<>();
		for (Node n = end; n != null; n = n.parent) path.addFirst(n.pos);
		result = path;
	}

	private boolean atGoal(BlockPos p) {
		int dx = p.getX() - goal.getX(), dz = p.getZ() - goal.getZ(), dy = p.getY() - goal.getY();
		return dx * dx + dz * dz <= 5 && dy >= -3 && dy <= 2;
	}

	private double h(BlockPos p) {
		int dx = Math.abs(p.getX() - goal.getX()), dz = Math.abs(p.getZ() - goal.getZ()), dy = goal.getY() - p.getY();
		double flat = Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
		return flat + (dy > 0 ? dy : -dy * 0.3);
	}

	private double moveCost(BlockPos a, BlockPos b) {
		boolean diagonal = a.getX() != b.getX() && a.getZ() != b.getZ();
		double cost = diagonal ? 1.414 : 1;
		int dy = b.getY() - a.getY();
		if (dy > 0) cost += 0.8 * dy;
		else if (dy < 0) cost += 0.15 * -dy;
		if (level.getFluidState(b).is(FluidTags.WATER)) cost += 1.5;
		return cost;
	}

	private List<BlockPos> neighbours(BlockPos p) {
		List<BlockPos> out = new ArrayList<>(10);
		boolean headroom = free(p.above(2));
		for (int[] d : DIRS) {
			boolean diagonal = d[0] != 0 && d[1] != 0;
			if (diagonal && (!passable(p.offset(d[0], 0, 0)) || !passable(p.offset(0, 0, d[1])))) continue;
			BlockPos side = p.offset(d[0], 0, d[1]);
			if (!loaded(side)) continue;
			if (standable(side)) {
				out.add(side);
				continue;
			}
			// One block up: needs room to jump.
			BlockPos up = side.above();
			if (headroom && !diagonal && standable(up)) {
				out.add(up);
				continue;
			}
			// Down: walk off the edge and fall a few blocks.
			if (!passable(side)) continue;
			for (int fall = 1; fall <= MAX_FALL; fall++) {
				BlockPos down = side.below(fall);
				if (!free(down)) break;
				if (standable(down)) {
					out.add(down);
					break;
				}
			}
		}
		// Ladders, vines and water let you go straight up or down.
		if (climbable(p) || water(p)) {
			if (free(p.above()) && free(p.above(2)) || climbable(p.above()) || water(p.above())) out.add(p.above());
		}
		if (climbable(p.below()) || water(p.below())) out.add(p.below());
		return out;
	}

	private boolean loaded(BlockPos p) {
		return level.hasChunk(p.getX() >> 4, p.getZ() >> 4);
	}

	private boolean standable(BlockPos p) {
		if (!free(p) || !free(p.above())) return false;
		return solid(p.below()) || climbable(p) || water(p);
	}

	/** Feet and head both fit. */
	private boolean passable(BlockPos p) {
		return free(p) && free(p.above());
	}

	private boolean free(BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.getFluidState().is(FluidTags.LAVA)) return false;
		return s.getCollisionShape(level, p).isEmpty() || climbable(s);
	}

	private boolean solid(BlockPos p) {
		BlockState s = level.getBlockState(p);
		return !s.getCollisionShape(level, p).isEmpty() && !s.getFluidState().is(FluidTags.LAVA);
	}

	private boolean climbable(BlockPos p) {
		return climbable(level.getBlockState(p));
	}

	private static boolean climbable(BlockState s) {
		return s.is(BlockTags.CLIMBABLE);
	}

	private boolean water(BlockPos p) {
		return level.getFluidState(p).is(FluidTags.WATER);
	}

	/** The block the player stands in: moves down while in the air (jumping, falling). */
	private BlockPos ground(BlockPos p) {
		for (int i = 0; i < 6; i++) {
			if (standable(p)) return p;
			p = p.below();
		}
		return p.above(6);
	}
}
