package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.Hunting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Critter Safari helpers: run tracker with capsule warning and summary, shard prices over critters,
 * Sparkling alert, Critterdex and milestone reader, tickets, items on the ground, Honeyed Helix
 * markers, Safari Essence per hour and catches of party members.
 *
 * Hypixel's exact chat wording is not known, so the patterns are loose and every unknown Safari
 * line is written to the log once ("[FP-Safari]"), so they can be made exact later.
 */
public final class Safari {
	private static final Pattern LEVEL_TAG = Pattern.compile("\\[[^]]*]|[\\d,.]+/?[\\d,.]*[❤♥]|[❤♥✦✧★]");
	private static final Pattern MY_CATCH = Pattern.compile("(?i)^(?:you |nice! you |success! you )?(?:caught|captured|catch(?:ed)?) (?:an? |the )?(?:sparkling )?([A-Za-z' -]{3,30}?)(?:!|\\.| with| in |$)");
	private static final Pattern OTHER_CATCH = Pattern.compile("^(?:\\[[^]]+] )?([A-Za-z0-9_]{3,16}) (?:caught|captured) (?:an? |the )?(?:sparkling )?([A-Za-z' -]{3,30}?)(?:!|\\.|$)");
	private static final Pattern FAIL = Pattern.compile("(?i)(broke free|escaped|got away|slipped out|missed)");
	private static final Pattern ESSENCE = Pattern.compile("(?i)\\+?([\\d,]+)x? Safari Essence|Safari Essence x?([\\d,]+)");
	private static final Pattern FRACTION = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");
	private static final Pattern COUNT = Pattern.compile("(?i)^(\\d+)x\\s*");

	/** A critter, item or Honeyed Helix shown on screen. */
	public record Mark(Vec3 pos, String name, String detail, int color, boolean alert) {}

	private static final List<Mark> MARKS = new ArrayList<>();
	private static final Map<String, long[]> HONEY = new LinkedHashMap<>();
	private static final Map<String, Vec3> HONEY_POS = new LinkedHashMap<>();
	private static final Map<String, Integer> PARTY = new LinkedHashMap<>();
	private static final Deque<long[]> ESSENCE_SAMPLES = new ArrayDeque<>();
	private static final Set<String> LOGGED = new HashSet<>();
	private static final Set<Integer> SPARKLING_SEEN = new HashSet<>();

	// The current run
	private static boolean inRun;
	private static long runStart, leftAt;
	private static int capsulesStart = -1, capsules = -1, capsulesWarned = Integer.MAX_VALUE;
	private static int catches, fails;
	private static long essence;
	private static final List<String> caughtThisRun = new ArrayList<>();
	private static boolean ticketHinted;

	private Safari() {}

	/** Inside the Critter Safari itself (not the entrance). */
	public static boolean inside() {
		String area = SkyBlockReader.area().toLowerCase(Locale.ROOT);
		return area.contains("safari") && !area.contains("entrance") || area.contains(" biome");
	}

	public static boolean nearSafari() {
		String area = SkyBlockReader.area().toLowerCase(Locale.ROOT);
		return area.contains("safari") || area.contains(" biome");
	}

