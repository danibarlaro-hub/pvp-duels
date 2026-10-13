package com.pvpduels.duel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** The items the duel summary counts. The game already counts every use per player (Stats.ITEM_USED): we only take the difference. */
final class RecapItems {
	private static final Item[][] GROUPS = {
		{Items.TOTEM_OF_UNDYING},
		{Items.ENDER_PEARL},
		{Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE},
		{Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION}
	};
	private static final String[] NAMES = {"totem", "ender pearl", "golden apple", "potion"};
	static final int COUNT = GROUPS.length;

	/** Every item that can be eaten (the daily missions count foods). Found once, the first time it is needed. */
	private static List<Item> foods;

	private RecapItems() {}

	/** How many foods (anything that can be eaten) this player has eaten so far in their whole game. */
	static int foods(ServerPlayer p) {
		if (foods == null) {
			List<Item> found = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item.components().has(DataComponents.FOOD)) found.add(item);
			}
			foods = found;
		}
		int total = 0;
		for (Item item : foods) total += p.getStats().getValue(Stats.ITEM_USED, item);
		return total;
	}

	/** How many of each group this player has used so far in their whole game. */
	static int[] snapshot(ServerPlayer p) {
		int[] out = new int[COUNT];
		for (int g = 0; g < COUNT; g++) {
			for (Item item : GROUPS[g]) out[g] += p.getStats().getValue(Stats.ITEM_USED, item);
		}
		return out;
	}

	/** "2 totems, 1 ender pearl", or an empty text when nothing was used. */
	static String describe(int[] before, int[] after) {
		StringBuilder text = new StringBuilder();
		for (int g = 0; g < COUNT; g++) {
			int used = after[g] - before[g];
			if (used <= 0) continue;
			if (!text.isEmpty()) text.append(", ");
			text.append(used).append(' ').append(NAMES[g]).append(used == 1 ? "" : "s");
		}
		return text.toString();
	}
}
