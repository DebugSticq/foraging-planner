package dev.foragingplanner.mc;

import java.util.Locale;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Collects names the player meets while playing (gift rewards, catches, mobs, ground items), so the
 * list pickers in the settings can offer them instead of asking the player to type them.
 */
public final class SeenNames {
	private static final int MAX_ITEMS = 600;
	private static final int MAX_MOBS = 200;
	private static final Pattern LEVEL = Pattern.compile("\\[[^\\]]*\\]");
	private static final Pattern TAIL = Pattern.compile("[\\s\\d.,/kKmM❤✦✯☠♣⚔]+$");
	private static int ticks;

	private SeenNames() {}

	/** An item name, with its SkyBlock id when known. */
	public static void item(String id, String name) {
		if (name == null) return;
		String clean = cleanItem(name);
		if (clean.isEmpty() || clean.length() > 40) return;
		var map = Progress.get().seenItems;
		String old = map.get(clean);
		if (old != null && (!old.isEmpty() || id == null || id.isEmpty())) return;
		if (old == null && map.size() >= MAX_ITEMS) map.remove(map.keySet().iterator().next());
		map.put(clean, id == null ? "" : id);
		Progress.save(false);
	}

	public static void mob(String name) {
		String clean = cleanMob(name);
		if (clean.length() < 3 || clean.length() > 28 || !Character.isLetter(clean.charAt(0))) return;
		var list = Progress.get().seenMobs;
		if (list.contains(clean)) return;
		if (list.size() >= MAX_MOBS) list.remove(0);
		list.add(clean);
		Progress.save(false);
	}

	static String cleanItem(String name) {
		String s = Planner.strip(name).trim();
		s = s.replaceFirst("^\\d+x\\s+", "").replaceFirst("\\s+x\\d+$", "");
		return s.trim();
	}

	static String cleanMob(String name) {
		String s = LEVEL.matcher(Planner.strip(name)).replaceAll(" ").trim();
		s = TAIL.matcher(s).replaceAll("").trim();
		return s.replaceAll("\\s+", " ");
	}

	/** Called once a second: looks at named mobs and items on the ground nearby. */
	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !SkyBlockReader.inSkyBlock()) return;
		if (++ticks % 3 != 0) return;
		for (Entity e : client.level.getEntitiesOfClass(Entity.class, client.player.getBoundingBox().inflate(24))) {
			if (e instanceof Player) continue;
			if (e instanceof ItemEntity item) {
				var stack = item.getItem();
				if (!stack.isEmpty()) item(GearAdvisor.itemId(stack), stack.getHoverName().getString());
				continue;
			}
			if (e.getCustomName() == null) continue;
			String raw = e.getCustomName().getString();
			String lower = raw.toLowerCase(Locale.ROOT);
			// Mob nametags on Hypixel carry a level or health; plain signs and NPC names do not.
			if (!lower.contains("❤") && !lower.contains("[lv")) continue;
			mob(raw);
		}
	}
}
