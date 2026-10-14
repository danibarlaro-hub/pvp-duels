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
 * The worlds inside the four newer Domain Expansions (the dome and the floor are drawn by {@link DomeArt}):
 * <ul>
 * <li>the Frozen Abyss: spikes of ice break out of the floor everywhere, a crystal throne rises behind the winner, snow falls, and at the
 * end a cage of giant icicles closes on the loser, freezes them in a block of ice and shatters it;</li>
 * <li>the Infernal Throne: cracks of lava run over a black floor, pillars of fire stand around the arena, a throne of blackstone rises
 * behind the winner, and at the end a geyser of fire erupts under the loser;</li>
 * <li>the Cherry Garden: a dusk of rose, cherry trees in bloom with a giant one behind the winner, petals that fall everywhere, a storm of
 * petals that turns around the loser, closes in and bursts into a thousand cuts;</li>
 * <li>the Abyssal Sea: the bottom of the ocean with shafts of light, kelp, pillars of an old temple and a whale that swims over the
 * dome, and at the end a giant wave that sweeps the arena and crashes on the loser.</li>
 * </ul>
 * Only drawing: nothing is changed in the world. {@code t} is the number of ticks since the domain started to grow; {@code e}, {@code h}
 * and {@code end} are how long the dome grows, how long it holds and the tick it is gone.
 */
final class DomeWorlds {
	private static final ParticleOptions ICE_DUST = new DustParticleOptions(0xBFEFFF, 1.4f);
	private static final ParticleOptions BUBBLE_DUST = new DustParticleOptions(0xD8F6FF, 0.9f);
	private static final ParticleOptions PETAL_DUST = new DustParticleOptions(0xFFB7D0, 1.3f);

	private DomeWorlds() {}

	// ------------------------------------------------------------------ small helpers

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private static double ease(double x) {
		double u = clamp01(x);
		return u * u * (3 - 2 * u);
	}

	private static boolean near(Vec3 a, Vec3 b, double d) {
		return Math.hypot(a.x - b.x, a.z - b.z) < d;
	}

	/** A turn that leans the up direction toward {@code dir} (a flat unit vector) by {@code angle} radians. */
	private static Q leanToward(Vec3 dir, double angle) {
		return Q.axis(dir.z, 0, -dir.x, angle);
	}

	/** One block of a built thing: its block, where it is (right, bottom, forward), its size and when it appears. */
	private record Piece(String block, double x, double bottom, double z, double sx, double sy, double sz, int batch) {}

	/** Builds the pieces of a batch (small, so they can grow one tick later), or grows them. */
	private static void build(Props props, Piece[] pieces, String key, int batch, boolean create, Vec3 base, Vec3 f, Vec3 r, Q face, int end, int grow) {
		for (int i = 0; i < pieces.length; i++) {
			Piece p = pieces[i];
			if (p.batch() != batch) continue;
			Vec3 center = Shapes.at(base, f, r, p.x(), p.bottom() + p.sy() / 2, p.z());
			if (create) props.lifetime(props.name(key + i, props.growing(p.block(), center, new Vec3(p.sx(), p.sy(), p.sz()), face, true, 1)), end);
			else props.reshape(props.named(key + i), center, new Vec3(p.sx(), p.sy(), p.sz()), face, grow);
		}
	}

	/** A column that rises from the floor: born flat, and grown by {@link #grow} one tick later. */
	private static void column(Props props, String key, String block, Vec3 foot, double width, double height, Q rot, int end) {
		Vec3 mid = foot.add(rot.rotate(new Vec3(0, height / 2, 0)));
		props.lifetime(props.name(key, props.growing(block, mid, new Vec3(width, height, width), rot, true, 1)), end);
	}

	private static void grow(Props props, String key, Vec3 foot, double width, double height, Q rot, int ticks) {
		Vec3 mid = foot.add(rot.rotate(new Vec3(0, height / 2, 0)));
		props.reshape(props.named(key), mid, new Vec3(width, height, width), rot, ticks);
	}

	// ================================================================== the Frozen Abyss

	private static final String[] ICES = {"minecraft:blue_ice", "minecraft:packed_ice", "minecraft:light_blue_stained_glass", "minecraft:blue_ice"};

	private static final class Spike {
		final Vec3 foot;
		final double width;
		final double height;
		final Q rot;

		Spike(Vec3 foot, double width, double height, Q rot) {
			this.foot = foot;
			this.width = width;
			this.height = height;
			this.rot = rot;
		}
	}

	/** The i-th spike of the floor (null when it would stand on a duelist). */
	private static Spike spike(DomeArt.Spec s, int i) {
		Random sd = new Random(i * 7919L + 31);
		double ang = sd.nextDouble() * Math.PI * 2;
		double rad = 5.0 + Math.sqrt(sd.nextDouble()) * (s.radius() - 9.0);
		Vec3 foot = new Vec3(s.center().x + Math.cos(ang) * rad, s.center().y, s.center().z + Math.sin(ang) * rad);
		if (near(foot, s.winner(), 3.2) || near(foot, s.loser(), 3.2)) return null;
		double height = (4.5 + sd.nextDouble() * 8.5) * (s.cinema() ? 1.0 : 0.8);
		double width = 0.9 + sd.nextDouble() * 1.3;
		Q rot = Q.yaw(sd.nextDouble() * 360).mul(Q.axis(1, 0, 0, (sd.nextDouble() - 0.5) * 0.5)).mul(Q.axis(0, 0, 1, (sd.nextDouble() - 0.5) * 0.5));
		return new Spike(foot, width, height, rot);
	}

	private static final int CAGE = 12;

