package dev.foragingplanner.mc;

import net.minecraft.client.input.MouseButtonEvent;

/** Size of the mod's big menus, independent of Minecraft's GUI scale (Settings > Style). */
final class MenuScale {
	private MenuScale() {}

	static float get() {
		return Math.clamp(Planner.config.menuScale, 70, 130) / 100f;
	}

	static MouseButtonEvent event(MouseButtonEvent e, float s) {
		return s == 1f ? e : new MouseButtonEvent(e.x() / s, e.y() / s, e.buttonInfo());
	}
}
