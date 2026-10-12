package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /spectate &lt;player&gt;: watch a running duel in spectator mode. Like in a duel or the workshop, the inventory,
 * game mode and position are saved to disk first and come back with /leave (or when the duel ends).
 */
public final class Spectators {
	private static final Map<UUID, Duel> WATCHING = new HashMap<>();

	private static final SuggestionProvider<CommandSourceStack> DUELISTS = (ctx, builder) -> {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
			if (DuelManager.isInDuel(p.getUUID())) names.add(p.getName().getString());
		}
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private Spectators() {}

	public static boolean isIn(UUID id) {
		return WATCHING.containsKey(id);
	}

	/** The duel this player is watching, or null. */
	public static Duel watching(UUID id) {
		return WATCHING.get(id);
	}

	/** Returns an error text, or null when the player is now watching the duel of the target. */
	public static String enter(ServerPlayer p, ServerPlayer target) {
		MinecraftServer server = p.level().getServer();
		if (target.getUUID().equals(p.getUUID())) return "You can't watch yourself.";
		Duel duel = DuelManager.duelOf(target.getUUID());
		if (duel == null || duel.closed) return target.getName().getString() + " is not in a duel right now.";
		if (DuelManager.isInDuel(p.getUUID())) return "You are in a duel yourself.";
		ServerLevel level = duel.space.level(server);
		if (level == null) return "The duel world is not loaded.";

		if (WATCHING.get(p.getUUID()) == duel) {
			moveNear(p, target, level);
			return null;
		}
		if (DuelManager.isBusy(p.getUUID())) return "You are busy: use /leave to stop what you are doing first.";

		// A backup still on disk means the player was never given their stuff back: do that first.
		if (PlayerBackup.exists(p.getUUID())) PlayerBackup.restore(server, p);
		try {
			PlayerBackup.save(server, p);
		} catch (IOException e) {
			return "Could not save your inventory, so you were not sent to the duel: " + e.getMessage();
		}
		WATCHING.put(p.getUUID(), duel);
		p.closeContainer();
		p.removeAllEffects();
		p.setGameMode(GameType.SPECTATOR);
		moveNear(p, target, level);
		p.sendSystemMessage(Msg.title("Watching " + duel.names[0] + " vs " + duel.names[1]));
		p.sendSystemMessage(Msg.info("  You are in spectator mode. /leave stops watching and brings you back."));
		PvpDuelsMod.LOGGER.info("{} is watching the duel {} vs {}", p.getName().getString(), duel.names[0], duel.names[1]);
		return null;
	}

	private static void moveNear(ServerPlayer p, ServerPlayer target, ServerLevel level) {
		double x = target.level() == level ? target.getX() : 0;
		double y = target.level() == level ? target.getY() + 4 : 100;
		double z = target.level() == level ? target.getZ() : 0;
		p.teleportTo(level, x, y, z, Set.of(), p.getYRot(), 30f, true);
		p.setDeltaMovement(0, 0, 0);
	}

	public static void exit(ServerPlayer p) {
		Duel duel = WATCHING.remove(p.getUUID());
		if (duel == null) return;
		PlayerBackup.restore(p.level().getServer(), p);
		p.sendSystemMessage(Msg.ok("You stopped watching. Your inventory was restored."));
	}

	/** The duel is over: everybody who watched it goes back. */
	static void release(Duel duel) {
		MinecraftServer server = duelServer(duel);
		for (Map.Entry<UUID, Duel> entry : Map.copyOf(WATCHING).entrySet()) {
			if (entry.getValue() != duel) continue;
			ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(entry.getKey());
			if (p != null) exit(p);
			else WATCHING.remove(entry.getKey());
		}
	}

	private static MinecraftServer duelServer(Duel duel) {
		return duel.server();
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Their inventory backup stays on disk: it is given back the next time they join.
			WATCHING.remove(handler.player.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID id : Set.copyOf(WATCHING.keySet())) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) exit(p);
			}
			WATCHING.clear();
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("spectate")
			.then(Commands.argument("player", StringArgumentType.word()).suggests(DUELISTS).executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				String name = StringArgumentType.getString(ctx, "player");
				ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
				if (target == null) {
					ctx.getSource().sendFailure(Msg.err("No player named '" + name + "' is online."));
					return 0;
				}
				String problem = enter(p, target);
				if (problem != null) {
					ctx.getSource().sendFailure(Msg.err(problem));
					return 0;
				}
				return 1;
			})));
	}
}
