package dev.foragingplanner.mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * Smaller Galatea helpers: falling mob alert, critter and Stridersurfer finder, gather rates from
 * sacks, Miria's Prize counter, HOTF whisper reminder and a full inventory warning.
 */
public final class Extras {
	private static final Pattern FELL = Pattern.compile("^A ([A-Za-z' ]+) fell from the Tree!$");
	private static final Pattern MIRIA = Pattern.compile("Miria's Prize");

	/** A mob or critter shown on screen. */
	public record Mark(Vec3 pos, String name, int color, boolean alert) {}

	private static final List<Mark> MARKS = new ArrayList<>();
	private static String fallen = "";
	private static long fallenAt;
	private static int striders = -1;
	private static int stridersMax;
	private static long stridersWarned;
	private static long inventoryWarned;
	private static String whisperNotified = "";
	private static long lastWhisperCheck;

	/** Sack items per hour: name → [time ms, count] samples of the last hour. */
	private static final Map<String, Deque<long[]>> GATHER = new LinkedHashMap<>();

	private Extras() {}

	public static void register() {
		ServerMessages.register((component, overlay) -> {
			if (overlay || !SkyBlockReader.inSkyBlock()) return;
			String text = Planner.strip(component.getString()).trim();
			Matcher fell = FELL.matcher(text);
			if (fell.matches() && SkyBlockReader.inForagingArea()) {
				fallen = fell.group(1).trim();
				fallenAt = System.currentTimeMillis();
				if (Planner.config.fallAlert) {
					Toasts.show(Toasts.Kind.RARE, "phantom_membrane", Planner.tr("fall.title", fallen), Planner.tr("fall.text"));
					var client = Minecraft.getInstance();
					client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.4f, 1.0f));
					client.gui.hud.setTitle(Component.literal("§c" + fallen + "!"));
				}
			}
			if (MIRIA.matcher(text).find() && !text.startsWith("[FP]") && (text.contains("RARE") || text.contains("received") || text.contains("+"))) {
				Progress.get().miriaPrizes++;
				Progress.changed();
				Toasts.show(Toasts.Kind.RARE, "nether_star", Planner.tr("miria.title"), Planner.tr("miria.text", Progress.get().miriaPrizes));
			}
		});
	}

	/** Called for every item in a sack message on the foraging islands. */
	public static void gathered(String name, long count) {
		if (count <= 0) return;
		long now = System.currentTimeMillis();
		Deque<long[]> samples = GATHER.computeIfAbsent(name, k -> new ArrayDeque<>());
		samples.addLast(new long[] {now, count});
		while (!samples.isEmpty() && now - samples.peekFirst()[0] > 3_600_000) samples.removeFirst();
	}

	/** HUD lines with the gather rate of everything except logs, best first. */
	public static List<String> gatherLines() {
		List<String> out = new ArrayList<>();
		if (!Planner.config.gatherRates) return out;
		long now = System.currentTimeMillis();
		List<Map.Entry<String, Double>> rates = new ArrayList<>();
		for (var e : GATHER.entrySet()) {
			Deque<long[]> s = e.getValue();
			while (!s.isEmpty() && now - s.peekFirst()[0] > 3_600_000) s.removeFirst();
			if (s.isEmpty() || now - s.peekLast()[0] > 600_000) continue;
			long total = 0;
			for (long[] x : s) total += x[1];
			double span = Math.max(60_000, now - s.peekFirst()[0]);
			rates.add(Map.entry(e.getKey(), total * 3_600_000.0 / span));
		}
		rates.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		for (int i = 0; i < Math.min(4, rates.size()); i++) {
			out.add("§7" + rates.get(i).getKey() + " §e" + Planner.whole(rates.get(i).getValue()) + "/h");
		}
		if (striders >= 0 && Planner.config.striderCounter) out.add("§7Stridersurfer §b" + striders);
		return out;
	}

	// Every half second: entities around the player

	public static void tick(Minecraft client) {
		MARKS.clear();
		if (client.player == null || client.level == null || !SkyBlockReader.inForagingArea()) {
			striders = -1;
			return;
		}
		long now = System.currentTimeMillis();
		List<String> critters = Planner.config.critterNames;
		boolean findCritters = Planner.config.critterHighlight && !critters.isEmpty();
		boolean findFallen = Planner.config.fallAlert && now - fallenAt < 30_000 && !fallen.isEmpty();
		int strider = 0;
		Map<String, Boolean> seen = new HashMap<>();
		for (Entity entity : client.level.getEntitiesOfClass(Entity.class, client.player.getBoundingBox().inflate(48))) {
			if (entity == client.player) continue;
			Component custom = entity.getCustomName();
			String name = custom == null ? "" : Planner.strip(custom.getString());
			if (name.isEmpty()) continue;
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.contains("stridersurfer")) strider++;
			if (findFallen && lower.contains(fallen.toLowerCase(Locale.ROOT))) {
				MARKS.add(new Mark(entity.position(), fallen, 0xFFFF5555, true));
				continue;
			}
			if (findCritters) {
				for (String critter : critters) {
					if (lower.contains(critter.toLowerCase(Locale.ROOT))) {
						String key = critter + Math.round(entity.getX()) + "," + Math.round(entity.getZ());
						if (seen.putIfAbsent(key, true) == null) MARKS.add(new Mark(entity.position(), critter, 0xFF55FFFF, false));
						break;
					}
				}
			}
		}

		// Stridersurfers: warn when they run low after there were more.
		striders = strider;
		stridersMax = Math.max(stridersMax, strider);
		if (Planner.config.striderCounter && stridersMax >= 2 && strider < Planner.config.striderMin && now - stridersWarned > 120_000 && strider >= 0) {
			stridersWarned = now;
			stridersMax = strider;
			Toasts.show(Toasts.Kind.INFO, "string", Planner.tr("strider.title"), Planner.tr("strider.text", strider));
		}

		checkInventory(client, now);
		if (now - lastWhisperCheck > 30_000) {
			lastWhisperCheck = now;
			checkWhispers();
		}
	}

	public static List<Mark> marks() {
		return MARKS;
	}

	private static void checkInventory(Minecraft client, long now) {
		if (!Planner.config.inventoryWarning || now - inventoryWarned < 120_000) return;
		Inventory inventory = client.player.getInventory();
		int free = 0;
		for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) free++;
		if (free <= 1 && SessionRates.foraging()) {
			inventoryWarned = now;
			Toasts.show(Toasts.Kind.ALARM, "chest", Planner.tr("inventory.title"), Planner.tr("inventory.text"));
		}
	}

	/** Tells the player once when the planned next HOTF perk is affordable. */
	private static void checkWhispers() {
		if (!Planner.config.whisperReminder || Planner.config.whispers <= 0) return;
		try {
			var plan = Planner.plan();
			if (plan.steps().isEmpty()) return;
			var step = plan.steps().getFirst();
			String key = step.perk().name + ":" + step.toLevel();
			if (key.equals(whisperNotified)) return;
			whisperNotified = key;
			Toasts.show(Toasts.Kind.GOAL, "oak_sapling", Planner.tr("whisper.title"),
					Planner.tr("whisper.text", step.perk().name, step.toLevel(), Planner.whole(step.cost())));
		} catch (RuntimeException ignored) {
			// The planner needs stats; without them there is nothing to suggest.
		}
	}
}
