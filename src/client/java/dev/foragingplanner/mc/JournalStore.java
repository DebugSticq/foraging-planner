package dev.foragingplanner.mc;

import com.google.gson.*;
import dev.foragingplanner.core.Journal;
import java.nio.file.*;
import java.util.*;

public final class JournalStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Journal journal = new Journal();
    private static long lastSave, lastTick;
    public static boolean sackIncomplete;
    public static void load() {
        journal = new Journal();
        try {
            Path file = ModConfig.profileDirectory().resolve("journal.json");
            if (Files.exists(file)) {
                Journal read = GSON.fromJson(Files.readString(file), Journal.class);
                if (read != null && read.current != null && read.history != null && read.totals != null
                        && read.gifts != null && read.loot != null && read.targets != null) journal = read;
            }
        } catch (Exception e) { Planner.LOGGER.warn("Could not load journal", e); }
        if (journal.hunting == null) journal.hunting = new dev.foragingplanner.core.Hunting();
        if (journal.hunting.shards == null) journal.hunting.shards = new LinkedHashMap<>();
        if (journal.hunting.names == null) journal.hunting.names = new LinkedHashMap<>();
        if (journal.current.shards == null) journal.current.shards = new LinkedHashMap<>();
        for (var s : journal.history) if (s.shards == null) s.shards = new LinkedHashMap<>();
        HuntingTracker.reset();
        journal.rollDay(System.currentTimeMillis());
        lastTick = 0; sackIncomplete = false;
        Progress.load(); TreeTracker.reload(); KnownTrees.reload();
    }
    public static void save() {
        try {
            Files.createDirectories(ModConfig.profileDirectory());
            Path file = ModConfig.profileDirectory().resolve("journal.json"), temp = file.resolveSibling("journal.json.tmp");
            Files.writeString(temp, GSON.toJson(journal));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            lastSave = System.currentTimeMillis();
        } catch (Exception e) { Planner.LOGGER.warn("Could not save journal", e); }
    }
    public record Valuation(double coins, int unpriced) {}
    public static Valuation value(Map<String, Long> items) {
        double coins = 0; int unknown = 0;
        for (var item : items.entrySet()) {
            Double price = Planner.PRICES.get(item.getKey());
            if (price == null || !Double.isFinite(price) || price <= 0) unknown++;
            else coins += price * item.getValue();
        }
        return new Valuation(coins, unknown);
    }
    public static void tick(boolean active) {
        long now = System.currentTimeMillis();
        journal.tick(lastTick == 0 ? 0 : now - lastTick, active, now, value(journal.current.items).coins());
        lastTick = now;
        if (now - lastSave >= 30_000) save();
        for (var target : journal.targets) {
            if (!target.announced && TargetProgress.done(target)) {
                target.announced = true;
                if (journal.reminders) notify(Planner.tr("journal.target_done", target.name));
            }
        }
    }
    public static void notify(String text) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a[FP] §f" + text));
    }
    public static Path export() throws java.io.IOException {
        Path path = ModConfig.profileDirectory().resolve("sessions-" + System.currentTimeMillis() + ".csv");
        StringBuilder csv = new StringBuilder("started_utc,seconds,xp,logs,forest_whispers,desert_whispers,gifts,estimated_coins,unpriced_items,hunting_seconds,shards,estimated_shard_coins,unpriced_shards\n");
        List<Journal.Session> sessions = new ArrayList<>(); sessions.add(journal.current); sessions.addAll(journal.history);
        for (var s : sessions) {
            var value = value(s.items);
            csv.append(java.time.Instant.ofEpochMilli(s.started)).append(',').append(s.elapsedMs / 1000)
                    .append(',').append(s.value("xp")).append(',').append(s.value("logs"))
                    .append(',').append(s.value("forest")).append(',').append(s.value("desert"))
                    .append(',').append(s.value("gifts")).append(',').append(value.coins()).append(',').append(value.unpriced())
                    .append(',').append(s.huntingElapsedMs / 1000).append(',').append(s.value("shards"))
                    .append(',').append(value(s.shards).coins()).append(',').append(value(s.shards).unpriced()).append('\n');
        }
        Files.createDirectories(path.getParent()); Files.writeString(path, csv.toString()); return path;
    }
    private JournalStore() {}
}
