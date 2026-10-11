package com.pvpduels.layout;

import com.pvpduels.util.Msg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Fills each slot of the layout (hotbar and backpack) with the first item that matches its
 * priority list (level 1, then 2, 3, 4), searching the whole inventory. Items are swapped, never lost.
 */
public final class InventorySorter {
	private InventorySorter() {}

	/** Returns how many swaps were made. */
	public static int sort(ServerPlayer player, LayoutStore.Profile profile) {
		Inventory inv = player.getInventory();
		boolean[] locked = new boolean[36];
		int moves = 0;

		for (int slot = 0; slot < 36; slot++) {
			List<String> rules = profile.slots.get(slot + 1);
			if (rules == null || rules.isEmpty()) continue;

			int found = findMatch(inv, rules, slot, locked);
			if (found >= 0) {
				locked[slot] = true;
				if (found != slot) {
					swap(inv, slot, found);
					moves++;
				}
			}
		}

		return moves;
	}

	/** Index of the best item for a slot (the slot itself if it already holds the best one), or -1. */
	private static int findMatch(Inventory inv, List<String> rules, int target, boolean[] locked) {
		for (String rule : rules) {
			if (rule.isEmpty()) continue; // empty priority level
			if (ItemMatcher.matches(rule, inv.getItem(target))) {
				return target;
			}
			for (int i = 0; i < 36; i++) {
				if (i == target || locked[i]) continue;
				if (ItemMatcher.matches(rule, inv.getItem(i))) {
					return i;
				}
			}
		}
		return -1;
	}

	private static void swap(Inventory inv, int a, int b) {
		ItemStack first = inv.getItem(a);
		ItemStack second = inv.getItem(b);
		inv.setItem(a, second);
		inv.setItem(b, first);
	}

	/** Sorts using the player's active layout and tells them what happened. */
	public static void sortAndReport(ServerPlayer player) {
		LayoutStore.Data data = LayoutStore.get(player.getUUID());
		LayoutStore.Profile profile = data.profiles.get(data.active);
		boolean hasRules = LayoutStore.hasRules(profile);
		if (!hasRules) {
			player.sendSystemMessage(Msg.err("Layout '" + data.active + "' has no rules yet. Try: /layout slot 1 sword spear"));
			return;
		}
		int moved = sort(player, profile);
		player.sendSystemMessage(Msg.ok("Inventory sorted with layout '" + data.active + "' (" + moved + " moved)."));
	}
}
