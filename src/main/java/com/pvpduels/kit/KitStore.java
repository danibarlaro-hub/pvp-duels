package com.pvpduels.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

	/**
	 * locked = a kit somebody gave you with /private: you can use it but not share or edit it. from = who made it.
	 * problems = slots that could not be read back exactly (empty for a healthy kit).
	 */
	public record Kit(String name, String profile, ItemStack[] slots, boolean locked, String from, List<String> problems) {
		public Kit(String name, String profile, ItemStack[] slots, boolean locked, String from) {
			this(name, profile, slots, locked, from, List.of());
		}

		public Kit(String name, String profile, ItemStack[] slots) {
			this(name, profile, slots, false, "");
		}

		/** How many of the 41 slots hold something. */
		public int stacks() {
			return countStacks(slots);
		}
	}

	/** What /kit save really wrote: how many slots hold an item in the file, and what could not be saved exactly. */
	public record SaveReport(int stacks, List<String> warnings) {}

	public static int countStacks(ItemStack[] stacks) {
		int n = 0;
		for (ItemStack s : stacks) if (s != null && !s.isEmpty()) n++;
		return n;
	}

	/** Slots 0-40 that hold something worth saving (the guide book does not count). */
	public static int stacksInInventory(Inventory inv) {
		int n = 0;
		for (int i = 0; i < SLOTS && i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !com.pvpduels.GuideBook.isGuide(s) && !com.pvpduels.duel.LobbyKit.isLobbyItem(s)) n++;
		}
		return n;
	}

	/** What the server sees in an inventory, by part, not counting the lobby items and the guide book (for the "your inventory is empty" message). */
	public static String describe(Inventory inv) {
		int hotbar = 0;
		int backpack = 0;
		int armor = 0;
		int offhand = 0;
		for (int i = 0; i < SLOTS && i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || com.pvpduels.GuideBook.isGuide(s) || com.pvpduels.duel.LobbyKit.isLobbyItem(s)) continue;
			if (i < 9) hotbar++;
			else if (i < 36) backpack++;
			else if (i < 40) armor++;
			else offhand++;
		}
		return "hotbar " + hotbar + ", backpack " + backpack + ", armor " + armor + ", offhand " + offhand + " - the lobby items and the guide book don't count";
	}

	/** "hotbar 3", "inventory 12", "boots", "offhand"... */
	public static String slotName(int i) {
		if (i < 9) return "hotbar " + (i + 1);
		if (i < 36) return "inventory " + (i + 1);
		return switch (i) {
			case 36 -> "boots";
			case 37 -> "leggings";
			case 38 -> "chestplate";
			case 39 -> "helmet";
			default -> "offhand";
		};
	}

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
		return encodeInventory(server, inv, new ArrayList<>());
	}

	/** Same, and says in warnings every slot that could not be written exactly as it is. */
	public static JsonArray encodeInventory(MinecraftServer server, Inventory inv, List<String> warnings) {
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonArray slots = new JsonArray();
		int available = inv.getContainerSize();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack stack = i < available ? inv.getItem(i) : ItemStack.EMPTY;
			slots.add(encodeStack(ops, stack, i, warnings));
		}
		return slots;
	}

	/**
	 * One stack as JSON. When Minecraft's strict rules refuse it (a stack bigger than its maximum, odd data...) the
	 * item is still saved in the closest legal form instead of silently turning into an empty slot.
	 */
	private static JsonElement encodeStack(RegistryOps<JsonElement> ops, ItemStack stack, int slot, List<String> warnings) {
		if (stack.isEmpty()) return new JsonObject();
		var first = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
		if (first.result().isPresent()) return first.result().get();
		String why = first.error().map(e -> e.message()).orElse("unknown reason");
		String what = slotName(slot) + " (" + stack.getHoverName().getString() + ")";

		ItemStack smaller = stack.copy();
		if (smaller.getCount() > smaller.getMaxStackSize()) smaller.setCount(smaller.getMaxStackSize());
		var second = ItemStack.OPTIONAL_CODEC.encodeStart(ops, smaller);
		if (second.result().isPresent()) {
			warnings.add(what + ": the stack was too big, saved " + smaller.getCount() + " instead of " + stack.getCount() + ".");
			return second.result().get();
		}

		ItemStack bare = new ItemStack(stack.getItem(), Math.max(1, Math.min(stack.getCount(), stack.getItem().getDefaultMaxStackSize())));
		var third = ItemStack.OPTIONAL_CODEC.encodeStart(ops, bare);
		if (third.result().isPresent()) {
			warnings.add(what + ": its extra data (" + why + ") could not be saved, only the plain item was.");
			return third.result().get();
		}
		warnings.add(what + ": could not be saved (" + why + ").");
		return new JsonObject();
	}

	/** A short list of stacks (like the four hotbar slots the lobby takes over) as a JSON array. */
	public static JsonArray encodeStacks(MinecraftServer server, ItemStack[] stacks) {
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonArray out = new JsonArray();
		List<String> ignored = new ArrayList<>();
		for (int i = 0; i < stacks.length; i++) out.add(encodeStack(ops, stacks[i], i, ignored));
		return out;
	}

	/** The first n stacks of a JSON array written by encodeStacks. */
	public static ItemStack[] decodeStacks(MinecraftServer server, JsonArray arr, int n) {
		ItemStack[] all = decodeInventory(server, arr, new ArrayList<>());
		ItemStack[] out = new ItemStack[n];
		for (int i = 0; i < n; i++) out[i] = i < all.length ? all[i] : ItemStack.EMPTY;
		return out;
	}

	public static ItemStack[] decodeInventory(MinecraftServer server, JsonArray arr) {
		return decodeInventory(server, arr, new ArrayList<>());
	}

	/** Same, and says in problems every non-empty slot that could not be read back exactly. */
	public static ItemStack[] decodeInventory(MinecraftServer server, JsonArray arr, List<String> problems) {
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		ItemStack[] stacks = new ItemStack[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			stacks[i] = ItemStack.EMPTY;
			if (arr == null || i >= arr.size()) continue;
			JsonElement element = arr.get(i);
			if (element == null || element.isJsonNull() || (element.isJsonObject() && element.getAsJsonObject().size() == 0)) continue;
			var parsed = ItemStack.OPTIONAL_CODEC.parse(ops, element);
			if (parsed.result().isPresent()) {
				stacks[i] = parsed.result().get();
				continue;
			}
			String why = parsed.error().map(e -> e.message()).orElse("unknown reason");
			ItemStack rescued = rescue(element);
			if (rescued.isEmpty()) {
				problems.add(slotName(i) + ": could not be read (" + why + ").");
			} else {
				problems.add(slotName(i) + " (" + rescued.getHoverName().getString() + "): only the plain item could be read (" + why + ").");
			}
			stacks[i] = rescued;
		}
		return stacks;
	}

	/** The plain item of a stack whose data Minecraft refuses to read, or an empty stack when even the id is unusable. */
	private static ItemStack rescue(JsonElement element) {
		try {
			if (!element.isJsonObject() || !element.getAsJsonObject().has("id")) return ItemStack.EMPTY;
			Identifier id = Identifier.parse(element.getAsJsonObject().get("id").getAsString());
			if (!BuiltInRegistries.ITEM.containsKey(id)) return ItemStack.EMPTY;
			var item = BuiltInRegistries.ITEM.getValue(id);
			int count = element.getAsJsonObject().has("count") ? element.getAsJsonObject().get("count").getAsInt() : 1;
			count = Math.max(1, Math.min(count, item.getDefaultMaxStackSize()));
			return new ItemStack(item, count);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	/** Replaces the whole inventory with the given 41 stacks and sends it to the player's game again. */
	public static void setInventory(ServerPlayer player, ItemStack[] stacks) {
		// The whole inventory is replaced: the lobby hotbar items (and what they hide) go first; the next tick puts them back if the player is still in the lobby.
		com.pvpduels.duel.LobbyKit.strip(player);
		Inventory inv = player.getInventory();
		inv.clearContent();
		int max = Math.min(inv.getContainerSize(), SLOTS);
		for (int i = 0; i < max; i++) {
			inv.setItem(i, stacks[i].copy());
		}
		resync(player);
	}

	/** Sends the whole inventory to the player's game again, so it shows what the server really has. */
	public static void resync(ServerPlayer player) {
		player.inventoryMenu.sendAllDataToRemote();
		if (player.containerMenu != player.inventoryMenu) player.containerMenu.sendAllDataToRemote();
	}

	/**
	 * Saves the player's current inventory as a kit. Keeps the kit's layout profile if it already existed.
	 * The file is read back right away, so the report says what is really in it.
	 */
	public static SaveReport save(MinecraftServer server, ServerPlayer player, String name) throws IOException {
		UUID owner = player.getUUID();
		Inventory inv = player.getInventory();
		List<String> warnings = new ArrayList<>();
		JsonArray slots = encodeInventory(server, inv, warnings);
		// The guide book is not part of any kit.
		for (int i = 0; i < SLOTS && i < inv.getContainerSize(); i++) {
			if (com.pvpduels.GuideBook.isGuide(inv.getItem(i)) || com.pvpduels.duel.LobbyKit.isLobbyItem(inv.getItem(i))) slots.set(i, new JsonObject());
		}

		JsonObject root = new JsonObject();
		root.addProperty("name", name);
		String oldProfile = existingProfile(owner, name);
		if (oldProfile != null) {
			root.addProperty("profile", oldProfile);
		}
		root.add("slots", slots);

		Files.createDirectories(dir(owner));
		writeFile(file(owner, name), GSON.toJson(root));

		// Read it back from the disk and compare with what the player really has.
		List<String> unreadable = new ArrayList<>();
		ItemStack[] back = decodeInventory(server,
			JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject().getAsJsonArray("slots"), unreadable);
		warnings.addAll(unreadable);
		for (int i = 0; i < SLOTS && i < inv.getContainerSize(); i++) {
			ItemStack live = inv.getItem(i);
			if (live.isEmpty() || com.pvpduels.GuideBook.isGuide(live) || com.pvpduels.duel.LobbyKit.isLobbyItem(live)) continue;
			String key = slotName(i) + " ";
			boolean told = warnings.stream().anyMatch(w -> w.startsWith(key));
			if (!told && !ItemStack.matches(live, back[i])) {
				warnings.add(slotName(i) + " (" + live.getHoverName().getString() + "): it is not the same after saving.");
			}
		}
		return new SaveReport(countStacks(back), warnings);
	}

	/** Writes the file in one piece, so a crash in the middle can never leave half a kit. */
	private static void writeFile(Path target, String text) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, text);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The kit exactly as it is stored (used to hand a copy to somebody else). */
	public static JsonObject readRaw(UUID owner, String name) throws IOException {
		return JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject();
	}

	public static void writeRaw(UUID owner, String name, JsonObject root) throws IOException {
		Files.createDirectories(dir(owner));
		writeFile(file(owner, name), GSON.toJson(root));
	}

	/** True when the kit exists and was given to the player with /private. */
	public static boolean isLocked(UUID owner, String name) {
		try {
			return exists(owner, name) && readRaw(owner, name).has("locked") && readRaw(owner, name).get("locked").getAsBoolean();
		} catch (Exception e) {
			return false;
		}
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
		return Optional.of(parse(server, name, root));
	}

	/** The kit stored in a JSON object (a file of kits/ or of the public shelf). */
	public static Kit parse(MinecraftServer server, String name, JsonObject root) {
		List<String> problems = new ArrayList<>();
		ItemStack[] stacks = decodeInventory(server, root.getAsJsonArray("slots"), problems);
		String profile = root.has("profile") ? root.get("profile").getAsString() : null;
		boolean locked = root.has("locked") && root.get("locked").getAsBoolean();
		String from = root.has("from") ? root.get("from").getAsString() : "";
		return new Kit(name, profile, stacks, locked, from, problems);
	}

	public static void setProfile(UUID owner, String name, String profile) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file(owner, name))).getAsJsonObject();
		if (profile == null) {
			root.remove("profile");
		} else {
			root.addProperty("profile", profile);
		}
		writeFile(file(owner, name), GSON.toJson(root));
	}

	/** Replaces the player's whole inventory with the kit. */
	public static void give(ServerPlayer player, Kit kit) {
		setInventory(player, kit.slots());
	}

	public static boolean delete(UUID owner, String name) throws IOException {
		return Files.deleteIfExists(file(owner, name));
	}

	/** The newest kits first, at most "limit" of them. */
	public static List<String> recent(UUID owner, int limit) {
		Path d = dir(owner);
		if (!Files.isDirectory(d)) return Collections.emptyList();
		try (Stream<Path> files = Files.list(d)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted((x, y) -> Long.compare(modified(y), modified(x)))
				.limit(limit)
				.map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
				.toList();
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
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
