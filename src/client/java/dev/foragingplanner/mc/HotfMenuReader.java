package dev.foragingplanner.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.HotfPlanner;

/**
 * Reads perk levels and Forest Whispers while the Heart of the Forest menu is open,
 * and adds the planner's verdict to perk tooltips there.
 */
public final class HotfMenuReader {
	/** Items arrive a moment after the menu opens, so it is read again every few ticks while open. */
	private static final int READ_INTERVAL_TICKS = 10;

	private static AbstractContainerScreen<?> openMenu;
	private static int ticks;

	private HotfMenuReader() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			if (!isHotfMenu(screen)) return;

			openMenu = menu;
			ticks = 0;

			ScreenEvents.afterTick(screen).register(current -> {
				if (++ticks % READ_INTERVAL_TICKS == 0) read(menu);
			});
			ScreenEvents.remove(screen).register(current -> {
				read(menu);
				openMenu = null;
				Planner.config.save();
			});
		});

		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (openMenu != null) addTooltip(stack, lines);
		});
	}

	private static boolean isHotfMenu(Screen screen) {
		return Planner.strip(screen.getTitle().getString()).trim().equalsIgnoreCase(Planner.data.hotf.menuTitle);
	}

	private static void read(AbstractContainerScreen<?> menu) {
		Pattern levelPattern = Patterns.get(Planner.data.hotf.levelPattern);
		Pattern whispersPattern = Patterns.get(Planner.data.hotf.whispersPattern);
		Map<String, Integer> levels = new LinkedHashMap<>();
		long whispers = -1;

		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || slot.container == net.minecraft.client.Minecraft.getInstance().player.getInventory()) continue;

			String name = Planner.strip(stack.getHoverName().getString()).trim();
			List<Component> lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines();

			if (name.equalsIgnoreCase(Planner.data.hotf.menuTitle) && whispersPattern != null) {
				double found = ContestMath.parseNumber(firstGroup(lore, whispersPattern));
				if (found >= 0) whispers = (long) found;
                double desert = ContestMath.parseNumber(firstGroup(lore, Pattern.compile("Desert Whispers: ([0-9,]+)")));
                if (desert >= 0) Planner.config.desertWhispers = (long) desert;
                double tokens = ContestMath.parseNumber(firstGroup(lore, Pattern.compile("Tokens? of the Forest: ([0-9,]+)")));
                if (tokens >= 0) Planner.config.tokens = (int) tokens;
				continue;
			}

			GameData.Perk perk = Planner.data.perk(name);
			if (perk == null || levelPattern == null) continue;

			Planner.config.perkSlots.put(perk.name, slot.index);
            Map<String, Integer> requirements = new LinkedHashMap<>();
            boolean locked = false;
            boolean known = false;
            for (Component component : lore) {
                String text = Planner.strip(component.getString()).trim();
                if (text.contains("Desert Whispers")) Planner.config.perkCurrencies.put(perk.name, "desert");
                else if (text.contains("Forest Whispers")) Planner.config.perkCurrencies.put(perk.name, "forest");
                if (text.startsWith("Requires") || text.contains("Click to unlock")) locked = true;
                Matcher tier = Pattern.compile("Requires Tier (\\d+)").matcher(text);
                if (tier.find()) Planner.config.requiredTiers.put(perk.name, Integer.parseInt(tier.group(1)));
                for (GameData.Perk parent : Planner.data.hotf.perks) {
                    if (text.equals("Requires " + parent.name)) {
                        requirements.put(parent.name, 1);
                        known = true;
                    }
                }
                if (text.contains("Click to unlock")) known = true;
            }
            if (known && (!requirements.isEmpty() || !Planner.config.requirements.containsKey(perk.name))) {
                Planner.config.requirements.put(perk.name, requirements);
            }
            // Locked perks can still display their prospective first level.

			double level = ContestMath.parseNumber(firstGroup(lore, levelPattern));
			boolean enabled = lore.stream().anyMatch(line -> Planner.strip(line.getString()).trim().matches("ENABLED|SELECTED"));
            levels.put(perk.name, locked ? 0 : enabled && level < 0 ? 1 : Math.max(0, (int) level));
		}

		// An empty result means the items had not arrived yet. Keep what was read before.
		if (!levels.isEmpty()) Planner.config.perkLevels.putAll(levels);
		if (whispers >= 0) Planner.config.whispers = whispers;
        applyRequirements();
	}

    public static void applyRequirements() {
        for (GameData.Perk perk : Planner.data.hotf.perks) {
            if (Planner.config.requirements.containsKey(perk.name)) {
                perk.requires = Planner.config.requirements.get(perk.name);
                perk.unlockKnown = true;
            }
            perk.requiredTier = Planner.config.requiredTiers.getOrDefault(perk.name, perk.requiredTier);
            perk.menuSlot = Planner.config.perkSlots.getOrDefault(perk.name, -1);
            perk.currency = Planner.config.perkCurrencies.getOrDefault(perk.name, perk.currency);
        }
    }

	private static String firstGroup(List<Component> lore, Pattern pattern) {
		for (Component line : lore) {
			Matcher matcher = pattern.matcher(Planner.strip(line.getString()));
			if (matcher.find()) return matcher.group(1);
		}

		return null;
	}

	private static void addTooltip(ItemStack stack, List<Component> lines) {
		GameData.Perk perk = Planner.data.perk(Planner.strip(stack.getHoverName().getString()).trim());
		if (perk == null) return;

		if (!perk.hasValuedEffects()) {
			lines.add(Component.literal(Planner.tr("tooltip.unvalued")));
			return;
		}

		List<HotfPlanner.Upgrade> upgrades = Planner.upgrades();

		for (int i = 0; i < upgrades.size(); i++) {
			HotfPlanner.Upgrade upgrade = upgrades.get(i);
			if (upgrade.perk() != perk) continue;

			lines.add(Component.literal(Planner.tr("tooltip.rank", i + 1, upgrades.size(), Planner.goal().id())));
			lines.add(Component.literal(Planner.tr("tooltip.gain",
					String.format(java.util.Locale.US, "%.2f", upgrade.gainPercent()), Planner.whole(upgrade.cost()))));
			return;
		}
	}
}
