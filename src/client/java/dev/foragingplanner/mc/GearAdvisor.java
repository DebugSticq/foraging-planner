package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import dev.foragingplanner.core.Objective;
import dev.foragingplanner.core.PlayerStats;

/**
 * Works out which piece of gear is worth buying next.
 *
 * <p>It finds what the player uses now (armor worn, best axe in the inventory, equipment and pet
 * seen in their menus or the tab list), then for every better item computes how much it would
 * improve the chosen goal, using the same model as the HOTF planner, and divides that by its price.
 */
public final class GearAdvisor {
	public static final List<String> SLOTS = List.of("axe", "helmet", "chestplate", "leggings", "boots", "necklace", "cloak", "belt", "gloves", "pet");
	private static final List<String> EQUIPMENT = List.of("necklace", "cloak", "belt", "gloves");
	private static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
	private static final Pattern TAB_SKILL = Pattern.compile("Foraging ([0-9]+|[IVXLC]+)(?::|\\s|$)");
	private static final Pattern TAB_PET = Pattern.compile("\\[Lvl (\\d+)] ([A-Za-z ]+?)\\s*$");
	private static final int SCAN_TICKS = 10;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Equipment and pet last seen, by slot. Saved per profile because they are not visible all the time. */
	private static Map<String, String> seen = new LinkedHashMap<>();
	private static boolean loaded;

	/**
	 * One recommendation for one slot.
	 *
	 * @param replaces     what it replaces, or null for an empty slot
	 * @param gainPercent  improvement of the goal, in percent
	 * @param price        coins to buy it, or null while no price is known
	 * @param bazaar       true when the price is from the Bazaar, false for the auction house
	 * @param perMillion   improvement per million coins, 0 without a price
	 * @param stronger     a pricier item for the same slot that gives clearly more, or null
	 */
	public record Pick(GearData.Item item, GearData.Item replaces, double sweep, double fortune, double wisdom,
					   double gainPercent, Double price, boolean bazaar, double perMillion, Pick stronger) {
		Pick withStronger(Pick other) {
			return new Pick(item, replaces, sweep, fortune, wisdom, gainPercent, price, bazaar, perMillion, other);
		}
	}

	/**
	 * Everything the screen shows.
	 *
	 * @param picks        at most one pick per slot, best value for money first
	 * @param locked       the next item per slot that the player's Foraging level does not allow yet
	 * @param level        the player's Foraging level, or -1 when unknown
	 * @param totalCost    price of all priced picks together
	 * @param combinedGain improvement if all picks are bought, in percent
	 */
	public record Result(List<Pick> picks, List<Pick> locked, int level, double totalCost, double combinedGain,
						 Map<String, List<Pick>> options) {}

	/**
	 * The best set of purchases for a budget: at most one item per slot, chosen so that the summed
	 * improvement is as high as possible without going over the budget.
	 */
	public static List<Pick> plan(Result result, double budget) {
		if (budget <= 0) return List.of();
		int units = 400;
		double unit = budget / units;
		List<List<Pick>> slots = new ArrayList<>();
		for (List<Pick> options : result.options().values()) {
			List<Pick> priced = new ArrayList<>();
			for (Pick pick : options) if (pick.price() != null && pick.price() > 0 && pick.price() <= budget) priced.add(pick);
			if (!priced.isEmpty()) slots.add(priced);
		}

		double[][] best = new double[slots.size() + 1][units + 1];
		int[][] choice = new int[slots.size() + 1][units + 1];

		for (int s = 1; s <= slots.size(); s++) {
			List<Pick> options = slots.get(s - 1);
			for (int c = 0; c <= units; c++) {
				best[s][c] = best[s - 1][c];
				choice[s][c] = -1;
				for (int o = 0; o < options.size(); o++) {
					int cost = (int) Math.ceil(options.get(o).price() / unit);
					if (cost > c) continue;
					double value = best[s - 1][c - cost] + options.get(o).gainPercent();
					if (value > best[s][c]) { best[s][c] = value; choice[s][c] = o; }
				}
			}
		}

		List<Pick> chosen = new ArrayList<>();
		int c = units;
		for (int s = slots.size(); s >= 1; s--) {
			int o = choice[s][c];
			if (o < 0) continue;
			Pick pick = slots.get(s - 1).get(o);
			chosen.add(pick);
			c -= (int) Math.ceil(pick.price() / unit);
		}
		chosen.sort(Comparator.comparingDouble(Pick::perMillion).reversed());
		return chosen;
	}

