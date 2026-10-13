package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The drawing of the big explosions: the two Orbital cannons, the Shooting Star, the Black Hole and the Meteor Shower. Only light and
 * sound: nothing is damaged and nothing is broken. {@code t} is the number of ticks since the explosion started.
 * <p>
 * Each one is built in layers. The body is made of display entities ({@link Props}): giant beams, spheres of rotated blocks, spinning
 * disks, fireballs and flying debris, all full-bright. Around it come the particles (fire, smoke, sparks) and, for the shock waves, particles
 * that the client moves by itself. On top, real fireworks burst in the sky. Every particle goes through {@link Quality}, so each player
 * sees as many as they chose.
 */
public final class ExplosionArt {
	/** A meteor of the shower: where it starts, where it lands, when it started, how long it flies and whether it is the big last one. */
	public static final class Meteor {
		final Vec3 start;
		final Vec3 end;
		final int born;
		final int flight;
		final boolean big;
		Vec3 dir = new Vec3(0, -1, 0);
		Props.Prop body;
		Props.Prop halo;
		Props.Prop tail;
		Props.Prop tail2;

		Meteor(Vec3 start, Vec3 end, int born, int flight, boolean big) {
			this.start = start;
			this.end = end;
			this.born = born;
			this.flight = flight;
			this.big = big;
		}
	}

	/** The Orbital Strike: the targeting lasts this long and then the beam hits. */
	public static final int ORBITAL_SLAM = 16;
	/** How long the Shooting Star flies before it lands. */
	public static final int STAR_FLIGHT = 36;
	/** How long the long star of the cinematic finale flies, and how long the camera stays after it lands. */
	public static final int CINEMATIC_FLIGHT = 84;
	public static final int CINEMATIC_AFTER = 90;
	/** The Black Hole collapses at this tick and bursts a few ticks later. */
	public static final int HOLE_COLLAPSE = 72;
	public static final int HOLE_BURST = 80;
	/** The Meteor Shower: when the last small meteor is born and when the big one is. */
	public static final int SHOWER_END = 56;
	public static final int SHOWER_BIG = 58;
	/** How long the big meteor takes to fall. */
	public static final int SHOWER_BIG_FLIGHT = 24;

	static final ParticleOptions GOLD = new DustParticleOptions(0xFFD54A, 1.6f);
	static final ParticleOptions ICE = new DustParticleOptions(0x8FD3FF, 1.5f);
	static final ParticleOptions VIOLET = new DustParticleOptions(0xB06CFF, 1.5f);
	static final ParticleOptions VOID = new DustParticleOptions(0x050505, 3.0f);
	private static final ParticleOptions DISK_A = new DustParticleOptions(0xFFB347, 1.6f);
	private static final ParticleOptions DISK_B = new DustParticleOptions(0xFF5A1F, 1.7f);
	private static final ParticleOptions ARM = new DustParticleOptions(0x5A2A8C, 1.5f);
	static final ParticleOptions SIGHT_RED = new DustParticleOptions(0xFF2A2A, 1.8f);
	private static final ParticleOptions SIGHT_PURPLE = new DustParticleOptions(0xB04CFF, 1.8f);

	private static final String[] GROUND_PIECES = {"minecraft:grass_block", "minecraft:dirt", "minecraft:stone", "minecraft:cobblestone", "minecraft:coarse_dirt"};

	private ExplosionArt() {}

	// ------------------------------------------------------------------ small helpers

