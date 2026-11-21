package dev.foragingplanner.mc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Asks Modrinth once per game start whether a newer version for this Minecraft version exists.
 *
 * <p>Installing is only done when the player asks for it (a click in chat, {@code /fp update}, or the
 * "install updates automatically" setting). The new jar is downloaded next to the old one as
 * {@code <name>.jar.fpupdate}, which Fabric does not load, and checked against Modrinth's SHA-512.
 * When the game closes, a small helper waits for Minecraft to exit, deletes the old jar and renames
 * the new one to {@code .jar}. If anything fails, the old version simply stays.
 */
public final class UpdateChecker {
	/** The project's slug on Modrinth. */
	public static final String PROJECT = "foraging-planner";
	private static final String PAGE = "https://modrinth.com/mod/" + PROJECT;
	private static final String SUFFIX = ".fpupdate";

	private static boolean checked;
	private static volatile String newer;
	private static volatile String changelog;
	private static volatile String fileUrl;
	private static volatile String fileName;
	private static volatile String fileSha512;
	private static boolean told;

	/** Downloaded and checked, waiting for the game to close. */
	private static volatile Path ready;
	private static volatile boolean downloading;
	private static boolean hookAdded;
	private static boolean cleaned;

	private UpdateChecker() {}

	/** Called once a second; checks after joining SkyBlock and tells the player once. */
	public static void tick(Minecraft client) {
		if (!cleaned) {
			cleaned = true;
			cleanUp();
		}
		if (!Planner.config.updateCheck || client.player == null) return;
		if (!checked && SkyBlockReader.inSkyBlock()) {
			checked = true;
			check();
		}
		if (newer != null && !told) {
			told = true;
			if (ready != null) {
				say("update.ready", newer);
			} else if (Planner.config.autoUpdate && canInstall()) {
				install();
			} else {
				announce(client);
			}
		}
	}

