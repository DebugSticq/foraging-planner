package dev.foragingplanner.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import java.util.function.BooleanSupplier;

/** Pixel-edged cards with native focus, narration and animated switch state. */
public final class ForestButton extends Button {
    private final BooleanSupplier selected;
    private Item icon;
    private Component description = Component.empty();
    private boolean toggle;
    private boolean navigation;
    private double hover;
    private double switchPosition;
    private long lastFrame = System.nanoTime();

    public ForestButton(int x, int y, int width, int height, Component text, OnPress action, BooleanSupplier selected) {
        super(x, y, width, height, text, action, DEFAULT_NARRATION);
        this.selected = selected;
        switchPosition = selected.getAsBoolean() ? 1 : 0;
    }

    public ForestButton details(Item icon, Component description) {
        this.icon = icon;
        this.description = description;
        return this;
    }

    public ForestButton toggle() { toggle = true; return this; }
    public ForestButton navigation() { navigation = true; return this; }

    @Override
    protected MutableComponent createNarrationMessage() {
        if (!toggle) return super.createNarrationMessage();
        return getMessage().copy().append(" ").append(Planner.tr(selected.getAsBoolean() ? "settings.on" : "settings.off"));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        double blend = Planner.config.animations ? 1 - Math.exp(-(now - lastFrame) / 70_000_000.0) : 1;
        hover += ((isHoveredOrFocused() ? 1 : 0) - hover) * blend;
        boolean on = selected.getAsBoolean();
        switchPosition += ((on ? 1 : 0) - switchPosition) * blend;
        lastFrame = now;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int base = navigation && on ? 0xFF384A35 : ForestTheme.CARD;
        int surface = ForestTheme.mix(base, 0xFF3B5140, hover * .65);
        int border = isFocused() ? ForestTheme.GOLD : ForestTheme.mix(ForestTheme.BORDER, ForestTheme.LEAF, hover * .6);
        ForestTheme.panel(g, x, y, w, h, surface, border);
        if (on && navigation) g.fill(x + 1, y + 4, x + 3, y + h - 4, ForestTheme.GOLD);
        var font = Minecraft.getInstance().font;
        if (icon == null) {
            g.centeredText(font, font.plainSubstrByWidth(getMessage().getString(), w - 12),
                    x + w / 2, y + (h - font.lineHeight) / 2, active ? ForestTheme.TEXT : ForestTheme.MUTED);
            return;
        }
        boolean compact = navigation || h < 40;
        int iconY = y + (compact ? (h - 16) / 2 : 9);
        g.fill(x + 7, iconY - 1, x + 25, iconY + 17, 0xFF18271F);
        ForestTheme.icon(g, icon, x + 8, iconY);
        int labelX = x + 31;
        int labelWidth = w - 40 - (toggle ? 30 : 0);
        g.text(font, font.plainSubstrByWidth(getMessage().getString(), labelWidth), labelX,
                y + (compact ? (h - font.lineHeight) / 2 : 9), active ? ForestTheme.TEXT : ForestTheme.MUTED);
        if (!compact && !description.getString().isEmpty()) {
            g.text(font, font.plainSubstrByWidth(description.getString(), w - 17 - (toggle ? 34 : 0)),
                    x + 9, y + h - 17, ForestTheme.MUTED);
        }
        if (toggle) {
            int tx = x + w - 37, ty = compact ? y + (h - 14) / 2 : y + h - 20;
            g.fill(tx, ty + 1, tx + 28, ty + 13, ForestTheme.mix(0xFF17261F, 0xFF66834F, switchPosition));
            g.fill(tx + 1, ty, tx + 27, ty + 14, ForestTheme.mix(0xFF17261F, 0xFF66834F, switchPosition));
            int thumb = tx + 2 + (int) Math.round(switchPosition * 14);
            g.fill(thumb, ty + 2, thumb + 10, ty + 12, on ? 0xFFE3EBCB : 0xFF899589);
        } else if (!navigation && h >= 40) {
            g.text(font, ">", x + w - 13, y + h - 17, ForestTheme.GOLD);
        }
    }
}
