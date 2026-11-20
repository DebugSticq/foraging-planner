package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * A bug report in one click: versions, the detection check, other mods, settings (without personal
 * fields) and the mod's part of the log. Player name, UUID, IP addresses and user folders are
 * replaced before anything is copied.
 */
public final class BugReport {
	private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
	private static final Pattern WIN_USER = Pattern.compile("(?i)([A-Z]:[\\\\/]+Users[\\\\/]+)[^\\\\/\\s]+");
	private static final Pattern NIX_USER = Pattern.compile("(/(?:home|Users)/)[^/\\s]+");
	private static final Pattern TOKEN = Pattern.compile("(?i)(token|session|accessToken|key)([\"'=: ]+)[A-Za-z0-9._-]{12,}");
	private static boolean noticed;

	private BugReport() {}

	/** Builds the report. */
	public static String build() {
		StringBuilder b = new StringBuilder();
		Minecraft mc = Minecraft.getInstance();
		b.append("## Foraging Planner bug report\n");
		b.append("Mod: ").append(ModVersion.get()).append('\n');
		b.append("Minecraft: ").append(net.minecraft.SharedConstants.getCurrentVersion().id()).append('\n');
		b.append("Java: ").append(System.getProperty("java.version")).append(" · OS: ").append(System.getProperty("os.name")).append('\n');
		b.append("Language: ").append(mc.options == null ? "?" : mc.options.languageCode).append(" · GUI scale: ").append(mc.getWindow().getGuiScale()).append('\n');
		b.append("Area: ").append(SkyBlockReader.area()).append(" · in SkyBlock: ").append(SkyBlockReader.inSkyBlock())
				.append(" · light mode: ").append(Perf.light()).append('\n');

		b.append("\n### Check\n");
		for (String line : CheckScreen.reportLines()) b.append("- ").append(line).append('\n');

		b.append("\n### Mods\n");
		try {
			TreeSet<String> mods = new TreeSet<>();
			for (var mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				if (id.startsWith("fabric-") || id.equals("java") || id.equals("minecraft") || id.equals("fabricloader") || id.equals("mixinextras")) continue;
				mods.add(id + " " + mod.getMetadata().getVersion().getFriendlyString());
			}
			b.append(String.join(", ", mods)).append('\n');
		} catch (RuntimeException e) {
			b.append("(could not list)\n");
		}

		b.append("\n### Settings code\n").append(SettingsShare.export()).append('\n');

		b.append("\n### Log (Foraging Planner lines and errors)\n```\n");
		for (String line : logLines(80)) b.append(line).append('\n');
		b.append("```\n");
		return clean(b.toString());
	}

	/** The mod's lines, warnings and stack traces from latest.log, the newest {@code max}. */
	static List<String> logLines(int max) {
		List<String> out = new ArrayList<>();
		try {
			Path log = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
			if (!Files.exists(log)) return out;
			List<String> all = Files.readAllLines(log);
			boolean inTrace = false;
			for (String line : all.subList(Math.max(0, all.size() - 20_000), all.size())) {
				String lower = line.toLowerCase(Locale.ROOT);
				boolean ours = lower.contains("foragingplanner") || lower.contains("foraging planner") || line.contains("[FP");
				boolean trace = line.startsWith("\tat ") || line.startsWith("Caused by") || line.startsWith("\t... ");
				if (ours || (inTrace && trace)) {
					out.add(line.length() > 400 ? line.substring(0, 400) + "…" : line);
					inTrace = ours && (lower.contains("exception") || lower.contains("error") || lower.contains("warn")) || (inTrace && trace);
				} else {
					inTrace = false;
				}
			}
		} catch (Exception e) {
			out.add("(log not readable: " + e.getClass().getSimpleName() + ")");
		}
		return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
	}

	/** Removes what could identify the player. */
	static String clean(String text) {
		Minecraft mc = Minecraft.getInstance();
		try {
			String name = mc.getUser().getName();
			if (name != null && name.length() >= 3) text = text.replace(name, "<player>");
			String uuid = mc.getUser().getProfileId().toString();
			text = text.replace(uuid, "<uuid>").replace(uuid.replace("-", ""), "<uuid>");
		} catch (RuntimeException ignored) {
			// No user yet.
		}
		text = IP.matcher(text).replaceAll("<ip>");
		text = WIN_USER.matcher(text).replaceAll("$1<user>");
		text = NIX_USER.matcher(text).replaceAll("$1<user>");
		text = TOKEN.matcher(text).replaceAll("$1$2<hidden>");
		return text;
	}

	/** Copies the report and saves it next to the config. Returns the file name, or "" if saving failed. */
	public static String copy() {
		String report = build();
		Minecraft.getInstance().keyboardHandler.setClipboard(report);
		try {
			Path file = ModConfig.directory().resolve("bug-report-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".md");
			Files.writeString(file, report);
			return file.getFileName().toString();
		} catch (Exception e) {
			return "";
		}
	}

	/** The first time something fails at runtime, suggest a report once. */
	public static void noticed(Throwable error) {
		if (noticed) return;
		noticed = true;
		Planner.LOGGER.warn("[FP] Problem noticed, a bug report can be made with /fp report", error);
		Toasts.show(Toasts.Kind.ALARM, "writable_book", Planner.tr("report.noticed_title"), Planner.tr("report.noticed"));
	}
}
