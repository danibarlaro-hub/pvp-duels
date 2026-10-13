package com.pvpduels.effects;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The drawing of the three big explosions that need more than a few lines: the Shooting Star, the Black Hole and the Meteor Shower.
 * Only particles and sounds: nothing is damaged and nothing is broken. {@code t} is the number of ticks since the explosion started
 * (the show lasts about 90 of them). Every particle goes through {@link Quality}, so each player sees as many as they chose.
 */
public final class ExplosionArt {
	/** A meteor of the shower: where it starts, where it lands, when it started, how long it flies and whether it is the big last one. */
	public static final class Meteor {
		final Vec3 start;
		final Vec3 end;
		final int born;
		final int flight;
		final boolean big;

		Meteor(Vec3 start, Vec3 end, int born, int flight, boolean big) {
			this.start = start;
			this.end = end;
			this.born = born;
			this.flight = flight;
			this.big = big;
		}
	}

	/** How long the Shooting Star flies before it lands. */
	public static final int STAR_FLIGHT = 34;
	/** The Black Hole collapses at this tick and bursts a few ticks later. */
	public static final int HOLE_COLLAPSE = 64;
	public static final int HOLE_BURST = 72;
	/** The Meteor Shower: when the last small meteor is born and when the big one is. */
	public static final int SHOWER_END = 62;
	public static final int SHOWER_BIG = 64;

	private static final ParticleOptions GOLD = new DustParticleOptions(0xFFD54A, 1.5f);
	private static final ParticleOptions ICE = new DustParticleOptions(0x8FD3FF, 1.4f);
	private static final ParticleOptions VIOLET = new DustParticleOptions(0xB06CFF, 1.3f);
	private static final ParticleOptions VOID = new DustParticleOptions(0x050505, 3.0f);
	private static final ParticleOptions DISK_A = new DustParticleOptions(0xFFB347, 1.6f);
	private static final ParticleOptions DISK_B = new DustParticleOptions(0xFF5A1F, 1.7f);
	private static final ParticleOptions DISK_C = new DustParticleOptions(0xA3200E, 1.7f);
	private static final ParticleOptions ARM = new DustParticleOptions(0x5A2A8C, 1.5f);

