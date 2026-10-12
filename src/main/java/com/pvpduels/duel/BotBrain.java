package com.pvpduels.duel;

import com.pvpduels.duel.TestDummy.Dummy;
import com.pvpduels.layout.ItemMatcher;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * What a movable /botduel bot does each tick: it looks at the player, and (Easy, Normal, Hard) walks up to them and
 * attacks. A fake player has no game client to move it, so when the server did not move it by itself this class runs
 * the walking / knockback / gravity physics too.
 */
final class BotBrain {
	private final Dummy bot;
	private final BotLevel level;
	private final UUID targetId;
	private int cooldown;
	private int strafeTicks;
	private float strafeSide = 1f;

	BotBrain(Dummy bot, BotLevel level, UUID targetId) {
		this.bot = bot;
		this.level = level;
		this.targetId = targetId;
	}

	private ServerPlayer target() {
		return bot.level().getServer().getPlayerList().getPlayer(targetId);
	}

	private boolean fighting() {
		Duel duel = DuelManager.duelOf(bot.getUUID());
		return duel != null && duel.phase == Duel.Phase.FIGHTING;
	}

	/** Before the normal tick: where to look and which way to walk. */
	void prepare() {
		bot.xxa = 0f;
		bot.zza = 0f;
		ServerPlayer target = target();
		if (target == null || !target.level().dimension().equals(bot.level().dimension())) {
			bot.setSprinting(false);
			return;
		}
		bot.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		bot.setYHeadRot(bot.getYRot());
		if (!level.fights || !fighting()) {
			bot.setSprinting(false);
			return;
		}
		selectWeapon();
		double dist = bot.distanceTo(target);
		if (dist > level.reach - 0.6) {
			bot.zza = 1f;
			bot.setSprinting(level.sprint && dist > 4.5);
		} else {
			bot.setSprinting(false);
		}
		if (level.strafe && dist < 6.0) {
			if (--strafeTicks <= 0) {
				strafeTicks = 15 + bot.getRandom().nextInt(25);
				strafeSide = bot.getRandom().nextBoolean() ? 1f : -1f;
			}
			bot.xxa = strafeSide;
		}
	}

	/** After the normal tick: movement the server did not do, then the attack. */
	void afterTick(Vec3 before) {
		Vec3 v = bot.getDeltaMovement();
		boolean input = bot.xxa != 0f || bot.zza != 0f;
		boolean moving = input || v.x * v.x + v.z * v.z > 1.0E-5 || v.y > 0.0 || !bot.onGround();
		if (moving && bot.position().distanceToSqr(before) < 1.0E-6) {
			bot.travel(new Vec3(bot.xxa, 0.0, bot.zza));
		}

		if (!level.fights || !fighting()) return;
		ServerPlayer target = target();
		if (target == null) return;
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (bot.distanceTo(target) > level.reach) return;
		if (bot.getAttackStrengthScale(0.5f) < 1.0f) return;
		bot.swing(InteractionHand.MAIN_HAND);
		if (bot.getRandom().nextFloat() < level.accuracy) bot.attack(target);
		else bot.resetAttackStrengthTicker();
		cooldown = level.extraDelay;
	}

	/** Holds the best melee weapon of the hotbar: a sword, else an axe, else a spear, else whatever is in slot 1. */
	private void selectWeapon() {
		Inventory inv = bot.getInventory();
		int best = 0;
		for (String kind : new String[] {"sword", "axe", "spear"}) {
			boolean found = false;
			for (int i = 0; i < 9; i++) {
				if (ItemMatcher.matches(kind, inv.getItem(i))) {
					best = i;
					found = true;
					break;
				}
			}
			if (found) break;
		}
		if (inv.getSelectedSlot() != best) inv.setSelectedSlot(best);
	}
}
