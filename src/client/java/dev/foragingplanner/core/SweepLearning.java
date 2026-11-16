package dev.foragingplanner.core;

import java.util.*;

/** Observations bound thresholds, rather than pretending every observed Sweep is an exact threshold. */
public final class SweepLearning {
    public record Sample(double sweep, double logs, double toughness) {}
    public Map<String, List<Sample>> samples = new LinkedHashMap<>();

    public boolean record(String tree, Sample sample) {
        if (!Double.isFinite(sample.sweep()) || !Double.isFinite(sample.logs())
                || !Double.isFinite(sample.toughness()) || sample.sweep() < 0 || sample.logs() < 0
                || sample.logs() > 1000 || sample.toughness() <= 0) return false;
        List<Sample> observations = samples.computeIfAbsent(tree, key -> new ArrayList<>());
        if (observations.contains(sample)) return false;
        // A different toughness is a different cutting context. Replace that context's model.
        if (observations.stream().anyMatch(old -> old.toughness() != sample.toughness()
                || old.sweep() < sample.sweep() && Math.floor(old.logs()) > Math.floor(sample.logs())
                || old.sweep() > sample.sweep() && Math.floor(old.logs()) < Math.floor(sample.logs()))) {
            observations.clear();
        }
        observations.removeIf(old -> old.sweep() == sample.sweep());
        observations.add(sample);
        if (observations.size() > 256) observations.removeFirst();
        return true;
    }

    public List<Double> corrected(String tree, List<Double> defaults) {
        List<Sample> observations = samples.getOrDefault(tree, List.of());
        List<Double> corrected = new ArrayList<>(defaults);
        double previous = 0;
        for (int index = 0; index < corrected.size(); index++) {
            double low = 0;
            double high = Double.POSITIVE_INFINITY;
            for (Sample sample : observations) {
                if (Math.floor(sample.logs()) >= index + 1) high = Math.min(high, sample.sweep());
                else low = Math.max(low, Math.nextUp(sample.sweep()));
            }
            double value = Math.max(previous, Math.max(low, Math.min(high, corrected.get(index))));
            corrected.set(index, value);
            previous = value;
        }
        return corrected;
    }
}
