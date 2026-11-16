package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * Moonglade Beacon: a message when it is ready again (from the tab list widget), and help with the
 * "Tune Frequency" game: what the target colour, speed and pitch are and what to change.
 */
public final class Beacon {
	private static final Pattern READY = Pattern.compile("Cooldown: AVAILABLE");
	private static final Pattern COLOR = Pattern.compile("Current color: ([\\w ]+)");
	private static final Pattern SPEED = Pattern.compile("Current speed: (\\d+)");
	private static final Pattern PITCH = Pattern.compile("Current pitch: (\\w+)");
	private static final int[] TICKS = {52, 42, 32, 22, 12};
	private static final String[] PITCHES = {"Low", "Normal", "High"};
	private static final float[] PITCH_VALUES = {0.0952381f, 0.7936508f, 1.4920635f};

	private static long lastAlert;
	private static boolean open;
	private static String targetColor = "", currentColor = "", currentPitch = "";
	private static int currentSpeed = -1, targetSpeed = -1;
	private static int lastIndex = -1;
	private static long lastMoveTick;
	private static final List<Long> intervals = new ArrayList<>();
	private static long ticks;
	private static boolean dumped;
	/** Slots of the three setting buttons, found by their lore. */
	private static int colorSlot = -1, speedSlot = -1, pitchSlot = -1;
	private static AbstractContainerScreen<?> screen;

	private Beacon() {}

	public static boolean menuOpen() {
		return open;
	}

