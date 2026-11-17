package dev.foragingplanner.mc;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import dev.foragingplanner.core.ContestMath;
import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.Goal;
import dev.foragingplanner.core.HotfPlanner;
import dev.foragingplanner.core.PlayerStats;
import dev.foragingplanner.core.TreeReport;

/** The /fp command and its subcommands. Everything here only prints or changes the mod's own settings. */
public final class Commands {
	private static final int SUCCESS = 1;
	private static final int SHOWN_UPGRADES = 5;

	private Commands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("fp")
				.executes(context -> help(context.getSource()));

		root.then(ClientCommands.literal("dashboard").executes(context -> { JournalScreen.requestOpen(); return SUCCESS; }));
        root.then(ClientCommands.literal("hunting").executes(context -> { JournalScreen.requestHunting(); return SUCCESS; }));
        root.then(ClientCommands.literal("profile").then(ClientCommands.argument("name", StringArgumentType.word()).executes(context -> {
            ProfileStore.select(StringArgumentType.getString(context, "name"));
            context.getSource().sendFeedback(Component.literal("[FP] " + ProfileStore.label())); return SUCCESS;
        })));
        root.then(ClientCommands.literal("settings").executes(context -> { SettingsScreen.requestOpen(); return SUCCESS; }));
        root.then(ClientCommands.literal("gear").executes(context -> { GearScreen.requestOpen(); return SUCCESS; }));
        root.then(ClientCommands.literal("overview").executes(context -> { StatsScreen.requestOpen(0); return SUCCESS; }));
        root.then(ClientCommands.literal("goals").executes(context -> { StatsScreen.requestOpen(1); return SUCCESS; }));
        root.then(ClientCommands.literal("contests").executes(context -> { StatsScreen.requestOpen(3); return SUCCESS; }));
        root.then(ClientCommands.literal("drops").executes(context -> { StatsScreen.requestOpen(4); return SUCCESS; }));
        root.then(ClientCommands.literal("sell").executes(context -> { StatsScreen.requestOpen(6); return SUCCESS; }));
        root.then(ClientCommands.literal("alarms").executes(context -> { StatsScreen.requestOpen(8); return SUCCESS; }));
        root.then(ClientCommands.literal("share").executes(context -> {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(ProfileExport.text());
            context.getSource().sendFeedback(Component.literal("§a[FP] §f" + Planner.tr("stats.share_done_text"))); return SUCCESS;
        }));
        root.then(ClientCommands.literal("wiki")
                .then(ClientCommands.argument("term", StringArgumentType.greedyString())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Lexicon.names(), builder))
                        .executes(context -> { Lexicon.show(StringArgumentType.getString(context, "term"), context.getSource()::sendFeedback); return SUCCESS; })));
        root.then(ClientCommands.literal("pv")
                .then(ClientCommands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(PvClient.suggestions(), builder))
                        .executes(context -> { PvScreen.requestOpen(StringArgumentType.getString(context, "player")); return SUCCESS; })));
        root.then(ClientCommands.literal("routes").executes(context -> { StatsScreen.requestOpen(9); return SUCCESS; }));
        root.then(ClientCommands.literal("sounds")
                .executes(context -> { Sounds.list(context.getSource()::sendFeedback); return SUCCESS; })
                .then(ClientCommands.literal("toggle").then(ClientCommands.argument("id", StringArgumentType.greedyString())
                        .executes(context -> {
                            String id = StringArgumentType.getString(context, "id");
                            boolean muted = Sounds.toggle(id);
                            context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("§a[FP] §7" + Planner.tr(muted ? "sounds.muted" : "sounds.unmuted", id)));
                            return SUCCESS;
                        }))));
        root.then(ClientCommands.literal("version").executes(context -> {
            context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("§a[FP] §fForaging Planner §e" + ModVersion.get()));
            return SUCCESS;
        }));
        root.then(ClientCommands.literal("bells")
                .executes(context -> { Bells.list(context.getSource()::sendFeedback); return SUCCESS; })
                .then(ClientCommands.literal("reset").executes(context -> { Bells.reset(); say(context.getSource(), "bell.reset_done"); return SUCCESS; }))
                .then(ClientCommands.literal("toggle").then(ClientCommands.argument("number", StringArgumentType.word())
                        .executes(context -> { Bells.toggle(StringArgumentType.getString(context, "number"), context.getSource()::sendFeedback); return SUCCESS; }))));
        root.then(ClientCommands.literal("perf")
                .executes(context -> { Perf.report(context.getSource()::sendFeedback); return SUCCESS; })
                .then(ClientCommands.literal("reset").executes(context -> { Perf.reset(); say(context.getSource(), "perf.reset_done"); return SUCCESS; })));
        root.then(ClientCommands.literal("settings")
                .then(ClientCommands.literal("export").executes(context -> { SettingsShare.exportToClipboard(); say(context.getSource(), "share.copied_text"); return SUCCESS; }))
                .then(ClientCommands.literal("import").executes(context -> {
                    int n = SettingsShare.importFromClipboard();
                    context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(n < 0 ? "§c[FP] " + Planner.tr("share.bad_text") : "§a[FP] §f" + Planner.tr("share.imported_text", n)));
                    return SUCCESS; }))
                .then(ClientCommands.literal("undo").executes(context -> { say(context.getSource(), SettingsShare.undo() ? "share.undone" : "share.no_backup"); return SUCCESS; })));
        root.then(ClientCommands.literal("safari").executes(context -> { Safari.history(context.getSource()::sendFeedback); return SUCCESS; }));
        root.then(ClientCommands.literal("help").executes(context -> { Help.show(context.getSource()::sendFeedback); return SUCCESS; }));
        root.then(ClientCommands.literal("setup").executes(context -> { SettingsScreen.requestSetup(); return SUCCESS; }));
        root.then(ClientCommands.literal("top").executes(context -> { StatsScreen.requestOpen(11); return SUCCESS; }));
        root.then(ClientCommands.literal("chapters").executes(context -> { StatsScreen.requestOpen(10); return SUCCESS; }));
        root.then(ClientCommands.literal("fusion").executes(context -> {
            context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("§a[FP] §7" + Planner.tr("fusion.link") + " ")
                    .append(net.minecraft.network.chat.Component.literal("§b§nskyshards.com").withStyle(style -> style
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create("https://skyshards.com/")))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal("https://skyshards.com/"))))));
            return SUCCESS;
        }));
        root.then(ClientCommands.literal("ghost").then(ClientCommands.literal("reset").executes(context -> {
            Routes.Route route = Routes.active();
            if (route != null) Routes.resetGhost(route);
            context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("§a[FP] §7" + Planner.tr(route == null ? "route.none_active" : "route.ghost_reset")));
            return SUCCESS;
        })));
        root.then(ClientCommands.literal("route")
                .executes(context -> { StatsScreen.requestOpen(9); return SUCCESS; })
                .then(ClientCommands.literal("new").executes(context -> { Routes.startRecording(Planner.tr("route.default_name", Routes.here().size() + 1)); return SUCCESS; })
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(context -> {
                            Routes.startRecording(StringArgumentType.getString(context, "name")); return SUCCESS; })))
                .then(ClientCommands.literal("add").executes(context -> { Routes.addPoint(); return SUCCESS; }))
                .then(ClientCommands.literal("warp").executes(context -> { Routes.addWarpPoint(); return SUCCESS; }))
                .then(ClientCommands.literal("undo").executes(context -> { Routes.undo(); return SUCCESS; }))
                .then(ClientCommands.literal("save").executes(context -> { Routes.finishRecording(); return SUCCESS; }))
                .then(ClientCommands.literal("cancel").executes(context -> { Routes.cancelRecording(); return SUCCESS; }))
                .then(ClientCommands.literal("next").executes(context -> { Routes.skip(); return SUCCESS; }))
                .then(ClientCommands.literal("off").executes(context -> { Routes.activate(null); return SUCCESS; }))
                .then(ClientCommands.literal("import").executes(context -> {
                    Routes.ImportResult result = Routes.importText(Routes.clipboard());
                    context.getSource().sendFeedback(Component.literal(result.error() != null ? "§c[FP] " + result.error()
                            : "§a[FP] §f" + Planner.tr("stats.route_imported_text", result.routes(), result.points()))); return SUCCESS; }))
                .then(ClientCommands.literal("export").executes(context -> {
                    Routes.Route active = Routes.active();
                    if (active == null) { context.getSource().sendFeedback(Component.literal("§c[FP] " + Planner.tr("route.no_active"))); return SUCCESS; }
                    Routes.copy(Routes.exportSkyblocker(active));
                    context.getSource().sendFeedback(Component.literal("§a[FP] §f" + Planner.tr("stats.route_copied_sb"))); return SUCCESS; })));
        root.then(ClientCommands.literal("preset").then(ClientCommands.argument("name", StringArgumentType.word()).executes(context -> {
            HudPresets.apply(StringArgumentType.getString(context, "name")); return SUCCESS;
        })));
        root.then(ClientCommands.literal("rates").executes(context -> {
            context.getSource().sendFeedback(Component.literal(SessionRates.line())); return SUCCESS;
        }).then(ClientCommands.literal("reset").executes(context -> { SessionRates.reset(); return SUCCESS; })));
        root.then(ClientCommands.literal("hotf").executes(context -> hotf(context.getSource())));

		root.then(ClientCommands.literal("plan")
				.executes(context -> plan(context.getSource(), Planner.config.whispers))
				.then(ClientCommands.argument("whispers", LongArgumentType.longArg(0))
						.executes(context -> plan(context.getSource(), LongArgumentType.getLong(context, "whispers")))));

		root.then(ClientCommands.literal("tree").executes(context -> tree(context.getSource())));
		root.then(ClientCommands.literal("contest").executes(context -> contest(context.getSource())));

		root.then(ClientCommands.literal("goal")
				.then(ClientCommands.argument("goal", StringArgumentType.word())
						.suggests((context, builder) -> {
							for (Goal goal : Goal.values()) builder.suggest(goal.id());
							return builder.buildFuture();
						})
						.executes(context -> goal(context.getSource(), StringArgumentType.getString(context, "goal")))));

		root.then(ClientCommands.literal("usetree")
				.then(ClientCommands.argument("tree", StringArgumentType.word())
						.suggests((context, builder) -> {
							builder.suggest("auto");
							for (GameData.Tree tree : Planner.data.trees) builder.suggest(tree.id);
							return builder.buildFuture();
						})
						.executes(context -> useTree(context.getSource(), StringArgumentType.getString(context, "tree")))));

		root.then(ClientCommands.literal("stats")
				.executes(context -> showStats(context.getSource()))
				.then(ClientCommands.literal("auto").executes(context -> autoStats(context.getSource())))
				.then(ClientCommands.argument("sweep", DoubleArgumentType.doubleArg(0))
						.then(ClientCommands.argument("fortune", DoubleArgumentType.doubleArg(0))
								.then(ClientCommands.argument("wisdom", DoubleArgumentType.doubleArg(0))
										.executes(context -> manualStats(context.getSource(),
												DoubleArgumentType.getDouble(context, "sweep"),
												DoubleArgumentType.getDouble(context, "fortune"),
												DoubleArgumentType.getDouble(context, "wisdom")))))));

		root.then(ClientCommands.literal("hud")
				.executes(context -> toggleHud(context.getSource()))
				.then(ClientCommands.argument("x", IntegerArgumentType.integer(0))
						.then(ClientCommands.argument("y", IntegerArgumentType.integer(0))
								.executes(context -> moveHud(context.getSource(),
										IntegerArgumentType.getInteger(context, "x"),
										IntegerArgumentType.getInteger(context, "y"))))));

		root.then(ClientCommands.literal("data")
				.then(ClientCommands.literal("export").executes(context -> exportData(context.getSource())))
				.then(ClientCommands.literal("reload").executes(context -> reloadData(context.getSource()))));

		root.then(ClientCommands.literal("debug").executes(context -> debug(context.getSource())));

		dispatcher.register(root);
	}

	private static void say(FabricClientCommandSource source, String key, Object... args) {
		source.sendFeedback(Planner.message(key, args));
	}

	private static void line(FabricClientCommandSource source, String key, Object... args) {
		source.sendFeedback(Component.literal(Planner.tr(key, args)));
	}

	private static int help(FabricClientCommandSource source) {
		say(source, "help.title");
		for (int i = 1; i <= 10; i++) line(source, "help." + i);
		return SUCCESS;
	}

	/** Explains why nothing can be calculated yet. Returns true when the caller should stop. */
	private static boolean missingStats(FabricClientCommandSource source) {
		if (SkyBlockReader.statsKnown()) return false;

		say(source, "error.no_stats");
		return true;
	}

	private static int hotf(FabricClientCommandSource source) {
		if (missingStats(source)) return 0;

		if (Planner.config.perkLevels.isEmpty()) {
			say(source, "error.hotf_unread");
			return 0;
		}

		List<HotfPlanner.Upgrade> upgrades = Planner.upgrades();
		say(source, "hotf.title", Planner.goal().id(),
				Planner.config.whispers < 0 ? "?" : Planner.whole(Planner.config.whispers));

		int shown = 0;

		for (HotfPlanner.Upgrade upgrade : upgrades) {
			if (upgrade.gain() <= 0 || shown >= SHOWN_UPGRADES) break;
			shown++;

			line(source, Planner.budget(upgrade.perk()) >= upgrade.cost() ? "hotf.row_affordable" : "hotf.row",
					shown, upgrade.perk().name, upgrade.fromLevel(), upgrade.fromLevel() + 1,
					Planner.whole(upgrade.cost()), String.format(Locale.US, "%.2f", upgrade.gainPercent()));
		}

		if (shown == 0) line(source, "hotf.nothing");
		line(source, "hotf.footer");
		return SUCCESS;
	}

	private static int plan(FabricClientCommandSource source, long budget) {
		if (missingStats(source)) return 0;

		if (Planner.config.perkLevels.isEmpty() || budget < 0) {
			say(source, "error.hotf_unread");
			return 0;
		}

		HotfPlanner.Plan plan = HotfPlanner.plan(Planner.data, Planner.config.perkLevels,
				SkyBlockReader.stats(), Planner.objective(), budget, Planner.config.desertWhispers, Planner.config.tokens, Planner.config.hotfTier);

		say(source, "plan.title", Planner.whole(budget), Planner.goal().id());

		if (plan.steps().isEmpty()) {
			line(source, "hotf.nothing");
			return SUCCESS;
		}

		for (HotfPlanner.PlanStep step : plan.steps()) {
			line(source, "plan.row", step.perk().name, step.fromLevel(), step.toLevel(), Planner.whole(step.cost()) + " " + step.perk().currency + (step.fromLevel() == 0 ? " + 1 Token" : ""));
		}

		line(source, "journal.budgets", Planner.whole(plan.forestLeft()), Planner.whole(plan.desertLeft()));
        line(source, "plan.total", Planner.whole(plan.totalCost()), Planner.whole(plan.leftover()),
				String.format(Locale.US, "%.1f", plan.gainPercent()));
		return SUCCESS;
	}

	private static int tree(FabricClientCommandSource source) {
		if (missingStats(source)) return 0;

		PlayerStats stats = SkyBlockReader.stats();
		say(source, "tree.title", Planner.decimal(stats.sweep()), Planner.decimal(stats.fortune()), Planner.decimal(stats.wisdom()));

		for (TreeReport.Row row : Planner.treeRows()) {
			String next = row.sweepToNext() < 0
					? Planner.tr("hud.tree_max")
					: Planner.tr("hud.tree_next", Planner.decimal(row.sweepToNext()));

			line(source, row.best() ? "tree.row_best" : "tree.row", row.tree().name, Planner.decimal(row.logs()),
					Planner.tr(row.measured() ? "tree.measured" : "tree.estimated"),
					Planner.whole(row.xpPerHit()), Planner.decimal(row.dropsPerHit()), next);

			Double price = Planner.PRICES.get(row.tree().bazaarId);
			if (PlannerHud.showsCoins() && price != null) {
				line(source, "tree.coins", Planner.decimal(price), Planner.whole(row.dropsPerHit() * price));
			}
		}

		line(source, "tree.footer", Planner.goal().id());
		return SUCCESS;
	}

	private static int contest(FabricClientCommandSource source) {
		if (!ContestTracker.active()) {
			say(source, "error.no_contest");
			return 0;
		}

		ContestMath.Status status = ContestTracker.status();
		int seconds = ContestTracker.secondsLeft();

		say(source, "hud.contest", ContestTracker.host(), seconds / 60, String.format("%02d", seconds % 60));
		line(source, "hud.contest_points", Planner.whole(ContestTracker.points()),
				status.current() == null ? "-" : status.current().name);

		if (status.next() != null) {
			line(source, "hud.contest_next", status.next().name, Planner.whole(status.pointsToNext()),
					Double.isInfinite(status.neededPerMin()) ? "-" : Planner.whole(Math.ceil(status.neededPerMin())),
					Planner.whole(ContestTracker.pointsPerMinute()));
		}

		if (status.projectedBracket() != null) {
			line(source, "hud.contest_projection", Planner.whole(status.projected()), status.projectedBracket().name);
		}

		return SUCCESS;
	}

	private static int goal(FabricClientCommandSource source, String text) {
		Goal goal = Goal.parse(text);

		if (goal == null) {
			say(source, "error.goal");
			return 0;
		}

		Planner.config.goal = goal.id();
		Planner.config.save();
		say(source, "goal.set", goal.id());

		if (goal == Goal.COINS && Planner.PRICES.isEmpty()) line(source, "goal.no_prices");
		return SUCCESS;
	}

	private static int useTree(FabricClientCommandSource source, String text) {
		if (!"auto".equalsIgnoreCase(text) && Planner.data.tree(text) == null) {
			say(source, "error.tree");
			return 0;
		}

		Planner.config.tree = text.toLowerCase(Locale.ROOT);
		Planner.config.save();
		say(source, "usetree.set", Planner.config.tree);
		return SUCCESS;
	}

	private static int showStats(FabricClientCommandSource source) {
		if (missingStats(source)) return 0;

		PlayerStats stats = SkyBlockReader.stats();
		say(source, Planner.config.manualStats ? "stats.manual" : "stats.auto",
				Planner.decimal(stats.sweep()), Planner.decimal(stats.fortune()), Planner.decimal(stats.wisdom()));
		return SUCCESS;
	}

	private static int manualStats(FabricClientCommandSource source, double sweep, double fortune, double wisdom) {
		Planner.config.manualStats = true;
		Planner.config.manualSweep = sweep;
		Planner.config.manualFortune = fortune;
		Planner.config.manualWisdom = wisdom;
		Planner.config.save();
		return showStats(source);
	}

	private static int autoStats(FabricClientCommandSource source) {
		Planner.config.manualStats = false;
		Planner.config.save();
		say(source, "stats.auto_on");
		return SUCCESS;
	}

	private static int toggleHud(FabricClientCommandSource source) {
		Planner.config.hudEnabled = !Planner.config.hudEnabled;
		Planner.config.save();
		say(source, Planner.config.hudEnabled ? "hud.on" : "hud.off");
		return SUCCESS;
	}

	private static int moveHud(FabricClientCommandSource source, int x, int y) {
		Planner.config.hudX = x;
		Planner.config.hudY = y;
		Planner.config.save();
		say(source, "hud.moved", x, y);
		return SUCCESS;
	}

	private static int exportData(FabricClientCommandSource source) {
		try {
			say(source, DataLoader.export() ? "data.exported" : "data.exists", DataLoader.overrideFile().toString());
			return SUCCESS;
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not export game data", e);
			say(source, "error.export", e.getMessage());
			return 0;
		}
	}

	private static int reloadData(FabricClientCommandSource source) {
		Patterns.clear();
		Planner.data = DataLoader.load();
		LearnedSweep.captureDefaults();
		HotfMenuReader.applyRequirements();
		say(source, "data.reloaded", Planner.data.hotf.perks.size(), Planner.data.trees.size());
		return SUCCESS;
	}

	/** Prints what the mod currently reads, so a changed Hypixel text can be matched in gamedata.json. */
	private static int debug(FabricClientCommandSource source) {
		say(source, "debug.title", SkyBlockReader.inForagingArea(), SkyBlockReader.statsKnown());
		line(source, "debug.sidebar");
		for (String text : SkyBlockReader.sidebar()) source.sendFeedback(Component.literal("§7 | §f" + text));

		line(source, "debug.tab");
		for (String text : SkyBlockReader.tab()) {
			// The tab list also holds every player name; only lines with a colon carry information.
			if (text.contains(":")) source.sendFeedback(Component.literal("§7 | §f" + text));
		}

		line(source, "debug.perks", Planner.config.perkLevels.toString());
		return SUCCESS;
	}

}
