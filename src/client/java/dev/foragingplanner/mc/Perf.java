package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;

/**
 * Measures how long the mod's work takes per tick and per frame, and switches to a lighter mode
 * on its own when the mod costs too much time (slow PCs, crowded lobbies).
 */
public final class Perf {
	/** name → [total ns, calls, max ns] since the last report window. */
	private static final Map<String, long[]> WINDOW = new LinkedHashMap<>();
	/** The last finished window, for /fp perf. */
	private static Map<String, long[]> last = Map.of();
	private static long windowStart = System.nanoTime();
	private static long lastWindowNs = 1;
	private static boolean autoLight;
	private static long heavyWindows, calmWindows;

	private static final long WINDOW_NS = 5_000_000_000L;
	/** Mod time per second above which the light mode starts by itself (about 3 ms per tick). */
	private static final long HEAVY_NS_PER_SECOND = 60_000_000L;
	private static final long CALM_NS_PER_SECOND = 20_000_000L;

	private Perf() {}

	public static void run(String name, Runnable work) {
		long start = System.nanoTime();
		try {
			work.run();
		} finally {
			add(name, System.nanoTime() - start);
		}
	}

	public static synchronized void add(String name, long ns) {
		long[] s = WINDOW.computeIfAbsent(name, k -> new long[3]);
		s[0] += ns;
		s[1]++;
		if (ns > s[2]) s[2] = ns;
		long now = System.nanoTime();
		if (now - windowStart >= WINDOW_NS) roll(now);
	}

	private static void roll(long now) {
		lastWindowNs = now - windowStart;
		long total = 0;
		for (long[] s : WINDOW.values()) total += s[0];
		long perSecond = total * 1_000_000_000L / Math.max(1, lastWindowNs);
		Map<String, long[]> copy = new LinkedHashMap<>();
		WINDOW.forEach((k, v) -> copy.put(k, v.clone()));
		last = copy;
		WINDOW.clear();
		windowStart = now;

		// Light mode by itself: on after two heavy windows, off after three calm ones.
		if (perSecond > HEAVY_NS_PER_SECOND) {
			heavyWindows++;
			calmWindows = 0;
		} else if (perSecond < CALM_NS_PER_SECOND) {
			calmWindows++;
			heavyWindows = 0;
		}
		if (!autoLight && heavyWindows >= 2 && Planner.config.perfAuto) {
			autoLight = true;
			Toasts.show(Toasts.Kind.INFO, "clock", Planner.tr("perf.light_title"), Planner.tr("perf.light_text"));
		} else if (autoLight && calmWindows >= 3) {
			autoLight = false;
		}
	}

	/** True when the mod should save time: fewer scans, a smaller path budget, longer caches. */
	public static boolean light() {
		return Planner.config.perfLight || autoLight;
	}

	/** Ticks between entity scans (10 normally, 20 in light mode). */
	public static int scanTicks() {
		return light() ? 20 : 10;
	}

	public static synchronized void reset() {
		WINDOW.clear();
		last = Map.of();
		windowStart = System.nanoTime();
	}

	/** "/fp perf": the most expensive parts of the last five seconds. */
	public static synchronized void report(Consumer<Component> out) {
		Map<String, long[]> data = last.isEmpty() ? WINDOW : last;
		long span = last.isEmpty() ? Math.max(1, System.nanoTime() - windowStart) : lastWindowNs;
		List<Map.Entry<String, long[]>> list = new ArrayList<>(data.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		long total = 0;
		for (var e : list) total += e.getValue()[0];
		double msPerSecond = total / 1e6 / (span / 1e9);
		String mode = light() ? (Planner.config.perfLight ? Planner.tr("perf.mode_light") : Planner.tr("perf.mode_auto")) : Planner.tr("perf.mode_normal");
		out.accept(Component.literal("§a[FP] §f" + Planner.tr("perf.title", String.format(java.util.Locale.ROOT, "%.2f", msPerSecond / 20), mode)));
		int shown = 0;
		for (var e : list) {
			if (shown++ >= 12) break;
			long[] s = e.getValue();
			double perSec = s[0] / 1e6 / (span / 1e9);
			String color = perSec > 10 ? "§c" : perSec > 3 ? "§e" : "§7";
			out.accept(Component.literal(color + String.format(java.util.Locale.ROOT, " %-14s %6.2f ms/s · Ø %5.0f µs · max %5.1f ms",
					e.getKey(), perSec, s[0] / 1e3 / Math.max(1, s[1]), s[2] / 1e6)));
		}
		out.accept(Component.literal("§8" + Planner.tr("perf.help")));
	}
}
