package dev.foragingplanner.mc;

import java.util.*;
import java.util.regex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import dev.foragingplanner.core.ContestMath;

/** Cumulative skill progress plus reconciled inventory and sack changes. */
public final class SessionRates {
    /** "+6.3 Foraging (24/750)", and at the level cap "+6.3 Foraging (5,183,244/0)". */
    private static final Pattern XP_FRACTION = Pattern.compile("(?:\\+([\\d,.]+) )?Foraging \\(([\\d,.]+[kmb]?)/([\\d,.]+[kmb]?)\\)", Pattern.CASE_INSENSITIVE);
    /** "+6.3 Foraging (48.39%)", shown when the Hypixel setting for skill progress is set to percent. */
    private static final Pattern XP_PERCENT = Pattern.compile("\\+([\\d,.]+) Foraging \\(([\\d.,]+)%\\)", Pattern.CASE_INSENSITIVE);
    private static String lastPercent;
    private static final Map<String, Integer> INVENTORY = new HashMap<>();
    private static final Map<String, Long> DELTAS = new HashMap<>();
    private static double previousXp = -1, previousCap = -1;
    private static long lastXp, pendingSince;
    private static Object world;
    public static double xpRemaining() { return previousXp < 0 ? -1 : Math.max(0, previousCap - previousXp); }
    public static void register() {
        ServerMessages.register((message, overlay) -> {
            if (overlay && SkyBlockReader.inForagingArea()) readActionBar(Planner.strip(message.getString()));
        });
    }

    /**
     * The action bar repeats the same skill text several times a second, so gains are taken from the
     * change in progress, never by adding up every "+X" that is shown.
     */
    static void readActionBar(String text) {
        long now = System.currentTimeMillis();
        Matcher fraction = XP_FRACTION.matcher(text);

        if (fraction.find()) {
            double gained = fraction.group(1) == null ? -1 : ContestMath.parseNumber(fraction.group(1));
            double current = ContestMath.parseNumber(fraction.group(2));
            double cap = ContestMath.parseNumber(fraction.group(3));
            if (current < 0) return;

            if (previousXp >= 0) {
                double change = current - previousXp;
                // Progress only falls when a level was reached; then the shown gain is the best known amount.
                if (change > 0) credit(change, now);
                else if (change < 0 && gained > 0) credit(gained, now);
            }

            previousXp = current;
            previousCap = cap;
            lastPercent = null;
            return;
        }

        Matcher percent = XP_PERCENT.matcher(text);
        if (!percent.find()) return;

        String progress = percent.group(2);
        double gained = ContestMath.parseNumber(percent.group(1));
        if (lastPercent != null && !progress.equals(lastPercent) && gained > 0) credit(gained, now);

        lastPercent = progress;
        previousXp = previousCap = -1;
    }

    private static void credit(double xp, long now) {
        JournalStore.journal.add("xp", xp, now);
        Combo.hit();
        TreeProgress.hit();
        lastXp = now;
    }

    public static void reset() { flush(); JournalStore.journal.archive(); clearBaselines(); JournalStore.save(); }
    public static void clearBaselines() { previousXp = previousCap = -1; lastPercent = null; lastXp = pendingSince = 0; INVENTORY.clear(); DELTAS.clear(); world = null; LootTracker.clear(); }
    public static boolean recentActivity() { return System.currentTimeMillis() - lastXp < 12_000; }
    /** True while the player is clearly foraging, even if the XP line in the action bar is hidden by another mod. */
    static boolean foraging() { return recentActivity() || ContestTracker.active() || TreeProgress.recent(); }
    public static void sack(String id, long delta, int window) {
        // A sack message right after a contest still holds logs cut during it.
        if (ContestTracker.inGrace()) ContestTracker.addLate(id, delta, window);
        if (!foraging() || Minecraft.getInstance().gui.screen() != null) return;
        // During a contest the sack message is the final word, so count it at once instead of after the usual delay.
        if (ContestTracker.active() && delta > 0) {
            long now = System.currentTimeMillis();
            JournalStore.journal.add("logs", delta, now);
            JournalStore.journal.item(id, delta, now);
        } else {
            addDelta(id, delta);
        }
    }
    private static void addDelta(String id, long delta) {
        if (delta == 0) return;
        if (pendingSince == 0) pendingSince = System.currentTimeMillis();
        DELTAS.merge(id, delta, Long::sum);
    }
    public static void flush() {
        long now = System.currentTimeMillis();
        DELTAS.forEach((id, amount) -> {
            if (amount <= 0) return;
            JournalStore.journal.add("logs", amount, now);
            JournalStore.journal.item(id, amount, now);
        });
        DELTAS.clear(); pendingSince = 0;
    }
    public static void tick(Minecraft client) {
        if (client.level != world || !SkyBlockReader.inForagingArea()) {
            flush(); clearBaselines(); world = client.level;
        }
        if (client.player == null || !SkyBlockReader.inForagingArea()) return;
        Map<String, Integer> current = new HashMap<>();
        for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
            var stack = client.player.getInventory().getItem(slot);
            String id = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("id", "");
            for (var tree : Planner.data.trees) if (tree.bazaarId.equals(id)) current.merge(id, stack.getCount(), Integer::sum);
        }
        for (var tree : Planner.data.trees) current.putIfAbsent(tree.bazaarId, 0);
        if (!INVENTORY.isEmpty() && client.gui.screen() == null && foraging()) {
            current.forEach((id, count) -> addDelta(id, count - INVENTORY.getOrDefault(id, 0)));
        }
        INVENTORY.clear(); INVENTORY.putAll(current);
        if (pendingSince > 0 && System.currentTimeMillis() - pendingSince >= 12_000) flush();
    }
    /** Everything the session card shows, computed once per second. */
    public record Snapshot(boolean waiting, long elapsedMs, double xpPerHour, double coinsPerHour, double profit,
                           boolean unpriced, double essence, double essencePerHour, double essenceCoins, double gifts,
                           double hotf, double hotfPerHour, double hotfFromContests, double contests) {}

    public static Snapshot snapshot() {
        var session = JournalStore.journal.current;
        var valuation = JournalStore.value(session.items);
        long elapsed = session.elapsedMs;
        double hours = elapsed / 3_600_000.0;
        double essence = session.value("essence");
        Double essencePrice = Planner.PRICES.get("ESSENCE_FOREST");

        return new Snapshot(elapsed < 20_000, elapsed, session.rate("xp"),
                hours <= 0 ? 0 : valuation.coins() / hours, valuation.coins(), valuation.unpriced() > 0,
                essence, session.rate("essence"), essencePrice == null ? 0 : essence * essencePrice, session.value("gifts"),
                session.value("hotf"), session.rate("hotf"), session.value("hotf_contest"), session.value("contests"));
    }

    public static String line() {
        Snapshot now = snapshot();
        if (now.waiting()) return Planner.tr("rates.waiting");
        return Planner.tr("rates.line", Planner.whole(now.xpPerHour()), Planner.whole(now.coinsPerHour()) + (now.unpriced() ? "+?" : ""))
                + " " + Planner.tr("rates.essence", Planner.whole(now.essence()));
    }
    private SessionRates() {}
}
