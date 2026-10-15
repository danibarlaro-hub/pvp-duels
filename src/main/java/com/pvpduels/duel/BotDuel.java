package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.duel.TestDummy.Dummy;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /botduel: a practice duel against a bot. The command asks whether the bot should be static (Yes: it stands still and
 * can't be pushed) or not (No: it takes knockback, and you choose how it behaves: passive, easy, normal or hard).
 * It joins a fake player called "Bot", puts you both in the big bedrock practice arena (100 blocks each way, with
 * walls) and starts a duel with unlimited rounds that lasts until you use /leave. The bot is removed again when the
 * duel is over. Practice duels never count for the statistics.
 */
public final class BotDuel {
	private record Bot(String name, BotLevel level) {}

	/** Bots that exist right now. */
	private static final Map<UUID, Bot> BOTS = new HashMap<>();
	/** Bots whose duel just closed; they leave the game on the next tick. */
	private static final List<UUID> LEAVING = new ArrayList<>();

	private BotDuel() {}

	public static boolean isBot(UUID id) {
		return BOTS.containsKey(id);
	}

	/** How this bot behaves, or null when the id is not a bot. */
	public static BotLevel levelOf(UUID id) {
		Bot bot = BOTS.get(id);
		return bot == null ? null : bot.level();
	}

	// ------------------------------------------------------------------ starting

	/** What a bot duel is for: plain practice, the timed combo drill (/drill) or the two-hearts scenario (/clutch). */
	enum Mode { PRACTICE, DRILL, CLUTCH }

	private record Pending(UUID player, String kit, BotLevel level, Mode mode) {}
	/** Practice duels chosen in the menu: they start at the end of this tick, once the menu click is completely handled. */
	private static final List<Pending> PENDING = new ArrayList<>();

	/**
	 * Called from the menu. Minecraft keeps working on the player's inventory after a menu click (it writes what the
	 * client thought the click did), so the kit and the teleport must wait until that is over.
	 */
	static void startLater(ServerPlayer player, String kit, BotLevel level) {
		startLater(player, kit, level, Mode.PRACTICE);
	}

	/** Same, and the mode makes it the timed combo drill of /drill or the clutch scenario of /clutch. */
	static void startLater(ServerPlayer player, String kit, BotLevel level, Mode mode) {
		PENDING.add(new Pending(player.getUUID(), kit, level, mode));
	}

	/** Joins the bot and starts the duel. Returns an error text, or null (also when the reason was already told to the player). */
	static String start(ServerPlayer player, String kit, BotLevel level) {
		return start(player, kit, level, Mode.PRACTICE);
	}

	/**
	 * Same, and the mode DRILL starts the timed combo drill (60 seconds, records, the bot never hits back); with CLUTCH the player starts
	 * with {@link Clutch#START_HEALTH} and no regeneration, and the first KO ends the scenario (see Duel).
	 */
	static String start(ServerPlayer player, String kit, BotLevel level, Mode mode) {
		boolean drill = mode == Mode.DRILL;
		boolean clutch = mode == Mode.CLUTCH;
		MinecraftServer server = player.level().getServer();
		if (DuelManager.isBusy(player.getUUID())) return "You are busy: finish your duel or leave the kit workshop (/leave) first.";
		if (!KitStore.exists(player.getUUID(), kit)) return "You have no kit named '" + kit + "'. Use /kit list.";
		if (DuelManager.activeDuels() >= DuelManager.MAX_DUELS) return "All " + DuelManager.MAX_DUELS + " arenas are busy right now. Try again in a minute.";

		String name = freeName(server);
		Dummy bot = TestDummy.create(server, name);
		bot.setGameMode(GameType.SURVIVAL);
		BOTS.put(bot.getUUID(), new Bot(name, level));

		DuelConfig config = new DuelConfig(kit, ArenaKind.BOT, true, Arena.BOT_RADIUS, WinMode.ROUNDS, 0,
			clutch ? new DuelConfig.Rules(false, true, true, true) : DuelConfig.Rules.DEFAULT);
		DuelManager.startPractice(player, bot, config);

		Duel duel = DuelManager.duelOf(player.getUUID());
		if (duel == null || !duel.practice) {
			// It did not start (the reason was already printed): the bot has nothing to do here.
			LEAVING.add(bot.getUUID());
			return null;
		}
		if (level.pinned()) {
			double[] spot = duel.space.spawn(1);
			bot.pin(duel.space.dimension(), spot[0], spot[1], spot[2]);
		} else {
			bot.setBrain(new BotBrain(bot, level, player.getUUID()));
		}
		if (drill) {
			duel.startDrill();
			player.sendSystemMessage(Msg.info("  Combo drill against " + name + ": once the countdown ends you have " + Duel.drillTicks / 20 + " seconds to hit it as many times as you can."));
			player.sendSystemMessage(Msg.info("  It never hits back, so keep your combo going. Your hits, best combo and accuracy are counted and your records are saved (/drill records)."
				+ " /leave stops it. Practice doesn't count for your stats."));
			return null;
		}
		if (clutch) {
			duel.clutch = true;
			player.setHealth(Clutch.START_HEALTH);
			player.sendSystemMessage(Msg.info("  Clutch against " + name + " (" + level.label + "): " + level.description));
			player.sendSystemMessage(Msg.info("  You have 2 hearts and nothing brings them back by itself. Knock " + name + " out once to win; if you go down it is a loss. /leave stops it."));
			return null;
		}
		player.sendSystemMessage(Msg.info("  Practice duel against " + name + " (" + level.label + "): " + level.description));
		player.sendSystemMessage(Msg.info("  It goes on until you use /leave. A bot you knock out gets up again (KO) and nothing is reset. Your hits, damage and combos are on the sidebar."
			+ " Practice duels don't count for your stats."));
		return null;
	}

	/** "Bot", or "Bot2", "Bot3"... when somebody else already uses the name. */
	private static String freeName(MinecraftServer server) {
		for (int n = 1; ; n++) {
			String name = n == 1 ? "Bot" : "Bot" + n;
			final String candidate = name;
			if (server.getPlayerList().getPlayerByName(name) == null && BOTS.values().stream().noneMatch(b -> b.name().equals(candidate))) return name;
		}
	}

	// ------------------------------------------------------------------ cleaning up

	/** The duel is closed: its bot (if any) leaves the game on the next tick. */
	static void release(Duel duel) {
		for (UUID id : duel.ids) {
			if (BOTS.containsKey(id) && !LEAVING.contains(id)) LEAVING.add(id);
		}
	}

	private static void tick(MinecraftServer server) {
		if (!PENDING.isEmpty()) {
			for (Pending pending : List.copyOf(PENDING)) {
				PENDING.remove(pending);
				ServerPlayer who = server.getPlayerList().getPlayer(pending.player());
				if (who == null) continue;
				String problem = start(who, pending.kit(), pending.level(), pending.mode());
				if (problem != null) who.sendSystemMessage(Msg.err(problem));
			}
		}
		if (LEAVING.isEmpty()) return;
		for (UUID id : List.copyOf(LEAVING)) {
			TestDummy.disconnect(server, id);
			BOTS.remove(id);
			LEAVING.remove(id);
		}
	}

	private static void removeAll(MinecraftServer server) {
		for (UUID id : List.copyOf(BOTS.keySet())) TestDummy.disconnect(server, id);
		BOTS.clear();
		LEAVING.clear();
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(BotDuel::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(BotDuel::removeAll);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PENDING.clear();
			BOTS.clear();
			LEAVING.clear();
		});
	}

	// ------------------------------------------------------------------ command

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("drill")
			.executes(ctx -> drill(ctx.getSource(), null))
			.then(Commands.literal("records").executes(ctx -> records(ctx.getSource())))
			.then(Commands.argument("kit", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
				.executes(ctx -> drill(ctx.getSource(), StringArgumentType.getString(ctx, "kit")))));
		dispatcher.register(Commands.literal("botduel")
			.executes(ctx -> open(ctx.getSource(), null, null))
			.then(Commands.argument("kit", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
				.executes(ctx -> open(ctx.getSource(), StringArgumentType.getString(ctx, "kit"), null))
				.then(Commands.argument("bot", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("static", "passive", "easy", "normal", "hard"), builder))
					.executes(ctx -> open(ctx.getSource(), StringArgumentType.getString(ctx, "kit"), StringArgumentType.getString(ctx, "bot"))))));
	}

	/** /drill [kit]: the timed combo drill. Without a kit you choose one in a menu. */
	private static int drill(CommandSourceStack source, String kit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		if (DuelManager.isBusy(p.getUUID())) {
			source.sendFailure(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return 0;
		}
		if (KitStore.list(p.getUUID()).isEmpty()) {
			source.sendFailure(Msg.err("You have no kits yet. Set up your inventory and run /kit save <name> first."));
			return 0;
		}
		if (kit == null) {
			DuelMenus.openDrill(p);
			return 1;
		}
		if (!KitStore.exists(p.getUUID(), kit)) {
			source.sendFailure(Msg.err("You have no kit named '" + kit + "'. Use /kit list."));
			return 0;
		}
		String problem = start(p, kit, BotLevel.PASSIVE, Mode.DRILL);
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}

	private static int records(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		Drill.Rec rec = Drill.get(p.getUUID());
		if (rec.drills == 0) {
			source.sendSuccess(() -> Msg.info("You have not done a combo drill yet: /drill starts one (" + Duel.drillTicks / 20 + " seconds)."), false);
			return 1;
		}
		source.sendSuccess(() -> Msg.title("Your combo drill records"), false);
		source.sendSuccess(() -> Msg.info("  Best combo: " + rec.combo + "   Most hits: " + rec.hits), false);
		source.sendSuccess(() -> Msg.info("  Best accuracy: " + (rec.accuracy > 0 ? rec.accuracy + "%" : "none yet (a drill needs " + Drill.MIN_SWINGS + " swings of a weapon)")
			+ "   Drills done: " + rec.drills), false);
		return 1;
	}

	private static int open(CommandSourceStack source, String kit, String botText) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		if (DuelManager.isBusy(p.getUUID())) {
			source.sendFailure(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return 0;
		}
		if (kit != null && !KitStore.exists(p.getUUID(), kit)) {
			source.sendFailure(Msg.err("You have no kit named '" + kit + "'. Use /kit list."));
			return 0;
		}
		if (botText != null) {
			BotLevel level = BotLevel.parse(botText);
			if (level == null) {
				source.sendFailure(Msg.err("The bot can be: static, passive, easy, normal or hard."));
				return 0;
			}
			String problem = start(p, kit, level);
			if (problem != null) {
				source.sendFailure(Msg.err(problem));
				return 0;
			}
			return 1;
		}
		DuelMenus.openBot(p, kit);
		return 1;
	}
}
