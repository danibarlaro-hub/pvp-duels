package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Zeus Storm: a deck of black clouds gathers over the arena and the world goes dark, lightning bolts fall in a ring that closes in on the
 * target (they are only drawn: no real lightning is summoned, so nothing burns and nobody is hurt), the clouds spin and charge, and one
 * giant bolt strikes the target. Only light and sound: every solid part is a display entity.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class StormArt {
	private static final ParticleOptions BLUE_DUST = new DustParticleOptions(0x9FD8FF, 1.7f);
	private static final Q[] TURNS = {Q.ID, Q.yaw(45)};
	private static final int CLOUDS = 38;

	private StormArt() {}

	// ------------------------------------------------------------------ the numbers

	/** How long the clouds take to gather. */
	public static int gather(boolean cinema) {
		return cinema ? 36 : 18;
	}

	/** How long the ring of bolts lasts. */
	public static int storm(boolean cinema) {
		return cinema ? 80 : 40;
	}

	/** How long the clouds charge before the great bolt. */
	public static int charge(boolean cinema) {
		return cinema ? 20 : 10;
	}

	public static int after(boolean cinema) {
		return cinema ? 76 : 40;
	}

	/** The tick of the great bolt. */
	public static int strike(boolean cinema) {
		return gather(cinema) + storm(cinema) + charge(cinema);
	}

	public static int ticks(boolean cinema) {
		return strike(cinema) + after(cinema);
	}

	/** How high above the target the clouds are. */
	public static double cloudHeight(boolean cinema) {
		return cinema ? 36 : 30;
	}

	private static double ease(double x) {
		double u = Math.max(0, Math.min(1, x));
		return u * u * (3 - 2 * u);
	}

	private static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	/** The slab of cloud number {@code i}: where it is, how big it is and its block (the same every time it is asked). */
	private static Vec3[] cloud(int i, Vec3 target, boolean cinema) {
		double a = i * 2.399963229728653;
		double r = Math.sqrt((i + 0.5) / CLOUDS) * (cinema ? 42 : 30);
		double wobble = ((i * 37) % 11) / 11.0;
		Vec3 at = new Vec3(target.x + Math.cos(a) * r, target.y + cloudHeight(cinema) + wobble * 5, target.z + Math.sin(a) * r);
		Vec3 size = new Vec3(10 + ((i * 13) % 9), 3.0 + ((i * 7) % 4), 10 + ((i * 17) % 9));
		return new Vec3[] {at, size};
	}

	// ------------------------------------------------------------------ a bolt

	/**
	 * A jagged bolt from {@code top} to {@code bottom}: a core of white and a glow of pale blue, seven bends, and (when {@code fork}) a branch.
	 * It disappears {@code life} ticks after {@code t}.
	 */
	private static void bolt(Props props, Vec3 top, Vec3 bottom, int t, int life, double glow, double core, boolean fork, double wander) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int segments = 7;
		Vec3[] p = new Vec3[segments + 1];
		for (int i = 0; i <= segments; i++) {
			Vec3 base = top.add(bottom.subtract(top).scale(i / (double) segments));
			double taper = Math.sin(Math.PI * i / segments);
			p[i] = base.add((rnd.nextDouble() - 0.5) * 2 * wander * taper, 0, (rnd.nextDouble() - 0.5) * 2 * wander * taper);
		}
		for (int i = 0; i < segments; i++) {
			props.lifetime(Shapes.line(props, "minecraft:light_blue_stained_glass", p[i], p[i + 1], glow, true), t + life);
			props.lifetime(Shapes.line(props, "minecraft:white_concrete", p[i], p[i + 1], core, true), t + life);
		}
		if (fork) {
			double a = rnd.nextDouble() * Math.PI * 2;
			Vec3 from = p[3];
			Vec3 mid = from.add(Math.cos(a) * wander * 1.6, -(top.y - bottom.y) * 0.12, Math.sin(a) * wander * 1.6);
			Vec3 tip = mid.add(Math.cos(a + 0.6) * wander * 1.2, -(top.y - bottom.y) * 0.1, Math.sin(a + 0.6) * wander * 1.2);
			props.lifetime(Shapes.line(props, "minecraft:white_concrete", from, mid, core * 0.8, true), t + life);
			props.lifetime(Shapes.line(props, "minecraft:white_concrete", mid, tip, core * 0.6, true), t + life);
		}
	}

	// ------------------------------------------------------------------ Zeus Storm

	public static void zeus(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int gather = gather(cinema);
		int storm = storm(cinema);
		int charge = charge(cinema);
		int strike = strike(cinema);
		int end = ticks(cinema);
		double height = cloudHeight(cinema);
		double domeR = cinema ? 40 : 28;
		Vec3 ground = target.add(0, 0.3, 0);
		if (t >= end) return;

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			for (int i = 0; i < CLOUDS; i++) {
				Vec3[] c = cloud(i, target, cinema);
				// each slab starts as a small puff at its place and swells
				props.lifetime(props.name("cl" + i, props.block(i % 3 == 0 ? "minecraft:black_concrete" : "minecraft:gray_concrete", c[0], new Vec3(1.0, 0.6, 1.0), Q.yaw(i * 23), false)), end - 1);
			}
			for (int i = 0; i < TURNS.length; i++) {
				props.lifetime(props.name("dark" + i, props.block("minecraft:gray_stained_glass", target, cube(-2.0), TURNS[i], true)), end - 1);
			}
		}
		if (t == 1) {
			for (int i = 0; i < CLOUDS; i++) {
				Vec3[] c = cloud(i, target, cinema);
				props.reshape(props.named("cl" + i), c[0], c[1], Q.yaw(i * 23), gather);
			}
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("dark" + i), target, cube(-2.0 * domeR), TURNS[i], gather);
		}
		if (t < gather && t % 2 == 0) {
			double r = domeR * ease((t + 1) / (double) gather);
			ExplosionArt.ring(level, viewers, ParticleTypes.SQUID_INK, ground, r, Math.min(72, (int) (r * 2.5) + 8), t * 0.07, 0);
		}
		if (t > 4 && t < strike && t % 2 == 0) {
			// rain falls around the target all the time the storm lasts
			ExplosionArt.emit(level, viewers, ParticleTypes.RAIN, target.x, target.y + 9, target.z, 20, 11.0, 4.0, 11.0, 0.0);
		}
		int stormStart = gather;
		int waveGap = cinema ? 5 : 5;
		if (t >= stormStart && t < stormStart + storm && (t - stormStart) % waveGap == 0) {
			int wave = (t - stormStart) / waveGap;
			int waves = storm / waveGap;
			double progress = wave / (double) Math.max(1, waves - 1);
			double ring = 16.0 - 11.0 * progress;
			int bolts = cinema ? 2 : 1;
			for (int b = 0; b < bolts; b++) {
				double a = (wave * 2 + b) * 2.399963229728653 + rnd.nextDouble() * 0.3;
				Vec3 hit = new Vec3(target.x + Math.cos(a) * ring, target.y, target.z + Math.sin(a) * ring);
				Vec3 top = new Vec3(hit.x + (rnd.nextDouble() - 0.5) * 6, target.y + height, hit.z + (rnd.nextDouble() - 0.5) * 6);
				bolt(props, top, hit, t, 4, 0.9, 0.28, true, 2.4);
				ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y + 0.4, hit.z, 14, 1.2, 0.5, 1.2, 0.25);
				ExplosionArt.emit(level, viewers, BLUE_DUST, hit.x, hit.y + 0.3, hit.z, 6, 1.0, 0.3, 1.0, 0.1);
				ExplosionArt.sound(level, hit, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0f, 0.8f + rnd.nextFloat() * 0.5f);
				// a scorch mark stays on the ground
				props.lifetime(props.block("minecraft:black_concrete", new Vec3(hit.x, target.y + 0.04, hit.z), new Vec3(2.2, 0.05, 2.2), Q.yaw(rnd.nextDouble() * 90), true), end - 20);
				props.lifetime(props.block("minecraft:gray_concrete", new Vec3(hit.x, target.y + 0.05, hit.z), new Vec3(1.2, 0.05, 1.2), Q.yaw(rnd.nextDouble() * 90), true), end - 20);
			}
		}
		if (t >= stormStart && t < strike && t % 4 == 1) {
			// the clouds flash from inside
			Vec3[] c = cloud(rnd.nextInt(CLOUDS), target, cinema);
			Props.Prop glow = props.item("minecraft:firework_star", c[0].add(0, -1, 0), cinema ? 14.0 : 10.0);
			props.lifetime(glow, t + 2);
		}
		if (t >= strike - charge && t < strike) {
			// the clouds spin and sink toward the middle, with sparks rising to meet them
			int k = t - (strike - charge);
			if (t % 4 == 0) {
				for (int i = 0; i < CLOUDS; i++) {
					Vec3[] c = cloud(i, target, cinema);
					double pull = 0.35 * ease((k + 4) / (double) charge);
					double a = (k + 4) * 0.05;
					double rx = c[0].x - target.x;
					double rz = c[0].z - target.z;
					Vec3 at = new Vec3(target.x + (rx * Math.cos(a) - rz * Math.sin(a)) * (1 - pull), c[0].y - 3 * ease((k + 4) / (double) charge), target.z + (rx * Math.sin(a) + rz * Math.cos(a)) * (1 - pull));
					props.move(props.named("cl" + i), at);
				}
			}
			double spin = t * 0.6;
			for (int s = 0; s < 3; s++) {
				double r = 5.0 - 4.0 * ((k + s * 3) % 20) / 20.0;
				ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.x + Math.cos(spin + s * 2.1) * r, target.y + 0.6 + (k % 12) * 0.6, target.z + Math.sin(spin + s * 2.1) * r, 2, 0.1, 0.1, 0.1, 0.0);
			}
			if (k == 0) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 6.0f, 0.6f);
			if (k == charge / 2) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 6.0f, 1.2f);
		}
		if (t == strike) {
			Vec3 top = new Vec3(target.x, target.y + height - 2, target.z);
			Vec3 bottom = new Vec3(target.x, target.y, target.z);
			bolt(props, top, bottom, t, 18, 4.0, 1.0, false, 3.2);
			// the bolt has a second, wider skin so it looks huge
			props.lifetime(Shapes.line(props, "minecraft:white_stained_glass", bottom.add(0, 1, 0), top, 2.2, true), t + 14);
			for (int f = 0; f < 6; f++) {
				double a = f * Math.PI / 3 + rnd.nextDouble() * 0.4;
				Vec3 from = new Vec3(target.x, target.y + height * (0.45 + 0.1 * (f % 3)), target.z);
				Vec3 to = new Vec3(target.x + Math.cos(a) * (cinema ? 14 : 9), target.y + height * 0.12, target.z + Math.sin(a) * (cinema ? 14 : 9));
				bolt(props, from, to, t, 14, 1.2, 0.4, false, 2.0);
			}
			Props.Prop flash = props.item("minecraft:firework_star", target.add(0, 4, 0), 3.0);
			props.resize(flash, cinema ? 70.0 : 44.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.SONIC_BOOM, target.x, target.y + 2, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.ELECTRIC_SPARK, ground, 140, 2.6, 0.03);
			ExplosionArt.shockwave(level, viewers, BLUE_DUST, ground, 110, 1.8, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 90, 2.2, 0.02);
			ExplosionArt.blast(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.add(0, 1.2, 0), 100, 1.5, true);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 10.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 8.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 8.0f, 0.7f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 4.0f, 0.6f);
			String[] blocks = {"minecraft:black_concrete", "minecraft:blue_concrete", "minecraft:white_concrete"};
			for (int i = 0; i < 3; i++) Shapes.diskMake(props, "cr" + i + "_", blocks[i], new Vec3(target.x, target.y + 0.07 + i * 0.012, target.z), 0.05, 4, true, end - 1);
			ExplosionArt.debris(props, target.add(0, 1, 0), target.y, new String[] {"minecraft:copper_block", "minecraft:blackstone", "minecraft:iron_block", "minecraft:blue_concrete"},
				cinema ? 18 : 8, 1.6, 1.9, t, 60);
			ExplosionArt.firework(level, target.add(0, 16, 0), FireworkExplosion.Shape.LARGE_BALL, new int[] {0xFFFFFF, 0x9FD8FF}, new int[] {0x2040A0});
			ExplosionArt.firework(level, target.add(0, 26, 0), FireworkExplosion.Shape.BURST, new int[] {0xE8F4FF}, new int[] {0x4080FF});
		}
		if (t == strike + 1) {
			double[] radii = {7.0, 4.8, 1.8};
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.07 + i * 0.012, target.z), radii[i], 0.05, 4, 8);
		}
		if (t > strike && t < strike + 40) {
			if (t % 2 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.x, target.y + 1.0, target.z, 4, 3.0, 0.8, 3.0, 0.25);
			ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, target.x, target.y + 1, target.z, 1, 1.6, 0.6, 1.6, 0.03);
		}
		if (t > strike && t <= strike + 30 && t % 6 == 0) {
			// the last rumbles: smaller bolts still fall around
			double a = rnd.nextDouble() * Math.PI * 2;
			double r = 4 + rnd.nextDouble() * 9;
			Vec3 hit = new Vec3(target.x + Math.cos(a) * r, target.y, target.z + Math.sin(a) * r);
			bolt(props, new Vec3(hit.x, target.y + height, hit.z), hit, t, 3, 0.7, 0.22, false, 2.0);
			ExplosionArt.sound(level, hit, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.0f, 0.7f + rnd.nextFloat() * 0.4f);
		}
		if (t == end - 20) {
			// the sky clears
			for (int i = 0; i < CLOUDS; i++) {
				Props.Prop c = props.named("cl" + i);
				if (c == null) continue;
				props.reshape(c, c.pos(), new Vec3(1.0, 0.6, 1.0), Q.yaw(i * 23), 18);
			}
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("dark" + i), target, cube(-2.0), TURNS[i], 18);
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.07 + i * 0.012, target.z), 0.4, 0.05, 4, 14);
		}
	}
}
