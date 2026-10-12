package com.pvpduels.achievements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Achievements and titles. The game tells this class what happened (a duel was won, a kit was saved...) by raising counters; when
 * a counter reaches the target of an achievement it is unlocked, the player is told a few seconds later, and if it has a title
 * they can wear it in front of their name (/titles). Saved in pvpduels/achievements.json.
 */
public final class Achievements {
	public static final String DUELS = "Duels";
	public static final String PRACTICE = "Practice";
	public static final String KITS = "Kits";
	public static final String BUILDING = "Building";

	private static final Achievement[] ALL = {
		new Achievement("first_duel", DUELS, "First Steps", "Play your first duel.", "duels", 1, null, ChatFormatting.GOLD, Items.WOODEN_SWORD),
		new Achievement("first_win", DUELS, "First Victory", "Win a duel.", "wins", 1, "Rookie", ChatFormatting.GREEN, Items.STONE_SWORD),
		new Achievement("wins_10", DUELS, "Duelist", "Win 10 duels.", "wins", 10, "Duelist", ChatFormatting.GOLD, Items.IRON_SWORD),
		new Achievement("wins_25", DUELS, "Gladiator", "Win 25 duels.", "wins", 25, "Gladiator", ChatFormatting.GOLD, Items.GOLDEN_SWORD),
		new Achievement("wins_50", DUELS, "Champion", "Win 50 duels.", "wins", 50, "Champion", ChatFormatting.GOLD, Items.DIAMOND_SWORD),
		new Achievement("wins_100", DUELS, "Legend", "Win 100 duels.", "wins", 100, "Legend", ChatFormatting.GOLD, Items.NETHERITE_SWORD),
		new Achievement("streak_3", DUELS, "Hot Streak", "Win 3 duels in a row.", "streak", 3, "Streaker", ChatFormatting.RED, Items.BLAZE_POWDER),
		new Achievement("streak_5", DUELS, "On Fire", "Win 5 duels in a row.", "streak", 5, "On Fire", ChatFormatting.RED, Items.FIRE_CHARGE),
		new Achievement("streak_10", DUELS, "Unstoppable", "Win 10 duels in a row.", "streak", 10, "Unstoppable", ChatFormatting.RED, Items.NETHER_STAR),
		new Achievement("flawless", DUELS, "Flawless", "Win a duel without losing a single round (first to 2 or more).", "flawless", 1, "Flawless", ChatFormatting.AQUA, Items.SHIELD),
		new Achievement("flawless_5", DUELS, "Untouchable", "Win 5 duels without losing a round.", "flawless", 5, "Untouchable", ChatFormatting.AQUA, Items.NETHERITE_CHESTPLATE),
		new Achievement("elo_1100", DUELS, "Rising Star", "Reach 1100 Elo.", "elo", 1100, "Rising Star", ChatFormatting.AQUA, Items.COPPER_INGOT),
		new Achievement("elo_1200", DUELS, "Expert", "Reach 1200 Elo.", "elo", 1200, "Expert", ChatFormatting.AQUA, Items.GOLD_INGOT),
		new Achievement("elo_1400", DUELS, "Master", "Reach 1400 Elo.", "elo", 1400, "Master", ChatFormatting.AQUA, Items.DIAMOND),
		new Achievement("combo_5", DUELS, "Combo Starter", "Land a 5-hit combo.", "combo", 5, null, ChatFormatting.RED, Items.IRON_AXE),
		new Achievement("combo_10", DUELS, "Combo Master", "Land a 10-hit combo.", "combo", 10, "Combo King", ChatFormatting.RED, Items.DIAMOND_AXE),
		new Achievement("totems_5", DUELS, "Second Chance", "Pop 5 totems of undying in duels.", "totems", 5, null, ChatFormatting.YELLOW, Items.TOTEM_OF_UNDYING),
		new Achievement("pearls_10", DUELS, "Pearl Thrower", "Throw 10 ender pearls in duels.", "pearls", 10, null, ChatFormatting.DARK_AQUA, Items.ENDER_PEARL),
		new Achievement("apples_10", DUELS, "An Apple a Day", "Eat 10 golden apples in duels.", "apples", 10, null, ChatFormatting.YELLOW, Items.GOLDEN_APPLE),
		new Achievement("kits_3", DUELS, "Versatile", "Win with 3 different kits.", "kit_wins", 3, "Versatile", ChatFormatting.LIGHT_PURPLE, Items.CHEST),
		new Achievement("kits_10", DUELS, "Jack of All Kits", "Win with 10 different kits.", "kit_wins", 10, null, ChatFormatting.LIGHT_PURPLE, Items.ENDER_CHEST),
		new Achievement("arenas_4", DUELS, "Traveler", "Duel in 4 different kinds of arena.", "arena_kinds", 4, "Traveler", ChatFormatting.GREEN, Items.COMPASS),
		new Achievement("queue_1", DUELS, "Queue Up", "Win a duel from the queue.", "queue_wins", 1, null, ChatFormatting.GREEN, Items.CLOCK),
		new Achievement("queue_10", DUELS, "Queue Veteran", "Win 10 duels from the queue.", "queue_wins", 10, "Matchmaker", ChatFormatting.GREEN, Items.RECOVERY_COMPASS),
		new Achievement("team_first", DUELS, "Better Together", "Win a 2v2 duel.", "team_wins", 1, "Teammate", ChatFormatting.GREEN, Items.LEAD),
		new Achievement("team_10", DUELS, "Dynamic Duo", "Win 10 duels in 2v2.", "team_wins", 10, "Duo", ChatFormatting.GREEN, Items.GOLDEN_CARROT),
		new Achievement("clutch_1", DUELS, "Clutch", "Win a round alone against both rivals after your partner is out.", "team_clutches", 1, null, ChatFormatting.RED, Items.CLOCK),
		new Achievement("clutch_5", DUELS, "Clutch Master", "Win 5 rounds alone against both rivals in 2v2.", "team_clutches", 5, "Clutch Master", ChatFormatting.RED, Items.TOTEM_OF_UNDYING),
		new Achievement("spectate_5", DUELS, "Fan", "Watch 5 duels.", "spectated", 5, null, ChatFormatting.GRAY, Items.SPYGLASS),

		new Achievement("bot_10", PRACTICE, "Bot Buster", "Knock out the practice bot 10 times.", "bot_kos", 10, null, ChatFormatting.GREEN, Items.ZOMBIE_HEAD),
		new Achievement("bot_100", PRACTICE, "Bot Crusher", "Knock out the practice bot 100 times.", "bot_kos", 100, "Crusher", ChatFormatting.GREEN, Items.WITHER_SKELETON_SKULL),

		new Achievement("kit_first", KITS, "Kit Maker", "Save your first kit.", "kits_saved", 1, null, ChatFormatting.LIGHT_PURPLE, Items.CRAFTING_TABLE),
		new Achievement("kit_10", KITS, "Collector", "Have 10 saved kits.", "kits_saved", 10, "Collector", ChatFormatting.LIGHT_PURPLE, Items.BUNDLE),
		new Achievement("kit_share", KITS, "Generous", "Share a kit with everybody.", "kits_published", 1, "Generous", ChatFormatting.LIGHT_PURPLE, Items.EMERALD),

		new Achievement("arena_first", BUILDING, "Architect", "Save your first custom arena.", "arenas_saved", 1, "Architect", ChatFormatting.YELLOW, Items.BRICKS),
		new Achievement("arena_share", BUILDING, "Open Doors", "Make one of your arenas public.", "arenas_published", 1, null, ChatFormatting.YELLOW, Items.OAK_DOOR),
		new Achievement("contest_join", BUILDING, "Contestant", "Hand in a build to the weekly contest.", "contest_entries", 1, null, ChatFormatting.YELLOW, Items.PAINTING),
		new Achievement("contest_vote", BUILDING, "Critic", "Vote in 5 building contests.", "contest_votes", 5, "Critic", ChatFormatting.YELLOW, Items.WRITABLE_BOOK),
		new Achievement("contest_podium", BUILDING, "Builder", "Place 1st, 2nd or 3rd in a building contest.", "contest_podiums", 1, "Builder", ChatFormatting.YELLOW, Items.GOLDEN_PICKAXE),
		new Achievement("contest_win", BUILDING, "Master Builder", "Win a building contest.", "contest_wins", 1, "Master Builder", ChatFormatting.YELLOW, Items.GOLDEN_HELMET)
	};

