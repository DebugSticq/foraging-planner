package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Small helpers drawn over Hypixel menus: attribute levels in the Hunting Box, the best upgrade
 * in the equipment menu and a price trend on Bazaar product pages.
 */
public final class MenuOverlays {
	private static List<ShardAdvisor.Plan> plans = List.of();
	private static GearAdvisor.Result result;
	private static long plansMs, resultMs;

	private MenuOverlays() {}

	private static List<ShardAdvisor.Plan> plans() {
		long now = System.currentTimeMillis();
		if (now - plansMs > 2_000) {
			plans = ShardAdvisor.plans();
			plansMs = now;
		}
		return plans;
	}

	private static GearAdvisor.Result result() {
		long now = System.currentTimeMillis();
		if (result == null || now - resultMs > 3_000) {
			result = GearAdvisor.recommend();
			resultMs = now;
		}
		return result;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			String title = Planner.strip(screen.getTitle().getString()).trim();
			String lower = title.toLowerCase(Locale.ROOT);
			ScreenEvents.afterExtract(screen).register((current, g, mx, my, delta) -> {
				if (!Planner.config.menuOverlays || !SkyBlockReader.inSkyBlock()) return;
				try {
					if (lower.contains("hunting box")) huntingBox(g, menu);
					else if (lower.contains("equipment")) equipment(g, menu);
					else if (title.contains("➜") || title.contains("➡")) bazaar(g, menu, title);
				} catch (RuntimeException e) {
					// Never break a Hypixel menu because of an overlay.
				}
			});
		});
	}

	private static int left(AbstractContainerScreen<?> menu) {
		return (menu.width - 176) / 2;
	}

	private static int top(AbstractContainerScreen<?> menu) {
		int rows = Math.max(1, (menu.getMenu().slots.size() - 36) / 9);
		return (menu.height - (114 + rows * 18)) / 2;
	}

	private static boolean playerSlot(Slot slot) {
		var player = Minecraft.getInstance().player;
		return player != null && slot.container == player.getInventory();
	}

	private static void outline(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x - 1, y - 1, x + 17, y, color);
		g.fill(x - 1, y + 16, x + 17, y + 17, color);
		g.fill(x - 1, y, x, y + 16, color);
		g.fill(x + 16, y, x + 17, y + 16, color);
	}

	private static void hint(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu, String text, int line) {
		Font font = Minecraft.getInstance().font;
		int w = font.width(text) + 12;
		int x = (menu.width - w) / 2, y = Math.max(4, top(menu) - 40 + line * 16);
		ForestTheme.round(g, x, y, w, 14, 5, 0xE0182A1F);
		g.text(font, text, x + 6, y + 3, 0xFFE8F0E0, false);
	}

	/** Level badge on each foraging shard; the three best buys get a green frame. */
	private static void huntingBox(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu) {
		List<ShardAdvisor.Plan> plans = plans();
		List<String> best = new ArrayList<>();
		for (ShardAdvisor.Plan p : plans) {
			if (best.size() >= 3) break;
			if (!p.done() && p.gainMax() > 0.005) best.add(p.shard().id);
		}
		Font font = Minecraft.getInstance().font;
		int left = left(menu), top = top(menu);
		for (Slot slot : menu.getMenu().slots) {
			if (playerSlot(slot) || slot.getItem().isEmpty()) continue;
			String id = MenuReaders.shardIdFor(MenuReaders.name(slot.getItem()).replaceAll("^\\d+x\\s*", ""));
			if (id == null) continue;
			ShardAdvisor.Plan plan = null;
			for (ShardAdvisor.Plan p : plans) if (p.shard().id.equals(id)) plan = p;
			if (plan == null) continue;
			int x = left + slot.x, y = top + slot.y;
			if (best.contains(id)) outline(g, x, y, 0xFF86BD6E);
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			pose.scale(0.5f, 0.5f);
			String label = plan.done() ? "✔" : "Lv" + plan.level();
			g.text(font, label, 1, 1, plan.done() ? 0xFF86BD6E : 0xFFFFD24A, true);
			pose.popMatrix();
		}
		if (!best.isEmpty()) hint(g, menu, Planner.tr("overlay.hunting_best"), 0);
	}

	/** The equipment slot with the best upgrade gets a gold frame, with a short note above. */
	private static void equipment(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu) {
		GearAdvisor.Result result = result();
		GearAdvisor.Pick bestPick = null;
		for (GearAdvisor.Pick pick : result.picks()) {
			if (!List.of("necklace", "cloak", "belt", "gloves").contains(pick.item().slot)) continue;
			if (bestPick == null || pick.gainPercent() > bestPick.gainPercent()) bestPick = pick;
		}
		if (bestPick == null) return;
		int left = left(menu), top = top(menu);
		String replacedId = bestPick.replaces() == null ? null : bestPick.replaces().id;
		if (replacedId != null) {
			for (Slot slot : menu.getMenu().slots) {
				ItemStack stack = slot.getItem();
				if (playerSlot(slot) || stack.isEmpty() || !replacedId.equals(GearAdvisor.itemId(stack))) continue;
				int color = (int) (0xC0 + 0x3F * Math.sin(System.currentTimeMillis() / 150.0)) << 24 | 0xFFC233;
				outline(g, left + slot.x, top + slot.y, color);
			}
		}
		hint(g, menu, Planner.tr("overlay.equipment_best", Planner.tr("gear.slot." + bestPick.item().slot), bestPick.item().name,
				String.format(Locale.US, "%.1f", bestPick.gainPercent())), 0);
	}

	/** On a Bazaar product page: price now against the seven-day average. */
	private static void bazaar(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu, String title) {
		String product = title.substring(Math.max(title.lastIndexOf('➜'), title.lastIndexOf('➡')) + 1).trim();
		String id = idFor(product);
		if (id == null) return;
		PriceHistory.Trend t = PriceHistory.trend(id);
		if (t == null) {
			hint(g, menu, Planner.tr("overlay.bazaar_collecting"), 0);
			return;
		}
		double buy = t.buyPercent();
		String verdict = buy <= -8 ? Planner.tr("overlay.bazaar_cheap") : buy >= 8 ? Planner.tr("overlay.bazaar_expensive") : Planner.tr("overlay.bazaar_normal");
		hint(g, menu, Planner.tr("overlay.bazaar_trend", GearScreenFormat.coins(t.buy()), String.format(Locale.US, "%+.0f%%", buy), verdict), 0);
	}

	private static String idFor(String product) {
		String lower = product.toLowerCase(Locale.ROOT);
		for (String id : PriceHistory.watched()) {
			String pretty = StatsScreen.prettyName(id.startsWith("SHARD_") ? id.substring(6) + "_SHARD" : id).toLowerCase(Locale.ROOT);
			if (pretty.equals(lower)) return id;
		}
		for (GearData.Item item : GearData.get().items) if (item.name.equalsIgnoreCase(product)) return item.id;
		return null;
	}
}
