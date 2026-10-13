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
 * The two explosions that strike the ground from the sky: Gungnir (a giant black needle that falls without a sound, and the thunder comes
 * late) and the Seven Stars (the Big Dipper is drawn on the floor by seven stars that fall one after the other). Only light and sound.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class StrikeArt {
	private static final ParticleOptions RED_DUST = new DustParticleOptions(0xFF3030, 1.7f);
	private static final ParticleOptions BLACK_DUST = new DustParticleOptions(0x0A0A0A, 2.6f);

	private StrikeArt() {}

	// ------------------------------------------------------------------ Gungnir

	/** The height the needle starts at above the target. */
	public static double gungnirHeight(boolean cinema) {
		return cinema ? 110 : 70;
	}

	/** How long the needle falls, how long until the sound arrives, and how long it stays after it lands. */
	public static int gungnirFall(boolean cinema) {
		return cinema ? 56 : 20;
	}

	public static int gungnirDelay(boolean cinema) {
		return cinema ? 22 : 12;
	}

	public static int gungnirAfter(boolean cinema) {
		return cinema ? 100 : 84;
	}

	public static int gungnirTicks(boolean cinema) {
		return gungnirFall(cinema) + gungnirAfter(cinema);
	}

	private static final double TAPER = 1.3;
	private static final double[] TAPER_WIDTH = {0.15, 0.3, 0.5, 0.7};
	private static final double SHAFT = 0.9;

	private static double shaftLength(boolean cinema) {
		return cinema ? 46 : 34;
	}

	/** Where the tip of the needle is {@code t} ticks after it was released. */
	public static Vec3 gungnirTip(Vec3 target, int t, boolean cinema) {
		int fall = gungnirFall(cinema);
		double u = Math.max(0, Math.min(1, t / (double) fall));
		double top = target.y + gungnirHeight(cinema);
		double bottom = target.y - 2.4; // the needle stands in the ground
		return new Vec3(target.x, top + (bottom - top) * Math.pow(u, 2.2), target.z);
	}

	/** Where the camera of the finale should look to follow the needle (its middle). */
	public static Vec3 gungnirHead(Vec3 target, int t, boolean cinema) {
		return gungnirTip(target, t, cinema).add(0, 8, 0);
	}

	private static Vec3 partCenter(Vec3 tip, int part, boolean cinema) {
		double length = shaftLength(cinema);
		if (part < 4) return tip.add(0, TAPER * part + TAPER / 2, 0);
		if (part == 4) return tip.add(0, TAPER * 4 + length / 2, 0);
		return tip.add(0, TAPER * 4 + length / 2 + 0.2, 0); // the sleeve
	}

	private static Vec3 partSize(int part, boolean cinema) {
		double length = shaftLength(cinema);
		if (part < 4) return new Vec3(TAPER_WIDTH[part], TAPER, TAPER_WIDTH[part]);
		if (part == 4) return new Vec3(SHAFT, length, SHAFT);
		return new Vec3(1.7, length + 0.6, 1.7);
	}

	private static String partBlock(int part) {
		return part == 5 ? "minecraft:red_stained_glass" : "minecraft:black_concrete";
	}

	public static void gungnir(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int fall = gungnirFall(cinema);
		int delay = gungnirDelay(cinema);
		int after = gungnirAfter(cinema);
		double gy = target.y;
		if (t == 0) {
			Vec3 tip = gungnirTip(target, 0, cinema);
			for (int part = 0; part < 6; part++) {
				props.name("g" + part, props.block(partBlock(part), partCenter(tip, part, cinema), partSize(part, cinema), Q.ID, true));
			}
		}
		if (t < fall) {
			// it falls without a sound; only a dark trail and a few red sparks follow it
			if (t % 2 == 0) {
				Vec3 tip = gungnirTip(target, t, cinema);
				for (int part = 0; part < 6; part++) props.move(props.named("g" + part), partCenter(tip, part, cinema));
			}
			Vec3 tip = gungnirTip(target, t, cinema);
			for (int k = 0; k < 3; k++) {
				double y = tip.y + 4 + rnd.nextDouble() * shaftLength(cinema);
				ExplosionArt.emit(level, viewers, k == 0 ? RED_DUST : ParticleTypes.SQUID_INK, tip.x + (rnd.nextDouble() - 0.5) * 1.6, y, tip.z + (rnd.nextDouble() - 0.5) * 1.6,
					1, 0.2, 0.2, 0.2, 0.0);
			}
			if (t > fall - 22) {
				// the ground feels it coming: a dark ring closes in on the place
				double left = fall - t;
				ExplosionArt.ring(level, viewers, BLACK_DUST, target.add(0, 0.25, 0), 2.5 + left * 0.45, 36, t * 0.15, 0);
				ExplosionArt.ring(level, viewers, RED_DUST, target.add(0, 0.25, 0), 1.6 + left * 0.3, 24, -t * 0.2, 0);
			}
			return;
		}
		int w = t - fall;
		Vec3 ground = target.add(0, 0.3, 0);
		if (w == 0) {
			Vec3 tip = gungnirTip(target, fall, cinema);
			for (int part = 0; part < 6; part++) props.move(props.named("g" + part), partCenter(tip, part, cinema));
			// white flash, and no sound yet
			Props.Prop flash = props.item("minecraft:firework_star", target.add(0, 3, 0), 3.0);
			props.resize(flash, cinema ? 60.0 : 36.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.SONIC_BOOM, target.x, target.y + 2, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 120, 2.2, 0.02);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, ground, 90, 1.5, 0.04);
			ExplosionArt.shockwave(level, viewers, RED_DUST, ground, 80, 1.0, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, target.add(0, 1.2, 0), 80, 1.2, true);
			// the crater: dark ground with a glowing middle, the needle in the hole
			String[] blocks = {"minecraft:black_concrete", "minecraft:magma_block", "minecraft:black_concrete"};
			for (int i = 0; i < 3; i++) {
				for (int k = 0; k < 4; k++) {
					props.lifetime(props.name("crater" + i + "_" + k, props.block(blocks[i], new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(0.3, 0.05, 0.3),
						Q.yaw(k * 22.5), true)), t + after);
				}
			}
			// the cracks that run out of the crater
			int cracks = cinema ? 16 : 10;
			for (int c = 0; c < cracks; c++) {
				double a = c * Math.PI * 2 / cracks + rnd.nextDouble() * 0.3;
				double from = 2.0;
				double to = 6.0 + rnd.nextDouble() * (cinema ? 10 : 6);
				Vec3 p1 = new Vec3(target.x + Math.cos(a) * from, gy + 0.14, target.z + Math.sin(a) * from);
				Vec3 p2 = new Vec3(target.x + Math.cos(a) * to, gy + 0.14, target.z + Math.sin(a) * to);
				props.lifetime(props.name("crack" + c, Shapes.lineGrowing(props, c % 2 == 0 ? "minecraft:orange_concrete" : "minecraft:yellow_concrete", p1, p2, 0.32, true)), t + after);
			}
			// the shock ring: planks that run outward on the ground
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				Vec3 tangent = new Vec3(-Math.sin(a), 0, Math.cos(a));
				Props.Prop plank = props.block("minecraft:white_stained_glass", new Vec3(target.x + Math.cos(a) * 2.0, gy + 1.4, target.z + Math.sin(a) * 2.0),
					new Vec3(0.25, 3.0, 2.8), Shapes.facing(tangent), true);
				props.name("ring" + k, plank);
				props.lifetime(plank, t + 14);
			}
		}
		if (w == 1) {
			double[] radii = {7.0, 5.0, 1.5};
			for (int i = 0; i < 3; i++) {
				double side = Shapes.Disk.sideFor(radii[i], 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("crater" + i + "_" + k), new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(side, 0.05, side), Q.yaw(k * 22.5), 8);
				}
			}
			int cracks = cinema ? 16 : 10;
			for (int c = 0; c < cracks; c++) {
				Props.Prop crack = props.named("crack" + c);
				if (crack == null) continue;
				// the crack is reshaped to the line it was made for (its middle tells where it points)
				Vec3 start = crack.pos();
				Vec3 away = new Vec3(start.x - target.x, 0, start.z - target.z);
				if (away.lengthSqr() < 1e-6) continue;
				Vec3 dir = away.normalize();
				double length = 2 * away.length() - 4.0;
				Vec3 p1 = new Vec3(target.x + dir.x * 2.0, gy + 0.14, target.z + dir.z * 2.0);
				Vec3 p2 = new Vec3(target.x + dir.x * (2.0 + Math.max(0.5, length)), gy + 0.14, target.z + dir.z * (2.0 + Math.max(0.5, length)));
				Shapes.growLine(props, crack, p1, p2, 0.32, 8);
			}
		}
		if (w >= 1 && w <= 14 && w % 2 == 0) {
			// the planks run outward, shorter and shorter
			double r = 2.0 + w * 2.6;
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				props.move(props.named("ring" + k), new Vec3(target.x + Math.cos(a) * r, gy + 1.4 * (1 - w / 18.0), target.z + Math.sin(a) * r));
			}
		}
		if (w == delay) {
			// the sound arrives late, like thunder after lightning
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 8.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 8.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 0.7f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.5f);
			ExplosionArt.blast(level, viewers, ParticleTypes.LAVA, target.add(0, 1, 0), 40, 1.1, true);
			ExplosionArt.debris(props, target.add(0, 1, 0), target.y, new String[] {"minecraft:magma_block", "minecraft:blackstone", "minecraft:obsidian"}, cinema ? 18 : 8, 1.5, 1.8, t, 60);
			int[] fire = {0xFF5A1F, 0xFFB347, 0xFFFFFF};
			ExplosionArt.firework(level, target.add(0, 14, 0), FireworkExplosion.Shape.LARGE_BALL, fire, new int[] {0x2A0005});
		}
		if (w < 60) {
			// fire and smoke rise from the crater for a long time
			ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, target.x, target.y + 0.5, target.z, 4, 2.4, 0.4, 2.4, 0.04);
			ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, target.x, target.y + 1, target.z, 2, 2.0, 0.6, 2.0, 0.03);
			if (w % 3 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.LAVA, target.x, target.y + 0.6, target.z, 1, 3.0, 0.2, 3.0, 0.0);
		}
		if (w < after && w % 2 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, target.x, target.y + 0.4, target.z, 1, 1.4, 0.1, 1.4, 0.02);
		// at the end the needle thins out and the crater closes
		if (w == after - 16) {
			Vec3 tip = gungnirTip(target, fall, cinema);
			for (int part = 0; part < 6; part++) {
				Vec3 size = partSize(part, cinema);
				props.reshape(props.named("g" + part), partCenter(tip, part, cinema), new Vec3(0.04, size.y, 0.04), Q.ID, 14);
			}
			for (int i = 0; i < 3; i++) {
				double side = Shapes.Disk.sideFor(0.4, 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("crater" + i + "_" + k), new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(side, 0.05, side), Q.yaw(k * 22.5), 14);
				}
			}
		}
	}

	// ------------------------------------------------------------------ Seven Stars

	/** The Big Dipper, in units around the middle of its bowl: x runs along the way from the winner to the loser, y to the right. Handle first. */
	private static final double[][] DIPPER = {
		{-6.7, 1.65}, {-5.1, 1.15}, {-3.6, 1.2}, {-2.1, 0.85}, {-1.4, -1.05}, {1.7, -1.25}, {2.0, 1.4}};

	public static double sevenScale(boolean cinema) {
		return cinema ? 2.3 : 1.5;
	}

	public static int sevenGap(boolean cinema) {
		return cinema ? 14 : 7;
	}

	public static int sevenFall(boolean cinema) {
		return cinema ? 40 : 14;
	}

	/** The tick at which star {@code i} (0 to 6) lands. */
	public static int sevenLanding(int i, boolean cinema) {
		return i * sevenGap(cinema) + sevenFall(cinema);
	}

	/** The tick at which the whole constellation lights up. */
	public static int sevenIgnite(boolean cinema) {
		return sevenLanding(6, cinema) + (cinema ? 16 : 8);
	}

	public static int sevenTicks(boolean cinema) {
		return sevenIgnite(cinema) + (cinema ? 80 : 44);
	}

	/** Where star {@code i} lands. {@code bowl} is the middle of the bowl, {@code forward} the direction from the winner to the loser. */
	public static Vec3 sevenSpot(int i, Vec3 bowl, Vec3 forward, boolean cinema) {
		Vec3 right = Shapes.rightOf(forward);
		double k = sevenScale(cinema);
		return new Vec3(bowl.x + forward.x * DIPPER[i][0] * k + right.x * DIPPER[i][1] * k, bowl.y, bowl.z + forward.z * DIPPER[i][0] * k + right.z * DIPPER[i][1] * k);
	}

	private static double sevenHeight(boolean cinema) {
		return cinema ? 90 : 56;
	}

	/** Where star {@code i} is {@code t} ticks after the explosion started (it is above the sky before it is released). */
	public static Vec3 sevenHead(int i, Vec3 bowl, Vec3 forward, int t, boolean cinema) {
		Vec3 spot = sevenSpot(i, bowl, forward, cinema);
		double height = sevenHeight(cinema);
		Vec3 start = new Vec3(spot.x - forward.x * height * 0.3, spot.y + height, spot.z - forward.z * height * 0.3);
		int begin = i * sevenGap(cinema);
		double u = Math.max(0, Math.min(1, (t - begin) / (double) sevenFall(cinema)));
		double e = Math.pow(u, 1.8);
		return start.add(spot.subtract(start).scale(e));
	}

	private static String sevenLine(boolean glow) {
		return glow ? "minecraft:yellow_stained_glass" : "minecraft:white_concrete";
	}

	/** The line between two stars of the dipper, as (from, to) indexes: the handle, the bowl and the closing side. */
	private static final int[][] LINES = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}, {6, 3}};

	public static void sevenStars(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 bowl, Vec3 forward, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int gap = sevenGap(cinema);
		int fall = sevenFall(cinema);
		double gy = bowl.y;
		double sc = cinema ? 1.0 : 0.7;
		if (t == 0) ExplosionArt.sound(level, bowl.add(0, 30, 0), SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.5f);

		for (int i = 0; i < 7; i++) {
			int begin = i * gap;
			int land = begin + fall;
			if (t == begin) {
				Vec3 head = sevenHead(i, bowl, forward, begin, cinema);
				Vec3 way = sevenSpot(i, bowl, forward, cinema).subtract(head).normalize();
				props.name("st" + i + "a", props.item("minecraft:nether_star", head, 6.0 * sc));
				props.name("st" + i + "b", props.item("minecraft:firework_star", head, 12.0 * sc));
				props.name("st" + i + "c", props.block("minecraft:white_concrete", head.subtract(way.scale(6 * sc)), new Vec3(0.3 * sc, 0.3 * sc, 12 * sc), Q.fromZ(way), true));
				props.name("st" + i + "d", props.block("minecraft:light_blue_stained_glass", head.subtract(way.scale(5 * sc)), new Vec3(1.2 * sc, 1.2 * sc, 10 * sc), Q.fromZ(way), true));
				ExplosionArt.sound(level, bowl.add(0, 25, 0), SoundEvents.FIREWORK_ROCKET_LAUNCH, 3.0f, 0.6f + i * 0.08f);
			}
			if (t > begin && t < land) {
				if (t % 2 == 0) {
					Vec3 head = sevenHead(i, bowl, forward, t, cinema);
					Vec3 way = sevenSpot(i, bowl, forward, cinema).subtract(head);
					way = way.lengthSqr() < 1e-6 ? new Vec3(0, -1, 0) : way.normalize();
					props.move(props.named("st" + i + "a"), head);
					props.move(props.named("st" + i + "b"), head);
					props.move(props.named("st" + i + "c"), head.subtract(way.scale(6 * sc)));
					props.move(props.named("st" + i + "d"), head.subtract(way.scale(5 * sc)));
				}
				Vec3 head = sevenHead(i, bowl, forward, t, cinema);
				ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, head, 6, 0.5 * sc, 0.03);
				ExplosionArt.emit(level, viewers, ExplosionArt.ICE, head.x, head.y + 3, head.z, 2, 0.4, 2.5, 0.4, 0.0);
			}
			if (t == land) {
				Vec3 spot = sevenSpot(i, bowl, forward, cinema);
				for (String key : new String[] {"a", "b", "c", "d"}) props.remove(props.named("st" + i + key));
				boolean big = i == 6;
				ExplosionArt.sound(level, spot, SoundEvents.AMETHYST_BLOCK_CHIME, 5.0f, 0.6f + i * 0.14f);
				ExplosionArt.sound(level, spot, SoundEvents.GENERIC_EXPLODE, 3.0f, 1.2f);
				ExplosionArt.sound(level, spot, SoundEvents.NOTE_BLOCK_CHIME, 4.0f, 0.7f + i * 0.12f);
				ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION, spot.x, spot.y + 0.8, spot.z, 1, 0, 0, 0, 0);
				ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, spot.add(0, 0.3, 0), 60, 1.2, 0.03);
				ExplosionArt.shockwave(level, viewers, ExplosionArt.ICE, spot.add(0, 0.3, 0), 40, 0.8, 0.03);
				ExplosionArt.blast(level, viewers, ParticleTypes.FIREWORK, spot.add(0, 1, 0), 40, 0.9, true);
				// the crater: dark ground with a bright middle
				for (int k = 0; k < 4; k++) {
					props.lifetime(props.name("cr" + i + "_" + k, props.block("minecraft:black_concrete", new Vec3(spot.x, gy + 0.05, spot.z), new Vec3(0.3, 0.05, 0.3), Q.yaw(k * 22.5), true)),
						sevenTicks(cinema));
					props.lifetime(props.name("cl" + i + "_" + k, props.block("minecraft:sea_lantern", new Vec3(spot.x, gy + 0.062, spot.z), new Vec3(0.3, 0.05, 0.3), Q.yaw(k * 22.5), true)),
						sevenTicks(cinema));
				}
				// crystals
				for (int c = 0; c < 3; c++) {
					double a = rnd.nextDouble() * Math.PI * 2;
					double d = 0.5 + rnd.nextDouble() * 0.7;
					double height = (c == 0 ? 2.4 : 1.5 + rnd.nextDouble()) * (big ? 1.4 : 1.0);
					Q rot = Q.axis(Math.cos(a + 1.57), 0, Math.sin(a + 1.57), (rnd.nextDouble() - 0.3) * 0.5);
					Vec3 base = new Vec3(spot.x + Math.cos(a) * d, gy, spot.z + Math.sin(a) * d);
					Vec3 center = base.add(rot.rotate(new Vec3(0, height / 2 - 0.1, 0)));
					props.lifetime(props.growing("minecraft:amethyst_block", center, new Vec3(0.55, height, 0.55), rot, true, 1), sevenTicks(cinema));
				}
				// the light that comes with it
				Vec3 mid = new Vec3(spot.x, gy + 35, spot.z);
				Props.Prop pillar = props.block("minecraft:white_concrete", mid, new Vec3(big ? 1.6 : 0.9, 70, big ? 1.6 : 0.9), Q.ID, true);
				Props.Prop sleeve = props.block("minecraft:light_blue_stained_glass", mid, new Vec3(big ? 3.4 : 2.0, 70, big ? 3.4 : 2.0), Q.ID, true);
				props.reshape(pillar, mid, new Vec3(0.05, 70, 0.05), Q.ID, 12);
				props.reshape(sleeve, mid, new Vec3(0.1, 70, 0.1), Q.ID, 12);
				props.lifetime(pillar, t + 13);
				props.lifetime(sleeve, t + 13);
				if (i == 3 || i == 6) ExplosionArt.firework(level, spot.add(0, 15, 0), FireworkExplosion.Shape.STAR, new int[] {0xFFFFFF, 0xFFD54A, 0x8FD3FF}, new int[] {0xB06CFF});
				// the first lines are drawn between the stars
				if (i >= 1) {
					Vec3 p1 = sevenSpot(i - 1, bowl, forward, cinema).add(0, 0.18, 0);
					Vec3 p2 = spot.add(0, 0.18, 0);
					props.lifetime(props.name("ln" + i + "c", Shapes.lineGrowing(props, sevenLine(false), p1, p2, 0.22 * sc, true)), sevenTicks(cinema));
					props.lifetime(props.name("ln" + i + "g", Shapes.lineGrowing(props, sevenLine(true), p1, p2, 0.6 * sc, true)), sevenTicks(cinema));
				}
				if (i == 6) {
					Vec3 p1 = spot.add(0, 0.18, 0);
					Vec3 p2 = sevenSpot(3, bowl, forward, cinema).add(0, 0.18, 0);
					props.lifetime(props.name("ln7c", Shapes.lineGrowing(props, sevenLine(false), p1, p2, 0.22 * sc, true)), sevenTicks(cinema));
					props.lifetime(props.name("ln7g", Shapes.lineGrowing(props, sevenLine(true), p1, p2, 0.6 * sc, true)), sevenTicks(cinema));
				}
			}
			if (t == land + 1) {
				Vec3 spot = sevenSpot(i, bowl, forward, cinema);
				double radius = i == 6 ? 3.8 : 2.7;
				double side = Shapes.Disk.sideFor(radius, 4);
				double inner = Shapes.Disk.sideFor(radius * 0.6, 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("cr" + i + "_" + k), new Vec3(spot.x, gy + 0.05, spot.z), new Vec3(side, 0.05, side), Q.yaw(k * 22.5), 5);
					props.reshape(props.named("cl" + i + "_" + k), new Vec3(spot.x, gy + 0.062, spot.z), new Vec3(inner, 0.05, inner), Q.yaw(k * 22.5), 5);
				}
				for (int line = 0; line < 2; line++) {
					// the lines grow along their whole length over the next ticks
					if (i >= 1 && line == 0) growConstellationLine(props, i, "c", sevenSpot(i - 1, bowl, forward, cinema), spot, 0.22 * sc, cinema);
					if (i >= 1 && line == 1) growConstellationLine(props, i, "g", sevenSpot(i - 1, bowl, forward, cinema), spot, 0.6 * sc, cinema);
				}
				if (i == 6) {
					growConstellationLine(props, 7, "c", spot, sevenSpot(3, bowl, forward, cinema), 0.22 * sc, cinema);
					growConstellationLine(props, 7, "g", spot, sevenSpot(3, bowl, forward, cinema), 0.6 * sc, cinema);
				}
			}
		}
		int ignite = sevenIgnite(cinema);
		if (t == ignite) {
			// the whole constellation lights up and a column of light stands in the middle of the bowl
			ExplosionArt.sound(level, bowl, SoundEvents.BEACON_ACTIVATE, 6.0f, 1.2f);
			ExplosionArt.sound(level, bowl, SoundEvents.END_PORTAL_SPAWN, 4.0f, 1.4f);
			ExplosionArt.sound(level, bowl, SoundEvents.AMETHYST_BLOCK_RESONATE, 6.0f, 0.5f);
			Vec3 mid = new Vec3(bowl.x, gy + 60, bowl.z);
			Props.Prop core = props.block("minecraft:white_concrete", mid, new Vec3(2.0, 120, 2.0), Q.ID, true);
			Props.Prop sleeve = props.block("minecraft:light_blue_stained_glass", mid, new Vec3(5.0, 120, 5.0), Q.ID, true);
			props.reshape(core, mid, new Vec3(0.05, 120, 0.05), Q.ID, cinema ? 40 : 24);
			props.reshape(sleeve, mid, new Vec3(0.1, 120, 0.1), Q.ID, cinema ? 40 : 24);
			props.lifetime(core, t + (cinema ? 41 : 25));
			props.lifetime(sleeve, t + (cinema ? 41 : 25));
			for (int i = 0; i < 7; i++) {
				Vec3 spot = sevenSpot(i, bowl, forward, cinema);
				ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, spot.add(0, 0.3, 0), 30, 0.7, 0.03);
				ExplosionArt.blast(level, viewers, ParticleTypes.FIREWORK, spot.add(0, 1, 0), 20, 0.7, true);
			}
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, bowl.add(0, 0.3, 0), 110, 1.8, 0.03);
			ExplosionArt.shockwave(level, viewers, ExplosionArt.GOLD, bowl.add(0, 0.3, 0), 80, 1.2, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, bowl.add(0, 1.5, 0), 110, 1.4, true);
			int[] white = {0xFFFFFF, 0xFFD54A, 0x8FD3FF};
			int[] fade = {0xB06CFF};
			ExplosionArt.firework(level, bowl.add(0, 16, 0), FireworkExplosion.Shape.STAR, white, fade);
			ExplosionArt.firework(level, bowl.add(8, 18, 3), FireworkExplosion.Shape.LARGE_BALL, white, fade);
			ExplosionArt.firework(level, bowl.add(-8, 18, -3), FireworkExplosion.Shape.BURST, white, fade);
			// the lines glow thicker for a moment
			for (int l = 1; l <= 7; l++) {
				int a = l == 7 ? 6 : l - 1;
				int b = l == 7 ? 3 : l;
				Vec3 p1 = sevenSpot(a, bowl, forward, cinema).add(0, 0.18, 0);
				Vec3 p2 = sevenSpot(b, bowl, forward, cinema).add(0, 0.18, 0);
				Props.Prop glow = props.named("ln" + l + "g");
				if (glow != null) Shapes.growLine(props, glow, p1, p2, 1.3 * sc, 6);
			}
		}
		if (t > ignite + 6 && t == ignite + (cinema ? 40 : 22)) {
			for (int l = 1; l <= 7; l++) {
				int a = l == 7 ? 6 : l - 1;
				int b = l == 7 ? 3 : l;
				Vec3 p1 = sevenSpot(a, bowl, forward, cinema).add(0, 0.18, 0);
				Vec3 p2 = sevenSpot(b, bowl, forward, cinema).add(0, 0.18, 0);
				Props.Prop glow = props.named("ln" + l + "g");
				if (glow != null) Shapes.growLine(props, glow, p1, p2, 0.5 * sc, 10);
			}
		}
		if (t >= ignite && t < ignite + (cinema ? 60 : 30)) {
			// star dust falls over the whole figure
			for (int k = 0; k < (cinema ? 10 : 6); k++) {
				int star = rnd.nextInt(7);
				Vec3 spot = sevenSpot(star, bowl, forward, cinema);
				ExplosionArt.emit(level, viewers, k % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.FIREWORK, spot.x + (rnd.nextDouble() - 0.5) * 5, spot.y + 2 + rnd.nextDouble() * 8,
					spot.z + (rnd.nextDouble() - 0.5) * 5, 1, 0.05, 0.3, 0.05, 0.01);
			}
		}
		// the end: the crystals stay until the show is over; the lines go out
		if (t == sevenTicks(cinema) - 12) {
			for (int l = 1; l <= 7; l++) {
				int a = l == 7 ? 6 : l - 1;
				int b = l == 7 ? 3 : l;
				Vec3 p1 = sevenSpot(a, bowl, forward, cinema).add(0, 0.18, 0);
				Vec3 p2 = sevenSpot(b, bowl, forward, cinema).add(0, 0.18, 0);
				Props.Prop glow = props.named("ln" + l + "g");
				Props.Prop core = props.named("ln" + l + "c");
				if (glow != null) Shapes.growLine(props, glow, p1, p2, 0.02, 10);
				if (core != null) Shapes.growLine(props, core, p1, p2, 0.02, 10);
			}
		}
	}

	private static void growConstellationLine(Props props, int line, String kind, Vec3 from, Vec3 to, double thick, boolean cinema) {
		Props.Prop p = props.named("ln" + line + kind);
		if (p == null) return;
		Shapes.growLine(props, p, new Vec3(from.x, from.y + 0.18, from.z), new Vec3(to.x, to.y + 0.18, to.z), thick, cinema ? 12 : 6);
	}
}
