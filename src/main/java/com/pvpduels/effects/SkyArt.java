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
 * Two explosions that happen in the sky above the target: the Eclipse (a black disk with a golden corona slides in front of the
 * sun, the world goes dim, and beams of light come down from its rim) and the Moonfall (the moon comes down growing until it lands on
 * the target). Only light and sound: every solid part is a display entity.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class SkyArt {
	private static final ParticleOptions GOLD_DUST = new DustParticleOptions(0xFFC83C, 1.8f);
	private static final ParticleOptions WHITE_DUST = new DustParticleOptions(0xFFF4D0, 1.6f);
	private static final ParticleOptions MOON_DUST = new DustParticleOptions(0xC8D0E0, 2.0f);
	/** Two boxes turned 45 degrees from each other make the shell that darkens the world. */
	private static final Q[] TURNS = {Q.ID, Q.yaw(45)};

	private SkyArt() {}

	private static double ease(double x) {
		double u = Math.max(0, Math.min(1, x));
		return u * u * (3 - 2 * u);
	}

	private static double clamp01(double x) {
		return Math.max(0, Math.min(1, x));
	}

	private static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	// ------------------------------------------------------------------ the Eclipse: the numbers

	/** How long the shadow takes to sweep over the arena. */
	public static int eclipseSweep(boolean cinema) {
		return cinema ? 36 : 24;
	}

	/** How long totality lasts (the corona shines, then the beams are born). */
	public static int eclipseTotal(boolean cinema) {
		return cinema ? 56 : 28;
	}

	/** How long the beams take to reach the target. */
	public static int eclipseBeams(boolean cinema) {
		return cinema ? 14 : 8;
	}

	public static int eclipseAfter(boolean cinema) {
		return cinema ? 80 : 44;
	}

	/** The tick the beams are let go. */
	public static int eclipseStrike(boolean cinema) {
		return eclipseSweep(cinema) + eclipseTotal(cinema);
	}

	/** The tick the beams arrive. */
	public static int eclipseImpact(boolean cinema) {
		return eclipseStrike(cinema) + eclipseBeams(cinema);
	}

	public static int eclipseTicks(boolean cinema) {
		return eclipseImpact(cinema) + eclipseAfter(cinema);
	}

	/** How high above the target the disks are. */
	public static double eclipseHeight(boolean cinema) {
		return cinema ? 62 : 46;
	}

	/** The radius of the black disk. */
	public static double eclipseRadius(boolean cinema) {
		return cinema ? 15 : 11;
	}

	/** The middle of the black disk. */
	public static Vec3 eclipseCenter(Vec3 target, boolean cinema) {
		return target.add(0, eclipseHeight(cinema), 0);
	}

	private static int rays(boolean cinema) {
		return cinema ? 28 : 18;
	}

	private static int beamCount(boolean cinema) {
		return cinema ? 14 : 8;
	}

	private static double rayLength(int k) {
		return 5.0 + ((k * 7) % 5) * 4.5;
	}

	private static Vec3[] rayEnds(int k, int total, Vec3 mid, double rb, double scale) {
		double a = k * Math.PI * 2 / total;
		Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
		double rc = rb + 3.5;
		return new Vec3[] {mid.add(dir.scale(rb - 1)).add(0, 1.2, 0), mid.add(dir.scale(rc + rayLength(k) * scale)).add(0, 1.2, 0)};
	}

	// ------------------------------------------------------------------ the Eclipse

	public static void eclipse(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int sweep = eclipseSweep(cinema);
		int total = eclipseTotal(cinema);
		int beams = eclipseBeams(cinema);
		int after = eclipseAfter(cinema);
		int strike = sweep + total;
		int impact = strike + beams;
		int end = impact + after;
		double rb = eclipseRadius(cinema);
		double rc = rb + 3.5;
		double domeR = cinema ? 36 : 26;
		Vec3 mid = eclipseCenter(target, cinema);
		Vec3 ground = target.add(0, 0.3, 0);
		Vec3 aim = target.add(0, 1.0, 0);
		int rayCount = rays(cinema);
		int beamN = beamCount(cinema);
		if (t >= end) return;

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.5f);
			Shapes.diskMake(props, "bd", "minecraft:black_concrete", mid, 1.0, 8, true, end);
			Shapes.diskMake(props, "cd", "minecraft:yellow_concrete", mid.add(0, 0.9, 0), 0.6, 8, true, end);
			Shapes.diskMake(props, "hd", "minecraft:orange_stained_glass", mid.add(0, 1.5, 0), 0.2, 8, true, end);
			// the shadow: two boxes of dark glass that swell from the middle of the arena (seen from inside, so the faces drawn are the inner ones)
			for (int i = 0; i < TURNS.length; i++) {
				props.lifetime(props.name("umbra" + i, props.block("minecraft:gray_stained_glass", target, cube(-2.0), TURNS[i], true)), end);
			}
		}
		if (t == 1) {
			Shapes.diskTo(props, "bd", mid, rb, 1.0, 8, sweep);
			Shapes.diskTo(props, "cd", mid.add(0, 0.9, 0), rc, 0.6, 8, sweep);
			Shapes.diskTo(props, "hd", mid.add(0, 1.5, 0), rc + 4, 0.2, 8, sweep);
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("umbra" + i), target, cube(-2.0 * domeR), TURNS[i], sweep);
		}
		if (t < sweep && t % 2 == 0) {
			// the edge of the shadow runs over the floor
			double r = domeR * ease((t + 1) / (double) sweep);
			ExplosionArt.ring(level, viewers, ParticleTypes.SQUID_INK, ground, r, Math.min(72, (int) (r * 2.5) + 8), t * 0.07, 0);
		}
		if (t == sweep) {
			// totality: the world goes dark at once, the corona shines
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.7f);
			props.lifetime(props.name("umbra2", props.block("minecraft:black_stained_glass", target, cube(-2.0 * domeR * 0.98), TURNS[0], true)), impact + 2);
			for (int k = 0; k < rayCount; k++) {
				Vec3[] ends = rayEnds(k, rayCount, mid, rb, 1.0);
				props.lifetime(props.name("ray" + k, Shapes.lineGrowing(props, k % 2 == 0 ? "minecraft:yellow_stained_glass" : "minecraft:white_stained_glass", ends[0], ends[1], 0.6, true)), end - 20);
			}
			Vec3 rim = mid.add(rb * 0.9, 0.5, 0);
			props.lifetime(props.name("diamond", props.item("minecraft:firework_star", rim, 4.0)), strike + 8);
		}
		if (t == sweep + 1) {
			for (int k = 0; k < rayCount; k++) {
				Vec3[] ends = rayEnds(k, rayCount, mid, rb, 1.0);
				Shapes.growLine(props, props.named("ray" + k), ends[0], ends[1], 0.6, 12);
			}
		}
		if (t == sweep + total / 2 || t == sweep + total * 3 / 4) {
			// the corona flares
			ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, t == sweep + total / 2 ? 0.7f : 1.1f);
			for (int k = 0; k < rayCount; k++) {
				Vec3[] ends = rayEnds(k, rayCount, mid, rb, 1.45);
				Shapes.growLine(props, props.named("ray" + k), ends[0], ends[1], 0.9, 4);
			}
		}
		if (t == sweep + total / 2 + 6 || t == sweep + total * 3 / 4 + 6) {
			for (int k = 0; k < rayCount; k++) {
				Vec3[] ends = rayEnds(k, rayCount, mid, rb, 1.0);
				Shapes.growLine(props, props.named("ray" + k), ends[0], ends[1], 0.6, 6);
			}
		}
		if (t >= sweep && t < strike && t % 2 == 0) {
			// gold drifts down out of the dark
			ExplosionArt.emit(level, viewers, GOLD_DUST, target.x + (rnd.nextDouble() - 0.5) * 24, target.y + 3 + rnd.nextDouble() * 12, target.z + (rnd.nextDouble() - 0.5) * 24, 6, 1.0, 1.0, 1.0, 0.0);
			ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, aim, 2, 1.4, 0.03);
		}
		if (t == strike - 10) {
			Props.Prop diamond = props.named("diamond");
			if (diamond != null) props.resize(diamond, 26.0, 8);
			ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 5.0f, 1.6f);
		}
		if (t == strike - 1) {
			// the beams are born collapsed on the rim of the black disk, one tick before they are let go
			for (int b = 0; b < beamN; b++) {
				double a = b * Math.PI * 2 / beamN + 0.2;
				Vec3 rim = mid.add(Math.cos(a) * rb * 0.9, -0.6, Math.sin(a) * rb * 0.9);
				props.lifetime(props.name("sb" + b, Shapes.lineGrowing(props, "minecraft:yellow_stained_glass", rim, aim, 0.55, true)), impact + 4);
				props.lifetime(props.name("sc" + b, Shapes.lineGrowing(props, "minecraft:white_concrete", rim, aim, 0.16, true)), impact + 4);
			}
		}
		if (t == strike) {
			for (int b = 0; b < beamN; b++) {
				double a = b * Math.PI * 2 / beamN + 0.2;
				Vec3 rim = mid.add(Math.cos(a) * rb * 0.9, -0.6, Math.sin(a) * rb * 0.9);
				Shapes.growLine(props, props.named("sb" + b), rim, aim, 0.55, beams);
				Shapes.growLine(props, props.named("sc" + b), rim, aim, 0.16, beams);
			}
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 1.4f);
			ExplosionArt.sound(level, target, SoundEvents.FIREWORK_ROCKET_LAUNCH, 6.0f, 0.5f);
		}
		if (t >= strike && t < impact) {
			ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, aim, 8, 1.6, 0.08);
			ExplosionArt.ring(level, viewers, GOLD_DUST, ground, 1.5 + (impact - t) * 0.6, 24, t * 0.3, 0);
		}
		if (t == impact - 1) {
			// the column of the sun itself, from the middle of the black disk to the target
			String[] states = {"minecraft:orange_stained_glass", "minecraft:yellow_stained_glass", "minecraft:white_concrete"};
			double[] widths = {6.0, 3.2, 1.2};
			Vec3 foot = new Vec3(target.x, target.y, target.z);
			Vec3 head = mid.add(0, -0.6, 0);
			for (int i = 0; i < 3; i++) props.lifetime(props.name("col" + i, Shapes.lineGrowing(props, states[i], head, foot, widths[i], true)), impact + 24);
		}
		if (t == impact) {
			Vec3 foot = new Vec3(target.x, target.y, target.z);
			Vec3 head = mid.add(0, -0.6, 0);
			double[] widths = {6.0, 3.2, 1.2};
			for (int i = 0; i < 3; i++) Shapes.growLine(props, props.named("col" + i), head, foot, widths[i], 6);
			Props.Prop flash = props.item("minecraft:firework_star", aim.add(0, 2, 0), 3.0);
			props.resize(flash, cinema ? 64.0 : 40.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.SONIC_BOOM, target.x, target.y + 2, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, ground, 120, 2.2, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 100, 2.6, 0.02);
			ExplosionArt.shockwave(level, viewers, GOLD_DUST, ground, 100, 1.5, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.FLAME, target.add(0, 1.2, 0), 70, 1.0, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, target.add(0, 1.2, 0), 70, 1.4, true);
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 8.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 8.0f, 0.6f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 4.0f, 0.8f);
			String[] blocks = {"minecraft:black_concrete", "minecraft:magma_block", "minecraft:orange_concrete"};
			for (int i = 0; i < 3; i++) Shapes.diskMake(props, "cr" + i + "_", blocks[i], new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), 0.05, 4, true, end);
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				Vec3 tangent = new Vec3(-Math.sin(a), 0, Math.cos(a));
				Props.Prop pane = props.block("minecraft:yellow_stained_glass", new Vec3(target.x + Math.cos(a) * 2.0, target.y + 1.4, target.z + Math.sin(a) * 2.0),
					new Vec3(0.25, 3.0, 2.8), Shapes.facing(tangent), true);
				props.lifetime(props.name("ring" + k, pane), t + 14);
			}
			ExplosionArt.debris(props, target.add(0, 1, 0), target.y, new String[] {"minecraft:gold_block", "minecraft:magma_block", "minecraft:blackstone", "minecraft:orange_concrete"},
				cinema ? 18 : 8, 1.6, 1.9, t, 60);
			ExplosionArt.firework(level, target.add(0, 14, 0), FireworkExplosion.Shape.LARGE_BALL, new int[] {0xFFE680, 0xFFFFFF, 0xFFA030}, new int[] {0x5A2A00});
			ExplosionArt.firework(level, target.add(0, 24, 0), FireworkExplosion.Shape.STAR, new int[] {0xFFD54A, 0xFFFFFF}, new int[] {0xFF8A1F});
		}
		if (t == impact + 1) {
			double[] radii = {8.0, 5.5, 2.0};
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), radii[i], 0.05, 4, 8);
		}
		if (t > impact && t <= impact + 14 && (t - impact) % 2 == 0) {
			double r = 2.0 + (t - impact) * 2.8;
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				props.move(props.named("ring" + k), new Vec3(target.x + Math.cos(a) * r, target.y + 1.4 * (1 - (t - impact) / 18.0), target.z + Math.sin(a) * r));
			}
		}
		if (t == impact + 6) {
			Vec3 foot = new Vec3(target.x, target.y, target.z);
			Vec3 head = mid.add(0, -0.6, 0);
			for (int i = 0; i < 3; i++) Shapes.growLine(props, props.named("col" + i), head, foot, 0.05, 14);
		}
		if (t > impact && t < impact + 50) {
			ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, target.x, target.y + 0.5, target.z, 3, 2.2, 0.4, 2.2, 0.04);
			ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, target.x, target.y + 1, target.z, 2, 2.0, 0.6, 2.0, 0.03);
		}
		if (t == end - 28) {
			// the light comes back: the disks shrink and the shadow lifts
			Shapes.diskTo(props, "bd", mid, 0.5, 1.0, 8, 24);
			Shapes.diskTo(props, "cd", mid.add(0, 0.9, 0), 0.5, 0.6, 8, 24);
			Shapes.diskTo(props, "hd", mid.add(0, 1.5, 0), 0.5, 0.2, 8, 24);
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("umbra" + i), target, cube(-2.0), TURNS[i], 24);
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), 0.4, 0.05, 4, 14);
		}
	}

	// ------------------------------------------------------------------ the Moonfall: the numbers

	/** The night comes first; the moon is born this many ticks after the start. */
	public static int moonWait(boolean cinema) {
		return cinema ? 24 : 10;
	}

	/** How long the moon falls. */
	public static int moonDrop(boolean cinema) {
		return cinema ? 90 : 40;
	}

	/** The tick the moon lands. */
	public static int moonLand(boolean cinema) {
		return moonWait(cinema) + moonDrop(cinema);
	}

	public static int moonAfter(boolean cinema) {
		return cinema ? 96 : 60;
	}

	public static int moonTicks(boolean cinema) {
		return moonLand(cinema) + moonAfter(cinema);
	}

	/** The radius of the moon when it lands. */
	public static double moonRadius(boolean cinema) {
		return cinema ? 13 : 8;
	}

	/** How high above the floor the middle of the moon is when it is born. */
	public static double moonHeight(boolean cinema) {
		return cinema ? 150 : 90;
	}

	private static double moonFraction(int t, boolean cinema) {
		return clamp01((t - moonWait(cinema)) / (double) moonDrop(cinema));
	}

	/** The radius of the moon at tick {@code t}: it looks small far away and grows as it comes. */
	public static double moonRadiusAt(int t, boolean cinema) {
		return moonRadius(cinema) * (0.2 + 0.8 * Math.pow(moonFraction(t, cinema), 1.3));
	}

	/** The middle of the moon at tick {@code t}: it falls faster and faster and ends a little inside the ground. */
	public static Vec3 moonCenter(Vec3 target, int t, boolean cinema) {
		double u = moonFraction(t, cinema);
		double y0 = target.y + moonHeight(cinema);
		double y1 = target.y + moonRadius(cinema) - 1.5;
		return new Vec3(target.x, y0 + (y1 - y0) * u * u, target.z);
	}

	private static final int LAYERS = 12;
	private static final String[] MOON_BLOCKS = {"minecraft:white_concrete", "minecraft:light_gray_concrete", "minecraft:calcite", "minecraft:light_gray_concrete"};

	/** The moon is a pile of flat disks whose radii follow a sphere. */
	private static void moonShape(Props props, Vec3 center, double radius, int ticks) {
		double h = 2 * radius / LAYERS;
		for (int i = 0; i < LAYERS; i++) {
			double y = -radius + (i + 0.5) * h;
			double r = Math.sqrt(Math.max(0.01, radius * radius - y * y));
			Shapes.diskTo(props, "m" + i + "_", center.add(0, y, 0), r, h * 1.1, 6, ticks);
		}
	}

	// ------------------------------------------------------------------ the Moonfall

	public static void moonfall(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int wait = moonWait(cinema);
		int land = moonLand(cinema);
		int after = moonAfter(cinema);
		int end = land + after;
		double rFinal = moonRadius(cinema);
		double domeR = cinema ? 38 : 26;
		Vec3 ground = target.add(0, 0.3, 0);
		if (t >= end) return;

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			// night comes: two boxes of blue glass swell from the middle of the arena
			for (int i = 0; i < TURNS.length; i++) {
				props.lifetime(props.name("night" + i, props.block("minecraft:blue_stained_glass", target, cube(-2.0), TURNS[i], true)), end - 20);
			}
		}
		if (t == 1) {
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("night" + i), target, cube(-2.0 * domeR), TURNS[i], wait);
		}
		if (t < wait && t % 2 == 0) {
			double r = domeR * ease((t + 1) / (double) wait);
			ExplosionArt.ring(level, viewers, ParticleTypes.SQUID_INK, ground, r, Math.min(72, (int) (r * 2.5) + 8), t * 0.07, 0);
		}
		if (t == wait) {
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.4f);
			ExplosionArt.sound(level, target, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.4f);
			Vec3 at = moonCenter(target, t, cinema);
			for (int i = 0; i < LAYERS; i++) Shapes.diskMake(props, "m" + i + "_", MOON_BLOCKS[i % MOON_BLOCKS.length], at, 0.3, 6, true, land + 26);
			Shapes.diskMake(props, "sh", "minecraft:black_stained_glass", target.add(0, 0.04, 0), 0.05, 8, true, land + 4);
			props.lifetime(props.name("halo", props.item("minecraft:firework_star", at, 8.0)), land + 4);
		}
		if (t > wait && t <= land && ((t - wait) % 4 == 1 || t == land)) {
			int span = t == land ? 2 : 4;
			Vec3 at = moonCenter(target, t, cinema);
			double r = moonRadiusAt(t, cinema);
			moonShape(props, at, r, span);
			Shapes.diskTo(props, "sh", target.add(0, 0.04, 0), r * 1.1, 0.05, 8, span);
			Props.Prop halo = props.named("halo");
			if (halo != null) {
				props.move(halo, at);
				props.resize(halo, r * 3.4, span);
			}
		}
		if (t > wait && t < land && t % 3 == 0) {
			// cold dust falls from the moon, more and more as it comes
			double u = moonFraction(t, cinema);
			Vec3 at = moonCenter(target, t, cinema);
			double r = moonRadiusAt(t, cinema);
			ExplosionArt.emit(level, viewers, MOON_DUST, at.x, at.y - r, at.z, 4 + (int) (6 * u), r * 0.5, 0.5, r * 0.5, 0.0);
			if (u > 0.5) ExplosionArt.ring(level, viewers, MOON_DUST, ground, r * (1.4 - 0.4 * u), 24, t * 0.2, 0);
		}
		if (t == land - 12) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 6.0f, 0.5f);
		if (t == land) {
			Props.Prop flash = props.item("minecraft:firework_star", target.add(0, 4, 0), 3.0);
			props.resize(flash, cinema ? 80.0 : 50.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, ground, 140, 2.8, 0.03);
			ExplosionArt.shockwave(level, viewers, MOON_DUST, ground, 120, 2.0, 0.04);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 90, 3.0, 0.02);
			ExplosionArt.blast(level, viewers, ParticleTypes.CLOUD, target.add(0, 2, 0), 90, 1.2, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, target.add(0, 2, 0), 60, 1.5, true);
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 10.0f, 0.4f);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 10.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.4f);
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 8.0f, 0.5f);
			String[] blocks = {"minecraft:black_concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete"};
			for (int i = 0; i < 3; i++) Shapes.diskMake(props, "cr" + i + "_", blocks[i], new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), 0.05, 4, true, end);
			int panes = 36;
			for (int k = 0; k < panes; k++) {
				double a = k * Math.PI * 2 / panes;
				Vec3 tangent = new Vec3(-Math.sin(a), 0, Math.cos(a));
				Props.Prop pane = props.block("minecraft:white_stained_glass", new Vec3(target.x + Math.cos(a) * rFinal, target.y + 1.6, target.z + Math.sin(a) * rFinal),
					new Vec3(0.25, 3.4, 3.0), Shapes.facing(tangent), true);
				props.lifetime(props.name("ring" + k, pane), t + 16);
			}
			ExplosionArt.debris(props, target.add(0, 2, 0), target.y, new String[] {"minecraft:stone", "minecraft:andesite", "minecraft:calcite", "minecraft:white_concrete", "minecraft:light_gray_concrete"},
				cinema ? 32 : 14, 2.0, 2.2, t, 70);
			ExplosionArt.firework(level, target.add(0, 20, 0), FireworkExplosion.Shape.LARGE_BALL, new int[] {0xFFFFFF, 0xC8D0E0}, new int[] {0x6080C0});
			ExplosionArt.firework(level, target.add(0, 30, 0), FireworkExplosion.Shape.BURST, new int[] {0xE8F0FF}, new int[] {0x4060A0});
		}
		if (t == land + 1) {
			double[] radii = {rFinal * 0.95, rFinal * 0.7, rFinal * 0.35};
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), radii[i], 0.05, 4, 8);
		}
		if (t > land && t <= land + 16 && (t - land) % 2 == 0) {
			double r = rFinal + (t - land) * 3.2;
			for (int k = 0; k < 36; k++) {
				double a = k * Math.PI * 2 / 36;
				props.move(props.named("ring" + k), new Vec3(target.x + Math.cos(a) * r, target.y + 1.6 * (1 - (t - land) / 20.0), target.z + Math.sin(a) * r));
			}
		}
		if (t == land + 6) {
			// the moon crumbles into the ground
			Vec3 at = moonCenter(target, land, cinema);
			for (int i = 0; i < LAYERS; i++) Shapes.diskTo(props, "m" + i + "_", new Vec3(at.x, at.y - 2.5, at.z), 0.4, 0.2, 6, 16);
			ExplosionArt.blast(level, viewers, MOON_DUST, at, 80, 0.8, true);
		}
		if (t > land && t < land + 60) {
			ExplosionArt.emit(level, viewers, ParticleTypes.CLOUD, target.x, target.y + 0.6, target.z, 3, rFinal * 0.5, 0.4, rFinal * 0.5, 0.03);
			if (t % 3 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, target.x, target.y + 0.5, target.z, 1, rFinal * 0.4, 0.1, rFinal * 0.4, 0.02);
		}
		if (t == end - 20) {
			// dawn: the night lifts
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("night" + i), target, cube(-2.0), TURNS[i], 18);
			for (int i = 0; i < 3; i++) Shapes.diskTo(props, "cr" + i + "_", new Vec3(target.x, target.y + 0.05 + i * 0.012, target.z), 0.4, 0.05, 4, 14);
		}
	}
}
