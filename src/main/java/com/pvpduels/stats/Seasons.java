package com.pvpduels.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.effects.Effects;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Elo seasons. A season lasts 28 days. During it every real duel moves a separate season rating (it starts at 1000 for everybody, and
 * moves by the same points as the normal Elo). When the season ends, the top 3 with at least 5 duels each win an explosion they do not
 * have yet (1st a Domain Expansion, 2nd one of the big films, 3rd one of the newest), everybody is told, and a new season starts from
 * zero. The normal Elo and /top are not touched. Saved in pvpduels/seasons.json.
 */
public final class Seasons {
	/** Duels needed in a season to be on the podium. */
	public static final int MIN_DUELS = 5;
	public static final int DEFAULT_DAYS = 28;
	private static final long DAY = 24L * 60 * 60 * 1000;
	private static final int HISTORY = 20;

	/** The explosion prizes of 1st, 2nd and 3rd place. */
	static final String[][] PRIZES = {
		{"domain_expansion", "domain_void", "domain_swords", "domain_frost", "domain_inferno", "domain_garden", "domain_ocean"},
		{"railgun", "tesseract", "eclipse", "moonfall", "cronos", "zeus"},
		{"fire_pillars", "tornado", "crystal_burst", "rift_storm", "dragon_strike", "titan_fist", "soul_reaper", "solar_flare", "supernova", "prism_beams", "spore_bloom",
			"sculk_awakening"}};
	private static final String[] PLACE = {"1st", "2nd", "3rd"};

	/** What one player did in the season that is running. */
	public static final class Mine {
		public String name = "";
		public int elo = 1000;
		public int duels;
	}

	/** One place of a podium. */
	public static final class Spot {
		public String id = "";
		public String name = "";
		public int elo;
		public int duels;
		public String prize = "";
	}

	/** A season that is over. */
	public static final class Past {
		public int number;
		public long ended;
		public List<Spot> podium = new ArrayList<>();
	}

	static final class State {
		int number = 1;
		long start;
		int days = DEFAULT_DAYS;
		Map<String, Mine> players = new HashMap<>();
		List<Past> history = new ArrayList<>();
		/** Messages about prizes that the winner has not read yet. */
		Map<String, List<String>> pending = new HashMap<>();
	}

	/** A line of the ranking. */
	public record Row(String id, String name, int elo, int duels) {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static State state = new State();
	private static boolean loaded;
	private static LongSupplier clock = System::currentTimeMillis;
	private static MinecraftServer server;
	private static int ticks;

	private Seasons() {}

	// ------------------------------------------------------------------ the file

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("seasons.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (Files.exists(file())) {
				State read = GSON.fromJson(Files.readString(file()), State.class);
				if (read != null) state = read;
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the seasons file: {}", e.toString());
		}
		if (state.players == null) state.players = new HashMap<>();
		if (state.history == null) state.history = new ArrayList<>();
		if (state.pending == null) state.pending = new HashMap<>();
		if (state.number < 1) state.number = 1;
		if (state.days < 1) state.days = DEFAULT_DAYS;
		if (state.start == 0) {
			state.start = clock.getAsLong();
			save();
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(state));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the seasons file: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------ the season that is running

	public static synchronized int number() {
		ensureLoaded();
		return state.number;
	}

	public static synchronized int days() {
		ensureLoaded();
		return state.days;
	}

	public static synchronized long endsAt() {
		ensureLoaded();
		return state.start + state.days * DAY;
	}

	public static synchronized long millisLeft() {
		return Math.max(0, endsAt() - clock.getAsLong());
	}

	public static synchronized void setDays(int days) {
		ensureLoaded();
		state.days = Math.max(1, Math.min(90, days));
		save();
	}

	/** After a duel: the same points the normal Elo moved, added to the season rating of each one. */
	public static synchronized void record(UUID id0, String name0, int delta0, UUID id1, String name1, int delta1) {
		ensureLoaded();
		endIfDue();
		add(id0, name0, delta0);
		add(id1, name1, delta1);
		save();
	}

