package dev.foragingplanner.core;

import java.util.*;

/** Greedy budget planner that evaluates complete prerequisite paths before spending tokens. */
public final class HotfPlanner {
    private HotfPlanner() {}
    public record Upgrade(GameData.Perk perk, int fromLevel, long cost, double gain, double gainPercent) {
        public double efficiency() { return gain / Math.max(1, cost); }
    }
    public record PlanStep(GameData.Perk perk, int fromLevel, int toLevel, long cost) {}
    public record Plan(List<PlanStep> steps, long totalCost, long leftover, double gainPercent, int tokensUsed, long forestCost, long desertCost, long forestLeft, long desertLeft) {}

    /** Applies a level change, including interaction between Strength/Speed and conversion perks. */
    public static PlayerStats advance(GameData data, Map<String, Integer> levels, PlayerStats stats,
                                      GameData.Perk perk, int count) {
        PlayerStats next = stats.plus(perk, count);
        double extra = 0;
        for (GameData.Perk other : data.hotf.perks) {
            int level = levels.getOrDefault(other.name, 0);
            extra += level * (other.effects.getOrDefault("strengthConversion", 0.0) * (next.strength() - stats.strength())
                    + other.effects.getOrDefault("speedConversion", 0.0) * (next.speed() - stats.speed()));
        }
        double wisdom = next.wisdom();
        if (levels.getOrDefault(perk.name, 0) == 0) wisdom += perk.unlockEffects.getOrDefault("wisdom", 0.0);
        return new PlayerStats(next.sweep() + extra, next.fortune() + extra, wisdom,
                next.strength(), next.speed(), next.dailyWhispers(), next.giftBonus());
    }

    public static List<Upgrade> rankUpgrades(GameData data, Map<String, Integer> levels, PlayerStats stats, Objective objective) {
        List<Upgrade> result = new ArrayList<>();
        double current = objective.smoothValue(stats);
        for (GameData.Perk perk : data.hotf.perks) {
            int level = levels.getOrDefault(perk.name, 0);
            if (!perk.hasValuedEffects() || level < 1 || level >= perk.maxLevel) continue;
            double gain = objective.smoothValue(advance(data, levels, stats, perk, 1)) - current;
            result.add(new Upgrade(perk, level, PerkMath.costToReach(perk, level + 1), gain,
                    current <= 0 ? 0 : gain / current * 100));
        }
        result.sort(Comparator.comparingDouble(Upgrade::efficiency).reversed());
        return result;
    }

    public static Plan plan(GameData data, Map<String, Integer> levels, PlayerStats stats, Objective objective, long budget) {
        return plan(data, levels, stats, objective, budget, 0, 1);
    }

    public static Plan plan(GameData data, Map<String, Integer> levels, PlayerStats stats, Objective objective,
                            long budget, int tokens, int tier) {
        return plan(data, levels, stats, objective, budget, 0, tokens, tier);
    }

    public static Plan plan(GameData data, Map<String, Integer> levels, PlayerStats stats, Objective objective,
                            long forestBudget, long desertBudget, int tokens, int tier) {
        Map<String, Integer> working = new LinkedHashMap<>(levels);
        List<PlanStep> steps = new ArrayList<>();
        PlayerStats now = stats;
        long forestLeft = Math.max(0, forestBudget), desertLeft = Math.max(0, desertBudget);
        int remainingTokens = Math.max(0, tokens);
        double start = objective.smoothValue(stats);
        while (true) {
            List<PlanStep> best = List.of();
            PlayerStats bestStats = now;
            double bestScore = 0;
            int bestTokens = 0;
            long bestForest = 0, bestDesert = 0;
            for (GameData.Perk perk : data.hotf.perks) {
                int currentLevel = working.getOrDefault(perk.name, 0);
                if (!perk.hasValuedEffects() || currentLevel >= perk.maxLevel) continue;
                Map<String, Integer> target = new LinkedHashMap<>(working);
                List<PlanStep> path = new ArrayList<>();
                if (!path(data, perk, currentLevel + 1, tier, target, path, new HashSet<>())) continue;
                int tokenCost = (int) path.stream().filter(step -> step.fromLevel() == 0).count();
                long cost = path.stream().mapToLong(PlanStep::cost).sum();
                long desert = path.stream().filter(step -> "desert".equals(step.perk().currency)).mapToLong(PlanStep::cost).sum();
                long forest = cost - desert;
                if (forest > forestLeft || desert > desertLeft || tokenCost > remainingTokens) continue;
                PlayerStats after = now;
                Map<String, Integer> simulation = new LinkedHashMap<>(working);
                for (PlanStep step : path) {
                    after = advance(data, simulation, after, step.perk(), step.toLevel() - step.fromLevel());
                    simulation.put(step.perk().name, step.toLevel());
                }
                double gain = objective.smoothValue(after) - objective.smoothValue(now);
                double score = gain / Math.max(1, cost);
                if (score > bestScore) {
                    best = path;
                    bestStats = after;
                    bestScore = score;
                    bestTokens = tokenCost;
                    bestForest = forest;
                    bestDesert = desert;
                }
            }
            if (best.isEmpty()) break;
            for (PlanStep step : best) {
                working.put(step.perk().name, step.toLevel());
                // Only coalesce adjacent steps, preserving executable prerequisite order.
                if (!steps.isEmpty() && steps.getLast().perk() == step.perk()) {
                    PlanStep previous = steps.removeLast();
                    steps.add(new PlanStep(step.perk(), previous.fromLevel(), step.toLevel(), previous.cost() + step.cost()));
                } else steps.add(step);
            }
            now = bestStats;
            forestLeft -= bestForest;
            desertLeft -= bestDesert;
            remainingTokens -= bestTokens;
        }
        double percent = start <= 0 ? 0 : (objective.smoothValue(now) - start) / start * 100;
        long forestCost = Math.max(0, forestBudget) - forestLeft, desertCost = Math.max(0, desertBudget) - desertLeft;
        return new Plan(steps, forestCost + desertCost, forestLeft + desertLeft, percent,
                Math.max(0, tokens) - remainingTokens, forestCost, desertCost, forestLeft, desertLeft);
    }

    public static List<PlanStep> unlockPath(GameData data, Map<String, Integer> levels, String name, int desired, int tier) {
        GameData.Perk perk = data.perk(name);
        if (perk == null) return List.of();
        List<PlanStep> result = new ArrayList<>();
        return path(data, perk, desired, tier, new LinkedHashMap<>(levels), result, new HashSet<>()) ? result : List.of();
    }

    private static boolean path(GameData data, GameData.Perk perk, int desired, int tier,
                                Map<String, Integer> levels, List<PlanStep> steps, Set<String> visiting) {
        int current = levels.getOrDefault(perk.name, 0);
        if (current >= desired) return true;
        if (!perk.planEligible || desired > perk.maxLevel || !visiting.add(perk.name)) return false;
        if (current == 0) {
            if (!perk.unlockKnown || perk.requiredTier > tier) return false;
            for (var required : perk.requires.entrySet()) {
                GameData.Perk parent = data.perk(required.getKey());
                if (parent == null || !path(data, parent, required.getValue(), tier, levels, steps, visiting)) return false;
            }
        }
        steps.add(new PlanStep(perk, current, desired, PerkMath.costBetween(perk, current, desired)));
        levels.put(perk.name, desired);
        visiting.remove(perk.name);
        return true;
    }
}
