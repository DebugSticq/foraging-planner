package dev.foragingplanner.mc;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

/**
 * Quieter view on the foraging islands: no fog, clear water and no floating hotspot text.
 * The fog itself is changed in FogMixin; this class decides when.
 */
public final class Quiet {
	/** Ids of the floating text entities that belong to a hotspot, found once a second. */
	private static Set<Integer> hidden = Set.of();

	private Quiet() {}

	public static boolean noFog() {
		return Planner.config.noFog && SkyBlockReader.inForagingArea();
	}

	public static boolean clearWater() {
		return Planner.config.clearWater && SkyBlockReader.inForagingArea();
	}

	public static boolean hide(Entity entity) {
		return !hidden.isEmpty() && Planner.config.hideHotspots && hidden.contains(entity.getId());
	}

	/** Called once a second: finds hotspot labels and the text lines stacked under them. */
	public static void tick(Minecraft client) {
		if (!Planner.config.hideHotspots || client.level == null || client.player == null || !SkyBlockReader.inForagingArea()) {
			hidden = Set.of();
			return;
		}
		Set<Integer> found = new HashSet<>();
		AABB around = client.player.getBoundingBox().inflate(64);
		for (Entity entity : client.level.getEntitiesOfClass(Entity.class, around, Quiet::label)) {
			if (!text(entity).contains("hotspot")) continue;
			found.add(entity.getId());
			// The bonus lines ("+15 Fishing Speed" ...) float right above or below the title.
			AABB stack = new AABB(entity.getX() - 0.6, entity.getY() - 2.5, entity.getZ() - 0.6, entity.getX() + 0.6, entity.getY() + 2.5, entity.getZ() + 0.6);
			for (Entity line : client.level.getEntitiesOfClass(Entity.class, stack, Quiet::label)) found.add(line.getId());
		}
		hidden = found;
	}

	private static boolean label(Entity entity) {
		return entity instanceof Display.TextDisplay || entity instanceof ArmorStand stand && stand.isInvisible() && stand.getCustomName() != null;
	}

	private static String text(Entity entity) {
		var name = entity instanceof Display.TextDisplay display ? display.getText() : entity.getCustomName();
		return name == null ? "" : Planner.strip(name.getString()).toLowerCase(Locale.ROOT);
	}
}
