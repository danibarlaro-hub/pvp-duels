package com.pvpduels.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Head to head: how every pair of players did against each other (wins, losses, ties, rounds, who is on a streak and the last duel).
 * /h2h lists your rivals, /h2h &lt;player&gt; shows one. Duels against a bot or a test dummy don't count. Saved in pvpduels/h2h.json.
 */
public final class HeadToHead {
	/** One pair. "Low" is the player whose id is smaller as text, so the pair has one key whoever challenged. */
	private static final class Pair {
		String lowName = "";
		String highName = "";
		int lowWins;
		int highWins;
		int draws;
		int lowRounds;
		int highRounds;
		long last;
		/** -1 a tie, 0 the low player won, 1 the high player won. */
		int lastWinner = -1;
		String lastKit = "";
		/** Who has won the last duels in a row: 0 low, 1 high, -1 nobody (after a tie). */
		int streakSide = -1;
		int streak;
	}

	/** What one player sees of a pair. */
	public record View(UUID other, String otherName, int wins, int losses, int draws, int roundsFor, int roundsAgainst, long last, int lastResult, String lastKit, int streak) {
		public int total() {
			return wins + losses + draws;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Pair> PAIRS = new HashMap<>();
	private static boolean loaded;
	private static final int LISTED = 8;

	private HeadToHead() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("h2h.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Pair> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Pair>>() {}.getType());
			if (read != null) {
				for (Pair p : read.values()) {
					if (p.lowName == null) p.lowName = "";
					if (p.highName == null) p.highName = "";
					if (p.lastKit == null) p.lastKit = "";
				}
				PAIRS.putAll(read);
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the head to head file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(PAIRS));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the head to head file: {}", e.toString());
		}
	}

