package dev.foragingplanner.mc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's own walking routes: record them point by point, switch between them, and import or
 * export them in the formats of Skyblocker, Skytils, SkyHanni and ColeWeight.
 *
 * <p>Routes are kept for all profiles in config/foragingplanner/routes.json and belong to an island,
 * named like Skyblocker does ("foraging_2" for Galatea, "foraging_3" for Torrhus Canyon).
 */
public final class Routes {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String SKYBLOCKER = "[Skyblocker-Waypoint-Data-V1]";
	private static final String SKYBLOCKER_ORDERED = "[Skyblocker::OrderedWaypoints::v1]";
	private static final String SKYTILS = "<Skytils-Waypoint-Data>(V";
	private static final Pattern COORDS = Pattern.compile("(-?\\d+(?:\\.\\d+)?)[ ,;]+(-?\\d+(?:\\.\\d+)?)[ ,;]+(-?\\d+(?:\\.\\d+)?)");
	/** A point counts as reached within this distance, then the next one becomes the target. */
	private static final double REACHED = 3.5;

	public static final class Point {
		public double x, y, z;
		public String name = "";
		/** "walk" or "etherwarp". For an etherwarp point x, y, z is the block to warp onto. */
		public String type = "walk";

		public Point() {}

		public Point(double x, double y, double z, String name) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.name = name == null ? "" : name;
		}

		public Vec3 pos() {
			return etherwarp() ? new Vec3(x + 0.5, y + 1, z + 0.5) : new Vec3(x + 0.5, y, z + 0.5);
		}

		public boolean etherwarp() {
			return "etherwarp".equals(type);
		}

