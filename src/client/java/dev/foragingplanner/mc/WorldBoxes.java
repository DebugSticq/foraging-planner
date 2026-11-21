package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Coloured boxes around blocks in the world, for the Sweep and tree order overlays. The lists are
 * filled on the client tick and drawn every frame.
 */
public final class WorldBoxes {
	/** One box: fill and outline colours as ARGB (alpha 0 skips that part). */
	public record Box(BlockPos pos, int fill, int line, float width, float grow) {}

	private static volatile List<Box> sweep = List.of();
	private static volatile List<Box> order = List.of();
	private static volatile List<Box> trees = List.of();

	/** Shapes added for one frame only (lines and small cubes of the HUD overlays). */
	private record Cube(double x0, double y0, double z0, double x1, double y1, double z1, int fill, int line, float width) {}
	private record Segment(Vec3 a, Vec3 b, int color, float width) {}
	private static List<Cube> cubes = new ArrayList<>();
	private static List<Segment> segments = new ArrayList<>();

	static void cube(double x0, double y0, double z0, double x1, double y1, double z1, int fill, int line, float width) {
		cubes.add(new Cube(x0, y0, z0, x1, y1, z1, fill, line, width));
	}

	static void segment(Vec3 a, Vec3 b, int color, float width) {
		if (a != null && b != null && (color >>> 24) >= 8) segments.add(new Segment(a, b, color, width));
	}

	private WorldBoxes() {}

	public static void setSweep(List<Box> boxes) { sweep = boxes; }
	public static void setOrder(List<Box> boxes) { order = boxes; }
	public static void setTrees(List<Box> boxes) { trees = boxes; }

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldBoxes::submit);
	}

	private static void submit(LevelRenderContext context) {
		List<Cube> frameCubes = cubes;
		List<Segment> frameSegments = segments;
		cubes = new ArrayList<>();
		segments = new ArrayList<>();
		WorldText.submit(context);
		List<Cube> all = new ArrayList<>(trees.size() + order.size() + sweep.size() + frameCubes.size());
		for (List<Box> list : List.of(trees, order, sweep)) {
			for (Box b : list) {
				float g = b.grow();
				all.add(new Cube(b.pos().getX() - g, b.pos().getY() - g, b.pos().getZ() - g,
						b.pos().getX() + 1 + g, b.pos().getY() + 1 + g, b.pos().getZ() + 1 + g, b.fill(), b.line(), b.width()));
			}
		}
		all.addAll(frameCubes);
		if (all.isEmpty() && frameSegments.isEmpty()) return;
		var camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		Vec3 cam = camera.pos;
		PoseStack poses = context.poseStack();
		var collector = context.submitNodeCollector();
		collector.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, out) -> {
			for (Cube box : all) if ((box.fill() >>> 24) != 0) fill(pose, out, box, cam);
		});
		collector.submitCustomGeometry(poses, RenderTypes.lines(), (pose, out) -> {
			for (Cube box : all) if ((box.line() >>> 24) != 0) outline(pose, out, box, cam);
			for (Segment seg : frameSegments) {
				line(pose, out, seg.color(), seg.width(), (float) (seg.a().x - cam.x), (float) (seg.a().y - cam.y), (float) (seg.a().z - cam.z),
						(float) (seg.b().x - cam.x), (float) (seg.b().y - cam.y), (float) (seg.b().z - cam.z));
			}
		});
	}

	private static float[] corners(Cube box, Vec3 cam) {
		return new float[] {
				(float) (box.x0() - cam.x), (float) (box.y0() - cam.y), (float) (box.z0() - cam.z),
				(float) (box.x1() - cam.x), (float) (box.y1() - cam.y), (float) (box.z1() - cam.z)};
	}

	private static void fill(PoseStack.Pose pose, VertexConsumer out, Cube box, Vec3 cam) {
		float[] c = corners(box, cam);
		float x0 = c[0], y0 = c[1], z0 = c[2], x1 = c[3], y1 = c[4], z1 = c[5];
		int col = box.fill();
		quad(pose, out, col, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(pose, out, col, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		quad(pose, out, col, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		quad(pose, out, col, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(pose, out, col, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(pose, out, col, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer out, int color, float... p) {
		for (int i = 0; i < 12; i += 3) out.addVertex(pose, p[i], p[i + 1], p[i + 2]).setColor(color);
	}

	private static void outline(PoseStack.Pose pose, VertexConsumer out, Cube box, Vec3 cam) {
		float[] c = corners(box, cam);
		float[] xs = {c[0], c[3]}, ys = {c[1], c[4]}, zs = {c[2], c[5]};
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			line(pose, out, box, xs[0], ys[a], zs[b], xs[1], ys[a], zs[b]);
			line(pose, out, box, xs[a], ys[0], zs[b], xs[a], ys[1], zs[b]);
			line(pose, out, box, xs[a], ys[b], zs[0], xs[a], ys[b], zs[1]);
		}
	}

	private static void line(PoseStack.Pose pose, VertexConsumer out, Cube box, float x0, float y0, float z0, float x1, float y1, float z1) {
		line(pose, out, box.line(), box.width(), x0, y0, z0, x1, y1, z1);
	}

	private static void line(PoseStack.Pose pose, VertexConsumer out, int color, float width, float x0, float y0, float z0, float x1, float y1, float z1) {
		float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) { nx /= len; ny /= len; nz /= len; }
		out.addVertex(pose, x0, y0, z0).setNormal(pose, nx, ny, nz).setColor(color).setLineWidth(width);
		out.addVertex(pose, x1, y1, z1).setNormal(pose, nx, ny, nz).setColor(color).setLineWidth(width);
	}
}
