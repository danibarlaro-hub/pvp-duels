package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import com.pvpduels.util.Prefs;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The lobby: a place you decorate yourself. An admin sets it with /setlobby; after a duel everybody who has not
 * turned it off with /lobby return off is taken there (instead of back to where they stood), and /lobby takes you
 * there any time you are not busy.
 * <p>
 * The lobby is saved inside the world (pvpduels/lobby.json in the world folder), so a world that is copied to
 * another computer brings its lobby with it. An admin can also protect the lobby with /lobby protect: inside that
 * radius nobody can break or place blocks and players cannot hurt each other.
 */
public final class Lobby {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Spot(String dimension, double x, double y, double z, float yaw, float pitch) {}

	private static Spot spot;
	private static boolean loaded;
	/** Radius (in blocks, around the lobby) where building and fighting are not allowed. 0 = not protected. */
	private static int protect;
	/** The lobby file of the world that is running now (null before a world starts). */
	private static Path worldFile;

	private Lobby() {}

	/** Where older versions kept the lobby: one file for the whole game folder. It is still read when the world has none. */
	private static Path legacyFile() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("lobby.json");
	}

	private static Path file() {
		return worldFile != null ? worldFile : legacyFile();
	}

	/** Called by the mod at start-up: the lobby follows the world that is loaded. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> reset(server.getWorldPath(LevelResource.ROOT).resolve("pvpduels").resolve("lobby.json")));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset(null));
		ServerTickEvents.END_SERVER_TICK.register(Lobby::rescue);
	}

	private static synchronized void reset(Path file) {
		worldFile = file;
		spot = null;
		protect = 0;
		loaded = false;
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			Path source = file();
			if (!Files.exists(source) && worldFile != null) source = legacyFile();
			if (!Files.exists(source)) return;
			JsonObject root = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
			spot = new Spot(root.get("dimension").getAsString(), root.get("x").getAsDouble(), root.get("y").getAsDouble(),
				root.get("z").getAsDouble(), root.get("yaw").getAsFloat(), root.get("pitch").getAsFloat());
			protect = root.has("protect") ? root.get("protect").getAsInt() : 0;
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the lobby file: {}", e.toString());
		}
	}

	public static synchronized boolean isSet() {
		ensureLoaded();
		return spot != null;
	}

	public static synchronized void set(ServerPlayer p) throws IOException {
		setAt(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	public static synchronized void setAt(String dimension, double x, double y, double z, float yaw, float pitch) throws IOException {
		ensureLoaded();
		spot = new Spot(dimension, x, y, z, yaw, pitch);
		save();
	}

	private static void save() throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("dimension", spot.dimension());
		root.addProperty("x", spot.x());
		root.addProperty("y", spot.y());
		root.addProperty("z", spot.z());
		root.addProperty("yaw", spot.yaw());
		root.addProperty("pitch", spot.pitch());
		root.addProperty("protect", protect);
		Files.createDirectories(file().getParent());
		Files.writeString(file(), GSON.toJson(root));
	}

	public static synchronized boolean clear() throws IOException {
		ensureLoaded();
		boolean had = spot != null;
		spot = null;
		protect = 0;
		Files.deleteIfExists(file());
		Files.deleteIfExists(legacyFile());
		return had;
	}

	// ------------------------------------------------------------------ protection

	public static synchronized int protectRadius() {
		ensureLoaded();
		return spot == null ? 0 : protect;
	}

	/** Turns the protection on (radius > 0) or off (0). Needs a lobby. */
	public static synchronized void setProtectRadius(int radius) throws IOException {
		ensureLoaded();
		if (spot == null) throw new IOException("There is no lobby yet.");
		protect = Math.max(0, radius);
		save();
	}

	/** True when this place is inside the protected area around the lobby. */
	public static synchronized boolean protects(Level level, double x, double z) {
		ensureLoaded();
		if (spot == null || protect <= 0) return false;
		if (!level.dimension().identifier().toString().equals(spot.dimension())) return false;
		double dx = x - spot.x();
		double dz = z - spot.z();
		return dx * dx + dz * dz <= (double) protect * protect;
	}

	/** The radius of the lobby when no protection was set: the lobby hotbar items still need an area. */
	public static final int DEFAULT_RADIUS = 64;

	/** True when the player is in the lobby: same world, within the protected radius (or 64 blocks when there is none). */
	public static synchronized boolean contains(ServerPlayer p) {
		ensureLoaded();
		if (spot == null) return false;
		if (!p.level().dimension().identifier().toString().equals(spot.dimension())) return false;
		double radius = protect > 0 ? protect : DEFAULT_RADIUS;
		double dx = p.getX() - spot.x();
		double dz = p.getZ() - spot.z();
		return dx * dx + dz * dz <= radius * radius;
	}

	/** Somebody who fell off the island (far below the lobby, still near it) is brought back. */
	private static synchronized void rescue(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		ensureLoaded();
		if (spot == null || protect <= 0) return;
		ServerLevel level = level(server);
		if (level == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level() != level || !p.isAlive() || LobbyGuard.bypass(p)) continue;
			if (p.getY() > spot.y() - 70) continue;
			double dx = p.getX() - spot.x();
			double dz = p.getZ() - spot.z();
			if (dx * dx + dz * dz > (double) (protect + 80) * (protect + 80)) continue;
			teleport(server, p);
			p.sendSystemMessage(Msg.info("You fell off the island. Back to the lobby!"));
		}
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
			.then(Commands.literal("protect")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					int r = protectRadius();
					ctx.getSource().sendSuccess(() -> Msg.info(r > 0
						? "The lobby is protected within " + r + " blocks: no building, no fighting. Change it with /lobby protect <radius> or turn it off with /lobby protect off."
						: "The lobby is not protected. Use /lobby protect <radius> (for example 100) to stop building and fighting around it."), false);
					return 1;
				})
				.then(Commands.literal("off")
					.executes(ctx -> {
						try {
							if (!isSet()) {
								ctx.getSource().sendFailure(Msg.err("There is no lobby yet. Use /setlobby first."));
								return 0;
							}
							setProtectRadius(0);
						} catch (IOException e) {
							ctx.getSource().sendFailure(Msg.err("Could not save: " + e.getMessage()));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Msg.ok("The lobby is not protected any more."), false);
						return 1;
					}))
				.then(Commands.argument("radius", IntegerArgumentType.integer(5, 2000))
					.executes(ctx -> {
						int radius = IntegerArgumentType.getInteger(ctx, "radius");
						try {
							if (!isSet()) {
								ctx.getSource().sendFailure(Msg.err("There is no lobby yet. Use /setlobby first."));
								return 0;
							}
							setProtectRadius(radius);
						} catch (IOException e) {
							ctx.getSource().sendFailure(Msg.err("Could not save: " + e.getMessage()));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Msg.ok("The lobby is protected within " + radius
							+ " blocks: nobody can break or place blocks or hurt other players there (admins in creative mode can still build)."), false);
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
