package dev.foragingplanner.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.foragingplanner.core.SweepLearning;
import java.nio.file.*;
import java.util.*;

public final class LearnedSweep {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SweepLearning learning = new SweepLearning();
    private static final Map<String, List<Double>> DEFAULTS = new HashMap<>();
    private LearnedSweep() {}

    public static void load() {
        learning = new SweepLearning();
        try {
            Path file = ModConfig.profileDirectory().resolve("sweep-measurements.json");
            if (Files.exists(file)) {
                SweepLearning loaded = GSON.fromJson(Files.readString(file), SweepLearning.class);
                if (loaded != null && loaded.samples != null) {
                    SweepLearning validated = new SweepLearning();
                    loaded.samples.forEach((tree, samples) -> {
                        if (samples != null) for (var sample : samples) if (sample != null) validated.record(tree, sample);
                    });
                    learning = validated;
                }
            }
        } catch (Exception e) { Planner.LOGGER.warn("Could not load Sweep measurements", e); }
        captureDefaults();
    }

    public static void captureDefaults() {
        DEFAULTS.clear();
        for (var tree : Planner.data.trees) DEFAULTS.put(tree.id, List.copyOf(tree.sweepBreakpoints));
        apply();
    }

    public static void apply() {
        for (var tree : Planner.data.trees) {
            List<Double> defaults = DEFAULTS.get(tree.id);
            if (defaults != null) tree.sweepBreakpoints = Planner.config.learnSweep
                    ? learning.corrected(tree.id, defaults) : new ArrayList<>(defaults);
        }
    }

    public static void record(String tree, double sweep, double logs, double toughness) {
        if (!Planner.config.learnSweep || !learning.record(tree, new SweepLearning.Sample(sweep, logs, toughness))) return;
        apply();
        try {
            Files.createDirectories(ModConfig.profileDirectory());
            Path temporary = ModConfig.profileDirectory().resolve("sweep-measurements.json.tmp");
            Files.writeString(temporary, GSON.toJson(learning));
            Files.move(temporary, ModConfig.profileDirectory().resolve("sweep-measurements.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { Planner.LOGGER.warn("Could not save Sweep measurements", e); }
    }
}
