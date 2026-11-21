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

	private WorldBoxes() {}

	public static void setSweep(List<Box> boxes) { sweep = boxes; }
	public static void setOrder(List<Box> boxes) { order = boxes; }
	public static void setTrees(List<Box> boxes) { trees = boxes; }

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldBoxes::submit);
	}

	private static void submit(LevelRenderContext context) {
		List<Box> all = new ArrayList<>(trees.size() + order.size() + sweep.size());
		all.addAll(trees);
		all.addAll(order);
		all.addAll(sweep);
		if (all.isEmpty()) return;
		var camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		Vec3 cam = camera.pos;
		PoseStack poses = context.poseStack();
		var collector = context.submitNodeCollector();
		collector.submitCustomGeometry(poses, RenderTypes.debugQuads(), (pose, out) -> {
			for (Box box : all) if ((box.fill() >>> 24) != 0) fill(pose, out, box, cam);
		});
		collector.submitCustomGeometry(poses, RenderTypes.lines(), (pose, out) -> {
			for (Box box : all) if ((box.line() >>> 24) != 0) outline(pose, out, box, cam);
		});
	}

	private static float[] corners(Box box, Vec3 cam) {
		float g = box.grow();
		return new float[] {
				(float) (box.pos().getX() - cam.x) - g, (float) (box.pos().getY() - cam.y) - g, (float) (box.pos().getZ() - cam.z) - g,
				(float) (box.pos().getX() + 1 - cam.x) + g, (float) (box.pos().getY() + 1 - cam.y) + g, (float) (box.pos().getZ() + 1 - cam.z) + g};
	}

	private static void fill(PoseStack.Pose pose, VertexConsumer out, Box box, Vec3 cam) {
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

	private static void outline(PoseStack.Pose pose, VertexConsumer out, Box box, Vec3 cam) {
		float[] c = corners(box, cam);
		float[] xs = {c[0], c[3]}, ys = {c[1], c[4]}, zs = {c[2], c[5]};
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			line(pose, out, box, xs[0], ys[a], zs[b], xs[1], ys[a], zs[b]);
			line(pose, out, box, xs[a], ys[0], zs[b], xs[a], ys[1], zs[b]);
			line(pose, out, box, xs[a], ys[b], zs[0], xs[a], ys[b], zs[1]);
		}
	}

	private static void line(PoseStack.Pose pose, VertexConsumer out, Box box, float x0, float y0, float z0, float x1, float y1, float z1) {
		float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len > 0) { nx /= len; ny /= len; nz /= len; }
		out.addVertex(pose, x0, y0, z0).setNormal(pose, nx, ny, nz).setColor(box.line()).setLineWidth(box.width());
		out.addVertex(pose, x1, y1, z1).setNormal(pose, nx, ny, nz).setColor(box.line()).setLineWidth(box.width());
	}
}
