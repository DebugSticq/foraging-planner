package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.Hunting;

/**
 * Reads menus the player opens: collections, the Hunting Box, the attribute menu, the equipment
 * menu for the upgrade check, and Agatha's or Miria's shop for the coupon advice.
 */
public final class MenuReaders {
	private static final Pattern PROGRESS = Pattern.compile("Progress to (.+?) ([IVXLC]+): ([\\d.,]+)%");
	private static final Pattern FRACTION = Pattern.compile("([\\d,.]+[kmb]?)\\s*/\\s*([\\d,.]+[kmb]?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern TOTAL = Pattern.compile("Total [Cc]ollected: ([\\d,]+)");
	private static final Pattern ROMAN_END = Pattern.compile("^(.+?)\\s+([IVXLC]+)$");
	private static final Pattern OWNED = Pattern.compile("(?:Owned|Stored|Amount|You have)\\D*([\\d,]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern LEVEL = Pattern.compile("Level:?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COUPON_A = Pattern.compile("([\\d,]+)x?\\s+(.*Coupons?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COUPON_B = Pattern.compile("(.*Coupons?)\\s*x([\\d,]+)", Pattern.CASE_INSENSITIVE);
	private static final int SCAN_TICKS = 10;

	/** One shop entry with its value per coupon. */
	public record Offer(String name, String currency, int cost, Double value, double perCoupon) {}

	private static List<Offer> offers = List.of();

	private MenuReaders() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			String title = Planner.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
			boolean collection = title.contains("collection");
			boolean box = title.contains("hunting box");
			boolean attributes = title.contains("attribute");
			boolean equipment = title.contains("equipment");
			boolean hotf = title.contains("heart of the forest");
			boolean chapter = title.contains("chapter") || title.contains("tasks");
			boolean fusion = title.contains("fusion");
			boolean shop = title.contains("agatha") || title.contains("miria") || title.contains("coupon");
			if (!collection && !box && !attributes && !equipment && !shop && !hotf && !chapter && !fusion) return;

			int[] ticks = {0};
			offers = List.of();
			ScreenEvents.afterTick(screen).register(current -> {
				if (++ticks[0] % SCAN_TICKS != 0) return;
				if (collection) readCollections(menu);
				if (box) readHuntingBox(menu);
				if (attributes) readAttributes(menu);
				if (equipment) UpgradeCheck.readEquipment(menu);
				if (shop) offers = readShop(menu);
				if (hotf) readHotf(menu);
				if (chapter) readChapter(menu, Planner.strip(screen.getTitle().getString()));
				if (fusion) fusionShards = readFusion(menu);
			});

			if (shop) ScreenEvents.afterExtract(screen).register((current, g, mouseX, mouseY, delta) -> drawShop(g, current.width));
			if (attributes && Planner.config.attributeOverlay) {
				ScreenEvents.afterExtract(screen).register((current, g, mouseX, mouseY, delta) -> drawAttributes(g, menu));
			}
			if (fusion) {
				fusionShards = List.of();
				ScreenEvents.afterExtract(screen).register((current, g, mouseX, mouseY, delta) -> drawFusion(g));
			}
		});
	}

	static List<String> lore(ItemStack stack) {
		List<String> lines = new ArrayList<>();
		for (var line : stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()) lines.add(Planner.strip(line.getString()).trim());
		return lines;
	}

	static String name(ItemStack stack) {
		return Planner.strip(stack.getHoverName().getString()).trim();
	}

	// Collections

	private static void readCollections(AbstractContainerScreen<?> menu) {
		Progress p = Progress.get();
		boolean changed = false;

		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			List<String> lines = lore(stack);
			Progress.Collection entry = null;

			for (int i = 0; i < lines.size(); i++) {
				Matcher progress = PROGRESS.matcher(lines.get(i));
				if (progress.find()) {
					entry = new Progress.Collection();
					entry.name = progress.group(1);
					entry.nextTier = progress.group(2);
					for (int j = i; j < Math.min(lines.size(), i + 3); j++) {
						Matcher fraction = FRACTION.matcher(lines.get(j));
						if (fraction.find()) {
							entry.current = ContestMath.parseNumber(fraction.group(1));
							entry.needed = ContestMath.parseNumber(fraction.group(2));
							break;
						}
					}
				}

				Matcher total = TOTAL.matcher(lines.get(i));
				if (total.find()) {
					if (entry == null) {
						entry = new Progress.Collection();
						Matcher roman = ROMAN_END.matcher(name(stack));
						entry.name = roman.matches() ? roman.group(1) : name(stack);
						entry.maxed = true;
					}
					entry.total = ContestMath.parseNumber(total.group(1));
				}
			}

			if (entry == null || entry.name.isBlank() || !relevant(entry.name)) continue;
			entry.updated = System.currentTimeMillis();
			p.collections.put(entry.name, entry);
			changed = true;
		}

