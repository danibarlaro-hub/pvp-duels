package com.pvpduels.achievements;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;

/**
 * One achievement: it is unlocked when the counter reaches the target. The title (when there is one) can then be worn in front of
 * the name; it has the color of its category.
 */
public record Achievement(String id, String category, String name, String description, String counter, long target, String title, ChatFormatting color, Item icon) {
	public boolean hasTitle() {
		return title != null && !title.isEmpty();
	}
}
