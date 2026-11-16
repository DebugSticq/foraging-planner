package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;

/**
 * Precision Cutting helper. The perk marks one log on each nearby tree with a particle; hitting it
 * gives +10 Sweep. The mod watches particles that keep appearing at the same spot right on a tree
 * log and outlines that log brightly, so it is easy to find.
 */
public final class Precision {
	/** Particles that come from breaking, hits or the world, never from the marker. */
	private static final Set<String> IGNORE = Set.of("block", "block_marker", "item", "cloud", "poof", "crit", "enchanted_hit", "sweep_attack",
			"damage_indicator", "smoke", "large_smoke", "dust_plume", "falling_dust", "item_slime", "bubble", "splash", "rain", "underwater",
			"bubble_pop", "dripping_water", "falling_water", "ash", "white_ash", "mycelium", "explosion", "explosion_emitter", "firework",
			"entity_effect", "effect", "instant_effect", "portal", "reverse_portal", "spore_blossom_air", "falling_spore_blossom");
	private static final long KEEP_MS = 1500;

	private record Hit(Vec3 pos, String type, long timeMs) {}

	private static final List<Hit> HITS = new ArrayList<>();
	private static final Map<Integer, Long> MARKED = new HashMap<>();

	private static final List<Hit> CRITS = new ArrayList<>();

	private Precision() {}

	/** Crit particles of the last two seconds away from the player, for the Invisibug helper. */
	public static List<Vec3> crits() {
		long now = System.currentTimeMillis();
		CRITS.removeIf(h -> now - h.timeMs() > 2000);
		List<Vec3> out = new ArrayList<>();
		for (Hit h : CRITS) {
			boolean near = false;
			for (Vec3 v : out) if (v.distanceToSqr(h.pos()) < 1.5 * 1.5) { near = true; break; }
			if (!near) out.add(h.pos());
			if (out.size() >= 12) break;
		}
		return out;
	}

	public static void onParticle(ClientboundLevelParticlesPacket packet) {
		if (!Planner.config.precisionHelper && !Planner.config.invisibugHelper) return;
		var client = Minecraft.getInstance();
		if (!client.isSameThread() || client.player == null || client.level == null) return;
		if (!SkyBlockReader.inForagingArea()) return;
		var key = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle().getType());
		String type = key == null ? "" : key.getPath();
		Vec3 pos = new Vec3(packet.x(), packet.y(), packet.z());
		// Invisibugs give off crit particles; the player's own hits are close by.
		if (type.equals("crit") && Planner.config.invisibugHelper && pos.distanceToSqr(client.player.position()) > 5 * 5) CRITS.add(new Hit(pos, type, System.currentTimeMillis()));
		if (IGNORE.contains(type) || !Planner.config.precisionHelper) return;
		if (pos.distanceToSqr(client.player.position()) > 40 * 40) return;

		long now = System.currentTimeMillis();
		HITS.removeIf(h -> now - h.timeMs() > KEEP_MS);
		HITS.add(new Hit(pos, type, now));

		// A marker keeps appearing at almost the same place; random effects do not.
		int near = 0;
		for (Hit h : HITS) if (h.type().equals(type) && h.pos().distanceToSqr(pos) < 0.6 * 0.6) near++;
		if (near < 3) return;

		Display.BlockDisplay best = null;
		double bestDistance = 1.2 * 1.2;
		for (var display : client.level.getEntitiesOfClass(Display.BlockDisplay.class, new net.minecraft.world.phys.AABB(pos, pos).inflate(1.5))) {
			var render = display.blockRenderState();
			if (render == null) continue;
			var state = render.blockState();
			if (!(state.is(net.minecraft.world.level.block.Blocks.STRIPPED_SPRUCE_WOOD) || state.is(net.minecraft.world.level.block.Blocks.MANGROVE_WOOD)
					|| state.is(net.minecraft.world.level.block.Blocks.STRIPPED_BIRCH_WOOD) || state.is(net.minecraft.world.level.block.Blocks.STRIPPED_MANGROVE_WOOD))) continue;
			double d = display.position().add(0.5, 0.5, 0.5).distanceToSqr(pos);
			if (d < bestDistance) { bestDistance = d; best = display; }
		}
		if (best != null) MARKED.put(best.getId(), now);
	}

	/** Outline colour for a log display, or 0. */
	public static int color(Display.BlockDisplay entity) {
		Long seen = MARKED.get(entity.getId());
		if (seen == null) return 0;
		if (System.currentTimeMillis() - seen > 2500) {
			MARKED.remove(entity.getId());
			return 0;
		}
		return 0xFFFFE14D;
	}

	/** Positions of marked logs, for the on-screen markers. */
	public static List<Vec3> marked() {
		var client = Minecraft.getInstance();
		List<Vec3> out = new ArrayList<>();
		if (client.level == null) return out;
		long now = System.currentTimeMillis();
		MARKED.entrySet().removeIf(e -> now - e.getValue() > 2500);
		for (int id : MARKED.keySet()) {
			var entity = client.level.getEntity(id);
			if (entity != null) out.add(entity.position().add(0.5, 0.5, 0.5));
		}
		return out;
	}
}
