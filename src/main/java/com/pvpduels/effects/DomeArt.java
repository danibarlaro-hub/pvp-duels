package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The three Domain Expansions, made of display entities: a real dome (four huge turned cubes drawn from the inside, so together they
 * make a faceted ball around the arena), a floor that covers the arena, and one world inside:
 * <ul>
 * <li>the Crimson Shrine: a black sky, a red ritual circle on the floor, a dark pagoda of bones and skulls behind the winner, and white cuts
 * that flash everywhere around the loser;</li>
 * <li>the Infinite Void: a sky of black with hundreds of stars, a floor that is no floor, glyphs that turn around the loser and a last
 * rush of every star toward them;</li>
 * <li>the Field of Swords: a dusk-orange sky with a huge sun, a hundred swords that fall from the sky and stand in the ground, and a last
 * rain of them around the loser.</li>
 * </ul>
 * Only drawing: nothing is changed in the world. {@code t} is the number of ticks since the domain started to grow.
 */
public final class DomeArt {
	public enum Variant {
		SHRINE("domain_expansion", "Crimson Shrine"),
		VOID("domain_void", "Infinite Void"),
		SWORDS("domain_swords", "Field of Swords");

		public final String id;
		public final String title;

		Variant(String id, String title) {
			this.id = id;
			this.title = title;
		}

		/** The domain that goes with this explosion id, or null when the explosion is not a domain. */
		public static Variant of(String explosion) {
			for (Variant v : values()) if (v.id.equals(explosion)) return v;
			return null;
		}
	}

	/**
	 * Where the domain happens. {@code center} is the middle of the arena on the floor, {@code half} half the distance between the two
	 * duelists; {@code winner} and {@code loser} are where they stand. {@code cinema} is the long version of the finale.
	 */
	public record Spec(Vec3 center, double half, Vec3 winner, Vec3 loser, boolean cinema) {
		/** The dome is this far from the middle of the arena (the camera of the finale stays well inside it). */
		public double radius() {
			return Math.max(24.0, half + 14.0);
		}

		Vec3 forward() {
			return Shapes.flatDirection(winner, loser);
		}
	}

	/** How long the dome takes to fall apart at the end. */
	public static final int COLLAPSE = 20;
	private static final Q[] TURNS = {Q.ID, Q.yaw(45), Q.pitch(45), Q.roll(45)};
	private static final ParticleOptions RED_DUST = new DustParticleOptions(0xB00020, 1.7f);

	private DomeArt() {}

	/** How long the dome takes to grow. */
	public static int expand(boolean cinema) {
		return cinema ? 36 : 28;
	}

	/** How long the world inside stays complete. */
	public static int hold(boolean cinema) {
		return cinema ? 112 : 64;
	}

	/** The whole domain, from the first tick of its growth to the moment it is gone. */
	public static int ticks(boolean cinema) {
		return expand(cinema) + hold(cinema) + COLLAPSE;
	}

	private static String sky(Variant v) {
		return v == Variant.SWORDS ? "minecraft:orange_terracotta" : "minecraft:black_concrete";
	}

	private static String floor(Variant v) {
		return switch (v) {
			case SHRINE -> "minecraft:black_concrete";
			case VOID -> "minecraft:black_concrete";
			case SWORDS -> "minecraft:brown_concrete";
		};
	}

	private static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	private static void sparks(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 at, int count, double spread, double speed) {
		ExplosionArt.emit(level, viewers, options, at, count, spread, speed);
	}

	// ------------------------------------------------------------------ the dome and the floor

