package dev.foragingplanner.mc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reforges, enchantments and the Recombobulator for the gear the player owns. Every option is
 * valued with the same goal model as the gear picks, so "+6 Fortune on the boots" and "a new
 * chestplate" can be compared by improvement per coin.
 */
public final class UpgradeAdvisor {
	private static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE");
	private static final List<String> ARMOR = List.of("helmet", "chestplate", "leggings", "boots");
	private static final List<String> EQUIPMENT = List.of("necklace", "cloak", "belt", "gloves");

	/** One thing that can be done to a piece of gear. */
	public record Option(String slot, String piece, String kind, String title, String detail,
						 double sweep, double fortune, double wisdom, Double gainPercent, Double price,
						 String search, boolean done, String icon) {
		public double perMillion() {
			return gainPercent == null || price == null || price <= 0 ? 0 : gainPercent / (price / 1e6);
		}
	}

	// Data (upgrades.json)

	static final class Data {
		List<Reforge> reforges = new ArrayList<>();
		List<Enchant> enchants = new ArrayList<>();
	}

	static final class Reforge {
		String name, stone, stoneName, types;
		List<String> items;
		double apply;
		Map<String, Map<String, Double>> stats;

		double stat(String rarity, String stat) {
			Map<String, Double> s = stats == null ? null : stats.get(rarity);
			return s == null ? 0 : s.getOrDefault(stat, 0.0);
		}
	}

	static final class Enchant {
		String id, name, slot, note;
		int max;
		boolean ultimate, tiered;
		double sweepPerLevel, fortunePerLevel, wisdomPerLevel;
	}

	private static Data data;

