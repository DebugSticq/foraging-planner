package dev.foragingplanner.mc;

import dev.foragingplanner.core.Hunting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/** Passive chat tracking and a cue for the player's own leashed mob. Never sends game input. */
public final class HuntingTracker {
    private static final Hunting.ReelLatch latch = new Hunting.ReelLatch();
    private static Object world;
    private static long lastTick, lastCatch, previewUntil;
    private static boolean reeling;
    private static int ticks;
    public static String tr(String key, Object... args) { return Planner.tr("hunting." + key, args); }
    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !SkyBlockReader.inSkyBlock()) return;
            String text = Planner.strip(message.getString()).trim();
            long now = System.currentTimeMillis();
            int traps = Hunting.parseTraps(text);
            if (traps >= 0) {
                boolean changed = JournalStore.journal.hunting.reportTraps(traps, now);
                if (changed && Planner.config.trapAlerts) {
                    JournalStore.notify(tr("traps_ready", traps)); sound();
                }
                JournalStore.save(); PlannerHud.rebuild();
            }
            if (!Planner.config.huntingEnabled) return;
            var caught = Hunting.parseCatch(text);
            if (caught != null) {
                JournalStore.journal.caught(caught, now); lastCatch = now;
                JournalStore.save(); PlannerHud.rebuild();
            }
        });
    }
    public static void reset() {
        world = null; lastTick = lastCatch = previewUntil = 0; reeling = false; ticks = 0; latch.clear();
    }
    private static String heldId(Minecraft client) {
        return client.player.getMainHandItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getStringOr("id", "");
    }
    public static void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (world != client.level) { reset(); world = client.level; }
        long elapsed = lastTick == 0 ? 0 : now - lastTick; lastTick = now;
        if (client.player == null || client.level == null || !SkyBlockReader.inSkyBlock()) {
            reeling = false; latch.clear(); return;
        }
        String held = heldId(client);
        boolean lasso = held.endsWith("LASSO");
        boolean tool = lasso || held.endsWith("FISHING_NET");
        JournalStore.journal.tickHunting(elapsed, Planner.config.huntingEnabled && (tool || now - lastCatch < 30_000), now);
        if (!Planner.config.reelCue || !lasso) { reeling = false; latch.clear(); return; }
        if (++ticks % 2 != 0) return;
        int reelTarget = -1;
        for (var entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Leashable leash) || leash.getLeashHolder() != client.player || entity.distanceToSqr(client.player) > 64 * 64) continue;
            for (var stand : client.level.getEntitiesOfClass(ArmorStand.class, entity.getBoundingBox().move(0, 2, 0).inflate(2))) {
                if (stand.getCustomName() != null && Planner.strip(stand.getCustomName().getString()).trim().equals("REEL")) {
                    reelTarget = entity.getId(); break;
                }
            }
            if (reelTarget >= 0) break;
        }
        reeling = reelTarget >= 0;
        if (latch.observe(reelTarget, now)) sound();
    }
    private static void sound() {
        if (Planner.config.huntingSound) Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f));
    }
    public static void preview() { previewUntil = System.currentTimeMillis() + 4000; sound(); }
    public static boolean previewing() { return System.currentTimeMillis() < previewUntil; }
    public static List<String> reelLines() {
        if (previewing()) return List.of("§e" + tr("preview"), "§a§lREEL!", tr("reel_action"));
        return Planner.config.reelCue && reeling && SkyBlockReader.inSkyBlock()
                ? List.of("§a§lREEL!", tr("reel_action")) : List.of();
    }
    public static String trapStatus() {
        var h = JournalStore.journal.hunting;
        if (h.trapNoticeAt == 0) return tr("traps_unknown");
        long minutes = Math.max(0, (System.currentTimeMillis() - h.trapNoticeAt) / 60_000);
        return tr("trap_report", h.readyTraps, minutes);
    }
    public static List<String> hudLines() {
        var j = JournalStore.journal;
        if (!Planner.config.huntingEnabled && (!Planner.config.trapAlerts || j.hunting.trapNoticeAt == 0)) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add("§a" + tr("title"));
        if (Planner.config.huntingEnabled) {
            lines.add(tr("session_line", Planner.whole(j.current.value("shards")),
                    j.current.huntingElapsedMs < 20_000 ? "—" : Planner.whole(j.current.rate("shards"))));
            if (j.current.paused) lines.add("§e" + Planner.tr("journal.paused"));
        }
        if (Planner.config.trapAlerts && j.hunting.trapNoticeAt != 0) lines.add("§e" + trapStatus());
        return lines;
    }
    private HuntingTracker() {}
}
