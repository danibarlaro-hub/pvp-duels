package com.pvpduels.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Boards in the world that show the best players: a floating text (a vanilla text display) with the top 10 by Elo, by wins or by
 * win streak. An admin puts one where they stand with /leaderboard place, in the lobby for instance. Every board is refreshed twice
 * a minute and a few seconds after a duel ends. The places of the boards are saved inside the world (pvpduels/boards.json), the
 * text display itself is an entity of the world with the tag pvpduels_board_&lt;kind&gt;.
 */
public final class Leaderboards {
	public static final String TAG_PREFIX = "pvpduels_board_";
	public static final String[] KINDS = {"elo", "wins", "streak"};
	static final int ROWS = 10;
	private static final int REFRESH_TICKS = 600;
	private static final int SOON_TICKS = 40;
	/** The board stands this many blocks above the feet of the admin who places it. */
	private static final double HEIGHT = 2.2;

	/** One board: where it is and what it shows. */
	public static final class Board {
		public String dimension = "";
		public double x;
		public double y;
		public double z;
		public String kind = "elo";
	}

	/** Where the rankings come from: the statistics (the self-test gives a fixed list instead). */
	public static Function<String, List<StatsStore.Entry>> source = StatsStore::ranking;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Board> BOARDS = new ArrayList<>();
	/** The last text sent to each board (by entity id), so a board is only touched when its text changes. */
	private static final Map<String, String> LAST = new HashMap<>();
	private static Path worldFile;
	private static boolean loaded;
	private static int ticks;
	private static int dueAt = -1;
	/** How many boards the last refresh could not reach (their chunk is not loaded), and until when it keeps trying after a duel. */
	private static int skipped;
	private static int retryUntil = -1;
	private static final int RETRY_TICKS = 100;
	private static final int RETRY_FOR = 6000;

	private Leaderboards() {}

	// ------------------------------------------------------------------ the file

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		BOARDS.clear();
		if (worldFile == null || !Files.exists(worldFile)) return;
		try {
			List<Board> read = GSON.fromJson(Files.readString(worldFile), new TypeToken<List<Board>>() {}.getType());
			if (read != null) BOARDS.addAll(read);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the leaderboards file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		if (worldFile == null) return;
		try {
			Files.createDirectories(worldFile.getParent());
			Files.writeString(worldFile, GSON.toJson(BOARDS));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the leaderboards file: {}", e.toString());
		}
	}

	private static synchronized void bind(Path file) {
		worldFile = file;
		loaded = false;
		LAST.clear();
		BOARDS.clear();
	}

	// ------------------------------------------------------------------ the text

	public static boolean validKind(String kind) {
		for (String k : KINDS) if (k.equals(kind)) return true;
		return false;
	}

	public static String title(String kind) {
		return switch (kind) {
			case "wins" -> "TOP 10 - WINS";
			case "streak" -> "TOP 10 - WIN STREAK";
			default -> "TOP 10 - ELO";
		};
	}

	private static String value(String kind, StatsStore.Stats s) {
		return switch (kind) {
			case "wins" -> s.wins + (s.wins == 1 ? " win" : " wins");
			case "streak" -> "streak " + s.bestStreak;
			default -> s.elo + " Elo";
		};
	}

	/** Quotes and slashes would break the text component, and a name has none of them anyway. */
	private static String clean(String name) {
		if (name == null || name.isEmpty()) return "Unknown";
		return name.replace("\\", "").replace("\"", "").replace("'", "");
	}

	/** The plain lines of a board: the title, then one line per player (or a line that says nobody played). */
	public static List<String> lines(String kind, List<StatsStore.Entry> ranking) {
		List<String> out = new ArrayList<>();
		out.add(title(kind));
		if (ranking.isEmpty()) {
			out.add("Nobody has played a duel yet.");
			return out;
		}
		for (int i = 0; i < Math.min(ROWS, ranking.size()); i++) {
			StatsStore.Entry e = ranking.get(i);
			out.add((i + 1) + ". " + clean(e.name()) + "  " + value(kind, e.stats()));
		}
		return out;
	}

	private static int colorOf(int index) {
		return switch (index) {
			case 0 -> 0xFFAA00; // gold
			case 1 -> 0xFFFFFF; // silver
			case 2 -> 0xCD7F32; // bronze
			default -> 0xAAAAAA;
		};
	}

