package com.pvpduels.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Hotbar layout profiles, one JSON file per player (.minecraft/pvpduels/layouts/UUID.json).
 * A profile maps hotbar slots 1-9 to an ordered priority list of rules (categories like "sword" or exact item ids).
 */
public final class LayoutStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, Data> CACHE = new HashMap<>();

	private LayoutStore() {}

	public static class Profile {
		public Map<Integer, List<String>> slots = new TreeMap<>();
	}

	public static class Data {
		public String active = "default";
		public Map<String, Profile> profiles = new TreeMap<>();
	}

	private static Path file(UUID owner) {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("layouts").resolve(owner + ".json");
	}

	public static Data get(UUID owner) {
		Data cached = CACHE.get(owner);
		if (cached != null) return cached;

		Data data = null;
		try {
			Path f = file(owner);
			if (Files.exists(f)) {
				data = GSON.fromJson(Files.readString(f), Data.class);
			}
		} catch (Exception e) {
			data = null;
		}
		if (data == null) data = new Data();
		if (data.profiles == null) data.profiles = new TreeMap<>();
		if (data.active == null) data.active = "default";
		data.profiles.computeIfAbsent("default", k -> new Profile());
		data.profiles.computeIfAbsent(data.active, k -> new Profile());
		CACHE.put(owner, data);
		return data;
	}

	public static Profile active(UUID owner) {
		Data d = get(owner);
		return d.profiles.get(d.active);
	}

	public static void save(UUID owner) throws IOException {
		Data d = get(owner);
		Files.createDirectories(file(owner).getParent());
		Files.writeString(file(owner), GSON.toJson(d));
	}

	public static List<String> profileNames(UUID owner) {
		return new ArrayList<>(get(owner).profiles.keySet());
	}
}
