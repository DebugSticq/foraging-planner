package dev.foragingplanner.mc;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Draws the low-poly axe meshes (made from the player's GLB/OBJ files) as real triangles. Glowing
 * materials ignore the light level and pulse slowly along the blade.
 */
public final class MeshAxe implements NoDataSpecialModelRenderer {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath(Planner.MOD_ID, "textures/misc/white.png");
	private static final Map<String, Mesh> MESHES = new HashMap<>();

	/** One mesh: colours per material, part names with their boxes, and triangles (position, normal, original height). */
	private record Mesh(float[] colors, int[] material, int[] part, String[] parts, float[] boxes, float[] data, Vector3f min, Vector3f max) {}

	private final String name;

	private MeshAxe(String name) {
		this.name = name;
	}

	/** Registers foragingplanner:axe_mesh_ice, _nature and _lava for item definitions. */
	public static void register() {
		for (String name : new String[] {"ice", "nature", "lava"}) {
			MapCodec<Unbaked> codec = MapCodec.unit(new Unbaked(name));
			CODECS.put(name, codec);
			SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath(Planner.MOD_ID, "axe_mesh_" + name), codec);
		}
	}

	private static final Map<String, MapCodec<Unbaked>> CODECS = new HashMap<>();

	public record Unbaked(String name) implements NoDataSpecialModelRenderer.Unbaked {
		@Override
		public MapCodec<Unbaked> type() {
			return CODECS.get(name);
		}

		@Override
		public SpecialModelRenderer<Void> bake(SpecialModelRenderer.BakingContext context) {
			return new MeshAxe(name);
		}
	}

	private static Mesh mesh(String name) {
		return MESHES.computeIfAbsent(name, MeshAxe::load);
	}

	private static Mesh load(String name) {
		try (InputStream raw = MeshAxe.class.getResourceAsStream("/assets/foragingplanner/meshes/" + name + ".bin")) {
			if (raw == null) throw new IllegalStateException("missing mesh " + name);
			DataInputStream in = new DataInputStream(raw);
			byte[] magic = new byte[4];
			in.readFully(magic);
			int materials = in.readInt();
			float[] colors = new float[materials * 4];
			for (int i = 0; i < colors.length; i++) colors[i] = in.readFloat();
			String[] parts = new String[in.readInt()];
			for (int i = 0; i < parts.length; i++) parts[i] = in.readUTF().toLowerCase(java.util.Locale.ROOT);
			float[] boxes = new float[parts.length * 6];
			for (int i = 0; i < parts.length; i++) {
				boxes[i * 6] = boxes[i * 6 + 1] = boxes[i * 6 + 2] = Float.MAX_VALUE;
				boxes[i * 6 + 3] = boxes[i * 6 + 4] = boxes[i * 6 + 5] = -Float.MAX_VALUE;
			}
			int tris = in.readInt();
			int[] material = new int[tris], part = new int[tris];
			float[] data = new float[tris * 21];
			Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
			for (int t = 0; t < tris; t++) {
				material[t] = in.readInt();
				part[t] = in.readInt();
				for (int k = 0; k < 21; k++) data[t * 21 + k] = in.readFloat();
				for (int v = 0; v < 3; v++) {
					int o = t * 21 + v * 7;
					min.min(new Vector3f(data[o], data[o + 1], data[o + 2]));
					max.max(new Vector3f(data[o], data[o + 1], data[o + 2]));
					for (int a = 0; a < 3; a++) {
						boxes[part[t] * 6 + a] = Math.min(boxes[part[t] * 6 + a], data[o + a]);
						boxes[part[t] * 6 + 3 + a] = Math.max(boxes[part[t] * 6 + 3 + a], data[o + a]);
					}
				}
			}
			return new Mesh(colors, material, part, parts, boxes, data, min, max);
		} catch (Exception e) {
			Planner.LOGGER.error("Could not load axe mesh {}", name, e);
			return new Mesh(new float[0], new int[0], new int[0], new String[0], new float[0], new float[0], new Vector3f(), new Vector3f(1));
		}
	}

	/** Box (min xyz, max xyz) of all parts whose name contains one of the words. */
	private static float[] box(Mesh mesh, String... words) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < mesh.parts().length; i++) {
			for (String w : words) {
				if (!mesh.parts()[i].contains(w)) continue;
				for (int a = 0; a < 3; a++) {
					b[a] = Math.min(b[a], mesh.boxes()[i * 6 + a]);
					b[3 + a] = Math.max(b[3 + a], mesh.boxes()[i * 6 + 3 + a]);
				}
			}
		}
		if (b[0] > b[3]) return new float[] {0.4f, 0.4f, 0.45f, 0.6f, 0.6f, 0.55f};
		return b;
	}

	/** How each part moves: 0 still, 1 leaves, 2 vine, 3 moss, 4 swelling glow. */
	private static int motion(String part) {
		if (part.contains("leaves")) return 1;
		if (part.contains("vine")) return 2;
		if (part.contains("moss")) return 3;
		if (part.contains("crack") || part.contains("hot_edge") || part.contains("frozen_edge") || part.contains("rivet") && part.contains("molten")) return 4;
		return 0;
	}

	private static float hash(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean glint, int seed) {
		Mesh mesh = mesh(name);
		if (mesh.material().length == 0) return;
		float time = (System.currentTimeMillis() % 1_000_000L) / 1000f;
		boolean animate = Planner.config.animations;
		float speed = name.equals("lava") ? 2.4f : name.equals("ice") ? 1.6f : 1.0f;
		int[] motions = new int[mesh.parts().length];
		for (int i = 0; i < motions.length; i++) motions[i] = animate ? motion(mesh.parts()[i]) : 0;
		float[] moss = box(mesh, "moss");
		float mossX = (moss[0] + moss[3]) / 2, mossY = (moss[1] + moss[4]) / 2, mossZ = (moss[2] + moss[5]) / 2;
		float breathe = 1 + 0.035f * (float) Math.sin(time * 1.5f);

		collector.submitCustomGeometry(pose, RenderTypes.entitySolid(WHITE), (p, buffer) -> {
			float[] d = mesh.data();
			float[] xyz = new float[9];
			for (int t = 0; t < mesh.material().length; t++) {
				int m = mesh.material()[t] * 4;
				float r = mesh.colors()[m], g = mesh.colors()[m + 1], b = mesh.colors()[m + 2], glow = mesh.colors()[m + 3];
				int o = t * 21;
				int move = motions[mesh.part()[t]];
				for (int v = 0; v < 3; v++) {
					int at = o + v * 7;
					float x = d[at], y = d[at + 1], z = d[at + 2];
					switch (move) {
						case 1 -> { // leaves sway in the wind
							z += 0.016f * (float) Math.sin(time * 2.1f + x * 11 + y * 7);
							x += 0.006f * (float) Math.sin(time * 1.7f + y * 9);
						}
						case 2 -> z += 0.007f * (float) Math.sin(time * 2.6f + y * 20 + x * 6);
						case 3 -> { // moss breathes
							x = mossX + (x - mossX) * breathe;
							y = mossY + (y - mossY) * breathe;
							z = mossZ + (z - mossZ) * breathe;
						}
						case 4 -> { // glowing parts swell a little with the pulse
							float s = 0.0025f * (0.5f + 0.5f * (float) Math.sin(time * speed * 2.2f - d[at + 6] * 9f));
							x += d[at + 3] * s;
							y += d[at + 4] * s;
							z += d[at + 5] * s;
						}
						default -> { }
					}
					xyz[v * 3] = x;
					xyz[v * 3 + 1] = y;
					xyz[v * 3 + 2] = z;
				}
				// Front side, then the back side, so thin details stay visible from both sides.
				for (int side = 0; side < 2; side++) {
					for (int k = 0; k < 4; k++) {
						int v = side == 0 ? Math.min(k, 2) : 2 - Math.min(k, 2);
						int at = o + v * 7;
						float shine = 1;
						int vertexLight = light;
						if (glow > 0) {
							shine = animate ? 0.8f + 0.35f * (float) Math.sin(time * speed * 2.2f - d[at + 6] * 9f) * Math.min(1, glow * 1.4f) : 1;
							vertexLight = LightCoordsUtil.FULL_BRIGHT;
						}
						float n = side == 0 ? 1 : -1;
						vertex(buffer, p, xyz[v * 3], xyz[v * 3 + 1], xyz[v * 3 + 2], d[at + 3] * n, d[at + 4] * n, d[at + 5] * n,
								r * shine, g * shine, b * shine, vertexLight, overlay);
					}
				}
			}
			if (animate) particles(mesh, buffer, p, time, overlay);
		});
	}

	/** Extra moving pieces around the axe: ice crystals, spores, embers. */
	private void particles(Mesh mesh, VertexConsumer buffer, PoseStack.Pose p, float time, int overlay) {
		switch (name) {
			case "ice" -> {
				float[] b = box(mesh, "crescent", "frozen_edge");
				float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = 0.5f;
				for (int i = 0; i < 5; i++) {
					double a = time * 0.9 + i * Math.PI * 2 / 5;
					float x = cx + (float) Math.cos(a) * 0.3f;
					float y = cy + (float) Math.sin(a) * 0.24f + 0.02f * (float) Math.sin(time * 2 + i);
					float z = cz + (float) Math.cos(a) * 0.16f;
					float shine = 0.85f + 0.15f * (float) Math.sin(time * 3 + i);
					crystal(buffer, p, x, y, z, 0.026f, time * 1.3f + i, 0.72f * shine, 0.94f * shine, 1.0f, overlay);
				}
				for (int i = 0; i < 7; i++) {
					float tw = (float) Math.pow(Math.max(0, Math.sin(time * 2.7 + i * 1.9)), 6);
					if (tw < 0.05f) continue;
					float x = b[0] + hash(i, 1) * (b[3] - b[0]), y = b[1] + hash(i, 2) * (b[4] - b[1]);
					float z = i % 2 == 0 ? b[5] + 0.004f : b[2] - 0.004f;
					crystal(buffer, p, x, y, z, 0.011f * tw, time * 4, 1, 1, 1, overlay);
				}
			}
			case "nature" -> {
				float[] b = box(mesh, "moss", "leaves");
				int n = 12;
				for (int i = 0; i < n; i++) {
					float f = time * 0.22f + i / (float) n;
					int cycle = (int) Math.floor(f);
					float ph = f - cycle;
					float x = b[0] + hash(i, cycle) * (b[3] - b[0]) + 0.03f * (float) Math.sin(time * 1.8 + i);
					float y = b[1] + hash(i, cycle + 99) * (b[4] - b[1]) + ph * 0.28f;
					float z = b[2] + hash(i, cycle + 7) * (b[5] - b[2]) + 0.02f * (float) Math.cos(time * 1.3 + i);
					float size = 0.017f * (float) Math.sin(ph * Math.PI);
					crystal(buffer, p, x, y, z, size, time + i, 0.78f, 1.0f, 0.45f, overlay);
				}
			}
			case "lava" -> {
				float[] b = box(mesh, "blade", "crack");
				int n = 14;
				for (int i = 0; i < n; i++) {
					float f = time * 0.5f + i / (float) n;
					int cycle = (int) Math.floor(f);
					float ph = f - cycle;
					float x = b[0] + hash(i, cycle) * (b[3] - b[0]) + 0.05f * ph * (float) Math.sin(time * 3 + i);
					float y = b[1] + hash(i, cycle + 31) * (b[4] - b[1]) + ph * 0.32f;
					float z = 0.5f + (hash(i, cycle + 5) - 0.5f) * 0.12f;
					float size = 0.014f * (1 - ph * 0.75f);
					float r = 1, g = 0.85f - ph * 0.6f, bl = 0.35f - ph * 0.3f;
					crystal(buffer, p, x, y, z, size, time * 2 + i, r, g, bl, overlay);
				}
			}
			default -> { }
		}
	}

	/** A small glowing octahedron, turned by {@code spin}. */
	private static void crystal(VertexConsumer buffer, PoseStack.Pose p, float x, float y, float z, float size, float spin,
			float r, float g, float b, int overlay) {
		if (size <= 0.0005f) return;
		float c = (float) Math.cos(spin), s = (float) Math.sin(spin);
		float[][] pts = {{size * c, 0, size * s}, {-size * s, 0, size * c}, {-size * c, 0, -size * s}, {size * s, 0, -size * c},
				{0, size * 1.6f, 0}, {0, -size * 1.6f, 0}};
		int[][] faces = {{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}, {1, 0, 5}, {2, 1, 5}, {3, 2, 5}, {0, 3, 5}};
		for (int[] f : faces) {
			float ax = pts[f[1]][0] - pts[f[0]][0], ay = pts[f[1]][1] - pts[f[0]][1], az = pts[f[1]][2] - pts[f[0]][2];
			float bx = pts[f[2]][0] - pts[f[0]][0], by = pts[f[2]][1] - pts[f[0]][1], bz = pts[f[2]][2] - pts[f[0]][2];
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len == 0) continue;
			nx /= len;
			ny /= len;
			nz /= len;
			for (int side = 0; side < 2; side++) {
				for (int k = 0; k < 4; k++) {
					int v = f[side == 0 ? Math.min(k, 2) : 2 - Math.min(k, 2)];
					float n = side == 0 ? 1 : -1;
					vertex(buffer, p, x + pts[v][0], y + pts[v][1], z + pts[v][2], nx * n, ny * n, nz * n, r, g, b, LightCoordsUtil.FULL_BRIGHT, overlay);
				}
			}
		}
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z, float nx, float ny, float nz,
			float r, float g, float b, int light, int overlay) {
		buffer.addVertex(pose, x, y, z)
				.setColor(clamp(r), clamp(g), clamp(b), 255)
				.setUv(0.5f, 0.5f)
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(pose, nx, ny, nz);
	}

	private static int clamp(float c) {
		return Math.max(0, Math.min(255, Math.round(c * 255)));
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		Mesh mesh = mesh(name);
		Vector3f a = mesh.min(), b = mesh.max();
		for (int i = 0; i < 8; i++) {
			output.accept(new Vector3f((i & 1) == 0 ? a.x : b.x, (i & 2) == 0 ? a.y : b.y, (i & 4) == 0 ? a.z : b.z));
		}
	}
}
