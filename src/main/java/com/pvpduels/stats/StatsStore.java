package com.pvpduels.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

	/** A copy of the numbers of this player (all zero when they never dueled). */
	public static synchronized Stats get(UUID id) {
		ensureLoaded();
		Stats s = ALL.get(id.toString());
		if (s == null) return new Stats();
		Stats copy = new Stats();
		copy.duels = s.duels; copy.wins = s.wins; copy.losses = s.losses; copy.draws = s.draws;
		copy.roundsWon = s.roundsWon; copy.roundsLost = s.roundsLost; copy.streak = s.streak; copy.bestStreak = s.bestStreak;
		return copy;
	}

	/** result: 1 = won, 0 = draw, -1 = lost. */
	public static synchronized void record(UUID id, int result, int roundsFor, int roundsAgainst) {
		ensureLoaded();
		Stats s = ALL.computeIfAbsent(id.toString(), k -> new Stats());
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
		save();
	}
}
