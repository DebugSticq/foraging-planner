package dev.foragingplanner.mc;

import dev.foragingplanner.core.*;
import java.util.*;

public final class TargetProgress {
    public static double fraction(Journal.Target t) {
        if (t.metric.equals("perk")) {
            return Math.clamp(Planner.config.perkLevels.getOrDefault(t.perk, 0) / (double) Math.max(1, t.level), 0, 1);
        }
        return Math.clamp(JournalStore.journal.progress(t) / Math.max(1, t.amount), 0, 1);
    }
    public static boolean done(Journal.Target t) { return fraction(t) >= 1; }
    public static String detail(Journal.Target t) {
        if (t.metric.equals("perk")) {
            var path = HotfPlanner.unlockPath(Planner.data, Planner.config.perkLevels, t.perk, t.level, Planner.config.hotfTier);
            if (done(t)) return Planner.tr("journal.complete");
            if (path.isEmpty()) return Planner.tr("journal.path_unknown");
            long forest = 0, desert = 0; int tokens = 0;
            for (var step : path) {
                if (step.fromLevel() == 0) tokens++;
                if (step.perk().currency.equals("desert")) desert += step.cost(); else forest += step.cost();
            }
            return Planner.whole(forest) + " F / " + Planner.whole(desert) + " D / " + tokens + " Tokens";
        }
        double hours = JournalStore.journal.etaHours(t);
        return Planner.whole(JournalStore.journal.progress(t)) + " / " + Planner.whole(t.amount)
                + " · " + (Double.isFinite(hours) ? Planner.tr("journal.eta", Planner.decimal(hours)) : Planner.tr("journal.no_eta"));
    }
    public static void add(String metric, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || JournalStore.journal.targets.size() >= 12) return;
        var target = new Journal.Target(); target.metric = metric; target.amount = amount;
        target.name = Planner.tr("journal." + metric) + " +" + Planner.whole(amount);
        target.baseline = JournalStore.journal.totals.getOrDefault(metric, 0.0);
        JournalStore.journal.targets.add(target); JournalStore.save();
    }
    public static void perk(GameData.Perk perk, int level) {
        if (JournalStore.journal.targets.size() >= 12) return;
        var t = new Journal.Target(); t.metric = "perk"; t.perk = perk.name; t.level = level; t.name = perk.name + " " + level;
        JournalStore.journal.targets.add(t); JournalStore.save();
    }
    public static boolean shard(String name, int amount) {
        String id = Hunting.shardId(name);
        if (name.isBlank() || id.equals("SHARD_") || amount <= 0 || JournalStore.journal.targets.size() >= 12) return false;
        var t = new Journal.Target(); t.metric = "shard:" + id; t.amount = amount;
        t.name = name.trim().replaceAll("(?i)\\s+shards?$", "") + " +" + Planner.whole(amount);
        t.baseline = JournalStore.journal.totals.getOrDefault(t.metric, 0.0);
        JournalStore.journal.targets.add(t); JournalStore.save(); return true;
    }
    private TargetProgress() {}
}
