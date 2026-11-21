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
	/** "You tried to cut the protected Tree and were fined 1,000 Coins by the Tree Protection Agency!" */
	private static final Pattern FINE = Pattern.compile("(?i)protected tree.*fined|fined .*tree protection|tree protection agency");
	/** Nametag-zones not seen again for this long while the player is close are dropped (order lifted). */
	private static final long GONE_MS = 20_000;
	/** Zones learned from a fine are kept this long. */
	private static final long FINE_KEEP_MS = 30 * 60_000L;

	/** A protected place. */
	public static final class Zone {
		final String area;
		final double x, y, z;
		long seenMs;
		final char source;

		Zone(String area, double x, double y, double z, long seenMs, char source) {
			this.area = area;
			this.x = x;
			this.y = y;
			this.z = z;
			this.seenMs = seenMs;
			this.source = source;
		}

		Vec3 pos() {
			return new Vec3(x, y, z);
		}

		String save() {
			return area + "|" + Math.round(x) + "|" + Math.round(y) + "|" + Math.round(z) + "|" + seenMs + "|" + source;
		}
	}

	private static final java.util.List<Zone> ZONES = new java.util.ArrayList<>();
	private static boolean loaded;
	private static long warnedMs;

	private TreeWarnings() {}

	private static void load() {
		if (loaded) return;
		loaded = true;
		// The old per-tree list was permanent and often wrong: start fresh.
		if (!Progress.get().protectedTrees.isEmpty()) {
			Progress.get().protectedTrees.clear();
			Progress.changed();
		}
		for (String line : Progress.get().protectedZones) {
			if (line.endsWith("|f")) continue; // places guessed from fines were often wrong
			String[] p = line.split("\\|");
			if (p.length < 6) continue;
			try {
				ZONES.add(new Zone(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Long.parseLong(p[4]), p[5].charAt(0)));
			} catch (RuntimeException ignored) {
				// skip broken entries
			}
		}
	}

	private static void save() {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (Zone z : ZONES) out.add(z.save());
		Progress.get().protectedZones = out;
		Progress.changed();
	}

	public static java.util.List<Zone> zones() {
		load();
		return ZONES;
	}

	/** True when a protected tree is within {@code radius} blocks (sideways) of the point. */
	public static boolean protectedAt(Vec3 at, double radius) {
		if (!Planner.config.protectedTrees) return false;
		String area = SkyBlockReader.area();
		for (Zone z : zones()) {
			if (!z.area.equals(area)) continue;
			double dx = z.x - at.x, dz = z.z - at.z;
			if (dx * dx + dz * dz <= radius * radius && Math.abs(z.y - at.y) < 18) return true;
		}
		return false;
	}

	private static Zone add(double x, double y, double z, char source) {
		String area = SkyBlockReader.area();
		long now = System.currentTimeMillis();
		for (Zone zone : zones()) {
			double dx = zone.x - x, dz = zone.z - z;
			if (zone.area.equals(area) && dx * dx + dz * dz < 4 * 4 && Math.abs(zone.y - y) < 18) {
				zone.seenMs = now;
				return null;
			}
		}
		Zone zone = new Zone(area, x, y, z, now, source);
		ZONES.add(zone);
		save();
		return zone;
	}

	public static void register() {
		ServerMessages.register((component, overlay) -> {
			if (overlay || !Planner.config.protectedTrees) return;
			String text = Planner.strip(component.getString());
			if (!FINE.matcher(text).find()) return;
			Minecraft client = Minecraft.getInstance();
			if (client.player == null) return;
			// The crosshair is no proof: with Axe Toss it still points at your own tree. Only the
			// "Tree Protection Order" sign marks a tree, so look for it now.
			scanNametags(client);
			Planner.LOGGER.info("[FP] Protected tree fine; {} protected places known nearby", ZONES.size());
		});
	}

	/** Once a second: nametags and text displays that mention protection mark that place. */
	public static void scanNametags(Minecraft client) {
		if (!Planner.config.protectedTrees || client.player == null || client.level == null) return;
		long now = System.currentTimeMillis();
		Vec3 me = client.player.position();
		java.util.Set<Zone> seen = new java.util.HashSet<>();
		for (var e : client.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, client.player.getBoundingBox().inflate(64))) {
			String name = null;
			if (e.getCustomName() != null) name = e.getCustomName().getString();
			else if (e instanceof net.minecraft.world.entity.Display.TextDisplay display && display.getText() != null) name = display.getText().getString();
			if (name == null) continue;
			String lower = Planner.strip(name).toLowerCase(Locale.ROOT);
			if (!lower.contains("protection order") && !(lower.contains("protected") && lower.contains("tree"))) continue;
			Zone added = add(e.getX(), e.getY(), e.getZ(), 'n');
			if (added != null) Planner.LOGGER.info("[FP] Protected tree nametag '{}' at {}", Planner.strip(name), e.position());
			for (Zone z : zones()) if (z.pos().distanceToSqr(e.position()) < 16 * 16) seen.add(z);
		}
		// Drop zones whose nametag is gone while the player is close enough to see it, and old fine zones.
		boolean changed = ZONES.removeIf(z -> {
			if (seen.contains(z)) {
				z.seenMs = now;
				return false;
			}
			if (z.source == 'f') return true;
			boolean near = z.area.equals(SkyBlockReader.area()) && z.pos().distanceTo(me) < 40;
			return near && now - z.seenMs > GONE_MS;
		});
		if (changed) save();
	}

	/** Logs, wood, leaves and roots: the parts of a tree. Walls, floors and fences are not. */
	private static boolean treeBlock(net.minecraft.core.BlockPos pos) {
		var level = Minecraft.getInstance().level;
		if (level == null) return false;
		var state = level.getBlockState(pos);
		return state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)
				|| state.is(net.minecraft.world.level.block.Blocks.MANGROVE_ROOTS) || state.is(net.minecraft.world.level.block.Blocks.MUDDY_MANGROVE_ROOTS);
	}

	/** True when the crosshair is on a block of a protected tree (not on something in front of it). */
	private static boolean aimingAtProtected(Minecraft client) {
		HitResult hit = client.player.pick(40, 0, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return false;
		return treeBlock(block.getBlockPos()) && protectedAt(Vec3.atCenterOf(block.getBlockPos()), 6);
	}

	/** Called by the hit hooks: true stops the hit on a protected tree. Sneaking hits anyway. */
	public static boolean blockHit(net.minecraft.core.BlockPos pos) {
		if (!Planner.config.protectedTrees || !Planner.config.protectedBlock) return false;
		var player = Minecraft.getInstance().player;
		if (player == null || player.isShiftKeyDown()) return false;
		if (!treeBlock(pos) || !protectedAt(Vec3.atCenterOf(pos), 6)) return false;
		warnBlocked();
		return true;
	}

	/** Axe Toss and other right-click abilities aimed at a protected tree. */
	public static boolean blockUse() {
		if (!Planner.config.protectedTrees || !Planner.config.protectedBlock) return false;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.player.isShiftKeyDown() || !holdingAxe(client)) return false;
		if (!aimingAtProtected(client)) return false;
		warnBlocked();
		return true;
	}

	private static void warnBlocked() {
		long now = System.currentTimeMillis();
		if (now - warnedMs < 3_000) return;
		warnedMs = now;
		var player = Minecraft.getInstance().player;
		if (player != null) player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("§c⛔ " + Planner.tr("protected.blocked")));
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

	static boolean holdingAxe(Minecraft client) {
		var stack = client.player.getMainHandItem();
		if (stack.is(net.minecraft.tags.ItemTags.AXES)) return true;
		String name = Planner.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		return name.contains("axe") || name.contains("chopper") || name.contains("treecapitator");
	}

	/** Called from the world overlay every frame. */
	public static void draw(Minecraft client, GuiGraphicsExtractor g, long now) {
		if (client.player == null) return;
		var font = client.font;
		if (Planner.config.protectedTrees) {
			String area = SkyBlockReader.area();
			Vec3 me = client.player.position();
			for (Zone z : zones()) {
				if (!z.area.equals(area) || z.pos().distanceTo(me) > 64) continue;
				Vec3 top = z.pos().add(0, 2.2, 0);
				float[] at = WorldOverlay.project(client, top);
				if (at == null || !WorldOverlay.visible(client, top)) continue;
				String label = "⛔ " + Planner.tr("protected.label_big");
				int tw = font.width(label);
				int x = Math.round(at[0]), y = Math.round(at[1]);
				double pulse = Planner.config.animations ? 0.8 + 0.2 * Math.sin(now / 160.0) : 1;
				round(g, x - tw / 2 - 4, y - 6, tw + 8, 12, 4, alpha(0xFF3A0B0B, 0.85));
				g.text(font, label, x - tw / 2, y - 4, alpha(0xFFFF5555, pulse), false);
			}
		}
		if (!SkyBlockReader.inForagingArea()) return;
		int sw = client.getWindow().getGuiScaledWidth(), sh = client.getWindow().getGuiScaledHeight();
		String warning = null;
		int color = 0xFFFF5555;

		// Aim: what a thrown axe would hit, and whether that tree is protected.
		if (Planner.config.protectedTrees && holdingAxe(client)) {
			if (aimingAtProtected(client)) warning = "⚠ " + Planner.tr("protected.aim");
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
