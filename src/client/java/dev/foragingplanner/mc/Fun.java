package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.alpha;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

/**
 * Small extras just for fun: falling leaves and "Timber!" when a tree is cut down, confetti for
 * rare drops, and the season shown behind the settings menu.
 */
public final class Fun {
	private static final Random RANDOM = new Random();

	private Fun() {}

	// Timber!

	private record Timber(Vec3 pos, long startMs) {}

	private static final List<Timber> TIMBER = new ArrayList<>();

	/** A tree the player helped with reached 100 %. */
	public static void treeFelled(Vec3 stand, String tree) {
		if (!Planner.config.treeFallEffect) return;
		var client = Minecraft.getInstance();
		if (client.level == null) return;
		Vec3 top = stand.add(0, 1.5, 0);
		int[] colors = switch (tree) {
			case "mangrove" -> new int[] {0x4E8F3A, 0x6FAF4B, 0x3B6E2A};
			case "fig" -> new int[] {0x6E9E3F, 0x8DBA52, 0xB5C95A};
			default -> new int[] {0xC9A0DC, 0xE7C6F2, 0x9B7BC4};
		};
		for (int i = 0; i < 46; i++) {
			double x = top.x + (RANDOM.nextDouble() - 0.5) * 7;
			double y = top.y + RANDOM.nextDouble() * 5;
			double z = top.z + (RANDOM.nextDouble() - 0.5) * 7;
			client.level.addParticle(ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, colors[i % colors.length]), x, y, z, 0, -0.02, 0);
		}
		for (int i = 0; i < 8; i++) {
			client.level.addParticle(ParticleTypes.POOF, top.x + (RANDOM.nextDouble() - 0.5) * 2, stand.y + 0.3, top.z + (RANDOM.nextDouble() - 0.5) * 2, 0, 0.03, 0);
		}
		client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BAMBOO_BREAK, 0.7f, 0.8f));
		TIMBER.add(new Timber(stand.add(0, 3, 0), System.currentTimeMillis()));
	}

	// Confetti

	private static final class Piece {
		double x, y, vx, vy, spin, angle;
		int color, w, h;
	}

	private static final List<Piece> CONFETTI = new ArrayList<>();
	private static final int[] CONFETTI_COLORS = {0xFFFFD34D, 0xFFFF6B6B, 0xFF6BD4FF, 0xFF86E07A, 0xFFE58CFF, 0xFFFFFFFF, 0xFFFFA94D};
	private static long confettiLastNs;

	/** A burst of confetti from the top of the screen. */
	public static void confetti() {
		if (!Planner.config.confetti) return;
		var client = Minecraft.getInstance();
		int w = client.getWindow().getGuiScaledWidth();
		for (int i = 0; i < 110 && CONFETTI.size() < 400; i++) {
			Piece p = new Piece();
			p.x = w * (0.15 + RANDOM.nextDouble() * 0.7);
			p.y = -10 - RANDOM.nextDouble() * 60;
			p.vx = (RANDOM.nextDouble() - 0.5) * 90;
			p.vy = 20 + RANDOM.nextDouble() * 70;
			p.spin = (RANDOM.nextDouble() - 0.5) * 10;
			p.angle = RANDOM.nextDouble() * Math.PI;
			p.color = CONFETTI_COLORS[RANDOM.nextInt(CONFETTI_COLORS.length)];
			p.w = 2 + RANDOM.nextInt(3);
			p.h = 3 + RANDOM.nextInt(3);
			CONFETTI.add(p);
		}
	}

	/** HUD layer: confetti and the floating "Timber!". */
	public static void draw(GuiGraphicsExtractor g) {
		var client = Minecraft.getInstance();
		long nowNs = System.nanoTime();
		double dt = confettiLastNs == 0 ? 0 : Math.min(0.05, (nowNs - confettiLastNs) / 1e9);
		confettiLastNs = nowNs;

		if (!CONFETTI.isEmpty()) {
			int h = client.getWindow().getGuiScaledHeight();
			var pose = g.pose();
			for (int i = CONFETTI.size() - 1; i >= 0; i--) {
				Piece p = CONFETTI.get(i);
				p.vy = Math.min(p.vy + 60 * dt, 75);
				p.vx *= 1 - 0.8 * dt;
				p.x += (p.vx + Math.sin(nowNs / 3e8 + i) * 12) * dt;
				p.y += p.vy * dt;
				p.angle += p.spin * dt;
				if (p.y > h + 10) {
					CONFETTI.remove(i);
					continue;
				}
				pose.pushMatrix();
				pose.translate((float) p.x, (float) p.y);
				pose.rotate((float) p.angle);
				// Flipping in the air: the piece gets thinner and wider again.
				int flip = Math.max(1, (int) Math.round(p.h * Math.abs(Math.cos(p.angle * 1.7))));
				g.fill(-p.w / 2, -flip / 2, p.w - p.w / 2, flip - flip / 2 + 1, p.color);
				pose.popMatrix();
			}
		}

		if (TIMBER.isEmpty() || client.player == null) return;
		long now = System.currentTimeMillis();
		TIMBER.removeIf(t -> now - t.startMs() > 1800);
		for (Timber t : TIMBER) {
			double age = (now - t.startMs()) / 1800.0;
			float[] at = WorldOverlay.project(client, t.pos().add(0, age * 1.5, 0));
			if (at == null) continue;
			double a = age < 0.15 ? age / 0.15 : 1 - Math.max(0, (age - 0.6) / 0.4);
			float scale = (float) (1.6 + Math.sin(Math.min(1, age * 4) * Math.PI) * 0.4);
			String text = Planner.tr("fun.timber");
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(at[0], at[1]);
			pose.scale(scale, scale);
			pose.rotate((float) (Math.sin(age * 12) * 0.06));
			g.text(client.font, text, -client.font.width(text) / 2, -4, alpha(0xFF9BE36B, Math.clamp(a, 0, 1)), true);
			pose.popMatrix();
		}
	}

	// Seasons behind the settings menu

	public enum Season { WINTER, SPRING, SUMMER, AUTUMN }

	public static Season season() {
		String choice = Planner.config.seasonTheme;
		return switch (choice) {
			case "winter" -> Season.WINTER;
			case "spring" -> Season.SPRING;
			case "summer" -> Season.SUMMER;
			case "autumn" -> Season.AUTUMN;
			default -> {
				int month = LocalDate.now().getMonthValue();
				yield month == 12 || month <= 2 ? Season.WINTER : month <= 5 ? Season.SPRING : month <= 8 ? Season.SUMMER : Season.AUTUMN;
			}
		};
	}

	/** True when the theme is off and the old fireflies should be drawn. */
	public static boolean fireflies() {
		return Planner.config.seasonTheme.equals("off") || season() == Season.SUMMER;
	}

	private static boolean christmas() {
		MonthDay d = MonthDay.now();
		return d.getMonthValue() == 12 && d.getDayOfMonth() >= 20 && d.getDayOfMonth() <= 26;
	}

	/**
	 * Snow, spring petals or autumn leaves drifting down behind the settings panel. Everything comes
	 * from the time and a seed per piece, so nothing has to be stored.
	 */
	public static void backdrop(GuiGraphicsExtractor g, int width, int height, double t, double open) {
		Season season = season();
		int count = season == Season.WINTER ? 70 : 36;
		var pose = g.pose();
		for (int i = 0; i < count; i++) {
			double seed = (i + 1) * 7.137;
			double bx = fract(Math.sin(seed) * 43758.5453);
			double by = fract(Math.sin(seed * 1.9) * 24634.6345);
			double speed = 0.5 + fract(Math.sin(seed * 3.1) * 9631.13);
			double fall = season == Season.WINTER ? 0.035 : 0.025;
			double y = fract(by + t * fall * speed) * (height + 20) - 10;
			double sway = Math.sin(t * (0.6 + speed * 0.5) + i) * (season == Season.WINTER ? 10 : 22);
			double x = bx * width + sway;
			switch (season) {
				case WINTER -> {
					int size = speed > 1.1 ? 2 : 1;
					int c = christmas() && i % 9 == 0 ? (i % 2 == 0 ? 0xFFFF6B6B : 0xFF86E07A) : 0xFFFFFFFF;
					g.fill((int) x - size, (int) y - size, (int) x + size, (int) y + size, alpha(c, (0.35 + 0.4 * speed / 1.5) * open));
				}
				case SPRING, AUTUMN -> {
					int[] colors = season == Season.SPRING ? new int[] {0xFFF6B6D2, 0xFFFFD6E7, 0xFFFFFFFF} : new int[] {0xFFE07A2E, 0xFFC4452B, 0xFFE8B23A, 0xFF9C5A2B};
					int c = colors[i % colors.length];
					pose.pushMatrix();
					pose.translate((float) x, (float) y);
					pose.rotate((float) (t * (0.8 + speed) + i));
					int w = season == Season.SPRING ? 2 : 3;
					int flip = Math.max(1, (int) Math.round(2 * Math.abs(Math.cos(t * 1.3 + i))));
					g.fill(-w, -flip, w, flip, alpha(c, 0.55 * open));
					g.fill(-w + 1, -flip - 1, w - 1, flip + 1, alpha(c, 0.35 * open));
					pose.popMatrix();
				}
				default -> {}
			}
		}
	}

	private static double fract(double v) {
		return v - Math.floor(v);
	}
}
