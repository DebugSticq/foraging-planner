package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;
import static dev.foragingplanner.mc.ForestTheme.UI_PANEL;
import static dev.foragingplanner.mc.ForestTheme.UI_TEXT;
import static dev.foragingplanner.mc.ForestTheme.UI_TILE;
import static dev.foragingplanner.mc.ForestTheme.alpha;
import static dev.foragingplanner.mc.ForestTheme.easeOut;
import static dev.foragingplanner.mc.ForestTheme.easeOutBack;
import static dev.foragingplanner.mc.ForestTheme.fit;
import static dev.foragingplanner.mc.ForestTheme.round;
import static dev.foragingplanner.mc.ForestTheme.roundOutline;
import static dev.foragingplanner.mc.ForestTheme.shadow;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Animated cards at the top of the screen for drops, milestones, goals and price alarms. */
public final class Toasts {
	public enum Kind { GOAL, MILESTONE, RARE, ALARM, INFO }

	private record Toast(Kind kind, ItemStack icon, String title, String text, int color, long durationMs) {}

	private static final Deque<Toast> QUEUE = new ArrayDeque<>();
	private static Toast showing;
	private static long shownAt;

	private Toasts() {}

	public static void show(Kind kind, String icon, String title, String text) {
		if (!Planner.config.toasts) return;
		int color = switch (kind) {
			case RARE -> 0xFFFFAA00;
			case MILESTONE -> 0xFFC266FF;
			case GOAL -> 0xFF86BD6E;
			case ALARM -> 0xFFEBC678;
			default -> 0xFF8FB8E0;
		};
		var item = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(icon)).orElse(Items.PAPER);
		QUEUE.add(new Toast(kind, new ItemStack(item), title, text, color, kind == Kind.RARE ? 7000 : 5000));
		if (Planner.config.toastChat) JournalStore.notify(title + " §7" + text);
	}

	private static void sound(Kind kind) {
		if (!Planner.config.toastSound) return;
		SoundEvent event = switch (kind) {
			case RARE -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
			case MILESTONE, GOAL -> SoundEvents.PLAYER_LEVELUP;
			default -> SoundEvents.EXPERIENCE_ORB_PICKUP;
		};
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, 0.6f));
	}

	public static void extract(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();

		if (showing != null && now - shownAt > showing.durationMs()) showing = null;
		if (showing == null && !QUEUE.isEmpty()) {
			showing = QUEUE.poll();
			shownAt = now;
			sound(showing.kind());
		}
		if (showing == null) return;

		var client = Minecraft.getInstance();
		var font = client.font;
		double t = (now - shownAt) / 1000.0;
		double life = showing.durationMs() / 1000.0;
		double in = Planner.config.animations ? easeOutBack(t / 0.45) : 1;
		double out = Planner.config.animations ? 1 - easeOut((t - (life - 0.4)) / 0.4) : 1;
		double a = Math.clamp(Math.min(t / 0.2, out), 0, 1);

		int w = Math.max(170, Math.max(font.width(showing.title()), font.width(showing.text())) + 46);
		w = Math.min(w, client.getWindow().getGuiScaledWidth() - 20);
		int h = 34;
		int x = (client.getWindow().getGuiScaledWidth() - w) / 2;
		int y = (int) Math.round(-h + (h + 8) * in);
		if (out < 1) y = (int) Math.round(8 - (1 - out) * (h + 8));

		shadow(g, x, y, w, h, 8, a);
		round(g, x, y, w, h, 8, alpha(UI_PANEL, a * 0.96));
		roundOutline(g, x, y, w, h, 8, alpha(showing.color(), a * (showing.kind() == Kind.RARE ? 0.6 + 0.4 * Math.sin(t * 6) : 0.7)));

		// A light sweep across the card for rare drops.
		if (showing.kind() == Kind.RARE && Planner.config.animations) {
			int sweepX = x + (int) ((t * 160) % (w + 60)) - 30;
			for (int i = 0; i < 18; i++) {
				int sx = sweepX + i;
				if (sx > x + 2 && sx < x + w - 2) g.fill(sx, y + 2, sx + 1, y + h - 2, alpha(0xFFFFFFFF, a * 0.06 * (1 - Math.abs(i - 9) / 9.0)));
			}
		}

		round(g, x + 7, y + 6, 22, 22, 5, alpha(UI_TILE, a));
		if (a > 0.4) g.item(showing.icon(), x + 10, y + 9);
		g.text(font, fit(font, showing.title(), w - 44), x + 36, y + 8, alpha(showing.color(), a), false);
		g.text(font, fit(font, showing.text(), w - 44), x + 36, y + 20, alpha(UI_TEXT, a), false);

		// Remaining time as a thin line.
		double left = 1 - t / life;
		g.fill(x + 8, y + h - 3, x + 8 + (int) ((w - 16) * Math.max(0, left)), y + h - 2, alpha(showing.color(), a * 0.5));
		if (!QUEUE.isEmpty()) g.text(font, "+" + QUEUE.size(), x + w - 8 - font.width("+" + QUEUE.size()), y + 8, alpha(UI_MUTED, a), false);
	}
}
