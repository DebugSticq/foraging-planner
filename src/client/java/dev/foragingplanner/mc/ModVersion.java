package dev.foragingplanner.mc;

import net.fabricmc.loader.api.FabricLoader;

/** The mod's own version, as written into fabric.mod.json by the build ("1.0.0+26.3"). */
public final class ModVersion {
	private static String version;

	private ModVersion() {}

	public static String get() {
		if (version == null) {
			try {
				version = FabricLoader.getInstance().getModContainer(Planner.MOD_ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			} catch (RuntimeException e) {
				version = "dev";
			}
		}
		return version;
	}

	/** Without the Minecraft part: "1.0.0". */
	public static String plain() {
		String v = get();
		int plus = v.indexOf('+');
		return plus > 0 ? v.substring(0, plus) : v;
	}
}
