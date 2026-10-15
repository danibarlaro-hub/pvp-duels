package com.pvpduels.duel;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A round mutator: one twist that both duelists get in every round (the same for both, so it is always fair).
 * The challenger picks it in the rules menu of /duel. RANDOM draws a different one at the start of every round.
 */
public enum Mutator {
	NONE("None", "A normal round."),
	LOW_GRAVITY("Low gravity", "Everybody floats down slowly and jumps higher."),
	SPEED("Speed", "Everybody runs faster (Speed II)."),
	HEAVY("Heavy hitters", "Everybody hits harder (Strength I)."),
	HEARTS("Double hearts", "Everybody has 40 health instead of 20."),
	SPOTLIGHT("Spotlight", "Everybody glows, so you can always see the rival."),
	RANDOM("Random every round", "A different mutator at the start of every round.");

	private static final int EFFECT_TICKS = 72000;

	private final String label;
	private final String about;

	Mutator(String label, String about) {
		this.label = label;
		this.about = about;
	}

	public String label() {
		return label;
	}

	public String about() {
		return about;
	}

	/** The next one in the menu (it goes back to the first after the last). */
	public Mutator next() {
		Mutator[] all = values();
		return all[(ordinal() + 1) % all.length];
	}

	/** The mutators that really change something: RANDOM picks from these. */
	public static Mutator[] real() {
		return new Mutator[] {LOW_GRAVITY, SPEED, HEAVY, HEARTS, SPOTLIGHT};
	}

	/** What a round with this setting plays: itself, or for RANDOM a real one that is not the one of the round before. */
	public Mutator forRound(Mutator before) {
		if (this != RANDOM) return this;
		Mutator[] pool = real();
		Mutator pick;
		do {
			pick = pool[ThreadLocalRandom.current().nextInt(pool.length)];
		} while (pick == before);
		return pick;
	}

	/** Gives the effects to a duelist. heal: a new round, so the extra hearts also start full. */
	public void apply(ServerPlayer p, boolean heal) {
		switch (this) {
			case LOW_GRAVITY -> {
				add(p, MobEffects.SLOW_FALLING, 0);
				add(p, MobEffects.JUMP_BOOST, 1);
			}
			case SPEED -> add(p, MobEffects.SPEED, 1);
			case HEAVY -> add(p, MobEffects.STRENGTH, 0);
			case HEARTS -> {
				add(p, MobEffects.HEALTH_BOOST, 4);
				if (heal) p.setHealth(p.getMaxHealth());
			}
			case SPOTLIGHT -> add(p, MobEffects.GLOWING, 0);
			default -> {
			}
		}
	}

	private static void add(ServerPlayer p, Holder<MobEffect> effect, int amplifier) {
		p.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, amplifier, true, true));
	}

	/** The line told at the start of a round. */
	public String announce() {
		return "Round mutator: " + label + " - " + about;
	}
}
