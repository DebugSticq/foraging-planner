package dev.foragingplanner.mc;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Turns the item data Hypixel's API sends (old 1.8 format, Base64 + gzip) into items this game can
 * draw: the right base item, leather colour, head textures and the enchantment glint.
 */
public final class LegacyItems {
	/** One decoded item. {@code name} and {@code lore} keep Hypixel's colour codes. */
	public record Entry(ItemStack stack, String id, String name, List<String> lore, String source) {
		public String plainName() {
			return Planner.strip(name);
		}
	}

	private static final Map<Integer, String> IDS = Map.ofEntries(
			Map.entry(298, "leather_helmet"), Map.entry(299, "leather_chestplate"), Map.entry(300, "leather_leggings"), Map.entry(301, "leather_boots"),
			Map.entry(302, "chainmail_helmet"), Map.entry(303, "chainmail_chestplate"), Map.entry(304, "chainmail_leggings"), Map.entry(305, "chainmail_boots"),
			Map.entry(306, "iron_helmet"), Map.entry(307, "iron_chestplate"), Map.entry(308, "iron_leggings"), Map.entry(309, "iron_boots"),
			Map.entry(310, "diamond_helmet"), Map.entry(311, "diamond_chestplate"), Map.entry(312, "diamond_leggings"), Map.entry(313, "diamond_boots"),
			Map.entry(314, "golden_helmet"), Map.entry(315, "golden_chestplate"), Map.entry(316, "golden_leggings"), Map.entry(317, "golden_boots"),
			Map.entry(258, "iron_axe"), Map.entry(271, "wooden_axe"), Map.entry(275, "stone_axe"), Map.entry(279, "diamond_axe"), Map.entry(286, "golden_axe"),
			Map.entry(267, "iron_sword"), Map.entry(268, "wooden_sword"), Map.entry(272, "stone_sword"), Map.entry(276, "diamond_sword"), Map.entry(283, "golden_sword"),
			Map.entry(261, "bow"), Map.entry(346, "fishing_rod"), Map.entry(359, "shears"), Map.entry(6, "oak_sapling"), Map.entry(280, "stick"),
			Map.entry(339, "paper"), Map.entry(340, "book"), Map.entry(403, "enchanted_book"), Map.entry(288, "feather"), Map.entry(334, "leather"),
			Map.entry(369, "blaze_rod"), Map.entry(399, "nether_star"), Map.entry(388, "emerald"), Map.entry(264, "diamond"), Map.entry(266, "gold_ingot"),
			Map.entry(265, "iron_ingot"), Map.entry(371, "gold_nugget"), Map.entry(287, "string"), Map.entry(352, "bone"), Map.entry(341, "slime_ball"),
			Map.entry(318, "flint"), Map.entry(296, "wheat"), Map.entry(38, "poppy"), Map.entry(37, "dandelion"), Map.entry(18, "oak_leaves"),
			Map.entry(166, "barrier"), Map.entry(165, "slime_block"), Map.entry(54, "chest"), Map.entry(130, "ender_chest"), Map.entry(385, "fire_charge"), Map.entry(378, "magma_cream"));

	private LegacyItems() {}

