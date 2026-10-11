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
 * Inventory layouts, one JSON file per player (.minecraft/pvpduels/layouts/UUID.json).
 * A layout (profile) maps slots to an ordered priority list of rules (categories like "sword" or exact item ids).
 * Slots 1-9 are the hotbar, 10-36 the backpack (left to right, top to bottom), 37 is the offhand.
 */
public final class LayoutStore {
	public static final int MAX_PROFILES = 21; // not counting the built-in "default"
	public static final int MAX_LEVELS = 4;
	public static final int MAX_SLOT = 36;
	public static final int OFFHAND = 37;

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

	/** How many layouts the player created (the built-in "default" does not count toward the limit). */
	public static int customCount(Data d) {
		int n = 0;
		for (String k : d.profiles.keySet()) {
			if (!k.equals("default")) n++;
		}
		return n;
	}

	public static Integer asNumber(String s) {
		try {
			return Integer.valueOf(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int compareNames(String a, String b) {
		if (a.equals("default")) return b.equals("default") ? 0 : -1;
		if (b.equals("default")) return 1;
		Integer na = asNumber(a);
		Integer nb = asNumber(b);
		if (na != null && nb != null) return Integer.compare(na, nb);
		if (na != null) return -1;
		if (nb != null) return 1;
		return a.compareTo(b);
	}

	/** Layout names in a friendly order: default first, then 1, 2, 3..., then named ones. */
	public static List<String> orderedNames(UUID owner) {
		List<String> names = new ArrayList<>(get(owner).profiles.keySet());
		names.sort(LayoutStore::compareNames);
		return names;
	}

	public static List<String> profileNames(UUID owner) {
		return orderedNames(owner);
	}
}
