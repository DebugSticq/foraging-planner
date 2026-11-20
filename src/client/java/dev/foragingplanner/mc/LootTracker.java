package dev.foragingplanner.mc;

import java.util.*;
import java.util.regex.*;
import dev.foragingplanner.core.ObservationParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Reads the server's full gift block including SHOW_TEXT rewards and sack receipts. */
public final class LootTracker {
    private static long giftUntil;
    private static String giftTree;
    private static boolean bonus, rewarded, sackLogged;
    private static final Set<String> seenRewardLines = new HashSet<>();
    public static void clear() { giftUntil = 0; giftTree = null; bonus = rewarded = false; seenRewardLines.clear(); }
    public static void register() {
        ServerMessages.register((component, overlay) -> {
            if (overlay) return;
            String text = Planner.strip(component.getString()).trim();
            ProfileStore.observe(text);
            if (!SkyBlockReader.inForagingArea()) return;
            long now = System.currentTimeMillis();
            if (text.equals("TREE GIFT")) { clear(); giftUntil = now + 8000; return; }
            if (text.startsWith("[Sacks]")) {
                Set<String> hovers = hovers(component);
                if (ContestTracker.active() && !sackLogged) {
                    sackLogged = true;
                    Planner.LOGGER.info("[FP] Sack message during contest: {} | hover: {}", text, hovers);
                }
                var windowMatch = java.util.regex.Pattern.compile("\\(Last (\\d+)s\\.?\\)").matcher(text);
                int window = windowMatch.find() ? Integer.parseInt(windowMatch.group(1)) : 10;
                // "[Sacks] +1,234 items, -12 items. (Last 30s.)": the total says how much a shortened hover left out.
                var addedMatch = java.util.regex.Pattern.compile("\\+([\\d,]+) items?").matcher(text);
                long added = addedMatch.find() ? Long.parseLong(addedMatch.group(1).replace(",", "")) : -1;
                long listed = 0;
                boolean shortened = false;
                for (String hover : hovers) {
                    if (hover.contains("other items")) {
                        JournalStore.sackIncomplete = true;
                        shortened = true;
                    }
                    for (var item : ObservationParser.sacks(hover)) {
                        String id = ObservationParser.itemId(item.name());
                        if (item.count() > 0) listed += item.count();
                        if (Planner.data.trees.stream().anyMatch(tree -> tree.bazaarId.equals(id))) {
                            SessionRates.sack(id, item.count(), window);
                        } else {
                            Extras.gathered(item.name(), item.count());
                            SeenNames.item(id, item.name());
                            SessionRates.sackOther(id, item.count(), window);
                        }
                    }
                }
                if (shortened && added > listed) SessionRates.sackHidden(added - listed);
                return;
            }
            if (text.startsWith("Daily Wishes") && text.contains("Whispers")) {
                JournalStore.journal.dailyWish = true;
            }
            var contribution = ObservationParser.CONTRIBUTION.matcher(text);
            if (contribution.matches()) {
                TreeProgress.contribution(contribution.group(2), Double.parseDouble(contribution.group(1)));
                Milestones.gift(contribution.group(2));
            }
            if (now > giftUntil) return;
            if (contribution.matches()) { giftTree = contribution.group(2).toLowerCase(Locale.ROOT); return; }
            if (text.matches("\\+\\d[\\d,]* rewards gained!.*") && giftTree != null && !rewarded) {
                rewarded = true;
                JournalStore.journal.gift(giftTree, now);
                for (String hover : hovers(component)) for (String line : hover.split("\\n")) {
                    var item = ObservationParser.reward(line);
                    if (item != null && seenRewardLines.add(line.trim())) reward(item.name(), item.count(), now, false);
                }
                return;
            }
            if (text.equals("BONUS GIFT")) { bonus = true; return; }
            if (bonus && rewarded) {
                var drop = ObservationParser.BONUS.matcher(text);
                if (drop.matches() && seenRewardLines.add(text)) reward(drop.group(1), 1, now, true);
            }
            if (text.matches("▬{20,}")) { JournalStore.save(); clear(); }
        });
    }
    private static void reward(String name, long count, long now, boolean bonusDrop) {
        var journal = JournalStore.journal;
        switch (name) {
            case "Foraging Experience", "Foraging XP" -> { /* actionbar is authoritative */ }
            case "HOTF Experience", "HotF Experience" -> journal.add("hotf", count, now);
            case "Forest Whispers" -> journal.add("forest", count, now);
            case "Desert Whispers" -> journal.add("desert", count, now);
            default -> {
                String id = ObservationParser.itemId(name);
                journal.loot.merge(id, count, Long::sum);
                if (id.equals("ESSENCE_FOREST")) journal.add("essence", count, now);
                else Watchers.reward(id, name, count, bonusDrop);
                // Logs already enter through inventory/sacks; don't value them twice.
                if (Planner.data.trees.stream().noneMatch(tree -> tree.bazaarId.equals(id))) {
                    journal.item(id, count, now);
                    // The same items arrive in the sacks a little later; the sack message must not count them again.
                    SessionRates.rewarded(id, count);
                }
            }
        }
    }
    private static Set<String> hovers(Component component) {
        Set<String> result = new LinkedHashSet<>(); collect(component, result); return result;
    }
    private static void collect(Component c, Set<String> output) {
        if (c.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hover) output.add(Planner.strip(hover.value().getString()));
        for (Component sibling : c.getSiblings()) collect(sibling, output);
    }
    private LootTracker() {}
}