	private static void add(UUID id, String name, int delta) {
		Mine m = state.players.computeIfAbsent(id.toString(), k -> new Mine());
		m.elo += delta;
		m.duels++;
		if (name != null && !name.isEmpty()) m.name = name;
	}

	public static synchronized Mine mine(UUID id) {
		ensureLoaded();
		Mine m = state.players.get(id.toString());
		if (m == null) return new Mine();
		Mine copy = new Mine();
		copy.name = m.name;
		copy.elo = m.elo;
		copy.duels = m.duels;
		return copy;
	}

	/** Everybody who played in this season, the best rating first. */
	public static synchronized List<Row> ranking() {
		ensureLoaded();
		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, Mine> e : state.players.entrySet()) {
			if (e.getValue().duels > 0) rows.add(new Row(e.getKey(), e.getValue().name, e.getValue().elo, e.getValue().duels));
		}
		rows.sort(Comparator.comparingInt(Row::elo).reversed().thenComparing(Comparator.comparingInt(Row::duels).reversed()).thenComparing(Row::name));
		return rows;
	}

	/** The ones who would win a place now: at least MIN_DUELS duels, the three best. */
	public static synchronized List<Row> podium() {
		List<Row> out = new ArrayList<>();
		for (Row r : ranking()) {
			if (r.duels() < MIN_DUELS) continue;
			out.add(r);
			if (out.size() == 3) break;
		}
		return out;
	}

	public static synchronized List<Past> history() {
		ensureLoaded();
		return new ArrayList<>(state.history);
	}

	// ------------------------------------------------------------------ the end of a season

	/** The explosion a winner of this place gets: the first one of the group (starting at a different one each season) that they do not own. */
	static String prizeFor(UUID id, int place, int seasonNumber) {
		String[] pool = PRIZES[place];
		for (int i = 0; i < pool.length; i++) {
			String ex = pool[(seasonNumber + i) % pool.length];
			if (!Effects.ownsExplosion(id, ex)) return ex;
		}
		return "";
	}

	/** The name of an explosion as the menus show it. */
	public static String prizeName(String ex) {
		int i = Effects.explosionIndex(ex);
		return i < 0 ? Effects.pretty(ex) : Effects.EXPLOSION_NAMES[i];
	}

	/** Ends the season now if its time is over. Returns whether it did. */
	private static boolean endIfDue() {
		if (clock.getAsLong() < state.start + state.days * DAY) return false;
		endSeason();
		return true;
	}

