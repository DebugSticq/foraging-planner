package dev.foragingplanner.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;

import dev.foragingplanner.core.GameData;
import dev.foragingplanner.core.TreeMath;

/**
 * How many logs are still needed to finish the tree in front of the player.
 *
 * <p>Hypixel shows the progress above a tree being cut ("FIG TREE 88%"). The mod counts the logs
 * the player cuts on that tree (one hit = the logs per hit from the game's Sweep Details, or from
 * the player's Sweep). The tree size comes from, in this order:
 * <ol>
 *   <li>the tree gift message "You helped cut 45.3% of the Fig Tree": own logs divided by own
 *       share gives the exact size, even when others helped;</li>
 *   <li>on the current tree, own logs divided by how far the percentage rose (exact when alone);</li>
 *   <li>older readings of single hits.</li>
 * </ol>
 */
public final class TreeProgress {
	private static final Pattern PROGRESS = Pattern.compile("(\\w+) TREE (\\d+)%");
	private static final int KEEP = 12;
	/** A rise this big on the current tree is enough for a live size estimate. */
	private static final int LIVE_MIN_RISE = 8;

	private static int standId = -1;
	private static String tree = "";
	private static int percent = -1;
	private static int startPercent = -1;
	private static double myLogs;
	private static int myHits;
	private static long seenMs;
	private static double standDistance;

	/** Own logs on the last finished tree, kept until the gift message arrives. */
	private static String finishedTree = "";
	private static double finishedLogs;
	private static long finishedMs;

	private TreeProgress() {}

	/** True when a tree's progress was seen in the last 15 seconds. */
	public static boolean recent() {
		return System.currentTimeMillis() - seenMs < 15_000;
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null || !Planner.config.treeProgress || !SkyBlockReader.inForagingArea()) {
			standId = -1;
			return;
		}

		ArmorStand best = null;
		double bestDistance = 18 * 18;
		Matcher bestMatch = null;
		for (ArmorStand stand : client.level.getEntitiesOfClass(ArmorStand.class, client.player.getBoundingBox().inflate(18))) {
			if (stand.getCustomName() == null) continue;
			Matcher m = PROGRESS.matcher(Planner.strip(stand.getCustomName().getString()));
			if (!m.find()) continue;
			double d = stand.distanceToSqr(client.player);
			if (d < bestDistance) {
				bestDistance = d;
				best = stand;
				bestMatch = m;
			}
		}

		long now = System.currentTimeMillis();
		if (best == null) {
			if (standId >= 0 && now - seenMs > 1500) finish();
			return;
		}

		String type = bestMatch.group(1).toLowerCase(Locale.ROOT);
		int value = Integer.parseInt(bestMatch.group(2));

		if (best.getId() != standId || !type.equals(tree)) {
			if (standId >= 0) finish();
			standId = best.getId();
			tree = type;
			startPercent = value;
			myLogs = 0;
			myHits = 0;
		} else if (percent >= 0 && value > percent && value - percent <= 60 && myHits == 0) {
			// No hit seen yet (XP line hidden by another mod): fall back to one hit per rise.
			learnRise(type, value - percent);
		}