	private ExplosionArt() {}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) Quality.send(level, v, options, x, y, z, count, dx, dy, dz, speed);
	}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 at, int count, double spread, double speed) {
		emit(level, viewers, options, at.x, at.y, at.z, count, spread, spread, spread, speed);
	}

	private static void sound(ServerLevel level, Vec3 at, SoundEvent event, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
	}

	private static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> event, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
	}

	/** A horizontal ring (tilted around the x axis by {@code tilt} radians) around {@code center}. */
	private static void ring(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 center, double radius, int points, double turn, double tilt) {
		double cos = Math.cos(tilt);
		double sin = Math.sin(tilt);
		for (int k = 0; k < points; k++) {
			double a = turn + k * Math.PI * 2 / points;
			double x = Math.cos(a) * radius;
			double z = Math.sin(a) * radius;
			emit(level, viewers, options, center.x + x, center.y + z * sin, center.z + z * cos, 1, 0.03, 0.03, 0.03, 0.0);
		}
	}

	private static Vec3 flatDirection(Vec3 from, Vec3 to) {
		Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
		return flat.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : flat.normalize();
	}

	// ------------------------------------------------------------------ the Shooting Star

	private static Vec3 starAt(Vec3 start, Vec3 end, double u) {
		double e = Math.pow(Math.max(0, Math.min(1, u)), 1.6); // it speeds up as it falls
		return start.add(end.subtract(start).scale(e));
	}

	/**
	 * A bright star with a long tail crosses the sky from behind the winner and lands on the target: a flash, a pillar of light, rings
	 * that spread, a star drawn on the ground and star dust that falls for a while.
	 */
	public static void shootingStar(ServerLevel level, List<ServerPlayer> viewers, Vec3 from, Vec3 target, int t) {
		Vec3 dir = flatDirection(from, target);
		Vec3 start = target.add(dir.scale(-80)).add(0, 62, 0);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) {
			sound(level, target.add(0, 25, 0), SoundEvents.FIREWORK_ROCKET_LAUNCH, 4.0f, 0.6f);
			sound(level, target.add(0, 25, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 4.0f, 0.5f);
		}
		if (t < STAR_FLIGHT) {
			double u = t / (double) STAR_FLIGHT;
			Vec3 head = starAt(start, target, u);
			emit(level, viewers, ParticleTypes.END_ROD, head, 10, 0.35, 0.02);
			emit(level, viewers, ParticleTypes.FIREWORK, head, 5, 0.6, 0.05);
			// the five rays of the star
			for (int r = 0; r < 5; r++) {
				double a = t * 0.3 + r * Math.PI * 2 / 5;
				for (int k = 1; k <= 3; k++) {
					emit(level, viewers, ParticleTypes.END_ROD, head.x + Math.cos(a) * k * 0.45, head.y + Math.sin(a) * k * 0.45, head.z, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
			// the tail: gold close to the star, then blue, then violet
			for (int k = 1; k <= 26; k++) {
				double back = u - k * 0.6 / STAR_FLIGHT;
				if (back < 0) break;
				Vec3 p = starAt(start, target, back);
				ParticleOptions o = k < 8 ? GOLD : (k < 17 ? ICE : VIOLET);
				emit(level, viewers, o, p.x, p.y, p.z, 2, 0.12 + k * 0.012, 0.12 + k * 0.012, 0.12 + k * 0.012, 0.0);
				if (k % 3 == 0) emit(level, viewers, ParticleTypes.END_ROD, p, 1, 0.2, 0.0);
			}
			return;
		}
		int w = t - STAR_FLIGHT;
		if (w == 0) {
			sound(level, target, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.9f);
			sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0f, 0.7f);
			sound(level, target, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 4.0f, 0.8f);
			sound(level, target, SoundEvents.AMETHYST_BLOCK_CHIME, 4.0f, 1.3f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
		}
		if (w < 26) {
			// the pillar of light
			double height = Math.min(16, w * 2.5);
			for (double h = 0; h <= height; h += 1.0) emit(level, viewers, ParticleTypes.END_ROD, target.x, target.y + h, target.z, 2, 0.35, 0.1, 0.35, 0.0);
		}
		if (w < 24) {
			// the rings that spread over the ground
			double radius = 0.8 + w * 0.55;
			ring(level, viewers, ParticleTypes.END_ROD, target.add(0, 0.25, 0), radius, 36, w * 0.1, 0);
			ring(level, viewers, GOLD, target.add(0, 0.35, 0), radius * 0.75, 28, -w * 0.1, 0);
		}
		if (w >= 2 && w <= 30) {
			// a star drawn on the ground (a pentagram), slowly turning
			double radius = 5.0;
			double turn = w * 0.04;
			for (int line = 0; line < 5; line++) {
				double a1 = turn + line * Math.PI * 2 * 2 / 5;
				double a2 = turn + (line + 1) * Math.PI * 2 * 2 / 5;
				for (int k = 0; k <= 8; k++) {
					double f = k / 8.0;
					double x = Math.cos(a1) * radius * (1 - f) + Math.cos(a2) * radius * f;
					double z = Math.sin(a1) * radius * (1 - f) + Math.sin(a2) * radius * f;
					emit(level, viewers, k % 2 == 0 ? GOLD : ICE, target.x + x, target.y + 0.3, target.z + z, 1, 0.02, 0.02, 0.02, 0.0);
				}
			}
		}
		if (w > 4 && w < 50) {
			// star dust that falls
			for (int k = 0; k < 10; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double r = Math.sqrt(rnd.nextDouble()) * 8;
				emit(level, viewers, k % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD, target.x + Math.cos(a) * r, target.y + 2 + rnd.nextDouble() * 7,
					target.z + Math.sin(a) * r, 1, 0.05, 0.3, 0.05, 0.01);
			}
		}
	}

	// ------------------------------------------------------------------ the Black Hole

	/**
	 * A black sphere appears above the target with a bright ring of light, a turning disk of fire around it and spiral arms of dust that are
	 * sucked in. It collapses, and a shock wave bursts out.
	 */
	public static void blackHole(ServerLevel level, List<ServerPlayer> viewers, Vec3 target, int t) {
		Vec3 hole = target.add(0, 5.0, 0);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) {
			sound(level, hole, SoundEvents.END_PORTAL_SPAWN, 4.0f, 0.5f);
			sound(level, hole, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
		}
		if (t < HOLE_BURST) {
			double core = t < 12 ? 1.8 * t / 12.0 : (t < HOLE_COLLAPSE ? 1.8 : 1.8 * (1 - (t - HOLE_COLLAPSE) / (double) (HOLE_BURST - HOLE_COLLAPSE)));
			// the dark sphere
			for (int k = 0; k < 16; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double b = Math.acos(2 * rnd.nextDouble() - 1);
				emit(level, viewers, k % 4 == 0 ? ParticleTypes.SQUID_INK : VOID, hole.x + core * Math.sin(b) * Math.cos(a), hole.y + core * Math.cos(b),
					hole.z + core * Math.sin(b) * Math.sin(a), 1, 0.02, 0.02, 0.02, 0.0);
			}
			double tilt = 0.45;
			double speed = t * 0.14;
			// the thin ring of light that bends around it
			ring(level, viewers, ParticleTypes.END_ROD, hole, core * 1.35 + 0.35, 28, speed, tilt);
			// the disk of fire: slower the further out
			ring(level, viewers, DISK_A, hole, core * 1.9 + 0.6, 22, speed * 1.0, tilt);
			ring(level, viewers, DISK_B, hole, core * 2.6 + 0.9, 26, speed * 0.7, tilt);
			ring(level, viewers, DISK_C, hole, core * 3.4 + 1.2, 30, speed * 0.5, tilt);
			if (t % 2 == 0) emit(level, viewers, ParticleTypes.FLAME, hole.x + Math.cos(speed) * core * 2.4, hole.y + Math.sin(speed) * core * 2.4 * Math.sin(tilt),
				hole.z + Math.sin(speed) * core * 2.4 * Math.cos(tilt), 2, 0.2, 0.1, 0.2, 0.01);
			// the arms that are sucked in: they spiral toward the center and turn faster the closer they get
			double pull = t < HOLE_COLLAPSE ? 0.5 : 1.1;
			for (int arm = 0; arm < 3; arm++) {
				for (int s = 0; s < 12; s++) {
					double phase = ((s + t * pull * 0.25) % 12) / 12.0;
					double r = 14 * (1 - phase) + 1.5;
					double a = arm * Math.PI * 2 / 3 + r * 0.42 + t * 0.12;
					double lift = Math.sin(r * 0.5) * 0.8;
					emit(level, viewers, s % 3 == 0 ? ParticleTypes.PORTAL : ARM, hole.x + Math.cos(a) * r, hole.y + lift + (r - 1.5) * 0.12 * Math.sin(tilt),
						hole.z + Math.sin(a) * r, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}
			if (t % 4 == 0) {
				// bits of the ground are pulled up
				double a = rnd.nextDouble() * Math.PI * 2;
				double r = 3 + rnd.nextDouble() * 7;
				emit(level, viewers, ParticleTypes.SMOKE, target.x + Math.cos(a) * r, target.y + 0.4, target.z + Math.sin(a) * r, 3, 0.3, 0.2, 0.3, 0.03);
			}
			return;
		}
		int w = t - HOLE_BURST;
		if (w == 0) {
			sound(level, hole, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 0.8f);
			sound(level, hole, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.6f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, hole.x, hole.y, hole.z, 1, 0, 0, 0, 0);
		}
		if (w < 16) {
			double radius = 1 + w * 1.3;
			ring(level, viewers, ParticleTypes.END_ROD, hole, radius, 48, w * 0.2, 0.0);
			ring(level, viewers, VIOLET, hole, radius * 0.8, 40, -w * 0.2, 0.9);
			ring(level, viewers, DISK_B, target.add(0, 0.3, 0), radius * 1.2, 48, 0, 0.0);
			emit(level, viewers, ParticleTypes.CLOUD, hole.x, hole.y, hole.z, 12, radius * 0.3, radius * 0.3, radius * 0.3, 0.2);
		}
	}

	// ------------------------------------------------------------------ the Meteor Shower

	/**
	 * Meteors fall from behind the winner over the target, one after the other, each with a fiery tail and a small blast where it lands;
	 * the last one is a big one that hits the target itself.
	 */
	public static void meteorShower(ServerLevel level, List<ServerPlayer> viewers, Vec3 from, Vec3 target, int t, List<Meteor> meteors) {
		Vec3 dir = flatDirection(from, target);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) sound(level, target.add(0, 20, 0), SoundEvents.ENDER_DRAGON_GROWL, 2.0f, 1.4f);
		if (t <= SHOWER_END && t % 3 == 0) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double r = 3 + Math.sqrt(rnd.nextDouble()) * 9;
			Vec3 end = target.add(Math.cos(a) * r, 0, Math.sin(a) * r);
			Vec3 start = end.add(dir.scale(-36)).add(0, 48, 0);
			meteors.add(new Meteor(start, end, t, 16 + rnd.nextInt(8), false));
		}
		if (t == SHOWER_BIG) {
			meteors.add(new Meteor(target.add(dir.scale(-40)).add(0, 55, 0), target, t, 24, true));
			sound(level, target.add(0, 25, 0), SoundEvents.WITHER_SPAWN, 3.0f, 1.6f);
		}
		Iterator<Meteor> it = meteors.iterator();
		while (it.hasNext()) {
			Meteor m = it.next();
			double u = (t - m.born) / (double) m.flight;
			if (u < 0) continue;
			if (u >= 1) {
				it.remove();
				land(level, viewers, m);
				continue;
			}
			Vec3 head = m.start.add(m.end.subtract(m.start).scale(u));
			int bigger = m.big ? 2 : 1;
			emit(level, viewers, ParticleTypes.FLAME, head, 4 * bigger, 0.25 * bigger, 0.02);
			emit(level, viewers, ParticleTypes.LARGE_SMOKE, head, 2 * bigger, 0.3 * bigger, 0.02);
			for (int k = 1; k <= 9; k++) {
				double back = u - k * 0.03;
				if (back < 0) break;
				Vec3 p = m.start.add(m.end.subtract(m.start).scale(back));
				emit(level, viewers, k < 4 ? ParticleTypes.FLAME : ParticleTypes.SMOKE, p, bigger, 0.12 * k * bigger, 0.01);
			}
		}
	}

	private static void land(ServerLevel level, List<ServerPlayer> viewers, Meteor m) {
		Vec3 at = m.end;
		if (m.big) {
			sound(level, at, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.7f);
			sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0f, 0.6f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
			for (int ring = 1; ring <= 3; ring++) ring(level, viewers, ring == 1 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, at.add(0, 0.3, 0), ring * 2.2, 24, ring, 0.0);
			emit(level, viewers, ParticleTypes.LAVA, at.x, at.y + 0.5, at.z, 14, 1.5, 0.5, 1.5, 0.0);
			return;
		}
		sound(level, at, SoundEvents.GENERIC_EXPLODE, 1.3f, 1.1f + (float) ThreadLocalRandom.current().nextDouble() * 0.3f);
		emit(level, viewers, ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		ring(level, viewers, ParticleTypes.FLAME, at.add(0, 0.2, 0), 1.6, 12, 0, 0.0);
		emit(level, viewers, ParticleTypes.LAVA, at.x, at.y + 0.4, at.z, 3, 0.5, 0.2, 0.5, 0.0);
	}
}
