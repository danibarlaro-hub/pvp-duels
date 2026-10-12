package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import com.pvpduels.util.Prefs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The lobby: a place you decorate yourself. An admin sets it with /setlobby; after a duel everybody who has not
 * turned it off with /lobby return off is taken there (instead of back to where they stood), and /lobby takes you
 * there any time you are not busy.
 */
public final class Lobby {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Spot(String dimension, double x, double y, double z, float yaw, float pitch) {}

	private static Spot spot;
	private static boolean loaded;

	private Lobby() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("lobby.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			spot = new Spot(root.get("dimension").getAsString(), root.get("x").getAsDouble(), root.get("y").getAsDouble(),
				root.get("z").getAsDouble(), root.get("yaw").getAsFloat(), root.get("pitch").getAsFloat());
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the lobby file: {}", e.toString());
		}
	}

	public static synchronized boolean isSet() {
		ensureLoaded();
		return spot != null;
	}

	public static synchronized void set(ServerPlayer p) throws IOException {
		ensureLoaded();
		spot = new Spot(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
		JsonObject root = new JsonObject();
		root.addProperty("dimension", spot.dimension());
		root.addProperty("x", spot.x());
		root.addProperty("y", spot.y());
		root.addProperty("z", spot.z());
		root.addProperty("yaw", spot.yaw());
		root.addProperty("pitch", spot.pitch());
		Files.createDirectories(file().getParent());
		Files.writeString(file(), GSON.toJson(root));
	}

	public static synchronized boolean clear() throws IOException {
		ensureLoaded();
		boolean had = spot != null;
		spot = null;
		Files.deleteIfExists(file());
		return had;
	}

	/** True when this player would be taken to the lobby after a duel. */
	public static boolean appliesTo(MinecraftServer server, ServerPlayer p) {
		return isSet() && Prefs.lobbyReturn(p.getUUID()) && level(server) != null;
	}

	private static synchronized ServerLevel level(MinecraftServer server) {
		ensureLoaded();
		if (spot == null) return null;
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(spot.dimension())));
	}

	/** Teleports the player to the lobby. Returns false when there is none (or its world is not loaded). */
	public static synchronized boolean teleport(MinecraftServer server, ServerPlayer p) {
		ServerLevel level = level(server);
		if (level == null) return false;
		p.teleportTo(level, spot.x(), spot.y(), spot.z(), Set.of(), spot.yaw(), spot.pitch(), true);
		p.setDeltaMovement(0, 0, 0);
		p.resetFallDistance();
		return true;
	}

	// ------------------------------------------------------------------ commands

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("setlobby")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				try {
					set(p);
				} catch (IOException e) {
					ctx.getSource().sendFailure(Msg.err("Could not save the lobby: " + e.getMessage()));
					return 0;
				}
				ctx.getSource().sendSuccess(() -> Msg.ok("The lobby is here now. After duels people are sent here (they can turn that off with /lobby return off). /lobby takes you here."), false);
				return 1;
			}));

		dispatcher.register(Commands.literal("lobby")
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				if (DuelManager.isBusy(p.getUUID())) {
					ctx.getSource().sendFailure(Msg.err("You are busy: finish your duel or leave the workshop / spectator mode (/leave) first."));
					return 0;
				}
				if (!teleport(ctx.getSource().getServer(), p)) {
					ctx.getSource().sendFailure(Msg.err("There is no lobby yet. An admin can create one with /setlobby."));
					return 0;
				}
				ctx.getSource().sendSuccess(() -> Msg.ok("Welcome to the lobby."), false);
				return 1;
			})
			.then(Commands.literal("return")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					boolean on = Prefs.lobbyReturn(p.getUUID());
					ctx.getSource().sendSuccess(() -> Msg.info("After a duel you are sent to " + (on ? "the lobby" : "where you were before it")
						+ ". Change it with /lobby return on|off."), false);
					return 1;
				})
				.then(Commands.argument("setting", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						String setting = StringArgumentType.getString(ctx, "setting");
						if (!setting.equals("on") && !setting.equals("off")) {
							ctx.getSource().sendFailure(Msg.err("Use /lobby return on or /lobby return off."));
							return 0;
						}
						boolean on = setting.equals("on");
						Prefs.setLobbyReturn(p.getUUID(), on);
						ctx.getSource().sendSuccess(() -> Msg.ok(on ? "After a duel you will be sent to the lobby."
							: "After a duel you will go back to where you were."), false);
						return 1;
					})))
			.then(Commands.literal("clear")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					try {
						boolean had = clear();
						ctx.getSource().sendSuccess(() -> Msg.ok(had ? "The lobby was removed." : "There was no lobby."), false);
						return 1;
					} catch (IOException e) {
						ctx.getSource().sendFailure(Msg.err("Could not remove the lobby: " + e.getMessage()));
						return 0;
					}
				})));
	}
}
