package dev.foragingplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.core.Direction;
import java.util.*;

/** Manual phase selection with nearby display-log highlighting. No harvesting or input automation. */
public final class TreeAssistant {
    private static KeyMapping phaseKey;
    private static final Map<Integer, Integer> colors = new HashMap<>();
    private static boolean wrongTarget;
    public static void register() {
        phaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("foragingplanner.key.phase", InputConstants.KEY_G, KeyMapping.Category.MISC));
    }
    public static void keys() { while (phaseKey.consumeClick()) nextPhase(); }
    public static void nextPhase() {
        int phases = Planner.config.guideTree.equals("mangrove") ? 3 : 2;
        Planner.config.guidePhase = (Planner.config.guidePhase + 1) % phases;
        Planner.config.save(); PlannerHud.rebuild();
    }
    public static String description() {
        String[] phases = switch (Planner.config.guideTree) {
            case "fig" -> new String[]{"trunk", "branches"};
            case "mangrove" -> new String[]{"branches", "trunk", "roots"};
            default -> new String[]{"beige", "red"};
        };
        return Planner.config.guideTree + " · " + Planner.tr("journal.phase_" + phases[Math.floorMod(Planner.config.guidePhase, phases.length)]) + " [G]";
    }
    public static String warning() { return Planner.tr(wrongTarget ? "journal.wrong_phase" : "journal.manual_phase"); }
    public static int color(Display.BlockDisplay entity) { return colors.getOrDefault(entity.getId(), 0); }
    public static void tick(Minecraft client) {
        colors.clear(); wrongTarget = false;
        if (!Planner.config.treeAssistant || !SkyBlockReader.inForagingArea() || client.level == null || client.player == null) return;
        var eye = client.player.getEyePosition(); var look = client.player.getLookAngle();
        double bestDot = .99;
        for (var entity : client.level.getEntitiesOfClass(Display.BlockDisplay.class, client.player.getBoundingBox().inflate(12))) {
            var render = entity.blockRenderState(); if (render == null) continue;
            var state = render.blockState(); boolean recognized = false, correct = false;
            int phase = Planner.config.guidePhase;
            boolean vertical = !state.hasProperty(RotatedPillarBlock.AXIS) || state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
            switch (Planner.config.guideTree) {
                case "helix" -> {
                    boolean beige = state.is(Blocks.STRIPPED_BIRCH_WOOD), red = state.is(Blocks.STRIPPED_MANGROVE_WOOD);
                    recognized = beige || red; correct = phase % 2 == 0 ? beige : red;
                }
                case "fig" -> { recognized = state.is(Blocks.STRIPPED_SPRUCE_WOOD); correct = recognized && (phase % 2 == 0 ? vertical : !vertical); }
                case "mangrove" -> {
                    boolean roots = state.is(Blocks.MANGROVE_ROOTS) || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
                    boolean wood = state.is(Blocks.MANGROVE_WOOD);
                    recognized = wood || roots;
                    correct = switch (phase % 3) { case 0 -> wood && !vertical; case 1 -> wood && vertical; default -> roots; };
                }
            }
            if (!recognized) continue;
            if (correct) colors.put(entity.getId(), 0xFF8CD58B);
            var offset = entity.position().add(.5, .5, .5).subtract(eye);
            if (offset.lengthSqr() < 64 && offset.lengthSqr() > .1) {
                double dot = offset.normalize().dot(look);
                if (dot > bestDot) { bestDot = dot; wrongTarget = !correct; }
            }
        }
    }
    private TreeAssistant() {}
}
