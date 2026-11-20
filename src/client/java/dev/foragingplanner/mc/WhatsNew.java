package dev.foragingplanner.mc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** "What's new" after an update: a card and a clickable chat line once, the full list on /fp news. */
public final class WhatsNew {
	/** One new thing; {@code open} is "setting:name", "stats:page", "gear:tab", "check", "wizard" or "rare". */
	public record Entry(String icon, String text, String open) {}

	private static Map<String, List<Entry>> cache;
	private static long inSkyBlockSince;

	private WhatsNew() {}

	/** Versions, newest first. */
	public static Map<String, List<Entry>> all() {
		if (cache != null) return cache;
		Map<String, List<Entry>> out = new LinkedHashMap<>();
		boolean german = Planner.tr("whatsnew.lang").equals("de");
		try (InputStream in = WhatsNew.class.getResourceAsStream("/foragingplanner/whatsnew.json")) {
			if (in != null) {
				JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> version : root.entrySet()) {
					List<Entry> list = new ArrayList<>();
					for (JsonElement el : version.getValue().getAsJsonArray()) {
						JsonObject o = el.getAsJsonObject();
						String text = o.has(german ? "de" : "en") ? o.get(german ? "de" : "en").getAsString() : o.get("en").getAsString();
						list.add(new Entry(o.has("icon") ? o.get("icon").getAsString() : "paper", text, o.has("open") ? o.get("open").getAsString() : ""));
					}
					out.put(version.getKey(), list);
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read whatsnew.json", e);
		}
		cache = out;
		return out;
	}

	/** Called once a second: after an update, tells the player once. */
	public static void tick(Minecraft client) {
		ModConfig c = Planner.config;
		String version = ModVersion.plain();
		if (version.equals(c.lastSeenVersion) || version.equals("dev") || client.player == null) return;
		if (!SkyBlockReader.inSkyBlock()) {
			inSkyBlockSince = 0;
			return;
		}
		if (inSkyBlockSince == 0) inSkyBlockSince = System.currentTimeMillis();
		if (System.currentTimeMillis() - inSkyBlockSince < 8_000) return;

		boolean freshInstall = c.lastSeenVersion.isEmpty() && !c.setupDone;
		c.lastSeenVersion = version;
		c.save();
		if (freshInstall || !all().containsKey(version)) return;

		int count = all().get(version).size();
		Toasts.hint("writable_book", Planner.tr("whatsnew.toast_title", version), Planner.tr("whatsnew.toast", count));
		Component link = Component.literal(" §a§n" + Planner.tr("whatsnew.show")).withStyle(st -> st.withClickEvent(new ClickEvent.RunCommand("/fp news")));
		client.player.sendSystemMessage(Component.literal("§a[FP] §7" + Planner.tr("whatsnew.chat", version, count)).append(link));
	}

	/** Runs an entry's link. */
	public static void open(Minecraft mc, net.minecraft.client.gui.screens.Screen from, String open) {
		if (open == null || open.isEmpty()) return;
		String[] parts = open.split(":", 2);
		switch (parts[0]) {
			case "setting" -> mc.gui.setScreen(SettingsScreen.showing(from, parts[1]));
			case "stats" -> mc.gui.setScreen(new StatsScreen(from, Integer.parseInt(parts[1])));
			case "gear" -> mc.gui.setScreen(new GearScreen(from, Integer.parseInt(parts[1])));
			case "check" -> mc.gui.setScreen(new CheckScreen(from));
			case "wizard" -> mc.gui.setScreen(new WizardScreen(from));
			case "rare" -> mc.gui.setScreen(PickerScreen.rare(from));
			default -> { }
		}
	}
}
