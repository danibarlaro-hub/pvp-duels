package com.pvpduels.workshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.Arena;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.duel.PlayerBackup;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The kit workshop: /kit create sends you to an empty void with a stone platform in creative mode, where you can
 * grab items, use /enchant and /infinite, and /kit save the result. You can't break or place anything there.
 * Your own inventory is saved to disk first and comes back with /leave (or /kit exit).
 */
public final class Workshop {
	private static final int BASE_X = 8192;
	private static final int SPACING = 256;
	private static final int FLOOR_Y = Arena.FLOOR_Y;
	private static final int RADIUS = 7;
	private static final int MAX_DISTANCE = 40;

	private static final Map<UUID, Integer> SESSIONS = new HashMap<>();

	private Workshop() {}

	public static boolean isIn(UUID id) {
		return SESSIONS.containsKey(id);
	}

	private static int centerX(int slot) {
		return BASE_X + slot * SPACING;
	}

	// ------------------------------------------------------------------ enter / exit

	/** Returns an error text, or null when the player is now in the workshop. */
	public static String enter(ServerPlayer p, String kitName) {
		MinecraftServer server = p.level().getServer();
		if (isIn(p.getUUID())) return "You are already in the kit workshop. Use /leave to go back.";
		if (DuelManager.isInDuel(p.getUUID())) return "You can't open the workshop during a duel.";
		if (com.pvpduels.duel.Spectators.isIn(p.getUUID())) return "You are watching a duel: use /leave first.";
		ServerLevel level = Arena.level(server);
		if (level == null) return "The duel world is not loaded. Make sure the mod is up to date, then restart the game.";

		KitStore.Kit kit = null;
		if (kitName != null) {
			try {
				Optional<KitStore.Kit> read = KitStore.read(server, p.getUUID(), kitName);
				if (read.isEmpty()) return "You have no kit named '" + kitName + "'. Use /kit list.";
				if (read.get().locked()) return "Kit '" + kitName + "' was given to you and is locked: you can use it but not edit it.";
				kit = read.get();
			} catch (IOException | RuntimeException e) {
				return "Could not read the kit: " + e.getMessage();
			}
		}

		// A backup still on disk means the player was never given their stuff back: do that first.
		if (PlayerBackup.exists(p.getUUID())) PlayerBackup.restore(server, p);
		try {
			PlayerBackup.save(server, p);
		} catch (IOException e) {
			return "Could not save your inventory, so the workshop was not opened: " + e.getMessage();
		}

		int slot = 0;
		while (SESSIONS.containsValue(slot)) slot++;
		SESSIONS.put(p.getUUID(), slot);
		buildPlatform(level, slot);

		p.closeContainer();
		p.removeAllEffects();
		p.getInventory().clearContent();
		if (kit != null) KitStore.give(p, kit);
		p.setGameMode(GameType.CREATIVE);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.teleportTo(level, centerX(slot) + 0.5, FLOOR_Y + 1, 0.5, Set.of(), 0f, 0f, true);
		p.setDeltaMovement(0, 0, 0);
		p.resetFallDistance();

		p.sendSystemMessage(Msg.title("Kit workshop"));
		p.sendSystemMessage(Msg.info(kit == null ? "  Your inventory is empty. Take items from the creative menu."
			: "  Editing the kit '" + kitName + "' (" + (kit.stacks() == 0 ? "it is EMPTY" : kit.stacks() + " item stacks") + ")."));
		p.sendSystemMessage(Msg.info("  /enchant <enchantment> [level 1-255] and /infinite <amount> change the item in your hand."));
		p.sendSystemMessage(Msg.info("  /kit save <name> saves your inventory as a kit. /leave brings your own inventory back. ")
			.copy().append(Msg.button("[LEAVE]", net.minecraft.ChatFormatting.RED, "/leave", "Leave the workshop and get your inventory back")));
		return null;
	}

	public static void exit(ServerPlayer p) {
		Integer slot = SESSIONS.remove(p.getUUID());
		if (slot == null) return;
		MinecraftServer server = p.level().getServer();
		ServerLevel level = Arena.level(server);
		if (level != null) Arena.clearEntitiesAround(level, centerX(slot));
		PlayerBackup.restore(server, p);
		p.sendSystemMessage(Msg.ok("You left the workshop. Your inventory was restored."));
	}

	private static void buildPlatform(ServerLevel level, int slot) {
		int cx = centerX(slot);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				pos.set(cx + dx, FLOOR_Y, dz);
				if (!level.getBlockState(pos).is(Blocks.STONE)) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2 | 16);
			}
		}
		Arena.clearEntitiesAround(level, cx);
	}

	// ------------------------------------------------------------------ events

	public static void register() {
		// Nothing can be placed, broken or used in the workshop.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) -> inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> inside(player) ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !inside(player));

		ServerTickEvents.END_SERVER_TICK.register(Workshop::onTick);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Their inventory backup stays on disk: it is given back the next time they join.
			Integer slot = SESSIONS.remove(handler.player.getUUID());
			if (slot != null) {
				ServerLevel level = Arena.level(server);
				if (level != null) Arena.clearEntitiesAround(level, centerX(slot));
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID id : Set.copyOf(SESSIONS.keySet())) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) exit(p);
			}
		});
	}

	private static boolean inside(Player player) {
		return player instanceof ServerPlayer sp && SESSIONS.containsKey(sp.getUUID());
	}

	private static void onTick(MinecraftServer server) {
		if (SESSIONS.isEmpty() || server.getTickCount() % 10 != 0) return;
		for (Map.Entry<UUID, Integer> entry : Map.copyOf(SESSIONS).entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
			if (p == null) {
				SESSIONS.remove(entry.getKey());
				continue;
			}
			if (!p.level().dimension().equals(Arena.DIMENSION)) {
				exit(p); // taken out of the workshop world some other way
				continue;
			}
			int cx = centerX(entry.getValue());
			double dx = p.getX() - cx;
			double dz = p.getZ();
			if (p.getY() < FLOOR_Y - 10 || dx * dx + dz * dz > (double) MAX_DISTANCE * MAX_DISTANCE) {
				p.teleportTo((ServerLevel) p.level(), cx + 0.5, FLOOR_Y + 1, 0.5, Set.of(), p.getYRot(), 0f, true);
				p.setDeltaMovement(0, 0, 0);
				p.resetFallDistance();
			}
		}
	}

	// ------------------------------------------------------------------ commands

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("kit")
			.then(Commands.literal("create")
				.executes(ctx -> create(ctx.getSource(), null))
				.then(Commands.argument("kit", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KitStore.list(ctx.getSource().getPlayerOrException().getUUID()), builder))
					.executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "kit")))))
			.then(Commands.literal("exit").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				if (!isIn(p.getUUID())) {
					ctx.getSource().sendFailure(Msg.err("You are not in the kit workshop."));
					return 0;
				}
				exit(p);
				return 1;
			})));
	}

	private static int create(CommandSourceStack source, String kit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		String problem = enter(p, kit);
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		PvpDuelsMod.LOGGER.info("{} opened the kit workshop", p.getName().getString());
		return 1;
	}
}
