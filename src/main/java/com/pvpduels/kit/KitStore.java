package com.pvpduels.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stores kits as JSON files in a global folder (.minecraft/pvpduels/kits/UUID/name.json),
 * so they are not tied to any world. One file per kit, so thousands of kits are fine.
 */
public final class KitStore {
	/** Main inventory (0-35), armor (36-39) and offhand (40). */
	public static final int SLOTS = 41;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

	private KitStore() {}

	public record Kit(String name, String profile, ItemStack[] slots) {}

	public static boolean validName(String name) {
		return NAME.matcher(name).matches();
	}

	private static Path dir(UUID owner) {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("kits").resolve(owner.toString());
	}

	/** Folder where this player's kits live (shown in messages so it is easy to find). */
	public static Path folder(UUID owner) {
		return dir(owner);
	}

	private static Path file(UUID owner, String name) {
		return dir(owner).resolve(name + ".json");
	}

	public static boolean exists(UUID owner, String name) {
		return Files.exists(file(owner, name));
	}

	/** Encodes slots 0-40 (inventory, armor, offhand) of an inventory as a JSON array. */
	public static JsonArray encodeInventory(MinecraftServer server, Inventory inv) {
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonArray slots = new JsonArray();
		int available = inv.getContainerSize();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack stack = i < available ? inv.getItem(i) : ItemStack.EMPTY;
			JsonElement encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().orElseGet(JsonObject::new);
			slots.add(encoded);
		}
		return slots;
	}

	public static ItemStack[] decodeInventory(MinecraftServer server, JsonArray arr) {
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		ItemStack[] stacks = new ItemStack[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			if (arr != null && i < arr.size()) {
				stacks[i] = ItemStack.OPTIONAL_CODEC.parse(ops, arr.get(i)).result().orElse(ItemStack.EMPTY);
			} else {
				stacks[i] = ItemStack.EMPTY;
			}
		}
		return stacks;
	}

	/** Replaces the whole inventory with the given 41 stacks. */
	public static void setInventory(ServerPlayer player, ItemStack[] stacks) {
		Inventory inv = player.getInventory();
		inv.clearContent();
		int max = Math.min(inv.getContainerSize(), SLOTS);
		for (int i = 0; i < max; i++) {
			inv.setItem(i, stacks[i].copy());
		}
	}

	/** Saves the player's current inventory as a kit. Keeps the kit's layout profile if it already existed. */
	public static void save(MinecraftServer server, ServerPlayer player, String name) throws IOException {
		UUID owner = player.getUUID();
		Inventory inv = player.getInventory();
		JsonArray slots = encodeInventory(server, inv);
		// The guide book is not part of any kit.
		for (int i = 0; i < SLOTS && i < inv.getContainerSize(); i++) {
			if (com.pvpduels.GuideBook.isGuide(inv.getItem(i))) slots.set(i, new JsonObject());
		}

		JsonObject root = new JsonObject();
		root.addProperty("name", name);
		String oldProfile = existingProfile(owner, name);
		if (oldProfile != null) {
			root.addProperty("profile", oldProfile);
		}
		root.add("slots", slots);

		Files.createDirectories(dir(owner));
		Files.writeString(file(owner, name), GSON.toJson(root));
	}

	private static String existingProfile(UUID owner, String name) {
		try {
			if (!exists(owner, name)) return null;
			JsonObject root = JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject();
			return root.has("profile") ? root.get("profile").getAsString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	public static Optional<Kit> read(MinecraftServer server, UUID owner, String name) throws IOException {
		if (!exists(owner, name)) return Optional.empty();
		JsonObject root = JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject();
		ItemStack[] stacks = decodeInventory(server, root.getAsJsonArray("slots"));
		String profile = root.has("profile") ? root.get("profile").getAsString() : null;
		return Optional.of(new Kit(name, profile, stacks));
	}

	public static void setProfile(UUID owner, String name, String profile) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject();
		if (profile == null) {
			root.remove("profile");
		} else {
			root.addProperty("profile", profile);
		}
		Files.writeString(file(owner, name), GSON.toJson(root));
	}

	/** Replaces the player's whole inventory with the kit. */
	public static void give(ServerPlayer player, Kit kit) {
		setInventory(player, kit.slots());
	}

	public static boolean delete(UUID owner, String name) throws IOException {
		return Files.deleteIfExists(file(owner, name));
	}

	public static List<String> list(UUID owner) {
		Path d = dir(owner);
		if (!Files.isDirectory(d)) return Collections.emptyList();
		try (Stream<Path> files = Files.list(d)) {
			List<String> names = new ArrayList<>();
			files.forEach(p -> {
				String n = p.getFileName().toString();
				if (n.endsWith(".json")) {
					names.add(n.substring(0, n.length() - 5));
				}
			});
			Collections.sort(names);
			return names;
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}
}
