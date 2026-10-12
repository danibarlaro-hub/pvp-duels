package com.pvpduels.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Small helpers for coloured chat messages. All player-facing text is in English. */
public final class Msg {
	private Msg() {}

	public static Component ok(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	public static Component err(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}

	public static Component info(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static Component title(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	/** A clickable chat button that runs a command, like [ACCEPT]. */
	public static Component button(String label, ChatFormatting color, String command, String hover) {
		return Component.literal(label).withStyle(style -> style.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}
}
