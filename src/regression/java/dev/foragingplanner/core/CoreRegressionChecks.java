package dev.foragingplanner.core;

import java.util.*;

/** Dependency-free regression checks for the planner's resource accounting and learned thresholds. */
public final class CoreRegressionChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static GameData.Perk perk(String name, double sweep) {
        GameData.Perk perk = new GameData.Perk();
        perk.name = name;
        perk.maxLevel = 3;
        perk.costBase = 10.0;
        perk.unlockKnown = true;
        if (sweep > 0) perk.effects.put("sweep", sweep);
        return perk;
    }
    public static void main(String[] args) {
        GameData data = new GameData();
        GameData.Perk bridge = perk("Bridge", 0);
        GameData.Perk reward = perk("Reward", 10);
        reward.requires.put("Bridge", 1);
        data.hotf.perks.addAll(List.of(bridge, reward));
        Objective objective = new Objective(data, Goal.SWEEP, Map.of(), null);
        var plan = HotfPlanner.plan(data, Map.of(), PlayerStats.ZERO, objective, 15, 2, 1);
        check(plan.tokensUsed() == 2 && plan.totalCost() == 10 && plan.leftover() == 5, "Path resources");
        check(plan.steps().getFirst().perk() == bridge, "Prerequisite order");
        check(HotfPlanner.plan(data, Map.of(), PlayerStats.ZERO, objective, 1000, 1, 1).steps().isEmpty(), "No partial path with insufficient tokens");
        reward.requiredTier = 2;
        check(HotfPlanner.plan(data, Map.of(), PlayerStats.ZERO, objective, 1000, 3, 1).steps().isEmpty(), "Tier requirement");
        reward.requiredTier = 1;
        bridge.requires.put("Reward", 1);
        check(HotfPlanner.plan(data, Map.of(), PlayerStats.ZERO, objective, 1000, 3, 1).steps().isEmpty(), "Cycle rejected");
        bridge.requires.clear();
        var legacy = HotfPlanner.plan(data, Map.of("Reward", 1), new PlayerStats(10, 0, 0), objective, 10);
        check(legacy.totalCost() == 10 && legacy.tokensUsed() == 0, "Existing callers still upgrade unlocked perks");

        GameData.Perk strength = perk("Strength", 0);
        strength.effects.put("strength", 2.0);
        GameData.Perk conversion = perk("Conversion", 0);
        conversion.effects.put("strengthConversion", .001);
        data.hotf.perks.addAll(List.of(strength, conversion));
        PlayerStats observed = new PlayerStats(50, 100, 10, 1000, 400, 0, 0);
        PlayerStats after = HotfPlanner.advance(data, Map.of("Conversion", 50, "Strength", 1), observed, strength, 1);
        check(Math.abs(after.sweep() - 50.1) < 1e-8, "Strength upgrade conversion synergy");

        SweepLearning learning = new SweepLearning();
        check(!learning.record("fig", new SweepLearning.Sample(Double.NaN, 3, 7)), "Reject NaN");
        learning.record("fig", new SweepLearning.Sample(10, 2.8, 7));
        learning.record("fig", new SweepLearning.Sample(15, 3.4, 7));
        List<Double> corrected = learning.corrected("fig", List.of(7.0, 12.0, 20.0, 30.0));
        check(corrected.get(1) <= 10 && corrected.get(2) > 10 && corrected.get(2) <= 15, "Threshold bounds");
        check(!learning.record("fig", new SweepLearning.Sample(15, 3.4, 7)), "Duplicate observation");
        learning.record("fig", new SweepLearning.Sample(20, 1, 14));
        check(learning.samples.get("fig").size() == 1, "Toughness contexts stay separate");
        var tree = new GameData.Tree();
        tree.sweepBreakpoints = List.of(7.0, 7.0, 15.0);
        check(Double.isFinite(TreeMath.smoothLogsPerHit(tree, 7)), "Equal learned thresholds");
        
        // A desert purchase cannot consume forest currency, even when that wallet is full.
        reward.currency = "desert";
        var noDesert = HotfPlanner.plan(data, Map.of("Reward", 1), new PlayerStats(10, 0, 0), objective, 10000, 0, 0, 8);
        check(noDesert.steps().isEmpty(), "Desert and forest wallets are isolated");
        var both = HotfPlanner.plan(data, Map.of("Reward", 1), new PlayerStats(10, 0, 0), objective, 123, 15, 0, 8);
        check(both.desertCost() == 10 && both.forestCost() == 0 && both.forestLeft() == 123 && both.desertLeft() == 5, "Wallet accounting");
        var path = HotfPlanner.unlockPath(data, Map.of(), "Reward", 2, 8);
        check(path.size() == 2 && path.getFirst().perk() == bridge && path.getLast().toLevel() == 2, "Target upgrade path");
        check(HotfPlanner.unlockPath(data, Map.of(), "Missing", 2, 8).isEmpty(), "Unknown path");

        long now = java.time.Instant.parse("2026-11-15T23:59:50Z").toEpochMilli();
        Journal journal = new Journal();
        journal.add("xp", 100, now);
        journal.item("FIG_LOG", 20, now);
        for (int i = 1; i <= 20; i++) journal.tick(1000, true, now + i * 1000, 50);
        check(journal.current.rate("xp") == 18000, "Session rate uses observed elapsed time");
        journal.current.paused = true;
        journal.add("xp", 30, now + 21000);
        journal.tick(1000, true, now + 22000, 50);
        check(journal.current.value("xp") == 100 && journal.totals.get("xp") == 130, "Pause excludes session gains but retains goal progress");
        journal.current.paused = false;
        journal.tick(1000, false, now + 23000, 50);
        check(journal.current.elapsedMs == 20000, "Offline time is excluded");
        var target = new Journal.Target(); target.metric = "xp"; target.baseline = 100; target.amount = 60;
        check(journal.progress(target) == 30 && Double.isFinite(journal.etaHours(target)), "Target baseline and ETA");
        journal.gift("fig", now); journal.dailyWish = true;
        journal.rollDay(now + 60000);
        check(journal.dailyGifts == 0 && !journal.dailyWish && journal.gifts.get("fig") == 1, "Daily reset preserves lifetime history");
        journal.archive();
        check(journal.history.size() == 1 && journal.current.started == 0 && journal.history.getFirst().items.get("FIG_LOG") == 20, "Session archive retains items");
        journal.add("xp", Double.NaN, now);
        check(journal.current.started == 0, "Non-finite observations rejected");
        check(ObservationParser.PROFILE.matcher("Your profile was changed to: Apple (Co-op)").matches(), "Profile switch fixture");
        check(!ObservationParser.PROFILE.matcher("[MVP+] Player: Profile: Apple").matches(), "Player text is not a profile switch");
        check(ObservationParser.CONTRIBUTION.matcher("You helped cut 15.2% of the Helix Tree.").matches(), "Gift contribution fixture");
        var sackItems = ObservationParser.sacks("Added items:\n +1,234 Fig Log (Foraging Sack)\n -11 Fish Bait (Bait Sack)");
        check(sackItems.size() == 2 && sackItems.getFirst().count() == 1234 && sackItems.getLast().count() == -11, "Sack hover receipt");
        check(ObservationParser.reward("Forest Whispers x1,000").count() == 1000, "Exact reward quantity");
        check(ObservationParser.reward("Tender Wood x0-2") == null && ObservationParser.reward("Signal Enhancer (0.4%)") == null, "Potential rewards are not counted as received");
        huntingChecks(now);
        System.out.println("Core regression checks passed: planner, currencies, sessions, goals, daily rollover, hunting and chat fixtures");
    }
    private static void huntingChecks(long now) {
        String[] messages = {
            "You caught x2 Voracious Spider Shards!", "You caught an Invisibug Shard!",
            "\uE025 GOOD CATCH! You caught Water Snake Shard x3!",
            "\uE025 GREAT CATCH! You caught a Giant Water Bug Shard!",
            "LOOT SHARE You received a Glacite Walker Shard for assisting Mealoan!",
            "LOOT SHARE! You received 2x Parakeet Shard from meowgirlemily catching a Parakeet!",
            "CHARM! You charmed the Slug and received 1 Keeled Slug Shard!",
            "CAPTURE! You caught a Strongarm and gained a Strongarm Shard!",
            "FLOOR DROP! You found Solsnatcher Shard on the ground!",
            "SHARD! Your contribution earned you the End Stone Protector Shard!"
        };
        int[] amounts = {2, 1, 3, 1, 1, 2, 1, 1, 1, 1};
        for (int i = 0; i < messages.length; i++) {
            var caught = Hunting.parseCatch(messages[i]);
            check(caught != null && caught.amount() == amounts[i], "Hunting fixture: " + messages[i]);
        }
        check(Hunting.parseCatch("§aYou caught x2 Voracious Spider Shards!").id().equals("SHARD_VORACIOUS_SPIDER"), "Bazaar shard identifier");
        check(Hunting.shardId(" voracious spider Shard ").equals("SHARD_VORACIOUS_SPIDER"), "Goal name normalization");
        for (String ignored : List.of("[MVP+] Player: You caught x2 Voracious Spider Shards!",
                "You sent 6 Voracious Spider Shards to your Hunting Box.", "FUSION! You obtained a Tadgang Shard! NEW!",
                "You caught x0 Voracious Spider Shards!", "You caught x999999999999999 Voracious Spider Shards!")) {
            check(Hunting.parseCatch(ignored) == null, "Non-rewards excluded: " + ignored);
        }
        int ready = Hunting.parseTraps("HUNT - 2 of your Hunting Traps caught something![Click here to collect them.]");
        Hunting h = new Hunting();
        check(ready == 2 && h.reportTraps(ready, now), "Trap notice fixture");
        check(!h.reportTraps(ready, now + 1000) && h.readyTraps == 2, "Repeated trap reports are not added");
        check(Hunting.parseTraps("Player: HUNT - 2 of your Hunting Traps caught something!") == -1, "No player trap notices");
        h.dismissTraps(); check(h.readyTraps == 0 && h.reportTraps(2, now + 2000), "A new report after dismissal alerts again");
        var latch = new Hunting.ReelLatch();
        check(latch.observe(5, now) && !latch.observe(5, now + 100), "REEL alerts once per phase");
        latch.observe(-1, now + 200);
        check(!latch.observe(5, now + 300), "A missing tag frame does not repeat the sound");
        latch.observe(-1, now + 700);
        check(latch.observe(5, now + 800) && latch.observe(6, now + 900), "New phase and new target alert");
        latch.clear(); check(latch.observe(6, now + 1000), "Profile/world reset clears the cue");
        Journal j = new Journal();
        var target = new Journal.Target(); target.metric = "shard:SHARD_VORACIOUS_SPIDER"; target.amount = 10;
        var caught = Hunting.parseCatch(messages[0]);
        for (int i = 0; i < 100; i++) j.tickHunting(200, true, now + i * 200);
        j.caught(caught, now + 20_000);
        check(j.current.rate("shards") == 360 && j.current.elapsedMs == 0, "Hunting has its own timed rate");
        check(j.current.items.isEmpty() && j.current.shards.get(caught.id()) == 2, "Shard values stay separate from foraging loot");
        j.current.paused = true; j.caught(caught, now + 21000); j.tickHunting(200, true, now + 21000);
        check(j.current.value("shards") == 2 && j.hunting.shards.get(caught.id()) == 4 && j.progress(target) == 4, "Paused session retains lifetime and goal progress");
        check(j.current.huntingElapsedMs == 20000, "Pause freezes hunting time");
        j.archive(); check(j.current.shards.isEmpty() && j.history.getFirst().shards.get(caught.id()) == 2 && j.hunting.shards.get(caught.id()) == 4, "Archive isolates hunting sessions");
    }
}
