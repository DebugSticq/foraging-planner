package dev.foragingplanner.mc;

import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Takes the player to an item's instant buy in the Bazaar: opens the search, then marks the item,
 * "Buy Instantly" and "Custom Amount" in gold, and types the amount into the sign. It never clicks
 * or buys by itself; every step is the player's click.
 */
public final class BazaarHelper {
	private static String product;
	private static long amount;
	private static long startedMs;
	private static boolean inInstantBuy;

	private BazaarHelper() {}

	/** Opens the Bazaar search for the item; amount 0 means "no amount to fill in". */
	public static void open(String name, long count) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		product = name.trim();
		amount = Math.max(0, count);
		startedMs = System.currentTimeMillis();
		inInstantBuy = false;
		client.gui.setScreen(null);
		client.player.connection.sendCommand("bz " + product);
		if (amount > 0) Toasts.show(Toasts.Kind.INFO, "gold_ingot", Planner.tr("bazaar.title", product), Planner.tr("bazaar.text", Planner.whole(amount)));
	}

	/** Opens the auction house search (for items that are not in the Bazaar). */
	public static void auction(String name) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		client.gui.setScreen(null);
		client.player.connection.sendCommand("ahs " + name.trim());
	}

	private static boolean active() {
		if (product == null) return false;
		if (System.currentTimeMillis() - startedMs > 180_000) {
			product = null;
			return false;
		}
		return true;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!active()) return;
			if (screen instanceof AbstractSignEditScreen sign) {
				if (inInstantBuy && amount > 0) {
					String text = String.valueOf(amount);
					for (int i = 0; i < text.length(); i++) sign.charTyped(new CharacterEvent(text.charAt(i)));
					product = null;
				}
				return;
			}
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			String title = Planner.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
			inInstantBuy = title.contains("instant buy");
			ScreenEvents.afterExtract(screen).register((current, g, mx, my, delta) -> highlight(g, menu, title));
		});
	}

	private static void highlight(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu, String title) {
		if (!active()) return;
		String want = product.toLowerCase(Locale.ROOT);
		boolean productPage = title.endsWith("➜ " + want) || title.endsWith("> " + want) || title.equals(want);
		int rows = Math.max(1, (menu.getMenu().slots.size() - 36) / 9);
		int left = (menu.width - 176) / 2, top = (menu.height - (114 + rows * 18)) / 2;
		long now = System.currentTimeMillis();
		var player = Minecraft.getInstance().player;
		for (Slot slot : menu.getMenu().slots) {
			if (player != null && slot.container == player.getInventory()) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = MenuReaders.name(stack).toLowerCase(Locale.ROOT);
			boolean mark;
			if (inInstantBuy) mark = name.contains("custom amount") || amount > 0 && name.matches(".*\\b" + amount + "\\b.*");
			else if (productPage) mark = name.contains("buy instantly");
			else mark = name.equals(want) || name.equals(want + " shard") || name.replace(" shard", "").equals(want.replace(" shard", ""));
			if (!mark) continue;
			int x = left + slot.x, y = top + slot.y;
			int color = (int) (0xC0 + 0x3F * Math.sin(now / 150.0)) << 24 | 0xFFC233;
			g.fill(x - 2, y - 2, x + 18, y, color);
			g.fill(x - 2, y + 16, x + 18, y + 18, color);
			g.fill(x - 2, y, x, y + 16, color);
			g.fill(x + 16, y, x + 18, y + 16, color);
		}
		String hint = inInstantBuy ? Planner.tr("bazaar.hint_amount", Planner.whole(amount)) : productPage ? Planner.tr("bazaar.hint_buy") : Planner.tr("bazaar.hint_pick", product);
		var font = Minecraft.getInstance().font;
		int w = font.width(hint) + 12;
		int x = (menu.width - w) / 2, y = Math.max(4, top - 22);
		ForestTheme.round(g, x, y, w, 14, 5, 0xE0182A1F);
		g.text(font, hint, x + 6, y + 3, 0xFFFFD24A, false);
	}
}
