package com.pvpduels.layout;

import com.pvpduels.kit.KitStore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * /layout auto: looks at all your kits and notices which items almost always sit in the same slot. An item (or a
 * category like "sword") gets a rule for a slot when it is in that slot in more than half of the kits that have it,
 * and in at least two kits. Only slots 1-36 are looked at, like the sorter itself.
 */
public final class AutoLayout {
	/** More kits than this and only the newest ones are looked at, so the game doesn't stall. */
	public static final int MAX_KITS = 300;

	public record Result(int kits, boolean truncated, LayoutStore.Profile profile) {
		public int slotsWithRules() {
			return profile.slots.size();
		}
	}

	private AutoLayout() {}

	/** The rule keys a stack counts for: its exact item id first, then every category it belongs to. */
	private static List<String> keysOf(ItemStack stack) {
		List<String> keys = new ArrayList<>();
		keys.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		List<String> categories = new ArrayList<>(ItemMatcher.CATEGORIES);
		categories.sort(String::compareTo);
		for (String category : categories) {
			if (ItemMatcher.matches(category, stack)) keys.add(category);
		}
		return keys;
	}

	public static Result analyze(MinecraftServer server, UUID owner) throws IOException {
		List<String> names = KitStore.recent(owner, MAX_KITS);
		boolean truncated = KitStore.list(owner).size() > names.size();

		Map<Integer, Map<String, Integer>> tally = new HashMap<>();
		Map<String, Integer> total = new HashMap<>();
		int kits = 0;
		for (String name : names) {
			Optional<KitStore.Kit> kit = KitStore.read(server, owner, name);
			if (kit.isEmpty()) continue;
			kits++;
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < LayoutStore.MAX_SLOT; i++) {
				ItemStack stack = kit.get().slots()[i];
				if (stack.isEmpty()) continue;
				for (String key : keysOf(stack)) {
					tally.computeIfAbsent(i + 1, k -> new HashMap<>()).merge(key, 1, Integer::sum);
					seen.add(key);
				}
			}
			for (String key : seen) total.merge(key, 1, Integer::sum);
		}

		int minSupport = kits >= 2 ? 2 : 1;
		LayoutStore.Profile profile = new LayoutStore.Profile();
		profile.slots = new TreeMap<>();
		for (Map.Entry<Integer, Map<String, Integer>> slot : tally.entrySet()) {
			List<Map.Entry<String, Integer>> good = new ArrayList<>();
			for (Map.Entry<String, Integer> e : slot.getValue().entrySet()) {
				int count = e.getValue();
				if (count >= minSupport && count * 2 > total.get(e.getKey())) good.add(e);
			}
			if (good.isEmpty()) continue;
			// Exact items first, then categories; the most common first inside each group.
			good.sort(Comparator
				.comparing((Map.Entry<String, Integer> e) -> e.getKey().contains(":") ? 0 : 1)
				.thenComparing((Map.Entry<String, Integer> e) -> -e.getValue())
				.thenComparing(Map.Entry::getKey));
			List<String> rules = new ArrayList<>();
			for (Map.Entry<String, Integer> e : good) {
				if (rules.size() >= LayoutStore.MAX_LEVELS) break;
				rules.add(e.getKey());
			}
			profile.slots.put(slot.getKey(), rules);
		}
		return new Result(kits, truncated, profile);
	}
}
