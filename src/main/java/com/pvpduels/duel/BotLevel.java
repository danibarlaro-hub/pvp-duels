package com.pvpduels.duel;

import java.util.Locale;

/** How the /botduel bot behaves. STATIC is nailed to the ground; the others can be pushed by knockback. */
public enum BotLevel {
	STATIC("Static", "Stands still. It can't be pushed and never fights back.", false, 0, 0f, 0, false, false),
	PASSIVE("Passive", "Takes knockback like a player, but never fights back.", false, 0, 0f, 0, false, false),
	EASY("Easy", "Walks up to you and hits slowly. It misses a lot.", true, 2.6, 0.55f, 8, false, false),
	NORMAL("Normal", "Sprints at you and hits every time its weapon is ready, but misses sometimes.", true, 3.0, 0.8f, 3, true, false),
	HARD("Hard", "Sprints, strafes and almost never misses.", true, 3.0, 0.95f, 0, true, true);

	public final String label;
	public final String description;
	/** Whether the bot walks up to the player and attacks. */
	final boolean fights;
	/** Distance (blocks) at which it can hit. */
	final double reach;
	/** Chance that a swing really hits. */
	final float accuracy;
	/** Extra ticks it waits after its weapon is ready. */
	final int extraDelay;
	final boolean sprint;
	final boolean strafe;

	BotLevel(String label, String description, boolean fights, double reach, float accuracy, int extraDelay, boolean sprint, boolean strafe) {
		this.label = label;
		this.description = description;
		this.fights = fights;
		this.reach = reach;
		this.accuracy = accuracy;
		this.extraDelay = extraDelay;
		this.sprint = sprint;
		this.strafe = strafe;
	}

	public boolean pinned() {
		return this == STATIC;
	}

	/** The level with this name (any case), or null. */
	public static BotLevel parse(String text) {
		for (BotLevel level : values()) {
			if (level.name().equals(text.toUpperCase(Locale.ROOT))) return level;
		}
		return null;
	}
}
