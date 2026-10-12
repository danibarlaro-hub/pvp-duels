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
	private static final Set<UUID> LOBBY_OFF = new HashSet<>();
	private static final Set<UUID> ARMOR_OFF = new HashSet<>();
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
			read(root, "lobbyOff", LOBBY_OFF);
			read(root, "armorOff", ARMOR_OFF);
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
			write(root, "lobbyOff", LOBBY_OFF);
			write(root, "armorOff", ARMOR_OFF);
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

	/** Whether this player is sent to the lobby (when there is one) after a duel. On by default. */
	public static synchronized boolean lobbyReturn(UUID id) {
		ensureLoaded();
		return !LOBBY_OFF.contains(id);
	}

	public static synchronized void setLobbyReturn(UUID id, boolean on) {
		ensureLoaded();
		if (on ? LOBBY_OFF.remove(id) : LOBBY_OFF.add(id)) save();
	}

	/** Whether the Sort key also puts on your best armor. On by default. */
	public static synchronized boolean armorSort(UUID id) {
		ensureLoaded();
		return !ARMOR_OFF.contains(id);
	}

	public static synchronized void setArmorSort(UUID id, boolean on) {
		ensureLoaded();
		if (on ? ARMOR_OFF.remove(id) : ARMOR_OFF.add(id)) save();
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
