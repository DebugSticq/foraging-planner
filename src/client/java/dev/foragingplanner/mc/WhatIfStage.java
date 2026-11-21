package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.util.Locale;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeMath;

/**
 * The animated preview on the what-if page: the player chops a tree with the current stats, and
 * next to it with the chosen changes. More logs per hit means fewer hits per tree and more logs
 * flying out; the numbers under each side are the same estimate as the summary row.
 */
final class WhatIfStage {
	/** One swing every half second. */
	private static final double SWING_S = 0.5;
	private static final double FALL_S = 0.9;
	private static final double REST_S = 0.7;
	/** The tree in the preview is as big as eight hits with the current stats. */
	private static final int BASE_HITS = 8;

	private WhatIfStage() {}

	static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, double a, long startNs, double[] totals) {
		round(g, x, y, w, h, 8, alpha(0xFF0F1A14, a));
		roundOutline(g, x, y, w, h, 8, alpha(0x553D6348, a));
		// Sky to grass, so the scene reads as outdoors.
		g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 22, alpha(0xFF16261D, a), alpha(0xFF1E3326, a));
		g.fill(x + 1, y + h - 22, x + w - 1, y + h - 1, alpha(0xFF24402C, a));
		g.fill(x + 1, y + h - 22, x + w - 1, y + h - 21, alpha(0xFF3F6B45, a));

		GameData.Tree tree = Why.farmedTree();
		if (tree == null) {
			g.centeredText(font, Planner.tr("gear.stage_no_tree"), x + w / 2, y + h / 2 - 4, alpha(UI_DIM, a));
			return;
		}
		PlayerStats stats = SkyBlockReader.stats();
		double before = Math.max(0.1, TreeMath.smoothLogsPerHit(tree, stats.sweep()));
		double after = Math.max(0.1, TreeMath.smoothLogsPerHit(tree, stats.sweep() + totals[0]));
		Why.Estimate e = Why.estimate(totals[0], totals[1], totals[2]);
		boolean changed = Math.abs(totals[0]) > 0.01 || Math.abs(totals[1]) > 0.01 || Math.abs(totals[2]) > 0.01;
		double size = before * BASE_HITS;
		double t = Planner.config.animations ? (System.nanoTime() - startNs) / 1e9 : 1.2;

		if (!changed) {
			side(g, font, tree, x, y, w, h, a, t, before, size, e.dropsBefore(), Planner.tr("gear.stage_now"), UI_TEXT);
			g.centeredText(font, Planner.tr("gear.stage_hint"), x + w / 2, y + h - 15, alpha(UI_MUTED, a));
			return;
		}
		int half = w / 2;
		side(g, font, tree, x, y, half, h, a, t, before, size, e.dropsBefore(), Planner.tr("gear.stage_now"), UI_TEXT);
		side(g, font, tree, x + half, y, w - half, h, a, t, after, size, e.dropsAfter(), Planner.tr("gear.stage_after"), UI_ACCENT);
		g.fill(x + half, y + 8, x + half + 1, y + h - 26, alpha(0x333D6348, a));
	}

	/** One chopping player and tree in the box x..x+w. */
	private static void side(GuiGraphicsExtractor g, Font font, GameData.Tree tree, int x, int y, int w, int h, double a,
							 double t, double logsPerHit, double size, double dropsPerHit, String label, int labelColor) {
		int hits = Math.max(1, (int) Math.ceil(size / logsPerHit - 1e-6));
		double chop = hits * SWING_S;
		double cycle = chop + FALL_S + REST_S;
		double inCycle = t % cycle;
		int felled = (int) (t / cycle);

		int ground = y + h - 22;
		int px = x + w / 2 - 34, treeX = x + w / 2 + 22;

		// The player, seen from the side, swinging while the tree stands.
		double swing = inCycle < chop ? Math.clamp((inCycle % SWING_S) / (SWING_S * 0.7), 0, 1) : 0;
		player(g, px - 22, ground - 64, px + 22, ground + 2, swing, a);

		// The tree: trunk of logs and a crown of leaves; it shakes on each hit and falls when done.
		double fall = inCycle < chop ? 0 : Math.clamp((inCycle - chop) / FALL_S, 0, 1);
		double regrow = inCycle >= chop + FALL_S ? Math.clamp((inCycle - chop - FALL_S) / REST_S, 0, 1) : -1;
		double shake = inCycle < chop ? Math.max(0, 1 - (inCycle % SWING_S) / 0.18) : 0;
		if (regrow >= 0) {
			drawTree(g, tree, treeX, ground, 0, 0, regrow * regrow, a);
		} else {
			drawTree(g, tree, treeX, ground, fall * fall * 1.45, Math.sin(shake * Math.PI * 3) * 1.6 * shake, 1, a * (1 - fall * 0.55));
		}

		// Logs flying out after each hit, with the drops per hit.
		if (inCycle < chop + 0.2) {
			int hit = (int) Math.min(hits - 1, inCycle / SWING_S);
			double since = inCycle - hit * SWING_S - SWING_S * 0.45;
			if (since > 0 && since < 0.75) {
				int pieces = (int) Math.clamp(Math.round(logsPerHit), 1, 7);
				Item log = logItem(tree);
				for (int i = 0; i < pieces; i++) {
					double ang = -Math.PI / 2 + (i - (pieces - 1) / 2.0) * 0.38;
					double dist = 10 + since * 34;
					double ix = treeX - 2 + Math.cos(ang) * dist * 0.8 - 6;
					double iy = ground - 22 + Math.sin(ang) * dist + since * since * 70;
					var pose = g.pose();
					pose.pushMatrix();
					pose.translate((float) ix, (float) iy);
					pose.scale(0.55f, 0.55f);
					g.item(new ItemStack(log), 0, 0);
					pose.popMatrix();
				}
				String plus = "+" + Planner.decimal(dropsPerHit);
				int ty = (int) (ground - 58 - since * 18);
				g.text(font, plus, treeX + 8, ty, alpha(UI_AMBER, a * (1 - since / 0.75)), true);
			}
		}

		// Remaining part of the tree as a thin bar, then the numbers.
		double left = inCycle < chop ? 1 - Math.min(hits, Math.floor(inCycle / SWING_S + 0.55)) / hits : 0;
		g.fill(treeX - 14, ground - 74, treeX + 14, ground - 72, alpha(0x33FFFFFF, a));
		g.fill(treeX - 14, ground - 74, treeX - 14 + (int) Math.round(28 * left), ground - 72, alpha(UI_ACCENT, a));

		g.text(font, label, x + 8, y + 6, alpha(labelColor, a), false);
		String perHit = Planner.tr("gear.stage_per_hit", Planner.decimal(logsPerHit), hits);
		g.text(font, perHit, x + 8, y + 17, alpha(UI_MUTED, a), false);
		String count = Planner.tr("gear.stage_felled", felled);
		g.text(font, count, x + w - 8 - font.width(count), y + 6, alpha(UI_DIM, a), false);
	}

	private static Item logItem(GameData.Tree tree) {
		String id = tree.bazaarId == null ? "" : tree.bazaarId.toUpperCase(Locale.ROOT);
		if (id.contains("MANGROVE")) return Items.MANGROVE_LOG;
		if (id.contains("FIG")) return Items.STRIPPED_SPRUCE_LOG;
		if (id.contains("HELIX")) return Items.STRIPPED_BIRCH_LOG;
		return Items.OAK_LOG;
	}

	private static Item leafItem(GameData.Tree tree) {
		String id = tree.bazaarId == null ? "" : tree.bazaarId.toUpperCase(Locale.ROOT);
		if (id.contains("MANGROVE")) return Items.MANGROVE_LEAVES;
		if (id.contains("FIG")) return Items.JUNGLE_LEAVES;
		if (id.contains("HELIX")) return Items.AZALEA_LEAVES;
		return Items.OAK_LEAVES;
	}

	/** A small tree of block items, rotated by {@code tilt} radians around its foot and grown by {@code grow}. */
	private static void drawTree(GuiGraphicsExtractor g, GameData.Tree tree, int cx, int ground, double tilt, double shakeX, double grow, double a) {
		if (a <= 0.02 || grow <= 0.02) return;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate((float) (cx + shakeX), ground);
		pose.rotate((float) tilt);
		pose.scale((float) grow, (float) grow);
		ItemStack log = new ItemStack(logItem(tree)), leaf = new ItemStack(leafItem(tree));
		int step = 9;
		for (int i = 0; i < 5; i++) g.item(log, -8, -16 - i * step);
		int top = -16 - 5 * step;
		int[][] crown = {{-18, 2}, {2, 2}, {-8, 0}, {-14, -8}, {-2, -8}, {-8, -15}, {-22, -4}, {6, -4}};
		for (int[] c : crown) g.item(leaf, c[0], top + c[1]);
		pose.popMatrix();
	}

	/** The local player in the box, facing the tree, with the arm at {@code swing} (0..1) of a chop. */
	private static void player(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, double swing, double a) {
		Minecraft mc = Minecraft.getInstance();
		LivingEntity player = mc.player;
		if (player == null || a < 0.4) return;
		try {
			EntityRenderState state = mc.getEntityRenderDispatcher().getRenderer(player).createRenderState(player, 1.0f);
			state.shadowPieces.clear();
			state.outlineColor = 0;
			float scale = 28;
			if (state instanceof LivingEntityRenderState living) {
				living.bodyRot = 180 - 62;
				living.yRot = -6;
				living.xRot = 14;
				living.walkAnimationSpeed = 0;
				living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
				living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
				living.scale = 1;
			}
			if (state instanceof ArmedEntityRenderState armed) {
				armed.currentSwing = new LivingEntity.SwingDescription(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, 6);
				armed.swingAnimation = (float) swing;
			}
			Quaternionf camera = new Quaternionf().rotateX((float) Math.toRadians(8));
			Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(camera);
			Vector3f offset = new Vector3f(0, state.boundingBoxHeight / 2 + 0.0625f, 0);
			g.entity(state, scale, offset, rotation, camera, x0, y0, x1, y1);
		} catch (RuntimeException e) {
			// Another mod's player renderer may not like this; the tree still shows.
		}
	}
}
