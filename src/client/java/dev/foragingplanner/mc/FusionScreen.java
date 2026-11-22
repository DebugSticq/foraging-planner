package dev.foragingplanner.mc;

import static dev.foragingplanner.mc.ForestTheme.UI_ACCENT;
import static dev.foragingplanner.mc.ForestTheme.UI_AMBER;
import static dev.foragingplanner.mc.ForestTheme.UI_MUTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Shard fusion planner: the cheapest way to get a number of shards, as a tree of fusions down to
 * the shards to buy, and the shopping list. Recipes from SkyShards (MIT). Only shows a plan.
 */
public final class FusionScreen extends ForestScreen {
	private static final String[] PAGES = {"tree", "shopping"};
	private static String target = "";
	private static long amount = 1;

	public FusionScreen(Screen parent) {
		super(parent, PAGES, new Item[] {Items.PRISMARINE_SHARD, Items.CHEST}, ForestScreen.lastPage(FusionScreen.class, 0));
		ShardFusion.load();
	}

	/** Opens the planner for a shard and an amount. */
	public static FusionScreen forShard(Screen parent, String name, long count) {
		target = name == null ? "" : name;
		amount = Math.max(1, count);
		return new FusionScreen(parent);
	}

	private static String tr(String key, Object... args) {
		return Planner.tr("fusion." + key, args);
	}

	@Override
	protected String menuKey() {
		return null;
	}

	@Override
	protected String title() {
		return tr("screen_title");
	}

	@Override
	protected String tagline() {
		return tr("subtitle");
	}

	@Override
	protected String pageName(int page) {
		return tr("page_" + PAGES[page]);
	}

	@Override
	protected Item logo() {
		return Items.PRISMARINE_SHARD;
	}

	@Override
	protected List<Line> build(int page) {
		List<Line> out = new ArrayList<>();
		out.add(field(tr("shard"), target, 40, value -> {
			target = value;
			rebuildSoon();
		}));
		out.add(field(tr("amount"), String.valueOf(amount), 7, value -> {
			try {
				amount = Math.clamp(Long.parseLong(value.trim()), 1, 1_000_000);
				rebuildSoon();
			} catch (NumberFormatException ignored) {
				// keep the last amount while typing
			}
		}));
		out.add(toggle("emerald", tr("orders"), tr("orders_sub"), () -> Planner.config.fusionOrders, v -> {
			Planner.config.fusionOrders = v;
			Planner.config.save();
			rebuild();
		}));

		if (!ShardFusion.ready()) {
			out.add(text("§7" + tr("loading"), UI_MUTED));
			return out;
		}
		ShardFusion.Shard shard = target.isBlank() ? null : ShardFusion.byName(target);
		if (shard == null) {
			out.add(text("§7" + tr(target.isBlank() ? "pick" : "unknown"), UI_MUTED));
			return out;
		}
		boolean orders = Planner.config.fusionOrders;
		ShardFusion.Node plan = ShardFusion.plan(shard, amount, orders);
		double direct = ShardFusion.price(shard, orders) * amount;

		out.add(header(amount + "x " + shard.name() + " Shard"));
		String directText = Double.isFinite(direct) ? GearScreenFormat.coins(direct) : tr("no_price");
		if (plan.bought()) {
			out.add(row("emerald", tr("best_buy", directText), tr("best_buy_sub"), null, 0, null));
		} else {
			String saving = Double.isFinite(direct) ? tr("saving", GearScreenFormat.coins(direct - plan.cost()), Math.round(100 - 100 * plan.cost() / direct)) : "";
			out.add(row("emerald", tr("best_fuse", GearScreenFormat.coins(plan.cost())), tr("direct", directText) + (saving.isEmpty() ? "" : " · " + saving),
					tr("fusions", ShardFusion.fusions(plan)), UI_ACCENT, null));
		}

		if (page == 0) {
			if (!plan.bought()) {
				out.add(spacer(2));
				addTree(out, plan, 0, orders);
			}
		} else {
			out.add(header(tr("buy_list")));
			for (Map.Entry<ShardFusion.Shard, Long> e : ShardFusion.shopping(plan).entrySet()) {
				ShardFusion.Shard s = e.getKey();
				double each = ShardFusion.price(s, orders);
				out.add(row("prismarine_shard", e.getValue() + "x " + s.name(), rarity(s) + " · " + GearScreenFormat.coins(each) + " " + tr("each"),
						GearScreenFormat.coins(each * e.getValue()), UI_AMBER,
						(x, w, b) -> BazaarHelper.open(s.name() + " Shard", e.getValue())));
			}
			out.add(text("§7" + tr("buy_hint"), UI_MUTED));
		}
		out.add(text("§8" + tr("source"), UI_MUTED));
		return out;
	}

	private void addTree(List<Line> out, ShardFusion.Node node, int depth, boolean orders) {
		String indent = "  ".repeat(Math.min(depth, 6)) + (depth > 0 ? "└ " : "");
		String title = indent + node.amount() + "x " + node.shard().name();
		String sub;
		if (node.bought()) {
			sub = indent + tr("buy_at", GearScreenFormat.coins(ShardFusion.price(node.shard(), orders)));
		} else {
			ShardFusion.Shard a = node.parts().get(0).shard(), b = node.parts().get(1).shard();
			long crafts = (node.amount() + node.recipe().out() - 1) / node.recipe().out();
			sub = indent + tr("fuse_from", crafts, a.fuse() + "x " + a.name(), b.fuse() + "x " + b.name(), node.recipe().out());
		}
		out.add(row(node.bought() ? "emerald" : "prismarine_shard", title, sub, GearScreenFormat.coins(node.cost()), node.bought() ? UI_AMBER : UI_ACCENT, null));
		if (depth < 12) for (ShardFusion.Node part : node.parts()) addTree(out, part, depth + 1, orders);
	}

	private static String rarity(ShardFusion.Shard s) {
		return s.rarity().isEmpty() ? "" : Character.toUpperCase(s.rarity().charAt(0)) + s.rarity().substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	private long rebuildAt;

	/** Rebuilds a moment after typing stops, so the field keeps its focus while typing. */
	private void rebuildSoon() {
		rebuildAt = System.currentTimeMillis() + 600;
	}

	@Override
	public void tick() {
		super.tick();
		if (rebuildAt != 0 && System.currentTimeMillis() >= rebuildAt) {
			rebuildAt = 0;
			rebuild();
		}
		if (ShardFusion.ready() && !wasReady) rebuild();
		wasReady = ShardFusion.ready();
	}

	private boolean wasReady;
}
