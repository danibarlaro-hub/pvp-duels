package com.pvpduels.arena;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Where the custom arenas live on disk: pvpduels/arenas/&lt;player id&gt;/&lt;name&gt;.nbt for everybody's own arenas and
 * pvpduels/publicarenas/&lt;name&gt;.nbt for the ones their builders shared (a copy, like the public kits).
 * An arena is named in duels by a key: "&lt;player id&gt;/&lt;name&gt;" or "public/&lt;name&gt;".
 */
public final class ArenaStore {
	public static final String PUBLIC = "public";

	/** A public arena on the shelf. */
	public record Entry(String name, UUID owner, String ownerName) {}

	private ArenaStore() {}

	private static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels");
	}

	private static Path ownFolder(UUID owner) {
		return root().resolve("arenas").resolve(owner.toString());
	}

	private static Path publicFolder() {
		return root().resolve("publicarenas");
	}

	/** Arena names are lower case so "Castle" and "castle" are the same on every operating system. */
	public static String clean(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public static boolean validName(String name) {
		return KitStore.validName(name) && name.length() <= 32;
	}

	public static String key(UUID owner, String name) {
		return owner + "/" + clean(name);
	}

	public static String publicKey(String name) {
		return PUBLIC + "/" + clean(name);
	}

	private static Path fileOf(String key) {
		int slash = key.indexOf('/');
		if (slash <= 0) throw new IllegalArgumentException("bad arena key " + key);
		String who = key.substring(0, slash);
		String name = key.substring(slash + 1);
		if (!validName(name)) throw new IllegalArgumentException("bad arena name " + name);
		if (who.equals(PUBLIC)) return publicFolder().resolve(name + ".nbt");
		return ownFolder(UUID.fromString(who)).resolve(name + ".nbt");
	}

	public static boolean exists(String key) {
		try {
			return Files.exists(fileOf(key));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public static boolean exists(UUID owner, String name) {
		return validName(clean(name)) && exists(key(owner, name));
	}

	public static void save(ArenaData arena, String key) throws IOException {
		Path file = fileOf(key);
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		NbtIo.writeCompressed(arena.toTag(), temp);
		Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public static Optional<ArenaData> load(String key) throws IOException {
		Path file;
		try {
			file = fileOf(key);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		if (!Files.exists(file)) return Optional.empty();
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		try {
			return Optional.of(ArenaData.fromTag(root));
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public static boolean delete(String key) throws IOException {
		return Files.deleteIfExists(fileOf(key));
	}

	/** The names of somebody's arenas, sorted. */
	public static List<String> list(UUID owner) {
		return names(ownFolder(owner));
	}

	private static List<String> names(Path folder) {
		if (!Files.isDirectory(folder)) return List.of();
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(folder)) {
			files.forEach(f -> {
				String file = f.getFileName().toString();
				if (file.endsWith(".nbt")) names.add(file.substring(0, file.length() - 4));
			});
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not list the arenas in {}: {}", folder, e.toString());
		}
		Collections.sort(names);
		return names;
	}

	// ------------------------------------------------------------------ the public shelf

	public static List<String> publicNames() {
		return names(publicFolder());
	}

	public static Optional<Entry> findPublic(String name) {
		String clean = clean(name);
		if (!validName(clean) || !Files.exists(publicFolder().resolve(clean + ".nbt"))) return Optional.empty();
		try {
			Optional<ArenaData> data = load(publicKey(clean));
			if (data.isEmpty()) return Optional.empty();
			return Optional.of(new Entry(clean, data.get().owner, data.get().ownerName));
		} catch (IOException e) {
			return Optional.of(new Entry(clean, null, ""));
		}
	}

	public static List<Entry> publicList() {
		List<Entry> list = new ArrayList<>();
		for (String name : publicNames()) findPublic(name).ifPresent(list::add);
		return list;
	}

	/** Copies the arena to the shelf. Returns an error text or null. */
	public static String publish(UUID owner, String name) throws IOException {
		String clean = clean(name);
		Optional<ArenaData> data = load(key(owner, clean));
		if (data.isEmpty()) return "You have no arena named '" + clean + "'. See /arena list.";
		Optional<Entry> taken = findPublic(clean);
		if (taken.isPresent() && !owner.equals(taken.get().owner())) return "Another player already shared an arena called '" + clean + "'. Rename yours.";
		save(data.get(), publicKey(clean));
		return null;
	}

	/** Takes the arena off the shelf. Returns an error text or null. */
	public static String unpublish(UUID owner, String name, boolean admin) throws IOException {
		String clean = clean(name);
		Optional<Entry> entry = findPublic(clean);
		if (entry.isEmpty()) return "There is no public arena named '" + clean + "'.";
		if (!admin && !owner.equals(entry.get().owner())) return "Only " + (entry.get().ownerName().isEmpty() ? "its owner" : entry.get().ownerName()) + " can take it off the shelf.";
		delete(publicKey(clean));
		return null;
	}

	/** For the self-test: deletes every arena of this player (and their shared copies). */
	public static void deleteForTest(UUID owner) {
		try {
			for (String name : list(owner)) {
				findPublic(name).ifPresent(e -> {
					if (owner.equals(e.owner())) {
						try {
							delete(publicKey(name));
						} catch (IOException ignored) {
							// a test leftover
						}
					}
				});
				delete(key(owner, name));
			}
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not clean the arenas of a test player: {}", e.toString());
		}
	}
}
