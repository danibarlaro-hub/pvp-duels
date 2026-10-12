package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvpduels.kit.KitStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a player had before a duel (items, game mode, health, food, position). It is written to disk before
 * the duel starts, so if the game closes or crashes the player gets it all back the next time they join.
 */
public final class PlayerBackup {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private PlayerBackup() {}

	private static Path file(UUID id) {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("backups").resolve(id + ".json");
	}

	public static boolean exists(UUID id) {
		return Files.exists(file(id));
	}

	public static void discard(UUID id) {
		try {
			Files.deleteIfExists(file(id));
		} catch (IOException ignored) {
			// nothing to do
		}
	}

	public static void save(MinecraftServer server, ServerPlayer p) throws IOException {
		// The lobby hotbar items are not part of what the player owns: give back what they hide first.
		LobbyKit.strip(p);
		JsonObject root = new JsonObject();
		root.add("slots", KitStore.encodeInventory(server, p.getInventory()));
		root.addProperty("dimension", p.level().dimension().identifier().toString());
		root.addProperty("x", p.getX());
		root.addProperty("y", p.getY());
		root.addProperty("z", p.getZ());
		root.addProperty("yaw", p.getYRot());
		root.addProperty("pitch", p.getXRot());
		root.addProperty("gamemode", p.gameMode().getName());
		root.addProperty("health", p.getHealth());
		root.addProperty("food", p.getFoodData().getFoodLevel());
		root.addProperty("saturation", p.getFoodData().getSaturationLevel());
		Files.createDirectories(file(p.getUUID()).getParent());
		Files.writeString(file(p.getUUID()), GSON.toJson(root));
	}

	/** Gives the player everything back (at the place they were) and deletes the backup. Returns false if there was nothing to restore. */
	public static boolean restore(MinecraftServer server, ServerPlayer p) {
		return restore(server, p, false);
	}

	/**
	 * Same, but with toLobby the player is sent to the lobby instead of where they were, if there is a lobby and
	 * they did not turn that off (/lobby return off).
	 */
	public static boolean restore(MinecraftServer server, ServerPlayer p, boolean toLobby) {
		Path f = file(p.getUUID());
		if (!Files.exists(f)) return false;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();

			p.removeAllEffects();
			p.clearFire();
			p.closeContainer();

			ItemStack[] stacks = KitStore.decodeInventory(server, root.getAsJsonArray("slots"));
			KitStore.setInventory(p, stacks);

			boolean lobby = toLobby && Lobby.appliesTo(server, p) && Lobby.teleport(server, p);
			if (!lobby) {
				ServerLevel target = server.getLevel(ResourceKey.create(Registries.DIMENSION,
					Identifier.parse(root.get("dimension").getAsString())));
				if (target == null) target = server.overworld();
				p.teleportTo(target, root.get("x").getAsDouble(), root.get("y").getAsDouble(), root.get("z").getAsDouble(),
					Set.of(), root.get("yaw").getAsFloat(), root.get("pitch").getAsFloat(), true);
				p.setDeltaMovement(0, 0, 0);
				p.resetFallDistance();
			}

			p.setGameMode(gameType(root.get("gamemode").getAsString()));
			p.setHealth(Math.min(root.get("health").getAsFloat(), p.getMaxHealth()));
			p.getFoodData().setFoodLevel(root.get("food").getAsInt());
			p.getFoodData().setSaturation(root.get("saturation").getAsFloat());

			Files.deleteIfExists(f);
			return true;
		} catch (Exception e) {
			com.pvpduels.PvpDuelsMod.LOGGER.error("Could not restore the backup of {}", p.getName().getString(), e);
			return false;
		}
	}

	private static GameType gameType(String name) {
		return GameType.byName(name, GameType.SURVIVAL);
	}
}
