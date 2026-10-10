package com.pvpduels.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

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
}
