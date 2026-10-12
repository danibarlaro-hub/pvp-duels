package com.pvpduels.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small per-player settings saved in one file: who turned the sidebar off, who already got the guide book. */
public final class Prefs {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Set<UUID> SIDEBAR_OFF = new HashSet<>();
	private static final Set<UUID> BOOK_GIVEN = new HashSet<>();
	private static boolean loaded;

	private Prefs() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("prefs.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			read(root, "sidebarOff", SIDEBAR_OFF);
			read(root, "bookGiven", BOOK_GIVEN);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the settings file: {}", e.toString());
		}
	}

	private static void read(JsonObject root, String key, Set<UUID> into) {
		if (!root.has(key)) return;
		for (var e : root.getAsJsonArray(key)) {
			try {
				into.add(UUID.fromString(e.getAsString()));
			} catch (IllegalArgumentException ignored) {
				// skip broken entries
			}
		}
	}

	private static void write(JsonObject root, String key, Set<UUID> from) {
		JsonArray arr = new JsonArray();
		for (UUID id : from) arr.add(id.toString());
		root.add(key, arr);
	}

	private static synchronized void save() {
		try {
			JsonObject root = new JsonObject();
			write(root, "sidebarOff", SIDEBAR_OFF);
			write(root, "bookGiven", BOOK_GIVEN);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(root));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the settings file: {}", e.toString());
		}
	}

	public static synchronized boolean sidebarOn(UUID id) {
		ensureLoaded();
		return !SIDEBAR_OFF.contains(id);
	}

	public static synchronized void setSidebar(UUID id, boolean on) {
		ensureLoaded();
		if (on ? SIDEBAR_OFF.remove(id) : SIDEBAR_OFF.add(id)) save();
	}

	public static synchronized boolean bookGiven(UUID id) {
		ensureLoaded();
		return BOOK_GIVEN.contains(id);
	}

	public static synchronized void markBookGiven(UUID id) {
		ensureLoaded();
		if (BOOK_GIVEN.add(id)) save();
	}
}
