package dev.foragingplanner.mc;

import net.minecraft.client.Minecraft;

/** Pauses the session clock when the player has not moved, turned or gained XP for a few minutes. */
public final class Afk {
	private static double x, y, z;
	private static float yaw, pitch;
	private static long lastActive = System.currentTimeMillis();
	private static boolean idle;

	private Afk() {}

	/** Called once a second. */
	public static void tick(Minecraft client) {
		long now = System.currentTimeMillis();
		if (client.player == null) return;
		var p = client.player;
		boolean moved = Math.abs(p.getX() - x) > 0.1 || Math.abs(p.getY() - y) > 0.1 || Math.abs(p.getZ() - z) > 0.1
				|| Math.abs(p.getYRot() - yaw) > 1 || Math.abs(p.getXRot() - pitch) > 1;
		if (moved || SessionRates.recentActivity()) lastActive = now;
		x = p.getX(); y = p.getY(); z = p.getZ(); yaw = p.getYRot(); pitch = p.getXRot();
		boolean nowIdle = Planner.config.afkPause && now - lastActive > Math.max(1, Planner.config.afkMinutes) * 60_000L;
		if (nowIdle && !idle) JournalStore.notify(Planner.tr("afk.paused"));
		if (!nowIdle && idle) JournalStore.notify(Planner.tr("afk.resumed"));
		idle = nowIdle;
	}

	public static boolean idle() {
		return idle;
	}
}
