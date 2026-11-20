package dev.foragingplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import dev.foragingplanner.core.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** Pannable, zoomable view of observed dependencies; unknown edges are never invented. */
public final class PerkTreeScreen extends Screen {
    private final Screen parent;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private record Node(GameData.Perk perk, int x, int y) {}
    private int left, top, pw, ph, gx, gy, gw, gh, dx, dy, dw, dh;
    private double panX, panY, zoom = 1, dragX, dragY, dragPanX, dragPanY;
    private boolean dragging, moved, positioned;
    private GameData.Perk selected;
    private HotfPlanner.Plan plan;
    private final Set<String> recommended = new HashSet<>();
    public PerkTreeScreen(Screen parent) { super(Component.literal("Heart of the Forest")); this.parent = parent; }
    private String tr(String key, Object... args) { return Planner.tr("journal." + key, args); }
    @Override protected void init() {
        nodes.clear(); recommended.clear();
        pw = Math.min(width - 12, 940); ph = Math.min(height - 12, 580); left = (width - pw) / 2; top = (height - ph) / 2;
        gx = left + 8; gy = top + 44; gw = pw - 16; gh = ph - 74;
        if (pw >= 630) { gw -= 230; dx = gx + gw + 8; dy = gy; dw = 222; dh = gh; }
        else { gh -= 84; dx = gx; dy = gy + gh + 6; dw = gw; dh = 78; }
        Map<Integer, Integer> columns = new HashMap<>();
        for (var perk : Planner.data.hotf.perks) {
            int tier = perk.requiredTier, col = columns.merge(tier, 1, Integer::sum) - 1;
            nodes.put(perk.name, new Node(perk, (perk.treeColumn < 0 ? col : perk.treeColumn) * 135 + 12, (tier - 1) * 68 + 12));
        }
        if (!positioned) { zoom = Math.clamp((gw - 24) / 945.0, .45, 1); panX = (gw - 945 * zoom) / 2; positioned = true; }
        plan = Planner.plan(); plan.steps().forEach(step -> recommended.add(step.perk().name));
        if (selected == null && !Planner.data.hotf.perks.isEmpty()) selected = Planner.data.hotf.perks.getFirst();
        addRenderableWidget(new ForestButton(left + 8, top + ph - 25, 78, 19, Component.literal(tr("center_view")), b -> { positioned = false; panY = 0; clearWidgets(); init(); }, () -> false));
        addRenderableWidget(new ForestButton(left + pw - 76, top + ph - 25, 68, 19, Component.literal(Planner.tr("settings.done")), b -> onClose(), () -> true));
        addRenderableWidget(new ForestButton(dx + 6, dy + dh - 24, Math.min(dw - 12, 160), 19, Component.literal(tr("target_perk")), b -> {
            if (selected != null) TargetProgress.perk(selected, Math.min(selected.maxLevel, Planner.config.perkLevels.getOrDefault(selected.name, 0) + 1));
        }, () -> false));
        int whyX = dx + 6 + Math.min(dw - 12, 160) + 4;
        if (whyX + 40 <= dx + dw - 4) addRenderableWidget(new ForestButton(whyX, dy + dh - 24, Math.min(60, dx + dw - 4 - whyX), 19, Component.literal(Planner.tr("why.button")), b -> {
            if (selected != null) Why.perk(selected);
        }, () -> false));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xDC09150F);
        ForestTheme.panel(g, left, top, pw, ph, ForestTheme.CANVAS, ForestTheme.BORDER);
        ForestTheme.icon(g, Items.OAK_SAPLING, left + 10, top + 9);
        g.text(font, "Heart of the Forest", left + 35, top + 8, ForestTheme.TEXT);
        String budgets = tr("budgets", Planner.config.whispers < 0 ? "?" : Planner.whole(Planner.config.whispers), Planner.config.desertWhispers < 0 ? "?" : Planner.whole(Planner.config.desertWhispers));
        g.text(font, font.plainSubstrByWidth(budgets + " · " + Math.max(0, Planner.config.tokens) + " Tokens", pw - 45), left + 35, top + 24, ForestTheme.LEAF);
        ForestTheme.panel(g, gx, gy, gw, gh, ForestTheme.PANEL, ForestTheme.BORDER);
        g.enableScissor(gx + 1, gy + 1, gx + gw - 1, gy + gh - 1);
        g.pose().pushMatrix(); g.pose().translate((float)(gx + panX), (float)(gy + panY)); g.pose().scale((float)zoom, (float)zoom);
        Set<String> selectedPath = new HashSet<>();
        if (selected != null) HotfPlanner.unlockPath(Planner.data, Planner.config.perkLevels, selected.name,
                Math.min(selected.maxLevel, Planner.config.perkLevels.getOrDefault(selected.name, 0) + 1), Planner.config.hotfTier).forEach(step -> selectedPath.add(step.perk().name));
        for (var node : nodes.values()) for (String parent : node.perk().requires.keySet()) {
            Node from = nodes.get(parent); if (from == null) continue;
            int color = selectedPath.contains(parent) && selectedPath.contains(node.perk().name) ? ForestTheme.GOLD : 0xFF50634C;
            int ax = from.x() + 60, ay = from.y() + 42, bx = node.x() + 60, by = node.y();
            int mid = (ay + by) / 2;
            g.fill(ax, Math.min(ay, mid), ax + 2, Math.max(ay, mid) + 1, color);
            g.fill(Math.min(ax, bx), mid, Math.max(ax, bx) + 2, mid + 2, color);
            g.fill(bx, Math.min(mid, by), bx + 2, Math.max(mid, by) + 1, color);
        }
        for (var node : nodes.values()) {
            var perk = node.perk(); int level = Planner.config.perkLevels.getOrDefault(perk.name, 0);
            int border = selected == perk ? ForestTheme.GOLD : recommended.contains(perk.name) ? ForestTheme.LEAF : ForestTheme.BORDER;
            ForestTheme.panel(g, node.x(), node.y(), 122, 45, level > 0 ? 0xFF294433 : ForestTheme.CARD, border);
            g.text(font, font.plainSubstrByWidth(perk.name, 108), node.x() + 6, node.y() + 7, ForestTheme.TEXT);
            g.text(font, level + "/" + perk.maxLevel + " · T" + perk.requiredTier, node.x() + 6, node.y() + 22, ForestTheme.LEAF);
            String state = level > 0 ? perk.currency : perk.unlockKnown ? tr("locked") : tr("path_unknown_short");
            g.text(font, font.plainSubstrByWidth(state, 108), node.x() + 6, node.y() + 33, ForestTheme.MUTED);
        }
        g.pose().popMatrix(); g.disableScissor();
        ForestTheme.panel(g, dx, dy, dw, dh, ForestTheme.CARD, ForestTheme.BORDER);
        g.enableScissor(dx + 3, dy + 3, dx + dw - 3, dy + dh - 27);
        if (selected != null) {
            int level = Planner.config.perkLevels.getOrDefault(selected.name, 0);
            g.text(font, font.plainSubstrByWidth(selected.name, dw - 14), dx + 7, dy + 7, ForestTheme.GOLD);
            var path = HotfPlanner.unlockPath(Planner.data, Planner.config.perkLevels, selected.name, Math.min(selected.maxLevel, level + 1), Planner.config.hotfTier);
            long f = 0, d = 0; int tokens = 0;
            for (var step : path) { if (step.fromLevel() == 0) tokens++; if (step.perk().currency.equals("desert")) d += step.cost(); else f += step.cost(); }
            String cost = path.isEmpty() ? tr(level >= selected.maxLevel ? "complete" : "path_unknown") : Planner.whole(f) + " F / " + Planner.whole(d) + " D / " + tokens + " T";
            g.text(font, font.plainSubstrByWidth(cost, dw - 14), dx + 7, dy + 23, ForestTheme.TEXT);
            var stats = SkyBlockReader.stats(); var levels = new LinkedHashMap<>(Planner.config.perkLevels);
            for (var step : path) { stats = HotfPlanner.advance(Planner.data, levels, stats, step.perk(), step.toLevel() - step.fromLevel()); levels.put(step.perk().name, step.toLevel()); }
            double before = Planner.objective().smoothValue(SkyBlockReader.stats()), after = Planner.objective().smoothValue(stats);
            String gain = tr("modeled_gain", before > 0 ? Planner.decimal((after / before - 1) * 100) : "?");
            g.text(font, font.plainSubstrByWidth(gain, dw - 14), dx + 7, dy + 38, ForestTheme.LEAF);
            if (dh > 100) {
                int yy = dy + 59;
                for (var step : path) {
                    yy += g.textWithWordWrap(font, Component.literal(step.perk().name + " " + step.fromLevel() + " → " + step.toLevel()), dx + 7, yy, dw - 14, ForestTheme.TEXT) + 5;
                }
                g.textWithWordWrap(font, Component.literal(tr("graph_note")), dx + 7, yy + 8, dw - 14, ForestTheme.MUTED);
            }
        }
        g.disableScissor();
        g.text(font, font.plainSubstrByWidth(tr("graph_controls"), pw - 170), left + 93, top + ph - 19, ForestTheme.MUTED);
        super.extractRenderState(g, mx, my, delta);
        Why.draw(g, font, width, height);
    }
    private boolean inGraph(double x, double y) { return x >= gx && x < gx + gw && y >= gy && y < gy + gh; }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (Why.isOpen()) { Why.close(); return true; }
        if (super.mouseClicked(e, twice)) return true;
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && inGraph(e.x(), e.y())) { dragging = true; moved = false; dragX = e.x(); dragY = e.y(); dragPanX = panX; dragPanY = panY; setDragging(true); return true; }
        return false;
    }
    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging) return super.mouseDragged(e, dx, dy);
        if (Math.abs(e.x() - dragX) + Math.abs(e.y() - dragY) > 3) moved = true;
        panTo(e.x(), e.y()); return true;
    }
    private void panTo(double x, double y) {
        if (Math.abs(x - dragX) + Math.abs(y - dragY) > 3) moved = true;
        if (moved) { panX = Math.clamp(dragPanX + x - dragX, -1800, gw - 30); panY = Math.clamp(dragPanY + y - dragY, -1000, gh - 30); }
    }
    @Override public void mouseMoved(double x, double y) { if (dragging) panTo(x, y); super.mouseMoved(x, y); }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging) {
            panTo(e.x(), e.y());
            if (!moved) {
                double xx = (e.x() - gx - panX) / zoom, yy = (e.y() - gy - panY) / zoom;
                for (var node : nodes.values()) if (xx >= node.x() && xx < node.x() + 122 && yy >= node.y() && yy < node.y() + 45) selected = node.perk();
            }
            dragging = false; setDragging(false); return true;
        }
        return super.mouseReleased(e);
    }
    @Override public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (!inGraph(mx, my)) return super.mouseScrolled(mx, my, dx, dy);
        double old = zoom; zoom = Math.clamp(zoom * Math.pow(1.12, dy), .45, 1.6);
        panX = mx - gx - (mx - gx - panX) * zoom / old; panY = my - gy - (my - gy - panY) * zoom / old; return true;
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (Why.isOpen() && e.key() == InputConstants.KEY_ESCAPE) { Why.close(); return true; }
        switch (e.key()) {
            case InputConstants.KEY_LEFT -> panX += 30; case InputConstants.KEY_RIGHT -> panX -= 30; case InputConstants.KEY_UP -> panY += 30; case InputConstants.KEY_DOWN -> panY -= 30;
            case InputConstants.KEY_EQUALS -> zoom = Math.min(1.6, zoom + .1); case InputConstants.KEY_MINUS -> zoom = Math.max(.45, zoom - .1);
            default -> { return super.keyPressed(e); }
        } return true;
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float delta) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Why.close(); minecraft.gui.setScreen(parent); }
}
