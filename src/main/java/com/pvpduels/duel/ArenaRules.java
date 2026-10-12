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
 * Building rules inside duels: only things that are entities or block entities (shulker boxes, chests, ender chests,
 * end crystals, boats, minecarts, armor stands...) can be placed. Normal blocks, fluids and fire are blocked,
 * and the only blocks that can be broken are the block entities you placed.
 */
final class ArenaRules {
	private ArenaRules() {}

	/** Only duels in the flat void arena have building rules; the normal arenas can be built and dug freely. */
	private static boolean duelist(net.minecraft.world.entity.player.Player player) {
		if (!(player instanceof ServerPlayer sp)) return false;
		Duel duel = DuelManager.duelOf(sp.getUUID());
		return duel != null && duel.config.arena() == ArenaKind.FLAT;
	}

	static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!duelist(player)) return InteractionResult.PASS;
			Item item = player.getItemInHand(hand).getItem();
			if (item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof EntityBlock)) {
				return InteractionResult.FAIL;
			}
			if (item instanceof BucketItem || item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!duelist(player)) return InteractionResult.PASS;
			if (player.getItemInHand(hand).getItem() instanceof BucketItem) return InteractionResult.FAIL;
			return InteractionResult.PASS;
		});

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!duelist(player)) return true;
			return state.getBlock() instanceof EntityBlock;
		});
	}
}
