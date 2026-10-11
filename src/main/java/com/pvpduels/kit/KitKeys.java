package com.pvpduels.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Which kit each of the 3 "Quick kit" keys (A, B, C) loads. One small JSON file per player. */
public final class KitKeys {
	public static final int COUNT = 3;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, List<String>> CACHE = new HashMap<>();

	private KitKeys() {}

	private static Path file(UUID owner) {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("kitkeys").resolve(owner + ".json");
	}

	/** Kit names for keys A, B, C; an empty string means the key has no kit yet. */
	public static List<String> get(UUID owner) {
		List<String> cached = CACHE.get(owner);
		if (cached != null) return cached;

		List<String> keys = null;
		try {
			Path f = file(owner);
			if (Files.exists(f)) {
				keys = GSON.fromJson(Files.readString(f), new TypeToken<List<String>>() {}.getType());
			}
		} catch (Exception e) {
			keys = null;
		}
		if (keys == null) keys = new ArrayList<>();
		keys = new ArrayList<>(keys);
		while (keys.size() < COUNT) keys.add("");
		CACHE.put(owner, keys);
		return keys;
	}

	public static void set(UUID owner, int index, String kit) throws IOException {
		List<String> keys = get(owner);
		keys.set(index, kit);
		Files.createDirectories(file(owner).getParent());
		Files.writeString(file(owner), GSON.toJson(keys));
	}

	public static char letter(int index) {
		return (char) ('A' + index);
	}

	/** 'a'/'b'/'c' (any case) to 0/1/2, or -1. */
	public static int indexOf(String letter) {
		if (letter.length() != 1) return -1;
		int i = Character.toLowerCase(letter.charAt(0)) - 'a';
		return i >= 0 && i < COUNT ? i : -1;
	}
}
