package dev.foragingplanner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain data holder for everything in gamedata.json. Gson fills the public fields by name,
 * so a field here must be spelled exactly like its key in the JSON file.
 */
public final class GameData {
	public int dataVersion;
	public List<String> areas = new ArrayList<>();
	public Map<String, String> statPatterns = new LinkedHashMap<>();
	public Hotf hotf = new Hotf();
	public List<Tree> trees = new ArrayList<>();
	public SweepChat sweepChat = new SweepChat();
	public Contest contest = new Contest();

	public static final class Hotf {
		public String menuTitle = "Heart of the Forest";
		public String levelPattern = "Level (\\d+)";
		public String whispersPattern = "Forest Whispers: ([\\d,]+)";
		public List<Perk> perks = new ArrayList<>();
	}

	public static final class Perk {
		public String name;
		public int maxLevel;
		/** Cost to reach level N is floor((N+1)^costExponent). Null when the perk uses the linear cost. */
		public Double costExponent;
		/** Linear cost: costBase + costPerLevel * N. Used when costExponent is null. */
		public Double costBase;
		public Double costPerLevel;
		/** Stat gained per level, keyed by "sweep", "fortune" or "wisdom". Empty for perks the planner cannot value. */
		public Map<String, Double> effects = new LinkedHashMap<>();
		/** Human readable effect for perks the planner cannot value. */
		public String note;
		public Map<String, Integer> requires = new LinkedHashMap<>();
		public int requiredTier = 1;
        public String currency = "forest";
        public int menuSlot = -1;
        public int treeColumn = -1;
        public boolean planEligible = true;
		public boolean unlockKnown = false;
		public Map<String, Double> unlockEffects = new LinkedHashMap<>();

		public boolean hasValuedEffects() {
			return effects != null && !effects.isEmpty();
		}
	}

	public static final class Tree {
		public String id;
		public String name;
		/** Tree name as it appears in the "Tree Toughness" chat line. */
		public String chatName;
		public String bazaarId;
		public double xpPerLog;
		public double contestPointsPer100Logs;
		/** Entry i is the Sweep needed to cut i+1 logs per hit. Must be ascending. */
		public List<Double> sweepBreakpoints = new ArrayList<>();
	}

	public static final class SweepChat {
		public String sweepPattern;
		public String treePattern;
	}

	public static final class Contest {
		public String headerPattern;
		public String pointsPattern;
		public int durationSeconds = 1200;
		public double pointsPerTreeGift = 10.0;
		public List<Bracket> brackets = new ArrayList<>();
		public List<Bracket> miriaBrackets = new ArrayList<>();
		public List<Bracket> bracketsFor(String host) {
			return "Miria".equalsIgnoreCase(host) ? miriaBrackets : brackets;
		}
	}

	public static final class Bracket {
		public String name;
		public int minPoints;
		public int coupons;
		/** HOTF experience the bracket rewards. 0 means "use the built-in table". */
		public int hotfExp;
		/** Forest Essence the bracket rewards. */
		public int essence;
	}

	public Tree tree(String id) {
		for (Tree tree : trees) {
			if (tree.id.equalsIgnoreCase(id)) return tree;
		}

		return null;
	}

	public Perk perk(String name) {
		for (Perk perk : hotf.perks) {
			if (perk.name.equalsIgnoreCase(name)) return perk;
		}

		return null;
	}
}
