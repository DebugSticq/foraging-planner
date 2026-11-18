package dev.foragingplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Drag independent panels. Wheel scales the selected panel; hidden modules remain editable. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private Map<String, List<String>> previews;
    private String selected = "trees";
    private boolean dragging;
    private double offsetX, offsetY;
    public HudEditorScreen(Screen parent) { super(Component.literal(Planner.tr("settings.position"))); this.parent = parent; }
    private ModConfig.HudLayout layout() { return Planner.config.module(selected); }
    @Override protected void init() {
        clearWidgets(); previews = PlannerHud.previewModules();
        int bw = Math.min(96, (width - 32) / 4), start = (width - (bw * 4 + 18)) / 2;
        String[] keys = {"default_position", "center", "visibility", "done"};
        for (int i = 0; i < 4; i++) {
            final int action = i;
            addRenderableWidget(new ForestButton(start + i * (bw + 6), height - 26, bw, 20,
                    Component.literal(Planner.tr("settings." + keys[i])), b -> {
                switch (action) {
                    case 0 -> { Planner.config.modules.clear(); for (String id : previews.keySet()) Planner.config.module(id); for (String id : previews.keySet()) clamp(id); }
                    case 1 -> { layout().x = (width - boxWidth(selected)) / 2; layout().y = (height - boxHeight(selected)) / 2; }
                    case 2 -> layout().visible = !layout().visible;
                    case 3 -> onClose();
                } clamp();
            }, () -> action == 2 && layout().visible));
        }
        for (String id : previews.keySet()) clamp(id);
    }
    private int boxWidth(String id) { return Math.min(width - 8, (int) Math.ceil(PlannerHud.cardWidth(id, previews.get(id)) * Planner.config.module(id).scale)); }
    private int boxHeight(String id) { return (int) Math.ceil(PlannerHud.cardHeight(id, previews.get(id)) * Planner.config.module(id).scale); }
    private void clamp() { clamp(selected); }
    private void clamp(String id) {
        var l = Planner.config.module(id);
        l.x = Math.clamp(l.x, 0, Math.max(0, width - boxWidth(id)));
        l.y = Math.clamp(l.y, 0, Math.max(0, height - 66 - boxHeight(id)));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xC0091510);
        if (Planner.config.hudGrid) {
            for (int xx = 0; xx < width; xx += 10) g.fill(xx, 0, xx + 1, height, xx % 50 == 0 ? 0x223D6348 : 0x0F3D6348);
            for (int yy = 0; yy < height; yy += 10) g.fill(0, yy, width, yy + 1, yy % 50 == 0 ? 0x223D6348 : 0x0F3D6348);
        }
        g.fill(width / 2, 0, width / 2 + 1, height - 64, 0x203D6348);
        g.fill(0, height / 2, width, height / 2 + 1, 0x203D6348);
        for (var entry : previews.entrySet()) if (!entry.getKey().equals(selected)) PlannerHud.drawModule(g, entry.getKey(), entry.getValue(), true, false);
        PlannerHud.drawModule(g, selected, previews.get(selected), true, true);
        if (dragging) for (int[] gd : guides) {
            if (gd[0] == 0) g.fill(gd[1], 0, gd[1] + 1, height - 64, 0xCCEBC678);
            else g.fill(0, gd[1], width, gd[1] + 1, 0xCCEBC678);
        }
        g.fill(0, height - 64, width, height, 0xEF101B18);
        String label = Planner.tr("journal.module_" + selected) + " · " + layout().x + ", " + layout().y + " · " + Math.round(layout().scale * 100) + "%";
        g.centeredText(font, label, width / 2, height - 59, ForestTheme.GOLD);
        g.centeredText(font, font.plainSubstrByWidth(Planner.tr("journal.hud_help"), width - 12), width / 2, height - 44, ForestTheme.MUTED);
        g.centeredText(font, font.plainSubstrByWidth(Planner.tr("journal.hud_help2", Planner.tr(Planner.config.hudSnap ? "journal.hud_on" : "journal.hud_off"), Planner.tr(Planner.config.hudGrid ? "journal.hud_on" : "journal.hud_off")), width - 12), width / 2, height - 34, ForestTheme.MUTED);
        super.extractRenderState(g, mx, my, delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (super.mouseClicked(e, twice)) return true;
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT || e.y() >= height - 64) return false;
        List<String> ids = new ArrayList<>(previews.keySet()); ids.remove(selected); ids.add(selected); Collections.reverse(ids);
        for (String id : ids) {
            var l = Planner.config.module(id);
            if (e.x() >= l.x && e.x() < l.x + boxWidth(id) && e.y() >= l.y && e.y() < l.y + boxHeight(id)) {
                selected = id; dragging = true; setDragging(true); setFocused(null); offsetX = e.x() - l.x; offsetY = e.y() - l.y; return true;
            }
        }
        return false;
    }
    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (!dragging) return super.mouseDragged(e, dx, dy);
        moveTo(e.x(), e.y()); return true;
    }
    private static final int SNAP = 6;
    /** Guide lines shown while dragging: {0 = vertical / 1 = horizontal, position}. */
    private final List<int[]> guides = new ArrayList<>();
    private void moveTo(double x, double y) {
        int nx = (int) (x - offsetX), ny = (int) (y - offsetY);
        guides.clear();
        boolean free = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
        if (!free && Planner.config.hudSnap) {
            int w = boxWidth(selected), h = boxHeight(selected);
            int bottomLimit = height - 66;
            List<Integer> xs = new ArrayList<>(List.of(0, width / 2, width));
            List<Integer> ys = new ArrayList<>(List.of(0, height / 2, bottomLimit));
            for (String id : previews.keySet()) {
                if (id.equals(selected)) continue;
                var o = Planner.config.module(id);
                if (!o.visible) continue;
                int ow = boxWidth(id), oh = boxHeight(id);
                xs.add(o.x); xs.add(o.x + ow); xs.add(o.x + ow / 2);
                ys.add(o.y); ys.add(o.y + oh); ys.add(o.y + oh / 2);
                // A small gap next to another card looks tidier than touching it.
                xs.add(o.x + ow + 2); xs.add(o.x - 2);
                ys.add(o.y + oh + 2); ys.add(o.y - 2);
            }
            int[] bestX = snap(nx, w, xs), bestY = snap(ny, h, ys);
            if (bestX != null) { nx = bestX[0]; guides.add(new int[] {0, bestX[1]}); }
            if (bestY != null) { ny = bestY[0]; guides.add(new int[] {1, bestY[1]}); }
            if (bestX == null && Planner.config.hudGrid) nx = Math.round(nx / 10f) * 10;
            if (bestY == null && Planner.config.hudGrid) ny = Math.round(ny / 10f) * 10;
        }
        layout().x = nx; layout().y = ny; clamp();
    }
    /** Snaps the left edge, center or right edge of a box of size {@code size} at {@code pos} to the nearest line. */
    private static int[] snap(int pos, int size, List<Integer> lines) {
        int best = SNAP + 1; int[] result = null;
        int[] offsets = {0, size / 2, size};
        for (int line : lines) for (int off : offsets) {
            int d = Math.abs(pos + off - line);
            if (d < best) { best = d; result = new int[] {line - off, line}; }
        }
        return result;
    }
    @Override public void mouseMoved(double x, double y) { if (dragging) moveTo(x, y); super.mouseMoved(x, y); }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        boolean was = dragging;
        // Mouse input can be batched between frames. Commit the release position even if no drag callback ran.
        if (dragging && e.button() == InputConstants.MOUSE_BUTTON_LEFT) { moveTo(e.x(), e.y()); Planner.config.save(); }
        dragging = false; setDragging(false); guides.clear(); return super.mouseReleased(e) || was;
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { layout().scale = Math.clamp(layout().scale + dy * .05, .5, 2); clamp(); return true; }
    @Override public boolean keyPressed(KeyEvent e) {
        switch (e.key()) {
            case InputConstants.KEY_LEFT -> layout().x--; case InputConstants.KEY_RIGHT -> layout().x++; case InputConstants.KEY_UP -> layout().y--; case InputConstants.KEY_DOWN -> layout().y++;
            case InputConstants.KEY_TAB -> { var ids = new ArrayList<>(previews.keySet()); selected = ids.get((ids.indexOf(selected) + 1) % ids.size()); }
            case InputConstants.KEY_H -> layout().visible = !layout().visible;
            case InputConstants.KEY_G -> { Planner.config.hudGrid = !Planner.config.hudGrid; Planner.config.save(); }
            case InputConstants.KEY_S -> { Planner.config.hudSnap = !Planner.config.hudSnap; Planner.config.save(); }
            case InputConstants.KEY_R -> { Planner.config.modules.remove(selected); Planner.config.module(selected); }
            case InputConstants.KEY_EQUALS -> layout().scale = Math.min(2, layout().scale + .05);
            case InputConstants.KEY_MINUS -> layout().scale = Math.max(.5, layout().scale - .05);
            default -> { return super.keyPressed(e); }
        } clamp(); return true;
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float delta) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { Planner.config.save(); PlannerHud.rebuild(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