	static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) Quality.send(level, v, options, x, y, z, count, dx, dy, dz, speed);
	}

	static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 at, int count, double spread, double speed) {
		emit(level, viewers, options, at.x, at.y, at.z, count, spread, spread, spread, speed);
	}

	/** One particle that the client moves by itself from {@code at} along {@code dir}. */
	static void fly(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 at, Vec3 dir, double speed) {
		for (ServerPlayer v : viewers) Quality.sendFlying(level, v, options, at.x, at.y, at.z, dir.x, dir.y, dir.z, speed);
	}

	static void sound(ServerLevel level, Vec3 at, SoundEvent event, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
	}

	static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> event, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
	}

	/** A circle (tilted around the x axis by {@code tilt} radians) around {@code center}. */
	static void ring(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 center, double radius, int points, double turn, double tilt) {
		double cos = Math.cos(tilt);
		double sin = Math.sin(tilt);
		for (int k = 0; k < points; k++) {
			double a = turn + k * Math.PI * 2 / points;
			double x = Math.cos(a) * radius;
			double z = Math.sin(a) * radius;
			emit(level, viewers, options, center.x + x, center.y + z * sin, center.z + z * cos, 1, 0.03, 0.03, 0.03, 0.0);
		}
	}

	/** A ring on the ground that spreads: every particle flies outward by itself. */
	static void shockwave(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 center, int points, double speed, double lift) {
		for (int k = 0; k < points; k++) {
			double a = k * Math.PI * 2 / points;
			fly(level, viewers, options, center, new Vec3(Math.cos(a), lift, Math.sin(a)), speed);
		}
	}

	/** An even spread of directions over a sphere (or only its upper half). */
	static Vec3 sphereDir(int i, int n, boolean upward) {
		double y = upward ? (i + 0.5) / n : 1 - 2 * (i + 0.5) / n;
		double r = Math.sqrt(Math.max(0, 1 - y * y));
		double a = i * 2.399963229728653;
		return new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
	}

	/** A ball of particles that fly out in every direction, each at a slightly different speed. */
	static void blast(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 center, int points, double speed, boolean upward) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < points; i++) {
			fly(level, viewers, options, center, sphereDir(i, points, upward), speed * (0.55 + 0.45 * rnd.nextDouble()));
		}
	}

	static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	static Q randomRotation() {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		return Q.yaw(rnd.nextDouble() * 360).mul(Q.pitch(rnd.nextDouble() * 360));
	}

	/** Pieces of the ground or of the blast thrown up and out; they fall and disappear when they land (or after {@code ttl} ticks). */
	static void debris(Props props, Vec3 at, double floorY, String[] blocks, int count, double speed, double lift, int t, int ttl) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < count; i++) {
			double size = 0.45 + rnd.nextDouble() * 0.75;
			Props.Prop p = props.block(blocks[rnd.nextInt(blocks.length)], at, cube(size), randomRotation(), true);
			double a = rnd.nextDouble() * Math.PI * 2;
			double sp = speed * (0.4 + 0.6 * rnd.nextDouble());
			props.throwPiece(p, new Vec3(Math.cos(a) * sp, lift * (0.6 + 0.6 * rnd.nextDouble()), Math.sin(a) * sp), floorY);
			props.lifetime(p, t + ttl);
		}
	}

	static void firework(ServerLevel level, Vec3 at, FireworkExplosion.Shape shape, int[] colors, int[] fades) {
		Props.burst(level, at, shape, colors, fades, true, true);
	}

	/** Where something that was thrown hit the ground: a puff of dust. */
	public static void landing(ServerLevel level, List<ServerPlayer> viewers, Vec3 at) {
		emit(level, viewers, ParticleTypes.POOF, at.x, at.y + 0.2, at.z, 2, 0.25, 0.1, 0.25, 0.02);
	}

	// ------------------------------------------------------------------ the Orbital cannons

	/**
	 * A red sight closes in on the target, then a giant beam slams down from the sky: a white-hot core inside two layers of glass, a
	 * fireball, a shock wave, flying rock, rising smoke, and fireworks bursting above. {@code wither} is the dark version with soul fire.
	 */
	public static void orbital(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean wither) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		ParticleOptions fire = wither ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
		ParticleOptions smoke = wither ? ParticleTypes.SQUID_INK : ParticleTypes.LARGE_SMOKE;
		ParticleOptions spark = wither ? ParticleTypes.SOUL : ParticleTypes.END_ROD;
		ParticleOptions sight = wither ? SIGHT_PURPLE : SIGHT_RED;
		Vec3 base = c.add(0, 0.2, 0);
		Vec3 middle = c.add(0, 90, 0);

		if (t == 0) {
			sound(level, c.add(0, 20, 0), wither ? SoundEvents.WITHER_SPAWN : SoundEvents.BEACON_ACTIVATE, 5.0f, wither ? 1.3f : 0.7f);
			props.name("laser", props.block(wither ? "minecraft:purple_stained_glass" : "minecraft:red_stained_glass", c.add(0, 45, 0), new Vec3(0.14, 90, 0.14), Q.ID, true));
		}
		if (t < ORBITAL_SLAM) {
			// the sight closes in
			double k = 1.0 - t / (double) ORBITAL_SLAM;
			double radius = 1.6 + 9.0 * k;
			ring(level, viewers, sight, base, radius, 40, t * 0.3, 0);
			ring(level, viewers, sight, base, radius * 0.55, 24, -t * 0.4, 0);
			for (int s = 0; s < 4; s++) {
				double a = t * 0.3 + s * Math.PI / 2;
				for (int q = 1; q <= 4; q++) emit(level, viewers, sight, base.x + Math.cos(a) * radius * q / 4, base.y, base.z + Math.sin(a) * radius * q / 4, 1, 0.02, 0.02, 0.02, 0);
			}
			if (t == ORBITAL_SLAM - 3) props.reshape(props.named("laser"), c.add(0, 45, 0), new Vec3(0.55, 90, 0.55), Q.ID, 3);
			return;
		}
		int w = t - ORBITAL_SLAM;
		if (w == 0) {
			props.remove(props.named("laser"));
			String coreBlock = wither ? "minecraft:black_concrete" : "minecraft:white_concrete";
			String midBlock = wither ? "minecraft:purple_stained_glass" : "minecraft:light_blue_stained_glass";
			String outerBlock = wither ? "minecraft:blue_stained_glass" : "minecraft:cyan_stained_glass";
			props.name("core", props.block(coreBlock, middle, new Vec3(1.6, 180, 1.6), Q.ID, true));
			props.name("mid", props.block(midBlock, middle, new Vec3(3.8, 180, 3.8), Q.ID, true));
			props.name("outer", props.block(outerBlock, middle, new Vec3(7.0, 180, 7.0), Q.ID, true));
			props.name("ball", props.item(wither ? "minecraft:wither_skeleton_skull" : "minecraft:fire_charge", c.add(0, 3, 0), 6.0));
			props.resize(props.named("ball"), 20.0, 6);
			props.lifetime(props.named("ball"), t + 26);
			sound(level, c, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.55f);
			sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.7f);
			sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 0.9f);
			sound(level, c, wither ? SoundEvents.ENDER_DRAGON_GROWL : SoundEvents.BEACON_ACTIVATE, 3.0f, 0.5f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.SONIC_BOOM, c.x, c.y + 2, c.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.GUST_EMITTER_LARGE, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
			shockwave(level, viewers, fire, base, 96, 1.5, 0.02);
			shockwave(level, viewers, smoke, base, 64, 0.9, 0.05);
			shockwave(level, viewers, spark, base, 96, 2.1, 0.04);
			blast(level, viewers, spark, c.add(0, 1, 0), 70, 1.3, true);
			blast(level, viewers, fire, c.add(0, 1, 0), 50, 0.9, true);
			debris(props, c.add(0, 1, 0), c.y, wither ? new String[] {"minecraft:crying_obsidian", "minecraft:blackstone"} : new String[] {"minecraft:magma_block", "minecraft:netherrack"},
				14, 1.3, 1.5, t, 60);
			int[] bright = wither ? new int[] {0x0A0A0A, 0x7A28C8, 0x37E6E6} : new int[] {0xFFFFFF, 0xFFB347, 0xFFE066};
			int[] fade = wither ? new int[] {0x2B0A4D} : new int[] {0xFF5A1F};
			firework(level, c.add(0, 12, 0), FireworkExplosion.Shape.LARGE_BALL, bright, fade);
			firework(level, c.add(6, 15, 3), FireworkExplosion.Shape.STAR, bright, fade);
			firework(level, c.add(-6, 15, -3), FireworkExplosion.Shape.BURST, bright, fade);
		}
		if (w == 6) {
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, c.x + 3, c.y + 1, c.z - 2, 1, 0, 0, 0, 0);
			shockwave(level, viewers, fire, base, 72, 1.1, 0.02);
			sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.8f);
		}
		if (w == 12) {
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, c.x - 3, c.y + 1, c.z + 3, 1, 0, 0, 0, 0);
			// the beam narrows
			props.reshape(props.named("core"), middle, new Vec3(1.0, 180, 1.0), Q.ID, 20);
			props.reshape(props.named("mid"), middle, new Vec3(2.4, 180, 2.4), Q.ID, 20);
			props.reshape(props.named("outer"), middle, new Vec3(4.4, 180, 4.4), Q.ID, 20);
			props.resize(props.named("ball"), 1.0, 14);
		}
		if (w == 34) {
			props.reshape(props.named("core"), middle, new Vec3(0.05, 180, 0.05), Q.ID, 14);
			props.reshape(props.named("mid"), middle, new Vec3(0.1, 180, 0.1), Q.ID, 14);
			props.reshape(props.named("outer"), middle, new Vec3(0.2, 180, 0.2), Q.ID, 14);
			props.lifetime(props.named("core"), t + 15);
			props.lifetime(props.named("mid"), t + 15);
			props.lifetime(props.named("outer"), t + 15);
		}
		if (w < 36) {
			// sparks along the beam, fire and smoke at its foot
			emit(level, viewers, spark, c.x, c.y + 6 + rnd.nextDouble() * 30, c.z, 6, 1.4, 6.0, 1.4, 0.04);
			emit(level, viewers, fire, c.x, c.y + 0.6, c.z, 8, 1.8, 0.6, 1.8, 0.05);
			emit(level, viewers, smoke, c.x, c.y + 1.5, c.z, 3, 1.6, 0.8, 1.6, 0.04);
		}
		if (w < 90 && w % 2 == 0) {
			// the column of smoke that rises for a long time
			emit(level, viewers, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, c.x, c.y + 0.4, c.z, 1, 1.2, 0.1, 1.2, 0.02);
			emit(level, viewers, smoke, c.x, c.y + 1.0, c.z, 2, 1.4, 0.5, 1.4, 0.03);
		}
	}

	// ------------------------------------------------------------------ the Shooting Star

	private static Vec3 starAt(Vec3 start, Vec3 end, double u) {
		double e = Math.pow(Math.max(0, Math.min(1, u)), 1.6); // it speeds up as it falls
		return start.add(end.subtract(start).scale(e));
	}

	private static Vec3 flatDirection(Vec3 from, Vec3 to) {
		Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
		return flat.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : flat.normalize();
	}

	/** The five chords of the star drawn on the ground: from every point to the second one after it. */
	private static void pentagram(Props props, Vec3 target, double turn, boolean create, int ticks) {
		double radius = 5.6;
		for (int line = 0; line < 5; line++) {
			double a1 = turn + line * Math.PI * 2 / 5;
			double a2 = turn + (line + 2) * Math.PI * 2 / 5;
			Vec3 p1 = new Vec3(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
			Vec3 p2 = new Vec3(Math.cos(a2) * radius, 0, Math.sin(a2) * radius);
			Vec3 mid = target.add((p1.x + p2.x) / 2, 0.12, (p1.z + p2.z) / 2);
			Vec3 size = new Vec3(0.45, 0.14, p1.distanceTo(p2));
			Q rot = Q.fromZ(p2.subtract(p1));
			if (create) props.name("chord" + line, props.block(line % 2 == 0 ? "minecraft:yellow_stained_glass" : "minecraft:light_blue_stained_glass", mid, size, rot, true));
			else props.reshape(props.named("chord" + line), mid, size, rot, ticks);
		}
	}

	/** How a Shooting Star looks and how long it flies: the quick one of a normal win and the long one of the cinematic finale. */
	private record StarLook(int flight, double distance, double height, double scale, double tail, boolean cinema) {}

	private static final StarLook QUICK_STAR = new StarLook(STAR_FLIGHT, 80, 62, 1.0, 0.6, false);
	/**
	 * The long star starts as far away as the server sends things to the players (the view distance of the server, less a margin for the
	 * distance between the camera and the landing), never farther than 130 blocks. A star born in a chunk that nobody has loaded could
	 * not be made at all.
	 */
	private static StarLook cinematicLook(ServerLevel level) {
		int view = level.getServer().getPlayerList().getViewDistance();
		double distance = Math.max(40, Math.min(130, view * 16 - 24));
		return new StarLook(CINEMATIC_FLIGHT, distance, distance * 0.65, 1.8, 1.5, true);
	}

	private static Vec3 starStart(StarLook look, Vec3 from, Vec3 target) {
		return target.add(flatDirection(from, target).scale(-look.distance)).add(0, look.height, 0);
	}

	/** Where the long star of the cinematic finale is born (high in the sky behind the winner). */
	public static Vec3 cinematicStarStart(ServerLevel level, Vec3 from, Vec3 target) {
		return starStart(cinematicLook(level), from, target);
	}

	/** Where the head of the long star is {@code t} ticks after it was born (the camera of the finale follows it). */
	public static Vec3 cinematicStarHead(ServerLevel level, Vec3 from, Vec3 target, int t) {
		double u = Math.max(0, Math.min(1, t / (double) CINEMATIC_FLIGHT));
		return starAt(starStart(cinematicLook(level), from, target), target, u);
	}

	/**
	 * A star (a flat picture of a nether star) with a long glowing tail crosses the sky from behind the winner and lands on the target:
	 * a white flash, a pillar of light made of beams, a nova, a pentagram of light on the ground with a ring around it, shock waves, star
	 * fireworks and star dust that falls for a while.
	 */
	public static void shootingStar(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 from, Vec3 target, int t) {
		star(level, viewers, props, from, target, t, QUICK_STAR);
	}

	/**
	 * The same star for the cinematic finale: it is born much higher and farther, grows out of a glint, takes {@link #CINEMATIC_FLIGHT}
	 * ticks to cross the sky (slowly at first, then faster and faster), is twice as big, and the ground draws converging rings before
	 * it lands. The camera of the finale follows it.
	 */
	public static void cinematicStar(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 from, Vec3 target, int t) {
		star(level, viewers, props, from, target, t, cinematicLook(level));
	}

	private static void star(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 from, Vec3 target, int t, StarLook look) {
		int flight = look.flight();
		double sc = look.scale();
		Vec3 start = starStart(look, from, target);
		Vec3 way = target.subtract(start).normalize();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) {
			sound(level, look.cinema() ? start : target.add(0, 25, 0), SoundEvents.FIREWORK_ROCKET_LAUNCH, 5.0f, 0.6f);
			sound(level, look.cinema() ? start : target.add(0, 25, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 5.0f, 0.5f);
			double halo = 20.0 * sc;
			double body = 10.0 * sc;
			props.name("halo", props.item("minecraft:firework_star", start, look.cinema() ? 1.0 : halo));
			props.name("star", props.item("minecraft:nether_star", start, look.cinema() ? 0.5 : body));
			if (look.cinema()) {
				// it grows out of a glint
				props.resize(props.named("halo"), halo, 16);
				props.resize(props.named("star"), body, 16);
			}
			props.name("tail0", props.block("minecraft:white_concrete", start.subtract(way.scale(14 * sc)), new Vec3(0.35 * sc, 0.35 * sc, 28 * sc), Q.fromZ(way), true));
			props.name("tail1", props.block("minecraft:yellow_stained_glass", start.subtract(way.scale(11 * sc)), new Vec3(1.0 * sc, 1.0 * sc, 22 * sc), Q.fromZ(way), true));
			props.name("tail2", props.block("minecraft:light_blue_stained_glass", start.subtract(way.scale(8 * sc)), new Vec3(2.0 * sc, 2.0 * sc, 16 * sc), Q.fromZ(way), true));
		}
		if (t < flight) {
			double u = t / (double) flight;
			Vec3 head = starAt(start, target, u);
			if (t % 2 == 0) {
				props.move(props.named("star"), head);
				props.move(props.named("halo"), head);
				props.move(props.named("tail0"), head.subtract(way.scale(14 * sc)));
				props.move(props.named("tail1"), head.subtract(way.scale(11 * sc)));
				props.move(props.named("tail2"), head.subtract(way.scale(8 * sc)));
			}
			emit(level, viewers, ParticleTypes.END_ROD, head, 12, 0.5 * sc, 0.03);
			emit(level, viewers, ParticleTypes.FIREWORK, head, 6, 0.8 * sc, 0.06);
			// the tail: gold close to the star, then blue, then violet
			for (int k = 1; k <= 26; k += 2) {
				double back = u - k * look.tail() / flight;
				if (back < 0) break;
				Vec3 p = starAt(start, target, back);
				ParticleOptions o = k < 8 ? GOLD : (k < 17 ? ICE : VIOLET);
				emit(level, viewers, o, p.x, p.y, p.z, 2, 0.15 + k * 0.014 * sc, 0.15 + k * 0.014 * sc, 0.15 + k * 0.014 * sc, 0.0);
				if (k % 3 == 1) emit(level, viewers, ParticleTypes.END_ROD, p, 1, 0.25 * sc, 0.0);
			}
			if (look.cinema()) {
				// a rising whistle, and rings on the ground that close in on the place where it will land
				if (t % 8 == 0) sound(level, head, SoundEvents.FIREWORK_ROCKET_LAUNCH, 6.0f, (float) (0.5 + 1.2 * u));
				if (t % 16 == 0 && t > 0) sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, (float) (0.6 + 0.9 * u));
				int left = flight - t;
				if (left <= 30) {
					double radius = 3.0 + left * 0.3;
					ring(level, viewers, ParticleTypes.END_ROD, target.add(0, 0.25, 0), radius, 40, t * 0.2, 0);
					if (left <= 16) ring(level, viewers, GOLD, target.add(0, 0.25, 0), radius * 0.6, 28, -t * 0.25, 0);
				}
			}
			return;
		}
		int w = t - flight;
		boolean cine = look.cinema();
		double fat = cine ? 1.35 : 1.0;
		Vec3 ground = target.add(0, 0.3, 0);
		Vec3 pillarMiddle = target.add(0, 70, 0);
		if (w == 0) {
			props.remove(props.named("star"));
			props.remove(props.named("halo"));
			props.remove(props.named("tail0"));
			props.remove(props.named("tail1"));
			props.remove(props.named("tail2"));
			sound(level, target, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.9f);
			sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0f, 0.7f);
			sound(level, target, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 5.0f, 0.8f);
			sound(level, target, SoundEvents.AMETHYST_BLOCK_CHIME, 5.0f, 1.3f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.SONIC_BOOM, target.x, target.y + 2, target.z, 1, 0, 0, 0, 0);
			// the pillar of light: a white core in two layers of glass
			props.name("pillar0", props.block("minecraft:white_concrete", pillarMiddle, new Vec3(1.2 * fat, 140, 1.2 * fat), Q.ID, true));
			props.name("pillar1", props.block("minecraft:yellow_stained_glass", pillarMiddle, new Vec3(3.2 * fat, 140, 3.2 * fat), Q.ID, true));
			props.name("pillar2", props.block("minecraft:light_blue_stained_glass", pillarMiddle, new Vec3(5.8 * fat, 140, 5.8 * fat), Q.ID, true));
			// the nova
			props.name("nova", props.item("minecraft:nether_star", target.add(0, 3, 0), 4.0));
			props.resize(props.named("nova"), 28.0 * fat, 8);
			props.lifetime(props.named("nova"), t + 22);
			// the star on the ground, and the ring around it
			pentagram(props, target, 0, true, 0);
			for (int k = 0; k < 18; k++) {
				double a = k * Math.PI * 2 / 18;
				double radius = 5.9;
				Vec3 at = target.add(Math.cos(a) * radius, 0.12, Math.sin(a) * radius);
				props.name("rim" + k, props.block("minecraft:yellow_stained_glass", at, new Vec3(0.4, 0.14, 2.1), Q.fromZ(new Vec3(-Math.sin(a), 0, Math.cos(a))), true));
			}
			shockwave(level, viewers, ParticleTypes.END_ROD, ground, 120, 1.7, 0.02);
			shockwave(level, viewers, GOLD, ground, 90, 1.1, 0.03);
			shockwave(level, viewers, ICE, ground, 72, 0.7, 0.04);
			blast(level, viewers, ParticleTypes.FIREWORK, target.add(0, 1, 0), 60, 1.2, true);
			if (cine) {
				// the cinematic landing is bigger: a second blast, a violet wave and a ball of sparks
				shockwave(level, viewers, VIOLET, ground, 96, 2.3, 0.05);
				blast(level, viewers, ParticleTypes.END_ROD, target.add(0, 1.5, 0), 120, 1.6, true);
				debris(props, target.add(0, 1, 0), target.y, new String[] {"minecraft:gold_block", "minecraft:sea_lantern", "minecraft:amethyst_block"}, 16, 1.4, 1.7, t, 70);
			}
			int[] white = {0xFFFFFF, 0xFFD54A, 0x8FD3FF};
			int[] fade = {0xB06CFF};
			firework(level, target.add(0, 12, 0), FireworkExplosion.Shape.STAR, white, fade);
			firework(level, target.add(7, 15, 2), FireworkExplosion.Shape.LARGE_BALL, white, fade);
			firework(level, target.add(-7, 15, -2), FireworkExplosion.Shape.STAR, white, fade);
		}
		if (w == 3) {
			firework(level, target.add(2, 18, 6), FireworkExplosion.Shape.BURST, new int[] {0xFFD54A, 0xFFFFFF}, new int[] {0x8FD3FF});
			firework(level, target.add(-3, 18, -6), FireworkExplosion.Shape.LARGE_BALL, new int[] {0x8FD3FF, 0xFFFFFF}, new int[] {0xB06CFF});
		}
		if (cine && (w == 12 || w == 24)) {
			firework(level, target.add(rnd.nextDouble() * 10 - 5, 16 + rnd.nextDouble() * 6, rnd.nextDouble() * 10 - 5), FireworkExplosion.Shape.STAR,
				new int[] {0xFFFFFF, 0xFFD54A}, new int[] {0x8FD3FF});
			firework(level, target.add(rnd.nextDouble() * 10 - 5, 16 + rnd.nextDouble() * 6, rnd.nextDouble() * 10 - 5), FireworkExplosion.Shape.BURST,
				new int[] {0x8FD3FF, 0xB06CFF}, new int[] {0xFFFFFF});
		}
		if (w == 8) props.resize(props.named("nova"), 0.5, 14);
		if (w == 10) {
			props.reshape(props.named("pillar0"), pillarMiddle, new Vec3(0.05, 140, 0.05), Q.ID, 30);
			props.reshape(props.named("pillar1"), pillarMiddle, new Vec3(0.1, 140, 0.1), Q.ID, 30);
			props.reshape(props.named("pillar2"), pillarMiddle, new Vec3(0.2, 140, 0.2), Q.ID, 30);
			props.lifetime(props.named("pillar0"), t + 31);
			props.lifetime(props.named("pillar1"), t + 31);
			props.lifetime(props.named("pillar2"), t + 31);
		}
		if (w > 0 && w % 6 == 0 && w <= 48) pentagram(props, target, w * 0.05, false, 6);
		if (w == 52) {
			for (int line = 0; line < 5; line++) props.remove(props.named("chord" + line));
			for (int k = 0; k < 18; k++) props.remove(props.named("rim" + k));
		}
		if (w < 24) {
			// a second wave of light on the ground
			double radius = 0.8 + w * 0.6;
			ring(level, viewers, ParticleTypes.END_ROD, target.add(0, 0.25, 0), radius, 36, w * 0.1, 0);
		}
		if (w > 4 && w < (cine ? 90 : 70)) {
			// star dust that falls
			for (int k = 0; k < (cine ? 12 : 8); k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double r = Math.sqrt(rnd.nextDouble()) * 9;
				emit(level, viewers, k % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD, target.x + Math.cos(a) * r, target.y + 2 + rnd.nextDouble() * 9,
					target.z + Math.sin(a) * r, 1, 0.05, 0.3, 0.05, 0.01);
			}
		}
	}

	// ------------------------------------------------------------------ the Black Hole

	private static Q coreRotation(int i, double spin) {
		return switch (i) {
			case 0 -> Q.yaw(spin);
			case 1 -> Q.yaw(45 + spin).mul(Q.pitch(54.7));
			default -> Q.yaw(spin * 0.5).mul(Q.roll(54.7)).mul(Q.pitch(45));
		};
	}

	private static Q diskRotation(int i, double spin) {
		return Q.pitch(24).mul(Q.yaw(i * 22.5 + spin));
	}

	/**
	 * A black sphere of rotated blocks hangs above the target inside a purple glass shell, with a disk of fire turning around it. Pieces of
	 * the ground are torn up and sucked in along spirals, arms of dust and a ring of light bend around it. It collapses, and a wave bursts out.
	 */
	public static void blackHole(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t) {
		Vec3 hole = target.add(0, 6.5, 0);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) {
			sound(level, hole, SoundEvents.END_PORTAL_SPAWN, 5.0f, 0.5f);
			sound(level, hole, SoundEvents.ENDER_DRAGON_GROWL, 4.0f, 0.5f);
			for (int i = 0; i < 3; i++) props.name("core" + i, props.block("minecraft:black_concrete", hole, cube(0.2), coreRotation(i, 0), false));
			for (int i = 0; i < 2; i++) props.name("shell" + i, props.block("minecraft:purple_stained_glass", hole, cube(0.3), coreRotation(i, 20), true));
			for (int i = 0; i < 4; i++) props.name("disk" + i, props.block("minecraft:orange_stained_glass", hole, new Vec3(0.5, 0.1, 0.5), diskRotation(i, 0), true));
			for (int i = 0; i < 2; i++) props.name("hot" + i, props.block("minecraft:red_stained_glass", hole, new Vec3(0.5, 0.08, 0.5), diskRotation(i * 2, 10), true));
		}
		if (t < HOLE_COLLAPSE) {
			double grow = Math.min(1.0, t / 16.0);
			double eased = grow * grow * (3 - 2 * grow);
			double core = 0.2 + 3.0 * eased;
			if ((t % 4 == 0 && t <= 16) || t % 6 == 0) {
				double spin = t * 3.0;
				for (int i = 0; i < 3; i++) props.reshape(props.named("core" + i), hole, cube(core), coreRotation(i, spin), 6);
				for (int i = 0; i < 2; i++) props.reshape(props.named("shell" + i), hole, cube(0.3 + 4.9 * eased), coreRotation(i, 20 - spin * 0.6), 6);
			}
			if (t % 4 == 0) {
				double spin = t * 7.0;
				for (int i = 0; i < 4; i++) props.reshape(props.named("disk" + i), hole, new Vec3(0.5 + 10.5 * eased, 0.1, 0.5 + 10.5 * eased), diskRotation(i, spin), 4);
				for (int i = 0; i < 2; i++) props.reshape(props.named("hot" + i), hole, new Vec3(0.5 + 7.5 * eased, 0.08, 0.5 + 7.5 * eased), diskRotation(i * 2, 10 + spin * 1.6), 4);
			}
			if (t >= 4 && t <= 56 && t % 3 == 0) {
				// a piece of the ground is torn up and sucked in
				double a = rnd.nextDouble() * Math.PI * 2;
				double r = 8 + rnd.nextDouble() * 6;
				Vec3 at = target.add(Math.cos(a) * r, 0.5, Math.sin(a) * r);
				double size = 0.5 + rnd.nextDouble() * 0.6;
				props.spiral(props.block(GROUND_PIECES[rnd.nextInt(GROUND_PIECES.length)], at, cube(size), randomRotation(), false), target, hole, r, a, t, 34);
			}
			double radius = core * 0.5;
			// the dark sphere (a few particles to soften the edge)
			for (int k = 0; k < 8; k++) {
				double a = rnd.nextDouble() * Math.PI * 2;
				double b = Math.acos(2 * rnd.nextDouble() - 1);
				emit(level, viewers, k % 4 == 0 ? ParticleTypes.SQUID_INK : VOID, hole.x + radius * 3.2 * Math.sin(b) * Math.cos(a), hole.y + radius * 3.2 * Math.cos(b),
					hole.z + radius * 3.2 * Math.sin(b) * Math.sin(a), 1, 0.02, 0.02, 0.02, 0.0);
			}
			double tilt = 0.42;
			double speed = t * 0.14;
			// the thin ring of light that bends around it, and the hot gas of the disk
			ring(level, viewers, ParticleTypes.END_ROD, hole, core * 1.7 + 0.5, 30, speed, tilt);
			ring(level, viewers, DISK_A, hole, core * 2.3 + 0.8, 24, speed, tilt);
			ring(level, viewers, DISK_B, hole, core * 3.0 + 1.1, 28, speed * 0.7, tilt);
			if (t % 2 == 0) emit(level, viewers, ParticleTypes.FLAME, hole.x + Math.cos(speed) * core * 2.8, hole.y + Math.sin(speed) * core * 2.8 * Math.sin(tilt),
				hole.z + Math.sin(speed) * core * 2.8 * Math.cos(tilt), 2, 0.25, 0.12, 0.25, 0.01);
			// the arms that are sucked in: they spiral toward the center and turn faster the closer they get
			double pull = t < 56 ? 0.5 : 1.1;
			for (int arm = 0; arm < 3; arm++) {
				for (int s = 0; s < 12; s++) {
					double phase = ((s + t * pull * 0.25) % 12) / 12.0;
					double r = 15 * (1 - phase) + 1.5;
					double a = arm * Math.PI * 2 / 3 + r * 0.42 + t * 0.12;
					double lift = Math.sin(r * 0.5) * 0.8;
					emit(level, viewers, s % 3 == 0 ? ParticleTypes.PORTAL : (s % 3 == 1 ? ARM : ParticleTypes.REVERSE_PORTAL), hole.x + Math.cos(a) * r,
						hole.y + lift + (r - 1.5) * 0.12 * Math.sin(tilt), hole.z + Math.sin(a) * r, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}
			if (t % 3 == 0) {
				// dust rises from the ground under it
				double a = rnd.nextDouble() * Math.PI * 2;
				double r = 3 + rnd.nextDouble() * 9;
				emit(level, viewers, ParticleTypes.SMOKE, target.x + Math.cos(a) * r, target.y + 0.4, target.z + Math.sin(a) * r, 3, 0.3, 0.2, 0.3, 0.03);
			}
			if (t == HOLE_COLLAPSE - 1) {
				sound(level, hole, SoundEvents.END_PORTAL_SPAWN, 4.0f, 1.6f);
				for (int i = 0; i < 3; i++) props.reshape(props.named("core" + i), hole, cube(0.3), coreRotation(i, 400), 8);
				for (int i = 0; i < 2; i++) props.reshape(props.named("shell" + i), hole, cube(0.3), coreRotation(i, -200), 8);
				for (int i = 0; i < 4; i++) props.reshape(props.named("disk" + i), hole, new Vec3(1.0, 0.1, 1.0), diskRotation(i, 500), 8);
				for (int i = 0; i < 2; i++) props.reshape(props.named("hot" + i), hole, new Vec3(1.0, 0.08, 1.0), diskRotation(i * 2, 700), 8);
			}
			return;
		}
		if (t < HOLE_BURST) {
			// it is about to burst: everything is pulled into a point
			emit(level, viewers, ParticleTypes.REVERSE_PORTAL, hole, 10, 4.0, 0.2);
			emit(level, viewers, ParticleTypes.END_ROD, hole, 4, 0.4, 0.0);
			return;
		}
		int w = t - HOLE_BURST;
		if (w == 0) {
			for (int i = 0; i < 3; i++) props.remove(props.named("core" + i));
			for (int i = 0; i < 2; i++) props.remove(props.named("shell" + i));
			for (int i = 0; i < 4; i++) props.remove(props.named("disk" + i));
			for (int i = 0; i < 2; i++) props.remove(props.named("hot" + i));
			sound(level, hole, SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 0.8f);
			sound(level, hole, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.6f);
			sound(level, hole, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0f, 0.6f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, hole.x, hole.y, hole.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, hole.x + 2.5, hole.y - 1, hole.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, hole.x - 2.5, hole.y + 1, hole.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.SONIC_BOOM, hole.x, hole.y, hole.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.GUST_EMITTER_LARGE, hole.x, hole.y - 2, hole.z, 1, 0, 0, 0, 0);
			// the shock: glass cubes that grow into a faceted ball and fade
			Props.Prop s0 = props.block("minecraft:white_stained_glass", hole, cube(1.5), Q.ID, true);
			Props.Prop s1 = props.block("minecraft:purple_stained_glass", hole, cube(1.5), coreRotation(1, 0), true);
			Props.Prop s2 = props.block("minecraft:white_stained_glass", hole, cube(1.5), coreRotation(2, 0), true);
			props.reshape(s0, hole, cube(36), Q.ID, 8);
			props.reshape(s1, hole, cube(32), coreRotation(1, 30), 8);
			props.reshape(s2, hole, cube(28), coreRotation(2, 60), 8);
			props.lifetime(s0, t + 9);
			props.lifetime(s1, t + 9);
			props.lifetime(s2, t + 9);
			blast(level, viewers, VIOLET, hole, 110, 1.7, false);
			blast(level, viewers, ParticleTypes.END_ROD, hole, 110, 2.1, false);
			blast(level, viewers, ParticleTypes.FLAME, hole, 70, 1.3, false);
			shockwave(level, viewers, DISK_B, target.add(0, 0.3, 0), 110, 1.6, 0.0);
			shockwave(level, viewers, ParticleTypes.CLOUD, target.add(0, 0.5, 0), 70, 1.0, 0.03);
			debris(props, target.add(0, 1, 0), target.y, GROUND_PIECES, 12, 1.2, 1.4, t, 60);
			int[] violet = {0xB06CFF, 0xFFFFFF, 0xFF5A1F};
			int[] fade = {0x2B0A4D};
			firework(level, hole.add(8, 6, 7), FireworkExplosion.Shape.LARGE_BALL, violet, fade);
			firework(level, hole.add(-8, 6, -7), FireworkExplosion.Shape.LARGE_BALL, violet, fade);
			firework(level, hole.add(-7, 8, 8), FireworkExplosion.Shape.STAR, violet, fade);
			firework(level, hole.add(7, 8, -8), FireworkExplosion.Shape.BURST, violet, fade);
		}
		if (w < 36) {
			// light and dust fall slowly
			emit(level, viewers, ParticleTypes.END_ROD, hole.add(0, 1, 0), 6, 7.0, 0.04);
			if (w % 2 == 0) emit(level, viewers, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, target.x, target.y + 0.5, target.z, 1, 3.0, 0.1, 3.0, 0.02);
		}
	}

	// ------------------------------------------------------------------ the Meteor Shower

	/** Where the big meteor of a Meteor Shower is {@code t} ticks after the start (the camera of the finale follows it). */
	public static Vec3 bigMeteorHead(Vec3 from, Vec3 target, int t) {
		Vec3 dir = flatDirection(from, target);
		Vec3 start = target.add(dir.scale(-44)).add(0, 60, 0);
		double u = Math.max(0, Math.min(1, (t - SHOWER_BIG) / (double) SHOWER_BIG_FLIGHT));
		return start.add(target.subtract(start).scale(u));
	}

	private static void makeMeteor(Props props, Meteor m) {
		Vec3 d = m.end.subtract(m.start).normalize();
		m.dir = d;
		double size = m.big ? 4.8 : 1.5;
		double length = m.big ? 34 : 13;
		double width = m.big ? 3.4 : 1.1;
		m.body = props.block("minecraft:magma_block", m.start, cube(size), randomRotation(), true);
		m.halo = props.item("minecraft:fire_charge", m.start, m.big ? 16.0 : 4.8);
		m.tail = props.block("minecraft:orange_stained_glass", m.start.subtract(d.scale(length / 2)), new Vec3(width, width, length), Q.fromZ(d), true);
		if (m.big) m.tail2 = props.block("minecraft:yellow_stained_glass", m.start.subtract(d.scale(length * 0.4)), new Vec3(1.7, 1.7, length * 0.8), Q.fromZ(d), true);
	}

	/**
	 * Meteors fall from behind the winner over the target, one after the other, each a glowing rock with a fireball and a long fiery tail;
	 * the last one is a huge one that hits the target itself: a blast, a pillar of fire, flying rock and fireworks.
	 */
	public static void meteorShower(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 from, Vec3 target, int t, List<Meteor> meteors) {
		Vec3 dir = flatDirection(from, target);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		if (t == 0) sound(level, target.add(0, 20, 0), SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 1.4f);
		if (t <= SHOWER_END && t % 3 == 0) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double r = 3 + Math.sqrt(rnd.nextDouble()) * 10;
			Vec3 end = target.add(Math.cos(a) * r, 0, Math.sin(a) * r);
			Vec3 start = end.add(dir.scale(-40)).add(0, 52, 0);
			Meteor m = new Meteor(start, end, t, 18 + rnd.nextInt(8), false);
			makeMeteor(props, m);
			meteors.add(m);
		}
		if (t == SHOWER_BIG) {
			Meteor m = new Meteor(target.add(dir.scale(-44)).add(0, 60, 0), target, t, SHOWER_BIG_FLIGHT, true);
			makeMeteor(props, m);
			meteors.add(m);
			sound(level, target.add(0, 25, 0), SoundEvents.WITHER_SPAWN, 4.0f, 1.6f);
		}
		Iterator<Meteor> it = meteors.iterator();
		while (it.hasNext()) {
			Meteor m = it.next();
			double u = (t - m.born) / (double) m.flight;
			if (u < 0) continue;
			if (u >= 1) {
				it.remove();
				land(level, viewers, props, m, t);
				continue;
			}
			Vec3 head = m.start.add(m.end.subtract(m.start).scale(u));
			if (t % 2 == 0) {
				props.move(m.body, head);
				props.move(m.halo, head);
				props.move(m.tail, head.subtract(m.dir.scale(m.big ? 17 : 6.5)));
				if (m.tail2 != null) props.move(m.tail2, head.subtract(m.dir.scale(13.6)));
			}
			int bigger = m.big ? 2 : 1;
			emit(level, viewers, ParticleTypes.FLAME, head, 4 * bigger, 0.3 * bigger, 0.03);
			emit(level, viewers, ParticleTypes.LARGE_SMOKE, head, 2 * bigger, 0.35 * bigger, 0.02);
			for (int k = 1; k <= 8; k++) {
				double back = u - k * 0.035;
				if (back < 0) break;
				Vec3 p = m.start.add(m.end.subtract(m.start).scale(back));
				emit(level, viewers, k < 4 ? ParticleTypes.FLAME : ParticleTypes.SMOKE, p, bigger, 0.14 * k * bigger, 0.01);
			}
		}
	}

	private static void land(ServerLevel level, List<ServerPlayer> viewers, Props props, Meteor m, int t) {
		Vec3 at = m.end;
		Vec3 ground = at.add(0, 0.3, 0);
		props.remove(m.body);
		props.remove(m.halo);
		props.remove(m.tail);
		props.remove(m.tail2);
		if (m.big) {
			sound(level, at, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.65f);
			sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.6f);
			sound(level, at, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 0.8f);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, at.x + 3, at.y + 1, at.z - 2, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, at.x - 3, at.y + 1, at.z + 3, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.SONIC_BOOM, at.x, at.y + 2, at.z, 1, 0, 0, 0, 0);
			emit(level, viewers, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
			shockwave(level, viewers, ParticleTypes.FLAME, ground, 120, 1.5, 0.02);
			shockwave(level, viewers, ParticleTypes.LARGE_SMOKE, ground, 80, 1.0, 0.04);
			shockwave(level, viewers, ParticleTypes.LAVA, ground, 40, 0.9, 0.35);
			blast(level, viewers, ParticleTypes.FLAME, at.add(0, 1, 0), 70, 1.1, true);
			Props.Prop ball = props.item("minecraft:fire_charge", at.add(0, 3, 0), 6.0);
			props.resize(ball, 24.0, 6);
			props.lifetime(ball, t + 8);
			Vec3 pillar = at.add(0, 16, 0);
			Props.Prop p0 = props.block("minecraft:orange_stained_glass", pillar, new Vec3(7.0, 32, 7.0), Q.ID, true);
			Props.Prop p1 = props.block("minecraft:yellow_stained_glass", pillar, new Vec3(3.4, 32, 3.4), Q.ID, true);
			props.reshape(p0, pillar, new Vec3(0.3, 40, 0.3), Q.ID, 28);
			props.reshape(p1, pillar, new Vec3(0.2, 36, 0.2), Q.ID, 22);
			props.lifetime(p0, t + 29);
			props.lifetime(p1, t + 23);
			debris(props, at.add(0, 1, 0), at.y, new String[] {"minecraft:magma_block", "minecraft:netherrack", "minecraft:obsidian"}, 16, 1.4, 1.6, t, 60);
			int[] fire = {0xFF5A1F, 0xFFB347, 0xFFE066};
			int[] fade = {0x4A1008};
			firework(level, at.add(0, 12, 0), FireworkExplosion.Shape.LARGE_BALL, fire, fade);
			firework(level, at.add(7, 14, 4), FireworkExplosion.Shape.BURST, fire, fade);
			firework(level, at.add(-7, 14, -4), FireworkExplosion.Shape.LARGE_BALL, fire, fade);
			return;
		}
		sound(level, at, SoundEvents.GENERIC_EXPLODE, 2.2f, 1.0f + (float) ThreadLocalRandom.current().nextDouble() * 0.3f);
		emit(level, viewers, ParticleTypes.EXPLOSION, at.x, at.y + 0.6, at.z, 1, 0, 0, 0, 0);
		shockwave(level, viewers, ParticleTypes.FLAME, ground, 28, 0.7, 0.03);
		emit(level, viewers, ParticleTypes.LAVA, at.x, at.y + 0.4, at.z, 4, 0.6, 0.2, 0.6, 0.0);
		Props.Prop pop = props.item("minecraft:fire_charge", at.add(0, 1.2, 0), 7.0);
		props.resize(pop, 0.5, 6);
		props.lifetime(pop, t + 7);
		debris(props, at.add(0, 0.8, 0), at.y, new String[] {"minecraft:magma_block", "minecraft:netherrack"}, 3, 0.7, 0.9, t, 40);
	}
}
