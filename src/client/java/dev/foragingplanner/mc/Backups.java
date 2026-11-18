package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies of the profile's statistics and settings (all its .json files), made at start and every
 * 30 minutes. The newest ten are kept; one can be restored from the settings or with /fp backup.
 */
public final class Backups {
	private static final int KEEP = 10;
	private static final long EVERY_MS = 30 * 60_000L;
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static long lastMs;

	private Backups() {}

	private static Path root() {
		return ModConfig.profileDirectory().resolve("backups");
	}

	private static long firstSeenMs;

	/** Called once a second. The first backup is made 20 seconds into SkyBlock, when the profile is known. */
	public static void tick() {
		if (!Planner.config.autoBackup || !SkyBlockReader.inSkyBlock()) return;
		long now = System.currentTimeMillis();
		if (firstSeenMs == 0) firstSeenMs = now;
		if (lastMs == 0) {
			if (now - firstSeenMs > 20_000) make("start");
			return;
		}
		if (now - lastMs >= EVERY_MS) make("auto");
	}

	/** Saves everything, then copies it into a new backup folder. Returns its name, or null. */
	public static String make(String reason) {
		lastMs = System.currentTimeMillis();
		try {
			JournalStore.save();
			Planner.config.save();
			Progress.save(true);
		} catch (RuntimeException e) {
			Planner.LOGGER.warn("Could not save before backup", e);
		}
		Path dir = ModConfig.profileDirectory();
		String name = LocalDateTime.now().format(NAME) + "_" + reason;
		Path target = root().resolve(name);
		try {
			Files.createDirectories(target);
			int copied = 0;
			try (Stream<Path> files = Files.list(dir)) {
				for (Path file : files.toList()) {
					String n = file.getFileName().toString();
					if (!Files.isRegularFile(file) || !n.endsWith(".json")) continue;
					Files.copy(file, target.resolve(n), StandardCopyOption.REPLACE_EXISTING);
					copied++;
				}
			}
			if (copied == 0) {
				Files.deleteIfExists(target);
				return null;
			}
			prune();
			return name;
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not make a backup", e);
			return null;
		}
	}

	/** Backup names, newest first. */
	public static List<String> list() {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(root())) return out;
		try (Stream<Path> dirs = Files.list(root())) {
			dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).forEach(out::add);
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not list backups", e);
		}
		return out;
	}

	private static void prune() throws java.io.IOException {
		List<String> all = list();
		for (int i = KEEP; i < all.size(); i++) {
			Path dir = root().resolve(all.get(i));
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.toList()) Files.deleteIfExists(f);
			}
			Files.deleteIfExists(dir);
		}
	}

	/** Puts a backup back and reloads statistics and settings. The current state is saved first. */
	public static boolean restore(String name) {
		Path source = root().resolve(name);
		if (name.contains("..") || !Files.isDirectory(source)) return false;
		make("before-restore");
		try (Stream<Path> files = Files.list(source)) {
			for (Path file : files.toList()) {
				Files.copy(file, ModConfig.profileDirectory().resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not restore backup {}", name, e);
			return false;
		}
		Planner.config = ModConfig.load();
		Progress.load();
		JournalStore.load();
		PlannerHud.rebuild();
		return true;
	}

	/** "2026-11-18 14:05 · auto" for a backup name. */
	public static String pretty(String name) {
		String[] parts = name.split("_", 3);
		if (parts.length < 3) return name;
		return parts[0] + " " + parts[1].substring(0, Math.min(5, parts[1].length())).replace('-', ':') + " · " + Planner.tr("backup.reason_" + parts[2]);
	}
}
