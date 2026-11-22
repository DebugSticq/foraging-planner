package dev.foragingplanner.mc;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Full screen map of the island: drag to move, mouse wheel to zoom (around the mouse), F to follow
 * yourself again. The buttons at the top switch marker types on and off.
 */
public final class MapScreen extends Screen {
	private static double zoom = 2.5;
	private static boolean follow = true;
	private static double centerX, centerZ;

	private java.util.List<MapMarkers.Hit> hits = java.util.List.of();
	private int filterY = 6;
	private int[][] filterBoxes = new int[MapMarkers.FILTERS.length][];

	public MapScreen() {
		super(Component.literal("Map"));
	}

	public static void open() {
		Minecraft.getInstance().gui.setScreen(new MapScreen());
	}

	private void followPlayer() {
		var player = Minecraft.getInstance().player;
		if (player != null) {
			centerX = player.getX();
			centerZ = player.getZ();
		}
	}

	@Override
	protected void init() {
		if (follow) followPlayer();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (follow) followPlayer();
		var font = Minecraft.getInstance().font;
		g.fill(0, 0, width, height, 0xF0101512);
		double sx = width / 2.0, sy = height / 2.0;
		g.enableScissor(0, 0, width, height);
		WorldMap.draw(g, centerX, centerZ, sx, sy, zoom, 0, 0, width, height);
		hits = MapMarkers.draw(g, centerX, centerZ, sx, sy, zoom, true);
		g.disableScissor();

		if (!WorldMap.hasTiles()) {
			String text = Planner.tr(WorldMap.island().isEmpty() ? "map.not_here" : "map.empty");
			g.text(font, text, (width - font.width(text)) / 2, height / 2 + 24, 0xFFDDDDDD, true);
		}

		// Title and filter buttons
		String title = "§a" + (SkyBlockReader.area().isEmpty() ? Planner.tr("map.title") : SkyBlockReader.area());
		ForestTheme.round(g, 6, 6, font.width(title) + 12, 16, 5, ForestTheme.alpha(ForestTheme.UI_PANEL, 0.85));
		g.text(font, title, 12, 10, 0xFFFFFFFF, true);
		int x = 6 + font.width(title) + 18;
		filterY = 6;
		for (int i = 0; i < MapMarkers.FILTERS.length; i++) {
			String f = MapMarkers.FILTERS[i];
			String label = Planner.tr("map.filter_" + f);
			int w = font.width(label) + 16;
			if (x + w > width - 6) break;
			boolean on = MapMarkers.shown(f);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= filterY && mouseY < filterY + 16;
			ForestTheme.round(g, x, filterY, w, 16, 5, ForestTheme.alpha(on ? 0xFF2C4A33 : ForestTheme.UI_PANEL, hover ? 0.95 : 0.8));
			int dot = "trees".equals(f) ? 0xFF8CD58B : "area".equals(f) ? 0xFFFFFFFF : "resources".equals(f) ? 0xFFE0443C : MapMarkers.color(f);
			g.fill(x + 5, filterY + 6, x + 9, filterY + 10, on ? dot : 0xFF555555);
			g.text(font, label, x + 12, filterY + 4, on ? 0xFFFFFFFF : 0xFF888888, false);
			filterBoxes[i] = new int[] {x, filterY, w, 16};
			x += w + 4;
		}

		// Coordinates under the mouse and hints
		double wx = centerX + (mouseX - sx) / zoom, wz = centerZ + (mouseY - sy) / zoom;
		String info = "X " + Math.round(Math.floor(wx)) + "  Z " + Math.round(Math.floor(wz)) + "  ·  " + Planner.tr("map.zoom", String.format(java.util.Locale.ROOT, "%.1f", zoom));
		String hint = Planner.tr("map.hint");
		ForestTheme.round(g, 6, height - 22, font.width(info) + 12, 16, 5, ForestTheme.alpha(ForestTheme.UI_PANEL, 0.85));
		g.text(font, info, 12, height - 18, 0xFFDDDDDD, false);
		g.text(font, hint, width - font.width(hint) - 8, height - 18, 0xAAFFFFFF, true);

		// Name of the marker under the mouse
		MapMarkers.Hit hit = MapMarkers.nearest(hits, mouseX, mouseY);
		if (hit != null) {
			var player = Minecraft.getInstance().player;
			String text = hit.text();
			if (player != null) {
				double hx = centerX + (hit.x() - sx) / zoom, hz = centerZ + (hit.y() - sy) / zoom;
				text += " §7· " + Math.round(Math.hypot(hx - player.getX(), hz - player.getZ())) + "m";
			}
			int w = font.width(text) + 10;
			int tx = Math.min(mouseX + 10, width - w - 4), ty = Math.max(4, mouseY - 18);
			ForestTheme.round(g, tx, ty, w, 14, 4, ForestTheme.alpha(ForestTheme.UI_PANEL, 0.95));
			g.text(font, text, tx + 5, ty + 3, 0xFFFFFFFF, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < filterBoxes.length; i++) {
				int[] b = filterBoxes[i];
				if (b != null && event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
					String f = MapMarkers.FILTERS[i];
					boolean nowOn = Planner.config.mapHidden.remove(f);
					if (!nowOn) Planner.config.mapHidden.add(f);
					Planner.config.save();
					UiSound.toggle(nowOn);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		follow = false;
		centerX -= dragX / zoom;
		centerZ -= dragY / zoom;
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		double sx = width / 2.0, sy = height / 2.0;
		double wx = centerX + (mouseX - sx) / zoom, wz = centerZ + (mouseY - sy) / zoom;
		zoom = Math.clamp(zoom * (vertical > 0 ? 1.25 : 0.8), 0.5, 12);
		if (!follow) {
			centerX = wx - (mouseX - sx) / zoom;
			centerZ = wz - (mouseY - sy) / zoom;
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_F) {
			follow = true;
			followPlayer();
			return true;
		}
		if (MiniMap.isMapKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** World position under the screen centre, for tests. */
	static Vec3 center() {
		return new Vec3(centerX, 0, centerZ);
	}
}
