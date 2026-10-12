package com.pvpduels.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.ContestMenus;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The weekly building contest. Every contest has a category picked at random ("castle"): everybody can build it on a plot
 * for {@link #BUILD_DAYS} days, then the builds are shown anonymously and everybody votes for the best one for
 * {@link #VOTE_DAYS} days. The top three (with at least one vote) get the Builder rank for three weeks. Then a new contest
 * starts by itself. Admins can start or end one by hand with /contest start and /contest end.
 * The state is kept in pvpduels/contest.json and the builds in pvpduels/contest/.
 */
public final class Contest {
	public enum Phase { NONE, BUILD, VOTE }

	public static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
	public static final int BUILD_DAYS = 5;
	public static final int VOTE_DAYS = 2;
	/** A build needs this many blocks changed from the template to be an entry. */
	public static final int MIN_BLOCKS = 20;
	/** With fewer builds than this the contest is cancelled (there is nothing to vote for). */
	public static final int MIN_ENTRIES = 2;
	/** How many places win the Builder rank. */
	public static final int WINNERS = 3;

	private static final String[] CATEGORIES = {
		"castle", "pirate ship", "treehouse", "lighthouse", "windmill", "medieval village", "haunted house", "volcano",
		"floating island", "dragon", "robot", "space station", "underwater base", "desert temple", "snowy cabin",
		"train station", "bridge", "fountain", "giant statue", "farm", "city skyline", "jungle ruins", "wizard tower",
		"airship", "mountain fortress", "market square", "cozy cafe", "amusement park", "waterfall", "futuristic car"
	};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private static final class Winner {
		String id = "";
		String name = "";
		int votes;
		int place;
	}

	private static final class Result {
		int number;
		String category = "";
		/** Why there is no winner, or empty. */
		String note = "";
		int entries;
		int voters;
		List<Winner> winners = new ArrayList<>();
		/** Winners who were already told. */
		List<String> told = new ArrayList<>();
	}

	private static final class State {
		int number;
		String category = "";
		Phase phase = Phase.NONE;
		long buildEnd;
		long voteEnd;
		/** Player id -> name of everybody who handed in a build. */
		Map<String, String> entries = new LinkedHashMap<>();
		/** The ids of the builds in the order of their numbers (set when the voting opens). */
		List<String> order = new ArrayList<>();
		/** Voter id -> id of the builder voted for. */
		Map<String, String> votes = new HashMap<>();
		List<String> recent = new ArrayList<>();
		Result last;
	}

	private static State state = new State();
	private static boolean loaded;
	private static long fakeNow;
	private static Random random = new Random();

	private Contest() {}

	// ------------------------------------------------------------------ the file

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("contest.json");
	}

	private static long now() {
		return fakeNow != 0 ? fakeNow : System.currentTimeMillis();
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			State read = GSON.fromJson(Files.readString(file()), State.class);
			if (read != null) {
				if (read.phase == null) read.phase = Phase.NONE;
				if (read.entries == null) read.entries = new LinkedHashMap<>();
				if (read.order == null) read.order = new ArrayList<>();
				if (read.votes == null) read.votes = new HashMap<>();
				if (read.recent == null) read.recent = new ArrayList<>();
				state = read;
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the contest file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(state));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the contest file: {}", e.toString());
		}
	}

	/** For the self-test: a fake clock (0 = the real one). */
	public static void setClockForTest(long millis) {
		fakeNow = millis;
	}

	/** For the self-test: forget everything, builds included. */
	public static synchronized void resetForTest() {
		state = new State();
		loaded = true;
		fakeNow = 0;
		ArenaStore.clearContest();
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	// ------------------------------------------------------------------ questions

	public static synchronized Phase phase() {
		ensureLoaded();
		return state.phase;
	}

	public static boolean building() {
		return phase() == Phase.BUILD;
	}

	public static boolean voting() {
		return phase() == Phase.VOTE;
	}

	public static synchronized String category() {
		ensureLoaded();
		return state.category;
	}

	public static synchronized int number() {
		ensureLoaded();
		return state.number;
	}

	/** "3 days left", "5 hours left" and so on for the phase that is running. */
	public static synchronized String timeLeft() {
		ensureLoaded();
		long end = state.phase == Phase.BUILD ? state.buildEnd : state.phase == Phase.VOTE ? state.voteEnd : 0;
		long left = end - now();
		if (state.phase == Phase.NONE) return "no time";
		if (left <= 0) return "a moment left";
		long days = left / DAY_MILLIS;
		if (days >= 1) return days + (days == 1 ? " day left" : " days left");
		long hours = left / (60L * 60 * 1000);
		if (hours >= 1) return hours + (hours == 1 ? " hour left" : " hours left");
		return "less than an hour left";
	}

	public static synchronized boolean hasEntry(UUID id) {
		ensureLoaded();
		return state.entries.containsKey(id.toString());
	}

	public static synchronized int entryCount() {
		ensureLoaded();
		return state.entries.size();
	}

	/** The number (1, 2, 3...) a build has in the voting, or 0. */
	public static synchronized int numberOf(UUID id) {
		ensureLoaded();
		return state.order.indexOf(id.toString()) + 1;
	}

	public static synchronized UUID ownerOf(int number) {
		ensureLoaded();
		if (number < 1 || number > state.order.size()) return null;
		try {
			return UUID.fromString(state.order.get(number - 1));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The ids of the builds in the order of their numbers. */
	public static synchronized List<UUID> gallery() {
		ensureLoaded();
		List<UUID> list = new ArrayList<>();
		for (String id : state.order) {
			try {
				list.add(UUID.fromString(id));
			} catch (IllegalArgumentException ignored) {
				// a damaged entry in the file
			}
		}
		return list;
	}

	public static synchronized int votesFor(UUID id) {
		ensureLoaded();
		int n = 0;
		for (String voted : state.votes.values()) if (voted.equals(id.toString())) n++;
		return n;
	}

	/** Whose build this player voted for, or null. */
	public static synchronized UUID votedFor(UUID voter) {
		ensureLoaded();
		String id = state.votes.get(voter.toString());
		if (id == null) return null;
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static synchronized int voterCount() {
		ensureLoaded();
		return state.votes.size();
	}

	/** The id of the winner of this place (1, 2, 3) in the last contest, or null. */
	public static synchronized UUID lastWinner(int place) {
		ensureLoaded();
		if (state.last == null) return null;
		for (Winner w : state.last.winners) {
			if (w.place == place) {
				try {
					return UUID.fromString(w.id);
				} catch (IllegalArgumentException e) {
					return null;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ the builds

	private static net.minecraft.world.level.block.state.BlockState[] flat;

	/** How many blocks of the build are different from the flat template everybody starts with. */
	public static synchronized int changed(ArenaData data) {
		if (flat == null) flat = ArenaTemplates.build(ArenaTemplates.Kind.FLAT);
		int n = 0;
		for (int i = 0; i < ArenaData.VOLUME; i++) if (data.blocks[i] != flat[i]) n++;
		return n;
	}

	public static ArenaData loadEntry(UUID id) {
		try {
			return ArenaStore.load(ArenaStore.contestKey(id)).orElse(null);
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not read a contest build: {}", e.toString());
			return null;
		}
	}

	public static synchronized void saveEntry(UUID id, String name, ArenaData data) throws IOException {
		ensureLoaded();
		ArenaStore.save(data, ArenaStore.contestKey(id));
		boolean first = state.entries.put(id.toString(), name) == null;
		save();
		if (first) com.pvpduels.achievements.Achievements.add(id, name, "contest_entries", 1);
	}

	public static synchronized void removeEntry(UUID id) {
		ensureLoaded();
		try {
			ArenaStore.delete(ArenaStore.contestKey(id));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not delete a contest build: {}", e.toString());
		}
		if (state.entries.remove(id.toString()) != null) save();
	}

	/** Votes for a build. Returns an error text or null. */
	public static synchronized String vote(ServerPlayer voter, int number) {
		ensureLoaded();
		if (state.phase != Phase.VOTE) return "The voting is not open. /contest tells you what is going on.";
		UUID owner = ownerOf(number);
		if (owner == null) return "There is no build number " + number + ". There are " + state.order.size() + " builds.";
		if (owner.equals(voter.getUUID())) return "You can't vote for your own build.";
		String before = state.votes.put(voter.getUUID().toString(), owner.toString());
		save();
		if (before == null) com.pvpduels.achievements.Achievements.add(voter.getUUID(), voter.getName().getString(), "contest_votes", 1);
		voter.sendSystemMessage(Msg.ok("Your vote goes to build #" + number + (before != null && !before.equals(owner.toString()) ? " (you changed your vote)" : "")
			+ ". You can change it until the voting ends."));
		return null;
	}

	// ------------------------------------------------------------------ the phases

	/** Called every second: moves the contest on when its time is up. */
	public static void advance(MinecraftServer server) {
		synchronized (Contest.class) {
			ensureLoaded();
			long t = now();
			if (state.phase == Phase.NONE) startNew(server, null);
			else if (state.phase == Phase.BUILD && t >= state.buildEnd) startVoting(server);
			else if (state.phase == Phase.VOTE && t >= state.voteEnd) finish(server);
		}
	}

	/** Admin: skips to the next phase now. Returns a text to tell the admin. */
	public static synchronized String skip(MinecraftServer server) {
		ensureLoaded();
		switch (state.phase) {
			case BUILD -> {
				startVoting(server);
				return "The building time is over.";
			}
			case VOTE -> {
				finish(server);
				return "The voting is over.";
			}
			default -> {
				startNew(server, null);
				return "A new contest started.";
			}
		}
	}

	/** Starts a new contest (the category is picked at random when it is null). Whatever was going on is dropped. */
	public static synchronized void startNew(MinecraftServer server, String category) {
		ensureLoaded();
		ArenaStudio.exitAll(server, ArenaStudio.Mode.CONTEST, ArenaStudio.Mode.VIEW);
		ArenaStore.clearContest();
		state.number++;
		state.category = category != null && !category.isBlank() ? category.strip() : pickCategory();
		state.recent.add(state.category.toLowerCase(java.util.Locale.ROOT));
		while (state.recent.size() > 5) state.recent.remove(0);
		state.phase = Phase.BUILD;
		state.buildEnd = now() + BUILD_DAYS * DAY_MILLIS;
		state.voteEnd = 0;
		state.entries.clear();
		state.order.clear();
		state.votes.clear();
		save();
		broadcast(server, Msg.title("Building contest #" + state.number + ": " + state.category));
		broadcast(server, Msg.info("  Everybody can build \"" + state.category + "\" for " + BUILD_DAYS + " days, then everybody votes. The top " + WINNERS
			+ " become Builders for " + BuilderRank.DEFAULT_WEEKS + " weeks and can make their own arenas. ")
			.copy().append(Msg.button("[JOIN]", ChatFormatting.GREEN, "/contest enter", "Go to your plot and start building")));
	}

	private static String pickCategory() {
		List<String> options = new ArrayList<>();
		for (String c : CATEGORIES) if (!state.recent.contains(c)) options.add(c);
		if (options.isEmpty()) options.addAll(List.of(CATEGORIES));
		return options.get(random.nextInt(options.size()));
	}

	private static void startVoting(MinecraftServer server) {
		ArenaStudio.exitAll(server, ArenaStudio.Mode.CONTEST); // keeps what they were building
		List<String> ids = new ArrayList<>();
		for (String id : state.entries.keySet()) {
			try {
				if (ArenaStore.exists(ArenaStore.contestKey(UUID.fromString(id)))) ids.add(id);
			} catch (IllegalArgumentException ignored) {
				// a damaged entry in the file
			}
		}
		if (ids.size() < MIN_ENTRIES) {
			Result r = new Result();
			r.number = state.number;
			r.category = state.category;
			r.entries = ids.size();
			r.note = "Cancelled: only " + ids.size() + (ids.size() == 1 ? " build was" : " builds were") + " handed in and at least " + MIN_ENTRIES + " are needed.";
			state.last = r;
			broadcast(server, Msg.info("Building contest #" + r.number + " (" + r.category + ") was cancelled: not enough builds. A new one starts now."));
			startNew(server, null);
			return;
		}
		Collections.shuffle(ids, random);
		state.order = ids;
		state.votes.clear();
		state.phase = Phase.VOTE;
		state.voteEnd = now() + VOTE_DAYS * DAY_MILLIS;
		save();
		broadcast(server, Msg.title("Building contest #" + state.number + ": time to vote!"));
		broadcast(server, Msg.info("  " + ids.size() + " builds of \"" + state.category + "\". Visit them and vote for the best one: " + VOTE_DAYS + " days. ")
			.copy().append(Msg.button("[VOTE]", ChatFormatting.GOLD, "/contest vote", "Open the gallery")));
	}

	private static void finish(MinecraftServer server) {
		ArenaStudio.exitAll(server, ArenaStudio.Mode.VIEW);
		Map<String, Integer> counts = new HashMap<>();
		for (String voted : state.votes.values()) counts.merge(voted, 1, Integer::sum);
		List<String> ranked = new ArrayList<>(state.order);
		ranked.sort((x, y) -> Integer.compare(counts.getOrDefault(y, 0), counts.getOrDefault(x, 0))); // stable: a tie keeps the random order
		Result r = new Result();
		r.number = state.number;
		r.category = state.category;
		r.entries = state.order.size();
		r.voters = state.votes.size();
		for (int i = 0; i < ranked.size() && r.winners.size() < WINNERS; i++) {
			int votes = counts.getOrDefault(ranked.get(i), 0);
			if (votes < 1) break;
			Winner w = new Winner();
			w.id = ranked.get(i);
			w.name = state.entries.getOrDefault(w.id, "?");
			w.votes = votes;
			w.place = r.winners.size() + 1;
			r.winners.add(w);
			try {
				UUID winnerId = UUID.fromString(w.id);
				BuilderRank.grant(winnerId, BuilderRank.DEFAULT_WEEKS);
				com.pvpduels.achievements.Achievements.add(winnerId, w.name, "contest_podiums", 1);
				if (w.place == 1) com.pvpduels.achievements.Achievements.add(winnerId, w.name, "contest_wins", 1);
			} catch (IllegalArgumentException ignored) {
				// a damaged entry in the file
			}
		}
		if (r.winners.isEmpty()) r.note = "Nobody voted, so nobody won the Builder rank.";
		state.last = r;
		save();
		broadcast(server, Msg.title("Building contest #" + r.number + " (" + r.category + ") results"));
		printResult(r, line -> broadcast(server, line));
		for (Winner w : r.winners) {
			ServerPlayer p = null;
			try {
				p = server.getPlayerList().getPlayer(UUID.fromString(w.id));
			} catch (IllegalArgumentException ignored) {
				// a damaged entry in the file
			}
			if (p != null) tellWinner(p, r, w);
		}
		save();
		startNew(server, null);
	}

	private static void tellWinner(ServerPlayer p, Result r, Winner w) {
		if (r.told.contains(w.id)) return;
		r.told.add(w.id);
		p.sendSystemMessage(Msg.ok("You placed " + ordinal(w.place) + " in the building contest: you are a Builder for " + BuilderRank.DEFAULT_WEEKS
			+ " weeks (" + BuilderRank.timeLeft(p.getUUID()) + "). /createarena opens the arena studio."));
	}

	private static String ordinal(int place) {
		return switch (place) {
			case 1 -> "1st";
			case 2 -> "2nd";
			case 3 -> "3rd";
			default -> place + "th";
		};
	}

	private static void printResult(Result r, java.util.function.Consumer<Component> out) {
		if (!r.note.isEmpty()) out.accept(Msg.info("  " + r.note));
		for (Winner w : r.winners) {
			out.accept(Msg.info("  " + ordinal(w.place) + ": " + w.name + " - " + w.votes + (w.votes == 1 ? " vote" : " votes")));
		}
		if (!r.winners.isEmpty()) out.accept(Msg.info("  They are Builders for " + BuilderRank.DEFAULT_WEEKS + " weeks. " + r.entries + " builds, " + r.voters + " voters."));
	}

	private static void broadcast(MinecraftServer server, Component message) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(message);
	}

	// ------------------------------------------------------------------ talking to players

	/** What /contest says. */
	public static void show(ServerPlayer p) {
		State s;
		synchronized (Contest.class) {
			ensureLoaded();
			s = state;
		}
		UUID me = p.getUUID();
		if (s.phase == Phase.NONE) {
			p.sendSystemMessage(Msg.info("There is no building contest yet. One starts by itself in a moment."));
			return;
		}
		p.sendSystemMessage(Msg.title("Building contest #" + s.number + ": " + s.category));
		if (s.phase == Phase.BUILD) {
			p.sendSystemMessage(Msg.info("  Build \"" + s.category + "\" on your own plot. " + timeLeft() + " to build, then everybody votes. " + s.entries.size()
				+ (s.entries.size() == 1 ? " build" : " builds") + " so far."));
			boolean mine = hasEntry(me);
			p.sendSystemMessage(Msg.info("  " + (mine ? "You handed in a build: you can keep improving it. " : "You have no build yet. "))
				.copy().append(Msg.button(mine ? "[CONTINUE]" : "[JOIN]", ChatFormatting.GREEN, "/contest enter", "Go to your plot")));
		} else {
			p.sendSystemMessage(Msg.info("  Voting is open: " + timeLeft() + ". " + s.order.size() + " builds of \"" + s.category + "\", shown without names."));
			UUID voted = votedFor(me);
			p.sendSystemMessage(Msg.info("  " + (voted == null ? "You haven't voted yet. " : "You voted for build #" + numberOf(voted) + " (you can change it). "))
				.copy().append(Msg.button("[GALLERY]", ChatFormatting.GOLD, "/contest vote", "Visit the builds and vote")));
		}
		p.sendSystemMessage(Msg.info("  Prize: the top " + WINNERS + " become Builders for " + BuilderRank.DEFAULT_WEEKS + " weeks. /contest results shows the last contest."));
	}

	/** What a player sees when they arrive to look at a build. */
	public static void describeView(ServerPlayer p, int number, boolean own) {
		p.sendSystemMessage(Msg.title("Build #" + number + " of " + gallery().size() + " - \"" + category() + "\""));
		Component buttons;
		if (own) {
			p.sendSystemMessage(Msg.info("  This is your own build: you can't vote for it."));
			buttons = Component.literal("  ");
		} else {
			UUID voted = votedFor(p.getUUID());
			UUID here = ownerOf(number);
			boolean mine = voted != null && voted.equals(here);
			p.sendSystemMessage(Msg.info(mine ? "  You voted for this build." : "  Fly around and look at it. Like it?"));
			buttons = Component.literal("  ").copy().append(Msg.button(mine ? "[VOTED]" : "[VOTE FOR #" + number + "]", ChatFormatting.GREEN, "/contest vote " + number,
				"Your vote goes to this build")).append(Component.literal(" "));
		}
		p.sendSystemMessage(buttons.copy()
			.append(number > 1 ? Msg.button("[<]", ChatFormatting.YELLOW, "/contest view " + (number - 1), "Previous build") : Component.literal(""))
			.append(Component.literal(" "))
			.append(number < gallery().size() ? Msg.button("[>]", ChatFormatting.YELLOW, "/contest view " + (number + 1), "Next build") : Component.literal(""))
			.append(Component.literal(" "))
			.append(Msg.button("[GALLERY]", ChatFormatting.GOLD, "/contest vote", "All the builds"))
			.append(Component.literal(" "))
			.append(Msg.button("[LEAVE]", ChatFormatting.RED, "/leave", "Go back and get your inventory back")));
	}

	/** Told to everybody who joins the server. */
	private static void remind(ServerPlayer p) {
		synchronized (Contest.class) {
			ensureLoaded();
			if (state.phase == Phase.BUILD) {
				p.sendSystemMessage(Msg.info("Building contest: \"" + state.category + "\" (" + timeLeft() + " to build). /contest"));
			} else if (state.phase == Phase.VOTE) {
				p.sendSystemMessage(Msg.info("Building contest: the voting is open (" + timeLeft() + "). /contest"));
			}
			if (state.last != null) {
				for (Winner w : state.last.winners) {
					if (w.id.equals(p.getUUID().toString())) {
						tellWinner(p, state.last, w);
						save();
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ events and commands

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) advance(server);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> remind(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			synchronized (Contest.class) {
				state = new State();
				loaded = false; // the next server reads the file again
			}
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("contest")
			.executes(ctx -> {
				show(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.literal("enter")
				.executes(ctx -> ArenaStudio.answer(ctx.getSource(), ArenaStudio.enterContest(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("save")
				.executes(ctx -> ArenaStudio.answer(ctx.getSource(), ArenaStudio.saveContest(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("vote")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (!voting()) return ArenaStudio.answer(ctx.getSource(), "The voting is not open. /contest tells you what is going on.");
					ContestMenus.openGallery(p, 0);
					return 1;
				})
				.then(Commands.argument("build", IntegerArgumentType.integer(1, 999))
					.executes(ctx -> ArenaStudio.answer(ctx.getSource(), vote(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "build"))))))
			.then(Commands.literal("view")
				.then(Commands.argument("build", IntegerArgumentType.integer(1, 999))
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						UUID owner = ownerOf(IntegerArgumentType.getInteger(ctx, "build"));
						if (owner == null) return ArenaStudio.answer(ctx.getSource(), "There is no such build. /contest vote opens the gallery.");
						return ArenaStudio.answer(ctx.getSource(), ArenaStudio.visit(p, owner));
					})))
			.then(Commands.literal("results")
				.executes(ctx -> {
					Result r;
					synchronized (Contest.class) {
						ensureLoaded();
						r = state.last;
					}
					if (r == null) {
						ctx.getSource().sendSuccess(() -> Msg.info("No contest has ended yet."), false);
						return 0;
					}
					ctx.getSource().sendSuccess(() -> Msg.title("Building contest #" + r.number + " (" + r.category + ")"), false);
					printResult(r, line -> ctx.getSource().sendSuccess(() -> line, false));
					return 1;
				}))
			.then(Commands.literal("start")
				.requires(IS_ADMIN)
				.executes(ctx -> {
					startNew(ctx.getSource().getServer(), null);
					return 1;
				})
				.then(Commands.argument("category", StringArgumentType.greedyString())
					.executes(ctx -> {
						String category = StringArgumentType.getString(ctx, "category");
						if (category.length() > 40) return ArenaStudio.answer(ctx.getSource(), "Keep the category under 40 characters.");
						startNew(ctx.getSource().getServer(), category);
						return 1;
					})))
			.then(Commands.literal("end")
				.requires(IS_ADMIN)
				.executes(ctx -> {
					String said = skip(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Msg.ok(said), true);
					return 1;
				})));
	}
}
