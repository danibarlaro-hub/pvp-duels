package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Where the replays are kept (the folder pvpduels/replays of the host) and who may watch them. Every real 1v1 duel is recorded; a player
 * has no access to a replay until an admin sends it to them. The requests wait in a file until an admin answers, so they are not lost
 * when somebody logs off or the game is closed.
 */
final class ReplayStore {
	/** What the list shows about one replay. */
	static final class Entry {
		String id = "";
		long created;
		String[] names = {"", ""};
		String[] uuids = {"", ""};
		String kit = "";
		String arena = "";
		String mode = "";
		int winner = -2;
		int[] points = {0, 0};
		int seconds;
		long bytes;
		/** The players an admin sent this replay to (ids). */
		List<String> granted = new ArrayList<>();
	}

	/** A player asked an admin for a replay. */
	static final class Request {
		String replay = "";
		String player = "";
		String name = "";
		long at;
		/** "pending", "sent" or "denied". */
		String status = "pending";
		/** The player was told the answer. */
		boolean told;
	}

	/** Old replays are deleted when there are more than this many. */
	static final int MAX_REPLAYS = 60;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static List<Entry> entries = new ArrayList<>();
	private static List<Request> requests = new ArrayList<>();
	private static boolean loaded;
	/** For the self-test: a different folder. */
	private static Path forcedDir;

	private ReplayStore() {}

	static Path dir() {
		return forcedDir != null ? forcedDir : FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("replays");
	}

	private static Path indexFile() {
		return dir().resolve("index.json");
	}

	private static Path requestsFile() {
		return dir().resolve("requests.json");
	}

	static Path fileOf(String id) {
		return dir().resolve(id + ".pvr");
	}

	static String newId() {
		java.util.Random random = new java.util.Random();
		while (true) {
			String id = Long.toString(random.nextLong() & 0xFFFFFFL, 36);
			while (id.length() < 4) id = "0" + id;
			if (!exists(id)) return id;
		}
	}