	/** The text of a board: the title, then one line per player, the first three in gold, silver and bronze. */
	public static Component component(String kind, List<StatsStore.Entry> ranking) {
		MutableComponent out = Component.literal("");
		out.append(Component.literal(title(kind)).withStyle(style -> style.withColor(0xFFFF55).withBold(true)));
		if (ranking.isEmpty()) {
			out.append(Component.literal("\nNobody has played a duel yet.").withStyle(style -> style.withColor(0xAAAAAA)));
			return out;
		}
		for (int i = 0; i < Math.min(ROWS, ranking.size()); i++) {
			StatsStore.Entry e = ranking.get(i);
			int color = colorOf(i);
			boolean top = i < 3;
			out.append(Component.literal("\n" + (i + 1) + ". ").withStyle(style -> style.withColor(color)));
			out.append(Component.literal(clean(e.name())).withStyle(style -> style.withColor(color).withBold(top)));
			out.append(Component.literal("  " + value(kind, e.stats())).withStyle(style -> style.withColor(0x555555)));
		}
		return out;
	}

	// ------------------------------------------------------------------ the entities

	private static String tag(String kind) {
		return TAG_PREFIX + kind;
	}

	private static List<Entity> entitiesOf(ServerLevel level, Board b) {
		return level.getEntities((Entity) null, new AABB(b.x - 1.0, b.y - 1.0, b.z - 1.0, b.x + 1.0, b.y + 1.0, b.z + 1.0), e -> e.entityTags().contains(tag(b.kind)));
	}

