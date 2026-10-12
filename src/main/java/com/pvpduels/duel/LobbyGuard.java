package com.pvpduels.duel;

import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The protection of the lobby (see /lobby protect): inside the protected area nobody can break blocks, place blocks,
 * pour water or lava, use tools that change blocks, or hit anything, and players cannot hurt each other.
 * Admins in creative mode are not stopped, so they can still decorate.
 */
final class LobbyGuard {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	private static final Map<UUID, Long> LAST_TOLD = new HashMap<>();

	private LobbyGuard() {}

	/** An admin in creative mode may build in the protected area. */
	static boolean bypass(Player player) {
		return player instanceof ServerPlayer sp && sp.isCreative() && IS_ADMIN.test(sp.createCommandSourceStack());
	}

	private static boolean blocked(Player player, Level level, BlockPos pos) {
		if (level.isClientSide()) return false;
		return Lobby.protects(level, pos.getX() + 0.5, pos.getZ() + 0.5) && !bypass(player);
	}

	private static void tell(Player player) {
		if (!(player instanceof ServerPlayer sp)) return;
		long now = sp.level().getGameTime();
		Long last = LAST_TOLD.get(sp.getUUID());
		if (last != null && now - last < 40) return;
		LAST_TOLD.put(sp.getUUID(), now);
		sp.sendOverlayMessage(Msg.err("The lobby is protected."));
	}

	/** Items that change the world when used on a block. */
	private static boolean changesBlocks(Item item) {
		return item instanceof BlockItem || item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem
			|| item instanceof HoeItem || item instanceof ShovelItem || item instanceof AxeItem || item instanceof BoneMealItem
			|| item instanceof ShearsItem || item instanceof SpawnEggItem;
	}

	static void register() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!blocked(player, level, pos)) return true;
			tell(player);
			return false;
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!blocked(player, level, pos)) return InteractionResult.PASS;
			tell(player);
			return InteractionResult.FAIL;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide()) return InteractionResult.PASS;
			Item item = player.getItemInHand(hand).getItem();
			if (!changesBlocks(item)) return InteractionResult.PASS;
			if (!blocked(player, level, hit.getBlockPos()) && !blocked(player, level, hit.getBlockPos().relative(hit.getDirection()))) return InteractionResult.PASS;
			tell(player);
			return InteractionResult.FAIL;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player.getItemInHand(hand).getItem() instanceof BucketItem)) return InteractionResult.PASS;
			if (!blocked(player, level, player.blockPosition())) return InteractionResult.PASS;
			tell(player);
			return InteractionResult.FAIL;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || bypass(player)) return InteractionResult.PASS;
			if (!Lobby.protects(level, player.getX(), player.getZ()) && !Lobby.protects(level, entity.getX(), entity.getZ())) return InteractionResult.PASS;
			tell(player);
			return InteractionResult.FAIL;
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register(LobbyGuard::allowDamage);
	}

	/** Players cannot hurt each other (with swords, arrows, anything a player does) inside the protected area. */
	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim)) return true;
		if (!(source.getEntity() instanceof Player attacker) || attacker == victim) return true;
		if (bypass(attacker)) return true;
		return !Lobby.protects(victim.level(), victim.getX(), victim.getZ());
	}
}