	private static void announce(Minecraft client) {
		String current = ModVersion.plain();
		Component line = Component.literal("§a[FP] §f" + Planner.tr("update.available", newer, current) + " ");
		if (canInstall() && fileUrl != null) {
			line = line.copy().append(Component.literal("§a§n" + Planner.tr("update.install")).withStyle(style -> style
					.withClickEvent(new ClickEvent.RunCommand("/fp update"))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(Planner.tr("update.install_hint"))))))
					.append(Component.literal(" §8· "));
		}
		line = line.copy().append(Component.literal("§b§n" + Planner.tr("update.open")).withStyle(style -> style
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(PAGE + "/versions")))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(changelog == null || changelog.isBlank() ? PAGE : changelog)))));
		client.player.sendSystemMessage(line);
	}

	private static void say(String key, Object... args) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.player != null) client.player.sendSystemMessage(Component.literal("§a[FP] §f" + Planner.tr(key, args)));
		});
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", "debugsticq/foraging-planner/" + ModVersion.plain());
	}

	private static void check() {
		String mc = net.minecraft.SharedConstants.getCurrentVersion().id();
		String query = "?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
				+ (mc.isEmpty() ? "" : "&game_versions=" + URLEncoder.encode("[\"" + mc + "\"]", StandardCharsets.UTF_8));
		HttpRequest req = request("https://api.modrinth.com/v2/project/" + PROJECT + "/version" + query).GET().build();
		HttpClient.newHttpClient().sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() != 200) return;
			JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
			String current = ModVersion.plain();
			for (var element : versions) {
				JsonObject v = element.getAsJsonObject();
				if (!"release".equals(v.has("version_type") ? v.get("version_type").getAsString() : "release")) continue;
				String number = v.get("version_number").getAsString();
				int plus = number.indexOf('+');
				String plain = plus > 0 ? number.substring(0, plus) : number;
				if (compare(plain, current) > 0) {
					String text = v.has("changelog") && !v.get("changelog").isJsonNull() ? v.get("changelog").getAsString() : "";
					changelog = text.length() > 400 ? text.substring(0, 400) + "…" : text;
					readFile(v);
					newer = plain;
				}
				break; // Modrinth lists the newest first.
			}
		}).exceptionally(error -> {
			Planner.LOGGER.debug("Update check failed", error);
			return null;
		});
	}

	/** The primary jar of a Modrinth version (or the first one), with its SHA-512. */
	private static void readFile(JsonObject version) {
		if (!version.has("files")) return;
		JsonObject chosen = null;
		for (var f : version.getAsJsonArray("files")) {
			JsonObject file = f.getAsJsonObject();
			String name = file.get("filename").getAsString();
			if (!name.endsWith(".jar") || name.endsWith("-sources.jar")) continue;
			if (chosen == null || file.has("primary") && file.get("primary").getAsBoolean()) chosen = file;
		}
		if (chosen == null || !chosen.has("hashes")) return;
		JsonObject hashes = chosen.getAsJsonObject("hashes");
		if (!hashes.has("sha512")) return;
		String name = chosen.get("filename").getAsString();
		// Only a plain file name, never a path.
		if (name.contains("/") || name.contains("\\") || name.contains("..")) return;
		fileName = name;
		fileUrl = chosen.get("url").getAsString();
		fileSha512 = hashes.get("sha512").getAsString().toLowerCase(Locale.ROOT);
	}

	/** The jar this mod was loaded from, or null in a development run. */
	private static Path ownJar() {
		try {
			var container = FabricLoader.getInstance().getModContainer(Planner.MOD_ID).orElse(null);
			if (container == null) return null;
			ModOrigin origin = container.getOrigin();
			if (origin == null || origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) return null;
			Path jar = origin.getPaths().get(0);
			return Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar") ? jar : null;
		} catch (Throwable t) {
			return null;
		}
	}

	public static boolean canInstall() {
		return ownJar() != null;
	}

	/** {@code /fp update}: downloads the newer version and installs it when the game closes. */
	public static void install() {
		if (ready != null) {
			say("update.ready", newer);
			return;
		}
		if (newer == null) {
			say(checked ? "update.none" : "update.not_checked");
			return;
		}
		Path jar = ownJar();
		if (jar == null || fileUrl == null || fileName == null || fileSha512 == null) {
			say("update.cannot");
			return;
		}
		if (downloading) return;
		downloading = true;
		say("update.downloading", newer);
		Path target = jar.resolveSibling(fileName + SUFFIX);
		Path part = jar.resolveSibling(fileName + SUFFIX + ".part");
		HttpRequest req = request(fileUrl).GET().build();
		HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
				.sendAsync(req, HttpResponse.BodyHandlers.ofFile(part)).thenAccept(response -> {
					try {
						if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
						String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(part)));
						if (!hash.equals(fileSha512)) throw new IllegalStateException("SHA-512 mismatch");
						Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
						ready = target;
						addHook();
						say("update.ready", newer);
					} catch (Exception e) {
						fail(part, e);
					} finally {
						downloading = false;
					}
				}).exceptionally(error -> {
					fail(part, error);
					downloading = false;
					return null;
				});
	}

	private static void fail(Path part, Throwable error) {
		Planner.LOGGER.warn("Update download failed", error);
		try {
			Files.deleteIfExists(part);
		} catch (Exception ignored) {
			// nothing to clean up
		}
		say("update.failed");
	}

	/**
	 * At start: removes leftover downloads that are not newer than this version, and picks up a
	 * newer one that was downloaded before but not installed (the player already agreed to it).
	 */
	private static void cleanUp() {
		Path jar = ownJar();
		if (jar == null) return;
		try (var files = Files.list(jar.getParent())) {
			for (Path p : files.toList()) {
				String name = p.getFileName().toString();
				if (!name.startsWith("foraging-planner-") || !(name.endsWith(SUFFIX) || name.endsWith(SUFFIX + ".part"))) continue;
				String version = name.substring("foraging-planner-".length()).replaceAll("[+].*$", "");
				if (name.endsWith(".part") || compare(version, ModVersion.plain()) <= 0) {
					Files.deleteIfExists(p);
				} else {
					ready = p;
					newer = version;
					addHook();
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.debug("Update clean-up failed", e);
		}
	}

	private static synchronized void addHook() {
		if (hookAdded) return;
		hookAdded = true;
		Runtime.getRuntime().addShutdownHook(new Thread(UpdateChecker::swapOnExit, "foragingplanner-update"));
	}

	/** Starts the helper that swaps the jars once this Minecraft process has exited. */
	private static void swapOnExit() {
		Path update = ready;
		Path old = ownJar();
		if (update == null || old == null || !Files.exists(update)) return;
		String name = update.getFileName().toString();
		Path finalJar = update.resolveSibling(name.substring(0, name.length() - SUFFIX.length()));
		long pid = ProcessHandle.current().pid();
		try {
			ProcessBuilder builder;
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
				String script = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; "
						+ "for ($i = 0; $i -lt 30; $i++) { try { Remove-Item -LiteralPath " + ps(old) + " -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 } }; "
						+ "if (-not (Test-Path -LiteralPath " + ps(old) + ")) { Move-Item -LiteralPath " + ps(update) + " -Destination " + ps(finalJar) + " -Force }";
				builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
			} else {
				String script = "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done; sleep 1; "
						+ "rm -f " + sh(old) + " && [ ! -e " + sh(old) + " ] && mv -f " + sh(update) + " " + sh(finalJar);
				builder = new ProcessBuilder("sh", "-c", script);
			}
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not start the update helper; the update stays for the next start", e);
		}
	}

	private static String ps(Path p) {
		return "'" + p.toAbsolutePath().toString().replace("'", "''") + "'";
	}

	private static String sh(Path p) {
		return "'" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'";
	}

	/** Compares "1.2.3" style versions; missing parts count as 0. */
	static int compare(String a, String b) {
		String[] x = a.split("[.-]"), y = b.split("[.-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int number(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