	/** Everything we know about one player. */
	private static final class Data {
		String name = "";
		/** Achievement id -> when it was unlocked (milliseconds). */
		Map<String, Long> unlocked = new LinkedHashMap<>();
		Map<String, Long> counters = new HashMap<>();
		/** For the counters that count different things (kits won with...). */
		Map<String, List<String>> sets = new HashMap<>();
		/** The achievement whose title is worn, or empty. */
		String title = "";
		/** Unlocked but the player has not been told yet. */
		List<String> unannounced = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Data> PLAYERS = new HashMap<>();
	private static boolean loaded;
	private static MinecraftServer server;
	private static int ticks;
	/** The tick from which a player is told about what they unlocked (a few seconds after, so the message is not buried). */
	private static final Map<UUID, Integer> DUE = new HashMap<>();
	private static final int ANNOUNCE_DELAY = 60;
	private static final String TEAM_PREFIX = "pvpd_t";

	private Achievements() {}

	public static List<Achievement> all() {
		return List.of(ALL);
	}

	public static Achievement find(String id) {
		for (Achievement a : ALL) if (a.id().equals(id)) return a;
		return null;
	}

	// ------------------------------------------------------------------ the file

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("achievements.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Data> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Data>>() {}.getType());
			if (read != null) {
				for (Data d : read.values()) {
					if (d.unlocked == null) d.unlocked = new LinkedHashMap<>();
					if (d.counters == null) d.counters = new HashMap<>();
					if (d.sets == null) d.sets = new HashMap<>();
					if (d.unannounced == null) d.unannounced = new ArrayList<>();
					if (d.title == null) d.title = "";
					if (d.name == null) d.name = "";
				}
				PLAYERS.putAll(read);
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the achievements file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(PLAYERS));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the achievements file: {}", e.toString());
		}
	}

