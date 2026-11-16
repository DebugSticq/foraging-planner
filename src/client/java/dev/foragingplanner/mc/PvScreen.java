package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_DANGER;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import dev.foragingplanner.core.GameData;

/** Another player's foraging progress, loaded through the mod's own API server: /fp pv <player>. */
public final class PvScreen extends ForestScreen {
	private static final String[] PAGES = {"overview", "hotf", "collections", "gear", "pets", "raw"};
	private static final Item[] ICONS = {Items.PLAYER_HEAD, Items.OAK_SAPLING, Items.CHEST, Items.IRON_CHESTPLATE, Items.BONE, Items.PAPER};
	private static String requested;

	private final String name;
	private volatile JsonObject data;
	private volatile String error;
	private final long started = System.currentTimeMillis();

	private static final List<String> GEAR_SLOTS = List.of("axe", "helmet", "chestplate", "leggings", "boots", "necklace", "cloak", "belt", "gloves");

	/** A foraging item with the stats read from its lore and the slot it goes in. */
	private record Gear(LegacyItems.Entry entry, String slot, double sweep, double fortune, double wisdom, boolean worn) {
		double score() {
			return sweep * 3 + fortune + wisdom * 5;
		}
	}

	/** Best foraging item per slot, over everything the player has. */
	private final Map<String, Gear> best = new java.util.LinkedHashMap<>();
	/** What is worn or equipped right now, per slot, foraging or not. */
	private final Map<String, Gear> worn = new java.util.LinkedHashMap<>();

	public PvScreen(Screen parent, String name) {
		super(parent, PAGES, ICONS, 0);
		this.name = name;
		PvClient.lookup(name).whenComplete((result, failure) -> {
			if (failure != null) {
				Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
				error = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
			} else {
				collectGear(result);
				data = result;
			}
		});
	}

	public static void requestOpen(String name) {
		requested = name;
	}

	public static void tick(Minecraft client) {
		if (requested == null) return;
		String name = requested;
		requested = null;
		client.gui.setScreen(new PvScreen(null, name));
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("pv." + key, args);
	}

	@Override
	protected String title() {
		return data != null && data.has("name") ? data.get("name").getAsString() : name;
	}

	@Override
	protected String tagline() {
		return data != null && data.has("profile") ? tr("profile", data.get("profile").getAsString()) : tr("tagline");
	}

	@Override
	protected String pageName(int page) {
		return tr("tab." + PAGES[page]);
	}

	@Override
	protected Item logo() {
		return Items.PLAYER_HEAD;
	}

	@Override
	protected String footerHint() {
		return data != null ? tr("fetched", StatsScreen.date(data.has("fetchedAt") ? data.get("fetchedAt").getAsLong() : 0)) : "";
	}

	@Override
	protected long rebuildNs() {
		return 250_000_000L;
	}

	@Override
	protected List<Line> build(int page) {
		if (error != null) return failed();
		if (data == null) return loading();
		return switch (page) {
			case 0 -> overview();
			case 1 -> hotf();
			case 2 -> collections();
			case 3 -> gear();
			case 4 -> pets();
			default -> raw();
		};
	}

	private List<Line> loading() {
		subtitle = tr("loading", name);
		List<Line> out = new ArrayList<>();
		out.add(new Line(60, (g, x, y, w, a, h) -> {
			double t = (System.currentTimeMillis() - started) / 1000.0;
			int cx = x + w / 2, cy = y + 26;
			for (int i = 0; i < 8; i++) {
				double angle = t * 4 + i * Math.PI / 4;
				int dx = (int) Math.round(Math.cos(angle) * 12), dy = (int) Math.round(Math.sin(angle) * 12);
				round(g, cx + dx - 2, cy + dy - 2, 4, 4, 2, alpha(UI_ACCENT, a * (0.25 + 0.75 * ((i + (int) (t * 8)) % 8) / 8.0)));
			}
		}, null));
		out.add(text(tr("loading_note"), UI_DIM));
		return out;
	}

