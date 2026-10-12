package com.pvpduels.duel;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** /kit show: a kit laid out like an inventory (armor and offhand on top, then the backpack, then the hotbar). Look only. */
public final class KitView {
	private KitView() {}

	/** Where each of the 41 kit slots sits in the 6-row chest, or -1 for none. */
	static int position(int kitSlot) {
		if (kitSlot < 9) return 45 + kitSlot; // hotbar: bottom row
		if (kitSlot < 36) return kitSlot; // backpack: the three rows in the middle
		return switch (kitSlot) {
			case 39 -> 0; // helmet
			case 38 -> 1; // chestplate
			case 37 -> 2; // leggings
			case 36 -> 3; // boots
			case 40 -> 8; // offhand
			default -> -1;
		};
	}

	public static void open(ServerPlayer player, String kitName, ItemStack[] slots) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < slots.length && i < 41; i++) {
			int at = position(i);
			if (at >= 0 && !slots[i].isEmpty()) b.set(at, slots[i].copy());
		}
		ChoiceMenu.open(player, Component.literal("Kit " + kitName), 6, b);
	}

	/** The chest position of a kit slot (used by the self-test). */
	public static int where(int kitSlot) {
		return position(kitSlot);
	}
}
