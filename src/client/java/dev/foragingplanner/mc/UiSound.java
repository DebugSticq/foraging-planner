package dev.foragingplanner.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Quiet sounds for the mod's menus: click, hover, page, switch, success and error. */
public final class UiSound {
	private static long lastHoverNs, lastNs;

	private UiSound() {}

	private static void play(SoundEvent sound, float pitch, float volume) {
		ModConfig c = Planner.config;
		if (!c.uiSounds) return;
		long now = System.nanoTime();
		// Two sounds in the same moment (a click that also switches) sound like one.
		if (now - lastNs < 25_000_000L) return;
		lastNs = now;
		float v = volume * Math.clamp(c.uiSoundVolume, 0, 100) / 100f;
		if (v <= 0.001f) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, v));
	}

	/** Changes whenever a sound is played; lets callers skip a second sound. */
	public static long stamp() {
		return lastNs;
	}

	public static void click() {
		play(SoundEvents.UI_BUTTON_CLICK.value(), 1.25f, 0.28f);
	}

	/** The pointer moved onto something clickable. */
	public static void hover() {
		if (!Planner.config.uiHoverSounds) return;
		long now = System.nanoTime();
		if (now - lastHoverNs < 70_000_000L) return;
		lastHoverNs = now;
		if (!Planner.config.uiSounds) return;
		float v = 0.08f * Math.clamp(Planner.config.uiSoundVolume, 0, 100) / 100f;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.9f, v));
	}

	public static void page() {
		play(SoundEvents.BOOK_PAGE_TURN, 1.15f, 0.55f);
	}

	public static void toggle(boolean on) {
		play(SoundEvents.NOTE_BLOCK_BELL.value(), on ? 1.6f : 1.1f, 0.22f);
	}

	public static void open() {
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f, 0.18f);
	}

	public static void success() {
		play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.35f, 0.3f);
	}

	public static void error() {
		play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f, 0.35f);
	}
}
