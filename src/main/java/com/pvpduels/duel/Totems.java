package com.pvpduels.duel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Totems of undying inside a duel. Fabric asks "may this player die?" before the game looks for a totem, and a duelist's
 * answer is always "no, end the round instead": so without this, a totem was never used. Here the totem gets its chance
 * first, exactly like in vanilla (hands in order, not against void/kill damage, same effects and the same statistics).
 */
final class Totems {
	private Totems() {}

	/** Pops a totem from either hand when one is there. True when the player was saved (they are left at 1 health). */
	static boolean tryPop(ServerPlayer p, DamageSource source) {
		if (source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = p.getItemInHand(hand);
			if (!stack.is(Items.TOTEM_OF_UNDYING)) continue;
			stack.shrink(1);
			p.awardStat(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
			p.setHealth(1.0f);
			p.removeAllEffects();
			p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
			p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
			p.level().broadcastEntityEvent(p, (byte) 35);
			return true;
		}
		return false;
	}
}
