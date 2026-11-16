package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.resources.sounds.SoundInstance;

/** Mutes chosen sounds on the foraging islands and remembers the beacon's last note. */
public final class Sounds {
	/** Sounds Hypixel uses when logs break and trees fall. */
	private static final Set<String> TREE_BREAK = Set.of("block.wood.break", "block.stem.break", "block.cherry_wood.break",
			"block.nether_wood.break", "block.bamboo_wood.break", "block.mangrove_roots.break", "block.muddy_mangrove_roots.break",
			"block.roots.break", "block.azalea_leaves.break", "block.grass.break", "entity.zombie.break_wooden_door",
			"entity.zombie.attack_wooden_door", "entity.zombie_villager.converted", "item.axe.strip", "item.axe.scrape");
	private static final long RECENT_MS = 15_000;

	private record Heard(String id, long timeMs, boolean muted) {}

	private static final List<Heard> RECENT = new ArrayList<>();
	private static float lastNotePitch = -1;
	private static long lastNoteMs;

	private Sounds() {}

	/** Called for every sound the game wants to play. Returns true to mute it. */
	public static boolean onPlay(SoundInstance sound) {
		try {
			String id = sound.getIdentifier().getPath();
			if (Planner.config.safariBells && id.contains("bell")) Bells.sound(id, sound.getX(), sound.getY(), sound.getZ());
			if (id.startsWith("block.note_block.") && Beacon.menuOpen()) {
				lastNotePitch = sound.getPitch();
				lastNoteMs = System.currentTimeMillis();
			}
			boolean mute = SkyBlockReader.inForagingArea() && (muted(id) || fromMutedMob(sound, id));
			remember(id, mute);
			return mute;
		} catch (RuntimeException ignored) {
			// A sound must never break because of the mod.
		}
		return false;
	}

	private static boolean muted(String id) {
		if (Planner.config.mutedSounds.contains(id)) return true;
		if (Planner.config.mutePhantoms && id.startsWith("entity.phantom.")) return true;
		if (Planner.config.muteTreeBreak) {
			if (TREE_BREAK.contains(id) || id.startsWith("entity.creaking.") || id.startsWith("block.creaking_heart.")) return true;
		}
		return false;
	}

	/**
	 * Mutes sounds that come from a mob with one of the muted names, like the Drybark. Hypixel's
	 * mobs carry their name on a nearby armor stand, so the entities around the sound are checked.
	 */
	private static boolean fromMutedMob(SoundInstance sound, String id) {
		if (!Planner.config.muteMobs || Planner.config.mutedMobs.isEmpty() || !id.startsWith("entity.")) return false;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return false;
		var box = new net.minecraft.world.phys.AABB(sound.getX() - 2.5, sound.getY() - 3, sound.getZ() - 2.5, sound.getX() + 2.5, sound.getY() + 3, sound.getZ() + 2.5);
		for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, box)) {
			var name = entity.getCustomName();
			if (name == null) continue;
			String text = Planner.strip(name.getString()).toLowerCase(java.util.Locale.ROOT);
			for (String mob : Planner.config.mutedMobs) if (!mob.isBlank() && text.contains(mob.toLowerCase(java.util.Locale.ROOT))) return true;
		}
		return false;
	}

	private static void remember(String id, boolean muted) {
		long now = System.currentTimeMillis();
		synchronized (RECENT) {
			RECENT.removeIf(h -> now - h.timeMs() > RECENT_MS);
			if (RECENT.size() < 400) RECENT.add(new Heard(id, now, muted));
		}
	}

	/** Sounds of the last 15 seconds: id → [count, muted 0/1], newest first. */
	public static Map<String, int[]> recent() {
		Map<String, int[]> out = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		synchronized (RECENT) {
			for (int i = RECENT.size() - 1; i >= 0; i--) {
				Heard h = RECENT.get(i);
				if (now - h.timeMs() > RECENT_MS) continue;
				int[] entry = out.computeIfAbsent(h.id(), k -> new int[2]);
				entry[0]++;
				if (h.muted()) entry[1] = 1;
			}
		}
		return out;
	}

	/** Mutes or unmutes one sound by its id. Returns true if it is muted now. */
	public static boolean toggle(String id) {
		List<String> list = Planner.config.mutedSounds;
		boolean now = !list.remove(id);
		if (now) list.add(id);
		Planner.config.save();
		return now;
	}

	/** Pitch of the last note block sound while the beacon menu is open, or -1. */
	public static float lastNotePitch() {
		return System.currentTimeMillis() - lastNoteMs < 15_000 ? lastNotePitch : -1;
	}
    /** Lists the sounds of the last seconds with a click to mute or unmute each. */
	public static void list(java.util.function.Consumer<net.minecraft.network.chat.Component> out) {
	    var recent = recent();
	    out.accept(net.minecraft.network.chat.Component.literal("§a[FP] §f" + Planner.tr("sounds.title")));
	    if (recent.isEmpty()) {
	        out.accept(net.minecraft.network.chat.Component.literal("§7" + Planner.tr("sounds.none")));
	        return;
	    }
	    int shown = 0;
	    for (var e : recent.entrySet()) {
	        if (shown++ >= 15) break;
	        String id = e.getKey();
	        boolean muted = e.getValue()[1] == 1 || Planner.config.mutedSounds.contains(id);
	        String command = "/fp sounds toggle " + id;
	        out.accept(net.minecraft.network.chat.Component.literal((muted ? "§c✖ " : "§a♪ ") + "§f" + id + " §8×" + e.getValue()[0] + " ")
	                .append(net.minecraft.network.chat.Component.literal(muted ? "§e[" + Planner.tr("sounds.unmute") + "]" : "§c[" + Planner.tr("sounds.mute") + "]")
	                        .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(command))
	                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal(command))))));
	    }
	    out.accept(net.minecraft.network.chat.Component.literal("§8" + Planner.tr("sounds.hint")));
	}
}
