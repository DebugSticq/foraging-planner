package dev.foragingplanner.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import java.util.*;

public final class ContestAlerts {
    private static long contestEnd;
    private static final Set<String> WARNED = new HashSet<>();
    private static String message = "";
    private static long until;
    private ContestAlerts() {}

    public static void tick() {
        if (!ContestTracker.active() || !SkyBlockReader.inForagingArea()) return;
        long end = System.currentTimeMillis() + ContestTracker.secondsLeft() * 1000L;
        if (Math.abs(end - contestEnd) > 10_000) {
            contestEnd = end;
            WARNED.clear();
            announce(Planner.tr("alert.start", ContestTracker.host()));
        }
        var status = ContestTracker.status();
        if (status.next() == null || ContestTracker.secondsLeft() > 120 || ContestTracker.pointsPerMinute() <= 0) return;
        double shortfall = status.next().minPoints - status.projected();
        String key = ContestTracker.host() + status.next().name;
        if (shortfall > 0 && shortfall <= status.next().minPoints * 0.15 && WARNED.add(key)) {
            announce(Planner.tr("alert.rank", status.next().name, Planner.whole(status.neededPerMin())));
        }
    }

    private static void announce(String text) {
        if (Planner.config.contestTitle) {
            message = text;
            until = System.currentTimeMillis() + 5000;
        }
        if (Planner.config.contestSound) Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
    }

    public static void extract(GuiGraphicsExtractor graphics) {
        if (System.currentTimeMillis() >= until || !Planner.config.contestTitle || !SkyBlockReader.inForagingArea()) return;
        var client = Minecraft.getInstance();
        graphics.centeredText(client.font, message, client.getWindow().getGuiScaledWidth() / 2, 36, 0xFFFFDE9A);
    }
}
