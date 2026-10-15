package com.pvpduels.duel;

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

/**
 * The personal records of the combo drill (/drill: 60 seconds against a bot that never hits back): the longest combo, the most hits and the
 * best accuracy (only with at least 20 swings, so one lucky swing is not a record). Saved in pvpduels/drills.json.
 */
final class Drill {
	/** A drill needs this many swings of a weapon before its accuracy can be a record. */
	static final int MIN_SWINGS = 20;

	static final class Rec {
		String name = "";
		int combo;
		int hits;
		int accuracy;
		int drills;
	}

	/** What a finished drill improved, and what the records were before. */
	record Outcome(boolean combo, boolean hits, boolean accuracy, int wasCombo, int wasHits, int wasAccuracy, int accuracyNow) {
		boolean any() {
			return combo || hits || accuracy;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Rec> ALL = new HashMap<>();
	private static boolean loaded;

	private Drill() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("drills.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Rec> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Rec>>() {}.getType());
			if (read != null) ALL.putAll(read);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the drill records: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(ALL));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the drill records: {}", e.toString());
		}
	}

	/** Accuracy in percent (landed hits of the swings), 0 when nothing was swung. */
	static int percent(int swings, int melee) {
		if (swings <= 0) return 0;
		return Math.min(100, Math.round(100f * Math.min(melee, swings) / swings));
	}

	/** Saves a finished drill and says which records it broke. */
	static synchronized Outcome submit(UUID id, String name, int hits, int combo, int swings, int melee) {
		ensureLoaded();
		Rec r = ALL.computeIfAbsent(id.toString(), k -> new Rec());
		int was = r.accuracy;
		int accuracy = swings >= MIN_SWINGS ? percent(swings, melee) : 0;
		Outcome outcome = new Outcome(combo > r.combo, hits > r.hits, accuracy > r.accuracy, r.combo, r.hits, was, accuracy);
		if (outcome.combo()) r.combo = combo;
		if (outcome.hits()) r.hits = hits;
		if (outcome.accuracy()) r.accuracy = accuracy;
		r.drills++;
		if (name != null && !name.isEmpty()) r.name = name;
		save();
		return outcome;
	}

	static synchronized Rec get(UUID id) {
		ensureLoaded();
		Rec r = ALL.get(id.toString());
		Rec copy = new Rec();
		if (r != null) {
			copy.name = r.name;
			copy.combo = r.combo;
			copy.hits = r.hits;
			copy.accuracy = r.accuracy;
			copy.drills = r.drills;
		}
		return copy;
	}

	static synchronized void resetForTest() {
		ALL.clear();
		loaded = true;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the test works with the memory
		}
	}

	static synchronized void reloadForTest() {
		ALL.clear();
		loaded = false;
		ensureLoaded();
	}
}
