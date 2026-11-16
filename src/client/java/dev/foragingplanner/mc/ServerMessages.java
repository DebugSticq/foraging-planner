package dev.foragingplanner.mc;

import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Delivers every server message exactly as the server sent it.
 *
 * <p>Other mods (Skyblocker's status bars, SkyHanni's compact chat) hide or rewrite the action bar
 * and the tree gift messages. Listening on the plain message event misses those. Every "allow"
 * listener is still called with the original text, so this listens there and never blocks anything.
 */
final class ServerMessages {
	interface Listener {
		void onMessage(Component message, boolean overlay);
	}

	private ServerMessages() {}

	static void register(Listener listener) {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			try {
				listener.onMessage(message, overlay);
			} catch (RuntimeException e) {
				Planner.LOGGER.warn("Handling a server message failed", e);
			}

			return true;
		});
	}
}
