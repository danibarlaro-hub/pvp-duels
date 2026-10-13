package com.pvpduels.effects;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.concurrent.ThreadLocalRandom;

/**
 * How many particles each player gets from the heavy effects (the finale, the explosions, the win particles). It is a choice of the player
 * who looks, so a slow computer can use Potato while a fast one uses Ultra Pro Max. Only the particles sent to that player change.
 */
public enum Quality {
	POTATO("Potato", 0.10, Items.POTATO, "A tenth of the particles. For very slow computers."),
	LOW("Low", 0.35, Items.COPPER_INGOT, "About a third of the particles."),
	MID("Mid", 0.65, Items.IRON_INGOT, "Two thirds of the particles."),
	HIGH("High", 1.0, Items.GOLD_INGOT, "All the particles. The normal look."),
	ULTRA("Ultra Pro Max", 2.0, Items.NETHER_STAR, "Twice the particles. For a very good graphics card.");

	public final String label;
	/** The share of the particles this player receives (2.0 = double). */
	public final double factor;
	public final Item icon;
	public final String description;

	Quality(String label, double factor, Item icon, String description) {
		this.label = label;
		this.factor = factor;
		this.icon = icon;
		this.description = description;
	}

	public Quality next() {
		return values()[(ordinal() + 1) % values().length];
	}

	/** The level with this number, or High when the number makes no sense. */
	public static Quality of(int index) {
		Quality[] all = values();
		return index < 0 || index >= all.length ? HIGH : all[index];
	}

	/** How many particles to send for {@code count} when the viewer wants this level (a fraction becomes a chance). */
	public int scale(int count) {
		double wanted = count * factor;
		int whole = (int) Math.floor(wanted);
		if (ThreadLocalRandom.current().nextDouble() < wanted - whole) whole++;
		return whole;
	}

	/** Sends the particles to one player, thinned out or doubled by that player's quality. */
	public static void send(ServerLevel level, ServerPlayer viewer, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		int n = Effects.quality(viewer.getUUID()).scale(count);
		if (n <= 0) return;
		level.sendParticles(viewer, options, false, true, x, y, z, n, dx, dy, dz, speed);
	}
}