	private static Data data() {
		if (data != null) return data;
		try (InputStream in = UpgradeAdvisor.class.getResourceAsStream("/foragingplanner/upgrades.json")) {
			data = in == null ? new Data() : new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Data.class);
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read upgrades.json", e);
			data = new Data();
		}
		return data;
	}

	private UpgradeAdvisor() {}

	// Reading the pieces

	/** The stacks the player owns per slot, as far as they can be seen right now. */
	private static Map<String, ItemStack> stacks() {
		Map<String, ItemStack> out = new java.util.LinkedHashMap<>();
		var player = Minecraft.getInstance().player;
		if (player == null) return out;
		GearData.Item axe = GearAdvisor.current().get("axe");
		if (axe != null) {
			var inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (GearAdvisor.itemId(inv.getItem(i)).equals(axe.id)) {
					out.put("axe", inv.getItem(i));
					break;
				}
			}
		}
		EquipmentSlot[] worn = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < 4; i++) {
			ItemStack stack = player.getItemBySlot(worn[i]);
			if (!stack.isEmpty() && GearData.get().item(GearAdvisor.itemId(stack)) != null) out.put(ARMOR.get(i), stack);
		}
		out.putAll(UpgradeCheck.equipmentStacks());
		return out;
	}

	/** Rarity from the last lore line ("LEGENDARY AXE"), which already includes a Recombobulator. */
	static String rarity(ItemStack stack) {
		List<String> lore = MenuReaders.lore(stack);
		for (int i = lore.size() - 1; i >= 0; i--) {
			String line = Planner.strip(lore.get(i)).trim();
			if (line.isEmpty() || !line.equals(line.toUpperCase(Locale.ROOT))) continue;
			for (String word : line.split("\\s+")) if (RARITIES.contains(word)) return word;
		}
		GearData.Item item = GearData.get().item(GearAdvisor.itemId(stack));
		return item != null ? item.rarity : "COMMON";
	}

	private static boolean fits(Reforge reforge, String slot, String id) {
		if (reforge.items != null) return reforge.items.contains(id);
		if (reforge.types == null) return false;
		return switch (reforge.types) {
			case "AXE" -> slot.equals("axe");
			case "ARMOR" -> ARMOR.contains(slot);
			case "EQUIPMENT" -> EQUIPMENT.contains(slot);
			default -> false;
		};
	}

	private static Reforge reforgeNamed(String modifier) {
		for (Reforge r : data().reforges) if (r.name.equalsIgnoreCase(modifier.replace('_', '-')) || r.name.replace("-", "").equalsIgnoreCase(modifier.replace("_", ""))) return r;
		return null;
	}

	private static Double price(String id) {
		if (id == null) return null;
		Double bazaar = BazaarPrices.BUY.get(id);
		if (bazaar != null) return bazaar;
		AuctionPrices.request(id);
		return AuctionPrices.get(id);
	}

	private static String roman(int n) {
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	// Options

	public static List<Option> options() {
		List<Option> out = new ArrayList<>();
		for (var entry : stacks().entrySet()) {
			String slot = entry.getKey();
			ItemStack stack = entry.getValue();
			String id = GearAdvisor.itemId(stack);
			GearData.Item item = GearData.get().item(id);
			String piece = item != null ? item.name : Planner.strip(stack.getHoverName().getString());
			String icon = item != null ? item.icon : "paper";
			CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
			String rarity = rarity(stack);
			String modifier = tag.getStringOr("modifier", "");
			Reforge current = modifier.isEmpty() ? null : reforgeNamed(modifier);
			double cs = current == null ? 0 : current.stat(rarity, "sweep");
			double cf = current == null ? 0 : current.stat(rarity, "fortune");
			double cw = current == null ? 0 : current.stat(rarity, "wisdom");

			// Reforges: every foraging reforge that fits and beats the current one.
			Option best = null;
			for (Reforge r : data().reforges) {
				if (!fits(r, slot, id)) continue;
				double ds = r.stat(rarity, "sweep") - cs, df = r.stat(rarity, "fortune") - cf, dw = r.stat(rarity, "wisdom") - cw;
				if (current == r) {
					out.add(new Option(slot, piece, "reforge", Planner.tr("upgrade.has_reforge", r.name), stats(r.stat(rarity, "sweep"), r.stat(rarity, "fortune"), r.stat(rarity, "wisdom")),
							0, 0, 0, null, null, null, true, icon));
					continue;
				}
				double gain = GearAdvisor.gainPercent(ds, df, dw);
				if (gain <= 0.01) continue;
				Double stone = r.stone == null ? null : price(r.stone);
				Double cost = r.stone == null ? null : stone == null ? null : stone + r.apply;
				String how = r.stone == null ? Planner.tr("upgrade.blacksmith") : Planner.tr("upgrade.stone", r.stoneName, GearScreenFormat.coins(r.apply));
				Option o = new Option(slot, piece, "reforge", Planner.tr("upgrade.reforge_to", r.name, Planner.tr("rarity." + rarity.toLowerCase(Locale.ROOT))),
						stats(ds, df, dw) + "  ·  " + how, ds, df, dw, gain, cost,
						r.stone == null ? null : "/bz " + r.stoneName, false, icon);
				if (best == null || better(o, best)) best = o;
			}
			if (best != null) out.add(best);

			// Recombobulator: the reforge gets the next rarity's values.
			if (tag.getIntOr("rarity_upgrades", 0) == 0) {
				int r = RARITIES.indexOf(rarity);
				if (r >= 0 && r + 1 < RARITIES.size()) {
					String next = RARITIES.get(r + 1);
					double ds = 0, df = 0, dw = 0;
					if (current != null) {
						ds = current.stat(next, "sweep") - cs;
						df = current.stat(next, "fortune") - cf;
						dw = current.stat(next, "wisdom") - cw;
					}
					Double gain = current == null ? null : GearAdvisor.gainPercent(ds, df, dw);
					out.add(new Option(slot, piece, "recomb", Planner.tr("upgrade.recomb_to", Planner.tr("rarity." + next.toLowerCase(Locale.ROOT))),
							(current == null ? Planner.tr("upgrade.recomb_hint") : stats(ds, df, dw) + "  ·  " + Planner.tr("upgrade.recomb_base")),
							ds, df, dw, gain, price("RECOMBOBULATOR_3000"), "/ahs Recombobulator 3000", false, "nether_star"));
				}
			} else {
				out.add(new Option(slot, piece, "recomb", Planner.tr("upgrade.recomb_done"), "", 0, 0, 0, null, null, null, true, "nether_star"));
			}

			// Enchantments for this slot.
			CompoundTag enchants = tag.getCompoundOrEmpty("enchantments");
			for (Enchant e : data().enchants) {
				if (!e.slot.equals(slot)) continue;
				String key = e.id.toLowerCase(Locale.ROOT);
				int level = enchants.getIntOr(key, 0);
				if (level >= e.max) {
					out.add(new Option(slot, piece, "enchant", e.name + " " + roman(level), "", 0, 0, 0, null, null, null, true, "enchanted_book"));
					continue;
				}
				if (e.tiered) {
					// Absorb goes up by breaking logs; nothing to buy.
					String detail = level == 0 ? Planner.tr("upgrade.absorb_missing") : e.note;
					out.add(new Option(slot, piece, "enchant", e.name + " " + (level == 0 ? "–" : roman(level)) + " → " + roman(e.max), detail,
							0, e.fortunePerLevel * (e.max - level), e.wisdomPerLevel * (e.max - level), null,
							level == 0 ? price("ENCHANTMENT_" + e.id + "_1") : null, level == 0 ? "/bz " + e.name + " I" : null, false, "enchanted_book"));
					continue;
				}
				String other = null;
				if (e.ultimate) {
					for (String k : enchants.keySet()) if (k.startsWith("ultimate_") && !k.equals(key)) other = k;
				}
				double ds = e.sweepPerLevel * (e.max - level), df = e.fortunePerLevel * (e.max - level), dw = e.wisdomPerLevel * (e.max - level);
				Double gain = ds + df + dw > 0 ? GearAdvisor.gainPercent(ds, df, dw) : null;
				String detail = Planner.tr("enchant." + key) + (other != null ? "  ·  §c" + Planner.tr("upgrade.replaces_ultimate", pretty(other)) : "");
				out.add(new Option(slot, piece, "enchant", e.name + " " + (level == 0 ? "" : roman(level) + " → ") + roman(e.max), detail,
						ds, df, dw, gain, price("ENCHANTMENT_" + e.id + "_" + e.max), "/bz " + e.name + " " + roman(e.max), false, "enchanted_book"));
			}
		}

		// Best value first; things without a value after; done things at the end.
		out.sort(Comparator.comparing(Option::done)
				.thenComparing(o -> o.gainPercent() == null)
				.thenComparing(Comparator.comparingDouble(Option::perMillion).reversed())
				.thenComparing(Comparator.comparingDouble((Option o) -> o.gainPercent() == null ? 0 : o.gainPercent()).reversed()));
		return out;
	}

	private static boolean better(Option a, Option b) {
		if (a.price() != null && b.price() != null) return a.perMillion() > b.perMillion();
		return a.gainPercent() > b.gainPercent();
	}

	private static String pretty(String key) {
		String s = key.replace("ultimate_", "").replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	static String stats(double sweep, double fortune, double wisdom) {
		StringBuilder b = new StringBuilder();
		if (sweep != 0) b.append(sweep > 0 ? "§a+" : "§c").append(Planner.decimal(sweep)).append(" Sweep ");
		if (fortune != 0) b.append(fortune > 0 ? "§6+" : "§c").append(Planner.decimal(fortune)).append(" Fortune ");
		if (wisdom != 0) b.append(wisdom > 0 ? "§b+" : "§c").append(Planner.decimal(wisdom)).append(" Wisdom");
		return b.toString().trim() + "§r";
	}
}
