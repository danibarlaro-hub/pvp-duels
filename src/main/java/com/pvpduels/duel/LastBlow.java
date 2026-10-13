package com.pvpduels.duel;

import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * The last blow a duelist took: how hard it was and what they had before it (health and golden absorption hearts). When a round ends
 * because of it, the chat says so, so a round that seems to end "too soon" can be explained at once.
 */
final class LastBlow {
	private float health;
	private float absorption;
	private float raw;
	private long tick = Long.MIN_VALUE;

	/** Remembers a blow that is about to land. In the same tick the strongest one wins (a weak one inside the invulnerability frames is ignored). */
	void note(ServerPlayer victim, float rawDamage) {
		long now = victim.level().getGameTime();
		if (now == tick && rawDamage < raw) return;
		tick = now;
		raw = rawDamage;
		health = victim.getHealth();
		absorption = victim.getAbsorptionAmount();
	}

	/** The sentence for the chat, or an empty text when there was no blow just now (the round ended for another reason). */
	String describe(ServerPlayer victim) {
		if (victim == null || victim.level().getGameTime() - tick > 2) return "";
		return String.format(Locale.ROOT, " Last blow: %.1f damage before armor, against %.1f health + %.1f absorption.", raw, health, absorption);
	}
}
