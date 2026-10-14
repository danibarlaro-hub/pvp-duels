package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.pvpduels.effects.WonderKit.beam;
import static com.pvpduels.effects.WonderKit.clamp01;
import static com.pvpduels.effects.WonderKit.cube;
import static com.pvpduels.effects.WonderKit.ease;
import static com.pvpduels.effects.WonderKit.fx;
import static com.pvpduels.effects.WonderKit.orbGone;
import static com.pvpduels.effects.WonderKit.orbMake;
import static com.pvpduels.effects.WonderKit.orbTo;
import static com.pvpduels.effects.WonderKit.polar;
import static com.pvpduels.effects.WonderKit.rise;
import static com.pvpduels.effects.WonderKit.sink;
import static com.pvpduels.effects.WonderKit.thin;

/**
 * The explosions that come from the sky or from a tear in it: Rift Storm, Dragon Strike, Soul Reaper, Solar Flare, Supernova and
 * Prism Beams. Only light and sound: every solid part is a display entity, nothing in the world is touched.
 * {@code c} is where the loser stands, {@code t} the number of ticks since the explosion began, {@code cinema} the long version of the finale.
 */
final class SkyWonders {
	private static final String[] DARK = {"minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:deepslate", "minecraft:blackstone"};

	private SkyWonders() {}

	// ------------------------------------------------------------------ Rift Storm