	static void frost(ServerLevel level, List<ServerPlayer> viewers, Props props, DomeArt.Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		Vec3 f = s.forward();
		Vec3 r = Shapes.rightOf(f);
		double gy = c.y;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int n = s.cinema() ? 44 : 24;
		int span = e + h - 72;

		// spikes of ice break out of the floor, one after the other
		for (int i = 0; i < n; i++) {
			int born = 4 + i * span / n;
			if (t != born && t != born + 1) continue;
			Spike sp = spike(s, i);
			if (sp == null) continue;
			if (t == born) {
				column(props, "ice" + i, ICES[i % ICES.length], sp.foot, sp.width, sp.height, sp.rot, end);
				ExplosionArt.emit(level, viewers, ParticleTypes.CLOUD, sp.foot.x, gy + 0.3, sp.foot.z, 5, 0.5, 0.2, 0.5, 0.03);
				ExplosionArt.emit(level, viewers, ParticleTypes.SNOWFLAKE, sp.foot.x, gy + 0.6, sp.foot.z, 8, 0.6, 0.5, 0.6, 0.05);
				if (i % 3 == 0) ExplosionArt.sound(level, sp.foot, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0f, 0.6f + (float) rnd.nextDouble() * 0.5f);
			} else {
				grow(props, "ice" + i, sp.foot, sp.width, sp.height, sp.rot, 10);
			}
		}

		// the crystal throne behind the winner: six tall prisms that lean away from each other
		Vec3 base = new Vec3(s.winner().x - f.x * 8.0, gy, s.winner().z - f.z * 8.0);
		if (t == e - 10) {
			ExplosionArt.sound(level, base, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.5f);
			for (int k = 0; k < 6; k++) {
				double side = k - 2.5;
				double height = 18.0 - Math.abs(side) * 3.2;
				double width = 2.1 - Math.abs(side) * 0.25;
				Vec3 foot = Shapes.at(base, f, r, side * 2.7, 0, -Math.abs(side) * 1.1);
				Q rot = Q.axis(f.x, 0, f.z, -side * 0.09);
				column(props, "cr" + k, k % 2 == 0 ? "minecraft:blue_ice" : "minecraft:packed_ice", foot, width, height, rot, end);
				column(props, "cg" + k, "minecraft:light_blue_stained_glass", foot, width * 1.6, height * 0.8, rot, end);
			}
		}
		if (t == e - 9) {
			for (int k = 0; k < 6; k++) {
				double side = k - 2.5;
				double height = 18.0 - Math.abs(side) * 3.2;
				double width = 2.1 - Math.abs(side) * 0.25;
				Vec3 foot = Shapes.at(base, f, r, side * 2.7, 0, -Math.abs(side) * 1.1);
				Q rot = Q.axis(f.x, 0, f.z, -side * 0.09);
				grow(props, "cr" + k, foot, width, height, rot, 16);
				grow(props, "cg" + k, foot, width * 1.6, height * 0.8, rot, 16);
			}
		}

		// snow falls and the cold mist lies on the floor
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 4; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (s.radius() - 3);
				ExplosionArt.emit(level, viewers, ParticleTypes.SNOWFLAKE, c.x + Math.cos(a) * rad, gy + 4 + rnd.nextDouble() * 10, c.z + Math.sin(a) * rad, 2, 0.6, 0.4, 0.6, 0.02);
			}
			if (t % 2 == 0) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (s.radius() - 3);
				ExplosionArt.emit(level, viewers, ParticleTypes.CLOUD, c.x + Math.cos(a) * rad, gy + 0.3, c.z + Math.sin(a) * rad, 2, 1.2, 0.1, 1.2, 0.005);
			}
		}
		if (t == e) {
			ExplosionArt.shockwave(level, viewers, ParticleTypes.SNOWFLAKE, new Vec3(loser.x, gy + 0.3, loser.z), 80, 1.0, 0.0);
			ExplosionArt.sound(level, loser, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.4f);
		}

		// the cage: giant icicles come out around the loser, lean in and close
		int cage = e + h - 44;
		int close = e + h - 26;
		int cube = e + h - 12;
		int shatter = e + h - 6;
		if (t == cage || t == cage + 1) {
			for (int k = 0; k < CAGE; k++) {
				double a = k * Math.PI * 2 / CAGE;
				Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
				Vec3 foot = new Vec3(loser.x + dir.x * 6.5, gy, loser.z + dir.z * 6.5);
				Q rot = leanToward(dir.scale(-1), 0.30);
				if (t == cage) {
					column(props, "cage" + k, k % 2 == 0 ? "minecraft:blue_ice" : "minecraft:packed_ice", foot, 1.7, 11.0, rot, end);
					ExplosionArt.emit(level, viewers, ParticleTypes.CLOUD, foot.x, gy + 0.4, foot.z, 8, 0.6, 0.3, 0.6, 0.04);
				} else {
					grow(props, "cage" + k, foot, 1.7, 11.0, rot, 12);
				}
			}
			if (t == cage) ExplosionArt.sound(level, loser, SoundEvents.PLAYER_ATTACK_CRIT, 4.0f, 0.5f);
		}
		if (t == close) {
			ExplosionArt.sound(level, loser, SoundEvents.AMETHYST_BLOCK_RESONATE, 5.0f, 0.3f);
			for (int k = 0; k < CAGE; k++) {
				double a = k * Math.PI * 2 / CAGE;
				Vec3 dir = new Vec3(Math.cos(a), 0, Math.sin(a));
				Vec3 foot = new Vec3(loser.x + dir.x * 6.5, gy, loser.z + dir.z * 6.5);
				grow(props, "cage" + k, foot, 1.7, 11.0, leanToward(dir.scale(-1), 0.62), 16);
			}
		}
		if (t > close && t < cube) {
			ExplosionArt.ring(level, viewers, ParticleTypes.SNOWFLAKE, new Vec3(loser.x, gy + 5.5, loser.z), 3.0 + (cube - t) * 0.2, 24, t * 0.2, 0);
		}
		Vec3 mid = new Vec3(loser.x, gy + 1.7, loser.z);
		if (t == cube) {
			// the loser is frozen: a block of ice grows around them
			props.lifetime(props.name("block", props.block("minecraft:light_blue_stained_glass", mid, new Vec3(0.3, 0.3, 0.3), Q.ID, true)), end);
			props.lifetime(props.name("blockin", props.block("minecraft:blue_ice", mid, new Vec3(0.2, 0.2, 0.2), Q.yaw(45), true)), end);
			ExplosionArt.sound(level, loser, SoundEvents.PLAYER_ATTACK_CRIT, 5.0f, 0.4f);
		}
		if (t == cube + 1) {
			props.reshape(props.named("block"), mid, new Vec3(3.0, 4.0, 3.0), Q.ID, 4);
			props.reshape(props.named("blockin"), mid, new Vec3(1.2, 1.9, 1.2), Q.yaw(45), 4);
		}
		if (t > cube && t < shatter) {
			ExplosionArt.emit(level, viewers, ParticleTypes.SNOWFLAKE, mid, 6, 1.4, 0.05);
		}
		if (t == shatter) {
			for (int k = 0; k < CAGE; k++) props.remove(props.named("cage" + k));
			props.remove(props.named("block"));
			props.remove(props.named("blockin"));
			ExplosionArt.sound(level, loser, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.7f);
			ExplosionArt.sound(level, loser, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 1.2f);
			ExplosionArt.sound(level, loser, SoundEvents.AMETHYST_BLOCK_CHIME, 5.0f, 1.6f);
			ExplosionArt.blast(level, viewers, ParticleTypes.SNOWFLAKE, mid, 140, 1.6, false);
			ExplosionArt.blast(level, viewers, ICE_DUST, mid, 120, 1.9, false);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, mid, 90, 1.3, false);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, new Vec3(loser.x, gy + 0.3, loser.z), 90, 1.5, 0.03);
			ExplosionArt.shockwave(level, viewers, ICE_DUST, new Vec3(loser.x, gy + 0.3, loser.z), 90, 1.1, 0.03);
			ExplosionArt.debris(props, loser.add(0, 1, 0), gy, new String[] {"minecraft:blue_ice", "minecraft:packed_ice", "minecraft:light_blue_stained_glass"}, 16, 1.5, 1.7, t, 50);
			Props.Prop flash = props.item("minecraft:snowball", mid, 2.0);
			props.resize(flash, 30.0, 5);
			props.lifetime(flash, t + 9);
		}
	}

	// ================================================================== the Infernal Throne

	private static final Piece[] THRONE = {
		new Piece("minecraft:blackstone", 0, 0, 0, 12.0, 0.7, 7.0, 0),
		new Piece("minecraft:polished_blackstone", 0, 0.7, -0.2, 9.0, 0.7, 5.4, 0),
		new Piece("minecraft:blackstone", 0, 1.4, -0.4, 6.0, 0.7, 4.0, 0),
		new Piece("minecraft:polished_blackstone_bricks", 0, 2.1, -2.3, 6.0, 11.0, 1.3, 1),
		new Piece("minecraft:blackstone", 0, 2.1, -0.4, 3.2, 1.0, 3.0, 1),
		new Piece("minecraft:blackstone", -2.2, 2.1, -0.4, 0.9, 1.8, 3.0, 1),
		new Piece("minecraft:blackstone", 2.2, 2.1, -0.4, 0.9, 1.8, 3.0, 1),
		new Piece("minecraft:magma_block", 0, 3.4, -1.55, 0.9, 8.0, 0.25, 2),
		new Piece("minecraft:magma_block", -1.7, 3.4, -1.55, 0.5, 6.0, 0.25, 2),
		new Piece("minecraft:magma_block", 1.7, 3.4, -1.55, 0.5, 6.0, 0.25, 2),
		new Piece("minecraft:gold_block", 0, 13.1, -2.3, 1.4, 1.4, 1.5, 3),
		new Piece("minecraft:blackstone", -2.6, 13.1, -2.3, 1.1, 6.0, 1.1, 3),
		new Piece("minecraft:blackstone", 2.6, 13.1, -2.3, 1.1, 6.0, 1.1, 3),
		new Piece("minecraft:blackstone", -5.4, 0.7, 2.2, 0.9, 6.0, 0.9, 3),
		new Piece("minecraft:blackstone", 5.4, 0.7, 2.2, 0.9, 6.0, 0.9, 3)};

	static void inferno(ServerLevel level, List<ServerPlayer> viewers, Props props, DomeArt.Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		Vec3 f = s.forward();
		Vec3 r = Shapes.rightOf(f);
		Q face = Shapes.facing(f);
		double gy = c.y;
		double radius = s.radius();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// cracks of lava run over the floor: two segments each, from the middle out
		int cracks = s.cinema() ? 14 : 9;
		if (t == 6 || t == 7) {
			for (int k = 0; k < cracks; k++) {
				double a = k * Math.PI * 2 / cracks + 0.2;
				Vec3 p0 = new Vec3(c.x + Math.cos(a) * 3.0, gy + 0.09, c.z + Math.sin(a) * 3.0);
				Vec3 p1 = new Vec3(c.x + Math.cos(a + 0.14) * radius * 0.5, gy + 0.09, c.z + Math.sin(a + 0.14) * radius * 0.5);
				Vec3 p2 = new Vec3(c.x + Math.cos(a - 0.1) * (radius - 3.5), gy + 0.09, c.z + Math.sin(a - 0.1) * (radius - 3.5));
				if (t == 6) {
					props.lifetime(props.name("lv" + k + "a", Shapes.lineGrowing(props, "minecraft:orange_concrete", p0, p1, 0.5, true)), end);
					props.lifetime(props.name("lv" + k + "b", Shapes.lineGrowing(props, "minecraft:orange_concrete", p1, p2, 0.4, true)), end);
				} else {
					Shapes.growLine(props, props.named("lv" + k + "a"), p0, p1, 0.5, 20);
					Shapes.growLine(props, props.named("lv" + k + "b"), p1, p2, 0.4, 24);
				}
			}
			if (t == 6) ExplosionArt.sound(level, c, SoundEvents.WITHER_SPAWN, 3.0f, 0.5f);
		}

		// pillars of fire stand in a ring: a glass of orange with a core of yellow
		int pillars = s.cinema() ? 16 : 10;
		for (int i = 0; i < pillars; i++) {
			int born = 8 + i * 3;
			double a = i * Math.PI * 2 / pillars + 0.1;
			double rad = radius * 0.78;
			Vec3 foot = new Vec3(c.x + Math.cos(a) * rad, gy, c.z + Math.sin(a) * rad);
			if (t == born) {
				column(props, "fp" + i, "minecraft:orange_stained_glass", foot, 2.4, 11.0, Q.ID, end);
				column(props, "fq" + i, "minecraft:yellow_stained_glass", foot, 1.1, 11.0, Q.ID, end);
				ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, foot.x, gy + 0.5, foot.z, 14, 0.8, 0.3, 0.8, 0.08);
				if (i % 3 == 0) ExplosionArt.sound(level, foot, SoundEvents.WITHER_SPAWN, 1.5f, 1.6f);
			}
			if (t == born + 1) {
				grow(props, "fp" + i, foot, 2.4, 11.0, Q.ID, 10);
				grow(props, "fq" + i, foot, 1.1, 11.0, Q.ID, 10);
			}
			if (t >= born + 12 && t < e + h - 30 && (t + i * 3) % 14 == 0) {
				// the fire breathes
				double tall = 8.0 + rnd.nextDouble() * 8.0;
				grow(props, "fp" + i, foot, 2.4, tall, Q.ID, 12);
				grow(props, "fq" + i, foot, 1.1, tall * 0.9, Q.ID, 12);
			}
			if (t > born + 4 && t < e + h && t % 3 == i % 3) {
				ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, foot.x, gy + 2 + rnd.nextDouble() * 8, foot.z, 2, 0.7, 0.8, 0.7, 0.02);
			}
		}

		// the throne rises behind the winner in four steps
		Vec3 base = new Vec3(s.winner().x - f.x * 8.5, gy, s.winner().z - f.z * 8.5);
		for (int batch = 0; batch < 4; batch++) {
			int when = e - 12 + batch * 4;
			if (t == when) {
				build(props, THRONE, "th", batch, true, base, f, r, face, end, 0);
				ExplosionArt.sound(level, base, SoundEvents.ANVIL_LAND, 3.0f, 0.4f + batch * 0.1f);
				ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, base.x, gy + 1, base.z, 30, 3.0, 0.5, 3.0, 0.05);
			}
			if (t == when + 1) build(props, THRONE, "th", batch, false, base, f, r, face, end, 14);
		}
		if (t >= e && t < e + h && t % 2 == 0) {
			for (int k = -1; k <= 1; k += 2) {
				Vec3 fire = Shapes.at(base, f, r, k * 5.4, 6.9, 2.2);
				ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, fire.x, fire.y, fire.z, 2, 0.15, 0.2, 0.15, 0.03);
				ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, fire.x, fire.y + 0.3, fire.z, 1, 0.1, 0.2, 0.1, 0.02);
			}
		}

		// embers rise, ash falls, smoke hangs
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 4; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (radius - 3);
				ParticleOptions p = k == 0 ? ParticleTypes.LAVA : (k == 1 ? ParticleTypes.ASH : (k == 2 ? ParticleTypes.FLAME : ParticleTypes.SMOKE));
				ExplosionArt.emit(level, viewers, p, c.x + Math.cos(a) * rad, gy + 0.4 + rnd.nextDouble() * 7, c.z + Math.sin(a) * rad, 1, 0.3, 0.5, 0.3, 0.02);
			}
		}

		// the geyser: the floor under the loser glows, then fire erupts
		int warn = e + h - 34;
		int geyser = e + h - 26;
		if (t == warn) {
			ExplosionArt.sound(level, loser, SoundEvents.PORTAL_TRAVEL, 4.0f, 0.4f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, new Vec3(loser.x, gy + 0.3, loser.z), 80, 0.8, 0.0);
		}
		if (t > warn && t < geyser) {
			ExplosionArt.ring(level, viewers, ParticleTypes.FLAME, new Vec3(loser.x, gy + 0.2, loser.z), 3.5 - (t - warn) * 0.3, 24, t * 0.3, 0);
			if (t % 2 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.LAVA, loser.x, gy + 0.3, loser.z, 3, 1.5, 0.1, 1.5, 0.1);
		}
		Vec3 foot = new Vec3(loser.x, gy, loser.z);
		if (t == geyser) {
			column(props, "gs0", "minecraft:red_stained_glass", foot, 7.0, 46.0, Q.ID, t + 26);
			column(props, "gs1", "minecraft:orange_stained_glass", foot, 4.6, 46.0, Q.ID, t + 26);
			column(props, "gs2", "minecraft:yellow_concrete", foot, 2.2, 46.0, Q.ID, t + 26);
			ExplosionArt.sound(level, loser, SoundEvents.GENERIC_EXPLODE, 7.0f, 0.55f);
			ExplosionArt.sound(level, loser, SoundEvents.LIGHTNING_BOLT_THUNDER, 6.0f, 0.6f);
			ExplosionArt.sound(level, loser, SoundEvents.WITHER_SPAWN, 4.0f, 0.8f);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, loser.x, gy + 1, loser.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, new Vec3(loser.x, gy + 0.3, loser.z), 120, 1.8, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.LAVA, new Vec3(loser.x, gy + 0.3, loser.z), 60, 1.2, 0.3);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.LARGE_SMOKE, new Vec3(loser.x, gy + 0.5, loser.z), 70, 1.0, 0.05);
			ExplosionArt.blast(level, viewers, ParticleTypes.FLAME, loser.add(0, 1.5, 0), 100, 1.4, true);
			ExplosionArt.debris(props, loser.add(0, 1, 0), gy, new String[] {"minecraft:magma_block", "minecraft:netherrack", "minecraft:blackstone"}, 20, 1.6, 1.9, t, 60);
			int[] fire = {0xFF3A00, 0xFFB347, 0xFFE066};
			ExplosionArt.firework(level, loser.add(0, 18, 0), net.minecraft.world.item.component.FireworkExplosion.Shape.LARGE_BALL, fire, new int[] {0x4A1008});
		}
		if (t == geyser + 1) {
			grow(props, "gs0", foot, 7.0, 46.0, Q.ID, 6);
			grow(props, "gs1", foot, 4.6, 46.0, Q.ID, 6);
			grow(props, "gs2", foot, 2.2, 46.0, Q.ID, 6);
		}
		if (t == geyser + 14) {
			grow(props, "gs0", foot, 0.3, 46.0, Q.ID, 11);
			grow(props, "gs1", foot, 0.2, 46.0, Q.ID, 11);
			grow(props, "gs2", foot, 0.1, 46.0, Q.ID, 11);
		}
		if (t > geyser && t < geyser + 20 && t % 2 == 0) {
			ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, loser.x, gy + 2 + rnd.nextDouble() * 24, loser.z, 8, 2.0, 3.0, 2.0, 0.1);
			ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, loser.x, gy + 20 + rnd.nextDouble() * 20, loser.z, 3, 2.0, 3.0, 2.0, 0.05);
		}
	}

	// ================================================================== the Cherry Garden

	private static void tree(Props props, String key, Vec3 base, double height, double crown, double width, long seed, int end, int step) {
		Random sd = new Random(seed);
		Vec3 top = base.add(0, height + crown * 0.2, 0);
		double[][] spots = {{0, 0.45, 0}, {0.55, 0, 0.0}, {-0.55, -0.1, 0.1}, {0.05, -0.15, 0.55}, {-0.1, -0.1, -0.55}};
		if (step == 0) {
			column(props, key + "t", "minecraft:cherry_log", base, width, height + crown * 0.3, Q.ID, end);
			for (int k = 0; k < spots.length; k++) {
				Vec3 at = top.add(spots[k][0] * crown, spots[k][1] * crown, spots[k][2] * crown);
				props.lifetime(props.name(key + "l" + k, props.block("minecraft:cherry_leaves", at, new Vec3(0.3, 0.3, 0.3), Q.yaw(sd.nextDouble() * 90), true)), end);
			}
		} else if (step == 1) {
			grow(props, key + "t", base, width, height + crown * 0.3, Q.ID, 14);
		} else {
			for (int k = 0; k < spots.length; k++) {
				Vec3 at = top.add(spots[k][0] * crown, spots[k][1] * crown, spots[k][2] * crown);
				double size = crown * (k == 0 ? 0.85 : 1.0) * (0.9 + sd.nextDouble() * 0.25);
				props.reshape(props.named(key + "l" + k), at, new Vec3(size, size, size), Q.yaw(sd.nextDouble() * 90), 14);
			}
		}
	}

	private static final String[] PETALS = {"minecraft:pink_concrete", "minecraft:white_concrete", "minecraft:pink_concrete", "minecraft:magenta_concrete"};

	static void garden(ServerLevel level, List<ServerPlayer> viewers, Props props, DomeArt.Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		Vec3 f = s.forward();
		double gy = c.y;
		double radius = s.radius();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// a pale moon hangs behind the winner
		if (t == 2) {
			Vec3 back = f.scale(-1);
			Vec3 at = new Vec3(c.x + back.x * (s.half() + 2), gy + radius * 0.55, c.z + back.z * (s.half() + 2));
			props.lifetime(props.name("moon", props.item("minecraft:snowball", at, 3.0)), end);
		}
		if (t == 3) props.resize(props.named("moon"), radius * 0.8, e);

		// trees in bloom come up around the arena
		int trees = s.cinema() ? 9 : 6;
		for (int i = 0; i < trees; i++) {
			int born = 6 + i * 3;
			double a = i * Math.PI * 2 / trees + 0.35;
			double rad = radius * 0.66;
			Vec3 base = new Vec3(c.x + Math.cos(a) * rad, gy, c.z + Math.sin(a) * rad);
			double height = 7.0 + (i % 3) * 1.4;
			double crown = 4.6 + (i % 2) * 1.0;
			if (t == born) tree(props, "tr" + i, base, height, crown, 0.8, i * 31L + 5, end, 0);
			if (t == born + 1) tree(props, "tr" + i, base, height, crown, 0.8, i * 31L + 5, end, 1);
			if (t == born + 6) tree(props, "tr" + i, base, height, crown, 0.8, i * 31L + 5, end, 2);
			if (t == born + 2) ExplosionArt.emit(level, viewers, ParticleTypes.CHERRY_LEAVES, base.x, gy + height, base.z, 30, 2.5, 2.0, 2.5, 0.05);
		}
		// the great tree behind the winner
		Vec3 giant = new Vec3(s.winner().x - f.x * 11.0, gy, s.winner().z - f.z * 11.0);
		if (t == e - 12) {
			tree(props, "gt", giant, 17.0, 10.5, 2.4, 777L, end, 0);
			ExplosionArt.sound(level, giant, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.6f);
		}
		if (t == e - 11) tree(props, "gt", giant, 17.0, 10.5, 2.4, 777L, end, 1);
		if (t == e - 5) {
			tree(props, "gt", giant, 17.0, 10.5, 2.4, 777L, end, 2);
			ExplosionArt.emit(level, viewers, ParticleTypes.CHERRY_LEAVES, giant.x, gy + 17, giant.z, 120, 6.0, 4.0, 6.0, 0.08);
		}

		// petals fall everywhere
		if (t >= 4 && t < e + h) {
			for (int k = 0; k < 5; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (radius - 3);
				ExplosionArt.emit(level, viewers, ParticleTypes.CHERRY_LEAVES, c.x + Math.cos(a) * rad, gy + 5 + rnd.nextDouble() * 10, c.z + Math.sin(a) * rad, 2, 0.8, 0.5, 0.8, 0.02);
			}
		}

		// the storm of petals around the loser: it turns, rises, closes in and bursts
		int n = s.cinema() ? 60 : 36;
		int start = e - 4;
		int tighten = e + h - 34;
		int burst = e + h - 10;
		if (t == start) {
			for (int i = 0; i < n; i++) {
				props.lifetime(props.name("pt" + i, props.block(PETALS[i % PETALS.length], loser.add(0, 1.0, 0), new Vec3(0.45, 0.45, 0.45), Q.yaw(i * 37).mul(Q.pitch(i * 23)), true)), burst);
			}
			ExplosionArt.sound(level, loser, SoundEvents.NOTE_BLOCK_CHIME, 4.0f, 0.6f);
		}
		if (t > start && t < burst && t % 2 == 0) {
			int k = t - start;
			double squeeze = 1.0 - 0.85 * ease((t - tighten) / 20.0);
			double spin = k * 0.16 * (1.0 + 1.2 * ease((t - tighten) / 20.0));
			for (int i = 0; i < n; i++) {
				double ring = 3.0 + (i % 5) * 1.3;
				double a = i * Math.PI * 2 / n * 3 + spin * (1.0 + 0.15 * (i % 3));
				double rise = ((i * 0.37 + k * 0.045) % 5.0);
				Vec3 at = new Vec3(loser.x + Math.cos(a) * ring * squeeze, gy + 0.6 + rise * (0.6 + 0.4 * squeeze), loser.z + Math.sin(a) * ring * squeeze);
				props.move(props.named("pt" + i), at);
			}
			if (t % 4 == 0) ExplosionArt.ring(level, viewers, ParticleTypes.CHERRY_LEAVES, loser.add(0, 1.5, 0), 5.0 * squeeze + 0.6, 20, spin, 0.3);
		}
		if (t == tighten) ExplosionArt.sound(level, loser, SoundEvents.PORTAL_TRAVEL, 4.0f, 0.7f);
		if (t == burst) {
			for (int i = 0; i < n; i++) props.remove(props.named("pt" + i));
			Vec3 mid = loser.add(0, 1.5, 0);
			// a thousand cuts: white lines that cross the loser for a moment
			for (int k = 0; k < 9; k++) {
				double phi = k * Math.PI / 9;
				double psi = (k % 3 - 1) * 0.55;
				Vec3 dir = new Vec3(Math.cos(phi) * Math.cos(psi), Math.sin(psi), Math.sin(phi) * Math.cos(psi));
				Vec3 from = mid.subtract(dir.scale(7.5));
				Vec3 to = mid.add(dir.scale(7.5));
				props.lifetime(Shapes.line(props, "minecraft:white_concrete", from, to, 0.1, true), t + 4);
				props.lifetime(Shapes.line(props, "minecraft:pink_stained_glass", from, to, 0.45, true), t + 5);
			}
			ExplosionArt.sound(level, loser, SoundEvents.PLAYER_ATTACK_SWEEP, 5.0f, 0.8f);
			ExplosionArt.sound(level, loser, SoundEvents.PLAYER_ATTACK_STRONG, 5.0f, 0.7f);
			ExplosionArt.sound(level, loser, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 1.3f);
			ExplosionArt.sound(level, loser, SoundEvents.AMETHYST_BLOCK_CHIME, 5.0f, 1.8f);
			ExplosionArt.blast(level, viewers, ParticleTypes.CHERRY_LEAVES, mid, 200, 1.5, false);
			ExplosionArt.blast(level, viewers, PETAL_DUST, mid, 140, 1.8, false);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, mid, 80, 1.2, false);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CHERRY_LEAVES, new Vec3(loser.x, gy + 0.3, loser.z), 100, 1.6, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.SWEEP_ATTACK, new Vec3(loser.x, gy + 1.0, loser.z), 24, 0.9, 0.0);
			Props.Prop flash = props.item("minecraft:snowball", mid, 2.0);
			props.resize(flash, 28.0, 5);
			props.lifetime(flash, t + 9);
		}
	}

	// ================================================================== the Abyssal Sea

	private static final Piece[] ARCH = {
		new Piece("minecraft:prismarine_bricks", -5.0, 0, 0, 2.2, 12.0, 2.2, 0),
		new Piece("minecraft:prismarine_bricks", 5.0, 0, 0, 2.2, 12.0, 2.2, 0),
		new Piece("minecraft:dark_prismarine", 0, 12.0, 0, 13.0, 1.8, 2.6, 1),
		new Piece("minecraft:sea_lantern", -5.0, 13.8, 0, 2.6, 0.9, 2.6, 2),
		new Piece("minecraft:sea_lantern", 5.0, 13.8, 0, 2.6, 0.9, 2.6, 2),
		new Piece("minecraft:sea_lantern", 0, 13.8, 0, 3.2, 3.2, 3.2, 2)};

	/** The pieces of the whale, relative to its middle: (right, up, forward) offsets and sizes along the swimming direction. */
	private static final Piece[] WHALE = {
		new Piece("minecraft:light_gray_concrete", 0, -2.2, -1.0, 4.2, 4.4, 11.0, 0),
		new Piece("minecraft:white_concrete", 0, -2.2, -1.0, 3.6, 1.4, 9.5, 0),
		new Piece("minecraft:light_gray_concrete", 0, -1.8, 5.8, 3.8, 3.6, 4.2, 0),
		new Piece("minecraft:light_gray_concrete", 0, -1.2, -8.0, 2.6, 2.4, 6.5, 0),
		new Piece("minecraft:light_gray_concrete", 0, -0.4, -12.2, 6.8, 0.5, 2.6, 0),
		new Piece("minecraft:light_gray_concrete", 2.6, -2.6, 2.0, 0.4, 1.0, 3.6, 0),
		new Piece("minecraft:light_gray_concrete", -2.6, -2.6, 2.0, 0.4, 1.0, 3.6, 0),
		new Piece("minecraft:black_concrete", 1.9, -0.2, 6.2, 0.2, 0.5, 0.5, 0)};

	private static void sway(Props props, String key, Vec3 foot, double width, double height, double lean, double dir, int ticks) {
		Q rot = Q.axis(Math.cos(dir), 0, Math.sin(dir), lean);
		grow(props, key, foot, width, height, rot, ticks);
	}

	/** The tick the giant wave crashes on the loser. */
	static int waveHit(DomeArt.Spec s, int e, int h) {
		int wave = e + h - 56;
		double reach = s.radius() * 0.75;
		double start = -s.radius() * 0.7;
		double along = s.half();
		double u = clamp01((along - start) / (reach - start));
		return wave + (int) Math.round(u * 32);
	}

	static void ocean(ServerLevel level, List<ServerPlayer> viewers, Props props, DomeArt.Spec s, int t, int e, int h, int end) {
		Vec3 c = s.center();
		Vec3 loser = s.loser();
		Vec3 f = s.forward();
		Vec3 r = Shapes.rightOf(f);
		Q face = Shapes.facing(f);
		double gy = c.y;
		double radius = s.radius();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// shafts of light come down from the surface
		int shafts = s.cinema() ? 9 : 6;
		if (t == 4 || t == 5) {
			for (int i = 0; i < shafts; i++) {
				double a = i * Math.PI * 2 / shafts + 0.4;
				double rad = radius * (0.25 + 0.5 * ((i * 5) % shafts) / shafts);
				Vec3 top = new Vec3(c.x + Math.cos(a) * rad * 0.6, gy + radius * 0.92, c.z + Math.sin(a) * rad * 0.6);
				Vec3 bottom = new Vec3(c.x + Math.cos(a) * rad, gy + 0.1, c.z + Math.sin(a) * rad);
				if (t == 4) {
					props.lifetime(props.name("ls" + i, Shapes.lineGrowing(props, "minecraft:light_blue_stained_glass", top, bottom, 1.8, true)), end);
					props.lifetime(props.name("lw" + i, Shapes.lineGrowing(props, "minecraft:white_stained_glass", top, bottom, 0.5, true)), end);
				} else {
					Shapes.growLine(props, props.named("ls" + i), top, bottom, 1.8, 24);
					Shapes.growLine(props, props.named("lw" + i), top, bottom, 0.5, 24);
				}
			}
		}

		// kelp sways on the floor
		int kelp = s.cinema() ? 26 : 14;
		for (int i = 0; i < kelp; i++) {
			Random sd = new Random(i * 6151L + 9);
			double a = sd.nextDouble() * Math.PI * 2;
			double rad = 7.0 + Math.sqrt(sd.nextDouble()) * (radius - 11.0);
			Vec3 foot = new Vec3(c.x + Math.cos(a) * rad, gy, c.z + Math.sin(a) * rad);
			if (near(foot, s.winner(), 2.5) || near(foot, loser, 2.5)) continue;
			double height = 5.0 + sd.nextDouble() * 6.0;
			int born = 6 + i * (e - 6) / kelp;
			String block = i % 3 == 0 ? "minecraft:lime_concrete" : "minecraft:green_concrete";
			if (t == born) column(props, "kp" + i, block, foot, 0.5, height, Q.ID, end);
			if (t == born + 1) grow(props, "kp" + i, foot, 0.5, height, Q.ID, 14);
			if (t > born + 16 && t < e + h - 30 && (t + i * 5) % 20 == 0) sway(props, "kp" + i, foot, 0.5, height, (sd.nextDouble() - 0.3) * 0.22, i * 0.9 + t * 0.1, 20);
		}

		// the pillars of an old temple, and its arch behind the winner
		int pillars = s.cinema() ? 6 : 4;
		for (int i = 0; i < pillars; i++) {
			int born = 10 + i * 4;
			double a = i * Math.PI * 2 / pillars + 0.9;
			Vec3 foot = new Vec3(c.x + Math.cos(a) * radius * 0.6, gy, c.z + Math.sin(a) * radius * 0.6);
			double height = 6.0 + (i % 3) * 1.8;
			if (t == born) {
				column(props, "pl" + i, "minecraft:prismarine_bricks", foot, 1.6, height, Q.ID, end);
				props.lifetime(props.name("pt" + i, props.block("minecraft:sea_lantern", foot.add(0, height + 0.4, 0), new Vec3(0.3, 0.3, 0.3), Q.ID, true)), end);
				ExplosionArt.sound(level, foot, SoundEvents.ANVIL_LAND, 2.0f, 0.5f);
			}
			if (t == born + 1) grow(props, "pl" + i, foot, 1.6, height, Q.ID, 14);
			if (t == born + 6) props.reshape(props.named("pt" + i), foot.add(0, height + 0.4, 0), new Vec3(2.0, 0.8, 2.0), Q.ID, 10);
		}
		Vec3 arch = new Vec3(s.winner().x - f.x * 9.5, gy, s.winner().z - f.z * 9.5);
		for (int batch = 0; batch < 3; batch++) {
			int when = e - 12 + batch * 4;
			if (t == when) {
				build(props, ARCH, "ar", batch, true, arch, f, r, face, end, 0);
				ExplosionArt.sound(level, arch, SoundEvents.ANVIL_LAND, 3.0f, 0.4f + batch * 0.1f);
			}
			if (t == when + 1) build(props, ARCH, "ar", batch, false, arch, f, r, face, end, 14);
		}

		// the whale swims across the dome, high over the arena, from the left to the right
		int swimFrom = e - 6;
		int swimTo = e + h - 24;
		Vec3 lane = new Vec3(c.x - f.x * s.half() * 0.2, gy + radius * 0.58, c.z - f.z * s.half() * 0.2);
		Q swimFace = Shapes.facing(r);
		if (t == swimFrom) {
			Vec3 start = lane.subtract(r.scale(radius * 0.8));
			for (int i = 0; i < WHALE.length; i++) {
				Piece p = WHALE[i];
				Vec3 at = Shapes.at(start, r, f.scale(-1), p.x(), p.bottom() + p.sy() / 2, p.z());
				props.lifetime(props.name("wh" + i, props.block(p.block(), at, new Vec3(p.sx(), p.sy(), p.sz()), swimFace, true)), end);
			}
			ExplosionArt.sound(level, lane, SoundEvents.AMETHYST_BLOCK_RESONATE, 5.0f, 0.3f);
		}
		if (t > swimFrom && t <= swimTo && t % 2 == 0) {
			double u = (t - swimFrom) / (double) (swimTo - swimFrom);
			Vec3 at0 = lane.add(r.scale(radius * (-0.8 + 1.6 * u))).add(0, Math.sin(t * 0.09) * 1.2, 0);
			for (int i = 0; i < WHALE.length; i++) {
				Piece p = WHALE[i];
				double wag = i == 4 ? Math.sin(t * 0.18) * 0.9 : 0;
				props.move(props.named("wh" + i), Shapes.at(at0, r, f.scale(-1), p.x(), p.bottom() + p.sy() / 2 + wag, p.z()));
			}
		}
		if (t == swimTo + 1) {
			for (int i = 0; i < WHALE.length; i++) props.remove(props.named("wh" + i));
		}

		// rising bubbles and drifting plankton
		if (t >= 4 && t < e + h) {
			for (int j = 0; j < 10; j++) {
				Random sd = new Random(j * 4099L + 3);
				double a = sd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(sd.nextDouble()) * (radius - 4);
				double y = (t * (0.28 + 0.03 * (j % 3)) + j * 2.3) % (radius * 0.8);
				ExplosionArt.emit(level, viewers, BUBBLE_DUST, c.x + Math.cos(a) * rad, gy + 0.5 + y, c.z + Math.sin(a) * rad, 1, 0.1, 0.05, 0.1, 0.0);
			}
			if (t % 2 == 0) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double rad = Math.sqrt(rnd.nextDouble()) * (radius - 3);
				ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, c.x + Math.cos(a) * rad, gy + 1 + rnd.nextDouble() * 14, c.z + Math.sin(a) * rad, 1, 0.2, 0.2, 0.2, 0.005);
			}
		}

		// the giant wave: panes of water sweep over the arena along the way of the duel
		int wave = e + h - 56;
		int dur = 32;
		int panes = 11;
		double ext = radius * 0.6;
		double from = -radius * 0.7;
		double reach = radius * 0.75;
		int hit = waveHit(s, e, h);
		if (t == wave) {
			ExplosionArt.sound(level, c, SoundEvents.PORTAL_TRAVEL, 5.0f, 0.4f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 0.5f);
			for (int j = 0; j < panes; j++) {
				double lateral = (j - (panes - 1) / 2.0) * (2 * ext / (panes - 1));
				Vec3 at = Shapes.at(c, f, r, lateral, 3.6, from - lateral * lateral * 0.01);
				double wide = 2 * ext / (panes - 1) + 0.8;
				props.lifetime(props.name("wv" + j, props.block("minecraft:light_blue_stained_glass", at, new Vec3(wide, 7.2, 1.0), face, true)), wave + dur + 1);
				props.lifetime(props.name("wc" + j, props.block("minecraft:white_stained_glass", at.add(0, 3.9, 0), new Vec3(wide, 0.9, 1.6), face, true)), wave + dur + 1);
			}
		}
		if (t > wave && t <= wave + dur && t % 2 == 0) {
			double u = (t - wave) / (double) dur;
			double x = from + (reach - from) * u;
			for (int j = 0; j < panes; j++) {
				double lateral = (j - (panes - 1) / 2.0) * (2 * ext / (panes - 1));
				double z = x - lateral * lateral * 0.01;
				Vec3 at = Shapes.at(c, f, r, lateral, 3.6, z);
				props.move(props.named("wv" + j), at);
				props.move(props.named("wc" + j), at.add(0, 3.9, 0));
				if (j % 2 == 0) {
					ExplosionArt.emit(level, viewers, ParticleTypes.CLOUD, at.x, at.y + 4.2, at.z, 3, 0.8, 0.3, 0.8, 0.05);
					ExplosionArt.emit(level, viewers, ParticleTypes.RAIN, at.x, at.y + 1, at.z, 5, 1.0, 2.0, 1.0, 0.1);
				}
			}
		}
		if (t == hit) {
			Vec3 mid = new Vec3(loser.x, gy + 1.5, loser.z);
			ExplosionArt.sound(level, loser, SoundEvents.GENERIC_EXPLODE, 7.0f, 0.5f);
			ExplosionArt.sound(level, loser, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 0.8f);
			ExplosionArt.sound(level, loser, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.7f);
			ExplosionArt.blast(level, viewers, ParticleTypes.RAIN, mid, 160, 1.8, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.CLOUD, mid, 120, 1.5, true);
			ExplosionArt.blast(level, viewers, BUBBLE_DUST, mid, 120, 1.2, false);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, new Vec3(loser.x, gy + 0.3, loser.z), 100, 1.7, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.RAIN, new Vec3(loser.x, gy + 0.4, loser.z), 100, 1.3, 0.1);
			ExplosionArt.debris(props, loser.add(0, 1, 0), gy, new String[] {"minecraft:prismarine", "minecraft:sea_lantern", "minecraft:dark_prismarine"}, 14, 1.5, 1.8, t, 50);
			Props.Prop flash = props.item("minecraft:snowball", mid, 2.0);
			props.resize(flash, 26.0, 5);
			props.lifetime(flash, t + 9);
		}
		if (t > hit && t < hit + 24 && t % 2 == 0) {
			// the water swirls around where the wave broke
			ExplosionArt.ring(level, viewers, ParticleTypes.CLOUD, new Vec3(loser.x, gy + 0.6 + (t - hit) * 0.08, loser.z), 2.0 + (t - hit) * 0.2, 24, (t - hit) * 0.5, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.RAIN, loser.x, gy + 1.5, loser.z, 6, 2.0, 1.5, 2.0, 0.1);
		}
	}
}