	// ------------------------------------------------------------------ the index

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (Files.exists(indexFile())) {
				List<Entry> read = GSON.fromJson(Files.readString(indexFile()), new TypeToken<List<Entry>>() {}.getType());
				if (read != null) entries = new ArrayList<>(read);
			}
			if (Files.exists(requestsFile())) {
				List<Request> read = GSON.fromJson(Files.readString(requestsFile()), new TypeToken<List<Request>>() {}.getType());
				if (read != null) requests = new ArrayList<>(read);
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the replay index: {}", e.toString());
		}
		for (Entry e : entries) if (e.granted == null) e.granted = new ArrayList<>();
	}

	private static synchronized void saveIndex() {
		try {
			Files.createDirectories(dir());
			Files.writeString(indexFile(), GSON.toJson(entries));
			Files.writeString(requestsFile(), GSON.toJson(requests));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the replay index: {}", e.toString());
		}
	}

	/** For the self-test: forget what is in memory and read the files again (or use another folder). */
	static synchronized void reloadForTest(Path folder) {
		forcedDir = folder;
		entries = new ArrayList<>();
		requests = new ArrayList<>();
		loaded = false;
		ensureLoaded();
	}

	static synchronized boolean exists(String id) {
		ensureLoaded();
		for (Entry e : entries) if (e.id.equals(id)) return true;
		return false;
	}

	static synchronized Entry get(String id) {
		ensureLoaded();
		for (Entry e : entries) if (e.id.equalsIgnoreCase(id)) return e;
		return null;
	}

	static synchronized int count() {
		ensureLoaded();
		return entries.size();
	}

	/** Keeps a finished duel. Returns false when it could not be written. */
	static synchronized boolean add(ReplayData data) {
		ensureLoaded();
		if (data.frames < 20) return false; // less than a second: nothing to watch
		try {
			Files.createDirectories(dir());
			Path file = fileOf(data.id);
			try (OutputStream out = Files.newOutputStream(file)) {
				data.write(out);
			}
			Entry e = new Entry();
			e.id = data.id;
			e.created = data.created;
			e.names = new String[] {data.names[0], data.names[1]};
			e.uuids = new String[] {data.uuids[0].toString(), data.uuids[1].toString()};
			e.kit = data.config.kit();
			e.arena = data.config.arenaName();
			e.mode = data.config.modeName();
			e.winner = data.winner;
			e.points = new int[] {data.points[0], data.points[1]};
			e.seconds = data.seconds();
			e.bytes = Files.size(file);
			entries.add(e);
			trim();
			saveIndex();
			PvpDuelsMod.LOGGER.info("Replay {} saved: {} vs {}, {} seconds, {} KB", e.id, e.names[0], e.names[1], e.seconds, e.bytes / 1024);
			return true;
		} catch (IOException | RuntimeException ex) {
			PvpDuelsMod.LOGGER.warn("Could not save the replay of {} vs {}: {}", data.names[0], data.names[1], ex.toString());
			return false;
		}
	}

	private static void trim() {
		while (entries.size() > MAX_REPLAYS) {
			Entry oldest = entries.stream().min(Comparator.comparingLong(e -> e.created)).orElse(null);
			if (oldest == null) return;
			remove(oldest);
		}
	}

	private static void remove(Entry e) {
		entries.remove(e);
		requests.removeIf(r -> r.replay.equals(e.id));
		try {
			Files.deleteIfExists(fileOf(e.id));
		} catch (IOException ex) {
			PvpDuelsMod.LOGGER.warn("Could not delete the replay {}: {}", e.id, ex.toString());
		}
	}

	static synchronized boolean delete(String id) {
		Entry e = get(id);
		if (e == null) return false;
		remove(e);
		saveIndex();
		return true;
	}

	static ReplayData load(String id) throws IOException {
		try (InputStream in = Files.newInputStream(fileOf(id))) {
			return ReplayData.read(in);
		}
	}

	// ------------------------------------------------------------------ who may watch

	static boolean tookPart(Entry e, UUID player) {
		String id = player.toString();
		return e.uuids[0].equals(id) || e.uuids[1].equals(id);
	}

	/** The newest replays, newest first. {@code player} null = everybody's. */
	static synchronized List<Entry> recent(UUID player, int limit) {
		ensureLoaded();
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) if (player == null || tookPart(e, player)) out.add(e);
		out.sort(Comparator.comparingLong((Entry e) -> e.created).reversed());
		return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	static synchronized boolean mayWatch(Entry e, UUID player, boolean admin) {
		return admin || e.granted.contains(player.toString());
	}

	static synchronized void grant(Entry e, UUID player) {
		if (!e.granted.contains(player.toString())) e.granted.add(player.toString());
		saveIndex();
	}

	// ------------------------------------------------------------------ requests

	static synchronized Request pendingOf(String replay, UUID player) {
		ensureLoaded();
		for (Request r : requests) if (r.replay.equals(replay) && r.player.equals(player.toString()) && r.status.equals("pending")) return r;
		return null;
	}

	/** Returns the new request, or null when the player already has one waiting. */
	static synchronized Request ask(String replay, UUID player, String name) {
		ensureLoaded();
		if (pendingOf(replay, player) != null) return null;
		Request r = new Request();
		r.replay = replay;
		r.player = player.toString();
		r.name = name;
		r.at = System.currentTimeMillis();
		requests.add(r);
		saveIndex();
		return r;
	}

	static synchronized List<Request> pending() {
		ensureLoaded();
		List<Request> out = new ArrayList<>();
		for (Request r : requests) if (r.status.equals("pending")) out.add(r);
		return out;
	}

	static synchronized List<Request> pendingFor(String replay) {
		List<Request> out = new ArrayList<>();
		for (Request r : pending()) if (r.replay.equals(replay)) out.add(r);
		return out;
	}

	/** The requests of this player that have an answer they were not told yet. */
	static synchronized List<Request> untold(UUID player) {
		ensureLoaded();
		List<Request> out = new ArrayList<>();
		for (Request r : requests) if (r.player.equals(player.toString()) && !r.status.equals("pending") && !r.told) out.add(r);
		return out;
	}

	static synchronized void answer(Request r, String status) {
		r.status = status;
		r.told = false;
		saveIndex();
	}

	static synchronized void told(Request r) {
		r.told = true;
		saveIndex();
	}
}
