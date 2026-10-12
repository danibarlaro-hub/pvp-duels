package com.pvpduels.duel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvpduels.GuideBook;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import com.pvpduels.workshop.Workshop;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lobby hotbar: while a player stands in the lobby (and is not in a duel, the workshop or spectating) four
 * hotbar slots belong to the mod and cannot be moved or dropped:
 * slot 1 Send Duel (sword), slot 2 Leave (barrier), slot 8 Create+ (grass block), slot 9 the guide book.
 * <p>
 * Whatever the player had in those four slots is put on disk first (lobbystash/ in the game folder) and comes back
 * when they leave the lobby, disconnect, die, or when the game closes, so a crash can never lose an item: the next
 * time they join the stash is given back.
 */
public final class LobbyKit {
	public static final int SEND = 0;
	public static final int LEAVE = 1;
	public static final int CREATE = 7;
	public static final int GUIDE = 8;
	static final int[] SLOTS = {SEND, LEAVE, CREATE, GUIDE};
	static final String[] KINDS = {"send", "leave", "create", "guide"};
	/** Every lobby item has the boolean pvpduels_lobby_&lt;kind&gt; in its custom data. */
	public static final String TAG = "pvpduels_lobby_";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, ItemStack[]> STASH = new HashMap<>();
	private static final Map<UUID, Integer> LAST_USE = new HashMap<>();
	private static int tick;

	private LobbyKit() {}

	// ------------------------------------------------------------------ items

