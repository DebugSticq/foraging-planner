package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Remembers where shard vendors in the Critter Safari were last seen, so they can be found again.
 * Names come from the config (vendorNames), because Hypixel may rename them.
 */
public final class Vendors {
	public record Vendor(String name, Vec3 pos, long seenMs) {}

	private static final Map<String, Vendor> SEEN = new LinkedHashMap<>();
	private static final long KEEP_MS = 30 * 60_000L;

	private Vendors() {}

	public static void tick(Minecraft client) {
		if (!Planner.config.vendorTracker || client.player == null || client.level == null) return;
		String area = SkyBlockReader.area().toLowerCase(Locale.ROOT);
		if (!area.contains("safari") && !area.contains("torrhus")) return;
		long now = System.currentTimeMillis();
		for (Entity e : client.level.getEntitiesOfClass(Entity.class, client.player.getBoundingBox().inflate(64))) {
			var custom = e.getCustomName();
			if (custom == null) continue;
			String name = Planner.strip(custom.getString()).trim();
			String lower = name.toLowerCase(Locale.ROOT);
			for (String v : Planner.config.vendorNames) {
				if (v.isBlank() || !lower.contains(v.toLowerCase(Locale.ROOT))) continue;
				String key = name + "@" + Math.round(e.getX() / 4) + "," + Math.round(e.getZ() / 4);
				SEEN.put(key, new Vendor(name, e.position(), now));
				break;
			}
		}
		SEEN.values().removeIf(v -> now - v.seenMs() > KEEP_MS);
	}

	public static List<Vendor> known() {
		return new ArrayList<>(SEEN.values());
	}

	/** HUD lines: name, distance and how long ago. */
	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		var player = Minecraft.getInstance().player;
		if (!Planner.config.vendorTracker || player == null) return out;
		long now = System.currentTimeMillis();
		for (Vendor v : SEEN.values()) {
			long min = (now - v.seenMs()) / 60_000;
			out.add("§6" + v.name() + " §7" + Math.round(v.pos().distanceTo(player.position())) + "m · " + (min == 0 ? Planner.tr("vendor.now") : Planner.tr("vendor.ago", min)));
		}
		return out;
	}
}
