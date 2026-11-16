package dev.foragingplanner.core;

import java.time.*;
import java.util.*;

/** Profile-local observed progress. No Minecraft dependencies; all times are injected. */
public final class Journal {
    /** Told about every amount added, for daily goals. Not saved. */
    public static transient java.util.function.BiConsumer<String, Double> listener;
    public Session current = new Session();
    public List<Session> history = new ArrayList<>();
    public Map<String, Double> totals = new LinkedHashMap<>();
    public Map<String, Long> gifts = new LinkedHashMap<>();
    public Map<String, Long> loot = new LinkedHashMap<>();
    public Hunting hunting = new Hunting();
    public List<Target> targets = new ArrayList<>();
    public String day = "";
    public int dailyGifts, dailyContests;
    public boolean dailyWish, reminders = false;
    public int resetHourUtc = 0;
    public static final class Session {
        public long started, elapsedMs, huntingElapsedMs;
        public boolean paused;
        public Map<String, Double> amounts = new LinkedHashMap<>();
        public Map<String, Long> items = new LinkedHashMap<>();
        public Map<String, Long> shards = new LinkedHashMap<>();
        public List<Sample> samples = new ArrayList<>();
        public double value(String key) { return amounts.getOrDefault(key, 0.0); }
        public double rate(String key) {
            long time = key.equals("shards") || key.startsWith("shard:") ? huntingElapsedMs : elapsedMs;
            return time < 20_000 ? 0 : value(key) * 3_600_000 / time;
        }
    }
    public record Sample(long elapsedMs, double xp, double logs, double forest, double desert, double coins) {}
    public static final class Target {
        public String name = "", metric = "xp", perk = "";
        public double amount = 100000, baseline;
        public int level = 1;
        public boolean announced;
    }
    public void rollDay(long now) {
        String today = Instant.ofEpochMilli(now).minusSeconds(Math.clamp(resetHourUtc, 0, 23) * 3600L)
                .atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (!today.equals(day)) { day = today; dailyGifts = dailyContests = 0; dailyWish = false; }
    }
    public void add(String metric, double amount, long now) {
        if (!Double.isFinite(amount) || amount <= 0) return;
        rollDay(now);
        totals.merge(metric, amount, Double::sum);
        if (listener != null) listener.accept(metric, amount);
        if (!current.paused) {
            if (current.started == 0) current.started = now;
            current.amounts.merge(metric, amount, Double::sum);
        }
    }
    public void item(String id, long amount, long now) {
        if (id == null || id.isBlank() || amount <= 0) return;
        if (!current.paused) {
            if (current.started == 0) current.started = now;
            current.items.merge(id, amount, Long::sum);
        }
    }
    public void gift(String tree, long now) {
        rollDay(now);
        gifts.merge(tree, 1L, Long::sum);
        dailyGifts++;
        add("gifts", 1, now);
    }
    public void caught(Hunting.Catch caught, long now) {
        hunting.names.put(caught.id(), caught.name());
        hunting.shards.merge(caught.id(), (long)caught.amount(), Long::sum);
        add("shards", caught.amount(), now);
        add("shard:" + caught.id(), caught.amount(), now);
        if (!current.paused) current.shards.merge(caught.id(), (long)caught.amount(), Long::sum);
    }
    public void tickHunting(long elapsed, boolean active, long now) {
        if (active && !current.paused) {
            if (current.started == 0) current.started = now;
            current.huntingElapsedMs += Math.clamp(elapsed, 0, 250);
        }
    }
    public void tick(long elapsedMs, boolean active, long now, double coins) {
        rollDay(now);
        if (active && !current.paused && current.started != 0) current.elapsedMs += Math.clamp(elapsedMs, 0, 5000);
        if (current.elapsedMs > 0 && (current.samples.isEmpty()
                || current.elapsedMs - current.samples.getLast().elapsedMs() >= 30_000)) {
            current.samples.add(new Sample(current.elapsedMs, current.value("xp"), current.value("logs"),
                    current.value("forest"), current.value("desert"), coins));
            if (current.samples.size() > 480) current.samples.removeFirst();
        }
    }
    public void archive() {
        if (current.started != 0) { history.addFirst(current); if (history.size() > 30) history.removeLast(); }
        current = new Session();
    }
    public double progress(Target target) {
        return Math.max(0, totals.getOrDefault(target.metric, 0.0) - target.baseline);
    }
    public double etaHours(Target target) {
        double left = Math.max(0, target.amount - progress(target));
        double rate = current.rate(target.metric);
        return left == 0 ? 0 : rate > 0 ? left / rate : Double.POSITIVE_INFINITY;
    }
}