	private GearAdvisor() {}

	// Reading what the player owns

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;

			String title = Planner.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
			boolean equipment = title.contains("equipment");
			boolean pets = title.startsWith("pets");
			boolean skills = title.contains("skills");
			if (!equipment && !pets && !skills) return;

			int[] ticks = {0};
			ScreenEvents.afterTick(screen).register(current -> {
				if (++ticks[0] % SCAN_TICKS != 0) return;
				if (skills) scanSkills(menu);
				else scanMenu(menu, equipment);
			});
		});
	}

	private static void scanMenu(AbstractContainerScreen<?> menu, boolean equipment) {
		load();
		GearData data = GearData.get();
		boolean changed = false;

		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;

			if (equipment) {
				GearData.Item item = data.item(itemId(stack));

				if (item != null && EQUIPMENT.contains(item.slot) && !item.id.equals(seen.get(item.slot))) {
					seen.put(item.slot, item.id);
					changed = true;
				}
				// The real stats (reforge, enchants, stars, David's milestones) are on the item itself.
				if (item != null) changed |= rememberStats(item.id, stack);
			} else if (loreContains(stack, "Click to despawn")) {
				String pet = petId(stack);

				if (pet != null && !pet.equals(seen.get("pet"))) {
					seen.put("pet", pet);
					changed = true;
				}
				if (pet != null) {
					changed |= rememberStats(pet, stack);
					var level = PET_LEVEL.matcher(Planner.strip(stack.getHoverName().getString()));
					if (level.find() && !level.group(1).equals(seen.get("petlevel"))) {
						seen.put("petlevel", level.group(1));
						changed = true;
					}
				}
			}
		}

		if (changed) save();
	}

	/** The skills menu names each skill "Foraging XXV". */
	private static void scanSkills(AbstractContainerScreen<?> menu) {
		load();

		for (Slot slot : menu.getMenu().slots) {
			String name = Planner.strip(slot.getItem().getHoverName().getString()).trim();
			if (!name.startsWith("Foraging ")) continue;

			int level = parseLevel(name.substring(9).trim());
			if (level >= 0 && !String.valueOf(level).equals(seen.get("level"))) {
				seen.put("level", String.valueOf(level));
				save();
			}
			return;
		}
	}

	/** The Foraging level from the tab list's skill widget, or the last one seen in /skills; -1 when unknown. */
	public static int foragingLevel() {
		load();

		for (String line : SkyBlockReader.tab()) {
			Matcher match = TAB_SKILL.matcher(Planner.strip(line));
			if (!match.find()) continue;

			int level = parseLevel(match.group(1));
			if (level >= 0) {
				if (!String.valueOf(level).equals(seen.get("level"))) {
					seen.put("level", String.valueOf(level));
					save();
				}
				return level;
			}
		}

		try {
			return seen.containsKey("level") ? Integer.parseInt(seen.get("level")) : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static int parseLevel(String text) {
		if (text.isEmpty()) return -1;
		if (Character.isDigit(text.charAt(0))) {
			try { return Integer.parseInt(text); } catch (NumberFormatException e) { return -1; }
		}

		int total = 0;
		int previous = 0;
		for (int i = text.length() - 1; i >= 0; i--) {
			int value = switch (text.charAt(i)) {
				case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100;
				default -> -1;
			};
			if (value < 0) return -1;
			total += value < previous ? -value : value;
			previous = Math.max(previous, value);
		}
		return total;
	}

	static String itemId(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("id", "");
	}

	/** "MONKEY;4" for a legendary Monkey, read from the pet's petInfo data. */
	private static String petId(ItemStack stack) {
		String info = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("petInfo", "");
		if (info.isEmpty()) return null;

		try {
			JsonObject json = JsonParser.parseString(info).getAsJsonObject();
			int tier = TIERS.indexOf(json.get("tier").getAsString());
			return tier < 0 ? null : json.get("type").getAsString() + ";" + tier;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean loreContains(ItemStack stack, String text) {
		for (var line : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) {
			if (Planner.strip(line.getString()).contains(text)) return true;
		}

		return false;
	}

	/** The active pet as the tab list shows it ("[Lvl 100] Monkey"), mapped to the best catalog entry of that name. */
	private static GearData.Item petFromTab(GearData data) {
		for (String line : SkyBlockReader.tab()) {
			Matcher match = TAB_PET.matcher(line);
			if (!match.find()) continue;

			String name = match.group(2).trim();
			GearData.Item best = null;

			for (GearData.Item item : data.items) {
				if (item.slot.equals("pet") && item.name.equalsIgnoreCase(name)
						&& (best == null || TIERS.indexOf(item.rarity) > TIERS.indexOf(best.rarity))) best = item;
			}

			if (best != null) return best;
		}

		return null;
	}

	/** What the player uses in each slot. Slots that could not be read are missing from the map. */
	private static final java.util.regex.Pattern LORE_STAT = java.util.regex.Pattern.compile("^(Sweep|Foraging Fortune|Foraging Wisdom): \\+([\\d,.]+)");

	/** "sweep,fortune,wisdom" from an item's lore, or null when it shows none of them. */
	private static String loreStats(ItemStack stack) {
		double sweep = 0, fortune = 0, wisdom = 0;
		boolean any = false;
		var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		if (lore == null) return null;
		for (var line : lore.lines()) {
			var m = LORE_STAT.matcher(Planner.strip(line.getString()).trim());
			if (!m.find()) continue;
			double v = Double.parseDouble(m.group(2).replace(",", ""));
			switch (m.group(1)) {
				case "Sweep" -> sweep = v;
				case "Foraging Fortune" -> fortune = v;
				default -> wisdom = v;
			}
			any = true;
		}
		return any ? sweep + "," + fortune + "," + wisdom : null;
	}

	private static final java.util.regex.Pattern PET_LEVEL = java.util.regex.Pattern.compile("\\[Lvl (\\d+)\\]");
	private static long lastInventoryScanMs;

	/** Remembers the stats shown on a gear item; true if they changed. */
	private static boolean rememberStats(String id, ItemStack stack) {
		String stats = loreStats(stack);
		if (stats == null || stats.equals(seen.get("stats:" + id))) return false;
		seen.put("stats:" + id, stats);
		return true;
	}

	/** Every few seconds: reads the real stats of worn armor and axes in the inventory. */
	public static void tick() {
		long now = System.currentTimeMillis();
		if (now - lastInventoryScanMs < 5_000) return;
		lastInventoryScanMs = now;
		var player = Minecraft.getInstance().player;
		if (player == null || !SkyBlockReader.inSkyBlock()) return;
		load();
		GearData data = GearData.get();
		boolean changed = false;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getItemBySlot(slot);
			GearData.Item item = data.item(itemId(stack));
			if (item != null) changed |= rememberStats(item.id, stack);
		}
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			GearData.Item item = data.item(itemId(stack));
			if (item != null && item.slot.equals("axe")) changed |= rememberStats(item.id, stack);
		}
		if (changed) save();
	}

	/** Level of the active pet as last seen in the pets menu; -1 when unknown. */
	public static int petLevel() {
		load();
		try {
			return Integer.parseInt(seen.getOrDefault("petlevel", "-1"));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** True if the stats of this item were read from the player's own item. */
	public static boolean hasRealStats(String id) {
		load();
		return seen.containsKey("stats:" + id);
	}

	/** A copy of the item with the stats last read from the player's own item (or the item itself). */
	private static GearData.Item withRealStats(GearData.Item item) {
		String stats = seen.getOrDefault("stats:" + item.id, "DAVIDS_CLOAK".equals(item.id) ? seen.get("davids_cloak") : null);
		if (stats == null) return item;
		String[] parts = stats.split(",");
		if (parts.length != 3) return item;
		GearData.Item copy = new GearData.Item();
		copy.id = item.id;
		copy.name = item.name;
		copy.slot = item.slot;
		copy.rarity = item.rarity;
		copy.icon = item.icon;
		copy.note = item.note;
		copy.free = item.free;
		copy.best = item.best;
		copy.foraging = item.foraging;
		// Collection and Jade Dragon scaling come on top of the shown stats, so they stay.
		copy.fortunePerDigit = item.fortunePerDigit;
		copy.sweepPerDigit = item.sweepPerDigit;
		copy.fortunePerSweep = item.fortunePerSweep;
		try {
			copy.sweep = Double.parseDouble(parts[0]);
			copy.fortune = Double.parseDouble(parts[1]);
			copy.wisdom = Double.parseDouble(parts[2]);
		} catch (NumberFormatException e) {
			return item;
		}
		return copy;
	}

	public static Map<String, GearData.Item> current() {
		load();
		GearData data = GearData.get();
		Map<String, GearData.Item> result = new LinkedHashMap<>();
		var player = Minecraft.getInstance().player;

		if (player != null) {
			putWorn(result, data, player.getItemBySlot(EquipmentSlot.HEAD), "helmet");
			putWorn(result, data, player.getItemBySlot(EquipmentSlot.CHEST), "chestplate");
			putWorn(result, data, player.getItemBySlot(EquipmentSlot.LEGS), "leggings");
			putWorn(result, data, player.getItemBySlot(EquipmentSlot.FEET), "boots");

			GearData.Item bestAxe = null;

			for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
				GearData.Item item = data.item(itemId(player.getInventory().getItem(i)));
				if (item != null && item.slot.equals("axe") && (bestAxe == null || baseValue(item) > baseValue(bestAxe))) bestAxe = item;
			}

			if (bestAxe != null) result.put("axe", withRealStats(bestAxe));
		}

		for (String slot : EQUIPMENT) {
			GearData.Item item = data.item(seen.get(slot));
			if (item != null) result.put(slot, withRealStats(item));
		}

		GearData.Item pet = petFromTab(data);
		if (pet == null) pet = data.item(seen.get("pet"));
		if (pet != null) result.put("pet", withRealStats(pet));

		return result;
	}

	private static void putWorn(Map<String, GearData.Item> result, GearData data, ItemStack stack, String slot) {
		GearData.Item item = data.item(itemId(stack));
		if (item != null && item.slot.equals(slot)) result.put(slot, withRealStats(item));
	}

	/** Rough ordering of axes without a goal: Sweep counts most, as it decides how many logs fall. */
	private static double baseValue(GearData.Item item) {
		return item.sweep * 3 + item.fortune + item.wisdom * 5;
	}

	// Recommendations

	/** Sweep and Fortune a piece gives, including the Jade Dragon's scaling. */
	private record Contribution(double sweep, double fortune, double wisdom, double fortunePerSweep) {}

	private static Contribution contribution(GearData.Item item, int digits) {
		if (item == null) return new Contribution(0, 0, 0, 0);
		return new Contribution(item.sweep + item.sweepPerDigit * digits, item.fortune + item.fortunePerDigit * digits,
				item.wisdom, item.fortunePerSweep);
	}

	/** Stat change from putting {@code item} into its slot instead of {@code replaced}. */
	private static double[] delta(GearData.Item item, GearData.Item replaced, Contribution pet, double sweepNow, int digits) {
		Contribution old = contribution(replaced, digits);
		Contribution next = contribution(item, digits);
		double sweep = next.sweep() - old.sweep();
		double wisdom = next.wisdom() - old.wisdom();
		double fortune = next.fortune() - old.fortune();

		if (item.slot.equals("pet")) {
			// Only one pet is active: its Sweep-to-Fortune conversion is swapped together with it.
			fortune += next.fortunePerSweep() * (sweepNow + sweep) - old.fortunePerSweep() * sweepNow;
		} else {
			fortune += pet.fortunePerSweep() * sweep;
		}

		return new double[] {sweep, fortune, wisdom};
	}

	private static PlayerStats plus(PlayerStats stats, double sweep, double fortune, double wisdom) {
		return new PlayerStats(stats.sweep() + sweep, stats.fortune() + fortune, stats.wisdom() + wisdom,
				stats.strength(), stats.speed(), stats.dailyWhispers(), stats.giftBonus());
	}

	private static Double priceOf(String id) {
		Double bazaar = BazaarPrices.BUY.get(id);
		if (bazaar != null) return bazaar;
		AuctionPrices.request(id);
		return AuctionPrices.get(id);
	}

	/**
	 * Works out, per slot, the cheapest upgrade for what it brings.
	 *
	 * <p>Only one item per slot can be used (one armor piece each, one cloak, one pet ...), so each
	 * slot gets at most one pick: the one with the best improvement per coin. If a pricier item in
	 * the same slot gives clearly more, it is shown next to it as the stronger option. Items the
	 * player's Foraging level does not allow yet are left out and listed separately.
	 */
	public static Result recommend() {
		GearData data = GearData.get();
		Map<String, GearData.Item> now = current();
		int digits = data.assumptions.mangroveDigits;
		int level = foragingLevel();
		Objective objective = Planner.objective();

		PlayerStats stats = SkyBlockReader.statsKnown() ? SkyBlockReader.stats() : statsFromGear(now, digits);
		double base = objective.smoothValue(stats);
		Contribution pet = contribution(now.get("pet"), digits);
		Map<String, List<Pick>> bySlot = new LinkedHashMap<>();
		Map<String, Pick> lockedBySlot = new LinkedHashMap<>();

		// Slots with an item that is always the best (David's Cloak): only that one is ever suggested.
		java.util.Set<String> bestSlots = new java.util.HashSet<>();
		for (GearData.Item item : data.items) if (item.best) bestSlots.add(item.slot);

		for (GearData.Item item : data.items) {
			GearData.Item replaced = now.get(item.slot);
			if (replaced != null && replaced.id.equals(item.id)) continue;
			if (bestSlots.contains(item.slot)) {
				if (!item.best || (replaced != null && replaced.best)) continue;
			}

			double[] d = delta(item, replaced, pet, stats.sweep(), digits);
			double gain = base <= 0 ? 0 : (objective.smoothValue(plus(stats, d[0], d[1], d[2])) / base - 1) * 100;
			if (gain <= 0.05) continue;

			if (level >= 0 && item.foraging > level) {
				Pick next = new Pick(item, replaced, d[0], d[1], d[2], gain, null, false, 0, null);
				Pick known = lockedBySlot.get(item.slot);
				if (known == null || item.foraging < known.item().foraging) lockedBySlot.put(item.slot, next);
				continue;
			}

			Double price = item.free ? Double.valueOf(0) : priceOf(item.id);
			boolean bazaar = BazaarPrices.BUY.containsKey(item.id);
			// Free items (quest rewards) beat everything that costs coins.
			double perMillion = item.free ? gain * 1e6 : price == null || price <= 0 ? 0 : gain / (price / 1e6);
			bySlot.computeIfAbsent(item.slot, k -> new ArrayList<>())
					.add(new Pick(item, replaced, d[0], d[1], d[2], gain, price, bazaar, perMillion, null));
		}

		List<Pick> picks = new ArrayList<>();

		for (List<Pick> options : bySlot.values()) {
			// Best value for money among priced options; unpriced ones only if nothing has a price yet.
			Pick best = null;
			for (Pick option : options) {
				if (option.price() == null) continue;
				if (best == null || option.perMillion() > best.perMillion()) best = option;
			}

			if (best == null) {
				for (Pick option : options) {
					if (best == null || option.gainPercent() > best.gainPercent()) best = option;
				}
				picks.add(best);
				continue;
			}

			Pick stronger = null;
			for (Pick option : options) {
				if (option == best || option.gainPercent() < best.gainPercent() * 1.5 + 1) continue;
				if (stronger == null || option.gainPercent() > stronger.gainPercent()) stronger = option;
			}

			picks.add(stronger == null ? best : best.withStronger(stronger));
		}

		picks.sort(Comparator.comparing((Pick p) -> p.price() == null)
				.thenComparing(Comparator.comparingDouble(Pick::perMillion).reversed())
				.thenComparing(Comparator.comparingDouble(Pick::gainPercent).reversed()));

		// All picks at once, one item per slot, so nothing is counted twice.
		Map<String, GearData.Item> after = new LinkedHashMap<>(now);
		double totalCost = 0;
		for (Pick pick : picks) {
			if (pick.price() == null) continue;
			after.put(pick.item().slot, pick.item());
			totalCost += pick.price();
		}

		PlayerStats fromNow = statsFromGear(now, digits);
		PlayerStats fromAfter = statsFromGear(after, digits);
		PlayerStats combined = plus(stats, fromAfter.sweep() - fromNow.sweep(), fromAfter.fortune() - fromNow.fortune(),
				fromAfter.wisdom() - fromNow.wisdom());
		double combinedGain = base <= 0 ? 0 : (objective.smoothValue(combined) / base - 1) * 100;

		List<Pick> locked = new ArrayList<>(lockedBySlot.values());
		locked.sort(Comparator.comparingInt((Pick p) -> p.item().foraging));
		return new Result(picks, locked, level, totalCost, combinedGain, bySlot);
	}

	/** Stat change {sweep, fortune, wisdom} from swapping the given slots (slot to item, null = take off). */
	public static double[] gearDelta(Map<String, GearData.Item> changes) {
		Map<String, GearData.Item> now = current();
		Map<String, GearData.Item> after = new LinkedHashMap<>(now);
		changes.forEach((slot, item) -> {
			if (item == null) after.remove(slot);
			else after.put(slot, item);
		});
		int digits = GearData.get().assumptions.mangroveDigits;
		PlayerStats a = statsFromGear(now, digits), b = statsFromGear(after, digits);
		return new double[] {b.sweep() - a.sweep(), b.fortune() - a.fortune(), b.wisdom() - a.wisdom()};
	}

	/** Price of one item: Bazaar first, then the auction house; null when unknown. */
	public static Double price(String id) {
		return priceOf(id);
	}

	/** Improvement of the goal in percent for a change of the player's stats (Jade Dragon scaling included). */
	public static double gainPercent(double sweep, double fortune, double wisdom) {
		GearData data = GearData.get();
		Map<String, GearData.Item> now = current();
		int digits = data.assumptions.mangroveDigits;
		Objective objective = Planner.objective();
		PlayerStats stats = SkyBlockReader.statsKnown() ? SkyBlockReader.stats() : statsFromGear(now, digits);
		double base = objective.smoothValue(stats);
		if (base <= 0) return 0;
		fortune += contribution(now.get("pet"), digits).fortunePerSweep() * sweep;
		return (objective.smoothValue(plus(stats, sweep, fortune, wisdom)) / base - 1) * 100;
	}

	private static PlayerStats statsFromGear(Map<String, GearData.Item> gear, int digits) {
		double sweep = 0;
		double fortune = 0;
		double wisdom = 0;

		for (GearData.Item item : gear.values()) {
			Contribution c = contribution(item, digits);
			sweep += c.sweep();
			fortune += c.fortune();
			wisdom += c.wisdom();
		}

		Contribution pet = contribution(gear.get("pet"), digits);
		return new PlayerStats(sweep, fortune + pet.fortunePerSweep() * sweep, wisdom);
	}

	/** Price to buy one of something from the Bazaar, or null. */
	public static Double bazaarPrice(String id) {
		return BazaarPrices.BUY.get(id);
	}

	// Saving

	private static Path file() {
		return ModConfig.profileDirectory().resolve("gear-seen.json");
	}

	private static void load() {
		if (loaded) return;
		loaded = true;

		try {
			if (Files.exists(file())) {
				Map<String, String> read = GSON.fromJson(Files.readString(file()), new TypeToken<LinkedHashMap<String, String>>() {}.getType());
				if (read != null) seen = read;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read the saved gear", e);
		}
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(seen));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save the seen gear", e);
		}
	}
}