		static Point warp(double x, double y, double z, String name) {
			Point point = new Point(x, y, z, name);
			point.type = "etherwarp";
			return point;
		}
	}

	public static final class Route {
		public String name = "Route";
		public String island = "";
		public List<Point> points = new ArrayList<>();
		public boolean loop = true;
		public long created = System.currentTimeMillis();
		/** Ghost: milliseconds from lap start when each point was reached on the best lap. */
		public List<Long> bestSplits = new ArrayList<>();
		public long bestLap;
	}

	private static final class Store {
		List<Route> routes = new ArrayList<>();
		/** Active route per island, by name. */
		java.util.Map<String, String> active = new java.util.LinkedHashMap<>();
	}

	private static Store store;
	private static Route recording;
	private static int target;
	private static String targetRoute = "";
	private static KeyMapping pointKey;
	private static KeyMapping warpKey;
	private static Vec3 lastPos;
	/** Ghost lap state. */
	private static long lapStart;
	private static final List<Long> lapSplits = new ArrayList<>();
	private static long lastDelta;
	private static boolean hasDelta;
	/** Etherwarp reaches about 57 blocks, more with Tuned Transmission; the ray goes a bit further. */
	public static final double WARP_RANGE = 61;

	private Routes() {}

	public static void register() {
		pointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.route_point", InputConstants.KEY_K, KeyMapping.Category.MISC));
		warpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.route_warp", InputConstants.KEY_J, KeyMapping.Category.MISC));
	}

	// Islands

	/** The current island as Skyblocker names it. */
	public static String island() {
		String area = SkyBlockReader.area().toLowerCase(Locale.ROOT);
		if (area.contains("torrhus") || area.contains("canyon")) return "foraging_3";
		if (area.contains("park")) return "foraging_1";
		return "foraging_2";
	}

	public static String islandName(String island) {
		return switch (island) {
			case "foraging_1" -> "The Park";
			case "foraging_3" -> "Torrhus Canyon";
			case "foraging_2" -> "Galatea";
			default -> island.isEmpty() ? "?" : island;
		};
	}

	// Store

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Planner.MOD_ID).resolve("routes.json");
	}

	private static Store store() {
		if (store != null) return store;
		store = new Store();

		try {
			if (Files.exists(file())) {
				Store read = GSON.fromJson(Files.readString(file()), Store.class);
				if (read != null) store = read;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read routes.json", e);
		}

		if (store.routes == null) store.routes = new ArrayList<>();
		if (store.active == null) store.active = new java.util.LinkedHashMap<>();
		store.routes.removeIf(r -> r == null || r.points == null);
		return store;
	}

	public static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(store()));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save routes.json", e);
		}
	}

	/** Routes of the current island. */
	public static List<Route> here() {
		String island = island();
		List<Route> list = new ArrayList<>();
		for (Route route : store().routes) if (route.island.equals(island)) list.add(route);
		return list;
	}

	public static List<Route> all() {
		return store().routes;
	}

	public static Route active() {
		String name = store().active.get(island());
		if (name == null) return null;
		for (Route route : here()) if (route.name.equals(name)) return route;
		return null;
	}

	public static void activate(Route route) {
		if (route == null) store().active.remove(island());
		else store().active.put(route.island, route.name);
		target = 0;
		save();
	}

	public static void delete(Route route) {
		store().routes.remove(route);
		store().active.values().removeIf(name -> name.equals(route.name));
		save();
	}

	private static String uniqueName(String wanted, String island) {
		String name = wanted == null || wanted.isBlank() ? "Route" : wanted.trim();
		String result = name;
		int n = 2;
		while (true) {
			String candidate = result;
			if (store().routes.stream().noneMatch(r -> r.island.equals(island) && r.name.equals(candidate))) return result;
			result = name + " " + n++;
		}
	}

	// Recording

	public static Route recording() {
		return recording;
	}

	public static void startRecording(String name) {
		recording = new Route();
		recording.island = island();
		recording.name = uniqueName(name, recording.island);
		message(Planner.tr("route.recording", recording.name));
	}

	/** Adds the tree the player looks at, or else the player's own position. */
	public static void addPoint() {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		if (recording == null) startRecording("Route");

		TreeTracker.Spot spot = TreeTracker.looked();
		Point point;
		if (spot != null) point = new Point(Math.floor(spot.x), Math.floor(spot.y), Math.floor(spot.z), StatsScreen.capital(spot.tree));
		else point = new Point(Math.floor(player.getX()), Math.floor(player.getY()), Math.floor(player.getZ()), "");

		recording.points.add(point);
		message(Planner.tr("route.point_added", recording.points.size(), point.name.isEmpty() ? Planner.tr("route.here") : point.name));
	}

	/** The block the player is aiming at within etherwarp range, or null. */
	public static net.minecraft.core.BlockPos aimedBlock() {
		var player = Minecraft.getInstance().player;
		if (player == null) return null;
		var hit = player.pick(WARP_RANGE, 1.0f, false);
		if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) return block.getBlockPos();
		return null;
	}

	/** Adds the block the player aims at as an etherwarp target. */
	public static void addWarpPoint() {
		if (recording == null) startRecording("Route");
		var block = aimedBlock();
		if (block == null) {
			message(Planner.tr("route.warp_none"));
			return;
		}
		recording.points.add(Point.warp(block.getX(), block.getY(), block.getZ(), ""));
		message(Planner.tr("route.warp_added", recording.points.size(), block.getX() + " " + block.getY() + " " + block.getZ()));
	}

	/** Holding an Aspect of the Void or End: a big jump in one tick was an etherwarp or teleport. */
	private static boolean holdsWarpItem(Minecraft client) {
		String id = GearAdvisor.itemId(client.player.getMainHandItem());
		return id.contains("ASPECT_OF_THE_VOID") || id.contains("ASPECT_OF_THE_END") || id.contains("ETHERWARP");
	}

	public static void undo() {
		if (recording == null || recording.points.isEmpty()) return;
		recording.points.removeLast();
		message(Planner.tr("route.undone", recording.points.size()));
	}

	public static void finishRecording() {
		if (recording == null) return;
		if (recording.points.size() < 2) {
			message(Planner.tr("route.too_short"));
			return;
		}
		store().routes.add(recording);
		activate(recording);
		message(Planner.tr("route.saved", recording.name, recording.points.size()));
		recording = null;
	}

	public static void cancelRecording() {
		recording = null;
		message(Planner.tr("route.cancelled"));
	}

	private static void message(String text) {
		JournalStore.notify(text);
	}

	// Following

	/** Called every tick. Handles the key and moves on to the next point when one is reached. */
	public static void tick(Minecraft client) {
		if (pointKey != null) while (pointKey.consumeClick()) {
			if (SkyBlockReader.inSkyBlock()) addPoint();
		}
		if (warpKey != null) while (warpKey.consumeClick()) {
			if (SkyBlockReader.inSkyBlock()) addWarpPoint();
		}

		// While recording, an etherwarp is stored by itself: a jump of many blocks in one tick while sneaking with the item.
		if (client.player != null) {
			Vec3 now = client.player.position();
			if (recording != null && lastPos != null && now.distanceTo(lastPos) > 6 && client.player.isShiftKeyDown() && holdsWarpItem(client)) {
				recording.points.add(Point.warp(Math.floor(now.x), Math.floor(now.y) - 1, Math.floor(now.z), ""));
				message(Planner.tr("route.warp_auto", recording.points.size()));
			}
			lastPos = now;
		}

		Route route = active();
		if (route == null || client.player == null || route.points.isEmpty()) return;
		if (!route.name.equals(targetRoute)) {
			targetRoute = route.name;
			target = nearestIndex(route, client.player.position());
		}
		target = Math.floorMod(target, route.points.size());
		Point next = route.points.get(target);
		double dx = next.x + 0.5 - client.player.getX();
		double dz = next.z + 0.5 - client.player.getZ();
		double reach = next.etherwarp() ? 1.6 : REACHED;
		double dy = next.pos().y - client.player.getY();
		if (dx * dx + dz * dz <= reach * reach && Math.abs(dy) < (next.etherwarp() ? 1.6 : 6)) {
			reached(route, target);
			target = route.loop ? (target + 1) % route.points.size() : Math.min(target + 1, route.points.size() - 1);
		}
	}

	// Ghost mode

	private static void reached(Route route, int index) {
		long now = System.currentTimeMillis();
		if (!route.loop || route.points.size() < 2) return;
		if (index == 0) {
			if (lapStart > 0 && lapSplits.size() == route.points.size() - 1) {
				long lap = now - lapStart;
				boolean best = route.bestLap <= 0 || lap < route.bestLap;
				if (best) {
					route.bestLap = lap;
					route.bestSplits = new ArrayList<>(lapSplits);
					route.bestSplits.addFirst(0L);
					save();
				}
				if (Planner.config.ghostRoutes) Toasts.show(best ? Toasts.Kind.MILESTONE : Toasts.Kind.INFO, "clock", Planner.tr(best ? "route.ghost_best" : "route.ghost_lap"), time(lap) + (best ? "" : "  " + delta(lap - route.bestLap)));
			}
			lapStart = now;
			lapSplits.clear();
			hasDelta = false;
			return;
		}
		if (lapStart <= 0) return;
		if (index != lapSplits.size() + 1) { lapStart = 0; lapSplits.clear(); hasDelta = false; return; }
		long split = now - lapStart;
		lapSplits.add(split);
		if (route.bestSplits.size() == route.points.size()) {
			lastDelta = split - route.bestSplits.get(index);
			hasDelta = true;
		}
	}

	/** Where the best lap was at this moment of the current lap, or null. */
	public static Vec3 ghost() {
		Route route = active();
		if (!Planner.config.ghostRoutes || route == null || lapStart <= 0 || route.bestSplits.size() != route.points.size()) return null;
		long t = System.currentTimeMillis() - lapStart;
		int n = route.points.size();
		for (int i = 0; i < n; i++) {
			long a = route.bestSplits.get(i);
			long b = i + 1 < n ? route.bestSplits.get(i + 1) : route.bestLap;
			if (t >= a && t < b) {
				double f = b == a ? 0 : (t - a) / (double) (b - a);
				Vec3 from = route.points.get(i).pos(), to = route.points.get((i + 1) % n).pos();
				return from.lerp(to, f);
			}
		}
		return null;
	}

	/** HUD line: current lap time and the last split compared to the best lap. */
	public static String ghostLine() {
		Route route = active();
		if (!Planner.config.ghostRoutes || route == null || lapStart <= 0) return "";
		String line = Planner.tr("route.ghost_hud", time(System.currentTimeMillis() - lapStart));
		if (hasDelta) line += "  " + delta(lastDelta);
		if (route.bestLap > 0) line += "  " + Planner.tr("route.ghost_pb", time(route.bestLap));
		return line;
	}

	public static boolean ahead() {
		return hasDelta && lastDelta <= 0;
	}

	private static String time(long ms) {
		long s = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
	}

	private static String delta(long ms) {
		return (ms <= 0 ? "-" : "+") + String.format(Locale.ROOT, "%.1fs", Math.abs(ms) / 1000.0);
	}

	public static void resetGhost(Route route) {
		route.bestLap = 0;
		route.bestSplits = new ArrayList<>();
		lapStart = 0;
		lapSplits.clear();
		save();
	}

	private static int nearestIndex(Route route, Vec3 from) {
		int best = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < route.points.size(); i++) {
			double d = route.points.get(i).pos().distanceToSqr(from);
			if (d < bestDistance) { bestDistance = d; best = i; }
		}
		return best;
	}

	public static int target() {
		return target;
	}

	/** Jumps to the next point by hand. */
	public static void skip() {
		Route route = active();
		if (route != null && !route.points.isEmpty()) target = (target + 1) % route.points.size();
	}

	// Import

	/** Result of an import: how many routes were added, or an error text. */
	public record ImportResult(int routes, int points, String error) {}

	/** Reads routes from text in any supported format and adds them for the current island. */
	public static ImportResult importText(String text) {
		if (text == null || text.isBlank()) return new ImportResult(0, 0, Planner.tr("route.import_empty"));
		List<Route> found;

		try {
			found = parse(text.trim());
		} catch (Exception e) {
			Planner.LOGGER.warn("Route import failed", e);
			found = List.of();
		}

		if (found.isEmpty()) return new ImportResult(0, 0, Planner.tr("route.import_unknown"));
		int points = 0;
		for (Route route : found) {
			if (route.island.isBlank()) route.island = island();
			route.name = uniqueName(route.name, route.island);
			store().routes.add(route);
			points += route.points.size();
		}
		if (found.size() == 1 && found.getFirst().island.equals(island())) activate(found.getFirst());
		save();
		return new ImportResult(found.size(), points, null);
	}

	static List<Route> parse(String text) throws Exception {
		if (text.startsWith(SKYBLOCKER)) return fromSkyblockerGroups(JsonParser.parseString(gunzip(text.substring(SKYBLOCKER.length()))));
		if (text.startsWith(SKYBLOCKER_ORDERED)) return fromSkyblockerOrdered(JsonParser.parseString(gunzip(text.substring(SKYBLOCKER_ORDERED.length()))));
		if (text.startsWith(SKYTILS)) return fromSkytils(JsonParser.parseString(gunzip(text.substring(text.indexOf(':') + 1))));
		if (text.startsWith("[") || text.startsWith("{")) return fromJson(JsonParser.parseString(text));

		// Plain Base64, as Skytils used to share its waypoints.
		if (text.matches("[A-Za-z0-9+/=\\s]+") && text.length() > 20) {
			try {
				String decoded = new String(Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8).trim();
				if (decoded.startsWith("[") || decoded.startsWith("{")) return fromJson(JsonParser.parseString(decoded));
			} catch (IllegalArgumentException ignored) {
				// Not Base64 after all.
			}
		}

		// One point per line: "x y z" or "x, y, z", with an optional name after it.
		Route route = new Route();
		route.name = Planner.tr("route.imported");
		for (String line : text.split("\\R")) {
			Matcher m = COORDS.matcher(line);
			if (!m.find()) continue;
			String rest = line.substring(m.end()).replaceAll("^[\\s,;:-]+", "").trim();
			double x = Double.parseDouble(m.group(1)), y = Double.parseDouble(m.group(2)), z = Double.parseDouble(m.group(3));
			route.points.add(rest.toLowerCase(Locale.ROOT).contains("ether") ? Point.warp(x, y, z, rest) : new Point(x, y, z, rest));
		}
		return route.points.isEmpty() ? List.of() : List.of(route);
	}

	private static String gunzip(String base64) throws Exception {
		byte[] bytes = Base64.getDecoder().decode(base64.trim());
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String gzip(String text) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
			zip.write(text.getBytes(StandardCharsets.UTF_8));
		}
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/** Recognises the JSON shapes of Skyblocker, Skytils, SkyHanni and ColeWeight. */
	private static List<Route> fromJson(JsonElement json) {
		if (json.isJsonObject()) {
			JsonObject object = json.getAsJsonObject();
			if (object.has("categories")) return fromSkytils(json);
			if (object.has("waypoints")) {
				JsonArray one = new JsonArray();
				one.add(object);
				return fromSkyblockerGroups(one);
			}
			return fromSkyblockerOrdered(json);
		}

		JsonArray array = json.getAsJsonArray();
		if (array.isEmpty()) return List.of();
		JsonObject first = array.get(0).getAsJsonObject();
		if (first.has("waypoints")) return fromSkyblockerGroups(array);

		Route route = new Route();
		route.name = Planner.tr("route.imported");
		route.points.addAll(points(array));
		return route.points.isEmpty() ? List.of() : List.of(route);
	}

	/** Waypoints as Skytils, SkyHanni or ColeWeight write them, or Skyblocker's {"pos": [x, y, z]}. */
	private static List<Point> points(JsonArray array) {
		List<Point> points = new ArrayList<>();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) continue;
			JsonObject w = element.getAsJsonObject();
			if (w.has("enabled") && !w.get("enabled").getAsBoolean()) continue;
			double x, y, z;

			if (w.has("pos") && w.get("pos").isJsonArray()) {
				JsonArray pos = w.getAsJsonArray("pos");
				x = pos.get(0).getAsDouble();
				y = pos.get(1).getAsDouble();
				z = pos.get(2).getAsDouble();
			} else if (w.has("x") && w.has("y") && w.has("z")) {
				x = w.get("x").getAsDouble();
				y = w.get("y").getAsDouble();
				z = w.get("z").getAsDouble();
			} else continue;

			String name = name(w);
			String lower = name.toLowerCase(Locale.ROOT);
			boolean warp = lower.contains("ether") || lower.matches("(?:ew|warp|tp)\\b.*") || (w.has("type") && w.get("type").getAsString().toLowerCase(Locale.ROOT).contains("ether"));
			points.add(warp ? Point.warp(x, y, z, name) : new Point(x, y, z, name));
		}

		// ColeWeight and SkyHanni number their points; keep that order.
		if (points.stream().allMatch(p -> p.name.matches("\\d+.*"))) points.sort((a, b) -> Integer.compare(leading(a.name), leading(b.name)));
		return points;
	}

	private static int leading(String text) {
		Matcher m = Pattern.compile("^(\\d+)").matcher(text);
		return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
	}

	private static String name(JsonObject w) {
		JsonElement name = w.get("name");
		if (name == null && w.has("options") && w.get("options").isJsonObject()) name = w.getAsJsonObject("options").get("name");
		if (name == null || name.isJsonNull()) return "";
		if (name.isJsonPrimitive()) return name.getAsString();
		if (name.isJsonObject() && name.getAsJsonObject().has("text")) return name.getAsJsonObject().get("text").getAsString();
		return "";
	}

	private static List<Route> fromSkyblockerGroups(JsonElement json) {
		List<Route> routes = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray()) {
			JsonObject group = element.getAsJsonObject();
			Route route = new Route();
			route.name = group.has("name") ? group.get("name").getAsString() : Planner.tr("route.imported");
			route.island = group.has("island") ? group.get("island").getAsString() : "";
			route.points.addAll(points(group.getAsJsonArray("waypoints")));
			if (!route.points.isEmpty()) routes.add(route);
		}
		return routes;
	}

	private static List<Route> fromSkyblockerOrdered(JsonElement json) {
		List<Route> routes = new ArrayList<>();
		for (var entry : json.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			JsonObject group = entry.getValue().getAsJsonObject();
			if (!group.has("waypoints")) continue;
			Route route = new Route();
			route.name = group.has("name") ? group.get("name").getAsString() : entry.getKey();
			route.points.addAll(points(group.getAsJsonArray("waypoints")));
			if (!route.points.isEmpty()) routes.add(route);
		}
		return routes;
	}

	private static List<Route> fromSkytils(JsonElement json) {
		if (json.isJsonArray()) return fromJson(json);
		List<Route> routes = new ArrayList<>();
		for (JsonElement element : json.getAsJsonObject().getAsJsonArray("categories")) {
			JsonObject category = element.getAsJsonObject();
			Route route = new Route();
			route.name = category.has("name") ? category.get("name").getAsString() : Planner.tr("route.imported");
			route.island = category.has("island") ? category.get("island").getAsString() : "";
			route.points.addAll(points(category.getAsJsonArray("waypoints")));
			if (!route.points.isEmpty()) routes.add(route);
		}
		return routes;
	}

	/** Groups saved in Skyblocker's own waypoint file on this computer, if Skyblocker is installed. */
	public static List<Route> skyblockerFile() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("skyblocker").resolve("waypoints.json");
		try {
			if (!Files.exists(path)) return List.of();
			return fromSkyblockerGroups(JsonParser.parseString(Files.readString(path)));
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read Skyblocker waypoints", e);
			return List.of();
		}
	}

	/** Copies a route from Skyblocker's file into this mod. */
	public static void adopt(Route route) {
		Route copy = new Route();
		copy.name = uniqueName(route.name, route.island.isBlank() ? island() : route.island);
		copy.island = route.island.isBlank() ? island() : route.island;
		for (Point p : route.points) {
			Point c = new Point(p.x, p.y, p.z, p.name);
			c.type = p.type;
			copy.points.add(c);
		}
		store().routes.add(copy);
		if (copy.island.equals(island())) activate(copy);
		save();
	}

	// Export

	/** ColeWeight/SkyHanni JSON: imports in Skyblocker, SkyHanni and most other mods. */
	public static String exportColeweight(Route route) {
		JsonArray array = new JsonArray();
		for (int i = 0; i < route.points.size(); i++) {
			Point p = route.points.get(i);
			JsonObject w = new JsonObject();
			w.addProperty("x", (int) Math.floor(p.x));
			w.addProperty("y", (int) Math.floor(p.y));
			w.addProperty("z", (int) Math.floor(p.z));
			w.addProperty("r", 0.53);
			w.addProperty("g", 0.84);
			w.addProperty("b", 0.55);
			JsonObject options = new JsonObject();
			options.addProperty("name", p.etherwarp() ? (i + 1) + " Etherwarp" : String.valueOf(i + 1));
			w.add("options", options);
			array.add(w);
		}
		return new Gson().toJson(array);
	}

	/** Skyblocker's own sharing format with name and island. */
	public static String exportSkyblocker(Route route) {
		try {
			JsonArray waypoints = new JsonArray();
			for (int i = 0; i < route.points.size(); i++) {
				Point p = route.points.get(i);
				JsonObject w = new JsonObject();
				JsonArray pos = new JsonArray();
				pos.add((int) Math.floor(p.x));
				pos.add((int) Math.floor(p.y));
				pos.add((int) Math.floor(p.z));
				w.add("pos", pos);
				String label = p.name.isEmpty() ? String.valueOf(i + 1) : p.name;
				w.addProperty("name", p.etherwarp() && !label.toLowerCase(Locale.ROOT).contains("ether") ? label + " Etherwarp" : label);
				JsonArray color = new JsonArray();
				color.add(0.53f);
				color.add(0.84f);
				color.add(0.55f);
				w.add("colorComponents", color);
				w.addProperty("alpha", 0.5f);
				w.addProperty("shouldRender", true);
				waypoints.add(w);
			}
			JsonObject group = new JsonObject();
			group.addProperty("name", route.name);
			group.addProperty("island", route.island);
			group.add("waypoints", waypoints);
			group.addProperty("ordered", true);
			JsonArray groups = new JsonArray();
			groups.add(group);
			return SKYBLOCKER + gzip(new Gson().toJson(groups));
		} catch (Exception e) {
			return exportColeweight(route);
		}
	}

	public static void copy(String text) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
	}

	public static String clipboard() {
		return Minecraft.getInstance().keyboardHandler.getClipboard();
	}

}