	private List<Line> failed() {
		subtitle = tr("failed", name);
		List<Line> out = new ArrayList<>();
		String code = error.contains(":") ? error.substring(0, error.indexOf(':')).trim() : error;
		String key = switch (code) {
			case "not_configured" -> "error.not_configured";
			case "unknown_player" -> "error.unknown_player";
			case "no_skyblock" -> "error.no_skyblock";
			case "rate_limited", "hypixel_rate_limited" -> "error.rate_limited";
			case "unauthorized", "revoked" -> "error.unauthorized";
			case "missing_secret" -> "error.missing_secret";
			default -> "error.other";
		};
		out.add(row("barrier", tr(key), error, null, UI_DANGER, null));
		if (code.equals("not_configured")) out.add(text(tr("setup_hint"), UI_MUTED));
		if (code.equals("missing_secret") || code.equals("server_error")) out.add(text(tr("secret_hint"), UI_MUTED));
		return out;
	}

	// Overview

	@SuppressWarnings("unchecked")
	private List<Line> overview() {
		subtitle = tr("about.overview");
		List<Line> out = new ArrayList<>();
		JsonObject foraging = obj(data, "foraging");
		boolean api = foraging != null && foraging.has("apiEnabled") && foraging.get("apiEnabled").getAsBoolean();
		if (!api) out.add(row("barrier", tr("api_off"), tr("api_off_sub"), null, UI_AMBER, null));

		double level = num(foraging, "level");
		double xp = num(foraging, "xp");
		JsonObject tree = foragingTree();
		double hotfXp = num(obj(obj(data, "skillTree"), "experience"), "foraging");
		double tokens = num(obj(obj(data, "skillTree"), "tokens_spent"), "foraging");
		double essence = num(data, "forestEssence");
		long perks = tree == null ? 0 : tree.entrySet().stream().filter(e -> e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()).count();

		// Only values Hypixel actually sends; nothing is shown as a fake 0.
		List<String> icons = new ArrayList<>(), labels = new ArrayList<>();
		List<Double> values = new ArrayList<>();
		if (level >= 0) { icons.add("iron_axe"); labels.add(tr("level")); values.add(level); }
		if (xp >= 0) { icons.add("experience_bottle"); labels.add(tr("xp")); values.add(xp); }
		if (hotfXp >= 0) { icons.add("oak_sapling"); labels.add(tr("hotf_xp")); values.add(hotfXp); }
		if (perks > 0) { icons.add("chest"); labels.add(tr("perks")); values.add((double) perks); }
		if (tokens >= 0) { icons.add("sunflower"); labels.add(tr("tokens")); values.add(tokens); }
		if (essence >= 0) { icons.add("glowstone_dust"); labels.add(tr("essence")); values.add(essence); }
		if (!icons.isEmpty()) {
			DoubleSupplier[] suppliers = new DoubleSupplier[values.size()];
			Supplier<String>[] subs = new Supplier[values.size()];
			for (int i = 0; i < values.size(); i++) {
				double v = values.get(i);
				suppliers[i] = () -> v;
				subs[i] = () -> null;
			}
			out.add(tiles(icons.toArray(String[]::new), labels.toArray(String[]::new), suppliers, subs, v -> Planner.whole(v)));
		}

		out.add(header(tr("gear_best")));
		if (best.isEmpty()) out.add(text(tr("gear_hidden"), UI_DIM));
		else out.add(doll(best));

		JsonElement pets = data.get("pets");
		if (pets != null && pets.isJsonArray()) {
			for (JsonElement p : pets.getAsJsonArray()) {
				JsonObject pet = p.getAsJsonObject();
				if (pet.has("active") && pet.get("active").getAsBoolean() && PetData.get().foraging(pet.get("type").getAsString())) {
					out.add(header(tr("active_pet")));
					out.add(petRow(pet));
				}
			}
		}
		return out;
	}

	// HOTF

	/** The foraging part of the skill tree, wherever Hypixel puts it. */
	private JsonObject foragingTree() {
		JsonObject skillTree = obj(data, "skillTree");
		JsonObject nodes = obj(skillTree, "nodes");
		JsonObject tree = obj(nodes, "foraging");
		return tree != null ? tree : obj(skillTree, "foraging");
	}

