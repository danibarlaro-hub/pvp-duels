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
 * /botduel: a practice duel against a bot that stands still. The command asks Yes or No, then joins a fake player
 * called "Bot", puts you both in the big bedrock practice arena (100 blocks each way, with walls) and starts a duel
 * with unlimited rounds that lasts until you use /leave. The bot is removed again when the duel is over.
 * Practice duels never count for the statistics.
 */
public final class BotDuel {
	/** Bots that exist right now (id to name). */
	private static final Map<UUID, String> BOTS = new HashMap<>();
	/** Bots whose duel just closed; they leave the game on the next tick. */
	private static final List<UUID> LEAVING = new ArrayList<>();

	private BotDuel() {}

	public static boolean isBot(UUID id) {
		return BOTS.containsKey(id);
	}

	// ------------------------------------------------------------------ starting

	/** Joins the bot and starts the duel. Returns an error text, or null (also when the reason was already told to the player). */
	static String start(ServerPlayer player, String kit) {
		MinecraftServer server = player.level().getServer();
		if (DuelManager.isBusy(player.getUUID())) return "You are busy: finish your duel or leave the kit workshop (/leave) first.";
		if (!KitStore.exists(player.getUUID(), kit)) return "You have no kit named '" + kit + "'. Use /kit list.";
		if (DuelManager.activeDuels() >= DuelManager.MAX_DUELS) return "All " + DuelManager.MAX_DUELS + " arenas are busy right now. Try again in a minute.";

		String name = freeName(server);
		Dummy bot = TestDummy.create(server, name);
		bot.setGameMode(GameType.SURVIVAL);
		BOTS.put(bot.getUUID(), name);

		DuelConfig config = new DuelConfig(kit, ArenaKind.BOT, true, Arena.BOT_RADIUS, WinMode.ROUNDS, 0, DuelConfig.Rules.DEFAULT);
		DuelManager.startPractice(player, bot, config);

		Duel duel = DuelManager.duelOf(player.getUUID());
		if (duel == null || !duel.practice) {
			// It did not start (the reason was already printed): the bot has nothing to do here.
			LEAVING.add(bot.getUUID());
			return null;
		}
		double[] spot = duel.space.spawn(1);
		bot.pin(duel.space.dimension(), spot[0], spot[1], spot[2]);
		player.sendSystemMessage(Msg.info("  Practice duel: " + name + " stands still and never fights back. Rounds go on until you use /leave."
			+ " Practice duels don't count for your stats."));
		return null;
	}

	/** "Bot", or "Bot2", "Bot3"... when somebody else already uses the name. */
	private static String freeName(MinecraftServer server) {
		for (int n = 1; ; n++) {
			String name = n == 1 ? "Bot" : "Bot" + n;
			if (server.getPlayerList().getPlayerByName(name) == null && !BOTS.containsValue(name)) return name;
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
			BOTS.clear();
			LEAVING.clear();
		});
	}

	// ------------------------------------------------------------------ command

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("botduel")
			.executes(ctx -> open(ctx.getSource(), null))
			.then(Commands.argument("kit", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
				.executes(ctx -> open(ctx.getSource(), StringArgumentType.getString(ctx, "kit")))));
	}

	private static int open(CommandSourceStack source, String kit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		if (DuelManager.isBusy(p.getUUID())) {
			source.sendFailure(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return 0;
		}
		if (kit != null && !KitStore.exists(p.getUUID(), kit)) {
			source.sendFailure(Msg.err("You have no kit named '" + kit + "'. Use /kit list."));
			return 0;
		}
		DuelMenus.openBot(p, kit);
		return 1;
	}
}