	/** For the self-test: forget everything in memory and on disk. */
	public static synchronized void resetForTest() {
		PAIRS.clear();
		loaded = true;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	/** For the self-test: throw away what is in memory and read the file again. */
	public static synchronized void reloadForTest() {
		PAIRS.clear();
		loaded = false;
		ensureLoaded();
	}

	private static boolean firstIsLow(UUID a, UUID b) {
		return a.toString().compareTo(b.toString()) < 0;
	}

	private static String key(UUID a, UUID b) {
		return firstIsLow(a, b) ? a + "|" + b : b + "|" + a;
	}

	/** Saves one finished duel. winner: -1 a tie, 0 or 1 (the first or the second player). */
	public static synchronized void record(UUID id0, String name0, UUID id1, String name1, int winner, int points0, int points1, String kit) {
		ensureLoaded();
		Pair p = PAIRS.computeIfAbsent(key(id0, id1), k -> new Pair());
		boolean zeroIsLow = firstIsLow(id0, id1);
		p.lowName = zeroIsLow ? name0 : name1;
		p.highName = zeroIsLow ? name1 : name0;
		p.lowRounds += zeroIsLow ? points0 : points1;
		p.highRounds += zeroIsLow ? points1 : points0;
		int side = winner < 0 ? -1 : ((winner == 0) == zeroIsLow ? 0 : 1);
		if (side < 0) {
			p.draws++;
			p.streakSide = -1;
			p.streak = 0;
		} else {
			if (side == 0) p.lowWins++;
			else p.highWins++;
			if (p.streakSide == side) {
				p.streak++;
			} else {
				p.streakSide = side;
				p.streak = 1;
			}
		}
		p.lastWinner = side;
		p.last = System.currentTimeMillis();
		p.lastKit = kit == null ? "" : kit;
		save();
	}

	private static View viewOf(UUID me, UUID other, Pair p) {
		boolean meLow = firstIsLow(me, other);
		if (p == null) return new View(other, "", 0, 0, 0, 0, 0, 0, 0, "", 0);
		int wins = meLow ? p.lowWins : p.highWins;
		int losses = meLow ? p.highWins : p.lowWins;
		int mySide = meLow ? 0 : 1;
		int lastResult = p.lastWinner < 0 ? 0 : (p.lastWinner == mySide ? 1 : -1);
		int streak = p.streakSide < 0 ? 0 : (p.streakSide == mySide ? p.streak : -p.streak);
		return new View(other, meLow ? p.highName : p.lowName, wins, losses, p.draws, meLow ? p.lowRounds : p.highRounds, meLow ? p.highRounds : p.lowRounds,
			p.last, lastResult, p.lastKit, streak);
	}

	/** How this player did against that one (all zero when they never dueled). */
	public static synchronized View view(UUID me, UUID other) {
		ensureLoaded();
		return viewOf(me, other, PAIRS.get(key(me, other)));
	}

	/** Everybody this player has dueled, the most duels first (the latest first among equals). */
	public static synchronized List<View> rivals(UUID me) {
		ensureLoaded();
		List<View> out = new ArrayList<>();
		String mine = me.toString();
		for (Map.Entry<String, Pair> e : PAIRS.entrySet()) {
			String[] ids = e.getKey().split("\\|");
			if (ids.length != 2) continue;
			String other = ids[0].equals(mine) ? ids[1] : (ids[1].equals(mine) ? ids[0] : null);
			if (other == null) continue;
			try {
				out.add(viewOf(me, UUID.fromString(other), e.getValue()));
			} catch (IllegalArgumentException ignored) {
				// a broken key in the file
			}
		}
		out.sort(Comparator.comparingInt(View::total).reversed().thenComparing(Comparator.comparingLong(View::last).reversed()));
		return out;
	}

	// ------------------------------------------------------------------ the command

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("h2h")
			.executes(ctx -> list(ctx.getSource().getPlayerOrException()))
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests((ctx, builder) -> {
					List<String> names = new ArrayList<>();
					ServerPlayer me = ctx.getSource().getPlayer();
					if (me != null) for (View v : rivals(me.getUUID())) if (!v.otherName().isEmpty()) names.add(v.otherName());
					for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) names.add(p.getName().getString());
					return SharedSuggestionProvider.suggest(names, builder);
				})
				.executes(ctx -> show(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "player")))));
	}

	/** "just now", "5 minutes ago", "3 days ago". */
	public static String ago(long then, long now) {
		long s = Math.max(0, (now - then) / 1000);
		if (s < 60) return "just now";
		if (s < 3600) return s / 60 + (s / 60 == 1 ? " minute ago" : " minutes ago");
		if (s < 86400) return s / 3600 + (s / 3600 == 1 ? " hour ago" : " hours ago");
		return s / 86400 + (s / 86400 == 1 ? " day ago" : " days ago");
	}

	private static int list(ServerPlayer me) {
		List<View> rivals = rivals(me.getUUID());
		if (rivals.isEmpty()) {
			me.sendSystemMessage(Msg.info("You have not dueled anybody yet. After a duel against a player, /h2h <player> shows how you did against each other."));
			return 1;
		}
		me.sendSystemMessage(Msg.title("Your rivals (" + rivals.size() + ")"));
		for (View v : rivals.subList(0, Math.min(LISTED, rivals.size()))) {
			String name = v.otherName().isEmpty() ? "Unknown" : v.otherName();
			ChatFormatting color = v.wins() > v.losses() ? ChatFormatting.GREEN : (v.wins() < v.losses() ? ChatFormatting.RED : ChatFormatting.YELLOW);
			me.sendSystemMessage(Component.literal("  " + name + "  ").withStyle(ChatFormatting.WHITE)
				.append(Component.literal(v.wins() + " - " + v.losses() + (v.draws() > 0 ? " (" + v.draws() + (v.draws() == 1 ? " tie)" : " ties)") : "")).withStyle(color))
				.append(Component.literal("  " + ago(v.last(), System.currentTimeMillis())).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(" "))
				.append(Msg.button("[DETAILS]", ChatFormatting.AQUA, "/h2h " + name, "Everything about your duels against " + name)));
		}
		return rivals.size();
	}

	private static int show(ServerPlayer me, String typed) {
		UUID other = null;
		String name = typed;
		for (ServerPlayer p : me.level().getServer().getPlayerList().getPlayers()) {
			if (p.getName().getString().equalsIgnoreCase(typed)) {
				other = p.getUUID();
				name = p.getName().getString();
			}
		}
		if (other == null) {
			for (View v : rivals(me.getUUID())) {
				if (v.otherName().equalsIgnoreCase(typed)) {
					other = v.other();
					name = v.otherName();
				}
			}
		}
		if (other == null) {
			me.sendSystemMessage(Msg.err("I do not know a player called '" + typed + "'. /h2h lists the ones you dueled."));
			return 0;
		}
		if (other.equals(me.getUUID())) {
			me.sendSystemMessage(Msg.err("That is you. Type the name of somebody you dueled."));
			return 0;
		}
		View v = view(me.getUUID(), other);
		String mine = me.getName().getString();
		me.sendSystemMessage(Msg.title("Head to head: " + mine + " vs " + name));
		if (v.total() == 0) {
			me.sendSystemMessage(Msg.info("0 duels: you have never dueled each other. "));
			me.sendSystemMessage(Msg.button("[CHALLENGE " + name + "]", ChatFormatting.GREEN, "/duel " + name, "Open the duel menu"));
			return 1;
		}
		me.sendSystemMessage(Msg.info(v.total() + (v.total() == 1 ? " duel: " : " duels: ") + "you won " + v.wins() + ", " + name + " won " + v.losses()
			+ (v.draws() > 0 ? ", " + v.draws() + (v.draws() == 1 ? " tie" : " ties") : "")));
		me.sendSystemMessage(Msg.info("Rounds: " + v.roundsFor() + " - " + v.roundsAgainst()
			+ (v.wins() + v.losses() > 0 ? "   Your win rate against " + name + ": " + Math.round(100f * v.wins() / (v.wins() + v.losses())) + "%" : "")));
		if (v.streak() >= 2) me.sendSystemMessage(Msg.ok("You have won the last " + v.streak() + " duels against " + name + "."));
		else if (v.streak() <= -2) me.sendSystemMessage(Msg.err(name + " has won the last " + -v.streak() + " duels against you."));
		String how = v.lastResult() > 0 ? "you won" : (v.lastResult() < 0 ? name + " won" : "a tie");
		me.sendSystemMessage(Msg.info("Last duel: " + how + ", " + ago(v.last(), System.currentTimeMillis()) + (v.lastKit().isEmpty() ? "" : ", kit " + v.lastKit())));
		me.sendSystemMessage(Msg.button("[CHALLENGE " + name + "]", ChatFormatting.GREEN, "/duel " + name, "Open the duel menu"));
		return 1;
	}
}
