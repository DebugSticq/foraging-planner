package dev.foragingplanner.mc;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** Entry point: loads data and settings, then wires every feature to its Fabric event. */
public final class ForagingPlannerClient implements ClientModInitializer {
	/** The sidebar and tab list are read once per second (20 ticks). */
	private static final int REFRESH_INTERVAL_TICKS = 20;

	private int ticks;
	/** A broken refresh would otherwise fill the log once per second. */
	private boolean refreshFailureLogged;

	@Override
	public void onInitializeClient() {
		Planner.config = ModConfig.load();
		Planner.data = DataLoader.load();
		LearnedSweep.load();
		HotfMenuReader.applyRequirements();
		JournalStore.load();
        SessionRates.register();
        LootTracker.register();
        ContestRewards.register();
        GearAdvisor.register();
        MenuReaders.register();
        Routes.register();
        dev.foragingplanner.core.Journal.listener = (metric, amount) -> {
            Progress.count(metric, amount);
            if ("gifts".equals(metric) && amount > 0) Tpm.gift();
        };
        NpcPrices.load();
        HuntingTracker.register();
		SettingsScreen.register();
        TreeAssistant.register();

		HotfMenuReader.register();
		SweepChatListener.register();
		Commands.register();
		Beacon.register();
		MiniMap.register();
		Extras.register();
		HotfTooltip.register();
		LogStripGuard.register();
		// Must be last: it can hide messages, and Fabric stops at the first listener that hides one.
		CompactChat.register();

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Planner.MOD_ID, "panel"), PlannerHud::extract);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Planner.MOD_ID, "world"), (graphics, delta) -> WorldOverlay.extract(graphics));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Planner.MOD_ID, "toasts"), (graphics, delta) -> Toasts.extract(graphics));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(Planner.MOD_ID, "minimap"), (graphics, delta) -> MiniMap.extract(graphics));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SettingsScreen.tick(client);
            TreeAssistant.keys();
            MiniMap.keys();
            HuntingTracker.tick(client);
            if (ticks % 10 == 0) TreeTracker.tick(client);
            Routes.tick(client);
            TreeProgress.tick(client);
            TreeChoice.tick(client);
            if (ticks % 10 == 5) Extras.tick(client);
			if (++ticks % REFRESH_INTERVAL_TICKS != 0) return;

			try {
				SkyBlockReader.refresh(client);
				ProfileStore.tick(client);
                ContestTracker.refresh(SkyBlockReader.sidebar());
				SessionRates.tick(client);
                TreeAssistant.tick(client);
				ContestAlerts.tick();
				Watchers.tick();
				Beacon.tick();
				Levels.tick();
				Afk.tick(client);
				Leaderboard.tick();
				if (!Planner.config.setupDone && SkyBlockReader.inSkyBlock() && client.gui.screen() == null) client.gui.setScreen(new SetupScreen(null));
				HudPresets.tick();
                JournalStore.tick(SkyBlockReader.inForagingArea() && client.player != null && !Afk.idle());
				if (SkyBlockReader.inForagingArea() || SkyBlockReader.inSkyBlock()) BazaarPrices.refreshIfDue();
				PlannerHud.rebuild();
			} catch (RuntimeException e) {
				// A changed Hypixel layout must never crash the game. Log it once and try again next second.
				if (!refreshFailureLogged) Planner.LOGGER.warn("Refresh failed", e);
				refreshFailureLogged = true;
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { SessionRates.flush(); JournalStore.save(); Planner.config.save(); Progress.save(true); TreeTracker.save(); });

		Planner.LOGGER.info("Foraging Planner {} loaded with {} perks and {} trees", ModVersion.get(),
				Planner.data.hotf.perks.size(), Planner.data.trees.size());
	}
}
