package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Clutch scenarios: a practice duel against the bot where you start with only two hearts and nothing brings them back by itself. The
 * first time you knock the bot out is a win, getting knocked out yourself is a loss. Your tries, wins and your best finish (the most
 * health you had left) are saved. /clutch [kit] [static|passive|easy|normal|hard], /clutch records.
 */
public final class Clutch {
	/** Two hearts. */
	public static final float START_HEALTH = 4f;

	/** What is saved for one player. */
	public static final class Record {
		public String name = "";
		public int tries;
		public int wins;
		public int streak;
		public int bestStreak;
		/** The most health points (2 = one heart) you had left when you won. */
		public float bestLeft;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Map<String, Record> all = new HashMap<>();
	private static boolean loaded;

	private Clutch() {}

	// ------------------------------------------------------------------ the file

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("clutch.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Record> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Record>>() {}.getType());
			if (read != null) all = new HashMap<>(read);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the clutch file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(all));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the clutch file: {}", e.toString());
		}
	}

	/** A copy of the numbers of this player (all zero when they never tried). */
	public static synchronized Record get(UUID id) {
		ensureLoaded();
		Record r = all.get(id.toString());
		Record copy = new Record();
		if (r != null) {
			copy.name = r.name;
			copy.tries = r.tries;
			copy.wins = r.wins;
			copy.streak = r.streak;
			copy.bestStreak = r.bestStreak;
			copy.bestLeft = r.bestLeft;
		}
		return copy;
	}

	// ------------------------------------------------------------------ the result

	/** Hearts with one decimal: 3 health points are "1.5". */
	static String hearts(float health) {
		return String.format(Locale.ROOT, "%.1f", health / 2f);
	}

	private static String recordLine(Record r) {
		return "Clutch record: " + r.wins + (r.wins == 1 ? " win" : " wins") + " in " + r.tries + (r.tries == 1 ? " try" : " tries") + ", win streak " + r.streak
			+ " (best " + r.bestStreak + ")" + (r.wins > 0 ? ", best finish " + hearts(r.bestLeft) + " hearts left." : ".");
	}

	/**
	 * Saves the end of a try and gives the lines to tell the player. {@code left} is the health you had when you knocked the bot out
	 * (ignored for a loss).
	 */
	static synchronized List<String> finish(UUID id, String name, boolean won, float left, String botName) {
		ensureLoaded();
		Record r = all.computeIfAbsent(id.toString(), k -> new Record());
		r.name = name;
		r.tries++;
		List<String> lines = new ArrayList<>();
		if (won) {
			r.wins++;
			r.streak++;
			r.bestStreak = Math.max(r.bestStreak, r.streak);
			lines.add("CLUTCH! You knocked out " + botName + " with " + hearts(left) + " hearts left.");
			if (left > r.bestLeft) {
				lines.add("NEW BEST FINISH: " + hearts(left) + " hearts left (was " + hearts(r.bestLeft) + ").");
				r.bestLeft = left;
			}
		} else {
			r.streak = 0;
			lines.add("Not this time: " + botName + " got you.");
		}
		lines.add(recordLine(r));
		save();
		return lines;
	}

	// ------------------------------------------------------------------ command

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("clutch")
			.executes(ctx -> open(ctx.getSource(), null, null))
			.then(Commands.literal("records").executes(ctx -> records(ctx.getSource().getPlayerOrException())))
			.then(Commands.argument("kit", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
				.executes(ctx -> open(ctx.getSource(), StringArgumentType.getString(ctx, "kit"), null))
				.then(Commands.argument("bot", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("static", "passive", "easy", "normal", "hard"), builder))
					.executes(ctx -> open(ctx.getSource(), StringArgumentType.getString(ctx, "kit"), StringArgumentType.getString(ctx, "bot"))))));
	}

	private static int records(ServerPlayer p) {
		Record r = get(p.getUUID());
		if (r.tries == 0) {
			p.sendSystemMessage(Msg.info("You have not tried a clutch yet. /clutch [kit] [bot] starts one: two hearts, no regeneration, knock the bot out."));
			return 0;
		}
		p.sendSystemMessage(Msg.title("Your clutch record"));
		p.sendSystemMessage(Msg.info("  " + recordLine(r)));
		return 1;
	}

	private static int open(CommandSourceStack source, String kit, String botText) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		if (DuelManager.isBusy(p.getUUID())) {
			source.sendFailure(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return 0;
		}
		BotLevel level = BotLevel.NORMAL;
		if (botText != null) {
			level = BotLevel.parse(botText);
			if (level == null) {
				source.sendFailure(Msg.err("The bot can be: static, passive, easy, normal or hard."));
				return 0;
			}
		}
		if (kit == null) {
			final BotLevel chosen = level;
			DuelMenus.pickKit(p, "Clutch: pick a kit", 0, (who, name) -> {
				who.closeContainer();
				BotDuel.startLater(who, name, chosen, true);
			});
			return 1;
		}
		String problem = BotDuel.start(p, kit, level, true);
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}

	// ------------------------------------------------------------------ for the tests

	public static synchronized void resetForTest() {
		all = new HashMap<>();
		loaded = true;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	public static synchronized void reloadForTest() {
		all = new HashMap<>();
		loaded = false;
		ensureLoaded();
	}
}
