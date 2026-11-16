package dev.foragingplanner.mc;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** /fp help: every command, grouped, each one clickable. */
public final class Help {
	private record Cmd(String command, String key) {}

	private static final List<List<Cmd>> GROUPS = List.of(
			List.of(new Cmd("/fp settings", "settings"), new Cmd("/fp setup", "setup"), new Cmd("/fp stats", "stats"), new Cmd("/fp overview", "overview"), new Cmd("/fp hud", "hud")),
			List.of(new Cmd("/fp plan", "plan"), new Cmd("/fp hotf", "hotf"), new Cmd("/fp gear", "gear"), new Cmd("/fp tree", "tree"), new Cmd("/fp goal", "goal")),
			List.of(new Cmd("/fp contests", "contests"), new Cmd("/fp goals", "goals"), new Cmd("/fp drops", "drops"), new Cmd("/fp alarms", "alarms"), new Cmd("/fp sell", "sell")),
			List.of(new Cmd("/fp routes", "routes"), new Cmd("/fp route new ", "route_new"), new Cmd("/fp route import", "route_import"), new Cmd("/fp ghost reset", "ghost")),
			List.of(new Cmd("/fp pv ", "pv"), new Cmd("/fp top", "top"), new Cmd("/fp wiki ", "wiki"), new Cmd("/fp fusion", "fusion"), new Cmd("/fp chapters", "chapters"), new Cmd("/fp sounds", "sounds")));
	private static final String[] TITLES = {"general", "planning", "progress", "routes", "lookup"};

	private Help() {}

	public static void show(Consumer<Component> out) {
		out.accept(Component.literal("§a§l[FP] §fForaging Planner §7v" + ModVersion.plain() + " §8· §f" + Planner.tr("help.title")));
		for (int g = 0; g < GROUPS.size(); g++) {
			out.accept(Component.literal("§2▍ §a" + Planner.tr("help.group." + TITLES[g])));
			for (Cmd cmd : GROUPS.get(g)) {
				boolean needsArgument = cmd.command().endsWith(" ");
				MutableComponent line = Component.literal("  §e" + cmd.command().trim() + (needsArgument ? " §7<…>" : "") + " §8– §7" + Planner.tr("help.cmd." + cmd.key()));
				line = line.withStyle(style -> style
						.withClickEvent(needsArgument ? new ClickEvent.SuggestCommand(cmd.command()) : new ClickEvent.RunCommand(cmd.command().trim()))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(Planner.tr(needsArgument ? "help.click_fill" : "help.click_run")))));
				out.accept(line);
			}
		}
	}
}