	/** For the self-test: forget everybody. */
	public static synchronized void resetForTest() {
		PLAYERS.clear();
		DUE.clear();
		loaded = true;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	/** For the self-test: throw away what is in memory and read the file again. */
	public static synchronized void reloadForTest() {
		PLAYERS.clear();
		loaded = false;
		ensureLoaded();
	}

	private static Data data(UUID id, String name) {
		ensureLoaded();
		Data d = PLAYERS.computeIfAbsent(id.toString(), k -> new Data());
		if (name != null && !name.isEmpty()) d.name = name;
		return d;
	}

	// ------------------------------------------------------------------ counters

	/** Counts something that happened (a win, a KO...). */
	public static synchronized void add(UUID id, String name, String counter, long amount) {
		Data d = data(id, name);
		d.counters.merge(counter, amount, Long::sum);
		changed(id, d, counter);
	}

	/** Remembers the highest value of something (the best streak, the Elo...). */
	public static synchronized void max(UUID id, String name, String counter, long value) {
		Data d = data(id, name);
		if (d.counters.getOrDefault(counter, 0L) >= value) return;
		d.counters.put(counter, value);
		changed(id, d, counter);
	}

	/** Counts different things: the counter is the number of different values seen. */
	public static synchronized void distinct(UUID id, String name, String counter, String value) {
		Data d = data(id, name);
		List<String> seen = d.sets.computeIfAbsent(counter, k -> new ArrayList<>());
		if (seen.contains(value)) return;
		seen.add(value);
		d.counters.put(counter, (long) seen.size());
		changed(id, d, counter);
	}

	private static void changed(UUID id, Data d, String counter) {
		boolean any = false;
		for (Achievement a : ALL) {
			if (!a.counter().equals(counter) || d.unlocked.containsKey(a.id())) continue;
			if (d.counters.getOrDefault(counter, 0L) < a.target()) continue;
			d.unlocked.put(a.id(), System.currentTimeMillis());
			d.unannounced.add(a.id());
			any = true;
		}
		if (any) DUE.put(id, ticks + ANNOUNCE_DELAY);
		save();
	}

	public static synchronized long counter(UUID id, String counter) {
		return data(id, null).counters.getOrDefault(counter, 0L);
	}

	public static synchronized boolean has(UUID id, String achievement) {
		return data(id, null).unlocked.containsKey(achievement);
	}

	/** For the self-test: how many unlocked achievements the player has not been told about yet. */
	public static synchronized int pendingCount(UUID id) {
		return data(id, null).unannounced.size();
	}

	public static synchronized int unlockedCount(UUID id) {
		return data(id, null).unlocked.size();
	}

	// ------------------------------------------------------------------ titles

	/** The unlocked achievements that give a title. */
	public static synchronized List<Achievement> titlesOf(UUID id) {
		Data d = data(id, null);
		List<Achievement> list = new ArrayList<>();
		for (Achievement a : ALL) if (a.hasTitle() && d.unlocked.containsKey(a.id())) list.add(a);
		return list;
	}

	/** The title being worn, or null. */
	public static synchronized Achievement titleOf(UUID id) {
		Data d = data(id, null);
		Achievement a = d.title.isEmpty() ? null : find(d.title);
		return a != null && a.hasTitle() && d.unlocked.containsKey(a.id()) ? a : null;
	}

	/** Wears the title of this achievement (null or empty = no title). Returns an error text or null. */
	public static synchronized String setTitle(ServerPlayer p, String achievement) {
		Data d = data(p.getUUID(), p.getName().getString());
		if (achievement == null || achievement.isEmpty()) {
			d.title = "";
		} else {
			Achievement a = find(achievement);
			if (a == null || !a.hasTitle()) return "That is not a title.";
			if (!d.unlocked.containsKey(a.id())) return "You haven't unlocked that title yet: " + a.description();
			d.title = a.id();
		}
		save();
		applyTitle(p);
		return null;
	}

	private static String teamName(Achievement a) {
		for (int i = 0; i < ALL.length; i++) if (ALL[i] == a) return TEAM_PREFIX + i;
		return TEAM_PREFIX + "x";
	}

	/** Puts the title in front of the name (nametag, tab list and chat) with a scoreboard team. */
	public static void applyTitle(ServerPlayer p) {
		MinecraftServer s = p.level().getServer();
		if (s == null) return;
		Scoreboard board = s.getScoreboard();
		String entry = p.getScoreboardName();
		PlayerTeam current = board.getPlayersTeam(entry);
		if (current != null && current.getName().startsWith(TEAM_PREFIX)) board.removePlayerFromTeam(entry, current);
		Achievement a = titleOf(p.getUUID());
		if (a == null) return;
		String name = teamName(a);
		PlayerTeam team = board.getPlayerTeam(name);
		if (team == null) {
			team = board.addPlayerTeam(name);
			team.setPlayerPrefix(Component.literal("[" + a.title() + "] ").withStyle(a.color()));
		}
		board.addPlayerToTeam(entry, team);
	}

	private static void removeTeams(MinecraftServer s) {
		Scoreboard board = s.getScoreboard();
		for (PlayerTeam team : new ArrayList<>(board.getPlayerTeams())) {
			if (team.getName().startsWith(TEAM_PREFIX)) board.removePlayerTeam(team);
		}
	}

	// ------------------------------------------------------------------ telling the player

	private static void announce(ServerPlayer p) {
		List<String> ids;
		synchronized (Achievements.class) {
			Data d = data(p.getUUID(), null);
			if (d.unannounced.isEmpty()) return;
			ids = new ArrayList<>(d.unannounced);
			d.unannounced.clear();
			save();
		}
		for (String id : ids) {
			Achievement a = find(id);
			if (a == null) continue;
			p.sendSystemMessage(Component.literal("Achievement unlocked: ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(a.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
				.append(Component.literal(" - " + a.description()).withStyle(ChatFormatting.GRAY)));
			if (a.hasTitle()) {
				p.sendSystemMessage(Msg.info("  New title: ").copy().append(Component.literal("[" + a.title() + "]").withStyle(a.color()))
					.append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
					.append(Msg.button("[WEAR]", ChatFormatting.GREEN, "/titles wear " + a.id(), "Put it in front of your name")));
			}
			p.sendOverlayMessage(Component.literal("Achievement: " + a.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		}
	}

	/** For the self-test: tell everybody what they unlocked right now. */
	public static void announceNow(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) announce(p);
	}

	// ------------------------------------------------------------------ events

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			removeTeams(s);
			server = null;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			synchronized (Achievements.class) {
				PLAYERS.clear();
				DUE.clear();
				loaded = false; // the next server reads the file again
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			ticks++;
			if (ticks % 10 != 0 || DUE.isEmpty()) return;
			for (Map.Entry<UUID, Integer> entry : new ArrayList<>(DUE.entrySet())) {
				if (entry.getValue() > ticks) continue;
				ServerPlayer p = s.getPlayerList().getPlayer(entry.getKey());
				if (p == null) continue;
				DUE.remove(entry.getKey());
				announce(p);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayer p = handler.getPlayer();
			applyTitle(p);
			synchronized (Achievements.class) {
				if (!data(p.getUUID(), p.getName().getString()).unannounced.isEmpty()) DUE.put(p.getUUID(), ticks + ANNOUNCE_DELAY);
			}
		});
	}
}
