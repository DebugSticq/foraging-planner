package dev.foragingplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Extra key bindings, unbound by default: stats, gear, check, navigator and HUD on/off. */
public final class Keys {
	private static KeyMapping stats, gear, check, navigator, hud;
	private static String lastNavigator = "arrow_star";

	private Keys() {}

	private static KeyMapping unbound(String name) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key." + name, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
	}

	public static void register() {
		stats = unbound("stats");
		gear = unbound("gear");
		check = unbound("check");
		navigator = unbound("navigator");
		hud = unbound("hud");
	}

	private static java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> later;

	/** Opens a screen on the next tick (a chat command would close it right away). */
	public static void openLater(java.util.function.Supplier<net.minecraft.client.gui.screens.Screen> screen) {
		later = screen;
	}

	/** Called every client tick. */
	public static void tick(Minecraft client) {
		if (later != null) {
			var open = later;
			later = null;
			client.gui.setScreen(open.get());
		}
		if (stats == null) return;
		while (stats.consumeClick()) if (client.gui.screen() == null) client.gui.setScreen(new StatsScreen(null));
		while (gear.consumeClick()) if (client.gui.screen() == null) client.gui.setScreen(new GearScreen(null));
		while (check.consumeClick()) if (client.gui.screen() == null) client.gui.setScreen(new CheckScreen(null));
		while (navigator.consumeClick()) toggleNavigator();
		while (hud.consumeClick()) {
			ModConfig c = Planner.config;
			c.hudEnabled = !c.hudEnabled;
			c.save();
			PlannerHud.rebuild();
			client.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("§a[FP] §7HUD " + Planner.tr(c.hudEnabled ? "journal.hud_on" : "journal.hud_off")), false);
		}
	}

	private static void toggleNavigator() {
		ModConfig c = Planner.config;
		String next;
		if (c.navigator.equals("off")) next = lastNavigator;
		else {
			lastNavigator = c.navigator;
			next = "off";
		}
		c.navigator = next;
		c.treeArrow = next.contains("arrow") || next.equals("all");
		c.treeChoice = next.contains("star") || next.equals("all");
		c.treeRoute = next.equals("route") || next.equals("all");
		c.save();
		Minecraft.getInstance().gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("§a[FP] §7" + Planner.tr("settings.navigator") + ": " + Planner.tr("option." + next)), false);
	}
}
