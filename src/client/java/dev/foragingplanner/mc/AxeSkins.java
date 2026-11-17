package dev.foragingplanner.mc;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Custom looks for foraging axes. Only the drawing on this client changes: other players still
 * see the normal axe, and the item itself is not touched.
 */
public final class AxeSkins {
	public static final List<String> SKINS = List.of("off", "moonblade", "rootcleaver", "emberbit");

	/** Hypixel axes that are not in the gear list but should get the skin too. */
	private static final Set<String> EXTRA = Set.of("TREECAPITATOR_AXE", "JUNGLE_AXE", "EFFICIENT_AXE", "PROMISING_AXE", "SWEET_AXE", "ROOKIE_AXE");

	/** SkyBlock id per item data, so the tag is not copied every frame. */
	private static final Map<CustomData, String> IDS = new IdentityHashMap<>();
	private static String loadedSkin = "";
	private static boolean loaded3d;
	private static Identifier model;

	private AxeSkins() {}

	/** The model to draw instead of the item's own, or null to draw it normally. */
	public static Identifier override(ItemStack stack) {
		String skin = Planner.config.axeSkin;
		if (skin == null || skin.equals("off") || stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		String id = IDS.get(data);
		if (id == null) {
			if (IDS.size() > 512) IDS.clear();
			id = data.copyTag().getStringOr("id", "");
			IDS.put(data, id);
		}
		if (id.isEmpty() || !isAxe(id)) return null;
		if (!skin.equals(loadedSkin) || loaded3d != Planner.config.axeSkin3d) {
			loadedSkin = skin;
			loaded3d = Planner.config.axeSkin3d;
			model = Identifier.fromNamespaceAndPath(Planner.MOD_ID, "axe_" + skin + (loaded3d ? "_3d" : ""));
		}
		return model;
	}

	private static boolean isAxe(String id) {
		if (EXTRA.contains(id)) return true;
		GearData.Item item = GearData.get().item(id);
		if (item != null) return item.slot.equals("axe");
		return Planner.config.axeSkinAll && (id.endsWith("_AXE") || id.endsWith("_CHOPPER"));
	}
}
