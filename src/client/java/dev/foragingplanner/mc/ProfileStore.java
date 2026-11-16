package dev.foragingplanner.mc;

import com.google.gson.*;
import dev.foragingplanner.core.ObservationParser;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Profile data is scoped by account UUID + server profile name. Unknown sessions use legacy storage. */
public final class ProfileStore {
    private static String key = "", label = "Local";
    private static Object connection;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Path directory() { return key.isEmpty() ? ModConfig.directory() : ModConfig.directory().resolve("profiles").resolve(key); }
    public static String label() { return label; }
    public static void observe(String line) {
        var match = ObservationParser.PROFILE.matcher(line.trim());
        if (match.matches()) select(match.group(1));
    }
    public static void tick(Minecraft client) {
        if (client.getConnection() != connection) {
            if (connection != null) {
                SessionRates.flush(); JournalStore.save(); Planner.config.save();
                if (client.getConnection() == null) {
                    key = ""; label = "Local";
                    Planner.config = ModConfig.load(); Planner.data = DataLoader.load();
                    LearnedSweep.load(); HotfMenuReader.applyRequirements();
                    JournalStore.load(); SessionRates.clearBaselines(); Planner.clearMeasurements();
                }
            }
            connection = client.getConnection();
        }
        for (String line : SkyBlockReader.tab()) observe(line);
    }
    public static void select(String profile) {
        if (!profile.matches("[A-Za-z][A-Za-z0-9 _-]{0,31}")) return;
        String account = Minecraft.getInstance().getUser().getProfileId().toString();
        String next = UUID.nameUUIDFromBytes((account + ":" + profile.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString();
        if (next.equals(key)) return;
        SessionRates.flush(); JournalStore.save();
        Planner.config.save();
        boolean migrateLegacy = key.isEmpty() && !Files.exists(ModConfig.directory().resolve("profiles"));
        Path previous = directory();
        key = next;
        if (migrateLegacy) {
            try {
                Files.createDirectories(directory());
                for (String filename : List.of("config.json", "journal.json", "sweep-measurements.json")) {
                    if (Files.exists(previous.resolve(filename))) Files.copy(previous.resolve(filename), directory().resolve(filename));
                }
            } catch (Exception e) { Planner.LOGGER.warn("Could not migrate legacy profile", e); }
        }
         label = profile;
        Planner.config = ModConfig.load();
        Planner.data = DataLoader.load();
        LearnedSweep.load();
        HotfMenuReader.applyRequirements();
        SessionRates.clearBaselines();
        JournalStore.load();
        Planner.clearMeasurements();
        PlannerHud.rebuild();
        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve("profile.json"), GSON.toJson(Map.of("name", profile, "account", account)));
        } catch (Exception e) { Planner.LOGGER.warn("Could not save profile name", e); }
    }
    private ProfileStore() {}
}
