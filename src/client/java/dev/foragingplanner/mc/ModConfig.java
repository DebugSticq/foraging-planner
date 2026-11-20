package dev.foragingplanner.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Player settings and the last Heart of the Forest state the mod read. Saved as config/foragingplanner/config.json. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean hudEnabled = true;
	public int hudX = 4;
	public int hudY = 40;
    public boolean animations = true;
    public boolean learnSweep = true;
    public boolean showRates = true;
    public boolean helixGuide = true;
    public boolean contestSound = true;
    public boolean contestTitle = true;
    public boolean huntingEnabled = true;
    public boolean trapAlerts = true;
    public boolean reelCue = true;
    public boolean huntingSound = true;
    public int tokens = -1;
    public int hotfTier = 1;
    public double manualStrength = 0;
    public double manualSpeed = 0;
    public Map<String, Map<String, Integer>> requirements = new LinkedHashMap<>();
    public Map<String, Integer> requiredTiers = new LinkedHashMap<>();
	/** xp, logs, coins or sweep. */
	public String goal = "xp";
	/** "auto" or a tree id from gamedata.json. */
	public String tree = "auto";

	/** When true the three values below are used instead of the tab list. */
	public boolean manualStats = false;
	public double manualSweep = 0;
	public double manualFortune = 0;
	public double manualWisdom = 0;

	/** Perk name to level, as last seen in the Heart of the Forest menu. */
	public Map<String, Integer> perkLevels = new LinkedHashMap<>();
	/** Forest Whispers last seen in the menu, or -1 when never read. */
	public long whispers = -1;
    public long desertWhispers = -1;
    public Map<String, Integer> perkSlots = new LinkedHashMap<>();
    public Map<String, String> perkCurrencies = new LinkedHashMap<>();
    public Map<String, HudLayout> modules = new LinkedHashMap<>();
    public boolean treeAssistant = false;
    public String guideTree = "helix";
    public int guidePhase = 0;
    public boolean treeMarkers = true;
    public boolean treeArrow = true;
    public boolean treeRoute = false;
    public boolean sweepPreview = true;
    public boolean treeProgress = true;
    public boolean comboCounter = true;
    public boolean protectedTrees = true;
    public boolean regrowWarning = true;
    public boolean milestones = true;
    public boolean bigTreesOnly = false;
    public boolean dropProtection = true;
    public java.util.List<String> protectedItems = new java.util.ArrayList<>(java.util.List.of("Shining Coin"));
    public boolean huntrapProtection = true;
    public boolean attributeOverlay = true;
    public boolean vendorTracker = true;
    // Rare drops: what counts as rare
    /** Always rare (name contains one of these), e.g. "Mango Dye". */
    public java.util.List<String> rareItems = new java.util.ArrayList<>(java.util.List.of("Mango Dye", "Chameleon Shard"));
    /** Never rare, even when expensive. */
    public java.util.List<String> rareIgnore = new java.util.ArrayList<>();
    /** Gift rewards worth at least this many coins are rare (0 = only the list). */
    public long rareValue = 100_000;
    /** Bonus gift drops always count as rare. */
    public boolean rareBonus = true;
    // Just for fun
    public boolean confetti = true;
    public boolean treeFallEffect = true;
    public boolean comboFire = true;
    /** Draw /hotf with the mod's own menu (hold Alt for the original). */
    public boolean customHotf = true;
    /** Custom axe look: off, moonblade, rootcleaver, emberbit. Only visible to this player. */
    public String axeSkin = "off";
    /** Also every other SkyBlock axe, not only foraging axes. */
    public boolean axeSkinAll = false;
    /** Low-poly 3D models in the hand (pixel version in the inventory). Off = pixel version everywhere. */
    public boolean axeSkinMesh = true;
    /** auto (by date), off (fireflies), winter, spring, summer, autumn */
    public String seasonTheme = "auto";
    /** Fewer scans and a smaller path budget, for slower PCs. */
    public boolean perfLight = false;
    /** Switch to the light mode by itself when the mod takes too much time. */
    public boolean perfAuto = true;
    /** Stops hits and Axe Toss on protected trees (sneak to hit anyway). */
    public boolean protectedBlock = true;
    // Critter Safari
    public boolean safariTracker = true;
    public int capsuleWarnAt = 5;
    public boolean safariPrices = true;
    public boolean sparklingAlert = true;
    public boolean critterdexHelper = true;
    public boolean safariMilestones = true;
    public boolean ticketReminder = true;
    public boolean safariGroundItems = true;
    public java.util.List<String> safariRareItems = new java.util.ArrayList<>(java.util.List.of("Icebreaker", "Shining Coin", "Lush Lily Pad"));
    public boolean honeyHelix = true;
    public java.util.List<String> honeyNames = new java.util.ArrayList<>(java.util.List.of("Honeyed Helix", "Honeyed"));
    public boolean partyProgress = true;
    public boolean safariBells = true;
    public boolean bellRoute = true;
    public boolean safariDebug = true;
    public java.util.List<String> vendorNames = new java.util.ArrayList<>(java.util.List.of("Shard Vendor", "Shard Trader", "Shard Merchant", "Vendor"));
    /** Asks Modrinth once per start whether a newer version exists. */
    public boolean updateCheck = true;
    /** Mutes sounds of mobs with these names (e.g. the Drybark). */
    public boolean muteMobs = true;
    public java.util.List<String> mutedMobs = new java.util.ArrayList<>(java.util.List.of("Drybark"));
    /** Pop-up cards for drops, goals, alarms and helpers. */
    public boolean toasts = true;
    /** Tree navigation: off, arrow, star, arrow_star, route or all. */
    public String navigator = "arrow_star";
    /** False until the first-start choice was made. */
    public boolean setupDone = false;
    public boolean minimap = true;
    public int mapSize = 100;
    public int mapRange = 70;
    public String mapCorner = "top_right";
    public boolean levelEta = true;
    public boolean afkPause = true;
    public int afkMinutes = 3;
    /** Off until the player turns it on: sends personal bests to the global leaderboards. */
    public boolean leaderboardShare = false;
    /** UUID confirmed by Mojang for the leaderboards, empty until then. */
    public String leaderboardUuid = "";
    public boolean fallAlert = true;
    public boolean critterHighlight = true;
    public java.util.List<String> critterNames = new java.util.ArrayList<>(java.util.List.of("Hideonleaf", "Shellwise", "Birries", "Invisibug", "Veilshroom"));
    public boolean invisibugHelper = true;
    public boolean striderCounter = true;
    public int striderMin = 2;
    public boolean gatherRates = true;
    public boolean inventoryWarning = true;
    public boolean whisperReminder = true;
    public boolean hotfCosts = true;
    public boolean hideLottery = true;
    public boolean treeSizeLabel = true;
    /** Sound ids muted by hand with /fp sounds. */
    public java.util.List<String> mutedSounds = new java.util.ArrayList<>();
    /** HUD style. */
    public int hudRadius = 0;
    public int hudOpacity = 47;
    public int hudColor = 0x000000;
    public boolean hudBorder = false;
    public int hudBorderColor = 0x55FF55;
    public int hudLabelColor = 0xAAAAAA;
    public boolean hudShadow = true;
    public int hudPadding = 4;
    public int sessionColumns = 2;
    public boolean cleanTrees = false;
    public boolean precisionHelper = true;
    public boolean beaconAlert = true;
    public boolean beaconSolver = true;
    public boolean mutePhantoms = false;
    public boolean muteTreeBreak = false;
    public boolean preventStripping = true;
    public boolean compactGifts = false;
    public boolean compactSweep = false;
    public boolean compactContest = false;
    public boolean ghostRoutes = true;
    public boolean pauseReminder = true;
    public boolean treeChoice = true;
    public boolean toastSound = true;
    public boolean toastChat = false;
    public boolean contestCountdown = true;
    public boolean contestCountdownSound = true;
    public boolean contestPreset = false;
    /** Card kinds that may show: goal, milestone, rare, alarm, info. */
    public boolean toastGoal = true, toastMilestone = true, toastRare = true, toastAlarm = true, toastInfo = true;
    /** During a Jacob-style contest only rare drops and alarms may show. */
    public boolean toastQuietContest = true;
    /** HUD layout per place: galatea, safari, hub, other; "" keeps the current layout. */
    public Map<String, String> locationPresets = new LinkedHashMap<>();
    public boolean hudSnap = true;
    public boolean hudGrid = true;
    public boolean autoBackup = true;
    public boolean hoverDetails = true;
    public boolean firstSteps = true;
    /** Mod version whose "What's new" card was shown; empty on a fresh install. */
    public String lastSeenVersion = "";
    public String activePreset = "farm";
    public Map<String, Map<String, HudLayout>> presets = new LinkedHashMap<>();
    public static class HudLayout {
        public int x = 4, y = 40;
        public double scale = 1;
        public boolean visible = true;
    }
    public HudLayout module(String id) {
        return modules.computeIfAbsent(id, key -> {
            HudLayout layout = new HudLayout();
            if (key.equals("progress")) {
                var window = net.minecraft.client.Minecraft.getInstance().getWindow();
                layout.x = window.getGuiScaledWidth() / 2 - 55;
                layout.y = window.getGuiScaledHeight() / 2 + 44;
                layout.scale = 1;
                return layout;
            }
            int index = java.util.List.of("trees", "upgrades", "contest", "hunting", "session", "targets", "guide", "reel").indexOf(key);
            int screenW = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
            int screenH = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
            layout.x = index < 4 ? hudX : screenW / 2 + 4;
            layout.y = 10 + Math.floorMod(index, 4) * Math.max(30, (screenH - 80) / 4);
            layout.scale = .75;
            return layout;
        });
    }
    public static Path profileDirectory() { return ProfileStore.directory(); }

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(Planner.MOD_ID);
	}

	private static Path file() {
		return profileDirectory().resolve("config.json");
	}

	public static ModConfig load() {
		try {
			if (Files.exists(file())) {
				ModConfig config = GSON.fromJson(Files.readString(file()), ModConfig.class);

				if (config != null) {
					if (config.modules == null) config.modules = new LinkedHashMap<>();
                    config.modules.values().removeIf(java.util.Objects::isNull);
                    config.modules.values().forEach(m -> m.scale = Double.isFinite(m.scale) ? Math.clamp(m.scale, .5, 2) : 1);
                    if (config.presets == null) config.presets = new LinkedHashMap<>();
                    if (config.locationPresets == null) config.locationPresets = new LinkedHashMap<>();
                    if (config.activePreset == null) config.activePreset = "farm";
                    if (config.perkSlots == null) config.perkSlots = new LinkedHashMap<>();
                    if (config.perkCurrencies == null) config.perkCurrencies = new LinkedHashMap<>();
                    if (config.perkLevels == null) config.perkLevels = new LinkedHashMap<>();
					if (config.requirements == null) config.requirements = new LinkedHashMap<>();
                    if (config.requiredTiers == null) config.requiredTiers = new LinkedHashMap<>();
                    if (config.goal == null) config.goal = "xp";
					if (config.tree == null) config.tree = "auto";
					return config;
				}
			}
		} catch (Exception e) {
			Planner.LOGGER.warn("Could not read config, using defaults", e);
		}

		return new ModConfig();
	}

	public void save() {
		try {
			Files.createDirectories(profileDirectory());
			Path temporary = profileDirectory().resolve("config.json.tmp");
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                // Windows refuses to replace a file another program has open (antivirus, editor).
                // Writing into it directly still works in that case.
                Files.writeString(file(), GSON.toJson(this));
                Files.deleteIfExists(temporary);
            }
		} catch (IOException e) {
			Planner.LOGGER.warn("Could not save config", e);
		}
	}
}
