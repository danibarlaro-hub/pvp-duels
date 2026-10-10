package com.pvpduels.layout;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * Decides whether an item matches a layout rule. A rule is either a category name
 * (like "sword") or an exact item id (like "minecraft:totem_of_undying").
 */
public final class ItemMatcher {
	public static final Set<String> CATEGORIES = Set.of(
		"sword", "spear", "axe", "pickaxe", "shovel", "hoe", "mace", "bow", "crossbow", "trident",
		"shield", "totem", "crystal", "obsidian", "gapple", "pearl", "potion", "food", "block",
		"elytra", "firework", "wind_charge", "cobweb"
	);

	private ItemMatcher() {}

	/** Returns a clean rule string, or null if the text is neither a known category nor a real item. */
	public static String normalize(String text) {
		String t = text.toLowerCase();
		if (CATEGORIES.contains(t)) return t;
		Identifier id = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			return id.toString();
		}
		return null;
	}

	public static boolean matches(String rule, ItemStack stack) {
		if (stack.isEmpty()) return false;
		Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (rule.contains(":")) {
			return key.toString().equals(rule);
		}
		String path = key.getPath();
		return switch (rule) {
			case "sword" -> path.endsWith("_sword");
			case "spear" -> path.endsWith("_spear");
			case "axe" -> path.endsWith("_axe");
			case "pickaxe" -> path.endsWith("_pickaxe");
			case "shovel" -> path.endsWith("_shovel");
			case "hoe" -> path.endsWith("_hoe");
			case "mace" -> path.equals("mace");
			case "bow" -> path.equals("bow");
			case "crossbow" -> path.equals("crossbow");
			case "trident" -> path.equals("trident");
			case "shield" -> path.equals("shield");
			case "totem" -> path.equals("totem_of_undying");
			case "crystal" -> path.equals("end_crystal");
			case "obsidian" -> path.equals("obsidian");
			case "gapple" -> path.equals("golden_apple") || path.equals("enchanted_golden_apple");
			case "pearl" -> path.equals("ender_pearl");
			case "potion" -> path.equals("potion") || path.equals("splash_potion") || path.equals("lingering_potion");
			case "elytra" -> path.equals("elytra");
			case "firework" -> path.equals("firework_rocket");
			case "wind_charge" -> path.equals("wind_charge");
			case "cobweb" -> path.equals("cobweb");
			case "food" -> stack.has(DataComponents.FOOD);
			case "block" -> stack.getItem() instanceof BlockItem;
			default -> false;
		};
	}
}
