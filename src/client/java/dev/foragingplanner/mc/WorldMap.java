package dev.foragingplanner.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A real map of the island, coloured like a Minecraft map item: every loaded chunk around the
 * player is drawn into 128×128 block tiles from the top block's map colour, with height shading and
 * water depth. Tiles are kept per island and saved as PNG, so the map remembers where you have been.
 */
public final class WorldMap {
	static final int TILE = 128;
	private static final int CHUNKS_PER_TICK = 3;
	private static final long RESCAN_MS = 15_000;
	private static final long SAVE_MS = 30_000;

	private static final class Tile {
		final int tx, tz;
		final NativeImage image;
		final DynamicTexture texture;
		final Identifier id;
		boolean dirtyGpu, dirtyDisk;

		Tile(String island, int tx, int tz, NativeImage image) {
			this.tx = tx;
			this.tz = tz;
			this.image = image;
			this.id = Identifier.fromNamespaceAndPath(Planner.MOD_ID, "map/" + island + "/" + tx + "_" + tz);
			this.texture = new DynamicTexture(() -> "foragingplanner map " + tx + "," + tz, image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
		}
	}

	private static String island = "";
	private static final Map<Long, Tile> TILES = new HashMap<>();
	private static final Map<Long, Long> SCANNED = new HashMap<>();
	private static long lastSave, lastUpload;
	private static int spiral;

	private WorldMap() {}

	/** Island key used for files and textures, or "" when the map is not used here. */
	static String islandKey() {
		String area = SkyBlockReader.area();
		if (area == null || area.isEmpty() || !SkyBlockReader.inForagingArea()) return "";
		if (area.equalsIgnoreCase("Moonglade Marsh")) area = "Galatea";
		return area.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
	}

	public static String island() {
		return island;
	}

	private static long key(int tx, int tz) {
		return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
	}

	private static Path folder(String key) {
		return ModConfig.directory().resolve("maps").resolve(key);
	}

	/** Called every client tick. */
	private static Object lastLevel;
	private static long levelSince;
	private static String areaSeen = "";
	private static long areaSince;

	public static void tick(Minecraft client) {
		if (!Planner.config.worldMap || client.level == null || client.player == null) return;
		long ms = System.currentTimeMillis();
		// After a server switch the sidebar still shows the old island for a moment: wait until the new
		// world and its island name have been the same for a few seconds before drawing anything.
		if (client.level != lastLevel) {
			lastLevel = client.level;
			levelSince = ms;
			switchIsland("");
		}
		String seen = islandKey();
		if (!seen.equals(areaSeen)) {
			areaSeen = seen;
			areaSince = ms;
		}
		if (ms - levelSince < 4000 || ms - areaSince < 3000) return;
		String now = seen;
		if (!now.equals(island)) switchIsland(now);
		if (island.isEmpty()) return;

		// Scan a few chunks per tick, nearest first, each again after a while.
		int cx = client.player.blockPosition().getX() >> 4, cz = client.player.blockPosition().getZ() >> 4;
		int radius = Math.min(10, client.options.getEffectiveRenderDistance());
		long time = System.currentTimeMillis();
		int done = 0, tries = 0, side = 2 * radius + 1, total = side * side;
		while (done < CHUNKS_PER_TICK && tries++ < total) {
			int[] off = spiralOffset(spiral++ % total);
			int x = cx + off[0], z = cz + off[1];
			long k = key(x, z);
			Long last = SCANNED.get(k);
			if (last != null && time - last < RESCAN_MS) continue;
			if (!client.level.hasChunk(x, z)) continue;
			scanChunk(client, x, z);
			SCANNED.put(k, time);
			done++;
		}
		if (time - lastUpload > 500) {
			lastUpload = time;
			for (Tile t : TILES.values()) {
				if (t.dirtyGpu) {
					t.texture.upload();
					t.dirtyGpu = false;
				}
			}
		}
		if (time - lastSave > SAVE_MS) save();
	}

