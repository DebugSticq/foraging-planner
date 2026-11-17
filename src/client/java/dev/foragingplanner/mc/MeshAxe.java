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

	/** One mesh: colours per material and triangles with position, normal and the original height. */
	private record Mesh(float[] colors, int[] material, float[] data, Vector3f min, Vector3f max) {}

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
			int tris = in.readInt();
			int[] material = new int[tris];
			float[] data = new float[tris * 21];
			Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
			for (int t = 0; t < tris; t++) {
				material[t] = in.readInt();
				for (int k = 0; k < 21; k++) data[t * 21 + k] = in.readFloat();
				for (int v = 0; v < 3; v++) {
					int o = t * 21 + v * 7;
					min.min(new Vector3f(data[o], data[o + 1], data[o + 2]));
					max.max(new Vector3f(data[o], data[o + 1], data[o + 2]));
				}
			}
			return new Mesh(colors, material, data, min, max);
		} catch (Exception e) {
			Planner.LOGGER.error("Could not load axe mesh {}", name, e);
			return new Mesh(new float[0], new int[0], new float[0], new Vector3f(), new Vector3f(1));
		}
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean glint, int seed) {
		Mesh mesh = mesh(name);
		if (mesh.material().length == 0) return;
		float time = (System.currentTimeMillis() % 100_000L) / 1000f;
		float speed = name.equals("lava") ? 2.4f : name.equals("ice") ? 1.6f : 1.0f;
		collector.submitCustomGeometry(pose, RenderTypes.entitySolid(WHITE), (p, buffer) -> {
			float[] d = mesh.data();
			for (int t = 0; t < mesh.material().length; t++) {
				int m = mesh.material()[t] * 4;
				float r = mesh.colors()[m], g = mesh.colors()[m + 1], b = mesh.colors()[m + 2], glow = mesh.colors()[m + 3];
				int o = t * 21;
				// Front side, then the back side, so thin details stay visible from both sides.
				for (int side = 0; side < 2; side++) {
					for (int k = 0; k < 4; k++) {
						int v = side == 0 ? Math.min(k, 2) : 2 - Math.min(k, 2);
						int at = o + v * 7;
						float shine = 1;
						int vertexLight = light;
						if (glow > 0) {
							// A bright wave runs up the blade; hotter materials pulse harder.
							shine = 0.8f + 0.35f * (float) Math.sin(time * speed * 2.2f - d[at + 6] * 9f) * Math.min(1, glow * 1.4f);
							vertexLight = LightCoordsUtil.FULL_BRIGHT;
						}
						float n = side == 0 ? 1 : -1;
						vertex(buffer, p, d[at], d[at + 1], d[at + 2], d[at + 3] * n, d[at + 4] * n, d[at + 5] * n,
								r * shine, g * shine, b * shine, vertexLight, overlay);
					}
				}
			}
		});
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