	public static void register() {
		ServerMessages.register((component, overlay) -> {
			if (overlay || !SkyBlockReader.inSkyBlock()) return;
			String text = Planner.strip(component.getString()).trim();
			if (text.isEmpty() || text.startsWith("[FP]")) return;
			onChat(text);
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			String title = Planner.strip(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
			boolean dex = title.contains("critterdex");
			boolean milestones = title.contains("milestone") || title.contains("andrea");
			if (!dex && !milestones) return;
			if (Planner.config.safariDebug) debug("menu: " + title);
			int[] ticks = {0};
			ScreenEvents.afterTick(screen).register(current -> {
				if (++ticks[0] % 10 != 0) return;
				if (dex) readDex(menu, ticks[0] == 10);
				readMilestones(menu);
			});
			if (dex && Planner.config.critterdexHelper) {
				ScreenEvents.afterExtract(screen).register((current, g, mx, my, delta) -> drawDex(g, menu));
			}
		});
	}

	// Chat

	private static void onChat(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		Progress p = Progress.get();

		if (lower.contains("safari ticket")) {
			Matcher n = Pattern.compile("(?i)(?:\\+|x)?([\\d,]+)x? (?:basic |rare |epic |legendary )?safari ticket").matcher(text);
			if (Planner.config.ticketReminder && (lower.contains("+") || lower.contains("received") || lower.contains("reward") || lower.contains("gift"))) {
				Toasts.show(Toasts.Kind.RARE, "paper", Planner.tr("safari.ticket_title"), Planner.tr("safari.ticket_text"));
			}
			if (n.find() && p.safariTickets >= 0 && !lower.contains("used")) p.safariTickets += (int) ContestMath.parseNumber(n.group(1));
			debug("chat: " + text);
		}

		if (!nearSafari()) return;
		if (Planner.config.safariDebug && LOGGED.size() < 400 && LOGGED.add("c" + text)) debug("chat: " + text);
		if (Planner.config.safariBells) Bells.chat(text);

		Matcher ess = ESSENCE.matcher(text);
		if (ess.find()) {
			long amount = (long) ContestMath.parseNumber(ess.group(1) != null ? ess.group(1) : ess.group(2));
			if (amount > 0 && amount < 100_000) {
				essence += amount;
				ESSENCE_SAMPLES.addLast(new long[] {System.currentTimeMillis(), amount});
			}
		}

		Matcher other = OTHER_CATCH.matcher(text);
		if (other.find() && !other.group(1).equalsIgnoreCase("you")) {
			String player = other.group(1);
			var self = Minecraft.getInstance().player;
			if (self != null && player.equalsIgnoreCase(self.getGameProfile().name())) {
				caught(other.group(2).trim());
			} else {
				PARTY.merge(player, 1, Integer::sum);
			}
			return;
		}

		Matcher mine = MY_CATCH.matcher(text);
		if (mine.find()) {
			caught(mine.group(1).trim());
			return;
		}
		if (FAIL.matcher(text).find() && (lower.contains("critter") || lower.contains("capsule"))) fails++;
	}

	private static void caught(String critter) {
		if (critter.isBlank()) return;
		catches++;
		caughtThisRun.add(critter);
		Progress p = Progress.get();
		if (p.critterCaught.add(critter)) {
			p.critterMissing.remove(critter);
			Toasts.show(Toasts.Kind.MILESTONE, "spyglass", Planner.tr("safari.new_title"), Planner.tr("safari.new_text", critter));
		}
		Progress.changed();
	}

	// Every half second

	public static void tick(Minecraft client) {
		MARKS.clear();
		if (client.player == null || client.level == null) return;
		long now = System.currentTimeMillis();
		boolean inside = inside();
		trackRun(client, inside, now);
		if (!inside) {
			if (nearSafari()) ticketHint(client);
			return;
		}
		countTickets(client);

		Vec3 me = client.player.position();
		Set<String> caught = Progress.get().critterCaught;
		for (Entity e : client.level.getEntitiesOfClass(Entity.class, client.player.getBoundingBox().inflate(48))) {
			if (e == client.player) continue;
			if (e instanceof ItemEntity item) {
				if (!Planner.config.safariGroundItems) continue;
				ItemStack stack = item.getItem();
				String name = Planner.strip(stack.getHoverName().getString()).trim();
				boolean rare = Planner.config.safariRareItems.stream().anyMatch(r -> name.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT)));
				MARKS.add(new Mark(item.position(), (stack.getCount() > 1 ? stack.getCount() + "x " : "") + name, "", rare ? 0xFFFFD34D : 0xFFE0E0E0, rare));
				if (Planner.config.safariDebug && LOGGED.size() < 400 && LOGGED.add("i" + name)) debug("item on ground: " + name);
				continue;
			}
			Component custom = e.getCustomName();
			if (custom == null) continue;
			String raw = Planner.strip(custom.getString()).trim();
			if (raw.isEmpty()) continue;
			if (Planner.config.safariDebug && LOGGED.size() < 400 && LOGGED.add("e" + raw)) debug("entity: " + e.getType().toShortString() + " '" + raw + "'");
			String lower = raw.toLowerCase(Locale.ROOT);

			if (Planner.config.honeyHelix && Planner.config.honeyNames.stream().anyMatch(h -> lower.contains(h.toLowerCase(Locale.ROOT)))) {
				String key = Math.round(e.getX() / 3) + "," + Math.round(e.getZ() / 3);
				HONEY.computeIfAbsent(key, k -> new long[] {now, now})[1] = now;
				HONEY_POS.put(key, e.position());
				continue;
			}

			String name = LEVEL_TAG.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
			boolean sparkling = lower.contains("sparkling");
			if (sparkling) name = name.replaceFirst("(?i)sparkling\\s*", "").trim();
			if (name.length() < 3 || name.length() > 30) continue;
			Double price = BazaarPrices.BUY.get(Hunting.shardId(name + " Shard"));
			boolean known = caught.contains(name) || Progress.get().critterMissing.contains(name);
			if (price == null && !known && !sparkling) continue;

			if (sparkling) sparkling(client, e, name);
			boolean isNew = !caught.isEmpty() && !caught.contains(name);
			if (!Planner.config.safariPrices && !sparkling && !(isNew && Planner.config.critterdexHelper)) continue;
			String detail = Planner.config.safariPrices && price != null ? GearScreenFormat.coins(price) : "";
			if (isNew && Planner.config.critterdexHelper) detail = (detail.isEmpty() ? "" : detail + " · ") + Planner.tr("safari.new_label");
			int color = sparkling ? 0xFFFFD34D : isNew ? 0xFFFF77DD : price != null && price >= 50_000 ? 0xFF8CD58B : 0xFF9FD8FF;
			MARKS.add(new Mark(e.position(), (sparkling ? "✦ " : "") + name, detail, color, sparkling));
		}