	private static ServerLevel levelOf(MinecraftServer server, Board b) {
		try {
			return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(b.dimension)));
		} catch (RuntimeException e) {
			return null;
		}
	}

	static String summonCommand(String kind, double x, double y, double z) {
		String nbt = "{Tags:[\"" + tag(kind) + "\"],billboard:\"center\",line_width:200,background:1073741824,shadow:1b,see_through:0b"
			+ ",text:{text:\"" + title(kind) + "\",color:\"yellow\",bold:true}"
			+ ",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[1.2f,1.2f,1.2f],translation:[0f,0f,0f]}}";
		return String.format(Locale.ROOT, "summon minecraft:text_display %.2f %.2f %.2f %s", x, y, z, nbt);
	}

	/** Puts a board at this place and fills it. Returns the new text display, or null when it could not be created. */
	public static synchronized Entity place(ServerLevel level, double x, double y, double z, String kind) {
		ensureLoaded();
		MinecraftServer server = level.getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), summonCommand(kind, x, y, z));
		Board board = new Board();
		board.dimension = level.dimension().identifier().toString();
		board.x = x;
		board.y = y;
		board.z = z;
		board.kind = kind;
		List<Entity> made = entitiesOf(level, board);
		if (made.isEmpty()) return null;
		BOARDS.add(board);
		save();
		refresh(server);
		return made.get(0);
	}

	/** Takes away the board that is closest to this place (within the radius). Returns false when there is none. */
	public static synchronized boolean remove(ServerLevel level, double x, double y, double z, double radius) {
		ensureLoaded();
		String dimension = level.dimension().identifier().toString();
		Board best = null;
		double bestDistance = radius * radius;
		for (Board b : BOARDS) {
			if (!b.dimension.equals(dimension)) continue;
			double d = (b.x - x) * (b.x - x) + (b.y - y) * (b.y - y) + (b.z - z) * (b.z - z);
			if (d <= bestDistance) {
				bestDistance = d;
				best = b;
			}
		}
		if (best == null) return false;
		for (Entity e : entitiesOf(level, best)) {
			LAST.remove(e.getStringUUID());
			e.discard();
		}
		BOARDS.remove(best);
		save();
		return true;
	}

	public static synchronized int count() {
		ensureLoaded();
		return BOARDS.size();
	}

	/** Where the boards are, for /leaderboard list. */
	public static synchronized List<String> describe() {
		ensureLoaded();
		List<String> out = new ArrayList<>();
		for (Board b : BOARDS) out.add(b.kind + " at " + Math.round(b.x) + " " + Math.round(b.y) + " " + Math.round(b.z) + " (" + b.dimension + ")");
		return out;
	}

	/** Writes the current ranking on every board whose chunk is loaded. Returns how many boards got a new text. */
	public static synchronized int refresh(MinecraftServer server) {
		ensureLoaded();
		int changed = 0;
		skipped = 0;
		Map<String, List<StatsStore.Entry>> rankings = new HashMap<>();
		for (Board b : BOARDS) {
			ServerLevel level = levelOf(server, b);
			if (level == null || !level.hasChunkAt(BlockPos.containing(b.x, b.y, b.z))) {
				skipped++;
				continue;
			}
			List<Entity> found = entitiesOf(level, b);
			if (found.isEmpty()) {
				skipped++;
				continue;
			}
			for (int i = 1; i < found.size(); i++) found.get(i).discard(); // two on top of each other: one is enough
			if (!(found.get(0) instanceof Display.TextDisplay board)) continue;
			List<StatsStore.Entry> ranking = rankings.computeIfAbsent(b.kind, k -> source.apply(k));
			String key = String.join("\n", lines(b.kind, ranking));
			if (key.equals(LAST.get(board.getStringUUID()))) continue;
			board.setText(component(b.kind, ranking));
			LAST.put(board.getStringUUID(), key);
			changed++;
		}
		return changed;
	}

	/** Something changed in the rankings (a duel ended): the boards are refreshed in a couple of seconds. */
	public static void markDirty() {
		if (dueAt < 0) dueAt = ticks + SOON_TICKS;
		retryUntil = ticks + RETRY_FOR;
	}

	// ------------------------------------------------------------------ for the self-test

	public static synchronized void resetForTest() {
		BOARDS.clear();
		LAST.clear();
		loaded = true;
		dueAt = -1;
		retryUntil = -1;
		skipped = 0;
		source = StatsStore::ranking;
		if (worldFile != null) {
			try {
				Files.deleteIfExists(worldFile);
			} catch (IOException ignored) {
				// the test works with the memory
			}
		}
	}

	public static synchronized void reloadForTest() {
		loaded = false;
		LAST.clear();
		ensureLoaded();
	}

	/** Forgets what was sent, so the next refresh writes every board again. */
	public static synchronized void forgetLastForTest() {
		LAST.clear();
	}

	public static synchronized int skippedForTest() {
		return skipped;
	}

	public static synchronized int dueInForTest() {
		return dueAt < 0 ? -1 : dueAt - ticks;
	}

	// ------------------------------------------------------------------ commands and events

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		Predicate<CommandSourceStack> admin = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
		dispatcher.register(Commands.literal("leaderboard").requires(admin)
			.then(Commands.literal("place").then(Commands.argument("kind", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KINDS, builder))
				.executes(ctx -> placeHere(ctx.getSource(), StringArgumentType.getString(ctx, "kind").toLowerCase(Locale.ROOT)))))
			.then(Commands.literal("remove").executes(ctx -> removeHere(ctx.getSource())))
			.then(Commands.literal("refresh").executes(ctx -> {
				int n = refresh(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Msg.ok(n + (n == 1 ? " board updated." : " boards updated.")), false);
				return n;
			}))
			.then(Commands.literal("list").executes(ctx -> list(ctx.getSource()))));
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> bind(server.getWorldPath(LevelResource.ROOT).resolve("pvpduels").resolve("boards.json")));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> bind(null));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticks++;
			if (ticks % REFRESH_TICKS == 0 || (dueAt >= 0 && ticks >= dueAt)) {
				dueAt = -1;
				refresh(server);
				// a board in a place nobody is near cannot be written: try again soon, for a few minutes after the duel
				if (skipped > 0 && ticks < retryUntil) dueAt = ticks + RETRY_TICKS;
			}
		});
	}

	private static int placeHere(CommandSourceStack source, String kind) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Msg.err("Stand where the board should be: only a player can place one."));
			return 0;
		}
		if (!validKind(kind)) {
			source.sendFailure(Msg.err("Use /leaderboard place elo, wins or streak."));
			return 0;
		}
		Entity made = place((ServerLevel) p.level(), p.getX(), p.getY() + HEIGHT, p.getZ(), kind);
		if (made == null) {
			source.sendFailure(Msg.err("Could not create the board here."));
			return 0;
		}
		source.sendSuccess(() -> Msg.ok("Board placed: " + title(kind) + ". It updates by itself; /leaderboard remove takes the closest one away."), true);
		return 1;
	}

	private static int removeHere(CommandSourceStack source) {
		ServerPlayer p = source.getPlayer();
		if (p == null) {
			source.sendFailure(Msg.err("Stand next to the board you want to remove."));
			return 0;
		}
		if (!remove((ServerLevel) p.level(), p.getX(), p.getY(), p.getZ(), 12)) {
			source.sendFailure(Msg.err("There is no board within 12 blocks of you. /leaderboard list shows where they are."));
			return 0;
		}
		source.sendSuccess(() -> Msg.ok("The closest board was removed."), true);
		return 1;
	}

	private static int list(CommandSourceStack source) {
		List<String> all = describe();
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("There are no boards. Stand where one should be and use /leaderboard place elo|wins|streak."), false);
			return 1;
		}
		source.sendSuccess(() -> Msg.title("Boards (" + all.size() + ")"), false);
		for (String line : all) source.sendSuccess(() -> Msg.info("  " + line), false);
		return all.size();
	}
}
