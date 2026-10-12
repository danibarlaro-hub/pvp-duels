package com.pvpduels.layout;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the best armor you carry on you. Used by the Sort key (and /layout sort).
 *
 * <ul>
 *   <li>Only real armor counts: a piece with armor points or toughness. Elytra, pumpkins and heads are never touched.</li>
 *   <li>Best = most armor points, then toughness and knockback resistance, protection enchantments, and a bit for
 *       how much durability is left. A worn piece is only replaced by a clearly better one.</li>
 *   <li>A piece with Curse of Binding is never put on (you could not take it off), and a worn piece with it stays.</li>
 * </ul>
 */
public final class ArmorPicker {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private ArmorPicker() {}

	/** equipped = names of what was put on; notes = what was left out or kept on, and why. */
	public record Result(List<String> equipped, List<String> notes) {
		public boolean changedSomething() {
			return !equipped.isEmpty();
		}
	}

	private static boolean isArmorSlot(EquipmentSlot slot) {
		return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
	}

	/** The armor slot this stack goes into, or null when it is not armor (no armor value, elytra, pumpkin, heads...). */
	public static EquipmentSlot armorSlot(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable == null) return null;
		EquipmentSlot slot = equippable.slot();
		if (!isArmorSlot(slot)) return null;
		return points(stack, slot) + toughness(stack, slot) > 0 ? slot : null;
	}

	private static double points(ItemStack stack, EquipmentSlot slot) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR, 0.0, slot);
	}

	private static double toughness(ItemStack stack, EquipmentSlot slot) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
	}

	private static double knockbackResistance(ItemStack stack, EquipmentSlot slot) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.KNOCKBACK_RESISTANCE, 0.0, slot);
	}

	/** Curse of Binding (or anything else that stops the piece from being taken off). */
	public static boolean binding(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) return true;
		for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
			if (enchantment.is(Enchantments.BINDING_CURSE)) return true;
		}
		return false;
	}

	/** How good a piece is: roughly "armor points" (one level of Protection is worth about one armor point). */
	public static double score(ItemStack stack, EquipmentSlot slot) {
		double score = points(stack, slot) + toughness(stack, slot) * 0.5 + knockbackResistance(stack, slot) * 10.0;
		ItemEnchantments enchantments = stack.getEnchantments();
		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			int level = enchantments.getLevel(enchantment);
			switch (enchantment.getRegisteredName()) {
				case "minecraft:protection" -> score += level;
				case "minecraft:blast_protection", "minecraft:fire_protection", "minecraft:projectile_protection" -> score += level * 0.75;
				case "minecraft:thorns", "minecraft:unbreaking" -> score += level * 0.1;
				case "minecraft:mending" -> score += 0.2;
				default -> { }
			}
		}
		if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
			score += 0.5 * (1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
		}
		return score;
	}

	/** Swaps the best armor of the inventory (slots 0-35) onto the player. */
	public static Result equipBest(ServerPlayer player) {
		Inventory inv = player.getInventory();
		List<String> equipped = new ArrayList<>();
		List<String> notes = new ArrayList<>();

		for (EquipmentSlot slot : SLOTS) {
			ItemStack worn = player.getItemBySlot(slot);
			boolean stuck = !worn.isEmpty() && binding(worn);
			if (!worn.isEmpty() && !stuck && armorSlot(worn) == null) continue; // elytra, pumpkin... stay as they are

			double best = worn.isEmpty() ? 0.0 : score(worn, slot);
			int from = -1;
			boolean skippedBinding = false;
			String skippedName = null;
			boolean otherPiece = false;
			for (int i = 0; i < 36; i++) {
				ItemStack candidate = inv.getItem(i);
				if (armorSlot(candidate) != slot) continue;
				otherPiece = true;
				if (binding(candidate)) {
					if (!skippedBinding) skippedName = candidate.getHoverName().getString();
					skippedBinding = true;
					continue;
				}
				double value = score(candidate, slot);
				if (value > best + 1e-9) {
					best = value;
					from = i;
				}
			}

			if (stuck) {
				if (otherPiece) notes.add(worn.getHoverName().getString() + " has Curse of Binding, so it stays on.");
				continue;
			}
			if (from >= 0) {
				ItemStack pick = inv.getItem(from);
				String name = pick.getHoverName().getString();
				inv.setItem(from, worn);
				player.setItemSlot(slot, pick);
				equipped.add(name);
			}
			if (skippedBinding) notes.add("Left out " + skippedName + ": Curse of Binding.");
		}
		return new Result(equipped, notes);
	}
}
