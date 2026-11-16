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
        for (int xx = 0; xx < width; xx += 20) g.fill(xx, 0, xx + 1, height, 0x183D6348);
        for (int yy = 0; yy < height; yy += 20) g.fill(0, yy, width, yy + 1, 0x183D6348);
        for (var entry : previews.entrySet()) if (!entry.getKey().equals(selected)) PlannerHud.drawModule(g, entry.getKey(), entry.getValue(), true, false);
        PlannerHud.drawModule(g, selected, previews.get(selected), true, true);
        g.fill(0, height - 64, width, height, 0xEF101B18);
        String label = Planner.tr("journal.module_" + selected) + " · " + layout().x + ", " + layout().y + " · " + Math.round(layout().scale * 100) + "%";
        g.centeredText(font, label, width / 2, height - 59, ForestTheme.GOLD);
        g.centeredText(font, font.plainSubstrByWidth(Planner.tr("journal.hud_help"), width - 12), width / 2, height - 44, ForestTheme.MUTED);
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
    private void moveTo(double x, double y) { layout().x = (int)(x - offsetX); layout().y = (int)(y - offsetY); clamp(); }
    @Override public void mouseMoved(double x, double y) { if (dragging) moveTo(x, y); super.mouseMoved(x, y); }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        boolean was = dragging;
        // Mouse input can be batched between frames. Commit the release position even if no drag callback ran.
        if (dragging && e.button() == InputConstants.MOUSE_BUTTON_LEFT) { moveTo(e.x(), e.y()); Planner.config.save(); }
        dragging = false; setDragging(false); return super.mouseReleased(e) || was;
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { layout().scale = Math.clamp(layout().scale + dy * .05, .5, 2); clamp(); return true; }
    @Override public boolean keyPressed(KeyEvent e) {
        switch (e.key()) {
            case InputConstants.KEY_LEFT -> layout().x--; case InputConstants.KEY_RIGHT -> layout().x++; case InputConstants.KEY_UP -> layout().y--; case InputConstants.KEY_DOWN -> layout().y++;
            case InputConstants.KEY_TAB -> { var ids = new ArrayList<>(previews.keySet()); selected = ids.get((ids.indexOf(selected) + 1) % ids.size()); }
            case InputConstants.KEY_H -> layout().visible = !layout().visible;
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