		// Honeyed Helix trees: shown while seen and for 20 minutes after, with the time since.
		HONEY.entrySet().removeIf(entry -> now - entry.getValue()[1] > 20 * 60_000L);
		HONEY_POS.keySet().retainAll(HONEY.keySet());
		if (Planner.config.honeyHelix) {
			for (var entry : HONEY.entrySet()) {
				Vec3 pos = HONEY_POS.get(entry.getKey());
				long gone = (now - entry.getValue()[1]) / 1000;
				String detail = gone < 3 ? Planner.tr("safari.honey_here", GearScreenFormat.duration((now - entry.getValue()[0]) / 1000))
						: Planner.tr("safari.honey_gone", GearScreenFormat.duration(gone));
				MARKS.add(new Mark(pos, "Honeyed Helix", detail, 0xFFFFB347, false));
			}
		}
		MARKS.removeIf(m -> m.pos().distanceTo(me) > 64);
	}

	private static void sparkling(Minecraft client, Entity e, String name) {
		if (!SPARKLING_SEEN.add(e.getId())) return;
		Progress.get().sparklingSeen++;
		Progress.changed();
		if (!Planner.config.sparklingAlert) return;
		Toasts.show(Toasts.Kind.RARE, "glowstone_dust", Planner.tr("safari.sparkling_title"), Planner.tr("safari.sparkling_text", name));
		client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.6f, 1.0f));
		client.gui.hud.setTitle(Component.literal("§e✦ Sparkling " + name + "!"));
	}

	private static void trackRun(Minecraft client, boolean inside, long now) {
		if (!Planner.config.safariTracker) return;
		if (inside) {
			leftAt = 0;
			int count = countCapsules(client);
			if (!inRun) {
				inRun = true;
				runStart = now;
				catches = fails = 0;
				essence = 0;
				caughtThisRun.clear();
				PARTY.clear();
				capsulesStart = count;
				capsulesWarned = Integer.MAX_VALUE;
			}
			if (capsulesStart < 0 || count > capsulesStart) capsulesStart = count;
			capsules = count;
			int warn = Planner.config.capsuleWarnAt;
			if (warn > 0 && count >= 0 && count <= warn && count < capsulesWarned && capsulesStart > warn) {
				capsulesWarned = count;
				Toasts.show(count == 0 ? Toasts.Kind.ALARM : Toasts.Kind.INFO, "ender_pearl", Planner.tr("safari.capsules_title"), Planner.tr("safari.capsules_text", count));
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 1.0f));
			}
		} else if (inRun) {
			if (leftAt == 0) leftAt = now;
			if (now - leftAt > 8_000) endRun(client, leftAt);
		}
	}

	private static void endRun(Minecraft client, long end) {
		inRun = false;
		long duration = end - runStart;
		if (duration < 30_000 && catches == 0) return;
		Progress.SafariRun run = new Progress.SafariRun();
		run.startMs = runStart;
		run.durationMs = duration;
		run.catches = catches;
		run.fails = fails;
		run.essence = essence;
		run.capsulesUsed = capsulesStart >= 0 && capsules >= 0 ? Math.max(0, capsulesStart - capsules) : 0;
		List<Progress.SafariRun> runs = Progress.get().safariRuns;
		runs.add(run);
		while (runs.size() > 30) runs.removeFirst();
		Progress.changed();
		if (client.player == null) return;
		client.player.sendSystemMessage(Component.literal("§a[FP] §f" + Planner.tr("safari.summary", GearScreenFormat.duration(duration / 1000), catches, fails, run.capsulesUsed, Planner.whole(essence))));
		if (!caughtThisRun.isEmpty()) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (String c : caughtThisRun) counts.merge(c, 1, Integer::sum);
			StringBuilder line = new StringBuilder("§7");
			counts.forEach((k, v) -> line.append(line.length() > 2 ? ", " : "").append(v > 1 ? v + "x " : "").append(k));
			client.player.sendSystemMessage(Component.literal(line.toString()));
		}
	}

	private static int countCapsules(Minecraft client) {
		var inv = client.player.getInventory();
		int count = 0;
		boolean any = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			String name = Planner.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
			if (!name.contains("capsule")) continue;
			any = true;
			Matcher m = COUNT.matcher(name);
			count += m.find() ? Integer.parseInt(m.group(1)) : stack.getCount();
		}
		return any ? count : capsules >= 0 ? 0 : -1;
	}

	private static void countTickets(Minecraft client) {
		// Tickets are taken at the door; nothing to count inside.
	}

	private static void ticketHint(Minecraft client) {
		if (!Planner.config.ticketReminder || ticketHinted || client.player == null) return;
		var inv = client.player.getInventory();
		int tickets = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && Planner.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT).contains("safari ticket")) tickets += stack.getCount();
		}
		Progress.get().safariTickets = tickets;
		ticketHinted = true;
		Toasts.show(tickets > 0 ? Toasts.Kind.INFO : Toasts.Kind.ALARM, "paper", Planner.tr("safari.tickets_title"),
				tickets > 0 ? Planner.tr("safari.tickets_have", tickets) : Planner.tr("safari.tickets_none"));
	}

	public static List<Mark> marks() {
		return MARKS;
	}

	// Menus

	private static void readDex(AbstractContainerScreen<?> menu, boolean dump) {
		Progress p = Progress.get();
		boolean changed = false;
		for (Slot slot : menu.getMenu().slots) {
			if (slot.container == Minecraft.getInstance().player.getInventory()) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = MenuReaders.name(stack);
			List<String> lore = MenuReaders.lore(stack);
			String all = String.join("\n", lore).toLowerCase(Locale.ROOT);
			if (dump && Planner.config.safariDebug) debug("dex slot " + slot.index + ": '" + name + "' " + lore);
			if (name.isBlank() || name.contains("?") || name.toLowerCase(Locale.ROOT).matches(".*(close|back|next|previous|page|biome|milestone|sparkling critterdex|go back).*")) continue;
			boolean missing = all.contains("not caught") || all.contains("not yet caught") || all.contains("undiscovered") || all.contains("not discovered") || all.contains("haven't caught");
			boolean got = !missing && (all.contains("caught") || all.contains("captured") || all.contains("discovered"));
			if (missing) changed |= p.critterMissing.add(name) | p.critterCaught.remove(name);
			else if (got) changed |= p.critterCaught.add(name) | p.critterMissing.remove(name);
		}
		if (changed) Progress.changed();
	}

	private static void readMilestones(AbstractContainerScreen<?> menu) {
		Progress p = Progress.get();
		boolean changed = false;
		for (Slot slot : menu.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = MenuReaders.name(stack);
			if (!name.toLowerCase(Locale.ROOT).contains("biome")) continue;
			for (String line : MenuReaders.lore(stack)) {
				Matcher f = FRACTION.matcher(line);
				if (!f.find()) continue;
				String value = f.group(1) + "/" + f.group(2);
				String biome = name.replaceAll("(?i)\\s*milestones?", "").trim();
				if (!value.equals(p.safariMilestones.put(biome, value))) changed = true;
				break;
			}
		}
		if (changed) Progress.changed();
	}

	private static void drawDex(GuiGraphicsExtractor g, AbstractContainerScreen<?> menu) {
		var font = Minecraft.getInstance().font;
		Progress p = Progress.get();
		int rows = Math.max(1, (menu.getMenu().slots.size() - 36) / 9);
		int left = (menu.width - 176) / 2, top = (menu.height - (114 + rows * 18)) / 2;
		for (Slot slot : menu.getMenu().slots) {
			if (slot.container == Minecraft.getInstance().player.getInventory()) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty() || !p.critterMissing.contains(MenuReaders.name(stack))) continue;
			int x = left + slot.x, y = top + slot.y;
			g.fill(x, y, x + 16, y + 1, 0xFFFF5577);
			g.fill(x, y + 15, x + 16, y + 16, 0xFFFF5577);
			g.fill(x, y, x + 1, y + 16, 0xFFFF5577);
			g.fill(x + 15, y, x + 16, y + 16, 0xFFFF5577);
		}
		List<String> missing = new ArrayList<>(p.critterMissing);
		int n = Math.min(14, missing.size());
		int w = 150, h = 32 + Math.max(1, n) * 11;
		int x = Math.max(4, left - w - 6), y = top;
		round(g, x, y, w, h, 7, alpha(UI_PANEL, 0.94));
		roundOutline(g, x, y, w, h, 7, alpha(UI_ACCENT, 0.5));
		g.text(font, Planner.tr("safari.dex_title", p.critterCaught.size(), p.critterCaught.size() + p.critterMissing.size()), x + 7, y + 6, UI_ACCENT, false);
		g.text(font, Planner.tr("safari.dex_missing", missing.size()), x + 7, y + 17, UI_DIM, false);
		for (int i = 0; i < n; i++) g.text(font, fit(font, missing.get(i), w - 14), x + 7, y + 29 + i * 11, 0xFFFF77DD, false);
	}

	// HUD

	public static List<String> lines() {
		List<String> out = new ArrayList<>();
		Progress p = Progress.get();
		long now = System.currentTimeMillis();
		if (inRun && Planner.config.safariTracker) {
			out.add("§7" + Planner.tr("safari.hud_run") + " §f" + GearScreenFormat.duration((now - runStart) / 1000));
			if (capsules >= 0) out.add("§7" + Planner.tr("safari.hud_capsules") + " " + (capsules <= Planner.config.capsuleWarnAt ? "§c" : "§f") + capsules);
			out.add("§7" + Planner.tr("safari.hud_catches") + " §a" + catches + (fails > 0 ? " §8· §c" + fails : ""));
			while (!ESSENCE_SAMPLES.isEmpty() && now - ESSENCE_SAMPLES.peekFirst()[0] > 3_600_000) ESSENCE_SAMPLES.removeFirst();
			if (essence > 0) {
				double span = Math.max(60_000, now - runStart);
				out.add("§7" + Planner.tr("safari.hud_essence") + " §d" + Planner.whole(essence) + " §8(" + Planner.whole(essence * 3_600_000.0 / span) + "/h)");
			}
		}
		if (inside() && Planner.config.safariBells) out.addAll(Bells.lines());
		if (nearSafari() && Planner.config.critterdexHelper && !(p.critterCaught.isEmpty() && p.critterMissing.isEmpty())) {
			out.add("§7Critterdex §f" + p.critterCaught.size() + "§7/" + (p.critterCaught.size() + p.critterMissing.size()));
		}
		if (nearSafari() && Planner.config.safariMilestones) p.safariMilestones.forEach((biome, value) -> out.add("§7" + biome + " §e" + value));
		if (nearSafari() && Planner.config.ticketReminder && p.safariTickets >= 0) out.add("§7" + Planner.tr("safari.hud_tickets") + " §f" + p.safariTickets);
		if (inRun && Planner.config.partyProgress) PARTY.forEach((name, count) -> out.add("§b" + name + " §7" + count));
		return out;
	}

	/** "/fp safari": the last runs. */
	public static void history(java.util.function.Consumer<Component> out) {
		List<Progress.SafariRun> runs = Progress.get().safariRuns;
		out.accept(Component.literal("§a[FP] §f" + Planner.tr("safari.history_title", runs.size())));
		if (runs.isEmpty()) out.accept(Component.literal("§7" + Planner.tr("safari.history_none")));
		var fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(java.time.ZoneId.systemDefault());
		for (int i = Math.max(0, runs.size() - 10); i < runs.size(); i++) {
			Progress.SafariRun r = runs.get(i);
			out.accept(Component.literal("§7" + fmt.format(java.time.Instant.ofEpochMilli(r.startMs)) + " §f" + GearScreenFormat.duration(r.durationMs / 1000)
					+ " §a" + r.catches + "§7/§c" + r.fails + " §7· " + r.capsulesUsed + " Caps · §d" + Planner.whole(r.essence) + " Ess"));
		}
		out.accept(Component.literal("§7Sparkling: §e" + Progress.get().sparklingSeen));
	}

	// World

	public static void draw(GuiGraphicsExtractor g) {
		var client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null || !inside()) return;
		Vec3 player = client.player.position();
		long now = System.currentTimeMillis();
		var font = client.font;
		for (Mark mark : MARKS) {
			float[] at = WorldOverlay.project(client, mark.pos().add(0, 1.0, 0));
			if (at == null) continue;
			int x = Math.round(at[0]), y = Math.round(at[1]);
			double pulse = mark.alert() && Planner.config.animations ? 0.6 + 0.4 * Math.sin(now / 120.0) : 1;
			round(g, x - 3, y - 3, 6, 6, 3, alpha(mark.color(), pulse));
			String text = mark.name() + (mark.detail().isEmpty() ? "" : " · " + mark.detail()) + " · " + Math.round(mark.pos().distanceTo(player)) + "m";
			int tw = font.width(text);
			round(g, x - tw / 2 - 3, y - 16, tw + 6, 11, 4, alpha(UI_PANEL, 0.75));
			g.text(font, text, x - tw / 2, y - 14, alpha(mark.color(), 1), false);
		}
		Bells.draw(client, g, player, now);
	}

	static void debug(String line) {
		if (Planner.config.safariDebug) Planner.LOGGER.info("[FP-Safari] {}", line);
	}

}
