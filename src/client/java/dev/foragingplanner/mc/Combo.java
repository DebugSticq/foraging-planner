package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.alpha;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Counts hits on trees without a break. Every Foraging XP gain from the action bar counts as one
 * hit; after a few seconds without one the combo ends. Just for fun.
 */
public final class Combo {
	private static final long BREAK_MS = 4_000;
	private static final int[] MILESTONES = {25, 50, 100, 250, 500, 1000};

	private static int combo;
	private static long lastHitMs, hitNs, endedNs;
	private static int endedAt;

	private Combo() {}

	/** Called for every Foraging XP gain. */
	public static void hit() {
		if (!Planner.config.comboCounter) return;
		long now = System.currentTimeMillis();
		if (now - lastHitMs > BREAK_MS) combo = 0;
		combo++;
		lastHitMs = now;
		hitNs = System.nanoTime();

		for (int m : MILESTONES) {
			if (combo == m) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 0.6f + m / 2000f, 0.5f));
				break;
			}
		}

		Progress p = Progress.get();
		if (combo > p.bestCombo) {
			p.bestCombo = combo;
			Progress.changed();
		}
	}

	public static int current() {
		return System.currentTimeMillis() - lastHitMs > BREAK_MS ? 0 : combo;
	}

	private static int color(int value) {
		if (value >= 250) return 0xFFFF55FF;
		if (value >= 100) return 0xFFFFAA00;
		if (value >= 50) return 0xFF55FFFF;
		if (value >= 25) return 0xFF55FF55;
		return 0xFFFFFFFF;
	}

	public static void draw(GuiGraphicsExtractor g) {
		if (!Planner.config.comboCounter) return;
		var client = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		boolean running = now - lastHitMs <= BREAK_MS && combo >= 3;

		if (!running && combo >= 3 && endedNs == 0) {
			endedNs = System.nanoTime();
			endedAt = combo;
		}
		if (running) endedNs = 0;

		int value;
		double a;
		if (running) {
			value = combo;
			// Fades slightly as the break timer runs out, as a hint to keep going.
			a = 1 - Math.max(0, (now - lastHitMs - 2500) / 1500.0) * 0.6;
		} else if (endedNs != 0 && System.nanoTime() - endedNs < 1_200_000_000L) {
			value = endedAt;
			a = 1 - (System.nanoTime() - endedNs) / 1.2e9;
		} else {
			return;
		}

		var font = client.font;
		String number = "x" + value;
		double pop = Planner.config.animations && running ? Math.max(0, 1 - (System.nanoTime() - hitNs) / 150e6) : 0;
		float scale = (float) (1.6 + pop * 0.5);
		int cx = client.getWindow().getGuiScaledWidth() / 2 + 60;
		int cy = client.getWindow().getGuiScaledHeight() / 2 - 22;
		int color = alpha(color(value), Math.clamp(a, 0, 1));

		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		// A small tilt on every hit.
		if (Planner.config.animations) pose.rotate((float) (Math.sin(System.nanoTime() / 120e6) * 0.03 * pop));
		g.text(font, number, -font.width(number) / 2, -4, color, true);
		pose.popMatrix();

		String label = running ? "COMBO" : Planner.tr("combo.ended");
		g.text(font, label, cx - font.width(label) / 2, cy + 12, alpha(0xFFAAAAAA, Math.clamp(a, 0, 1)), true);
	}
}
