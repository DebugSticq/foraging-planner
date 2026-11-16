package dev.foragingplanner.mc;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Protection against misclicks: dropping a protected item (like the Shining Coin) needs a second
 * press within two seconds, and hitting or using a Huntrap needs sneaking.
 */
public final class Safeguard {
	private static long dropArmedAt;
	private static String dropArmedFor = "";

	private Safeguard() {}

	/** Returns true when this drop should be blocked. */
	public static boolean blockDrop(ItemStack stack) {
		if (!Planner.config.dropProtection || stack.isEmpty()) return false;
		String name = Planner.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		boolean protectedItem = false;
		for (String p : Planner.config.protectedItems) if (!p.isBlank() && name.contains(p.toLowerCase(Locale.ROOT))) protectedItem = true;
		if (!protectedItem) return false;
		long now = System.currentTimeMillis();
		if (name.equals(dropArmedFor) && now - dropArmedAt < 2000) {
			dropArmedFor = "";
			return false;
		}
		dropArmedFor = name;
		dropArmedAt = now;
		Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal("§c" + Planner.tr("safeguard.drop", Planner.strip(stack.getHoverName().getString()))), false);
		return true;
	}

	/** Returns true when hitting or using this entity should be blocked. */
	public static boolean blockEntity(Entity target) {
		var client = Minecraft.getInstance();
		if (!Planner.config.huntrapProtection || client.player == null || client.player.isShiftKeyDown()) return false;
		if (!isHuntrap(target)) return false;
		client.gui.hud.setOverlayMessage(Component.literal("§e" + Planner.tr("safeguard.huntrap")), false);
		return true;
	}

	private static boolean isHuntrap(Entity target) {
		if (named(target)) return true;
		var level = target.level();
		for (Entity near : level.getEntitiesOfClass(Entity.class, target.getBoundingBox().inflate(0.6, 2.5, 0.6))) if (named(near)) return true;
		return false;
	}

	private static boolean named(Entity e) {
		var name = e.getCustomName();
		if (name == null) return false;
		String text = Planner.strip(name.getString()).toLowerCase(Locale.ROOT);
		return text.contains("huntrap") || text.contains("hunting trap");
	}
}