		if (changed) Progress.changed();
	}

	/** Only wood and foraging drops are kept, so the list stays about foraging. */
	private static boolean relevant(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		for (var tree : Planner.data.trees) if (n.contains(tree.name.toLowerCase(Locale.ROOT))) return true;
		return n.contains("log") || n.contains("wood") || n.contains("essence") || n.contains("vine") || n.contains("sap");
	}

	/** Bazaar id of the logs a collection counts, or null. */
	public static String logId(String collection) {
		String n = collection.toLowerCase(Locale.ROOT);
		for (var tree : Planner.data.trees) if (n.contains(tree.name.toLowerCase(Locale.ROOT))) return tree.bazaarId;
		return null;
	}

	private static final Pattern HOTF_TIER = Pattern.compile("Progress to (?:Tier|Level) ([\\dIVXLC]+)", Pattern.CASE_INSENSITIVE);

	/** The Heart of the Forest menu shows the progress to the next tier on one of its items. */
	private static void readHotf(AbstractContainerScreen<?> menu) {
		for (Slot slot : menu.getMenu().slots) {
			List<String> lines = lore(slot.getItem());
			for (int i = 0; i < lines.size(); i++) {
				Matcher tier = HOTF_TIER.matcher(lines.get(i));
				if (!tier.find()) continue;
				for (int j = i; j < Math.min(lines.size(), i + 3); j++) {
					Matcher fraction = FRACTION.matcher(lines.get(j));
					if (!fraction.find()) continue;
					Progress p = Progress.get();
					p.hotfNextTier = tier.group(1);
					p.hotfCurrent = ContestMath.parseNumber(fraction.group(1));
					p.hotfNeeded = ContestMath.parseNumber(fraction.group(2));
					p.hotfRead = System.currentTimeMillis();
					Progress.changed();
					return;
				}
			}
		}
	}

	// Chapters (Moonglade Marsh tasks)

	private static void readChapter(AbstractContainerScreen<?> menu, String title) {
		Progress p = Progress.get();
		var tasks = p.chapters.computeIfAbsent(title, k -> new java.util.LinkedHashMap<>());
		boolean changed = false;
		for (Slot slot : menu.getMenu().slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = name(stack);
			List<String> lines = lore(stack);
			if (name.isBlank() || lines.isEmpty() || name.equalsIgnoreCase("Close") || name.equalsIgnoreCase("Go Back")) continue;
			String all = String.join(" ", lines).toUpperCase(Locale.ROOT);
			boolean done = all.contains("COMPLETED") || all.contains("CLAIMED") || name.startsWith("✔");
			boolean task = done || all.contains("NOT COMPLETED") || all.contains("REWARD") || all.contains("PROGRESS");
			if (!task) continue;
			String hint = lines.getFirst();
			Progress.Task old = tasks.get(name);
			if (old == null || old.done != done) {
				Progress.Task t = new Progress.Task();
				t.done = done;
				t.hint = hint;
				tasks.put(name, t);
				changed = true;
			}
		}
		if (changed) Progress.changed();
	}

	// Attribute overlay (/am)

	private static final Pattern SHARD_PROGRESS = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");

	/** One attribute in the menu: level, shards still missing for the next level and their price. */
	private record Attr(Slot slot, String name, int level, int missing, Double cost) {}

	private static void drawAttributes(net.minecraft.client.gui.GuiGraphicsExtractor g, AbstractContainerScreen<?> menu) {
		var font = net.minecraft.client.Minecraft.getInstance().font;
		List<Attr> attrs = new ArrayList<>();
		for (Slot slot : menu.getMenu().slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = name(stack);
			int level = -1;
			Matcher roman = ROMAN_END.matcher(name);
			if (roman.matches()) { level = roman(roman.group(2)); name = roman.group(1); }
			int missing = -1;
			String shard = null;
			for (String line : lore(stack)) {
				Matcher lv = LEVEL.matcher(line);
				if (lv.find()) level = Integer.parseInt(lv.group(1));
				Matcher pr = SHARD_PROGRESS.matcher(line);
				if (missing < 0 && pr.find() && !line.contains("%")) {
					int have = Integer.parseInt(pr.group(1).replace(",", ""));
					int need = Integer.parseInt(pr.group(2).replace(",", ""));
					if (need > have && need < 100_000) missing = need - have;
				}
				if (shard == null && line.toLowerCase(Locale.ROOT).contains("shard")) {
					Matcher m = Pattern.compile("([A-Z][A-Za-z' ]+ Shard)").matcher(line);
					if (m.find()) shard = m.group(1);
				}
			}
			if (level < 0 || name.isBlank()) continue;
			Double price = shard == null ? null : BazaarPrices.BUY.get(Hunting.shardId(shard));
			attrs.add(new Attr(slot, name, level, missing, price == null || missing < 0 ? null : price * missing));
		}
		if (attrs.isEmpty()) return;

		int rows = Math.max(1, (menu.getMenu().slots.size() - 36) / 9);
		int left = (menu.width - 176) / 2, top = (menu.height - (114 + rows * 18)) / 2;
		var pose = g.pose();
		for (Attr a : attrs) {
			String lvl = String.valueOf(a.level());
			pose.pushMatrix();
			pose.translate(left + a.slot().x + 1, top + a.slot().y + 1);
			pose.scale(0.75f, 0.75f);
			g.text(font, lvl, 0, 0, a.level() >= 10 ? UI_AMBER : 0xFFFFFFFF, true);
			pose.popMatrix();
		}

		List<Attr> priced = new ArrayList<>(attrs.stream().filter(a -> a.cost() != null && a.level() < 10).toList());
		priced.sort((x, y) -> Double.compare(x.cost(), y.cost()));
		int n = Math.min(8, priced.size());
		int w = 170, h = 22 + Math.max(1, n) * 11 + 4;
		int x = Math.max(4, left - w - 6), y = top;
		round(g, x, y, w, h, 7, alpha(UI_PANEL, 0.94));
		roundOutline(g, x, y, w, h, 7, alpha(UI_ACCENT, 0.5));
		g.text(font, Planner.tr("attr.title"), x + 7, y + 6, UI_ACCENT, false);
		if (n == 0) g.text(font, Planner.tr("attr.none"), x + 7, y + 19, UI_DIM, false);
		for (int i = 0; i < n; i++) {
			Attr a = priced.get(i);
			String cost = GearScreenFormat.coins(a.cost());
			String label = a.name() + " " + (a.level() + 1);
			g.text(font, fit(font, label, w - 24 - font.width(cost)), x + 7, y + 19 + i * 11, UI_TEXT, false);
			g.text(font, cost, x + w - 7 - font.width(cost), y + 19 + i * 11, UI_AMBER, false);
		}
	}

	// Fusion machine

	/** A shard visible in the fusion machine, with its Bazaar price. */
	public record FusionShard(String name, Double price) {}

	private static List<FusionShard> fusionShards = List.of();

	private static List<FusionShard> readFusion(AbstractContainerScreen<?> menu) {
		java.util.Map<String, FusionShard> found = new java.util.LinkedHashMap<>();
		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = name(stack).replaceAll("^\\d+x\\s*", "");
			if (!name.toLowerCase(Locale.ROOT).endsWith("shard")) continue;
			String id = Hunting.shardId(name);
			found.putIfAbsent(id, new FusionShard(name, BazaarPrices.BUY.get(id)));
		}
		List<FusionShard> list = new ArrayList<>(found.values());
		list.sort((a, b) -> Double.compare(b.price() == null ? 0 : b.price(), a.price() == null ? 0 : a.price()));
		return list;
	}

	private static void drawFusion(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int rows = Math.min(10, fusionShards.size());
		int w = 170, h = 32 + rows * 11;
		int x = 6, y = 6;
		round(g, x, y, w, h, 7, alpha(UI_PANEL, 0.94));
		roundOutline(g, x, y, w, h, 7, alpha(UI_ACCENT, 0.5));
		g.text(font, Planner.tr("fusion.title"), x + 7, y + 6, UI_ACCENT, false);
		for (int i = 0; i < rows; i++) {
			FusionShard s = fusionShards.get(i);
			String price = s.price() == null ? "?" : GearScreenFormat.coins(s.price());
			g.text(font, fit(font, s.name(), w - 24 - font.width(price)), x + 7, y + 19 + i * 11, UI_TEXT, false);
			g.text(font, price, x + w - 7 - font.width(price), y + 19 + i * 11, s.price() == null ? UI_DIM : UI_AMBER, false);
		}
		g.text(font, Planner.tr("fusion.hint"), x + 7, y + h - 11, UI_DIM, false);
	}

	// Hunting Box and attributes

	private static void readHuntingBox(AbstractContainerScreen<?> menu) {
		Progress p = Progress.get();
		Map<String, Long> found = new HashMap<>();

		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = name(stack).replaceAll("^\\d+x\\s*", "");
			if (!name.toLowerCase(Locale.ROOT).contains("shard")) continue;

			long owned = stack.getCount();
			for (String line : lore(stack)) {
				Matcher m = OWNED.matcher(line);
				if (m.find()) { owned = Long.parseLong(m.group(1).replace(",", "")); break; }
			}
			found.merge(Hunting.shardId(name), owned, Math::max);
		}

		if (found.isEmpty()) return;
		p.huntingBox.putAll(found);
		p.huntingBoxRead = System.currentTimeMillis();
		Progress.changed();
	}

	private static void readAttributes(AbstractContainerScreen<?> menu) {
		Progress p = Progress.get();
		boolean changed = false;

		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = name(stack);
			int level = -1;

			Matcher roman = ROMAN_END.matcher(name);
			if (roman.matches()) {
				level = roman(roman.group(2));
				name = roman.group(1);
			}
			for (String line : lore(stack)) {
				Matcher m = LEVEL.matcher(line);
				if (m.find()) { level = Integer.parseInt(m.group(1)); break; }
			}

			if (level >= 0 && level <= 10 && !name.isBlank()) {
				Integer old = p.attributeLevels.put(name, level);
				changed |= old == null || old != level;
			}
		}

		if (changed) Progress.changed();
	}

	static int roman(String text) {
		int total = 0, previous = 0;
		for (int i = text.length() - 1; i >= 0; i--) {
			int v = switch (text.charAt(i)) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100; default -> 0; };
			total += v < previous ? -v : v;
			previous = Math.max(previous, v);
		}
		return total;
	}

	// Coupon shop

	private static List<Offer> readShop(AbstractContainerScreen<?> menu) {
		List<Offer> list = new ArrayList<>();

		for (Slot slot : menu.getMenu().slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			List<String> lines = lore(stack);
			int cost = -1;
			String currency = "";

			for (String line : lines) {
				Matcher a = COUPON_A.matcher(line);
				Matcher b = COUPON_B.matcher(line);
				if (a.find()) { cost = Integer.parseInt(a.group(1).replace(",", "")); currency = a.group(2); break; }
				if (b.find()) { cost = Integer.parseInt(b.group(2).replace(",", "")); currency = b.group(1); break; }
			}

			if (cost <= 0) continue;
			String id = GearAdvisor.itemId(stack);
			Double value = null;
			if (!id.isEmpty()) {
				value = Planner.PRICES.get(id);
				if (value == null) value = Watchers.price(id);
			}
			double total = value == null ? 0 : value * stack.getCount();
			list.add(new Offer(name(stack), currency.trim(), cost, value == null ? null : total, value == null ? 0 : total / cost));
		}

		list.sort((x, y) -> Double.compare(y.perCoupon(), x.perCoupon()));
		return list;
	}

	public static List<Offer> offers() {
		return offers;
	}

	private static void drawShop(net.minecraft.client.gui.GuiGraphicsExtractor g, int screenWidth) {
		if (offers.isEmpty()) return;
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int rows = Math.min(8, offers.size());
		int w = 168;
		int h = 22 + rows * 11;
		int x = 6;
		int y = 6;

		round(g, x, y, w, h, 7, alpha(UI_PANEL, 0.94));
		roundOutline(g, x, y, w, h, 7, alpha(UI_AMBER, 0.5));
		g.text(font, Planner.tr("coupon.title"), x + 7, y + 6, UI_AMBER, false);

		for (int i = 0; i < rows; i++) {
			Offer offer = offers.get(i);
			int ly = y + 19 + i * 11;
			String value = offer.value() == null ? "?" : GearScreenFormat.coins(offer.perCoupon()) + "/C";
			int color = i == 0 && offer.value() != null ? UI_ACCENT : offer.value() == null ? UI_DIM : UI_TEXT;
			g.text(font, fit(font, (i + 1) + ". " + offer.name(), w - 20 - font.width(value)), x + 7, ly, color, false);
			g.text(font, value, x + w - 7 - font.width(value), ly, offer.value() == null ? UI_DIM : UI_MUTED, false);
		}
	}
}
