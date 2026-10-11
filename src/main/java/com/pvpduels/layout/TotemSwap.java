package com.pvpduels.layout;

import com.pvpduels.util.Msg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Semi-automatic totem: every press moves a totem from the inventory into the offhand when the offhand
 * does not hold one. Spam the key and the offhand is refilled right after a totem pops.
 */
public final class TotemSwap {
	private static final int OFFHAND_INDEX = 40;
	private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();

	private TotemSwap() {}

	public static void swap(ServerPlayer player) {
		Inventory inv = player.getInventory();
		if (inv.getContainerSize() <= OFFHAND_INDEX) return;

		// Already holding a totem in the offhand: nothing to do (so spamming is harmless).
		if (ItemMatcher.matches("totem", inv.getItem(OFFHAND_INDEX))) return;

		// Backpack first so your hotbar order stays untouched, then the hotbar.
		int found = -1;
		for (int i = 9; i < 36 && found < 0; i++) {
			if (ItemMatcher.matches("totem", inv.getItem(i))) found = i;
		}
		for (int i = 0; i < 9 && found < 0; i++) {
			if (ItemMatcher.matches("totem", inv.getItem(i))) found = i;
		}

		if (found < 0) {
			long now = player.level().getGameTime();
			Long last = LAST_WARNING.get(player.getUUID());
			if (last == null || now - last > 40 || now < last) {
				LAST_WARNING.put(player.getUUID(), now);
				player.sendSystemMessage(Msg.err("No totem left in your inventory."), true);
			}
			return;
		}

		ItemStack totem = inv.getItem(found);
		ItemStack offhand = inv.getItem(OFFHAND_INDEX);
		inv.setItem(OFFHAND_INDEX, totem);
		inv.setItem(found, offhand);
	}
}
