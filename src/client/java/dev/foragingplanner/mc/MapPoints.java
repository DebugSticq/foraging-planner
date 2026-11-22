package dev.foragingplanner.mc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

/** Named places of the foraging islands for the map: areas, NPCs, warps, Honeyhives and more. */
public final class MapPoints {
	public static final class Point {
		public String name = "";
		public String type = "";
		public int x, y, z;
		public int size;
	}

	private static final class File {
		Map<String, List<Point>> islands = new HashMap<>();
	}

	private static Map<String, List<Point>> islands;

	private MapPoints() {}

	public static List<Point> forArea(String area) {
		if (islands == null) {
			islands = new HashMap<>();
			try (InputStream in = MapPoints.class.getResourceAsStream("/foragingplanner/map_points.json")) {
				if (in != null) {
					File f = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), File.class);
					if (f != null && f.islands != null) islands.putAll(f.islands);
				}
			} catch (Exception e) {
				Planner.LOGGER.warn("Could not read map_points.json", e);
			}
		}
		if (area == null) return List.of();
		if (area.equalsIgnoreCase("Moonglade Marsh")) area = "Galatea";
		List<Point> list = islands.get(area);
		return list == null ? List.of() : list;
	}
}