	/** Called once a second. */
	public static void tick() {
		if (!Planner.config.beaconAlert || !SkyBlockReader.inForagingArea()) return;
		long now = System.currentTimeMillis();
		if (now - lastAlert < 9 * 60_000L) return;
		for (String line : SkyBlockReader.tab()) {
			if (READY.matcher(Planner.strip(line)).find()) {
				lastAlert = now;
				Toasts.show(Toasts.Kind.INFO, "beacon", Planner.tr("beacon.ready"), Planner.tr("beacon.ready_text"));
				return;
			}
		}
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> menu)) return;
			String title = Planner.strip(screen.getTitle().getString());
			if (!title.contains("Tune Frequency") || !Planner.config.beaconSolver) return;
			open = true;
			intervals.clear();
			lastIndex = -1;
			targetColor = currentColor = currentPitch = "";
			currentSpeed = targetSpeed = -1;
			colorSlot = speedSlot = pitchSlot = -1;
			dumped = false;
			ticks = 0;
			Beacon.screen = menu;
			ScreenEvents.afterTick(screen).register(current -> read(menu));
			ScreenEvents.afterExtract(screen).register((current, g, mx, my, delta) -> draw(g));
			ScreenEvents.remove(screen).register(current -> open = false);
		});
	}

	private static String colorOf(net.minecraft.world.item.ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (!path.endsWith("_stained_glass_pane")) return null;
		return StatsScreen.prettyName(path.replace("_stained_glass_pane", "").toUpperCase(Locale.ROOT));
	}

	private static void read(AbstractContainerScreen<?> menu) {
		ticks++;
		dump(menu);
		var slots = menu.getMenu().slots;
		// The reference pane moves along slots 10 to 16; its colour is the target, its rhythm the speed.
		for (int i = 10; i <= 16 && i < slots.size(); i++) {
			String c = colorOf(slots.get(i).getItem());
			if (c == null || c.equalsIgnoreCase("Black") || c.equalsIgnoreCase("Gray")) continue;
			targetColor = c;
			if (lastIndex >= 0 && i != lastIndex) {
				intervals.add(ticks - lastMoveTick);
				while (intervals.size() > 6) intervals.removeFirst();
			}
			if (i != lastIndex) lastMoveTick = ticks;
			lastIndex = i;
			break;
		}
		if (!intervals.isEmpty()) {
			List<Long> sorted = new ArrayList<>(intervals);
			sorted.sort(null);
			long median = sorted.get(sorted.size() / 2);
			int best = 0;
			for (int s = 1; s < TICKS.length; s++) if (Math.abs(TICKS[s] - median) < Math.abs(TICKS[best] - median)) best = s;
			targetSpeed = best + 1;
		}

		for (Slot slot : slots) {
			for (String line : MenuReaders.lore(slot.getItem())) {
				Matcher m;
				if ((m = COLOR.matcher(line)).find()) { currentColor = m.group(1).trim(); colorSlot = slot.index; }
				if ((m = SPEED.matcher(line)).find()) { currentSpeed = Integer.parseInt(m.group(1)); speedSlot = slot.index; }
				if ((m = PITCH.matcher(line)).find()) { currentPitch = m.group(1).trim(); pitchSlot = slot.index; }
			}
		}
	}

	/** Writes the menu once to the log, so the solver can be fixed when Hypixel changes it. */
	private static void dump(AbstractContainerScreen<?> menu) {
		if (dumped || ticks < 10) return;
		dumped = true;
		StringBuilder out = new StringBuilder("[FP] Beacon menu:");
		for (Slot slot : menu.getMenu().slots) {
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory || slot.getItem().isEmpty()) continue;
			out.append("\n  ").append(slot.index).append(' ').append(BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath())
					.append(" \"").append(Planner.strip(slot.getItem().getHoverName().getString())).append("\" ")
					.append(String.join(" | ", MenuReaders.lore(slot.getItem())));
		}
		Planner.LOGGER.info(out.toString());
	}

	private static String targetPitch() {
		float heard = Sounds.lastNotePitch();
		if (heard < 0) return "";
		int best = 0;
		for (int i = 1; i < PITCH_VALUES.length; i++) if (Math.abs(PITCH_VALUES[i] - heard) < Math.abs(PITCH_VALUES[best] - heard)) best = i;
		return PITCHES[best];
	}

	private static void draw(GuiGraphicsExtractor g) {
		var font = Minecraft.getInstance().font;
		String pitch = targetPitch();
		List<String> lines = new ArrayList<>();
		lines.add(row("beacon.color", targetColor, currentColor));
		lines.add(row("beacon.speed", targetSpeed < 0 ? "" : String.valueOf(targetSpeed), currentSpeed < 0 ? "" : String.valueOf(currentSpeed)));
		lines.add(row("beacon.pitch", pitch, currentPitch));
		boolean done = !targetColor.isEmpty() && targetColor.equalsIgnoreCase(currentColor) && targetSpeed == currentSpeed
				&& (pitch.isEmpty() || pitch.equalsIgnoreCase(currentPitch));

		int w = 190, h = 22 + lines.size() * 11 + 12;
		int x = 6, y = 6;
		round(g, x, y, w, h, 7, alpha(UI_PANEL, 0.94));
		roundOutline(g, x, y, w, h, 7, alpha(done ? UI_ACCENT : UI_AMBER, 0.6));
		g.text(font, Planner.tr("beacon.title"), x + 7, y + 6, UI_AMBER, false);
		for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 7, y + 20 + i * 11, UI_TEXT, false);
		g.text(font, done ? "§a" + Planner.tr("beacon.done") : "§7" + (pitch.isEmpty() ? Planner.tr("beacon.listen") : Planner.tr("beacon.hint")), x + 7, y + h - 12, UI_MUTED, false);

		// Frames on the buttons: green when that setting is right, pulsing orange when it still needs clicks.
		frame(g, colorSlot, !targetColor.isEmpty() && targetColor.equalsIgnoreCase(currentColor), targetColor.isEmpty());
		frame(g, speedSlot, targetSpeed > 0 && targetSpeed == currentSpeed, targetSpeed < 0);
		frame(g, pitchSlot, !pitch.isEmpty() && pitch.equalsIgnoreCase(currentPitch), pitch.isEmpty());
	}

	private static void frame(GuiGraphicsExtractor g, int index, boolean ok, boolean unknown) {
		if (screen == null || index < 0 || index >= screen.getMenu().slots.size() || unknown) return;
		Slot slot = screen.getMenu().slots.get(index);
		int rows = Math.max(1, (screen.getMenu().slots.size() - 36) / 9);
		int left = (screen.width - 176) / 2;
		int top = (screen.height - (114 + rows * 18)) / 2;
		int sx = left + slot.x - 1, sy = top + slot.y - 1;
		double pulse = ok ? 1 : 0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 150.0);
		int c = alpha(ok ? 0xFF55FF55 : 0xFFFFAA00, pulse);
		g.fill(sx, sy, sx + 18, sy + 1, c);
		g.fill(sx, sy + 17, sx + 18, sy + 18, c);
		g.fill(sx, sy, sx + 1, sy + 18, c);
		g.fill(sx + 17, sy, sx + 18, sy + 18, c);
	}

	private static String row(String key, String target, String current) {
		String t = target.isEmpty() ? "§8?" : target;
		String c = current.isEmpty() ? "§8?" : current;
		boolean ok = !target.isEmpty() && target.equalsIgnoreCase(current);
		return "§7" + Planner.tr(key) + ": " + (ok ? "§a" : "§e") + c + (ok ? " ✔" : " §8→ §f" + t);
	}
}