	/** Decodes one Base64 inventory from the API. */
	public static List<Entry> decode(String base64, String source) {
		List<Entry> items = new ArrayList<>();
		if (base64 == null || base64.isEmpty()) return items;
		try {
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(base64)), NbtAccounter.unlimitedHeap());
			ListTag list = root.getListOrEmpty("i");
			for (int i = 0; i < list.size(); i++) {
				CompoundTag stack = list.getCompoundOrEmpty(i);
				CompoundTag tag = stack.getCompoundOrEmpty("tag");
				if (tag.isEmpty()) continue;
				CompoundTag display = tag.getCompoundOrEmpty("display");
				String id = tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
				List<String> lore = new ArrayList<>();
				ListTag loreTag = display.getListOrEmpty("Lore");
				for (int l = 0; l < loreTag.size(); l++) lore.add(loreTag.get(l).asString().orElse(""));
				items.add(new Entry(build(stack, tag, display, id), id, display.getStringOr("Name", "?"), lore, source));
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read {} of the viewed player", source, e);
		}
		return items;
	}

	private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();

	/**
	 * The best picture for one item. Equipment (necklaces, cloaks, belts, gloves) and many armor
	 * pieces are textured heads; when the upgrade loses the head or its texture, it is rebuilt here.
	 */
	private static ItemStack build(CompoundTag stack, CompoundTag tag, CompoundTag display, String id) {
		String texture = texture(tag);
		ItemStack upgraded = upgrade(stack);
		ItemStack result;
		if (!texture.isEmpty()) {
			result = upgraded != null && upgraded.is(Items.PLAYER_HEAD) ? upgraded : toStack(stack, tag, display, id, true);
			if (result.get(DataComponents.PROFILE) == null) applyTexture(result, tag.getCompoundOrEmpty("SkullOwner").getStringOr("Id", ""), texture);
		} else if (upgraded != null && !upgraded.is(Items.PAPER)) {
			result = upgraded;
		} else {
			result = toStack(stack, tag, display, id, false);
		}
		// Hypixel now sends many items as paper with an item model from its resource pack.
		String model = tag.getStringOr("ItemModel", "");
		if (!model.isEmpty() && modelExists(model)) {
			result.set(DataComponents.ITEM_MODEL, Identifier.parse(model));
			return result;
		}
		Identifier packModel = packModel(id);
		if (packModel != null) {
			result.set(DataComponents.ITEM_MODEL, packModel);
			return result;
		}
		if (result.is(Items.PAPER)) {
			// Without the resource pack: show the item from the catalog, if the mod knows it.
			GearData.Item known = GearData.get().item(id);
			if (known != null && known.icon != null && !known.icon.equals("paper")) {
				var item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(known.icon));
				if (item.isPresent()) {
					ItemStack copy = new ItemStack(item.get(), result.getCount());
					copy.set(DataComponents.CUSTOM_NAME, result.get(DataComponents.CUSTOM_NAME));
					result = copy;
				}
			}
		}
		if (result.is(Items.PAPER) && LOGGED.add(id)) {
			Planner.LOGGER.info("[FP] PV item without picture: {} raw id={} damage={} upgraded={} model={} keys={}", id, stack.get("id"),
					stack.getShortOr("Damage", (short) 0), upgraded == null ? "null" : BuiltInRegistries.ITEM.getKey(upgraded.getItem()), model, tag.keySet());
		}
		return result;
	}

	private static volatile Map<String, Identifier> packIndex = Map.of();
	private static long packIndexedAt;

	/**
	 * Hypixel's resource pack holds an item model for almost every SkyBlock item, named after the
	 * item id (".../equipment/ender_necklace"). The API leaves the model out, so look it up by name.
	 */
	private static Identifier packModel(String skyblockId) {
		if (skyblockId == null || skyblockId.isEmpty()) return null;
		Map<String, Identifier> index = packIndex;
		long now = System.currentTimeMillis();
		if (index.isEmpty() && now - packIndexedAt > 10_000) {
			packIndexedAt = now;
			index = buildPackIndex();
			packIndex = index;
		}
		String key = skyblockId.toLowerCase(java.util.Locale.ROOT);
		Identifier found = index.get(key);
		if (found == null && key.startsWith("starred_")) found = index.get(key.substring(8));
		if (found == null) found = index.get(key.replace(":", "_"));
		return found != null && modelExists(found.toString()) ? found : null;
	}

	/** How many pack models are known; 0 when the Hypixel pack is not loaded (or not indexed yet). */
	public static int packIndexSize() {
		if (packIndex.isEmpty()) packModel("DUMMY_ITEM");
		return packIndex.size();
	}

	/** An icon that uses the Hypixel resource pack model for a SkyBlock id, or null when the pack has none. */
	public static ItemStack packIcon(String skyblockId, Item fallback) {
		if (skyblockId == null) return null;
		Identifier model = packModel(skyblockId);
		if (model == null && skyblockId.startsWith("SHARD_")) model = packModel(skyblockId.substring(6) + "_SHARD");
		if (model == null && skyblockId.startsWith("ENCHANTMENT_")) return null;
		if (model == null) return null;
		ItemStack stack = new ItemStack(fallback);
		stack.set(DataComponents.ITEM_MODEL, model);
		return stack;
	}

	private static Map<String, Identifier> buildPackIndex() {
		Map<String, Identifier> out = new java.util.HashMap<>();
		try {
			var resources = net.minecraft.client.Minecraft.getInstance().getResourceManager()
					.listResources("items", id -> id.getNamespace().startsWith("hypixel") && id.getPath().endsWith(".json"));
			for (Identifier file : resources.keySet()) {
				String path = file.getPath().substring("items/".length(), file.getPath().length() - ".json".length());
				String name = path.substring(path.lastIndexOf('/') + 1);
				out.putIfAbsent(name, Identifier.fromNamespaceAndPath(file.getNamespace(), path));
			}
			Planner.LOGGER.info("[FP] Indexed {} item models from the Hypixel resource pack", out.size());
		} catch (RuntimeException e) {
			Planner.LOGGER.debug("Could not index the resource pack", e);
		}
		return out;
	}

	/** True when the loaded resource packs have this item model (Hypixel's pack is only loaded on the server). */
	private static boolean modelExists(String model) {
		try {
			Identifier id = Identifier.tryParse(model);
			if (id == null) return false;
			var models = net.minecraft.client.Minecraft.getInstance().getModelManager();
			var missing = models.getItemModel(Identifier.fromNamespaceAndPath("foragingplanner", "missing_probe"));
			return models.getItemModel(id) != missing;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String texture(CompoundTag tag) {
		ListTag textures = tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties").getListOrEmpty("textures");
		return textures.isEmpty() ? "" : textures.getCompoundOrEmpty(0).getStringOr("Value", "");
	}

	/** Data version of item stacks from before Minecraft 1.9, which is what Hypixel's API stores. */
	private static final int LEGACY_VERSION = 99;

	/**
	 * Lets the game's own world upgrader turn the 1.8 item into a current one, the same way old
	 * worlds are loaded: right item, leather colour, head texture, glint, name and lore. Returns
	 * null when that does not work, then the simple mapping below is used.
	 */
	private static ItemStack upgrade(CompoundTag stack) {
		try {
			var level = net.minecraft.client.Minecraft.getInstance().level;
			if (level == null) return null;
			int current = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
			var fixed = net.minecraft.util.datafix.DataFixers.getDataFixer().update(net.minecraft.util.datafix.fixes.References.ITEM_STACK,
					new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, (net.minecraft.nbt.Tag) stack.copy()), LEGACY_VERSION, current);
			var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, level.registryAccess());
			ItemStack result = ItemStack.CODEC.parse(ops, fixed.getValue()).result().orElse(null);
			return result == null || result.isEmpty() ? null : result;
		} catch (RuntimeException | LinkageError e) {
			Planner.LOGGER.debug("Could not upgrade an item from the API", e);
			return null;
		}
	}

	private static Item baseItem(CompoundTag stack, String skyblockId) {
		var idTag = stack.get("id");
		int damage = stack.getShortOr("Damage", (short) 0);

		if (idTag != null) {
			var text = idTag.asString();
			if (text.isPresent() && !text.get().isEmpty() && !Character.isDigit(text.get().charAt(0))) {
				String name = text.get().replace("minecraft:", "");
				if (name.equals("skull")) return damage == 3 ? Items.PLAYER_HEAD : Items.SKELETON_SKULL;
				var item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(name));
				if (item.isPresent()) return item.get();
			}
			int numeric = idTag.asInt().orElse(-1);
			if (numeric == 397) {
				return switch (damage) {
					case 0 -> Items.SKELETON_SKULL;
					case 1 -> Items.WITHER_SKELETON_SKULL;
					case 2 -> Items.ZOMBIE_HEAD;
					case 4 -> Items.CREEPER_HEAD;
					default -> Items.PLAYER_HEAD;
				};
			}
			if (numeric == 17 || numeric == 162) {
				String[] logs = numeric == 17 ? new String[] {"oak_log", "spruce_log", "birch_log", "jungle_log"} : new String[] {"acacia_log", "dark_oak_log"};
				return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(logs[Math.floorMod(damage, logs.length)]));
			}
			String mapped = IDS.get(numeric);
			if (mapped != null) return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(mapped));
		}

		GearData.Item known = GearData.get().item(skyblockId);
		if (known != null) return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(known.icon)).orElse(Items.PAPER);
		return Items.PAPER;
	}

	private static ItemStack toStack(CompoundTag stack, CompoundTag tag, CompoundTag display, String skyblockId, boolean head) {
		ItemStack item = new ItemStack(head ? Items.PLAYER_HEAD : baseItem(stack, skyblockId), Math.max(1, stack.getByteOr("Count", (byte) 1)));
		item.set(DataComponents.CUSTOM_NAME, Component.literal(display.getStringOr("Name", "")));

		if (display.contains("color")) item.set(DataComponents.DYED_COLOR, new DyedItemColor(display.getIntOr("color", DyedItemColor.LEATHER_COLOR)));
		if (!tag.getListOrEmpty("ench").isEmpty() || tag.getCompound("ench").isPresent()) item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

		if (item.is(Items.PLAYER_HEAD)) {
			CompoundTag owner = tag.getCompoundOrEmpty("SkullOwner");
			ListTag textures = owner.getCompoundOrEmpty("Properties").getListOrEmpty("textures");
			String value = textures.isEmpty() ? "" : textures.getCompoundOrEmpty(0).getStringOr("Value", "");
			if (!value.isEmpty()) applyTexture(item, owner.getStringOr("Id", ""), value);
		}
		return item;
	}

	/** Puts a head texture on a player head, built through the game's own profile format. */
	public static void applyTexture(ItemStack head, String uuid, String texture) {
		try {
			UUID id;
			try {
				id = UUID.fromString(uuid);
			} catch (IllegalArgumentException e) {
				id = UUID.nameUUIDFromBytes(texture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			JsonObject json = new JsonObject();
			JsonArray ints = new JsonArray();
			long most = id.getMostSignificantBits(), least = id.getLeastSignificantBits();
			ints.add((int) (most >> 32));
			ints.add((int) most);
			ints.add((int) (least >> 32));
			ints.add((int) least);
			json.add("id", ints);
			JsonArray properties = new JsonArray();
			JsonObject property = new JsonObject();
			property.addProperty("name", "textures");
			property.addProperty("value", texture);
			properties.add(property);
			json.add("properties", properties);
			ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, json).result().ifPresent(profile -> head.set(DataComponents.PROFILE, profile));
		} catch (RuntimeException e) {
			Planner.LOGGER.debug("Could not apply head texture", e);
		}
	}

	/** A player head with a texture, for pets. */
	public static ItemStack head(String texture) {
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		if (texture != null && !texture.isEmpty()) applyTexture(head, "", texture);
		return head;
	}
}
