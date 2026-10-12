package com.pvpduels.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** The shared shelf of public kits (/publicate): one file per kit in .minecraft/pvpduels/public. */
public final class PublicKits {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public record Entry(String name, UUID owner, String ownerName) {}

	private PublicKits() {}

	private static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("public");
	}

	private static Path file(String name) {
		return dir().resolve(name + ".json");
	}

	public static List<String> names() {
		Path d = dir();
		if (!Files.isDirectory(d)) return Collections.emptyList();
		try (Stream<Path> files = Files.list(d)) {
			List<String> names = new ArrayList<>();
			files.forEach(p -> {
				String n = p.getFileName().toString();
				if (n.endsWith(".json")) names.add(n.substring(0, n.length() - 5));
			});
			Collections.sort(names);
			return names;
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	public static Optional<Entry> find(String name) {
		try {
			if (!Files.exists(file(name))) return Optional.empty();
			JsonObject root = JsonParser.parseString(Files.readString(file(name))).getAsJsonObject();
			return Optional.of(new Entry(name, UUID.fromString(root.get("owner").getAsString()), root.get("ownerName").getAsString()));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	public static List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		for (String n : names()) find(n).ifPresent(out::add);
		return out;
	}

	/** The kit as stored on the shelf (owner info included). */
	public static JsonObject raw(String name) throws IOException {
		return JsonParser.parseString(Files.readString(file(name))).getAsJsonObject();
	}

	/** Puts a copy of the kit on the shelf. The layout profile is personal, so it is not shared. */
	public static void publish(UUID owner, String ownerName, String name, JsonObject kit) throws IOException {
		JsonObject root = kit.deepCopy();
		root.remove("profile");
		root.remove("locked");
		root.remove("from");
		root.addProperty("name", name);
		root.addProperty("owner", owner.toString());
		root.addProperty("ownerName", ownerName);
		Files.createDirectories(dir());
		Files.writeString(file(name), GSON.toJson(root));
	}

	public static boolean unpublish(String name) throws IOException {
		return Files.deleteIfExists(file(name));
	}
}