		percent = value;
		seenMs = now;
		standDistance = Math.sqrt(bestDistance);
		if (value >= 100) finish();
	}

	/** Called for every Foraging XP gain: one hit on the tree in front of the player. */
	public static void hit() {
		if (standId < 0 || System.currentTimeMillis() - seenMs > 2000 || standDistance > 12) return;
		double perHit = logsPerHit(data(tree));
		if (perHit <= 0) return;
		myLogs += perHit;
		myHits++;
	}

	/** The tree is done or out of sight: remember own logs for the gift message, and learn from it. */
	private static void finish() {
		if (standId >= 0 && myLogs > 0) {
			int rise = percent - startPercent;
			if (rise >= 20) addSample(tree, myLogs * 100.0 / rise, false);
			finishedTree = tree;
			finishedLogs = myLogs;
			finishedMs = System.currentTimeMillis();
		}
		standId = -1;
		percent = -1;
	}

	/** From "You helped cut 45.3% of the Fig Tree.": exact tree size from own logs and own share. */
	public static void contribution(String type, double share) {
		String t = type.toLowerCase(Locale.ROOT);
		double logs = t.equals(tree) && standId >= 0 ? myLogs
				: t.equals(finishedTree) && System.currentTimeMillis() - finishedMs < 20_000 ? finishedLogs : 0;
		if (logs <= 0 || share <= 0.5) return;
		addSample(t, logs * 100.0 / share, true);
		finishedLogs = 0;
	}

	private static void learnRise(String type, int rise) {
		double perHit = logsPerHit(data(type));
		if (perHit > 0) addSample(type, perHit * 100.0 / rise, false);
	}

	private static void addSample(String type, double size, boolean exact) {
		if (!Double.isFinite(size) || size < 5 || size > 100_000) return;
		var map = exact ? Progress.get().treeSizesExact : Progress.get().treeSizes;
		List<Double> list = map.computeIfAbsent(type, k -> new ArrayList<>());
		list.add(size);
		while (list.size() > KEEP) list.removeFirst();
		Progress.changed();
	}

	private static GameData.Tree data(String type) {
		for (GameData.Tree t : Planner.data.trees) if (t.id.equalsIgnoreCase(type) || t.name.equalsIgnoreCase(type)) return t;
		return null;
	}

	/** Logs one hit cuts on this tree right now. */
	private static double logsPerHit(GameData.Tree t) {
		if (t == null) return -1;
		Double measured = Planner.measuredLogs().get(t.id);
		if (measured != null && measured > 0) return measured;
		if (!SkyBlockReader.statsKnown()) return -1;
		return Math.max(1, TreeMath.logsPerHit(t, SkyBlockReader.stats().sweep()));
	}

	private static double median(List<Double> list) {
		List<Double> sorted = new ArrayList<>(list);
		sorted.sort(null);
		return sorted.get(sorted.size() / 2);
	}

	/** Where a size comes from. */
	public enum Source { EXACT, LIVE, ROUGH, NONE }

	public record Size(double logs, Source source) {}

	/** Size of a whole tree of this kind, and how sure the mod is about it. */
	public static Size treeSize(String type) {
		List<Double> exact = Progress.get().treeSizesExact.get(type);
		if (exact != null && !exact.isEmpty()) return new Size(median(exact), Source.EXACT);
		if (type.equals(tree) && standId >= 0 && percent - startPercent >= LIVE_MIN_RISE && myLogs > 0) {
			return new Size(myLogs * 100.0 / (percent - startPercent), Source.LIVE);
		}
		List<Double> rough = Progress.get().treeSizes.get(type);
		if (rough != null && rough.size() >= 2) return new Size(median(rough), Source.ROUGH);
		return new Size(-1, Source.NONE);
	}

	/** Lines for the HUD module, or none while no tree is being cut. */
	public static List<String> lines() {
		if (!Planner.config.treeProgress || standId < 0 || percent < 0 || System.currentTimeMillis() - seenMs > 1500) return List.of();
		GameData.Tree t = data(tree);
		String name = t != null ? t.name : StatsScreen.capital(tree);
		List<String> out = new ArrayList<>();
		out.add(Planner.tr("tree.progress", name, percent));

		Size size = treeSize(tree);
		double perHit = logsPerHit(t);
		if (size.logs() <= 0) {
			out.add("§7" + Planner.tr("tree.progress_learning"));
			return out;
		}

		double left = Math.max(0, size.logs() * (100 - percent) / 100.0);
		String color = perHit > 0 && left <= perHit ? "§a" : "§e";
		String text = perHit > 0
				? Planner.tr("tree.progress_left_hits", Planner.whole(Math.ceil(left)), (long) Math.max(1, Math.ceil(left / perHit)))
				: Planner.tr("tree.progress_left", Planner.whole(Math.ceil(left)));
		out.add(color + text + " §8(" + Planner.tr("tree.source." + size.source().name().toLowerCase(Locale.ROOT)) + ")");
		return out;
	}

	/** Current progress 0..100, or -1. */
	public static int percent() {
		return standId < 0 ? -1 : percent;
	}

	/** Example lines for the HUD editor. */
	public static List<String> preview() {
		return List.of(Planner.tr("tree.progress", "Fig", 72),
				"§e" + Planner.tr("tree.progress_left_hits", "48", 3) + " §8(" + Planner.tr("tree.source.exact") + ")");
	}
}
