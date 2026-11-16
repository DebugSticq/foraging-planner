package dev.foragingplanner.core;

import java.util.*;
import java.util.regex.Pattern;

/** Observed rewards, not an inventory balance. Transfers and fusions must not count as catches. */
public final class Hunting {
    public Map<String, Long> shards = new LinkedHashMap<>();
    public Map<String, String> names = new LinkedHashMap<>();
    public int readyTraps;
    public long trapNoticeAt;

    public record Catch(String name, int amount) {
        public String id() { return shardId(name); }
    }
    private static final List<Pattern> CATCHES = List.of(
        Pattern.compile("You caught(?: an?)?(?: x(?<amount>[\\d,]+))? (?<name>.+?) Shards?!"),
        Pattern.compile("\\uE025 [A-Z ]+ CATCH! You caught(?: an?)? (?<name>.+?) Shard(?: x(?<amount>[\\d,]+))?!"),
        Pattern.compile("LOOT SHARE!? You received (?:an?|(?<amount>[\\d,]+)x?) (?<name>.+?) Shards? (?:for assisting .+|from .+)!"),
        Pattern.compile("CHARM! You charmed the .+ and received (?<amount>[\\d,]+) (?<name>.+?) Shards?!"),
        Pattern.compile("CAPTURE! You (?:caught an?|found) .+ and (?:gained|as a reward (?:he|she|they|it) gave you) (?:an?|(?<amount>[\\d,]+)x) (?<name>.+?) Shards?!"),
        Pattern.compile("(?:FLOOR DROP! You found |SHARD! Your contribution earned you the )(?<name>.+?) Shard(?: on the ground)?!(?<amount>)")
    );
    private static final Pattern TRAPS = Pattern.compile("HUNT [-–] (?<amount>[1-5]) of your Hunting Traps caught something!\\s*(?:\\[Click here to collect them\\.])?");

    public static Catch parseCatch(String text) {
        String clean = text.replaceAll("§.", "").trim();
        for (var pattern : CATCHES) {
            var m = pattern.matcher(clean);
            if (!m.matches()) continue;
            try {
                String quantity = m.group("amount");
                int amount = quantity == null || quantity.isEmpty() ? 1 : Integer.parseInt(quantity.replace(",", ""));
                String name = m.group("name").trim();
                if (amount > 0 && amount <= 100_000 && !shardId(name).equals("SHARD_")) return new Catch(name, amount);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    public static int parseTraps(String text) {
        var m = TRAPS.matcher(text.replaceAll("§.", "").trim());
        return m.matches() ? Integer.parseInt(m.group("amount")) : -1;
    }
    public static String shardId(String name) {
        String clean = name.trim().replaceAll("(?i)\\s+shards?$", "");
        return "SHARD_" + clean.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
    /** Notifications describe a snapshot. Never sum repeated ready notices. */
    public boolean reportTraps(int amount, long now) {
        if (amount < 1 || amount > 5) return false;
        boolean alert = readyTraps != amount || now - trapNoticeAt >= 30_000;
        readyTraps = amount; trapNoticeAt = now;
        return alert;
    }
    public void dismissTraps() { readyTraps = 0; trapNoticeAt = 0; }

    /** One sound per REEL phase; brief missing name-tag frames do not retrigger it. */
    public static final class ReelLatch {
        private int target = -1;
        private long lastSeen;
        public boolean observe(int entity, long now) {
            if (entity < 0) { if (now - lastSeen > 300) target = -1; return false; }
            boolean edge = entity != target || now - lastSeen > 300;
            target = entity; lastSeen = now; return edge;
        }
        public void clear() { target = -1; lastSeen = 0; }
    }
}
