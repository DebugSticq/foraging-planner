package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Looks at the item data of the player's gear and lists what could still be added: reforge,
 * Recombobulator, enchantments such as Forest Pledge, gemstones and boosters.
 */
public final class UpgradeCheck {
	/** One finding: short text and whether it is already done. */
	public record Chip(String text, boolean done) {}

	private static final Map<String, List<Chip>> EQUIPMENT = new HashMap<>();

	private UpgradeCheck() {}

	/** Findings for the item in a slot, or an empty list when it cannot be seen right now. */
	public static List<Chip> forSlot(String slot, GearData.Item item) {
		if (item == null) return List.of();
		var player = Minecraft.getInstance().player;
		if (player == null) return List.of();

		ItemStack stack = switch (slot) {
			case "helmet" -> player.getItemBySlot(EquipmentSlot.HEAD);
			case "chestplate" -> player.getItemBySlot(EquipmentSlot.CHEST);
			case "leggings" -> player.getItemBySlot(EquipmentSlot.LEGS);
			case "boots" -> player.getItemBySlot(EquipmentSlot.FEET);
			case "axe" -> find(item.id);
			default -> ItemStack.EMPTY;
		};

		if (stack.isEmpty()) return EQUIPMENT.getOrDefault(slot, List.of());
		return check(stack, slot);
	}

	private static ItemStack find(String id) {
		var inventory = Minecraft.getInstance().player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (GearAdvisor.itemId(inventory.getItem(i)).equals(id)) return inventory.getItem(i);
		}
		return ItemStack.EMPTY;
	}

	static void readEquipment(AbstractContainerScreen<?> menu) {
		GearData data = GearData.get();
		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			GearData.Item item = data.item(GearAdvisor.itemId(stack));
			if (item != null && List.of("necklace", "cloak", "belt", "gloves").contains(item.slot)) EQUIPMENT.put(item.slot, check(stack, item.slot));
		}
	}

	static List<Chip> check(ItemStack stack, String slot) {
		CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		List<Chip> chips = new ArrayList<>();

		String reforge = tag.getStringOr("modifier", "");
		chips.add(reforge.isEmpty() ? new Chip(Planner.tr("upgrade.no_reforge"), false)
				: new Chip(Planner.tr("upgrade.reforge", capital(reforge)), true));

		chips.add(new Chip(Planner.tr("upgrade.recomb"), tag.getIntOr("rarity_upgrades", 0) > 0));

		CompoundTag enchants = tag.getCompoundOrEmpty("enchantments");
		for (String key : enchants.keySet()) {
			if (key.contains("pledge")) {
				int level = enchants.getIntOr(key, 0);
				chips.add(new Chip(Planner.tr("upgrade.pledge", level), level >= 5));
			}
		}

		List<String> lore = MenuReaders.lore(stack);
		int emptyGems = 0;
		for (String line : lore) emptyGems += line.split("\\[✧]", -1).length - 1;
		CompoundTag gems = tag.getCompoundOrEmpty("gems");
		if (gems.size() > 0) chips.add(new Chip(Planner.tr("upgrade.gems", gems.size()), emptyGems == 0));
		else if (emptyGems > 0) chips.add(new Chip(Planner.tr("upgrade.gems_empty", emptyGems), false));

		boolean booster = tag.contains("boosters") || lore.stream().anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("booster"));
		chips.add(new Chip(Planner.tr("upgrade.booster"), booster));
		return chips;
	}

	private static String capital(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1).replace('_', ' ');
	}
}
