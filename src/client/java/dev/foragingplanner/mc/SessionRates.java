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
    public static void clearBaselines() { previousXp = previousCap = -1; lastPercent = null; lastXp = pendingSince = 0; INVENTORY.clear(); DELTAS.clear(); TRANSFERS.clear(); REWARDS.clear(); world = null; LootTracker.clear(); }
    public static boolean recentActivity() { return System.currentTimeMillis() - lastXp < 12_000; }
    /** True while the player is clearly foraging, even if the XP line in the action bar is hidden by another mod. */
    static boolean foraging() { return recentActivity() || ContestTracker.active() || TreeProgress.recent(); }
    /**
     * Inventory changes that happened while a menu was open (or while not foraging), by item: moving
     * logs between inventory and sacks shows up in both places and must not count as income.
     */
    private static final Map<String, Deque<long[]>> TRANSFERS = new HashMap<>();
    /** When a container menu (sacks, storage, Bazaar, NPC) was last open. */
    private static long lastContainerMs;

    private static void transfer(String id, long delta, long now) {
        if (delta == 0) return;
        Deque<long[]> list = TRANSFERS.computeIfAbsent(id, k -> new ArrayDeque<>());
        list.addLast(new long[] {now, delta});
        while (!list.isEmpty() && now - list.peekFirst()[0] > 120_000) list.removeFirst();
    }

    /** Takes up to {@code wanted} of the recent transfers of {@code id} that point in the given direction. */
    private static long consumeTransfer(String id, long wanted, boolean outOfInventory, long sinceMs) {
        Deque<long[]> list = TRANSFERS.get(id);
        if (list == null || wanted <= 0) return 0;
        long taken = 0;
        for (long[] t : list) {
            if (t[0] < sinceMs || taken >= wanted) continue;
            boolean out = t[1] < 0;
            if (out != outOfInventory) continue;
            long use = Math.min(wanted - taken, Math.abs(t[1]));
            taken += use;
            t[1] += out ? use : -use;
        }
        list.removeIf(t -> t[1] == 0);
        return taken;
    }

    /** True if the player was foraging during a sack message's window (it arrives up to that many seconds later). */
    private static boolean activeDuring(int windowSeconds) {
        long now = System.currentTimeMillis();
        return ContestTracker.active() || now - lastXp < (windowSeconds + 15) * 1000L || TreeProgress.recent();
    }

    /**
     * One item of a "[Sacks]" message. Logs taken from or put into the sacks through a menu are
     * matched against the inventory change and ignored; what is left came straight from chopping.
     */
    public static void sack(String id, long delta, int window) {
        // A sack message right after a contest still holds logs cut during it.
        if (ContestTracker.inGrace()) ContestTracker.addLate(id, delta, window);
        if (delta == 0) return;
        long now = System.currentTimeMillis();
        long since = now - (window + 5) * 1000L;
        long real;
        if (delta > 0) {
            // Logs put into the sacks from the inventory are no new logs.
            real = delta - consumeTransfer(id, delta, true, since);
        } else {
            // Logs taken out of the sacks into the inventory: the inventory already saw them, nothing to do.
            consumeTransfer(id, -delta, false, since);
            return;
        }
        if (real <= 0 || !activeDuring(window)) return;
        // During a contest the sack message is the final word, so count it at once instead of after the usual delay.
        if (ContestTracker.active()) {
            JournalStore.journal.add("logs", real, now);
            JournalStore.journal.item(id, real, now);
        } else {
            addDelta(id, real);
        }
    }

    /** Gift rewards already counted from the gift message; they also show up in the next sack message. */
    private static final Map<String, Deque<long[]>> REWARDS = new HashMap<>();

    public static void rewarded(String id, long count) {
        if (count <= 0) return;
        long now = System.currentTimeMillis();
        Deque<long[]> list = REWARDS.computeIfAbsent(id, k -> new ArrayDeque<>());
        list.addLast(new long[] {now, count});
        while (!list.isEmpty() && now - list.peekFirst()[0] > 120_000) list.removeFirst();
    }

    private static long consumeReward(String id, long wanted, long sinceMs) {
        Deque<long[]> list = REWARDS.get(id);
        if (list == null) return 0;
        long taken = 0;
        for (long[] r : list) {
            if (r[0] < sinceMs || taken >= wanted) continue;
            long use = Math.min(wanted - taken, r[1]);
            taken += use;
            r[1] -= use;
        }
        list.removeIf(r -> r[1] <= 0);
        return taken;
    }

    /** Anything else from a sack message on the foraging islands (sticks, saplings, drops): counted for profit, not as logs. */
    public static void sackOther(String id, long delta, int window) {
        long now = System.currentTimeMillis();
        if (delta <= 0 || !activeDuring(window)) return;
        // Moving items around in a menu during the window makes the message unreliable for non-log items.
        if (now - lastContainerMs < (window + 5) * 1000L) return;
        // Gift rewards were counted when the gift came; only the rest is new.
        long real = delta - consumeReward(id, delta, now - (window + 15) * 1000L);
        if (real > 0) JournalStore.journal.item(id, real, now);
    }

    /** Items a shortened sack message left out ("and 3 other items"), as an estimate for the card. */
    public static void sackHidden(long count) {
        if (count > 0) JournalStore.journal.add("sack_hidden", count, System.currentTimeMillis());
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
        var screen = client.gui.screen();
        boolean menu = screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen);
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) lastContainerMs = System.currentTimeMillis();
        if (!INVENTORY.isEmpty()) {
            long now = System.currentTimeMillis();
            boolean counting = !menu && foraging();
            current.forEach((id, count) -> {
                int change = count - INVENTORY.getOrDefault(id, 0);
                // Changes during a menu or away from the trees are moves (sacks, storage, selling), remembered for sack messages.
                if (counting) addDelta(id, change);
                else transfer(id, change, now);
            });
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