	/** Offsets in a square spiral around 0,0: 0, then ring 1, ring 2, ... */
	private static int[] spiralOffset(int n) {
		if (n == 0) return new int[] {0, 0};
		int ring = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2);
		int sideLen = 2 * ring;
		int start = (2 * ring - 1) * (2 * ring - 1);
		int i = n - start;
		int seg = i / sideLen, pos = i % sideLen;
		return switch (seg) {
			case 0 -> new int[] {ring, -ring + 1 + pos};
			case 1 -> new int[] {ring - 1 - pos, ring};
			case 2 -> new int[] {-ring, ring - 1 - pos};
			default -> new int[] {-ring + 1 + pos, -ring};
		};
	}

	private static void scanChunk(Minecraft client, int chunkX, int chunkZ) {
		var level = client.level;
		int bx = chunkX << 4, bz = chunkZ << 4;
		int minY = level.getMinY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = bx; x < bx + 16; x++) {
			int northHeight = surface(level, x, bz - 1, minY);
			for (int z = bz; z < bz + 16; z++) {
				int y = surface(level, x, z, minY);
				int color = 0;
				if (y > minY) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					MapColor mapColor = state.getMapColor(level, pos);
					int depth = 0;
					while (mapColor == MapColor.NONE && pos.getY() > minY && depth < 12) {
						pos.move(0, -1, 0);
						state = level.getBlockState(pos);
						mapColor = state.getMapColor(level, pos);
						depth++;
					}
					if (mapColor != MapColor.NONE) {
						MapColor.Brightness brightness;
						if (mapColor == MapColor.WATER) {
							int water = 0;
							BlockPos.MutableBlockPos below = pos.mutable();
							while (water < 16 && below.getY() > minY && !level.getFluidState(below).isEmpty()) {
								below.move(0, -1, 0);
								water++;
							}
							double shade = water * 0.1 + ((x + z) & 1) * 0.2;
							brightness = shade < 0.5 ? MapColor.Brightness.HIGH : shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
						} else {
							brightness = y > northHeight ? MapColor.Brightness.HIGH : y < northHeight ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
						}
						color = mapColor.calculateARGBColor(brightness) | 0xFF000000;
					}
				}
				northHeight = y;
				set(x, z, color);
			}
		}
	}

	/** Y of the top block of a column, or minY when the column is empty or not loaded. */
	private static int surface(net.minecraft.client.multiplayer.ClientLevel level, int x, int z, int minY) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		return Math.max(minY, top);
	}

	private static void set(int x, int z, int argb) {
		int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
		Tile tile = tile(tx, tz, argb != 0);
		if (tile == null) return;
		int px = Math.floorMod(x, TILE), pz = Math.floorMod(z, TILE);
		int before = tile.image.getPixel(px, pz);
		if (before == argb) return;
		// An empty column never erases what is already known (e.g. a chunk without height data yet).
		if (argb == 0 && (before >>> 24) != 0) return;
		tile.image.setPixel(px, pz, argb);
		tile.dirtyGpu = true;
		tile.dirtyDisk = true;
	}

	private static Tile tile(int tx, int tz, boolean create) {
		long k = key(tx, tz);
		Tile tile = TILES.get(k);
		if (tile != null || !create || island.isEmpty()) return tile;
		NativeImage image = new NativeImage(TILE, TILE, true);
		image.fillRect(0, 0, TILE, TILE, 0);
		tile = new Tile(island, tx, tz, image);
		TILES.put(k, tile);
		return tile;
	}

	private static void switchIsland(String next) {
		save();
		for (Tile t : TILES.values()) Minecraft.getInstance().getTextureManager().release(t.id);
		TILES.clear();
		SCANNED.clear();
		island = next;
		if (next.isEmpty()) return;
		try {
			Path dir = folder(next);
			if (!Files.isDirectory(dir)) return;
			try (var files = Files.list(dir)) {
				for (Path file : files.toList()) {
					String name = file.getFileName().toString();
					if (!name.endsWith(".png")) continue;
					String[] parts = name.substring(0, name.length() - 4).split("_");
					if (parts.length != 2) continue;
					int tx = Integer.parseInt(parts[0]), tz = Integer.parseInt(parts[1]);
					try (var in = Files.newInputStream(file)) {
						NativeImage read = NativeImage.read(in);
						if (read.getWidth() != TILE || read.getHeight() != TILE) {
							read.close();
							continue;
						}
						Tile tile = new Tile(next, tx, tz, read);
						tile.texture.upload();
						TILES.put(key(tx, tz), tile);
					}
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not load the map of {}", next, e);
		}
		loadBundled(next);
	}

	/**
	 * The map that comes with the mod (resources/foragingplanner/maps/&lt;island&gt;/), so the whole island
	 * is there from the first start. The player's own map is drawn over it; only pixels the player
	 * has not explored yet are taken from it.
	 */
	private static void loadBundled(String key) {
		try (var index = WorldMap.class.getResourceAsStream("/foragingplanner/maps/index.json")) {
			if (index == null) return;
			var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(index, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
			if (!json.has(key)) return;
			for (var element : json.getAsJsonArray(key)) {
				String name = element.getAsString();
				String[] parts = name.split("_");
				if (parts.length != 2) continue;
				int tx = Integer.parseInt(parts[0]), tz = Integer.parseInt(parts[1]);
				try (var in = WorldMap.class.getResourceAsStream("/foragingplanner/maps/" + key + "/" + name + ".png")) {
					if (in == null) continue;
					NativeImage base = NativeImage.read(in);
					if (base.getWidth() != TILE || base.getHeight() != TILE) {
						base.close();
						continue;
					}
					Tile own = TILES.get(key(tx, tz));
					if (own == null) {
						Tile tile = new Tile(key, tx, tz, base);
						tile.texture.upload();
						TILES.put(key(tx, tz), tile);
						continue;
					}
					for (int x = 0; x < TILE; x++) {
						for (int z = 0; z < TILE; z++) {
							if ((own.image.getPixel(x, z) >>> 24) == 0) own.image.setPixel(x, z, base.getPixel(x, z));
						}
					}
					base.close();
					own.texture.upload();
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not load the bundled map of {}", key, e);
		}
	}

	public static void save() {
		lastSave = System.currentTimeMillis();
		if (island.isEmpty()) return;
		try {
			Path dir = folder(island);
			for (Tile t : TILES.values()) {
				if (!t.dirtyDisk) continue;
				Files.createDirectories(dir);
				t.image.writeToFile(dir.resolve(t.tx + "_" + t.tz + ".png"));
				t.dirtyDisk = false;
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not save the map", e);
		}
	}

	/** Forgets the map of the current island. */
	public static void clear() {
		String now = island;
		for (Iterator<Tile> it = TILES.values().iterator(); it.hasNext();) {
			Tile t = it.next();
			Minecraft.getInstance().getTextureManager().release(t.id);
			it.remove();
		}
		SCANNED.clear();
		try {
			Path dir = folder(now);
			if (Files.isDirectory(dir)) try (var files = Files.list(dir)) {
				for (Path f : files.toList()) Files.deleteIfExists(f);
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not clear the map", e);
		}
	}

	public static boolean hasTiles() {
		return !TILES.isEmpty();
	}

	/**
	 * Draws the map so that world point (centerX, centerZ) is at screen point (sx, sy), with
	 * {@code scale} screen pixels per block. The caller clips with a scissor.
	 */
	public static void draw(GuiGraphicsExtractor g, double centerX, double centerZ, double sx, double sy, double scale, int left, int top, int right, int bottom) {
		for (Tile t : TILES.values()) {
			double wx0 = t.tx * (double) TILE, wz0 = t.tz * (double) TILE;
			int x0 = (int) Math.floor(sx + (wx0 - centerX) * scale);
			int z0 = (int) Math.floor(sy + (wz0 - centerZ) * scale);
			int x1 = (int) Math.floor(sx + (wx0 + TILE - centerX) * scale);
			int z1 = (int) Math.floor(sy + (wz0 + TILE - centerZ) * scale);
			if (x1 < left || x0 > right || z1 < top || z0 > bottom || x1 <= x0 || z1 <= z0) continue;
			g.blit(RenderPipelines.GUI_TEXTURED, t.id, x0, z0, 0f, 0f, x1 - x0, z1 - z0, TILE, TILE, TILE, TILE);
		}
	}
}
