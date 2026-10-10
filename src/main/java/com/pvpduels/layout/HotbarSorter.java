package com.pvpduels.layout;

import com.pvpduels.util.Msg;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Fills each hotbar slot (1-9) with the first item that matches its priority list,
 * searching the whole inventory (hotbar first, then the backpack). Items are swapped, never lost.
 */
public final class HotbarSorter {
	private HotbarSorter() {}

	/** Returns how many items were moved. */
	public static int sort(ServerPlayer player, LayoutStore.Profile profile) {
		Inventory inv = player.getInventory();
		boolean[] locked = new boolean[36];
		int moves = 0;

		for (int slot = 0; slot < 9; slot++) {
			List<String> rules = profile.slots.get(slot + 1);
			if (rules == null || rules.isEmpty()) continue;

			int found = -1;
			search:
			for (String rule : rules) {
				if (ItemMatcher.matches(rule, inv.getItem(slot))) {
					found = slot;
					break;
				}
				for (int i = 0; i < 36; i++) {
					if (i == slot || locked[i]) continue;
					if (ItemMatcher.matches(rule, inv.getItem(i))) {
						found = i;
						break search;
					}
				}
			}

			if (found >= 0) {
				locked[slot] = true;
				if (found != slot) {
					ItemStack a = inv.getItem(slot);
					ItemStack b = inv.getItem(found);
					inv.setItem(slot, b);
					inv.setItem(found, a);
					moves++;
				}
			}
		}
		return moves;
	}

	/** Sorts using the player's active profile and tells them what happened. */
	public static void sortAndReport(ServerPlayer player) {
		LayoutStore.Data data = LayoutStore.get(player.getUUID());
		LayoutStore.Profile profile = data.profiles.get(data.active);
		boolean hasRules = profile.slots.values().stream().anyMatch(l -> !l.isEmpty());
		if (!hasRules) {
			player.sendSystemMessage(Msg.err("Profile '" + data.active + "' has no rules yet. Try: /layout slot 1 sword spear"));
			return;
		}
		int moved = sort(player, profile);
		player.sendSystemMessage(Msg.ok("Hotbar sorted with profile '" + data.active + "' (" + moved + " moved)."));
	}
}
