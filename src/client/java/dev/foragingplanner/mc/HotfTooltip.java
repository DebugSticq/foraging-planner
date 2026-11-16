package dev.foragingplanner.mc;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.PerkMath;

/** Adds the whisper cost to max and for the next 10 levels to each perk in the HOTF menu. */
public final class HotfTooltip {
	private HotfTooltip() {}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (!Planner.config.hotfCosts) return;
			var screen = Minecraft.getInstance().gui.screen();
			if (screen == null || !screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("heart of the forest")) return;
			String name = Planner.strip(stack.getHoverName().getString()).trim();
			GameData.Perk perk = null;
			for (GameData.Perk p : Planner.data.hotf.perks) if (name.equalsIgnoreCase(p.name) || name.startsWith(p.name + " ")) perk = p;
			if (perk == null || perk.maxLevel <= 1) return;
			int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
			if (level >= perk.maxLevel) return;
			long toMax = PerkMath.costBetween(perk, level, perk.maxLevel);
			long ten = PerkMath.costBetween(perk, level, Math.min(perk.maxLevel, level + 10));
			long budget = Planner.budget(perk);
			lines.add(Component.literal(""));
			lines.add(Component.literal("§a[FP] §7" + Planner.tr("hotf.to_max", Planner.whole(toMax), perk.maxLevel)));
			lines.add(Component.literal("§a[FP] §7" + Planner.tr("hotf.next_ten", Planner.whole(ten))));
			if (budget > 0) {
				long missing = Math.max(0, toMax - budget);
				lines.add(Component.literal("§a[FP] §7" + (missing == 0 ? Planner.tr("hotf.affordable") : Planner.tr("hotf.missing", Planner.whole(missing)))));
			}
		});
	}
}