	private static ItemStack named(net.minecraft.world.item.Item item, String name, ChatFormatting color, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withColor(color).withBold(true).withItalic(false)));
		List<Component> lines = new ArrayList<>();
		for (String line : lore) lines.add(Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	/** Marks a stack as a lobby item of this kind (the guide book keeps its own mark too). */
	public static ItemStack mark(ItemStack stack, String kind) {
		CompoundTag tag = new CompoundTag();
		CustomData old = stack.get(DataComponents.CUSTOM_DATA);
		if (old != null) tag = old.copyTag();
		tag.putBoolean(TAG + kind, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	static ItemStack make(String kind) {
		return switch (kind) {
			case "send" -> mark(named(Items.DIAMOND_SWORD, "Send Duel", ChatFormatting.GREEN,
				"Right-click: pick a player to challenge.",
				"Right-click or hit a player: challenge them."), kind);
			case "leave" -> mark(named(Items.BARRIER, "Leave", ChatFormatting.RED,
				"Cancels your duel requests and takes you",
				"back to the lobby spawn."), kind);
			case "create" -> mark(named(Items.GRASS_BLOCK, "Create+", ChatFormatting.GREEN,
				"Make your own kits and arenas."), kind);
			default -> mark(GuideBook.create(), kind);
		};
	}

	/** The kind of lobby item this is (send, leave, create, guide), or null. */
	public static String kindOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();
		for (String kind : KINDS) {
			if (tag.contains(TAG + kind)) return kind;
		}
		return null;
	}

	public static boolean isLobbyItem(ItemStack stack) {
		return kindOf(stack) != null;
	}

	static boolean isReserved(int inventorySlot) {
		for (int s : SLOTS) if (s == inventorySlot) return true;
		return false;
	}

	// ------------------------------------------------------------------ who gets them

	/** True while the lobby items are in this player's hotbar. */
	public static boolean isActive(UUID id) {
		return STASH.containsKey(id);
	}

	static boolean eligible(ServerPlayer p) {
		if (!p.isAlive() || p.isSpectator() || p.isRemoved()) return false;
		if (!Lobby.isSet() || !Lobby.contains(p)) return false;
		if (LobbyGuard.bypass(p)) return false; // an admin decorating in creative mode needs a free inventory
		if (DuelManager.isBusy(p.getUUID())) return false;
		return !PlayerBackup.exists(p.getUUID());
	}

	// ------------------------------------------------------------------ stash on disk

	private static Path file(UUID id) {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("lobbystash").resolve(id + ".json");
	}

	private static ItemStack[] readFile(MinecraftServer server, UUID id) {
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file(id))).getAsJsonObject();
			return KitStore.decodeStacks(server, root.getAsJsonArray("slots"), SLOTS.length);
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Could not read the lobby stash of {}: {}", id, e.toString());
			return null;
		}
	}

	/** For the self-test: lets the next use through, however soon it comes after the last one. */
	static void clearCooldowns() {
		LAST_USE.clear();
	}

	/** For the self-test: forget the stash in memory, like after a crash, so only the file on disk is left. */
	static void forgetInMemory(UUID id) {
		STASH.remove(id);
	}

	/** True when a stash file is on disk for this player (it was never given back). */
	static boolean hasStashFile(UUID id) {
		return Files.exists(file(id));
	}

	// ------------------------------------------------------------------ give / take back

	/** Hides what the player has in the four slots and puts the lobby items there. Returns false when it could not be saved. */
	static boolean give(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		Inventory inv = p.getInventory();
		ItemStack[] hidden = new ItemStack[SLOTS.length];
		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack here = inv.getItem(SLOTS[i]);
			hidden[i] = isLobbyItem(here) ? ItemStack.EMPTY : here.copy();
		}
		try {
			JsonObject root = new JsonObject();
			root.add("slots", KitStore.encodeStacks(server, hidden));
			Files.createDirectories(file(p.getUUID()).getParent());
			Files.writeString(file(p.getUUID()), GSON.toJson(root));
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the lobby stash of {}: {}", p.getName().getString(), e.toString());
			return false;
		}
		STASH.put(p.getUUID(), hidden);
		for (int i = 0; i < SLOTS.length; i++) inv.setItem(SLOTS[i], make(KINDS[i]));
		p.containerMenu.sendAllDataToRemote();
		return true;
	}

	/** Removes the lobby items and gives back what they were hiding. Safe to call any time: it does nothing when there is nothing to undo. */
	public static void strip(ServerPlayer p) {
		UUID id = p.getUUID();
		MinecraftServer server = p.level().getServer();
		ItemStack[] hidden = STASH.remove(id);
		boolean onDisk = hasStashFile(id);
		if (hidden == null && onDisk) hidden = readFile(server, id);
		Inventory inv = p.getInventory();
		boolean removed = removeLobbyItems(p);
		if (hidden != null) {
			for (int i = 0; i < SLOTS.length; i++) {
				ItemStack current = inv.getItem(SLOTS[i]);
				inv.setItem(SLOTS[i], hidden[i]);
				if (!current.isEmpty() && !isLobbyItem(current)) keepAfterStrip(p, current);
			}
		}
		if (onDisk) {
			try {
				Files.deleteIfExists(file(id));
			} catch (IOException e) {
				PvpDuelsMod.LOGGER.warn("Could not delete the lobby stash of {}: {}", id, e.toString());
			}
		}
		if (removed || hidden != null) p.containerMenu.sendAllDataToRemote();
	}

	/** Takes every lobby item out of the inventory, the open window and the cursor. */
	private static boolean removeLobbyItems(ServerPlayer p) {
		boolean any = false;
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isLobbyItem(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
				any = true;
			}
		}
		for (AbstractContainerMenu menu : new AbstractContainerMenu[] {p.containerMenu, p.inventoryMenu}) {
			for (Slot slot : menu.slots) {
				if (isLobbyItem(slot.getItem())) {
					slot.set(ItemStack.EMPTY);
					any = true;
				}
			}
			if (isLobbyItem(menu.getCarried())) {
				menu.setCarried(ItemStack.EMPTY);
				any = true;
			}
		}
		return any;
	}

	/** Puts every lobby item back where it belongs and removes copies that ended up somewhere else. */
	static void repair(ServerPlayer p) {
		Inventory inv = p.getInventory();
		boolean changed = false;
		for (int j = 0; j < inv.getContainerSize(); j++) {
			ItemStack here = inv.getItem(j);
			String kind = kindOf(here);
			if (kind == null) continue;
			if (isReserved(j) && KINDS[indexOfSlot(j)].equals(kind)) continue;
			inv.setItem(j, ItemStack.EMPTY);
			changed = true;
		}
		for (AbstractContainerMenu menu : new AbstractContainerMenu[] {p.containerMenu, p.inventoryMenu}) {
			for (Slot slot : menu.slots) {
				if (slot.container == inv) continue;
				if (isLobbyItem(slot.getItem())) {
					slot.set(ItemStack.EMPTY);
					changed = true;
				}
			}
			if (isLobbyItem(menu.getCarried())) {
				menu.setCarried(ItemStack.EMPTY);
				changed = true;
			}
		}
		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack here = inv.getItem(SLOTS[i]);
			if (KINDS[i].equals(kindOf(here)) && here.getCount() == 1) continue;
			if (!here.isEmpty() && !isLobbyItem(here)) {
				// Something else got into a lobby slot: keep it, the stash only holds what was there before.
				// It must leave the slot first: adding a stack that is still in the inventory merges it with itself.
				inv.setItem(SLOTS[i], ItemStack.EMPTY);
				keep(p, here);
			}
			inv.setItem(SLOTS[i], make(KINDS[i]));
			changed = true;
		}
		if (changed) p.containerMenu.sendAllDataToRemote();
	}

	/** Like keep, but the lobby slots are free again at this point (they were just given back), so only the other slots are used. */
	private static void keepAfterStrip(ServerPlayer p, ItemStack stack) {
		keep(p, stack);
	}

	/** Puts a stack that was in the way in the first free place of the inventory (outside the lobby slots), or drops it. */
	private static void keep(ServerPlayer p, ItemStack stack) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			if (isReserved(i) || !inv.getItem(i).isEmpty()) continue;
			inv.setItem(i, stack);
			return;
		}
		p.drop(stack, false);
	}

	private static int indexOfSlot(int slot) {
		for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) return i;
		return -1;
	}

	// ------------------------------------------------------------------ immovable

	/** True when this inventory slot is one of the lobby slots of a player who has the lobby items right now. */
	public static boolean locked(ServerPlayer p, int inventorySlot) {
		return isActive(p.getUUID()) && isReserved(inventorySlot);
	}

	/** Called by the container mixin before every click: true cancels the click. */
	public static boolean blocksClick(AbstractContainerMenu menu, int slotId, int button, ContainerInput input, ServerPlayer p) {
		if (!isActive(p.getUUID())) return false;
		if (slotId >= 0 && slotId < menu.slots.size()) {
			Slot slot = menu.slots.get(slotId);
			if (slot.container == p.getInventory() && isReserved(slot.getContainerSlot())) return true;
		}
		return input == ContainerInput.SWAP && button >= 0 && button <= 8 && isReserved(button);
	}

	// ------------------------------------------------------------------ using them

	private static boolean tooSoon(ServerPlayer p) {
		Integer last = LAST_USE.get(p.getUUID());
		if (last != null && tick - last < 5) return true;
		LAST_USE.put(p.getUUID(), tick);
		return false;
	}

	/** What happens when a lobby item is used (right-click). */
	static void use(ServerPlayer p, String kind) {
		switch (kind) {
			case "send" -> openPlayerPicker(p, 0);
			case "leave" -> leave(p);
			case "create" -> openCreateMenu(p);
			default -> { }
		}
	}

	/** The barrier: forgets everything pending and brings the player back to the lobby spawn. */
	static void leave(ServerPlayer p) {
		int cancelled = DuelManager.cancelInvitesOf(p.getUUID());
		if (DuelManager.isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: use /leave to get out of the duel, the workshop or spectator mode."));
			return;
		}
		boolean moved = Lobby.teleport(p.level().getServer(), p);
		p.sendSystemMessage(Msg.ok(cancelled > 0
			? "Cancelled " + cancelled + (cancelled == 1 ? " duel request." : " duel requests.")
			: (moved ? "Back at the lobby spawn." : "Nothing to leave.")));
	}

	static void challenge(ServerPlayer p, ServerPlayer target) {
		DuelCommands.challenge(p, target);
	}

	// ------------------------------------------------------------------ menus

	/** Sword menu: every other player who is online and free, as their head. */
	static void openPlayerPicker(ServerPlayer me, int page) {
		MinecraftServer server = me.level().getServer();
		List<ServerPlayer> others = new ArrayList<>();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(me.getUUID()) || BotDuel.isBot(other.getUUID())) continue;
			others.add(other);
		}
		if (others.isEmpty()) {
			me.sendSystemMessage(Msg.info("Nobody else is online. Invite a friend, or practice with the Practice Bot or /botduel."));
			return;
		}
		int pages = Math.max(1, (others.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= others.size()) break;
			ServerPlayer other = others.get(index);
			boolean busy = DuelManager.isBusy(other.getUUID());
			ItemStack head = new ItemStack(Items.PLAYER_HEAD);
			head.set(DataComponents.PROFILE, net.minecraft.world.item.component.ResolvableProfile.createResolved(other.getGameProfile()));
			head.set(DataComponents.CUSTOM_NAME, Component.literal(other.getName().getString())
				.withStyle(style -> style.withColor(busy ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN).withItalic(false)));
			head.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(busy ? "Busy right now" : "Click to challenge")
				.withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)))));
			String name = other.getName().getString();
			b.set(i, head, who -> {
				ServerPlayer target = who.level().getServer().getPlayerList().getPlayerByName(name);
				who.closeContainer();
				if (target == null) who.sendSystemMessage(Msg.err(name + " is not online any more."));
				else challenge(who, target);
			});
		}
		if (current > 0) b.set(45, DuelMenus.icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> openPlayerPicker(who, current - 1));
		b.set(49, DuelMenus.icon(Items.BARRIER, "Cancel", ChatFormatting.RED), ServerPlayer::closeContainer);
		if (current < pages - 1) b.set(53, DuelMenus.icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> openPlayerPicker(who, current + 1));
		ChoiceMenu.open(me, Component.literal("Send Duel: pick a player (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	/** The Create+ menu: the netherite chestplate (kits) and the grass block (arenas). */
	static void openCreateMenu(ServerPlayer p) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, DuelMenus.icon(Items.NETHERITE_CHESTPLATE, "Create Kit", ChatFormatting.AQUA,
			"Go to the kit workshop: a creative void",
			"where you build a kit and save it."), who -> openKitMenu(who));
		b.set(15, DuelMenus.icon(Items.GRASS_BLOCK, "Create Arena", ChatFormatting.GOLD,
			"Build your own arena.",
			"Needs the Builder rank."), who -> openArenaInfo(who));
		ChoiceMenu.open(p, Component.literal("Create+"), 3, b);
	}

	/** New kit or edit one of the existing ones. */
	static void openKitMenu(ServerPlayer p) {
		if (DuelManager.isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: use /leave first."));
			return;
		}
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, DuelMenus.icon(Items.CRAFTING_TABLE, "New kit", ChatFormatting.GREEN, "Start with an empty inventory."), who -> enterWorkshop(who, null));
		if (!KitStore.list(p.getUUID()).isEmpty()) {
			b.set(15, DuelMenus.icon(Items.CHEST, "Edit one of my kits", ChatFormatting.AQUA, "Pick a kit and change it."), who ->
				DuelMenus.pickKit(who, "Edit a kit", 0, (w, name) -> enterWorkshop(w, name)));
		}
		ChoiceMenu.open(p, Component.literal("Create Kit"), 3, b);
	}

	static void enterWorkshop(ServerPlayer p, String kit) {
		p.closeContainer();
		String problem = Workshop.enter(p, kit);
		if (problem != null) p.sendSystemMessage(Msg.err(problem));
	}

	/** The arena builder is the next delivery: for now this says how it will work. */
	static void openArenaInfo(ServerPlayer p) {
		p.closeContainer();
		p.sendSystemMessage(Msg.title("Create Arena"));
		p.sendSystemMessage(Msg.info("  Custom arenas are coming soon. Only players with the Builder rank will be able to make them."));
	}

	// ------------------------------------------------------------------ events

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(LobbyKit::onTick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// Left over from a crash or a closed game: give back what the lobby items were hiding.
			ServerPlayer p = handler.player;
			if (!STASH.containsKey(p.getUUID()) && hasStashFile(p.getUUID())) strip(p);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			strip(handler.player);
			LAST_USE.remove(handler.player.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) strip(p);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STASH.clear();
			LAST_USE.clear();
		});

		// Dying in the lobby must drop the player's own things, not the lobby items.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && isActive(p.getUUID())) strip(p);
			return true;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			String kind = kindOf(sp.getItemInHand(hand));
			if (kind == null || !isActive(sp.getUUID())) return InteractionResult.PASS;
			if (kind.equals("guide")) return InteractionResult.PASS; // the game opens the book
			if (!tooSoon(sp)) use(sp, kind);
			return InteractionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			String kind = kindOf(sp.getItemInHand(hand));
			if (kind == null || !isActive(sp.getUUID())) return InteractionResult.PASS;
			if (kind.equals("guide")) {
				// A written book is put on a lectern when you click one: not this one.
				return level.getBlockState(hit.getBlockPos()).is(Blocks.LECTERN) ? InteractionResult.FAIL : InteractionResult.PASS;
			}
			if (!tooSoon(sp)) use(sp, kind);
			return InteractionResult.SUCCESS;
		});

		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			String kind = kindOf(sp.getItemInHand(hand));
			if (kind == null || !isActive(sp.getUUID())) return InteractionResult.PASS;
			if (kind.equals("guide")) return InteractionResult.PASS;
			if (kind.equals("send") && entity instanceof ServerPlayer target) {
				if (!tooSoon(sp)) challenge(sp, target);
				return InteractionResult.SUCCESS;
			}
			if (Npcs.kindOf(entity) != null) return InteractionResult.PASS; // the NPC handles the click
			if (!tooSoon(sp)) use(sp, kind);
			return InteractionResult.SUCCESS;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
			String kind = kindOf(sp.getItemInHand(hand));
			if (kind == null || !isActive(sp.getUUID())) return InteractionResult.PASS;
			if (Npcs.kindOf(entity) != null) return InteractionResult.PASS; // the NPC handles the click
			if (kind.equals("send") && entity instanceof ServerPlayer target) {
				if (!tooSoon(sp)) challenge(sp, target);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.FAIL;
		});
	}

	private static void onTick(MinecraftServer server) {
		tick++;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID id = p.getUUID();
			boolean should = eligible(p);
			boolean active = STASH.containsKey(id);
			if (should && !active) {
				give(p);
			} else if (!should && active) {
				strip(p);
			} else if (active && (tick + p.getId()) % 5 == 0) {
				repair(p);
			}
		}
	}
}
