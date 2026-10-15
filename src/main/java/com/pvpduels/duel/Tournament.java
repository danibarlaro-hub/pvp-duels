package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.PublicKits;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A knockout tournament for a group of friends: 3 to 8 players, a bracket seeded by Elo (the best ones get the byes), every match is a
 * normal 1v1 duel with a public kit (or a random one), the winner goes on and the loser is out. A tie is played again; somebody who does not
 * come to their match in two minutes loses it. /tournament create, join, start, bracket, leave, cancel and top.
 */
public final class Tournament {
	public static final int MIN_PLAYERS = 3;
	public static final int MAX_PLAYERS = 8;
	private static final int SIGNUP_TIMEOUT = 20 * 60 * 10;

	enum Phase { SIGNUP, RUNNING }

	enum State { WAITING, READY, RUNNING, DONE }

	static final class Entrant {
		final UUID id;
		String name;
		/** 1 is the best. */
		int seed;
		boolean out;
		boolean withdrawn;

		Entrant(UUID id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	static final class Match {
		final int round;
		final int index;
		Entrant a;
		Entrant b;
		boolean bye;
		int winner = -1;
		int scoreA;
		int scoreB;
		State state = State.WAITING;
		int startAt;
		int waited;
		int failures;
		int ties;
		Duel duel;
		String how = "";

		Match(int round, int index) {
			this.round = round;
			this.index = index;
		}
	}

	private static final class Record {
		String name = "";
		int titles;
		int finals;
		int played;
	}

	private static final class Past {
		long when;
		String champion = "";
		String runnerUp = "";
		int players;
		int firstTo;
		String kit = "";
	}

	private static final class Store {
		Map<String, Record> players = new HashMap<>();
		List<Past> history = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Store store = new Store();
	private static boolean loaded;

	private static Phase phase;
	private static UUID host;
	private static String hostName = "";
	private static final List<Entrant> ENTRANTS = new ArrayList<>();
	private static int maxPlayers = MAX_PLAYERS;
	private static int firstTo = 3;
	/** The public kit every match uses; null = a random kit for each match. */
	private static String kitName;
	private static Match[][] rounds = new Match[0][];
	private static int bracketSize;
	private static int tick;
	private static int openedAt;
	private static Entrant champion;
	private static Entrant runnerUp;
	private static Random random = new Random();

	/** Ticks between a match being known and the duel; changed by the self-test. */
	static int firstDelay = 200;
	static int nextDelay = 400;
	static int tieDelay = 300;
	/** How long somebody can be missing for their match before they lose it. */
	static int grace = 2400;

	private Tournament() {}

	// ------------------------------------------------------------------ questions

	public static boolean isActive() {
		return phase != null;
	}

	public static boolean isIn(UUID id) {
		return find(id) != null;
	}

	private static Entrant find(UUID id) {
		for (Entrant e : ENTRANTS) if (e.id.equals(id)) return e;
		return null;
	}

	private static ServerPlayer player(MinecraftServer server, Entrant e) {
		return e == null ? null : server.getPlayerList().getPlayer(e.id);
	}

	private static String roundName(int round) {
		int matches = rounds.length > round ? rounds[round].length : 1;
		return switch (matches) {
			case 1 -> "Final";
			case 2 -> "Semifinals";
			case 4 -> "Quarterfinals";
			default -> "Round " + (round + 1);
		};
	}

	private static String kitLabel() {
		return kitName == null ? "random kits" : "kit " + kitName;
	}

	// ------------------------------------------------------------------ files

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("tournaments.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Store read = GSON.fromJson(Files.readString(file()), new TypeToken<Store>() {}.getType());
			if (read != null) {
				if (read.players == null) read.players = new HashMap<>();
				if (read.history == null) read.history = new ArrayList<>();
				store = read;
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the tournaments file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(store));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the tournaments file: {}", e.toString());
		}
	}

	/** For the self-test: forget everything, in memory and on disk. */
	public static synchronized void resetForTest() {
		store = new Store();
		loaded = true;
		phase = null;
		ENTRANTS.clear();
		rounds = new Match[0][];
		champion = null;
		runnerUp = null;
		firstDelay = 200;
		nextDelay = 400;
		tieDelay = 300;
		grace = 2400;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	/** For the self-test: throw away what is in memory and read the file again. */
	public static synchronized void reloadForTest() {
		store = new Store();
		loaded = false;
		ensureLoaded();
	}

	/** For the self-test: short waits. */
	static void fastForTest() {
		firstDelay = 40;
		nextDelay = 40;
		tieDelay = 40;
		grace = 200;
	}

	static void useRandom(Random r) {
		random = r;
	}

	public static synchronized int titles(UUID id) {
		ensureLoaded();
		Record r = store.players.get(id.toString());
		return r == null ? 0 : r.titles;
	}

	// ------------------------------------------------------------------ sending

	private static void tell(MinecraftServer server, Entrant e, Component text) {
		ServerPlayer p = player(server, e);
		if (p != null) p.sendSystemMessage(text);
	}

	private static void tellEntrants(MinecraftServer server, Component text) {
		for (Entrant e : ENTRANTS) tell(server, e, text);
	}

	private static void tellEverybody(MinecraftServer server, Component text) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(text);
	}

	private static void showTitle(ServerPlayer p, String big, String small) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
		p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(small).withStyle(ChatFormatting.WHITE)));
		p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(big).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
	}

	// ------------------------------------------------------------------ signing up

	private static String canPlay(ServerPlayer p) {
		if (BotDuel.isBot(p.getUUID())) return "Bots don't play tournaments.";
		if (Party.inParty(p.getUUID())) return "Leave your party first (/party leave): a tournament is 1v1.";
		return null;
	}

	/** Opens a tournament. {@code kit}: a public kit, or null / "random". Returns what went wrong, or null. */
	public static String create(ServerPlayer p, int max, int to, String kit) {
		ensureLoaded();
		if (phase != null) {
			return "There is already a tournament (" + hostName + "'s). /tournament shows it" + (phase == Phase.SIGNUP ? "; /tournament join to play." : ".");
		}
		String problem = canPlay(p);
		if (problem != null) return problem;
		String chosen = null;
		if (kit != null && !kit.equalsIgnoreCase("random")) {
			var entry = PublicKits.find(kit);
			if (entry.isEmpty()) return "There is no public kit named '" + kit + "'. Use random, or one of the public kits (/publicate puts a kit on the shelf).";
			chosen = entry.get().name();
		}
		MinecraftServer server = p.level().getServer();
		phase = Phase.SIGNUP;
		host = p.getUUID();
		hostName = p.getName().getString();
		maxPlayers = Math.max(MIN_PLAYERS, Math.min(MAX_PLAYERS, max));
		firstTo = Math.max(1, Math.min(5, to));
		kitName = chosen;
		ENTRANTS.clear();
		ENTRANTS.add(new Entrant(p.getUUID(), hostName));
		rounds = new Match[0][];
		champion = null;
		runnerUp = null;
		openedAt = tick;
		Component open = Component.literal("  Up to " + maxPlayers + " players, first to " + firstTo + ", " + kitLabel() + ".  ")
			.withStyle(ChatFormatting.GRAY)
			.append(Msg.button("[JOIN]", ChatFormatting.GREEN, "/tournament join", "Join the tournament"));
		tellEverybody(server, Msg.title(hostName + " opened a tournament!"));
		tellEverybody(server, open);
		p.sendSystemMessage(Msg.info("  When 3 or more are in, /tournament start begins it (it starts by itself when it is full)."));
		PvpDuelsMod.LOGGER.info("Tournament opened by {} (up to {}, first to {}, {})", hostName, maxPlayers, firstTo, kitLabel());
		return null;
	}

	public static String join(ServerPlayer p) {
		if (phase == null) return "There is no tournament. /tournament create opens one.";
		if (phase != Phase.SIGNUP) return "The tournament already started.";
		if (find(p.getUUID()) != null) return "You are already in. Wait for the host to start it.";
		if (ENTRANTS.size() >= maxPlayers) return "The tournament is full.";
		String problem = canPlay(p);
		if (problem != null) return problem;
		MinecraftServer server = p.level().getServer();
		ENTRANTS.add(new Entrant(p.getUUID(), p.getName().getString()));
		tellEntrants(server, Msg.ok(p.getName().getString() + " joined the tournament (" + ENTRANTS.size() + "/" + maxPlayers + ")."));
		if (ENTRANTS.size() >= maxPlayers) {
			tellEntrants(server, Msg.info("The tournament is full, so it starts now."));
			begin(server);
		}
		return null;
	}

	/** Leaves a tournament that has not started, or quits one that is going on (you lose the match you are in). */
	public static String leave(ServerPlayer p) {
		Entrant e = find(p.getUUID());
		if (phase == null || e == null) return "You are not in a tournament.";
		MinecraftServer server = p.level().getServer();
		if (phase == Phase.SIGNUP) {
			ENTRANTS.remove(e);
			p.sendSystemMessage(Msg.ok("You left the tournament."));
			if (e.id.equals(host)) cancel(server, hostName + " (the host) left, so the tournament was cancelled.");
			else tellEntrants(server, Msg.info(e.name + " left the tournament (" + ENTRANTS.size() + "/" + maxPlayers + ")."));
			return null;
		}
		if (e.out || e.withdrawn) return "You are already out of the tournament.";
		e.withdrawn = true;
		p.sendSystemMessage(Msg.ok("You quit the tournament."));
		tellEntrants(server, Msg.info(e.name + " quit the tournament."));
		Duel duel = DuelManager.duelOf(p.getUUID());
		if (duel != null && duel.tournament && duel.phase != Duel.Phase.ENDED) DuelManager.leave(p);
		return null;
	}

	public static String start(ServerPlayer p, boolean admin) {
		if (phase == null) return "There is no tournament. /tournament create opens one.";
		if (phase != Phase.SIGNUP) return "The tournament already started.";
		if (!admin && !p.getUUID().equals(host)) return "Only " + hostName + " (or an admin) can start it.";
		if (ENTRANTS.size() < MIN_PLAYERS) return "At least " + MIN_PLAYERS + " players are needed (now " + ENTRANTS.size() + ").";
		begin(p.level().getServer());
		return null;
	}

	public static String cancel(ServerPlayer p, boolean admin) {
		if (phase == null) return "There is no tournament.";
		if (!admin && !p.getUUID().equals(host)) return "Only " + hostName + " (or an admin) can cancel it.";
		cancel(p.level().getServer(), p.getName().getString() + " cancelled the tournament.");
		return null;
	}

	private static void cancel(MinecraftServer server, String why) {
		if (phase == null) return;
		tellEntrants(server, Msg.err(why));
		PvpDuelsMod.LOGGER.info("Tournament cancelled: {}", why);
		phase = null;
		ENTRANTS.clear();
		rounds = new Match[0][];
	}

	// ------------------------------------------------------------------ the bracket

	/** Who plays whom in the first round: 1 against the last, 4 against 5, 2 against 7, 3 against 6 (for 8). */
	static int[] seedOrder(int size) {
		int[] order = {1, 2};
		while (order.length < size) {
			int[] next = new int[order.length * 2];
			int total = order.length * 2 + 1;
			for (int i = 0; i < order.length; i++) {
				next[2 * i] = order[i];
				next[2 * i + 1] = total - order[i];
			}
			order = next;
		}
		return order;
	}

	private static void begin(MinecraftServer server) {
		phase = Phase.RUNNING;
		List<Entrant> seeded = new ArrayList<>(ENTRANTS);
		Collections.shuffle(seeded, random);
		seeded.sort((x, y) -> Integer.compare(StatsStore.get(y.id).elo, StatsStore.get(x.id).elo));
		for (int i = 0; i < seeded.size(); i++) seeded.get(i).seed = i + 1;
		int n = seeded.size();
		int size = 4;
		while (size < n) size *= 2;
		bracketSize = size;
		int count = Integer.numberOfTrailingZeros(size);
		rounds = new Match[count][];
		for (int r = 0; r < count; r++) {
			rounds[r] = new Match[size >> (r + 1)];
			for (int i = 0; i < rounds[r].length; i++) rounds[r][i] = new Match(r, i);
		}
		int[] order = seedOrder(size);
		for (int i = 0; i < rounds[0].length; i++) {
			Match m = rounds[0][i];
			int sa = order[2 * i];
			int sb = order[2 * i + 1];
			m.a = sa <= n ? seeded.get(sa - 1) : null;
			m.b = sb <= n ? seeded.get(sb - 1) : null;
			m.bye = m.a == null || m.b == null;
		}
		champion = null;
		runnerUp = null;
		tellEverybody(server, Msg.title("The tournament started! " + n + " players, first to " + firstTo + ", " + kitLabel() + "."));
		tellEverybody(server, Component.literal("  Follow it with /tournament, watch a match with /duels. ").withStyle(ChatFormatting.GRAY)
			.append(Msg.button("[BRACKET]", ChatFormatting.AQUA, "/tournament bracket", "Show the bracket")));
		PvpDuelsMod.LOGGER.info("Tournament started with {} players", n);
		for (Match m : rounds[0]) {
			if (m.bye) byeFor(server, m);
		}
		for (Match m : rounds[0]) {
			if (!m.bye) makeReady(server, m, firstDelay);
		}
		for (Entrant e : ENTRANTS) {
			ServerPlayer p = player(server, e);
			if (p == null) continue;
			p.sendSystemMessage(Msg.info("You are seed " + e.seed + " of " + n + "."));
			for (Component line : describe()) p.sendSystemMessage(line);
		}
	}

	private static void byeFor(MinecraftServer server, Match m) {
		int side = m.a != null ? 0 : 1;
		Entrant e = side == 0 ? m.a : m.b;
		m.winner = side;
		m.state = State.DONE;
		m.how = "bye";
		tell(server, e, Msg.ok("You have a bye in the first round: you go straight to the " + roundName(1) + "."));
		place(server, m, e);
	}

	/** Puts the winner of a match in its place in the next round. */
	private static void place(MinecraftServer server, Match from, Entrant e) {
		if (from.round >= rounds.length - 1) return;
		Match next = rounds[from.round + 1][from.index / 2];
		if (from.index % 2 == 0) next.a = e;
		else next.b = e;
		if (next.a != null && next.b != null) makeReady(server, next, nextDelay);
	}

	private static void makeReady(MinecraftServer server, Match m, int delay) {
		m.state = State.READY;
		m.startAt = tick + delay;
		m.waited = 0;
		tell(server, m.a, Msg.title(roundName(m.round) + ": you play " + m.b.name + " in " + Math.max(1, delay / 20) + " s."));
		tell(server, m.b, Msg.title(roundName(m.round) + ": you play " + m.a.name + " in " + Math.max(1, delay / 20) + " s."));
	}

	// ------------------------------------------------------------------ every tick

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Tournament::onTick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (phase != Phase.SIGNUP) return;
			Entrant e = find(handler.player.getUUID());
			if (e == null) return;
			ENTRANTS.remove(e);
			if (e.id.equals(host)) cancel(server, hostName + " (the host) left, so the tournament was cancelled.");
			else tellEntrants(server, Msg.info(e.name + " left the game, so they are out of the tournament (" + ENTRANTS.size() + "/" + maxPlayers + ")."));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			phase = null;
			ENTRANTS.clear();
			rounds = new Match[0][];
		});
	}

	private static void onTick(MinecraftServer server) {
		tick++;
		if (phase == null || tick % 10 != 0) return;
		if (phase == Phase.SIGNUP) {
			if (tick - openedAt > SIGNUP_TIMEOUT) cancel(server, "Nobody started the tournament in 10 minutes, so it was cancelled.");
			return;
		}
		boolean anybody = false;
		for (Entrant e : ENTRANTS) if (!e.out && !e.withdrawn) anybody = true;
		if (!anybody) {
			cancel(server, "Everybody left, so the tournament was cancelled.");
			return;
		}
		for (Match[] row : rounds) {
			for (Match m : row) {
				if (phase == null) return;
				step(server, m);
			}
		}
	}

	private static void step(MinecraftServer server, Match m) {
		if (m.state == State.RUNNING) {
			// the duel is gone without telling us (an admin closed it, for example): play it again
			if (m.duel != null && DuelManager.duelOf(m.a.id) != m.duel && DuelManager.duelOf(m.b.id) != m.duel) retryAfterVoid(server, m, "The duel was closed");
			return;
		}
		if (m.state != State.READY) return;
		if (m.a.withdrawn || m.b.withdrawn) {
			int side = m.a.withdrawn && m.b.withdrawn ? betterSeed(m) : (m.a.withdrawn ? 1 : 0);
			finishMatch(server, m, side, "walkover");
			return;
		}
		ServerPlayer pa = player(server, m.a);
		ServerPlayer pb = player(server, m.b);
		if (tick < m.startAt) {
			int seconds = Math.max(1, (m.startAt - tick + 19) / 20);
			if (pa != null) pa.sendOverlayMessage(Component.literal("Tournament: you play " + m.b.name + " in " + seconds + " s").withStyle(ChatFormatting.GOLD));
			if (pb != null) pb.sendOverlayMessage(Component.literal("Tournament: you play " + m.a.name + " in " + seconds + " s").withStyle(ChatFormatting.GOLD));
			return;
		}
		// who is not ready?
		boolean[] bad = new boolean[2];
		String[] why = new String[2];
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = i == 0 ? pa : pb;
			Entrant e = i == 0 ? m.a : m.b;
			if (p == null) {
				bad[i] = true;
				why[i] = e.name + " is not online";
				continue;
			}
			if (Spectators.isIn(p.getUUID())) Spectators.exit(p);
			if (Party.inParty(p.getUUID())) {
				bad[i] = true;
				why[i] = e.name + " is in a party (/party leave)";
			} else if (DuelManager.isBusy(p.getUUID())) {
				bad[i] = true;
				why[i] = e.name + " is busy (use /leave to finish what you are doing)";
			}
		}
		if (bad[0] || bad[1]) {
			m.waited += 10;
			if (m.waited == 10 || m.waited % 300 == 0) {
				int left = Math.max(0, (grace - m.waited) / 20);
				String reason = bad[0] && bad[1] ? why[0] + " and " + why[1] : (bad[0] ? why[0] : why[1]);
				Component text = Msg.err("Your tournament match is waiting: " + reason + ". " + left + " s left before the match is lost.");
				tell(server, m.a, text);
				tell(server, m.b, text);
			}
			if (m.waited >= grace) {
				int side = bad[0] == bad[1] ? betterSeed(m) : (bad[0] ? 1 : 0);
				finishMatch(server, m, side, bad[0] == bad[1] ? "seed" : "forfeit");
			}
			return;
		}
		if (!DuelManager.hasFreeSlot()) {
			if (m.waited == 0) {
				Component text = Msg.info("All the arenas are busy; your tournament match starts as soon as one is free.");
				tell(server, m.a, text);
				tell(server, m.b, text);
			}
			m.waited = Math.max(m.waited, 10);
			return;
		}
		m.waited = 0;
		String problem = Queue.startForTournament(server, pa, pb, kitName, firstTo);
		if (problem != null) {
			m.failures++;
			PvpDuelsMod.LOGGER.warn("Tournament: could not start {} vs {}: {}", m.a.name, m.b.name, problem);
			if (m.failures >= 3) {
				Component text = Msg.err("The match could not be played (" + problem + "), so the better seed goes on.");
				tell(server, m.a, text);
				tell(server, m.b, text);
				finishMatch(server, m, betterSeed(m), "seed");
			} else {
				m.startAt = tick + 100;
			}
			return;
		}
		m.duel = DuelManager.duelOf(pa.getUUID());
		m.state = State.RUNNING;
		Component line = Msg.title(roundName(m.round) + ": " + m.a.name + " vs " + m.b.name)
			.copy().append(Component.literal("  (first to " + firstTo + ", kit " + m.duel.config.kit() + ")").withStyle(ChatFormatting.GRAY));
		tell(server, m.a, line);
		tell(server, m.b, line);
		for (Entrant e : ENTRANTS) {
			if (e == m.a || e == m.b) continue;
			ServerPlayer watcher = player(server, e);
			if (watcher != null) {
				watcher.sendSystemMessage(Component.literal("  " + roundName(m.round) + ": " + m.a.name + " vs " + m.b.name + " has started. ").withStyle(ChatFormatting.GRAY)
					.append(Msg.button("[WATCH]", ChatFormatting.AQUA, "/watch " + m.a.name, "Watch this match")));
			}
		}
	}

	private static int betterSeed(Match m) {
		return m.a.seed <= m.b.seed ? 0 : 1;
	}

	private static void retryAfterVoid(MinecraftServer server, Match m, String why) {
		m.ties++;
		m.duel = null;
		if (m.ties >= 2) {
			Component text = Msg.info(why + " twice, so the better seed goes on.");
			tell(server, m.a, text);
			tell(server, m.b, text);
			finishMatch(server, m, betterSeed(m), "seed");
			return;
		}
		Component text = Msg.info(why + ", so the match is played again in " + Math.max(1, tieDelay / 20) + " s.");
		tell(server, m.a, text);
		tell(server, m.b, text);
		m.state = State.READY;
		m.startAt = tick + tieDelay;
		m.waited = 0;
	}

	// ------------------------------------------------------------------ results

	/** A tournament duel is over (called by the duel when it finishes). */
	static void onDuelFinished(Duel d) {
		if (phase != Phase.RUNNING) return;
		Match found = null;
		for (Match[] row : rounds) {
			for (Match m : row) {
				if (m.state == State.RUNNING && m.duel == d) found = m;
			}
		}
		if (found == null) return;
		MinecraftServer server = d.server();
		Match m = found;
		boolean sameOrder = d.ids[0].equals(m.a.id);
		m.scoreA = d.points[sameOrder ? 0 : 1];
		m.scoreB = d.points[sameOrder ? 1 : 0];
		if (d.winner == 0 || d.winner == 1) {
			int side = (d.winner == 0) == sameOrder ? 0 : 1;
			finishMatch(server, m, side, "");
			return;
		}
		retryAfterVoid(server, m, d.winner == -1 ? "It was a tie" : "The duel did not finish");
	}

	private static void finishMatch(MinecraftServer server, Match m, int side, String how) {
		Entrant win = side == 0 ? m.a : m.b;
		Entrant lose = side == 0 ? m.b : m.a;
		m.winner = side;
		m.state = State.DONE;
		m.how = how;
		m.duel = null;
		lose.out = true;
		boolean last = m.round >= rounds.length - 1;
		String score = (m.scoreA + m.scoreB > 0) ? " " + (side == 0 ? m.scoreA : m.scoreB) + "-" + (side == 0 ? m.scoreB : m.scoreA) : "";
		String suffix = how.isEmpty() ? "" : " (" + how + ")";
		Component result = Msg.info("  " + roundName(m.round) + ": " + win.name + " beat " + lose.name + score + suffix + ".");
		tellEntrants(server, result);
		if (last) {
			champion = win;
			runnerUp = lose;
			tell(server, lose, Msg.ok("You lost the final: second place. Well played!"));
			finishTournament(server);
			return;
		}
		tell(server, win, Msg.ok("You go through to the " + roundName(m.round + 1) + "."));
		tell(server, lose, Msg.err("You are out of the tournament (" + roundName(m.round) + "). You can still watch: /duels."));
		place(server, m, win);
	}

	private static void finishTournament(MinecraftServer server) {
		ensureLoaded();
		int players = ENTRANTS.size();
		for (Entrant e : ENTRANTS) {
			Record r = store.players.computeIfAbsent(e.id.toString(), k -> new Record());
			r.name = e.name;
			r.played++;
		}
		store.players.get(champion.id.toString()).titles++;
		store.players.get(champion.id.toString()).finals++;
		store.players.get(runnerUp.id.toString()).finals++;
		Past past = new Past();
		past.when = System.currentTimeMillis();
		past.champion = champion.name;
		past.runnerUp = runnerUp.name;
		past.players = players;
		past.firstTo = firstTo;
		past.kit = kitName == null ? "random" : kitName;
		store.history.add(0, past);
		while (store.history.size() > 20) store.history.remove(store.history.size() - 1);
		save();
		PvpDuelsMod.LOGGER.info("Tournament over: {} won, {} second ({} players)", champion.name, runnerUp.name, players);
		tellEverybody(server, Msg.title("TOURNAMENT OVER: " + champion.name + " is the champion!"));
		tellEverybody(server, Msg.info("  Second place: " + runnerUp.name + ". " + champion.name + " has won " + store.players.get(champion.id.toString()).titles
			+ (store.players.get(champion.id.toString()).titles == 1 ? " tournament." : " tournaments.") + "  /tournament top"));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getUUID().equals(champion.id)) showTitle(p, "CHAMPION", "You won the tournament!");
			else showTitle(p, champion.name + " WINS", "the tournament");
		}
		phase = null;
		lastLines = describe();
		ENTRANTS.clear();
	}

	/** The bracket of the last finished tournament, so /tournament can still show it. */
	private static List<Component> lastLines = new ArrayList<>();

	// ------------------------------------------------------------------ showing it

	static List<Component> describe() {
		List<Component> out = new ArrayList<>();
		if (phase == Phase.SIGNUP) {
			out.add(Msg.title("Tournament of " + hostName + " (signing up " + ENTRANTS.size() + "/" + maxPlayers + ")"));
			out.add(Msg.info("  First to " + firstTo + ", " + kitLabel() + "."));
			StringBuilder names = new StringBuilder();
			for (Entrant e : ENTRANTS) names.append(names.length() == 0 ? "" : ", ").append(e.name);
			out.add(Msg.info("  Players: " + names));
			out.add(Component.literal("  ").append(Msg.button("[JOIN]", ChatFormatting.GREEN, "/tournament join", "Join the tournament"))
				.append(Component.literal(" ")).append(Msg.button("[START]", ChatFormatting.GOLD, "/tournament start", "The host starts it (3 players or more)")));
			return out;
		}
		out.add(Msg.title("Tournament - " + (phase == null ? "finished" : ENTRANTS.size() + " players, first to " + firstTo + ", " + kitLabel())));
		for (Match[] row : rounds) {
			out.add(Msg.info(roundName(row[0].round) + ":").copy().withStyle(ChatFormatting.YELLOW));
			for (Match m : row) out.add(line(m));
		}
		return out;
	}

	private static String nameOf(Match m, boolean first) {
		Entrant e = first ? m.a : m.b;
		if (e != null) return e.name;
		return m.round == 0 ? "bye" : "?";
	}

	private static Component line(Match m) {
		String a = nameOf(m, true);
		String b = nameOf(m, false);
		switch (m.state) {
			case DONE -> {
				if (m.bye) return Msg.info("  " + (m.winner == 0 ? a : b) + " goes through (bye)");
				String win = m.winner == 0 ? a : b;
				return Msg.info("  " + a + " " + m.scoreA + " - " + m.scoreB + " " + b + "  ->  " + win + (m.how.isEmpty() ? "" : " (" + m.how + ")"));
			}
			case RUNNING -> {
				int pa = 0;
				int pb = 0;
				if (m.duel != null) {
					boolean same = m.duel.ids[0].equals(m.a.id);
					pa = m.duel.points[same ? 0 : 1];
					pb = m.duel.points[same ? 1 : 0];
				}
				return Component.literal("  " + a + " vs " + b + "  FIGHTING " + pa + " - " + pb + "  ").withStyle(ChatFormatting.GREEN)
					.append(Msg.button("[WATCH]", ChatFormatting.AQUA, "/watch " + a, "Watch this match"));
			}
			case READY -> {
				int seconds = Math.max(0, (m.startAt - tick + 19) / 20);
				return Msg.info("  " + a + " vs " + b + (m.waited > 0 ? "  waiting for the players" : "  starts in " + seconds + " s"));
			}
			default -> {
				return Msg.info("  " + a + " vs " + b);
			}
		}
	}

	/** For the self-test. */
	static List<String> linesForTest() {
		List<String> out = new ArrayList<>();
		for (Component c : describe()) out.add(c.getString());
		return out;
	}

	/** For the self-test: {first, second, state} of every match that has two known players and is not done. */
	static List<String[]> openMatchesForTest() {
		List<String[]> out = new ArrayList<>();
		for (Match[] row : rounds) {
			for (Match m : row) {
				if (m.a != null && m.b != null && m.state != State.DONE) out.add(new String[] {m.a.name, m.b.name, m.state.name(), roundName(m.round)});
			}
		}
		return out;
	}

	static int entrantsForTest() {
		return ENTRANTS.size();
	}

	static String phaseForTest() {
		return phase == null ? "none" : phase.name();
	}

	static String championForTest() {
		return champion == null ? null : champion.name;
	}

	static int seedOfForTest(String name) {
		for (Entrant e : ENTRANTS) if (e.name.equals(name)) return e.seed;
		return -1;
	}

	static int bracketSizeForTest() {
		return bracketSize;
	}

	// ------------------------------------------------------------------ commands

	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tournament")
			.executes(ctx -> show(ctx.getSource()))
			.then(Commands.literal("bracket").executes(ctx -> show(ctx.getSource())))
			.then(Commands.literal("create")
				.executes(ctx -> answer(ctx.getSource(), create(ctx.getSource().getPlayerOrException(), MAX_PLAYERS, 3, null)))
				.then(Commands.argument("players", IntegerArgumentType.integer(MIN_PLAYERS, MAX_PLAYERS))
					.executes(ctx -> answer(ctx.getSource(), create(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "players"), 3, null)))
					.then(Commands.argument("firstTo", IntegerArgumentType.integer(1, 5))
						.executes(ctx -> answer(ctx.getSource(), create(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "players"),
							IntegerArgumentType.getInteger(ctx, "firstTo"), null)))
						.then(Commands.argument("kit", StringArgumentType.word())
							.suggests((ctx, builder) -> {
								List<String> names = new ArrayList<>(PublicKits.names());
								names.add("random");
								return SharedSuggestionProvider.suggest(names, builder);
							})
							.executes(ctx -> answer(ctx.getSource(), create(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "players"),
								IntegerArgumentType.getInteger(ctx, "firstTo"), StringArgumentType.getString(ctx, "kit"))))))))
			.then(Commands.literal("join").executes(ctx -> answer(ctx.getSource(), join(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("leave").executes(ctx -> answer(ctx.getSource(), leave(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("start").executes(ctx -> answer(ctx.getSource(), start(ctx.getSource().getPlayerOrException(), IS_ADMIN.test(ctx.getSource())))))
			.then(Commands.literal("cancel").executes(ctx -> answer(ctx.getSource(), cancel(ctx.getSource().getPlayerOrException(), IS_ADMIN.test(ctx.getSource())))))
			.then(Commands.literal("top").executes(ctx -> top(ctx.getSource()))));
	}

	private static int answer(CommandSourceStack source, String problem) {
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}

	private static int show(CommandSourceStack source) {
		if (phase == null) {
			ensureLoaded();
			source.sendSuccess(() -> Msg.info("There is no tournament right now. /tournament create [players 3-8] [first to 1-5] [kit|random] opens one for your friends."), false);
			if (!lastLines.isEmpty()) {
				source.sendSuccess(() -> Msg.info("The last one:"), false);
				for (Component line : lastLines) source.sendSuccess(() -> line, false);
			}
			return 1;
		}
		for (Component line : describe()) source.sendSuccess(() -> line, false);
		return 1;
	}

	private static int top(CommandSourceStack source) {
		ensureLoaded();
		List<Record> all = new ArrayList<>(store.players.values());
		all.sort((x, y) -> y.titles != x.titles ? Integer.compare(y.titles, x.titles) : Integer.compare(y.finals, x.finals));
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("Nobody has won a tournament yet."), false);
			return 1;
		}
		source.sendSuccess(() -> Msg.title("Tournament champions"), false);
		int shown = 0;
		for (Record r : all) {
			if (shown++ >= 8) break;
			int rank = shown;
			source.sendSuccess(() -> Msg.info("  " + rank + ". " + r.name + ": " + r.titles + (r.titles == 1 ? " title" : " titles") + ", " + r.finals + " in the final, "
				+ r.played + " played"), false);
		}
		if (!store.history.isEmpty()) {
			source.sendSuccess(() -> Msg.title("Last tournaments"), false);
			int n = 0;
			for (Past past : store.history) {
				if (n++ >= 3) break;
				source.sendSuccess(() -> Msg.info(String.format(Locale.ROOT, "  %s won (second %s), %d players, first to %d, %s", past.champion, past.runnerUp, past.players, past.firstTo, past.kit)), false);
			}
		}
		return 1;
	}
}
