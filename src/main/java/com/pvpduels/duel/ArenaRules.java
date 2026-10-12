package com.pvpduels.duel;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.EntityBlock;

/**
 * Building rules inside duels (flat and practice arenas): only things that are entities or block entities (shulker boxes, chests, ender chests,
 * end crystals, boats, minecarts, armor stands...) can be placed. Normal blocks, fluids and fire are blocked,
 * and the only blocks that can be broken are the block entities you placed.
 */
final class ArenaRules {
	private ArenaRules() {}

	private enum Mode { OFF, FLAT, NO_BUILDING }

	/**
	 * Duels in the flat void arena have the entity-only rules. In the circular and unlimited arenas you can build
	 * freely, unless the challenger turned building off for the duel.
	 */
	private static Mode mode(net.minecraft.world.entity.player.Player player) {
		if (!(player instanceof ServerPlayer sp)) return Mode.OFF;
		DuelConfig config = DuelManager.configOf(sp.getUUID());
		if (config == null) return Mode.OFF;
		if (config.arena() == ArenaKind.FLAT || config.arena() == ArenaKind.BOT) return Mode.FLAT;
		return config.rules().building() ? Mode.OFF : Mode.NO_BUILDING;
	}

	static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			Mode mode = mode(player);
			if (mode == Mode.OFF) return InteractionResult.PASS;
			Item item = player.getItemInHand(hand).getItem();
			if (item instanceof BlockItem blockItem && (mode == Mode.NO_BUILDING || !(blockItem.getBlock() instanceof EntityBlock))) {
				return InteractionResult.FAIL;
			}
			if (item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (mode(player) == Mode.OFF) return InteractionResult.PASS;
			if (player.getItemInHand(hand).getItem() instanceof BucketItem) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			Mode mode = mode(player);
			if (mode == Mode.OFF) return true;
			return mode == Mode.FLAT && state.getBlock() instanceof EntityBlock;
		});
	}
}
