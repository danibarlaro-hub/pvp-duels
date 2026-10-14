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
import static com.pvpduels.effects.WonderKit.orbMake;
import static com.pvpduels.effects.WonderKit.orbTo;
import static com.pvpduels.effects.WonderKit.polar;
import static com.pvpduels.effects.WonderKit.rise;
import static com.pvpduels.effects.WonderKit.sink;

/**
 * The explosions that rise from the ground or crash into it: Pillars of Fire, Tornado, Crystal Burst, Titan Fist, Spore Bloom and
 * Sculk Awakening. Only light and sound: every solid part is a display entity, nothing in the world is touched.
 * {@code c} is where the loser stands, {@code t} the number of ticks since the explosion began, {@code cinema} the long version of the finale.
 */
final class EarthWonders {
	private EarthWonders() {}

	// ------------------------------------------------------------------ Pillars of Fire

	/** A ring of columns of fire erupts one after the other, their flames bend toward the middle, and a giant column bursts out of the target. */
	static void firePillars(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		int lead = cinema ? 100 : 52;
		double radius = cinema ? 10 : 7.5;
		int n = 8;
		Vec3 ground = c.add(0, 0.06, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 3.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 2.5f, 0.6f);
			Shapes.diskMake(props, "scorch", "minecraft:magma_block", ground, 0.12, 6, true, lead + 60);
		}
		if (t == 1) Shapes.diskTo(props, "scorch", ground, radius + 3, 0.12, 6, lead);
		int gap = Math.max(2, (lead - 30) / n);
		for (int i = 0; i < n; i++) {
			int t0 = 8 + i * gap;
			double a = i * Math.PI * 2 / n + 0.4;
			Vec3 base = polar(c, radius, a, 0);
			double h = 12 + (i % 3) * 3;
			int die = lead + 24 + i;
			rise(props, t, t0, 7, die, "fpm" + i, "minecraft:magma_block", base, 1.4, h, true);
			rise(props, t, t0, 7, die, "fpg" + i, "minecraft:orange_stained_glass", base, 2.6, h, true);
			if (t == t0) {
				ExplosionArt.sound(level, base, SoundEvents.GENERIC_EXPLODE, 1.2f, 1.5f);
				fx(level, viewers, ParticleTypes.EXPLOSION, base.add(0, 1, 0), 1, 0.1, 0.0);
			}
			if (t > t0 && t < lead + 8) {
				Vec3 top = base.add(0, h, 0);
				fx(level, viewers, ParticleTypes.FLAME, top, 3, 0.7, 0.04);
				if (t % 2 == 0) fx(level, viewers, ParticleTypes.LAVA, top, 1, 0.6, 0.0);
				if (t % 3 == 0) fx(level, viewers, ParticleTypes.LARGE_SMOKE, top.add(0, 1, 0), 1, 0.5, 0.02);
				fx(level, viewers, ParticleTypes.FLAME, base.add(0, 0.3, 0), 2, 1.2, 0.02);
				if (t >= lead - 20 && t < lead && t % 2 == 0) {
					Vec3 goal = c.add(0, 14, 0);
					ExplosionArt.fly(level, viewers, ParticleTypes.FLAME, top, goal.subtract(top).normalize(), 0.9);
				}
			}
			if (t == lead + 10 + i) {
				sink(props, "fpm" + i, base, 1.4, 12);
				sink(props, "fpg" + i, base, 2.6, 12);
			}
		}
		rise(props, t, lead, 5, lead + 46, "fpc", "minecraft:magma_block", c, 5.0, 38, true);
		rise(props, t, lead, 5, lead + 46, "fpo", "minecraft:orange_stained_glass", c, 8.5, 38, true);
		rise(props, t, lead, 4, lead + 46, "fpw", "minecraft:white_concrete", c, 2.4, 44, true);
		if (t == lead) {
			Vec3 mid = c.add(0, 1, 0);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 4.0f, 0.8f);
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.7f);
			fx(level, viewers, ParticleTypes.EXPLOSION_EMITTER, mid, 1, 0.0, 0.0);
			ExplosionArt.blast(level, viewers, ParticleTypes.FLAME, mid, cinema ? 110 : 70, 1.5, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.LAVA, mid, cinema ? 60 : 36, 1.0, true);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.FLAME, ground, cinema ? 90 : 60, 1.3, 0.03);
			ExplosionArt.debris(props, mid, c.y, new String[] {"minecraft:magma_block", "minecraft:netherrack", "minecraft:blackstone", "minecraft:orange_concrete"},
				cinema ? 24 : 12, 1.3, 1.7, t, 55);
		}
		if (t == lead + 6) ExplosionArt.shockwave(level, viewers, ParticleTypes.LARGE_SMOKE, ground, 60, 0.9, 0.04);
		if (t > lead && t < lead + 34) {
			ThreadLocalRandom rnd = ThreadLocalRandom.current();
			fx(level, viewers, ParticleTypes.FLAME, c.add((rnd.nextDouble() - 0.5) * 3, rnd.nextDouble() * 30, (rnd.nextDouble() - 0.5) * 3), 4, 1.5, 0.05);
			fx(level, viewers, ParticleTypes.LAVA, c.add(0, rnd.nextDouble() * 22, 0), 1, 3.0, 0.0);
		}
		if (t == lead + 36) {
			sink(props, "fpc", c, 5.0, 10);
			sink(props, "fpo", c, 8.5, 10);
			sink(props, "fpw", c, 2.4, 10);
		}
	}

	// ------------------------------------------------------------------ Tornado

	private static final String[] CLOUD_BLOCKS = {"minecraft:white_concrete", "minecraft:light_gray_concrete", "minecraft:gray_concrete"};

	/** A funnel of cloud-colored blocks climbs up from the ground and spins, lightning flickers inside, and it bursts outward. */
	static void tornado(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 108 : 56;
		int layers = 11;
		double top = cinema ? 22 : 16;
		double widen = cinema ? 0.75 : 0.55;
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.5f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.PORTAL_TRAVEL, 0.8f, 0.5f);
		}
		rise(props, t, 2, 10, lead + 2, "twcore", "minecraft:white_stained_glass", c, 1.4, top + 2, true);
		for (int j = 0; j < layers; j++) {
			int tj = 3 + j * (cinema ? 3 : 2);
			double y = 0.6 + j * top / layers;
			double r = 1.0 + j * widen;
			double size = 1.1 + j * 0.17;
			double w = 0.30 - 0.018 * j;
			for (int k = 0; k < 3; k++) {
				String key = "tw" + j + "_" + k;
				double ang = k * Math.PI * 2 / 3 + j * 0.7 + t * w;
				Vec3 pos = polar(c, r, ang, y);
				Q rot = Q.yaw(j * 31 + k * 57).mul(Q.pitch(18 + k * 14));
				if (t == tj) {
					props.lifetime(props.name(key, props.block(CLOUD_BLOCKS[(j + k) % 3], pos, cube(0.2), rot, true)), lead + 60);
				} else if (t == tj + 1) {
					props.reshape(props.named(key), pos, cube(size), rot, 6);
				} else if (t > tj + 1 && t < lead && t % 2 == 0) {
					props.move(props.named(key), pos);
				}
				if (t == lead) {
					double speed = 0.5 + 0.05 * j;
					props.throwPiece(props.named(key), new Vec3(Math.cos(ang) * speed, 0.5 + 0.06 * j, Math.sin(ang) * speed), c.y);
				}
			}
			if (t >= tj && t < lead) {
				for (int q = 0; q < 3; q++) {
					fx(level, viewers, ParticleTypes.CLOUD, polar(c, r + 0.8, t * w + q * 2.094 + 1.0, y), 1, 0.25, 0.02);
				}
				if (rnd.nextInt(4) == 0) fx(level, viewers, ParticleTypes.ELECTRIC_SPARK, polar(c, r * 0.5, rnd.nextDouble() * 6.28, y), 2, 0.4, 0.05);
			}
		}
		if (t > 4 && t < lead && t % 3 == 0) {
			fx(level, viewers, ParticleTypes.POOF, polar(c, 3 + rnd.nextDouble() * 7, rnd.nextDouble() * 6.28, 0.3), 3, 0.4, 0.05);
		}
		if (t > lead - 36 && t < lead && t % 6 == 0) {
			Vec3 from = c.add(0, top + 3, 0);
			Vec3 to = polar(c, 3 + rnd.nextDouble() * 6, rnd.nextDouble() * Math.PI * 2, 0.2);
			Vec3 mid = from.add(to.subtract(from).scale(0.5)).add((rnd.nextDouble() - 0.5) * 4, 0, (rnd.nextDouble() - 0.5) * 4);
			props.lifetime(Shapes.line(props, "minecraft:white_concrete", from, mid, 0.22, true), t + 3);
			props.lifetime(Shapes.line(props, "minecraft:white_concrete", mid, to, 0.22, true), t + 3);
			ExplosionArt.sound(level, to, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.5f, 1.4f);
		}
		if (t == lead) {
			Vec3 mid = c.add(0, 1, 0);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 5.0f, 0.7f);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.8f);
			ExplosionArt.blast(level, viewers, ParticleTypes.CLOUD, mid.add(0, 4, 0), cinema ? 110 : 70, 1.4, false);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, c.add(0, 0.3, 0), cinema ? 90 : 60, 1.4, 0.05);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.POOF, c.add(0, 1.2, 0), 50, 1.0, 0.1);
			sink(props, "twcore", c, 1.4, 6);
		}
		if (t == lead + 5) ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, c.add(0, 0.5, 0), 70, 0.8, 0.05);
		if (t == lead + 11) ExplosionArt.shockwave(level, viewers, ParticleTypes.ELECTRIC_SPARK, c.add(0, 0.8, 0), 50, 0.6, 0.05);
	}

	// ------------------------------------------------------------------ Crystal Burst

	/** A crystal seed swells above the target, then bursts into spikes in every direction; they hold for a moment and shatter. */
	static void crystalBurst(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 76 : 40;
		int hold = cinema ? 44 : 24;
		int shatter = lead + hold;
		int spikes = cinema ? 26 : 16;
		Vec3 seed = c.add(0, 3.2, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.6f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 2.0f, 1.6f);
			WonderKit.orbMake(props, "cbs", "minecraft:amethyst_block", seed, true, lead + 4);
			WonderKit.orbMake(props, "cbg", "minecraft:purple_stained_glass", seed, true, lead + 4);
		}
		if (t < lead) {
			if (t > 0 && t % 4 == 1) {
				double u = ease(t / (double) lead);
				orbTo(props, "cbs", seed, 0.4 + 2.2 * u, t * 6, 5);
				orbTo(props, "cbg", seed, 0.8 + 3.6 * u, -t * 4, 5);
			}
			double u = clamp01(t / (double) lead);
			for (int k = 0; k < 2; k++) {
				Vec3 start = polar(seed, 9 - 5 * u, rnd.nextDouble() * Math.PI * 2, (rnd.nextDouble() - 0.5) * 8);
				ExplosionArt.fly(level, viewers, k == 0 ? ParticleTypes.END_ROD : ParticleTypes.ELECTRIC_SPARK, start, seed.subtract(start).normalize(), 0.5);
			}
			ExplosionArt.ring(level, viewers, WonderKit.PURPLE, c.add(0, 0.2, 0), 9 * (1 - u) + 1.5, 24, t * 0.1, 0);
			if (t % 8 == 0) ExplosionArt.sound(level, seed, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 0.6f + 1.2f * (float) u);
		}
		for (int i = 0; i < spikes; i++) {
			Vec3 d = ExplosionArt.sphereDir(i, spikes, true);
			double len = (cinema ? 8 : 6) + (i % 4) * 2.2;
			double thick = 1.2 + (i % 3) * 0.35;
			Vec3 knee = seed.add(d.scale(len * 0.55));
			Vec3 tip = seed.add(d.scale(len));
			beam(props, t, lead, 3, shatter, "cba" + i, "minecraft:amethyst_block", seed, knee, thick);
			beam(props, t, lead, 3, shatter, "cbb" + i, "minecraft:purple_stained_glass", knee, tip, thick * 0.45);
		}
		if (t == lead) {
			ExplosionArt.sound(level, seed, SoundEvents.AMETHYST_BLOCK_RESONATE, 5.0f, 0.5f);
			ExplosionArt.sound(level, seed, SoundEvents.GENERIC_EXPLODE, 3.0f, 1.3f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, seed, 60, 1.2, 0.02);
			ExplosionArt.blast(level, viewers, ParticleTypes.ELECTRIC_SPARK, seed, 90, 1.4, false);
		}
		if (t > lead && t < shatter && t % 3 == 0) {
			fx(level, viewers, ParticleTypes.END_ROD, seed.add((rnd.nextDouble() - 0.5) * 12, (rnd.nextDouble() - 0.2) * 6, (rnd.nextDouble() - 0.5) * 12), 2, 0.3, 0.02);
		}
		if (t == shatter) {
			ExplosionArt.sound(level, seed, SoundEvents.GLASS_BREAK, 5.0f, 0.7f);
			ExplosionArt.sound(level, seed, SoundEvents.AMETHYST_BLOCK_CHIME, 4.0f, 1.6f);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, seed, cinema ? 130 : 80, 1.6, false);
			ExplosionArt.blast(level, viewers, ParticleTypes.FIREWORK, seed, 70, 1.1, false);
			ExplosionArt.debris(props, seed, c.y, new String[] {"minecraft:amethyst_block", "minecraft:purple_stained_glass", "minecraft:white_stained_glass"},
				cinema ? 26 : 14, 1.3, 1.5, t, 50);
			ExplosionArt.firework(level, seed.add(0, 3, 0), FireworkExplosion.Shape.STAR, new int[] {0xB06CFF, 0xFFFFFF, 0x7A3CFF}, new int[] {0xE0C8FF});
		}
	}

	// ------------------------------------------------------------------ Titan Fist

	/** A part of the fist: its block, where it is relative to the middle of the fist, and its size. */
	private record Part(String state, double dx, double dy, double dz, double sx, double sy, double sz) {}

	private static final Part[] FIST = {
		new Part("minecraft:stone", 0, 0, 0, 6.4, 6.0, 6.4),
		new Part("minecraft:polished_andesite", 0, -2.25, 3.35, 6.2, 1.2, 1.1),
		new Part("minecraft:polished_andesite", 0, -0.75, 3.35, 6.2, 1.2, 1.1),
		new Part("minecraft:polished_andesite", 0, 0.75, 3.35, 6.2, 1.2, 1.1),
		new Part("minecraft:polished_andesite", 0, 2.25, 3.35, 6.2, 1.2, 1.1),
		new Part("minecraft:andesite", -3.6, 1.0, 1.8, 1.6, 1.8, 3.6),
		new Part("minecraft:deepslate", 0, 6.0, 0, 4.4, 6.0, 4.4),
		new Part("minecraft:stone_bricks", 0, 27.0, 0, 3.6, 36.0, 3.6)};

	/** A fist the size of a house hangs in the sky, shakes, and slams down onto the target: crater, cracks, rock and dust. */
	static void titanFist(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 80 : 40;
		int slam = cinema ? 16 : 10;
		int fall = lead - slam;
		double hover = cinema ? 28 : 22;
		double rest = 3.0;
		double y;
		double sway = 0;
		if (t < fall) {
			y = hover + 12 * (1 - ease(t / (double) fall));
			sway = Math.sin(t * 0.35) * 0.3 * ease(t / (double) fall);
		} else if (t < lead) {
			double u = (t - fall) / (double) slam;
			y = hover + (rest - hover) * u * u;
		} else if (t < lead + 34) {
			y = rest;
		} else {
			y = rest + (hover + 25 - rest) * ease((t - lead - 34) / 22.0);
		}
		Vec3 mid = new Vec3(c.x + sway, c.y + y, c.z);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.5f, 0.4f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 2.5f, 0.5f);
		}
		if (t == 1) {
			for (int i = 0; i < FIST.length; i++) {
				Part p = FIST[i];
				props.lifetime(props.name("tf" + i, props.block(p.state(), mid.add(p.dx(), p.dy(), p.dz()), new Vec3(p.sx(), p.sy(), p.sz()), Q.ID, false)), lead + 92);
			}
		}
		boolean moving = t > 1 && (t < fall ? t % 2 == 0 : t < lead + 1 || (t >= lead + 34 && t < lead + 58));
		if (moving) {
			for (int i = 0; i < FIST.length; i++) {
				Part p = FIST[i];
				props.move(props.named("tf" + i), mid.add(p.dx(), p.dy(), p.dz()));
			}
		}
		if (t > 1 && t < lead) {
			fx(level, viewers, ParticleTypes.ASH, mid.add(0, -3, 0), 6, 3.2, 0.02);
			if (t % 3 == 0) fx(level, viewers, ParticleTypes.CLOUD, mid.add(0, -3.2, 0), 3, 2.6, 0.05);
		}
		if (t == fall) ExplosionArt.sound(level, c, SoundEvents.PLAYER_ATTACK_STRONG, 4.0f, 0.5f);
		if (t == lead) {
			Vec3 low = c.add(0, 0.1, 0);
			ExplosionArt.sound(level, c, SoundEvents.ANVIL_LAND, 5.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 5.0f, 0.5f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0f, 0.6f);
			fx(level, viewers, ParticleTypes.EXPLOSION_EMITTER, c.add(0, 1, 0), 1, 0.0, 0.0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, low.add(0, 0.2, 0), cinema ? 100 : 64, 1.6, 0.04);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.POOF, low.add(0, 1.0, 0), 60, 1.1, 0.08);
			ExplosionArt.blast(level, viewers, ParticleTypes.CLOUD, c.add(0, 1, 0), cinema ? 90 : 60, 1.1, true);
			ExplosionArt.debris(props, c.add(0, 1, 0), c.y, new String[] {"minecraft:stone", "minecraft:cobblestone", "minecraft:andesite", "minecraft:deepslate", "minecraft:coarse_dirt"},
				cinema ? 30 : 16, 1.4, 1.8, t, 60);
			Shapes.diskMake(props, "tfd", "minecraft:coarse_dirt", low, 0.14, 6, false, lead + 90);
			Shapes.diskMake(props, "tfk", "minecraft:black_concrete", low.add(0, 0.03, 0), 0.14, 5, false, lead + 90);
		}
		if (t == lead + 1) {
			Shapes.diskTo(props, "tfd", c.add(0, 0.1, 0), 10, 0.14, 6, 6);
			Shapes.diskTo(props, "tfk", c.add(0, 0.13, 0), 5, 0.14, 5, 6);
		}
		int cracks = cinema ? 12 : 8;
		for (int i = 0; i < cracks; i++) {
			double a = i * Math.PI * 2 / cracks + 0.3;
			double len = 9 + (i % 3) * 3;
			beam(props, t, lead + 1, 4, lead + 90, "tfc" + i, "minecraft:black_concrete", c.add(0, 0.2, 0), polar(c, len, a, 0.2), 0.45);
		}
		if (t == lead + 6) ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, c.add(0, 0.4, 0), 70, 0.9, 0.04);
		if (t > lead && t < lead + 30 && t % 3 == 0) {
			fx(level, viewers, ParticleTypes.CLOUD, polar(c, 1 + rnd.nextDouble() * 8, rnd.nextDouble() * 6.28, 0.4), 4, 0.5, 0.08);
		}
	}

	// ------------------------------------------------------------------ Spore Bloom

	/** Giant mushrooms sprout around the target and a huge one rises from it; their caps pop in a cloud of spores. */
	static void sporeBloom(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 108 : 56;
		int count = cinema ? 7 : 5;
		Vec3 ground = c.add(0, 0.06, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 2.5f, 0.7f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 2.5f, 0.7f);
			Shapes.diskMake(props, "sbd", "minecraft:mycelium", ground, 0.12, 5, false, lead + 60);
		}
		if (t == 1) Shapes.diskTo(props, "sbd", ground, cinema ? 13 : 10, 0.12, 5, lead - 10);
		for (int i = 0; i < count; i++) {
			boolean giant = i == count - 1;
			double a = i * Math.PI * 2 / (count - 1) + 0.5;
			Vec3 base = giant ? c : polar(c, cinema ? 9 : 7, a, 0);
			double h = giant ? 15 : 7 + (i % 3) * 1.5;
			double stem = giant ? 3.0 : 1.5;
			double cap = giant ? 13 : 6.4 + (i % 2);
			double capH = cap * 0.38;
			int t0 = giant ? 14 : 4 + i * (cinema ? 7 : 4);
			String capState = giant || i % 2 == 0 ? "minecraft:red_mushroom_block" : "minecraft:brown_mushroom_block";
			Vec3 top = base.add(0, h, 0);
			rise(props, t, t0, 8, lead + 40, "sbs" + i, "minecraft:mushroom_stem", base, stem, h, false);
			Vec3 capMid = top.add(0, capH / 2 - 0.2, 0);
			if (t == t0 + 8) {
				props.lifetime(props.name("sbc" + i, props.block(capState, capMid, cube(0.3), Q.ID, false)), lead);
				props.lifetime(props.name("sbt" + i, props.block(capState, top.add(0, capH, 0), cube(0.3), Q.ID, false)), lead);
			}
			if (t == t0 + 9) {
				props.reshape(props.named("sbc" + i), capMid, new Vec3(cap, capH, cap), Q.ID, 8);
				props.reshape(props.named("sbt" + i), top.add(0, capH + capH * 0.35, 0), new Vec3(cap * 0.66, capH * 0.7, cap * 0.66), Q.ID, 8);
			}
			if (t == t0 + 18) {
				for (int s = 0; s < 4; s++) {
					double sa = s * Math.PI / 2 + i;
					Vec3 at = top.add(Math.cos(sa) * cap * 0.28, capH * 1.75, Math.sin(sa) * cap * 0.28);
					props.lifetime(props.block("minecraft:white_concrete", at, cube(cap * 0.14), Q.yaw(s * 20), false), lead);
				}
			}
			if (t > t0 + 18 && t < lead && t % 10 == 0) {
				double breath = 1 + 0.06 * ((t / 10) % 2 == 0 ? 1 : -1);
				props.reshape(props.named("sbc" + i), capMid, new Vec3(cap * breath, capH, cap * breath), Q.ID, 8);
			}
			if (t == t0) ExplosionArt.sound(level, base, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 0.6f + (i % 4) * 0.2f);
			if (t > t0 + 9 && t < lead) {
				fx(level, viewers, ParticleTypes.CRIMSON_SPORE, top.add(0, capH + 0.5, 0), 3, cap * 0.4, 0.02);
				if (t % 2 == 0) fx(level, viewers, ParticleTypes.WARPED_SPORE, top.add(0, capH, 0), 2, cap * 0.45, 0.02);
				if (t % 4 == 0) fx(level, viewers, ParticleTypes.HAPPY_VILLAGER, top.add(0, capH + 1.0, 0), 2, cap * 0.35, 0.0);
			}
			if (t == lead) {
				Vec3 puff = top.add(0, capH, 0);
				fx(level, viewers, ParticleTypes.POOF, puff, giant ? 60 : 24, cap * 0.35, 0.15);
				fx(level, viewers, ParticleTypes.CRIMSON_SPORE, puff, giant ? 150 : 60, cap * 0.5, 0.2);
				ExplosionArt.debris(props, puff, c.y, new String[] {"minecraft:red_mushroom_block", "minecraft:brown_mushroom_block", "minecraft:white_concrete", "minecraft:mushroom_stem"},
					giant ? 16 : 5, 1.1, 1.4, t, 45);
			}
			if (t == lead + 8 + i) sink(props, "sbs" + i, base, stem, 14);
		}
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 4.0f, 0.9f);
			ExplosionArt.sound(level, c, SoundEvents.LIGHTNING_BOLT_THUNDER, 2.0f, 1.2f);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CRIMSON_SPORE, ground.add(0, 0.5, 0), cinema ? 90 : 60, 1.0, 0.05);
			ExplosionArt.blast(level, viewers, ParticleTypes.WARPED_SPORE, c.add(0, 14, 0), 90, 1.0, false);
		}
		if (t > lead && t < lead + 60) {
			fx(level, viewers, ParticleTypes.CRIMSON_SPORE, c.add((rnd.nextDouble() - 0.5) * 18, 4 + rnd.nextDouble() * 12, (rnd.nextDouble() - 0.5) * 18), 4, 1.2, 0.0);
			if (t % 3 == 0) fx(level, viewers, ParticleTypes.WARPED_SPORE, c.add((rnd.nextDouble() - 0.5) * 18, 3 + rnd.nextDouble() * 10, (rnd.nextDouble() - 0.5) * 18), 3, 1.2, 0.0);
		}
	}

	// ------------------------------------------------------------------ Sculk Awakening

	/** Sculk spreads over the ground from a shrieker, tendrils sprout, sonic rings pulse outward, and a sonic beam blasts into the sky. */
	static void sculkAwakening(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 c, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int lead = cinema ? 108 : 56;
		int tendrils = cinema ? 9 : 6;
		Vec3 ground = c.add(0, 0.06, 0);
		if (t == 0) {
			ExplosionArt.sound(level, c, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			Shapes.diskMake(props, "sk", "minecraft:sculk", ground, 0.14, 6, false, lead + 60);
		}
		if (t == 1) Shapes.diskTo(props, "sk", ground, cinema ? 12 : 9, 0.14, 6, lead);
		rise(props, t, 4, 12, lead + 22, "ssh", "minecraft:sculk_shrieker", c, 2.4, 2.4, false);
		for (int i = 0; i < tendrils; i++) {
			int t0 = 10 + i * (cinema ? 6 : 4);
			double a = i * Math.PI * 2 / tendrils + 0.2;
			double r = (cinema ? 4.5 : 3.5) + (i % 3) * 2.0;
			Vec3 base = polar(c, r, a, 0);
			double h = 3.0 + (i % 4) * 1.4;
			rise(props, t, t0, 8, lead, "skt" + i, "minecraft:sculk", base, 0.9, h, false);
			rise(props, t, t0, 8, lead, "skl" + i, "minecraft:light_blue_stained_glass", base, 0.4, h + 0.8, true);
			if (t > t0 + 8 && t < lead && t % 3 == 0) fx(level, viewers, ParticleTypes.SCULK_SOUL, base.add(0, h + 0.8, 0), 1, 0.2, 0.02);
		}
		int[] pulses = {lead / 4, lead / 2, lead * 3 / 4};
		for (int k = 0; k < pulses.length; k++) {
			if (t == pulses[k]) {
				ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 2.5f, 1.2f - 0.15f * k);
				for (int q = 0; q < 8; q++) {
					fx(level, viewers, ParticleTypes.SONIC_BOOM, polar(c, 3.5 + k * 1.5, q * Math.PI / 4 + k * 0.4, 1.2), 1, 0.0, 0.0);
				}
				ExplosionArt.shockwave(level, viewers, ParticleTypes.SCULK_SOUL, c.add(0, 0.4, 0), 40, 0.5 + 0.1 * k, 0.0);
			}
		}
		if (t > 6 && t < lead && t % 2 == 0) {
			fx(level, viewers, WonderKit.SCULK, polar(c, rnd.nextDouble() * 8, rnd.nextDouble() * 6.28, 0.3 + rnd.nextDouble() * 3), 2, 0.3, 0.01);
		}
		rise(props, t, lead, 4, lead + 40, "skb", "minecraft:light_blue_stained_glass", c, 3.4, 56, true);
		rise(props, t, lead, 4, lead + 40, "skc", "minecraft:white_concrete", c, 1.2, 60, true);
		rise(props, t, lead, 5, lead + 40, "sko", "minecraft:cyan_stained_glass", c, 5.6, 52, true);
		if (t == lead) {
			ExplosionArt.sound(level, c, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 0.8f);
			ExplosionArt.sound(level, c, SoundEvents.GENERIC_EXPLODE, 3.0f, 0.8f);
			ExplosionArt.sound(level, c, SoundEvents.BEACON_ACTIVATE, 3.0f, 0.6f);
			for (int ring = 0; ring < 3; ring++) {
				int pts = 10 + ring * 6;
				for (int q = 0; q < pts; q++) {
					fx(level, viewers, ParticleTypes.SONIC_BOOM, polar(c, 3.0 + ring * 3.0, q * Math.PI * 2 / pts, 1.0 + ring * 0.5), 1, 0.0, 0.0);
				}
			}
			ExplosionArt.shockwave(level, viewers, ParticleTypes.ELECTRIC_SPARK, c.add(0, 0.5, 0), cinema ? 90 : 60, 1.3, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.SCULK_SOUL, c.add(0, 1, 0), cinema ? 100 : 60, 1.2, true);
			ExplosionArt.debris(props, c.add(0, 1, 0), c.y, new String[] {"minecraft:sculk", "minecraft:deepslate", "minecraft:cyan_stained_glass"}, cinema ? 22 : 12, 1.3, 1.6, t, 50);
		}
		if (t > lead && t < lead + 30 && t % 2 == 0) {
			fx(level, viewers, ParticleTypes.ELECTRIC_SPARK, c.add((rnd.nextDouble() - 0.5) * 4, rnd.nextDouble() * 40, (rnd.nextDouble() - 0.5) * 4), 3, 1.0, 0.1);
			fx(level, viewers, ParticleTypes.SCULK_SOUL, c.add((rnd.nextDouble() - 0.5) * 6, rnd.nextDouble() * 10, (rnd.nextDouble() - 0.5) * 6), 2, 0.5, 0.04);
		}
		if (t == lead + 26) {
			sink(props, "skb", c, 3.4, 10);
			sink(props, "skc", c, 1.2, 10);
			sink(props, "sko", c, 5.6, 10);
		}
	}
}