	/** Ends the season that is running (also for /season end): prizes, a message for everybody, and a new season. */
	public static synchronized List<Spot> endSeason() {
		ensureLoaded();
		Past past = new Past();
		past.number = state.number;
		past.ended = clock.getAsLong();
		int place = 0;
		for (Row r : podium()) {
			Spot spot = new Spot();
			spot.id = r.id();
			spot.name = r.name();
			spot.elo = r.elo();
			spot.duels = r.duels();
			UUID id = UUID.fromString(r.id());
			spot.prize = prizeFor(id, place, state.number);
			if (!spot.prize.isEmpty()) Effects.grantExplosion(id, spot.prize);
			String text = "Season " + state.number + " is over and you finished " + PLACE[place] + " with " + r.elo() + " season Elo! "
				+ (spot.prize.isEmpty() ? "You already have every explosion of your prize group, so this one is for the glory."
					: "Your prize is the explosion " + prizeName(spot.prize) + ": equip it in /effects.");
			state.pending.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(text);
			past.podium.add(spot);
			place++;
		}
		state.history.add(past);
		while (state.history.size() > HISTORY) state.history.remove(0);
		int ended = state.number;
		state.number++;
		state.start = clock.getAsLong();
		state.players.clear();
		save();
		if (server != null) {
			announce(ended, past);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) deliver(p);
		}
		return past.podium;
	}

	private static void announce(int ended, Past past) {
		List<Component> lines = new ArrayList<>();
		lines.add(Msg.title("Season " + ended + " is over!"));
		if (past.podium.isEmpty()) {
			lines.add(Msg.info("  Nobody played at least " + MIN_DUELS + " duels in it, so there is no podium."));
		} else {
			for (int i = 0; i < past.podium.size(); i++) {
				Spot s = past.podium.get(i);
				lines.add(Msg.info("  " + PLACE[i] + ": " + s.name + "  -  " + s.elo + " Elo, " + s.duels + " duels"
					+ (s.prize.isEmpty() ? "" : "  -  prize: " + prizeName(s.prize))));
			}
		}
		lines.add(Msg.ok("  Season " + (ended + 1) + " starts now, from 1000 for everybody. /season shows how it goes."));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			for (Component line : lines) p.sendSystemMessage(line);
		}
	}

	/** Tells a winner about the prize they have not read yet. */
	public static synchronized void deliver(ServerPlayer p) {
		ensureLoaded();
		List<String> texts = state.pending.remove(p.getUUID().toString());
		if (texts == null || texts.isEmpty()) return;
		save();
		for (String text : texts) {
			p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
				.append(Msg.button("[EFFECTS]", ChatFormatting.GREEN, "/effects", "Choose how you celebrate your wins")));
		}
	}

	// ------------------------------------------------------------------ for the self-test

	public static synchronized void resetForTest() {
		state = new State();
		loaded = true;
		state.start = clock.getAsLong();
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the test works with the memory
		}
	}

	public static synchronized void reloadForTest() {
		loaded = false;
		state = new State();
		ensureLoaded();
	}

	/** The server that is told about the end of a season; null means nobody is online to be told (the prizes wait). */
	public static synchronized void setServerForTest(MinecraftServer s) {
		server = s;
	}

	public static synchronized void setClockForTest(LongSupplier c) {
		clock = c == null ? System::currentTimeMillis : c;
	}

	public static synchronized int pendingFor(UUID id) {
		ensureLoaded();
		List<String> texts = state.pending.get(id.toString());
		return texts == null ? 0 : texts.size();
	}

	// ------------------------------------------------------------------ text

	/** "3 days 4 hours", "5 hours 10 minutes", "under a minute". */
	public static String timeLeft(long millis) {
		long minutes = millis / 60_000;
		long days = minutes / (24 * 60);
		long hours = (minutes / 60) % 24;
		long mins = minutes % 60;
		if (days > 0) return days + (days == 1 ? " day " : " days ") + hours + (hours == 1 ? " hour" : " hours");
		if (hours > 0) return hours + (hours == 1 ? " hour " : " hours ") + mins + (mins == 1 ? " minute" : " minutes");
		if (mins > 0) return mins + (mins == 1 ? " minute" : " minutes");
		return "under a minute";
	}

	// ------------------------------------------------------------------ commands and events

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
		dispatcher.register(Commands.literal("season")
			.executes(ctx -> show(ctx.getSource()))
			.then(Commands.literal("top").executes(ctx -> top(ctx.getSource())))
			.then(Commands.literal("history").executes(ctx -> history(ctx.getSource())))
			.then(Commands.literal("end").requires(admin).executes(ctx -> endNow(ctx.getSource())))
			.then(Commands.literal("days").requires(admin).then(Commands.argument("days", IntegerArgumentType.integer(1, 90))
				.executes(ctx -> days(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "days"))))));
	}

	/** The prizes are told on joining, the clock is watched about once a minute. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			server = s;
			synchronized (Seasons.class) {
				ensureLoaded();
				endIfDue();
			}
			deliver(handler.getPlayer());
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			ticks++;
			if (ticks % 1200 != 0) return;
			server = s;
			synchronized (Seasons.class) {
				ensureLoaded();
				endIfDue();
			}
		});
	}

	private static int show(CommandSourceStack source) {
		synchronized (Seasons.class) {
			ensureLoaded();
			endIfDue();
		}
		int number = number();
		source.sendSuccess(() -> Msg.title("Season " + number), false);
		source.sendSuccess(() -> Msg.info("  Ends in " + timeLeft(millisLeft()) + ". Every duel against a player moves your season rating, which starts at 1000."), false);
		ServerPlayer me = source.getPlayer();
		if (me != null) {
			Mine mine = mine(me.getUUID());
			if (mine.duels == 0) {
				source.sendSuccess(() -> Msg.info("  You have not played this season. Win duels to climb."), false);
			} else {
				List<Row> all = ranking();
				int rank = 0;
				for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(me.getUUID().toString())) rank = i + 1;
				String line = "  You: " + mine.elo + " season Elo, " + mine.duels + (mine.duels == 1 ? " duel" : " duels") + ", #" + rank + " of " + all.size();
				source.sendSuccess(() -> Msg.ok(line), false);
				if (mine.duels < MIN_DUELS) {
					int need = MIN_DUELS - mine.duels;
					source.sendSuccess(() -> Msg.info("  Play " + need + " more " + (need == 1 ? "duel" : "duels") + " to be able to win a place on the podium."), false);
				}
			}
		}
		List<Row> podium = podium();
		if (podium.isEmpty()) {
			source.sendSuccess(() -> Msg.info("  The podium is empty: it needs players with at least " + MIN_DUELS + " duels."), false);
		} else {
			for (int i = 0; i < podium.size(); i++) {
				Row r = podium.get(i);
				String line = "  " + PLACE[i] + ": " + r.name() + "  -  " + r.elo() + " Elo, " + r.duels() + " duels";
				source.sendSuccess(() -> Msg.info(line), false);
			}
		}
		source.sendSuccess(() -> Msg.info("  Prizes: 1st a Domain Expansion, 2nd one of the big explosion films, 3rd one of the newest explosions (one you do not have yet)."), false);
		source.sendSuccess(() -> Msg.button("  [TOP]", ChatFormatting.GREEN, "/season top", "The ten best of this season")
			.copy().append(Component.literal("  ")).append(Msg.button("[HISTORY]", ChatFormatting.AQUA, "/season history", "The podiums of the seasons that are over")), false);
		return 1;
	}

	private static int top(CommandSourceStack source) {
		List<Row> all = ranking();
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("Nobody has played a duel in this season yet."), false);
			return 1;
		}
		int shown = Math.min(10, all.size());
		source.sendSuccess(() -> Msg.title("Season " + number() + ": top " + shown), false);
		for (int i = 0; i < shown; i++) {
			Row r = all.get(i);
			String line = String.format(Locale.ROOT, "%2d. %s  -  %d Elo  (%d duels%s)", i + 1, r.name(), r.elo(), r.duels(), r.duels() < MIN_DUELS ? ", not on the podium yet" : "");
			source.sendSuccess(() -> Msg.info(line), false);
		}
		return 1;
	}

	private static int history(CommandSourceStack source) {
		List<Past> past = history();
		if (past.isEmpty()) {
			source.sendSuccess(() -> Msg.info("No season is over yet."), false);
			return 1;
		}
		source.sendSuccess(() -> Msg.title("Seasons that are over"), false);
		for (int i = past.size() - 1; i >= Math.max(0, past.size() - 5); i--) {
			Past p = past.get(i);
			StringBuilder sb = new StringBuilder("  Season " + p.number + ": ");
			if (p.podium.isEmpty()) sb.append("no podium");
			for (int k = 0; k < p.podium.size(); k++) {
				if (k > 0) sb.append(", ");
				sb.append(PLACE[k]).append(' ').append(p.podium.get(k).name).append(" (").append(p.podium.get(k).elo).append(')');
			}
			source.sendSuccess(() -> Msg.info(sb.toString()), false);
		}
		return 1;
	}

	private static int endNow(CommandSourceStack source) {
		if (server == null) server = source.getServer();
		List<Spot> podium = endSeason();
		source.sendSuccess(() -> Msg.ok("Season over. " + (podium.isEmpty() ? "Nobody had enough duels for the podium." : podium.size() + " on the podium.")
			+ " Season " + number() + " has started."), true);
		return 1;
	}

	private static int days(CommandSourceStack source, int days) {
		setDays(days);
		source.sendSuccess(() -> Msg.ok("Every season lasts " + days() + (days() == 1 ? " day" : " days") + " from now on (the one running ends " + timeLeft(millisLeft()) + " from now)."), true);
		return 1;
	}
}