	private static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private List<Line> hotf() {
		subtitle = tr("about.hotf");
		List<Line> out = new ArrayList<>();
		JsonObject tree = foragingTree();
		if (tree == null || tree.size() == 0) {
			out.add(text(tr("hotf_none"), UI_DIM));
			return out;
		}

		for (Map.Entry<String, JsonElement> entry : tree.entrySet()) {
			if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) continue;
			int level = entry.getValue().getAsInt();
			GameData.Perk perk = null;
			for (GameData.Perk p : Planner.data.hotf.perks) if (normalize(p.name).equals(normalize(entry.getKey()))) perk = p;
			Boolean enabled = tree.has("toggle_" + entry.getKey()) ? tree.get("toggle_" + entry.getKey()).getAsBoolean() : null;
			String title = perk != null ? perk.name : StatsScreen.prettyName(entry.getKey());
			String suffix = enabled != null && !enabled ? "  · " + tr("disabled") : "";
			if (perk != null && perk.maxLevel > 0) {
				out.add(bar("oak_sapling", title + suffix, level + " / " + perk.maxLevel, level / (double) perk.maxLevel, UI_AMBER, null));
			} else {
				out.add(row("oak_sapling", title + suffix, null, tr("level_short", level), UI_ACCENT, null));
			}
		}
		return out;
	}

	// Collections

	private List<Line> collections() {
		subtitle = tr("about.collections");
		List<Line> out = new ArrayList<>();
		JsonObject collections = obj(data, "collections");
		if (collections == null || collections.size() == 0) {
			out.add(text(tr("collections_hidden"), UI_DIM));
			return out;
		}
		double max = 1;
		for (var e : collections.entrySet()) max = Math.max(max, e.getValue().getAsDouble());
		double top = max;
		collections.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue().getAsDouble(), x.getValue().getAsDouble())).forEach(e -> {
			double amount = e.getValue().getAsDouble();
			out.add(bar(iconFor(e.getKey()), StatsScreen.prettyName(e.getKey()), Planner.whole(amount), Math.log10(amount + 1) / Math.log10(top + 1), UI_ACCENT, null));
		});
		return out;
	}

	private static String iconFor(String id) {
		if (id.contains("FIG")) return "stripped_spruce_wood";
		if (id.contains("MANGROVE")) return "mangrove_log";
		if (id.contains("HELIX")) return "stripped_birch_wood";
		if (id.contains("BIRCH")) return "birch_log";
		if (id.contains("SPRUCE")) return "spruce_log";
		if (id.contains("JUNGLE")) return "jungle_log";
		if (id.contains("ACACIA")) return "acacia_log";
		if (id.contains("DARK_OAK")) return "dark_oak_log";
		if (id.contains("LOG")) return "oak_log";
		return "chest";
	}

	// Gear

	private static final Pattern SWEEP = Pattern.compile("Sweep: \\+?([\\d.,]+)");
	private static final Pattern FORTUNE = Pattern.compile("Foraging Fortune: \\+?([\\d.,]+)");
	private static final Pattern WISDOM = Pattern.compile("Foraging Wisdom: \\+?([\\d.,]+)");

	private static double stat(Pattern pattern, List<String> lore) {
		for (String line : lore) {
			Matcher m = pattern.matcher(Planner.strip(line));
			if (m.find()) {
				try {
					return Double.parseDouble(m.group(1).replace(",", ""));
				} catch (NumberFormatException ignored) {
					return 0;
				}
			}
		}
		return 0;
	}

	/** The slot from the catalog, or from the last lore line ("LEGENDARY NECKLACE", "EPIC AXE"). */
	private static String slotOf(LegacyItems.Entry e) {
		GearData.Item known = GearData.get().item(e.id());
		if (known != null && !known.slot.equals("pet")) return known.slot;
		for (int i = e.lore().size() - 1; i >= 0; i--) {
			String line = Planner.strip(e.lore().get(i)).toUpperCase(Locale.ROOT);
			if (line.isBlank()) continue;
			if (line.endsWith(" AXE")) return "axe";
			if (line.endsWith(" HELMET")) return "helmet";
			if (line.endsWith(" CHESTPLATE")) return "chestplate";
			if (line.endsWith(" LEGGINGS")) return "leggings";
			if (line.endsWith(" BOOTS")) return "boots";
			if (line.endsWith(" NECKLACE")) return "necklace";
			if (line.endsWith(" CLOAK")) return "cloak";
			if (line.endsWith(" BELT")) return "belt";
			if (line.endsWith(" GLOVES") || line.endsWith(" BRACELET")) return "gloves";
			return null;
		}
		return null;
	}

	private static Gear gear(LegacyItems.Entry e, boolean worn) {
		String slot = slotOf(e);
		if (slot == null) return null;
		GearData.Item known = GearData.get().item(e.id());
		double sweep = stat(SWEEP, e.lore()), fortune = stat(FORTUNE, e.lore()), wisdom = stat(WISDOM, e.lore());
		if (known != null) {
			sweep = Math.max(sweep, known.sweep);
			fortune = Math.max(fortune, known.fortune);
			wisdom = Math.max(wisdom, known.wisdom);
		}
		return new Gear(e, slot, sweep, fortune, wisdom, worn);
	}

	private void collectGear(JsonObject result) {
		List<LegacyItems.Entry> armor = LegacyItems.decode(str(result, "armor"), "worn");
		List<LegacyItems.Entry> equipment = LegacyItems.decode(str(result, "equipment"), "worn");
		List<LegacyItems.Entry> all = new ArrayList<>(armor);
		all.addAll(equipment);

		JsonObject storages = obj(result, "storages");
		if (storages != null) {
			all.addAll(LegacyItems.decode(str(storages, "inventory"), "inventory"));
			all.addAll(LegacyItems.decode(str(storages, "wardrobe"), "wardrobe"));
			all.addAll(LegacyItems.decode(str(storages, "enderChest"), "ender_chest"));
			if (storages.has("backpacks") && storages.get("backpacks").isJsonArray()) {
				for (JsonElement b : storages.getAsJsonArray("backpacks")) all.addAll(LegacyItems.decode(b.getAsString(), "backpack"));
			}
		}

		for (LegacyItems.Entry e : all) {
			boolean isWorn = e.source().equals("worn");
			Gear g = gear(e, isWorn);
			if (g == null) continue;
			if (isWorn) worn.putIfAbsent(g.slot(), g);
			// Only items that actually help foraging count as foraging gear.
			boolean foraging = GearData.get().item(e.id()) != null || g.score() > 0;
			if (!foraging) continue;
			Gear old = best.get(g.slot());
			if (old == null || g.score() > old.score() || (g.score() == old.score() && isWorn)) best.put(g.slot(), g);
		}
	}

	private static String str(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : null;
	}

	private static int rarityOf(LegacyItems.Entry e) {
		for (int i = e.lore().size() - 1; i >= 0; i--) {
			String line = Planner.strip(e.lore().get(i));
			if (line.isBlank()) continue;
			for (String r : List.of("MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON")) if (line.contains(r)) return rarity(r);
			break;
		}
		return UI_TEXT;
	}

	private String statText(Gear g) {
		StringBuilder b = new StringBuilder();
		if (g.sweep() > 0) b.append("§2").append(Planner.decimal(g.sweep())).append(" Sweep  ");
		if (g.fortune() > 0) b.append("§6").append(Planner.decimal(g.fortune())).append(" Fortune  ");
		if (g.wisdom() > 0) b.append("§3").append(Planner.decimal(g.wisdom())).append(" Wisdom");
		return b.toString().trim();
	}

	private void tooltip(Gear g) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(g.entry().name()));
		for (String line : g.entry().lore()) lines.add(Component.literal(line));
		lines.add(Component.literal("§8" + tr("source." + g.entry().source())));
		pendingTooltip = lines;
	}

	private List<Component> pendingTooltip;

	@Override
	protected void drawOverlay(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, double open) {
		if (pendingTooltip != null) g.setComponentTooltipForNextFrame(font, pendingTooltip, mx, my);
		pendingTooltip = null;
	}

	/** The gear as a row of slots with the real item pictures; hover shows the item's tooltip. */
	private Line doll(Map<String, Gear> gear) {
		int size = 26;
		return new Line(size + 14, (g, x, y, w, a, h) -> {
			int step = Math.max(size + 4, Math.min(44, (w - 4) / GEAR_SLOTS.size()));
			for (int i = 0; i < GEAR_SLOTS.size(); i++) {
				String slot = GEAR_SLOTS.get(i);
				Gear item = gear.get(slot);
				int sx = x + i * step;
				boolean over = mouseX >= sx && mouseX < sx + size && mouseY >= y && mouseY < y + size;
				round(g, sx, y, size, size, 6, alpha(over ? 0xFF22392A : 0xFF16241C, a));
				if (item != null) {
					ForestTheme.roundOutline(g, sx, y, size, size, 6, alpha(rarityOf(item.entry()), a * 0.7));
					if (a > 0.5) {
						var pose = g.pose();
						pose.pushMatrix();
						pose.translate(sx + 3, y + 3);
						pose.scale(1.25f, 1.25f);
						g.item(item.entry().stack(), 0, 0);
						pose.popMatrix();
					}
					if (over) tooltip(item);
				}
				String label = Planner.tr("gear.slot." + slot);
				label = ForestTheme.fit(font, label, step - 2);
				g.text(font, label, sx + size / 2 - font.width(label) / 2, y + size + 3, alpha(UI_DIM, a), false);
			}
		}, null);
	}

	private Line gearRow(Gear item) {
		String source = tr("source." + item.entry().source());
		String stats = statText(item);
		return new Line(32, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 32, a, h);
			int lift = (int) Math.round(h * 1.5);
			ForestTheme.round(g, x + 6, y + 5 - lift, 22, 22, 6, alpha(0xFF16241C, a));
			if (a > 0.5) g.item(item.entry().stack(), x + 9, y + 8 - lift);
			g.text(font, ForestTheme.fit(font, item.entry().name(), w - 130), x + 35, y + 6 - lift, alpha(0xFFFFFFFF, a), false);
			g.text(font, ForestTheme.fit(font, stats.isEmpty() ? StatsScreen.prettyName(item.entry().id()) : stats, w - 130), x + 35, y + 18 - lift, alpha(0xFFFFFFFF, a), false);
			String right = Planner.tr("gear.slot." + item.slot()) + " · " + source;
			g.text(font, right, x + w - 9 - font.width(right), y + 12 - lift, alpha(item.worn() ? UI_ACCENT : UI_MUTED, a), false);
			if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 32) tooltip(item);
		}, null);
	}

	private static int rarity(String r) {
		return switch (r) {
			case "UNCOMMON" -> 0xFF55FF55;
			case "RARE" -> 0xFF5599FF;
			case "EPIC" -> 0xFFC266FF;
			case "LEGENDARY" -> 0xFFFFAA00;
			case "MYTHIC" -> 0xFFFF66FF;
			default -> UI_TEXT;
		};
	}

	private List<Line> gear() {
		subtitle = tr("about.gear");
		List<Line> out = new ArrayList<>();
		if (best.isEmpty() && worn.isEmpty()) {
			out.add(text(tr("gear_hidden"), UI_DIM));
			return out;
		}
		out.add(header(tr("gear_best")));
		out.add(doll(best));
		for (String slot : GEAR_SLOTS) if (best.containsKey(slot)) out.add(gearRow(best.get(slot)));
		out.add(text(tr("gear_best_note"), UI_DIM));

		out.add(header(tr("gear_worn")));
		out.add(doll(worn));
		return out;
	}

	// Pets

	private final Map<String, net.minecraft.world.item.ItemStack> petHeads = new java.util.HashMap<>();

	private Line petRow(JsonObject pet) {
		String type = pet.has("type") ? pet.get("type").getAsString() : "?";
		String tier = pet.has("tier") ? pet.get("tier").getAsString() : "COMMON";
		String held = pet.has("heldItem") && !pet.get("heldItem").isJsonNull() ? StatsScreen.prettyName(pet.get("heldItem").getAsString().replace("PET_ITEM_", "")) : tr("no_item");
		boolean active = pet.has("active") && pet.get("active").getAsBoolean();
		double exp = pet.has("exp") ? pet.get("exp").getAsDouble() : 0;
		PetData.Level level = PetData.get().level(type, tier, exp);
		var head = petHeads.computeIfAbsent(type, t -> LegacyItems.head(PetData.get().textures.get(t)));
		return new Line(36, (g, x, y, w, a, h) -> {
			card(g, x, y, w, 36, a, h);
			round(g, x + 6, y + 7, 22, 22, 6, alpha(0xFF16241C, a));
			if (a > 0.5) g.item(head, x + 9, y + 10);
			String title = "[Lvl " + level.level() + "] " + StatsScreen.prettyName(type);
			g.text(font, title, x + 35, y + 6, alpha(rarity(tier), a), false);
			if (active) g.text(font, "● " + tr("active"), x + 35 + font.width(title) + 6, y + 6, alpha(UI_ACCENT, a), false);
			g.text(font, ForestTheme.fit(font, StatsScreen.prettyName(tier) + " · " + held, w - 150), x + 35, y + 18, alpha(UI_MUTED, a), false);
			int bw = 90, bx = x + w - 9 - bw;
			String lv = level.level() >= level.max() ? "MAX" : Math.round(level.progress() * 100) + "%";
			g.text(font, lv, x + w - 9 - font.width(lv), y + 8, alpha(level.level() >= level.max() ? UI_AMBER : UI_TEXT, a), false);
			round(g, bx, y + 22, bw, 4, 2, alpha(0x22FFFFFF, a));
			round(g, bx, y + 22, (int) Math.round(bw * (level.level() >= level.max() ? 1 : level.progress())), 4, 2, alpha(UI_AMBER, a));
		}, null);
	}

	private List<Line> pets() {
		subtitle = tr("about.pets");
		List<Line> out = new ArrayList<>();
		JsonElement pets = data.get("pets");
		if (pets == null || !pets.isJsonArray() || pets.getAsJsonArray().isEmpty()) {
			out.add(text(tr("pets_none"), UI_DIM));
			return out;
		}
		List<JsonObject> list = new ArrayList<>();
		for (JsonElement p : pets.getAsJsonArray()) {
			JsonObject pet = p.getAsJsonObject();
			String type = pet.has("type") ? pet.get("type").getAsString() : "";
			if (PetData.get().foraging(type) || (pet.has("active") && pet.get("active").getAsBoolean() && PetData.get().foraging(type))) list.add(pet);
		}
		list.sort((x, y) -> Double.compare(y.has("exp") ? y.get("exp").getAsDouble() : 0, x.has("exp") ? x.get("exp").getAsDouble() : 0));
		if (list.isEmpty()) out.add(text(tr("pets_none"), UI_DIM));
		for (JsonObject pet : list) out.add(petRow(pet));
		return out;
	}

	// Raw

	private List<Line> raw() {
		subtitle = tr("about.raw");
		List<Line> out = new ArrayList<>();
		JsonObject extra = obj(data, "extra");
		if (extra == null || extra.size() == 0) {
			out.add(text(tr("raw_none"), UI_DIM));
			return out;
		}
		flatten("", extra, out, 0);
		return out;
	}

	private void flatten(String prefix, JsonElement element, List<Line> out, int depth) {
		if (out.size() > 200) return;
		if (element.isJsonObject() && depth < 4) {
			for (var e : element.getAsJsonObject().entrySet()) flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out, depth + 1);
		} else {
			String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
			if (value.length() > 80) value = value.substring(0, 77) + "…";
			out.add(row(null, prefix, null, value, UI_TEXT, null));
		}
	}

	// JSON helpers

	private static JsonObject obj(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
		return parent.getAsJsonObject(key);
	}

	private static double num(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) return -1;
		try {
			return parent.get(key).getAsDouble();
		} catch (RuntimeException e) {
			return -1;
		}
	}
}
