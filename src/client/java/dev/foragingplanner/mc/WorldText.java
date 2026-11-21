package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Text, dots, lines and small cubes placed in the 3D world, like name tags: they stay on their spot
 * when the camera turns and are hidden behind blocks. Overlays add them while the HUD is drawn; the
 * next world frame draws everything added since the frame before.
 */
public final class WorldText {
	/** Full brightness, so labels are readable at night and in caves. */
	private static final int LIGHT = 0xF000F0;

	/** One label; lines are separated by {@code \n}. */
	private record Label(Vec3 pos, String text, int color, int background, float scale, boolean seeThrough) {}

	private static List<Label> labels = new ArrayList<>();

	private WorldText() {}

	/** A label with the usual dark panel behind it. */
	public static void label(Vec3 pos, String text, int color) {
		add(pos, text, color, ForestTheme.alpha(ForestTheme.UI_PANEL, 0.75), 1f, false);
	}

	/** A label that shows through blocks, for warnings that must not be missed. */
	public static void warning(Vec3 pos, String text, int color) {
		add(pos, text, color, ForestTheme.alpha(0xFF3A0B0B, 0.85), 1.1f, true);
	}

	public static void add(Vec3 pos, String text, int color, int background, float scale, boolean seeThrough) {
		if (pos == null || text == null || text.isEmpty() || (color >>> 24) < 8) return;
		labels.add(new Label(pos, text, color, background, scale, seeThrough));
	}

	/** A round dot (a billboarded "●"). */
	public static void dot(Vec3 pos, int color, float size) {
		add(pos, "●", color, 0, size, false);
	}

	/** A straight line in the world. */
	public static void line(Vec3 from, Vec3 to, int color, float width) {
		WorldBoxes.segment(from, to, color, width);
	}

	/** A cube with sides {@code 2 * half} around a point. */
	public static void cube(Vec3 center, double half, int fill, int line, float width) {
		WorldBoxes.cube(center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half, fill, line, width);
	}

	static void submit(LevelRenderContext context) {
		List<Label> list = labels;
		labels = new ArrayList<>();
		if (list.isEmpty()) return;
		var camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null || camera.orientation == null) return;
		Vec3 cam = camera.pos;
		Font font = Minecraft.getInstance().font;
		PoseStack poses = context.poseStack();
		var collector = context.submitNodeCollector();
		for (Label label : list) {
			double distance = label.pos().distanceTo(cam);
			if (distance > 160) continue;
			// Grows with distance up to a point, so far labels stay readable without covering the screen up close.
			float s = 0.025f * label.scale() * (float) Math.max(1, Math.min(distance, 72) / 9.0);
			poses.pushPose();
			poses.translate(label.pos().x - cam.x, label.pos().y - cam.y, label.pos().z - cam.z);
			poses.rotate(camera.orientation);
			poses.scale(s, -s, s);
			String[] lines = label.text().split("\n");
			float y = -lines.length * 10 / 2f;
			Font.DisplayMode mode = label.seeThrough() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
			for (String line : lines) {
				var text = Component.literal(line).getVisualOrderText();
				float x = -font.width(text) / 2f;
				collector.submitText(poses, x, y, text, false, mode, LIGHT, label.color(), label.background(), 0);
				y += 10;
			}
			poses.popPose();
		}
	}
}