	public static void play(ServerLevel level, List<ServerPlayer> viewers, Props props, Variant v, Spec s, int t) {
		int e = expand(s.cinema());
		int h = hold(s.cinema());
		int end = e + h + COLLAPSE;
		if (t < 0 || t >= end) return;
		double radius = s.radius();
		Vec3 c = s.center();
		double gy = c.y;

		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.6f);
			for (int i = 0; i < TURNS.length; i++) {
				// the cubes are turned inside out (a negative size), so their inside faces are the ones that are drawn
				Props.Prop p = props.name("dome" + i, props.block(sky(v), c, cube(-2.0), TURNS[i], true));
				props.lifetime(p, end);
			}
			for (int k = 0; k < 8; k++) {
				Props.Prop p = props.name("floor" + k, props.block(floor(v), c.add(0, 0.03, 0), new Vec3(1, 0.05, 1), Q.yaw(k * 11.25), true));
				props.lifetime(p, end);
			}
		}
		if (t == 1) {
			// one command per piece: the client makes the dome and the floor grow by itself
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("dome" + i), c, cube(-2.0 * radius), TURNS[i], e);
			double side = Shapes.Disk.sideFor(radius, 8);
			for (int k = 0; k < 8; k++) props.reshape(props.named("floor" + k), c.add(0, 0.03, 0), new Vec3(side, 0.05, side), Q.yaw(k * 11.25), e);
		}
		if (t < e) {
			double r = radius * Math.min(1.0, (t + 1) / (double) e);
			DomainArt.ring(level, viewers, c, r * 0.97, Math.min(96, (int) (r * 3) + 8), 0.15, DomainArt.LINE, t * 0.05);
		}
		if (t == e + h) {
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 1.2f);
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("dome" + i), c, cube(-2.0), TURNS[i], COLLAPSE);
			for (int k = 0; k < 8; k++) props.reshape(props.named("floor" + k), c.add(0, 0.03, 0), new Vec3(1, 0.05, 1), Q.yaw(k * 11.25), COLLAPSE);
			DomainArt.exhale(level, viewers, c, 6);
		}

		switch (v) {
			case SHRINE -> shrine(level, viewers, props, s, t, e, h, end);
			case VOID -> voidWorld(level, viewers, props, s, t, e, h, end);
			case SWORDS -> swords(level, viewers, props, s, t, e, h, end);
		}
	}

	// ------------------------------------------------------------------ the Crimson Shrine

	/** One block of the pagoda: its block, where it is (right, bottom, forward), its size and when it appears. */
	private record Part(String block, double x, double bottom, double z, double sx, double sy, double sz, int batch) {}

	private static final Part[] PAGODA = {
		new Part("minecraft:black_concrete", 0, 0, 0, 11.0, 0.6, 11.0, 0),
		new Part("minecraft:red_concrete", 0, 0.6, 0, 9.4, 0.15, 9.4, 0),
		new Part("minecraft:black_concrete", 0, 0, 6.2, 4.4, 0.3, 1.4, 0),
		new Part("minecraft:black_concrete", 0, 0, 7.4, 3.2, 0.15, 1.0, 0),
		new Part("minecraft:crimson_stem", -4.2, 0.75, -4.2, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:crimson_stem", 4.2, 0.75, -4.2, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:crimson_stem", -4.2, 0.75, 0, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:crimson_stem", 4.2, 0.75, 0, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:crimson_stem", -4.2, 0.75, 4.2, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:crimson_stem", 4.2, 0.75, 4.2, 0.7, 4.9, 0.7, 1),
		new Part("minecraft:red_concrete", 0, 5.65, 0, 13.4, 0.22, 13.4, 2),
		new Part("minecraft:black_concrete", 0, 5.87, 0, 12.8, 0.5, 12.8, 2),
		new Part("minecraft:black_concrete", 0, 6.37, 0, 6.0, 2.4, 6.0, 2),
		new Part("minecraft:red_stained_glass", 0, 6.9, 3.04, 3.0, 1.2, 0.1, 2),
		new Part("minecraft:red_concrete", 0, 8.77, 0, 9.4, 0.2, 9.4, 2),
		new Part("minecraft:black_concrete", 0, 8.97, 0, 9.0, 0.45, 9.0, 2),
		new Part("minecraft:black_concrete", 0, 9.42, 0, 3.6, 1.6, 3.6, 3),
		new Part("minecraft:red_concrete", 0, 10.84, 0, 6.4, 0.18, 6.4, 3),
		new Part("minecraft:black_concrete", 0, 11.02, 0, 6.0, 0.4, 6.0, 3),
		new Part("minecraft:bone_block", 0, 11.42, 0, 0.5, 4.2, 0.5, 3)};

	private static String shrineName(int index) {
		return "sh" + index;
	}

	private static void shrine(ServerLevel level, List<ServerPlayer> viewers, Props props, Spec s, int t, int e, int h, int end) {
		Vec3 f = s.forward();
		Vec3 r = Shapes.rightOf(f);
		Q face = Shapes.facing(f);
		double gy = s.center().y;
		Vec3 base = new Vec3(s.winner().x - f.x * 7.0, gy, s.winner().z - f.z * 7.0);
		Vec3 loser = s.loser();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// the ritual circle on the floor, around the loser
		if (t == e - 10) {
			ExplosionArt.sound(level, base, SoundEvents.WITHER_SPAWN, 4.0f, 0.55f);
			double[] radii = {8.0, 7.4, 4.6, 4.2};
			String[] blocks = {"minecraft:red_concrete", "minecraft:black_concrete", "minecraft:red_concrete", "minecraft:black_concrete"};
			for (int i = 0; i < radii.length; i++) {
				for (int k = 0; k < 4; k++) {
					Props.Prop p = props.name("ring" + i + "_" + k, props.block(blocks[i], new Vec3(loser.x, gy + 0.07 + i * 0.012, loser.z), new Vec3(0.4, 0.04, 0.4),
						Q.yaw(k * 22.5), true));
					props.lifetime(p, end);
				}
			}
			for (int k = 0; k < 5; k++) {
				double a1 = k * Math.PI * 2 / 5 + 0.3;
				double a2 = (k + 2) * Math.PI * 2 / 5 + 0.3;
				Vec3 p1 = new Vec3(loser.x + Math.cos(a1) * 7.3, gy + 0.12, loser.z + Math.sin(a1) * 7.3);
				Vec3 p2 = new Vec3(loser.x + Math.cos(a2) * 7.3, gy + 0.12, loser.z + Math.sin(a2) * 7.3);
				props.lifetime(props.name("star" + k, Shapes.lineGrowing(props, "minecraft:red_concrete", p1, p2, 0.16, true)), end);
			}
		}
		if (t == e - 9) {
			double[] radii = {8.0, 7.4, 4.6, 4.2};
			for (int i = 0; i < radii.length; i++) {
				double side = Shapes.Disk.sideFor(radii[i], 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("ring" + i + "_" + k), new Vec3(loser.x, gy + 0.07 + i * 0.012, loser.z), new Vec3(side, 0.04, side), Q.yaw(k * 22.5), 16);
				}
			}
			for (int k = 0; k < 5; k++) {
				double a1 = k * Math.PI * 2 / 5 + 0.3;
				double a2 = (k + 2) * Math.PI * 2 / 5 + 0.3;
				Vec3 p1 = new Vec3(loser.x + Math.cos(a1) * 7.3, gy + 0.12, loser.z + Math.sin(a1) * 7.3);
				Vec3 p2 = new Vec3(loser.x + Math.cos(a2) * 7.3, gy + 0.12, loser.z + Math.sin(a2) * 7.3);
				Shapes.growLine(props, props.named("star" + k), p1, p2, 0.16, 18);
			}
		}

		// the pagoda rises in four steps, the lowest parts first
		for (int batch = 0; batch < 4; batch++) {
			int when = e - 12 + batch * 4;
			if (t == when) {
				for (int i = 0; i < PAGODA.length; i++) {
					Part p = PAGODA[i];
					if (p.batch() != batch) continue;
					Vec3 center = Shapes.at(base, f, r, p.x(), p.bottom() + p.sy() / 2, p.z());
					props.lifetime(props.name(shrineName(i), props.growing(p.block(), center, new Vec3(p.sx(), p.sy(), p.sz()), face, true, 1)), end);
				}
				if (batch == 3) {
					shrineExtras(props, base, f, r, face, end);
				}
				ExplosionArt.sound(level, base, SoundEvents.ANVIL_LAND, 3.0f, 0.5f + batch * 0.1f);
			}
			if (t == when + 1) {
				for (int i = 0; i < PAGODA.length; i++) {
					Part p = PAGODA[i];
					if (p.batch() != batch) continue;
					Vec3 center = Shapes.at(base, f, r, p.x(), p.bottom() + p.sy() / 2, p.z());
					props.reshape(props.named(shrineName(i)), center, new Vec3(p.sx(), p.sy(), p.sz()), face, 14);
				}
				if (batch == 3) {
					for (int i = 0; i < 8; i++) props.resize(props.named("skull" + i), 1.7, 10);
					for (int i = 0; i < 4; i++) {
						Vec3 corner = shrineHorn(base, f, r, i, 6.2, 6.37);
						props.reshape(props.named("horn" + i), corner.add(0, 0.9, 0), new Vec3(0.5, 1.8, 0.5), hornTilt(f, r, i), 12);
					}
					for (int i = 0; i < 4; i++) {
						Vec3 corner = shrineHorn(base, f, r, i, 4.7, 8.97);
						props.reshape(props.named("horn" + (4 + i)), corner.add(0, 0.75, 0), new Vec3(0.4, 1.5, 0.4), hornTilt(f, r, i), 12);
					}
				}
			}
		}

		// slashes: white cuts that flash around the loser
		int every = s.cinema() ? 2 : 3;
		if (t >= e + 6 && t < e + h - 24 && t % every == 0) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double rad = rnd.nextDouble() * 6.5;
			Vec3 p = new Vec3(loser.x + Math.cos(a) * rad, gy + 0.5 + rnd.nextDouble() * 2.6, loser.z + Math.sin(a) * rad);
			double phi = rnd.nextDouble() * Math.PI;
			double psi = (rnd.nextDouble() - 0.5) * 1.8;
			Vec3 dir = new Vec3(Math.cos(phi) * Math.cos(psi), Math.sin(psi), Math.sin(phi) * Math.cos(psi));
			double len = 9 + rnd.nextDouble() * 9;
			Vec3 from = p.subtract(dir.scale(len / 2));
			Vec3 to = p.add(dir.scale(len / 2));
			Props.Prop core = Shapes.line(props, "minecraft:white_concrete", from, to, 0.12, true);
			Props.Prop glow = Shapes.line(props, "minecraft:red_stained_glass", from, to, 0.5, true);
			props.lifetime(core, t + 3);
			props.lifetime(glow, t + 4);
			sparks(level, viewers, ParticleTypes.SWEEP_ATTACK, p, 1, 0.3, 0.0);
			for (int k = 0; k <= 8; k++) {
				Vec3 q = from.add(to.subtract(from).scale(k / 8.0));
				ExplosionArt.emit(level, viewers, k % 2 == 0 ? ParticleTypes.CRIT : RED_DUST, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0.02);
			}
			ExplosionArt.sound(level, p, SoundEvents.PLAYER_ATTACK_SWEEP, 1.6f, 1.1f + (float) rnd.nextDouble() * 0.6f);
			if ((t / every) % 6 == 0) ExplosionArt.sound(level, p, SoundEvents.PLAYER_ATTACK_CRIT, 2.0f, 0.8f);
		}
		// the great cleave: a white beam through the loser, once, just before the end
		if (t == e + h - 24) {
			Vec3 mid = new Vec3(loser.x, gy + 30, loser.z);
			Props.Prop beam = props.block("minecraft:white_concrete", mid, new Vec3(1.4, 60, 1.4), Q.ID, true);
			Props.Prop glow = props.block("minecraft:red_stained_glass", mid, new Vec3(4.2, 60, 4.2), Q.ID, true);
			props.reshape(beam, mid, new Vec3(0.05, 60, 0.05), Q.ID, 16);
			props.reshape(glow, mid, new Vec3(0.1, 60, 0.1), Q.ID, 16);
			props.lifetime(beam, t + 17);
			props.lifetime(glow, t + 17);
			ExplosionArt.sound(level, loser, SoundEvents.LIGHTNING_BOLT_THUNDER, 6.0f, 0.6f);
			ExplosionArt.sound(level, loser, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 0.9f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CRIMSON_SPORE, new Vec3(loser.x, gy + 0.3, loser.z), 90, 1.4, 0.03);
			ExplosionArt.shockwave(level, viewers, RED_DUST, new Vec3(loser.x, gy + 0.3, loser.z), 90, 1.0, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, loser.add(0, 1.2, 0), 90, 1.3, true);
		}
		// ash and sparks all the time
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 3; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * 16;
				ExplosionArt.emit(level, viewers, k == 0 ? ParticleTypes.CRIMSON_SPORE : ParticleTypes.ASH, s.center().x + Math.cos(a) * rad, gy + 3 + rnd.nextDouble() * 9,
					s.center().z + Math.sin(a) * rad, 3, 0.4, 0.3, 0.4, 0.01);
			}
			if (t >= e && t % 2 == 0) {
				for (int i = 0; i < 4; i++) {
					Vec3 top = Shapes.at(base, f, r, i < 2 ? -4.2 : 4.2, 5.8, i % 2 == 0 ? -4.2 : 4.2);
					ExplosionArt.emit(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, top.x, top.y, top.z, 1, 0.1, 0.2, 0.1, 0.02);
				}
			}
		}
	}

	private static Vec3 shrineHorn(Vec3 base, Vec3 f, Vec3 r, int corner, double reach, double bottom) {
		double sx = (corner & 1) == 0 ? -1 : 1;
		double sz = (corner & 2) == 0 ? -1 : 1;
		return Shapes.at(base, f, r, sx * reach, bottom, sz * reach);
	}

	/** The horn on a corner leans outward. */
	private static Q hornTilt(Vec3 f, Vec3 r, int corner) {
		double sx = (corner & 1) == 0 ? -1 : 1;
		double sz = (corner & 2) == 0 ? -1 : 1;
		Vec3 out = new Vec3(r.x * sx + f.x * sz, 0, r.z * sx + f.z * sz).normalize();
		return Q.axis(out.z, 0, -out.x, Math.toRadians(28));
	}

	/** The horns, the skulls and the tip of the spire: made small, they grow one tick later. */
	private static void shrineExtras(Props props, Vec3 base, Vec3 f, Vec3 r, Q face, int end) {
		for (int i = 0; i < 4; i++) {
			Vec3 corner = shrineHorn(base, f, r, i, 6.2, 6.37);
			props.lifetime(props.name("horn" + i, props.growing("minecraft:bone_block", corner.add(0, 0.9, 0), new Vec3(0.5, 1.8, 0.5), hornTilt(f, r, i), true, 1)), end);
		}
		for (int i = 0; i < 4; i++) {
			Vec3 corner = shrineHorn(base, f, r, i, 4.7, 8.97);
			props.lifetime(props.name("horn" + (4 + i), props.growing("minecraft:bone_block", corner.add(0, 0.75, 0), new Vec3(0.4, 1.5, 0.4), hornTilt(f, r, i), true, 1)), end);
		}
		for (int i = 0; i < 5; i++) {
			Vec3 at = Shapes.at(base, f, r, -5.0 + i * 2.5, 6.3, 6.9);
			props.lifetime(props.name("skull" + i, props.item("minecraft:wither_skeleton_skull", at, 0.1)), end);
		}
		for (int i = 0; i < 3; i++) {
			Vec3 at = Shapes.at(base, f, r, -2.5 + i * 2.5, 9.6, 4.9);
			props.lifetime(props.name("skull" + (5 + i), props.item("minecraft:skeleton_skull", at, 0.1)), end);
		}
	}

	// ------------------------------------------------------------------ the Infinite Void

	private static String starBlock(int i) {
		if (i % 11 == 0) return "minecraft:light_blue_concrete";
		if (i % 7 == 0) return "minecraft:yellow_concrete";
		return "minecraft:white_concrete";
	}

	private static void voidWorld(ServerLevel level, List<ServerPlayer> viewers, Props props, Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		double gy = c.y;
		double radius = s.radius();
		int total = s.cinema() ? 190 : 90;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// the stars come out one after the other while the dome grows
		int span = e + 24;
		if (t >= 2 && t < 2 + span) {
			int from = (t - 2) * total / span;
			int to = (t - 1) * total / span;
			for (int i = from; i < to; i++) {
				Vec3 dir = Shapes.sphereDir(i, total, false);
				if (dir.y < -0.05) continue;
				Random seeded = new Random(i * 7919L + 13);
				double size = (0.25 + seeded.nextDouble() * 0.7) * (radius / 24.0) * (i % 17 == 0 ? 2.2 : 1.0);
				Vec3 at = new Vec3(c.x + dir.x * radius * 0.93, gy + dir.y * radius * 0.93, c.z + dir.z * radius * 0.93);
				Q rot = Q.yaw(seeded.nextDouble() * 360).mul(Q.pitch(seeded.nextDouble() * 360));
				props.lifetime(props.name("s" + i, props.block(starBlock(i), at, cube(size), rot, true)), end);
			}
		}
		// the glyphs: rings of letters that turn around the loser, each at its own height and speed
		if (t >= e - 6 && t < e + h) {
			Vec3 mid = new Vec3(loser.x, gy + 1.0, loser.z);
			int k = t - e;
			ExplosionArt.ring(level, viewers, ParticleTypes.ENCHANT, mid.add(0, 0.2, 0), 4.0, 22, k * 0.11, 0);
			ExplosionArt.ring(level, viewers, ParticleTypes.ENCHANT, mid.add(0, 1.0, 0), 5.5, 28, -k * 0.09, 0.35);
			ExplosionArt.ring(level, viewers, ParticleTypes.ENCHANT, mid.add(0, 2.0, 0), 7.0, 34, k * 0.07, -0.3);
			ExplosionArt.ring(level, viewers, ParticleTypes.END_ROD, mid.add(0, 1.0, 0), 3.2, 14, -k * 0.2, 0.9);
			if (t % 3 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.REVERSE_PORTAL, mid.x, mid.y, mid.z, 6, 5.0, 2.0, 5.0, 0.3);
		}
		// a wave of pale light runs over the floor every half second
		if (t >= e && t < e + h - 20 && (t - e) % 12 == 0) {
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, new Vec3(loser.x, gy + 0.2, loser.z), 60, 0.55, 0.0);
			ExplosionArt.sound(level, loser, SoundEvents.AMETHYST_BLOCK_RESONATE, 3.0f, 0.5f + (float) rnd.nextDouble() * 0.4f);
		}
		// small lights drift in the dark
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 2; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * 18;
				ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, c.x + Math.cos(a) * rad, gy + 1 + rnd.nextDouble() * 12, c.z + Math.sin(a) * rad, 1, 0.1, 0.1, 0.1, 0.01);
			}
		}
		// the last rush: every star flies to the loser
		int rush = e + h - 44;
		if (t >= rush && t < rush + 28 && t % 2 == 0) {
			Vec3 target = new Vec3(loser.x, gy + 1.6, loser.z);
			double pull = 0.13 + 0.01 * ((t - rush) / 2);
			for (int i = 0; i < total; i++) {
				Props.Prop p = props.named("s" + i);
				if (p == null) continue;
				props.move(p, p.pos().add(target.subtract(p.pos()).scale(pull)));
			}
		}
		if (t == rush) ExplosionArt.sound(level, loser, SoundEvents.PORTAL_TRAVEL, 4.0f, 0.5f);
		if (t == rush + 30) {
			for (int i = 0; i < total; i++) props.remove(props.named("s" + i));
			Vec3 mid = new Vec3(loser.x, gy + 1.6, loser.z);
			ExplosionArt.sound(level, loser, SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 0.7f);
			ExplosionArt.sound(level, loser, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.6f);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, mid, 150, 1.8, false);
			ExplosionArt.blast(level, viewers, ParticleTypes.PORTAL, mid, 120, 2.2, false);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, new Vec3(loser.x, gy + 0.3, loser.z), 100, 1.8, 0.02);
			Props.Prop flash = props.item("minecraft:nether_star", mid, 2.0);
			props.resize(flash, 30.0, 6);
			props.lifetime(flash, t + 12);
		}
	}

	// ------------------------------------------------------------------ the Field of Swords

	private static final String[] BLADES = {"minecraft:iron_block", "minecraft:iron_block", "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block",
		"minecraft:netherite_block", "minecraft:copper_block", "minecraft:iron_block"};
	private static final String[] HILTS = {"minecraft:black_concrete", "minecraft:dark_oak_planks", "minecraft:red_concrete", "minecraft:blue_concrete"};

	/** How many swords stand in the ground at the end (the last quarter falls around the loser). */
	public static int swordCount(boolean cinema) {
		return cinema ? 110 : 44;
	}

	private static Vec3 swordSpot(Spec s, int i, int total, Random seeded) {
		Vec3 c = s.center();
		double gy = c.y;
		boolean last = i >= total * 3 / 4;
		for (int tries = 0; tries < 8; tries++) {
			double a = seeded.nextDouble() * Math.PI * 2;
			double rad;
			Vec3 origin;
			if (last) {
				origin = s.loser();
				rad = 1.8 + seeded.nextDouble() * 6.0;
			} else {
				origin = c;
				rad = 4.0 + Math.sqrt(seeded.nextDouble()) * (s.radius() - 8.0);
			}
			Vec3 at = new Vec3(origin.x + Math.cos(a) * rad, gy, origin.z + Math.sin(a) * rad);
			// not on top of the two duelists
			if (Math.hypot(at.x - s.winner().x, at.z - s.winner().z) < 2.4 || Math.hypot(at.x - s.loser().x, at.z - s.loser().z) < 1.8) continue;
			return at;
		}
		return new Vec3(c.x + s.radius() * 0.5, gy, c.z);
	}

	private static void swords(ServerLevel level, List<ServerPlayer> viewers, Props props, Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		double gy = c.y;
		int total = swordCount(s.cinema());
		int first = total * 3 / 4;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		double scale = s.cinema() ? 1.0 : 0.8;

		// the sun, low over the winner's end
		if (t == 2) {
			Vec3 back = s.forward().scale(-1);
			Vec3 at = new Vec3(c.x + back.x * (s.half() + 2), gy + s.radius() * 0.5, c.z + back.z * (s.half() + 2));
			Props.Prop sun = props.name("sun", props.item("minecraft:fire_charge", at, 4.0));
			props.lifetime(sun, end);
		}
		if (t == 3) props.resize(props.named("sun"), s.radius() * 1.1, e);

		// swords fall from the sky and stand in the ground: the first three quarters spread over the arena, the last quarter around the loser
		int a1 = 4;
		int b1 = e + h - 52;
		int a2 = e + h - 40;
		int b2 = e + h - 28;
		int from = -1;
		int to = -1;
		if (t >= a1 && t < b1) {
			from = (t - a1) * first / (b1 - a1);
			to = (t - a1 + 1) * first / (b1 - a1);
		} else if (t >= a2 && t < b2) {
			from = first + (t - a2) * (total - first) / (b2 - a2);
			to = first + (t - a2 + 1) * (total - first) / (b2 - a2);
		}
		for (int i = from; i >= 0 && i < to; i++) {
			Random seeded = new Random(i * 104729L + 7);
			Vec3 spot = swordSpot(s, i, total, seeded);
			double length = (4.0 + seeded.nextDouble() * 3.2) * scale;
			double yaw = seeded.nextDouble() * 360;
			double tilt = (seeded.nextDouble() - 0.5) * 0.5;
			double lean = (seeded.nextDouble() - 0.5) * 0.5;
			Q rot = Q.yaw(yaw).mul(Q.axis(1, 0, 0, tilt)).mul(Q.axis(0, 0, 1, lean));
			double buried = 1.0;
			double wide = 0.8 * scale;
			Vec3 blade = spot.add(rot.rotate(new Vec3(0, length / 2 - buried, 0)));
			Vec3 guard = spot.add(rot.rotate(new Vec3(0, length - buried + 0.12, 0)));
			Vec3 hilt = spot.add(rot.rotate(new Vec3(0, length - buried + 0.24 + 0.7 * scale, 0)));
			Vec3 up = new Vec3(0, 16, 0);
			props.lifetime(props.name("swa" + i, props.block(BLADES[i % BLADES.length], blade.add(up), new Vec3(0.2 * scale, length, wide), rot, true)), end);
			props.lifetime(props.name("swb" + i, props.block("minecraft:gold_block", guard.add(up), new Vec3(0.24 * scale, 0.24 * scale, wide * 2.0), rot, true)), end);
			props.lifetime(props.name("swc" + i, props.block(HILTS[i % HILTS.length], hilt.add(up), new Vec3(0.2 * scale, 1.4 * scale, 0.2 * scale), rot, true)), end);
		}
		// one tick later they drop (the client slides them down in a few ticks)
		int dropFrom = -1;
		int dropTo = -1;
		if (t - 1 >= a1 && t - 1 < b1) {
			dropFrom = (t - 1 - a1) * first / (b1 - a1);
			dropTo = (t - a1) * first / (b1 - a1);
		} else if (t - 1 >= a2 && t - 1 < b2) {
			dropFrom = first + (t - 1 - a2) * (total - first) / (b2 - a2);
			dropTo = first + (t - a2) * (total - first) / (b2 - a2);
		}
		for (int i = dropFrom; i >= 0 && i < dropTo; i++) {
			for (String key : new String[] {"swa", "swb", "swc"}) {
				Props.Prop p = props.named(key + i);
				if (p != null) props.move(p, p.pos().subtract(0, 16, 0));
			}
			Props.Prop landed = props.named("swa" + i);
			if (landed != null && i % 3 == 0) {
				Vec3 at = landed.pos();
				ExplosionArt.landing(level, viewers, new Vec3(at.x, gy, at.z));
				if (i % 6 == 0) ExplosionArt.sound(level, at, SoundEvents.ANVIL_LAND, 1.2f, 1.4f + (float) rnd.nextDouble() * 0.5f);
			}
		}
		// dusk: embers rise, ash falls, and a heat haze of sparks hangs over the field
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 3; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (s.radius() - 4);
				ExplosionArt.emit(level, viewers, k == 0 ? ParticleTypes.LAVA : (k == 1 ? ParticleTypes.ASH : ParticleTypes.FLAME), c.x + Math.cos(a) * rad,
					gy + 0.3 + rnd.nextDouble() * 6, c.z + Math.sin(a) * rad, 1, 0.2, 0.4, 0.2, 0.02);
			}
		}
		// the climax: a wave of fire runs over the field
		if (t == e + h - 26) {
			ExplosionArt.sound(level, loser, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.7f);
			ExplosionArt.sound(level, loser, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.8f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, new Vec3(loser.x, gy + 0.3, loser.z), 110, 1.6, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.LAVA, new Vec3(loser.x, gy + 0.3, loser.z), 50, 1.1, 0.3);
			ExplosionArt.blast(level, viewers, ParticleTypes.FLAME, loser.add(0, 1.2, 0), 80, 1.1, true);
		}
	}
}
