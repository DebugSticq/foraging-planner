package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_DIM;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The list of new things per version; each entry leads to its setting or screen. */
public final class WhatsNewScreen extends ForestScreen {
	private final List<String> versions;

	public WhatsNewScreen(Screen parent) {
		this(parent, versionList());
	}

	private WhatsNewScreen(Screen parent, List<String> versions) {
		super(parent, versions.isEmpty() ? new String[] {"-"} : versions.toArray(String[]::new), icons(Math.max(1, versions.size())), 0);
		this.versions = versions;
	}

	private static List<String> versionList() {
		List<String> list = new ArrayList<>(WhatsNew.all().keySet());
		// Newest first.
		list.sort((a, b) -> compare(b, a));
		return list.size() > 8 ? list.subList(0, 8) : list;
	}

	private static int compare(String a, String b) {
		String[] x = a.split("\\."), y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int u = i < x.length ? parse(x[i]) : 0, v = i < y.length ? parse(y[i]) : 0;
			if (u != v) return Integer.compare(u, v);
		}
		return 0;
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D.*", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Item[] icons(int n) {
		Item[] out = new Item[n];
		for (int i = 0; i < n; i++) out[i] = i == 0 ? Items.WRITABLE_BOOK : Items.BOOK;
		return out;
	}

	@Override
	protected String title() {
		return Planner.tr("whatsnew.title");
	}

	@Override
	protected String tagline() {
		return Planner.tr("whatsnew.tagline", ModVersion.plain());
	}

	@Override
	protected String pageName(int page) {
		return versions.isEmpty() ? "–" : versions.get(page) + (versions.get(page).equals(ModVersion.plain()) ? " ★" : "");
	}

	@Override
	protected Item logo() {
		return Items.WRITABLE_BOOK;
	}

	@Override
	protected List<Line> build(int page) {
		List<Line> out = new ArrayList<>();
		subtitle = Planner.tr("whatsnew.about");
		if (versions.isEmpty()) {
			out.add(text(Planner.tr("whatsnew.none"), UI_DIM));
			return out;
		}
		Map<String, List<WhatsNew.Entry>> all = WhatsNew.all();
		for (WhatsNew.Entry e : all.getOrDefault(versions.get(page), List.of())) {
			boolean link = !e.open().isEmpty();
			out.add(row(e.icon(), e.text(), link ? Planner.tr("whatsnew.open") : "", link ? "›" : "", link ? UI_ACCENT : UI_MUTED,
					link ? (lx, w, button) -> WhatsNew.open(minecraft, this, e.open()) : null));
		}
		out.add(spacer(6));
		out.add(button(Planner.tr("whatsnew.changelog"), () -> {
			String url = "https://github.com/debugsticq/foraging-planner/blob/main/CHANGELOG.md";
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a[FP] §9§n" + url)
						.withStyle(st -> st.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))));
			}
			minecraft.keyboardHandler.setClipboard(url);
			Toasts.show(Toasts.Kind.INFO, "writable_book", Planner.tr("whatsnew.link_copied"), "");
		}));
		return out;
	}
}
