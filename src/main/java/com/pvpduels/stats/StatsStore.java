package com.pvpduels.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Duel statistics of every player, saved in .minecraft/pvpduels/stats.json. */
public final class StatsStore {
	public static final class Stats {
		public int duels;
		public int wins;
		public int losses;
		public int draws;
		public int roundsWon;
		public int roundsLost;
		public int streak;
		public int bestStreak;
		/** Rating: everybody starts at 1000. */
		public int elo = 1000;
		/** Last name this player had in a duel (for /top when they are offline). */
		public String name = "";

		public String winRate() {
			int decided = wins + losses;
			return decided == 0 ? "-" : Math.round(100f * wins / decided) + "%";
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Stats> ALL = new HashMap<>();
	private static boolean loaded;

	private StatsStore() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("stats.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Stats> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Stats>>() {}.getType());
			if (read != null) ALL.putAll(read);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the statistics file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(ALL));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the statistics file: {}", e.toString());
		}
	}

	private static Stats copy(Stats s) {
		Stats copy = new Stats();
		copy.duels = s.duels; copy.wins = s.wins; copy.losses = s.losses; copy.draws = s.draws;
		copy.roundsWon = s.roundsWon; copy.roundsLost = s.roundsLost; copy.streak = s.streak; copy.bestStreak = s.bestStreak;
		copy.elo = s.elo; copy.name = s.name == null ? "" : s.name;
		return copy;
	}

	/** A copy of the numbers of this player (all zero when they never dueled). */
	public static synchronized Stats get(UUID id) {
		ensureLoaded();
		Stats s = ALL.get(id.toString());
		return s == null ? new Stats() : copy(s);
	}

	/** result: 1 = won, 0 = draw, -1 = lost. */
	private static void apply(Stats s, int result, int roundsFor, int roundsAgainst) {
		s.duels++;
		s.roundsWon += roundsFor;
		s.roundsLost += roundsAgainst;
		if (result > 0) {
			s.wins++;
			s.streak++;
			s.bestStreak = Math.max(s.bestStreak, s.streak);
		} else if (result < 0) {
			s.losses++;
			s.streak = 0;
		} else {
			s.draws++;
		}
	}

	private static final double ELO_K = 32.0;

	/**
	 * Saves the result of a finished duel for both players and moves their Elo ratings (what one gains, the other loses).
	 * winner: -1 draw, 0 or 1. Returns {Elo of the first before, after, Elo of the second before, after}.
	 */
	public static synchronized int[] recordDuel(UUID id0, String name0, UUID id1, String name1, int winner, int points0, int points1) {
		ensureLoaded();
		Stats s0 = ALL.computeIfAbsent(id0.toString(), k -> new Stats());
		Stats s1 = ALL.computeIfAbsent(id1.toString(), k -> new Stats());
		int before0 = s0.elo;
		int before1 = s1.elo;
		double expected0 = 1.0 / (1.0 + Math.pow(10.0, (before1 - before0) / 400.0));
		double score0 = winner == -1 ? 0.5 : (winner == 0 ? 1.0 : 0.0);
		int delta = (int) Math.round(ELO_K * (score0 - expected0));
		s0.elo = before0 + delta;
		s1.elo = before1 - delta;
		s0.name = name0;
		s1.name = name1;
		apply(s0, winner == -1 ? 0 : (winner == 0 ? 1 : -1), points0, points1);
		apply(s1, winner == -1 ? 0 : (winner == 1 ? 1 : -1), points1, points0);
		save();
		return new int[] {before0, s0.elo, before1, s1.elo};
	}

	/** One line of the ranking. */
	public record Entry(UUID id, String name, Stats stats) {}

	/** Every player who has played a duel, best first. by = "elo", "wins" or "streak". */
	public static synchronized List<Entry> ranking(String by) {
		ensureLoaded();
		List<Entry> all = new ArrayList<>();
		for (Map.Entry<String, Stats> e : ALL.entrySet()) {
			if (e.getValue().duels <= 0) continue;
			try {
				Stats s = copy(e.getValue());
				all.add(new Entry(UUID.fromString(e.getKey()), s.name, s));
			} catch (IllegalArgumentException ignored) {
				// a broken key in the file
			}
		}
		Comparator<Entry> order = switch (by) {
			case "wins" -> Comparator.comparingInt((Entry e) -> e.stats().wins).reversed();
			case "streak" -> Comparator.comparingInt((Entry e) -> e.stats().bestStreak).reversed();
			default -> Comparator.comparingInt((Entry e) -> e.stats().elo).reversed();
		};
		all.sort(order.thenComparing(Comparator.comparingInt((Entry e) -> e.stats().wins).reversed()).thenComparing(Entry::name));
		return all;
	}
}
