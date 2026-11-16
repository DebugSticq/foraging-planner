package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;

import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Warnings at the tree: protected trees (cutting or throwing an axe at them costs a fine) and trees
 * that are about to grow back where the player is standing. Also shows where a thrown axe is aimed.
 */
public final class TreeWarnings {
	/** Chat lines about a fine for a protected tree. */
	private static final Pattern FINE = Pattern.compile("(?i)(protected|protection order).*(tree|fine|coins)|fined .*coins");

	private TreeWarnings() {}

	public static void register() {
		ServerMessages.register((component, overlay) -> {
			if (overlay || !Planner.config.protectedTrees || !SkyBlockReader.inForagingArea()) return;
			String text = Planner.strip(component.getString());
			if (!FINE.matcher(text).find()) return;
			TreeTracker.Spot spot = aimedSpot(Minecraft.getInstance(), 40);
			if (spot == null) spot = nearest(Minecraft.getInstance());
			if (spot != null && TreeTracker.markProtected(spot)) {
				Toasts.show(Toasts.Kind.ALARM, "barrier", Planner.tr("protected.learned"), Planner.tr("protected.learned_text"));
			}
		});
	}

	/** Once a second: nametags near a tree that mention protection mark the tree as protected. */
	public static void scanNametags(Minecraft client) {
		if (!Planner.config.protectedTrees || client.player == null || client.level == null || !SkyBlockReader.inForagingArea()) return;
		for (var e : client.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, client.player.getBoundingBox().inflate(48))) {
			var name = e.getCustomName();
			if (name == null || !Planner.strip(name.getString()).toLowerCase(Locale.ROOT).contains("protect")) continue;
			for (TreeTracker.Spot spot : TreeTracker.spots()) {
				double dx = spot.cx - e.getX(), dz = spot.cz - e.getZ();
				if (dx * dx + dz * dz < 6 * 6 && Math.abs(spot.cy - e.getY()) < 16) TreeTracker.markProtected(spot);
			}
		}
	}

	/** The tree spot the crosshair points at, within {@code range} blocks. */
	public static TreeTracker.Spot aimedSpot(Minecraft client, double range) {
		if (client.player == null) return null;
		HitResult hit = client.player.pick(range, 0, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return null;
		Vec3 at = Vec3.atCenterOf(block.getBlockPos());
		TreeTracker.Spot best = null;
		double bestD = 6 * 6;
		for (TreeTracker.Spot spot : TreeTracker.spots()) {
			double dx = spot.cx - at.x, dz = spot.cz - at.z;
			double d = dx * dx + dz * dz;
			if (d < bestD && Math.abs(spot.cy - at.y) < 14) { bestD = d; best = spot; }
		}
		return best;
	}

	private static TreeTracker.Spot nearest(Minecraft client) {
		if (client.player == null) return null;
		TreeTracker.Spot best = null;
		double bestD = 12 * 12;
		for (TreeTracker.Spot spot : TreeTracker.spots()) {
			double d = spot.center().distanceToSqr(client.player.position());
			if (d < bestD) { bestD = d; best = spot; }
		}
		return best;
	}

	private static boolean holdingAxe(Minecraft client) {
		var stack = client.player.getMainHandItem();
		if (stack.is(net.minecraft.tags.ItemTags.AXES)) return true;
		String name = Planner.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		return name.contains("axe") || name.contains("chopper") || name.contains("treecapitator");
	}

	/** Called from the world overlay every frame. */
	public static void draw(Minecraft client, GuiGraphicsExtractor g, long now) {
		if (client.player == null || !SkyBlockReader.inForagingArea()) return;
		var font = client.font;
		int sw = client.getWindow().getGuiScaledWidth(), sh = client.getWindow().getGuiScaledHeight();
		String warning = null;
		int color = 0xFFFF5555;

		// Aim: what a thrown axe would hit, and whether that tree is protected.
		if (Planner.config.protectedTrees && holdingAxe(client)) {
			TreeTracker.Spot aimed = aimedSpot(client, 40);
			if (aimed != null && TreeTracker.isProtected(aimed)) warning = "⚠ " + Planner.tr("protected.aim");
		}

		// Regrowing tree around the player.
		if (warning == null && Planner.config.regrowWarning) {
			Vec3 me = client.player.position();
			for (TreeTracker.Spot spot : TreeTracker.spots()) {
				if (spot.state == TreeTracker.State.READY || spot.cutAt <= 0) continue;
				long regrow = spot.regrow();
				if (regrow <= 0) continue;
				long eta = spot.cutAt + regrow - System.currentTimeMillis();
				double dx = spot.cx - me.x, dz = spot.cz - me.z;
				if (eta > 0 && eta < 6000 && dx * dx + dz * dz < 3.5 * 3.5 && me.y >= spot.y - 2 && me.y <= spot.y + 14) {
					warning = Planner.tr("regrow.warning", (eta + 999) / 1000);
					color = 0xFFFFAA00;
					break;
				}
			}
		}
		if (warning == null) return;
		double pulse = Planner.config.animations ? 0.75 + 0.25 * Math.sin(now / 110.0) : 1;
		int w = font.width(warning) + 12;
		int x = sw / 2 - w / 2, y = sh / 2 + 14;
		round(g, x, y, w, 14, 5, alpha(UI_PANEL, 0.85));
		g.text(font, warning, x + 6, y + 3, alpha(color, pulse), false);
	}
}
