package com.pvpduels.duel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Accuracy of the swings of a duelist: every swing of a melee weapon (or an empty hand) is counted by the swing packet, and every melee hit
 * that does damage. The difference is what missed (or hit while the rival could not be hurt). Critical hits are counted with the same rules
 * as the game: a falling player, not sprinting, with the weapon charged. Arrows, pearls and explosions are not melee and are not counted here.
 */
final class Accuracy {
	private Accuracy() {}

	/** True when a swing with this in the hand is an attack (a sword, an axe, a spear, a mace, a trident or an empty hand). Placing blocks and throwing things are not. */
	static boolean isMeleeHand(ItemStack stack) {
		if (stack.isEmpty()) return true;
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear") || path.equals("mace") || path.equals("trident");
	}

	/** The rules of a critical hit of the game (a mace smash is its own thing and is not a critical hit). */
	static boolean isCrit(ServerPlayer p) {
		return p.getAttackStrengthScale(0.5f) > 0.9f && p.fallDistance > 0.0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
			&& !p.hasEffect(MobEffects.BLINDNESS) && !p.isPassenger() && !p.isSprinting() && !p.getMainHandItem().is(Items.MACE);
	}

	/** "accuracy 75% (3/4 swings), 1 crit", or "" when the player never swung and landed nothing. */
	static String describe(int swings, int melee, int crits) {
		StringBuilder sb = new StringBuilder();
		if (swings > 0) {
			int landed = Math.min(melee, swings);
			sb.append("accuracy ").append(Math.round(100f * landed / swings)).append("% (").append(landed).append('/').append(swings).append(swings == 1 ? " swing)" : " swings)");
		}
		if (crits > 0) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(crits).append(crits == 1 ? " crit" : " crits");
		}
		return sb.toString();
	}
}