	/** A tear opens in the sky above the target, sparking and pulling the ground up into it, then it spits a beam down and closes. */
	static void riftStorm(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 96 : 50;
		double rh = cinema ? 11 : 9;
		Vec3 rift = c.add(0, rh, 0);
		Vec3 low = c.add(0, 0.2, 0);
		double full = rh * 1.6;
		if (t == 0) {
			ExplosionArt.sound(level, rift, SoundEvents.END_PORTAL_SPAWN, 4.0f, 0.6f);
			ExplosionArt.sound(level, rift, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			for (int s = 0; s < 2; s++) {
				Q turn = Q.yaw(s * 90);
				props.lifetime(props.name("rfk" + s, props.block("minecraft:black_concrete", rift, new Vec3(0.1, 0.3, 0.1), turn, false)), lead + 40);
				props.lifetime(props.name("rfp" + s, props.block("minecraft:purple_stained_glass", rift, new Vec3(0.2, 0.3, 0.2), turn, true)), lead + 40);
				props.lifetime(props.name("rfm" + s, props.block("minecraft:magenta_stained_glass", rift, new Vec3(0.3, 0.3, 0.3), turn, true)), lead + 40);
			}
		}
		if (t > 0 && t < lead + 14 && t % 3 == 1) {
			double hh = 0.3 + full * ease(t / (lead * 0.35));
			double wd = 0.12 + 2.4 * ease(t / (double) lead);
			for (int s = 0; s < 2; s++) {
				Q turn = Q.yaw(s * 90);
				props.reshape(props.named("rfk" + s), rift, new Vec3(wd, hh, 0.12), turn, 4);
				props.reshape(props.named("rfp" + s), rift, new Vec3(wd * 1.5 + 0.3, hh * 1.05, 0.3), turn, 4);
				props.reshape(props.named("rfm" + s), rift, new Vec3(wd * 2.2 + 0.5, hh * 1.1, 0.5), turn, 4);
			}
		}
		if (t > 2 && t < lead) {
			double hh = 0.3 + full * ease(t / (lead * 0.35));
			ExplosionArt.emit(level, viewers, ParticleTypes.PORTAL, rift.x, rift.y + (rnd.nextDouble() - 0.5) * hh, rift.z, 6, 0.8, 0.3, 0.8, 0.6);
			ExplosionArt.emit(level, viewers, ParticleTypes.REVERSE_PORTAL, rift.x, rift.y + (rnd.nextDouble() - 0.5) * hh, rift.z, 3, 1.2, 0.3, 1.2, 0.1);
			if (t % 2 == 0) fx(level, viewers, WonderKit.MAGENTA, rift.add(0, (rnd.nextDouble() - 0.5) * hh, 0), 2, 0.6, 0.0);
		}
		if (t >= 10 && t < lead && t % 4 == 0) {
			Vec3 from = rift.add(0, (rnd.nextDouble() - 0.5) * 8, 0);
			Vec3 to = polar(c, 4 + rnd.nextDouble() * 7, rnd.nextDouble() * Math.PI * 2, 0.2);
			Vec3 knee = from.add(to.subtract(from).scale(0.5)).add((rnd.nextDouble() - 0.5) * 3, 0, (rnd.nextDouble() - 0.5) * 3);
			props.lifetime(Shapes.line(props, "minecraft:magenta_stained_glass", from, knee, 0.25, true), t + 3);
			props.lifetime(Shapes.line(props, "minecraft:magenta_stained_glass", knee, to, 0.25, true), t + 3);
			fx(level, viewers, ParticleTypes.ELECTRIC_SPARK, to, 6, 0.5, 0.2);
			ExplosionArt.sound(level, to, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.2f, 1.8f);
		}
		if (t >= 4 && t <= lead - 14 && t % 3 == 0) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double r = 6 + rnd.nextDouble() * 5;
			double size = 0.5 + rnd.nextDouble() * 0.6;
			props.spiral(props.block(DARK[rnd.nextInt(DARK.length)], polar(c, r, a, 0.5), cube(size), ExplosionArt.randomRotation(), false), c, rift, r, a, t, 30);
		}
		beam(props, t, lead, 4, lead + 28, "rfb", "minecraft:magenta_stained_glass", rift, low, 3.6);
		beam(props, t, lead, 4, lead + 28, "rfc", "minecraft:white_concrete", rift, low, 1.3);
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 0.7f);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.7f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.PORTAL, low, cinema ? 90 : 60, 1.4, 0.04);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, low.add(0, 0.5, 0), 50, 1.0, 0.04);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, c.add(0, 1, 0), cinema ? 90 : 56, 1.3, true);
			ExplosionArt.debris(props, c.add(0, 1, 0), c.y, DARK, cinema ? 18 : 10, 1.3, 1.6, t, 50);
		}
		if (t > lead && t < lead + 20) {
			ExplosionArt.emit(level, viewers, ParticleTypes.PORTAL, c.x, c.y + rnd.nextDouble() * rh, c.z, 6, 1.2, 0.5, 1.2, 0.6);
		}
		if (t == lead + 16) thin(props, "rfb", rift, low, 8);
		if (t == lead + 18) {
			for (int s = 0; s < 2; s++) {
				Q turn = Q.yaw(s * 90);
				props.reshape(props.named("rfk" + s), rift, new Vec3(0.05, 0.4, 0.05), turn, 12);
				props.reshape(props.named("rfp" + s), rift, new Vec3(0.1, 0.5, 0.1), turn, 12);
				props.reshape(props.named("rfm" + s), rift, new Vec3(0.15, 0.6, 0.15), turn, 12);
			}
		}
	}

	// ------------------------------------------------------------------ Dragon Strike

	/** The place of the dragon's head after {@code tau} ticks: it circles above the arena and then dives at the target. */
	private static Vec3 dragonAt(Vec3 c, double tau, int lead, int dive, boolean cinema) {
		double circle = cinema ? 15 : 13;
		double alt = cinema ? 17 : 14;
		double fly = lead - dive;
		double omega = Math.PI * 2 * 1.1 / fly;
		double a0 = -0.5;
		if (tau <= fly) return polar(c, circle, a0 + omega * tau, alt + 2.5 * Math.sin(tau * 0.12));
		double u = clamp01((tau - fly) / dive);
		double e = u * u;
		double y0 = alt + 2.5 * Math.sin(fly * 0.12);
		return polar(c, circle * (1 - e), a0 + omega * fly + 1.2 * u, y0 + (1.2 - y0) * e);
	}

	/** A dragon built of blocks circles above the arena, flaps its wings, dives at the target and breathes a cloud of dragon breath on it. */
	static void dragonStrike(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 110 : 58;
		int dive = cinema ? 30 : 20;
		int fly = lead - dive;
		int bodyParts = 12;
		Vec3 ground = c.add(0, 0.06, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c.add(0, 15, 0), SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.8f);
		}
		double tau = Math.min(t, lead + 4);
		Vec3 head = dragonAt(c, tau, lead, dive, cinema);
		Vec3 ahead = dragonAt(c, tau + 1, lead, dive, cinema);
		Vec3 heading = ahead.subtract(head);
		if (heading.lengthSqr() < 1e-6) heading = new Vec3(0, -1, 0);
		heading = heading.normalize();
		if (t == 3) {
			props.lifetime(props.name("drh", props.block("minecraft:black_concrete", head, cube(2.4), Q.ID, false)), lead + 8);
			props.lifetime(props.name("drs", props.block("minecraft:purple_concrete", head.add(heading.scale(1.7)), cube(1.3), Q.ID, false)), lead + 8);
			props.lifetime(props.name("drj", props.block("minecraft:magenta_stained_glass", head.add(heading.scale(2.6)), cube(1.0), Q.ID, true)), lead + 8);
			for (int k = 1; k <= bodyParts; k++) {
				Vec3 at = dragonAt(c, tau - 3.2 * k, lead, dive, cinema);
				props.lifetime(props.name("drb" + k, props.block(k % 2 == 0 ? "minecraft:black_concrete" : "minecraft:purple_concrete", at, cube(Math.max(0.7, 2.1 - 0.12 * k)), Q.ID, false)),
					lead + 8);
			}
			for (int s = 0; s < 2; s++) {
				props.lifetime(props.name("drw" + s, props.block("minecraft:purple_stained_glass", head, new Vec3(8, 0.18, 3.4), Q.ID, true)), lead + 8);
			}
		}
		if (t > 3 && t % 2 == 0 && t <= lead + 6) {
			props.move(props.named("drh"), head);
			props.move(props.named("drs"), head.add(heading.scale(1.7)));
			props.move(props.named("drj"), head.add(heading.scale(2.6)));
			for (int k = 1; k <= bodyParts; k++) props.move(props.named("drb" + k), dragonAt(c, tau - 3.2 * k, lead, dive, cinema));
			Vec3 spot = dragonAt(c, tau - 6.4, lead, dive, cinema);
			Vec3 spotAhead = dragonAt(c, tau - 5.4, lead, dive, cinema);
			Vec3 spotBack = dragonAt(c, tau - 7.4, lead, dive, cinema);
			Vec3 way = spotAhead.subtract(spotBack);
			if (way.lengthSqr() < 1e-6) way = new Vec3(0, -1, 0);
			double flap = 30 * Math.sin(t * 0.45);
			Q right = Q.fromZ(way).mul(Q.roll(flap));
			Q left = Q.fromZ(way).mul(Q.roll(-flap));
			Vec3 size = new Vec3(8, 0.18, 3.4);
			props.reshape(props.named("drw0"), spot.add(right.rotate(new Vec3(4.1, 0, 0))), size, right, 2);
			props.reshape(props.named("drw1"), spot.add(left.rotate(new Vec3(-4.1, 0, 0))), size, left, 2);
			fx(level, viewers, ParticleTypes.PORTAL, head, 6, 0.5, 0.3);
			fx(level, viewers, WonderKit.PURPLE, head, 3, 0.4, 0.0);
			for (int k = 3; k <= bodyParts; k += 3) fx(level, viewers, ParticleTypes.PORTAL, dragonAt(c, tau - 3.2 * k, lead, dive, cinema), 2, 0.4, 0.2);
		}
		if (t == fly) ExplosionArt.sound(level, head, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 1.1f);
		if (t >= fly && t < lead && t % 2 == 0) {
			double u = (t - fly) / (double) dive;
			props.lifetime(Shapes.line(props, "minecraft:magenta_stained_glass", head.add(heading.scale(2.8)), ground, 0.5 + 1.3 * u, true), t + 3);
			ExplosionArt.fly(level, viewers, ParticleTypes.PORTAL, head, ground.subtract(head).normalize(), 1.0);
		}
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 5.0f, 0.7f);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.7f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 0.7f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.PORTAL, ground.add(0, 0.5, 0), cinema ? 90 : 60, 1.4, 0.04);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, c.add(0, 1, 0), cinema ? 80 : 50, 1.2, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.PORTAL, c.add(0, 1, 0), 90, 1.5, true);
			orbMake(props, "drc", "minecraft:purple_stained_glass", c.add(0, 1, 0), true, lead + 20);
			orbMake(props, "drd", "minecraft:magenta_stained_glass", c.add(0, 1, 0), true, lead + 16);
			Shapes.diskMake(props, "drk", "minecraft:obsidian", ground, 0.14, 5, false, lead + 70);
		}
		if (t == lead + 1) {
			orbTo(props, "drc", c.add(0, 1, 0), 15, 20, 8);
			orbTo(props, "drd", c.add(0, 1, 0), 10, -20, 8);
			Shapes.diskTo(props, "drk", ground, 6, 0.14, 5, 6);
		}
		if (t > lead && t < lead + 56) {
			ExplosionArt.emit(level, viewers, WonderKit.PURPLE, c.x + (rnd.nextDouble() - 0.5) * 9, c.y + 0.4 + rnd.nextDouble() * 2.5, c.z + (rnd.nextDouble() - 0.5) * 9, 3, 0.4, 0.3, 0.4, 0.0);
			if (t % 2 == 0) fx(level, viewers, ParticleTypes.PORTAL, c.add((rnd.nextDouble() - 0.5) * 8, 1.0 + rnd.nextDouble() * 2.0, (rnd.nextDouble() - 0.5) * 8), 3, 0.5, 0.2);
		}
	}

	// ------------------------------------------------------------------ Soul Reaper

	/** The angle of the scythe's handle: raised behind, pulled back, then swung down through the target and on up the other side. */
	private static double reaperAngle(int t, int lead) {
		int wind = (int) (lead * 0.35);
		double deg;
		if (t < wind) {
			deg = -125 - 25 * ease(t / (double) wind);
		} else if (t < lead) {
			double v = (t - wind) / (double) (lead - wind);
			deg = -150 + 150 * v * v;
		} else {
			double p = clamp01((t - lead) / 18.0);
			deg = 75 * (1 - (1 - p) * (1 - p));
		}
		return Math.toRadians(deg);
	}

	/** A giant scythe of soul glass is raised, pulled back and swung through the target; a slash of light and a column of souls follow. */
	static void soulReaper(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 100 : 52;
		double phi = ((Math.floor(c.x * 3) + Math.floor(c.z * 7)) % 12 + 12) % 12 * Math.PI / 6;
		Vec3 u = new Vec3(Math.cos(phi), 0, Math.sin(phi));
		Vec3 pivot = c.add(u.scale(-3.8)).add(0, 14.5, 0);
		double handle = 14;
		int blade = 6;
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 2.5f, 0.5f);
		}
		if (t >= 3 && t <= lead + 30 && (t == 3 || t % 2 == 0)) {
			double alpha = reaperAngle(t, lead);
			double sin = Math.sin(alpha);
			double cos = Math.cos(alpha);
			Vec3 hdir = u.scale(sin).add(0, -cos, 0);
			Vec3 tip = pivot.add(hdir.scale(handle));
			Vec3 swing = u.scale(cos).add(0, sin, 0);
			boolean fade = t >= lead + 28;
			if (t == 3) {
				props.lifetime(props.name("srh", Shapes.line(props, "minecraft:black_concrete", pivot, tip, 0.6, false)), lead + 36);
			} else {
				Shapes.growLine(props, props.named("srh"), pivot, tip, fade ? 0.05 : 0.6, 2);
			}
			Vec3 previous = tip;
			for (int j = 1; j <= blade; j++) {
				double s = j / (double) blade;
				Vec3 point = tip.add(swing.scale(8 * s)).add(hdir.scale(-3.5 * s * s));
				double thick = 1.4 - 0.15 * j;
				if (t == 3) {
					props.lifetime(props.name("srb" + j, Shapes.line(props, "minecraft:light_blue_stained_glass", previous, point, thick, true)), lead + 36);
					props.lifetime(props.name("src" + j, Shapes.line(props, "minecraft:white_concrete", previous, point, thick * 0.35, true)), lead + 36);
				} else {
					Shapes.growLine(props, props.named("srb" + j), previous, point, fade ? 0.05 : thick, 2);
					Shapes.growLine(props, props.named("src" + j), previous, point, fade ? 0.03 : thick * 0.35, 2);
				}
				if (t > 5 && t <= lead + 14) fx(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, point, 1, 0.25, 0.01);
				previous = point;
			}
			if (t > 5 && t <= lead + 14) fx(level, viewers, ParticleTypes.SCULK_SOUL, previous, 1, 0.3, 0.02);
		}
		if (t < lead && t % 2 == 0) {
			fx(level, viewers, ParticleTypes.SOUL, polar(c, rnd.nextDouble() * 7, rnd.nextDouble() * 6.28, 0.2), 1, 0.3, 0.05);
			if (t % 6 == 0) fx(level, viewers, ParticleTypes.SQUID_INK, polar(c, rnd.nextDouble() * 9, rnd.nextDouble() * 6.28, 0.5), 3, 0.5, 0.02);
		}
		Vec3 slashA = c.add(u.scale(-13)).add(0, 1.4, 0);
		Vec3 slashB = c.add(u.scale(13)).add(0, 1.4, 0);
		beam(props, t, lead, 2, lead + 16, "srs", "minecraft:white_concrete", slashA, slashB, 0.5);
		beam(props, t, lead, 2, lead + 16, "srt", "minecraft:light_blue_stained_glass", slashA, slashB, 1.8);
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.PLAYER_ATTACK_SWEEP, 5.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 1.2f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.5f, 0.8f);
			for (int k = -6; k <= 6; k++) fx(level, viewers, ParticleTypes.SWEEP_ATTACK, c.add(u.scale(k * 1.6)).add(0, 1.4, 0), 1, 0.2, 0.0);
			ExplosionArt.blast(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 1.4, 0), cinema ? 90 : 56, 0.9, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.SOUL, c.add(0, 1.4, 0), 50, 0.7, true);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 0.5, 0), 60, 1.1, 0.03);
		}
		if (t > lead && t < lead + 50) {
			double up = (t - lead) * 0.35;
			for (int strand = 0; strand < 3; strand++) {
				double a = t * 0.25 + strand * 2.094;
				Vec3 at = polar(c, 1.8 + (t - lead) * 0.03, a, up);
				fx(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, at, 1, 0.1, 0.01);
				if (strand == 0) fx(level, viewers, ParticleTypes.SOUL, at, 1, 0.2, 0.03);
			}
		}
	}

	// ------------------------------------------------------------------ Solar Flare

	/** A small sun swells in the sky above the target, throws fiery arcs, and shoots a beam of light down that scorches the ground. */
	static void solarFlare(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 110 : 56;
		double hs = cinema ? 22 : 16;
		double max = cinema ? 8.0 : 6.0;
		Vec3 sun = c.add(0, hs, 0);
		Vec3 ground = c.add(0, 0.1, 0);
		double u = ease(t / (lead * 0.9));
		double d = 0.4 + max * u;
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			orbMake(props, "sg", "minecraft:glowstone", sun, true, lead + 40);
			orbMake(props, "so", "minecraft:orange_stained_glass", sun, true, lead + 40);
			orbMake(props, "sy", "minecraft:yellow_stained_glass", sun, true, lead + 40);
		}
		if (t > 0 && t < lead + 10 && t % 3 == 1) {
			double pulse = 1 + 0.05 * Math.sin(t * 0.6);
			orbTo(props, "sg", sun, d, t * 4, 4);
			orbTo(props, "so", sun, d * 1.3 * pulse, -t * 3, 4);
			orbTo(props, "sy", sun, d * 1.7 * pulse, t * 2, 4);
		}
		if (t > 2 && t < lead + 14) {
			ExplosionArt.ring(level, viewers, ParticleTypes.FLAME, sun, d * 1.1 + 0.5, 26, t * 0.12, 0.5);
			ExplosionArt.ring(level, viewers, WonderKit.SUN, sun, d * 1.5 + 0.8, 24, -t * 0.09, -0.4);
			if (t % 2 == 0) ExplosionArt.ring(level, viewers, ParticleTypes.END_ROD, sun, d * 0.9 + 0.3, 14, t * 0.2, 1.2);
		}
		if (t >= 10 && t < lead && t % 5 == 0) {
			Vec3 dir = ExplosionArt.sphereDir(rnd.nextInt(40), 40, false);
			Vec3 side = new Vec3(-dir.z, 0.2, dir.x).normalize();
			double radius = d * 0.6;
			Vec3 prior = sun.add(dir.scale(radius));
			for (int q = 1; q <= 5; q++) {
				double s = q / 5.0;
				Vec3 point = sun.add(dir.scale(radius + 4.5 * Math.sin(Math.PI * s))).add(side.scale(6.0 * s));
				props.lifetime(Shapes.line(props, "minecraft:orange_stained_glass", prior, point, 0.55, true), t + 14);
				prior = point;
			}
			ExplosionArt.sound(level, sun, SoundEvents.GENERIC_EXPLODE, 1.5f, 1.6f);
		}
		beam(props, t, lead, 3, lead + 30, "sb1", "minecraft:yellow_stained_glass", sun, ground, 5.5);
		beam(props, t, lead, 3, lead + 30, "sb2", "minecraft:orange_stained_glass", sun, ground, 8.0);
		beam(props, t, lead, 3, lead + 30, "sb3", "minecraft:white_concrete", sun, ground, 2.0);
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 4.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0f, 0.7f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, ground, cinema ? 100 : 64, 1.5, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.LAVA, c.add(0, 1, 0), 60, 1.2, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, c.add(0, 1, 0), 80, 1.5, true);
			Shapes.diskMake(props, "sc", "minecraft:magma_block", ground, 0.12, 5, true, lead + 70);
		}
		if (t == lead + 1) Shapes.diskTo(props, "sc", ground, 9, 0.12, 5, 6);
		if (t == lead + 4) ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, ground.add(0, 0.4, 0), 70, 1.0, 0.04);
		if (t == lead + 9) ExplosionArt.shockwave(level, viewers, ParticleTypes.LARGE_SMOKE, ground.add(0, 0.6, 0), 60, 0.7, 0.05);
		if (t >= lead && t < lead + 28) {
			for (int k = 0; k < 4; k++) fx(level, viewers, ParticleTypes.FLAME, c.add((rnd.nextDouble() - 0.5) * 4, 1 + rnd.nextDouble() * (hs - 2), (rnd.nextDouble() - 0.5) * 4), 2, 0.5, 0.03);
		}
		if (t == lead + 14) orbTo(props, "sg", sun, 0.3, 0, 10);
		if (t == lead + 14) orbTo(props, "so", sun, 0.4, 0, 10);
		if (t == lead + 14) orbTo(props, "sy", sun, 0.5, 0, 10);
		if (t == lead + 26) {
			orbGone(props, "sg");
			orbGone(props, "so");
			orbGone(props, "sy");
		}
		if (t == lead + 18) {
			thin(props, "sb1", sun, ground, 8);
			thin(props, "sb2", sun, ground, 8);
			thin(props, "sb3", sun, ground, 8);
		}
	}

	// ------------------------------------------------------------------ Supernova

	/** A red giant swells in the sky, collapses to a white point, and bursts: shells of light, rays in every direction and a shaft of light. */
	static void supernova(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 108 : 56;
		double hs = cinema ? 20 : 15;
		int swell = (int) (lead * 0.7);
		Vec3 star = c.add(0, hs, 0);
		Vec3 ground = c.add(0, 0.1, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.7f);
			orbMake(props, "ng", "minecraft:red_stained_glass", star, true, lead);
			orbMake(props, "nc", "minecraft:orange_concrete", star, true, lead);
		}
		if (t == swell) {
			orbMake(props, "nw", "minecraft:white_concrete", star, true, lead);
			orbMake(props, "nh", "minecraft:light_blue_stained_glass", star, true, lead);
		}
		if (t > 0 && t < lead && t % 3 == 1) {
			if (t < swell) {
				double u = ease(t / (double) swell);
				double side = 0.6 + 5.0 * u;
				orbTo(props, "nc", star, side, t * 3, 4);
				orbTo(props, "ng", star, side * 1.35, -t * 2, 4);
			} else {
				double v = ease((t - swell) / (double) (lead - swell));
				double side = 0.3 + 5.3 * (1 - v);
				orbTo(props, "nc", star, side, t * 5, 4);
				orbTo(props, "ng", star, side * 1.35, -t * 4, 4);
				orbTo(props, "nw", star, 0.3 + 2.6 * v, t * 6, 4);
				orbTo(props, "nh", star, (0.3 + 2.6 * v) * 1.8, -t * 5, 4);
			}
		}
		if (t > 2 && t < lead) {
			double u = t < swell ? ease(t / (double) swell) : 1 - ease((t - swell) / (double) (lead - swell));
			ExplosionArt.ring(level, viewers, ParticleTypes.FLAME, star, 1.5 + 4.5 * u, 24, t * 0.15, 0.6);
			ExplosionArt.ring(level, viewers, ParticleTypes.END_ROD, star, 2.2 + 5 * u, 20, -t * 0.1, -0.7);
		}
		if (t >= swell && t < lead) {
			for (int k = 0; k < 2; k++) {
				Vec3 dir = ExplosionArt.sphereDir(rnd.nextInt(60), 60, false);
				Vec3 start = star.add(dir.scale(12));
				ExplosionArt.fly(level, viewers, ParticleTypes.END_ROD, start, dir.scale(-1), 0.7);
			}
			if (t % 4 == 0) ExplosionArt.sound(level, star, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0f, 0.5f + 1.5f * (t - swell) / (float) (lead - swell));
		}
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0f, 0.6f);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, star, cinema ? 130 : 80, 2.0, false);
			ExplosionArt.blast(level, viewers, ParticleTypes.FIREWORK, star, 80, 1.5, false);
			fx(level, viewers, ParticleTypes.EXPLOSION_EMITTER, star, 1, 0.0, 0.0);
			orbMake(props, "ns1", "minecraft:white_stained_glass", star, true, lead + 22);
			orbMake(props, "ns2", "minecraft:cyan_stained_glass", star, true, lead + 26);
			orbMake(props, "ns3", "minecraft:purple_stained_glass", star, true, lead + 30);
			Shapes.diskMake(props, "nd", "minecraft:white_stained_glass", star, 0.2, 5, true, lead + 24);
		}
		if (t == lead + 1) {
			orbTo(props, "ns1", star, 30, 10, 10);
			orbTo(props, "ns2", star, 24, -15, 12);
			orbTo(props, "ns3", star, 18, 25, 14);
			Shapes.diskTo(props, "nd", star, 26, 0.2, 5, 10);
		}
		int rays = cinema ? 18 : 12;
		for (int i = 0; i < rays; i++) {
			Vec3 dir = ExplosionArt.sphereDir(i, rays, false);
			beam(props, t, lead, 3, lead + 20, "nr" + i, "minecraft:white_concrete", star, star.add(dir.scale(24)), 0.55);
		}
		beam(props, t, lead, 4, lead + 40, "nsb", "minecraft:white_stained_glass", star, ground, 6.0);
		beam(props, t, lead, 4, lead + 40, "nsc", "minecraft:white_concrete", star, ground, 2.4);
		if (t == lead + 4) ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground.add(0, 0.5, 0), cinema ? 90 : 60, 1.4, 0.03);
		if (t == lead + 10) ExplosionArt.shockwave(level, viewers, ParticleTypes.ELECTRIC_SPARK, ground.add(0, 0.7, 0), 60, 1.0, 0.03);
		if (t > lead && t < lead + 40) {
			fx(level, viewers, ParticleTypes.END_ROD, star.add((rnd.nextDouble() - 0.5) * 26, (rnd.nextDouble() - 0.5) * 8 - (t - lead) * 0.1, (rnd.nextDouble() - 0.5) * 26), 3, 0.4, 0.02);
		}
		if (t == lead + 30) {
			thin(props, "nsb", star, ground, 8);
			thin(props, "nsc", star, ground, 8);
		}
	}

	// ------------------------------------------------------------------ Prism Beams

	/** Seven beams of the colors of the rainbow fall from the sky one after the other and meet on the target; it erupts in a column of white light and rings of color. */
	static void prismBeams(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 108 : 56;
		Vec3 ground = c.add(0, 0.2, 0);
		int gap = Math.max(2, (lead - 24) / 7);
		for (int i = 0; i < 7; i++) {
			int t0 = 6 + i * gap;
			double a = i * Math.PI * 2 / 7 + 0.3;
			Vec3 from = polar(c, cinema ? 28 : 22, a, cinema ? 34 : 26);
			beam(props, t, t0, 8, lead + 36 + i, "pb" + i, WonderKit.RAIN_GLASS[i], from, ground, 1.3);
			beam(props, t, t0, 8, lead + 36 + i, "pc" + i, "minecraft:white_concrete", from, ground, 0.45);
			if (t == t0) ExplosionArt.sound(level, ground, SoundEvents.NOTE_BLOCK_CHIME, 2.5f, 0.6f + i * 0.15f);
			if (t > t0 + 2 && t < lead) {
				fx(level, viewers, ParticleTypes.END_ROD, from.add(ground.subtract(from).scale(rnd.nextDouble())), 1, 0.3, 0.0);
				fx(level, viewers, WonderKit.RAIN_DUST[i], polar(c, 1.5 + i * 0.35, t * 0.2 + i, 0.2), 2, 0.1, 0.0);
			}
			if (t == lead + 28 + i) {
				thin(props, "pb" + i, from, ground, 8);
				thin(props, "pc" + i, from, ground, 8);
			}
		}
		rise(props, t, lead, 4, lead + 44, "pw", "minecraft:white_stained_glass", c, 5.5, 60, true);
		rise(props, t, lead, 4, lead + 44, "pn", "minecraft:white_concrete", c, 2.2, 66, true);
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.8f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 4.0f, 1.4f);
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 1.2f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground.add(0, 0.5, 0), cinema ? 90 : 60, 1.4, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.FIREWORK, c.add(0, 1, 0), 90, 1.4, true);
			for (int i = 0; i < 7; i++) {
				Shapes.diskMake(props, "pr" + i, WonderKit.RAIN_SOLID[i], ground.add(0, 0.03 * i, 0), 0.1, 4, true, lead + 50);
			}
			ExplosionArt.firework(level, c.add(0, 14, 0), FireworkExplosion.Shape.LARGE_BALL, WonderKit.RAINBOW, new int[] {0xFFFFFF});
		}
		if (t == lead + 1) {
			for (int i = 0; i < 7; i++) Shapes.diskTo(props, "pr" + i, ground.add(0, 0.03 * i, 0), 6 + (6 - i) * 2.4, 0.1, 4, 12);
		}
		if (t == lead + 8) ExplosionArt.firework(level, c.add(6, 16, 3), FireworkExplosion.Shape.STAR, WonderKit.RAINBOW, new int[] {0xFFFFFF});
		if (t == lead + 14) ExplosionArt.firework(level, c.add(-6, 18, -3), FireworkExplosion.Shape.BURST, WonderKit.RAINBOW, new int[] {0xFFFFFF});
		if (t == lead + 36) {
			for (int i = 0; i < 7; i++) Shapes.diskTo(props, "pr" + i, ground.add(0, 0.03 * i, 0), 0.05, 0.1, 4, 10);
			WonderKit.sink(props, "pw", c, 5.5, 10);
			WonderKit.sink(props, "pn", c, 2.2, 10);
		}
		if (t > lead && t < lead + 40) {
			int i = t % 7;
			fx(level, viewers, WonderKit.RAIN_DUST[i], c.add((rnd.nextDouble() - 0.5) * 5, rnd.nextDouble() * 24, (rnd.nextDouble() - 0.5) * 5), 3, 0.4, 0.0);
		}
	}
}
