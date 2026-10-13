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
 * The Railgun: a thin red laser comes down from above the clouds and marks the target, a crosshair closes in on it, and then a beam of
 * white-hot light is fired from the sky (its head falls at a steady speed, so a camera can ride it down). Only light and sound.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class BeamArt {
	private static final ParticleOptions SIGHT = new DustParticleOptions(0xFF2A2A, 1.6f);
	private static final ParticleOptions HOT = new DustParticleOptions(0xFFB020, 1.9f);
	private static final ParticleOptions COLD = new DustParticleOptions(0x7FD0FF, 1.5f);

	private BeamArt() {}

	private static double ease(double x) {
		double u = Math.max(0, Math.min(1, x));
		return u * u * (3 - 2 * u);
	}

	// ------------------------------------------------------------------ the numbers the film needs

	/** How long the red laser marks the target before the shot. */
	public static int railSight(boolean cinema) {
		return cinema ? 90 : 36;
	}

	/** How long the head of the beam takes to fall from the clouds to the ground. */
	public static int railFall(boolean cinema) {
		return cinema ? 20 : 8;
	}

	/** How long the aftermath lasts. */
	public static int railAfter(boolean cinema) {
		return cinema ? 96 : 64;
	}

	/** The tick the beam hits the ground. */
	public static int railImpact(boolean cinema) {
		return railSight(cinema) + railFall(cinema);
	}

	public static int railTicks(boolean cinema) {
		return railImpact(cinema) + railAfter(cinema);
	}

	/** How high above the target the beam starts. */
	public static double railHeight(boolean cinema) {
		return cinema ? 120 : 80;
	}

	/** How high above the target the cloud deck of the long version is. */
	public static final double CLOUDS = 52;

	/** Where the head of the beam is {@code t} ticks after the start. */
	public static Vec3 railHead(Vec3 target, int t, boolean cinema) {
		int shot = railSight(cinema);
		double u = Math.max(0, Math.min(1, (t - shot) / (double) railFall(cinema)));
		double top = target.y + railHeight(cinema);
		return new Vec3(target.x, top + (target.y - top) * u, target.z);
	}

	private static final String[] BEAM_BLOCKS = {"minecraft:orange_stained_glass", "minecraft:yellow_stained_glass", "minecraft:white_concrete"};
	private static final double[] BEAM_WIDTH = {5.0, 2.6, 1.1};

	// ------------------------------------------------------------------ the Railgun

	public static void railgun(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int sight = railSight(cinema);
		int fall = railFall(cinema);
		int after = railAfter(cinema);
		int impact = sight + fall;
		int end = impact + after;
		double gy = target.y;
		Vec3 top = new Vec3(target.x, gy + railHeight(cinema), target.z);
		Vec3 bottom = new Vec3(target.x, gy, target.z);
		Vec3 ground = target.add(0, 0.3, 0);

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 3.0f, 1.8f);
			if (cinema) clouds(props, target, end);
		}
		if (t < sight) sight(level, viewers, props, target, top, bottom, t, sight, cinema);
		if (t == sight - 1) {
			// the beam is born collapsed at the top, one tick before the shot (the client needs that tick to grow it)
			for (int i = 0; i < BEAM_BLOCKS.length; i++) props.name("beam" + i, Shapes.lineGrowing(props, BEAM_BLOCKS[i], top, bottom, BEAM_WIDTH[i], true));
		}
		if (t == sight) {
			for (int i = 0; i < BEAM_BLOCKS.length; i++) Shapes.growLine(props, props.named("beam" + i), top, bottom, BEAM_WIDTH[i], fall);
			ExplosionArt.sound(level, target.add(0, 24, 0), SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 1.5f);
			ExplosionArt.sound(level, target, SoundEvents.FIREWORK_ROCKET_LAUNCH, 6.0f, 0.5f);
		}
		if (t >= sight && t < impact) {
			// sparks follow the head of the beam down
			Vec3 head = railHead(target, t, cinema);
			ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, head.x, head.y + 2, head.z, 6, 1.8, 3.0, 1.8, 0.25);
			ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, head.x, head.y, head.z, 4, 1.2, 1.0, 1.2, 0.2);
			ExplosionArt.ring(level, viewers, HOT, head, 2.6, 14, t * 0.4, 0);
		}
		if (t >= impact) strike(level, viewers, props, target, ground, t - impact, t, cinema, fall, after, rnd);
		// the beam thins out and the crater closes at the end
		if (t == impact + 6) {
			for (int i = 0; i < BEAM_BLOCKS.length; i++) {
				Props.Prop beam = props.named("beam" + i);
				Shapes.growLine(props, beam, top, bottom, 0.04, 14);
				props.lifetime(beam, impact + 22);
			}
		}
		if (t == end - 16) {
			for (int i = 0; i < 3; i++) {
				double side = Shapes.Disk.sideFor(0.4, 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("crater" + i + "_" + k), new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(side, 0.05, side), Q.yaw(k * 22.5), 14);
				}
			}
		}
	}

	/** The deck of clouds above the arena that the camera of the long version climbs through. */
	private static void clouds(Props props, Vec3 target, int end) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < 34; i++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			// there is a gap above the target so the camera can see through it
			double r = 15 + Math.sqrt(rnd.nextDouble()) * 34;
			Vec3 at = new Vec3(target.x + Math.cos(a) * r, target.y + CLOUDS + rnd.nextDouble() * 6, target.z + Math.sin(a) * r);
			Vec3 size = new Vec3(8 + rnd.nextDouble() * 8, 2.0 + rnd.nextDouble() * 2.5, 8 + rnd.nextDouble() * 8);
			String block = i % 3 == 0 ? "minecraft:light_gray_concrete" : "minecraft:white_concrete";
			props.lifetime(props.block(block, at, size, Q.yaw(rnd.nextDouble() * 90), true), end);
		}
	}

	/** The red laser, the crosshair that closes in and the charging sounds. */
	private static void sight(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, Vec3 top, Vec3 bottom, int t, int sight, boolean cinema) {
		double start = cinema ? 10.0 : 7.0;
		double close = 3.2;
		int born = 4;
		if (t == 2) {
			// a thin red line from the sky to the target; it grows downward from the top
			props.lifetime(props.name("sight", Shapes.lineGrowing(props, "minecraft:red_stained_glass", top, bottom, 0.14, true)), sight + 2);
		}
		if (t == 3) Shapes.growLine(props, props.named("sight"), top, bottom, 0.14, cinema ? 10 : 6);
		if (t == sight - 12) Shapes.growLine(props, props.named("sight"), top, bottom, 0.5, 10);
		if (t == born) {
			for (int k = 0; k < 4; k++) {
				double a = Math.PI / 4 + k * Math.PI / 2;
				Vec3 at = new Vec3(target.x + Math.cos(a) * start, target.y + 0.12, target.z + Math.sin(a) * start);
				props.lifetime(props.name("bar" + k, props.block("minecraft:red_concrete", at, new Vec3(0.2, 0.1, 2.6), Shapes.facing(new Vec3(-Math.cos(a), 0, -Math.sin(a))), true)), sight + 1);
			}
		}
		if (t >= born && t % 2 == 0) {
			double u = ease((t - born) / (double) (sight - born));
			double r = start + (close - start) * u;
			for (int k = 0; k < 4; k++) {
				double a = Math.PI / 4 + k * Math.PI / 2 + u * 0.5;
				Vec3 at = new Vec3(target.x + Math.cos(a) * r, target.y + 0.12, target.z + Math.sin(a) * r);
				Props.Prop bar = props.named("bar" + k);
				props.move(bar, at);
			}
			ExplosionArt.ring(level, viewers, SIGHT, target.add(0, 0.25, 0), r + 0.8, 30, t * 0.12, 0);
			ExplosionArt.ring(level, viewers, SIGHT, target.add(0, 0.25, 0), Math.max(0.8, r * 0.45), 18, -t * 0.2, 0);
		}
		if (t > 8 && t % 2 == 0) {
			ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.x, target.y + 1.2, target.z, 3, 1.5, 1.3, 1.5, 0.15);
		}
		if (t > sight - 20 && t % 2 == 1) {
			// the charge: a spiral of cold sparks rises around the target
			double a = t * 0.7;
			for (int k = 0; k < 3; k++) {
				double h = 0.2 + ((t + k * 7) % 14) * 0.45;
				ExplosionArt.emit(level, viewers, COLD, target.x + Math.cos(a + k * 2.1) * 1.4, target.y + h, target.z + Math.sin(a + k * 2.1) * 1.4, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}
		if (t == sight - 24) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.8f);
		if (t == sight - 14) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 1.2f);
		if (t == sight - 6) {
			ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 1.8f);
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 3.0f, 2.0f);
		}
	}

	/** The blow: flash, crater, lightning cracks, shock ring, thunder and fire. {@code w} is the number of ticks since the beam landed. */
	private static void strike(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, Vec3 ground, int w, int t, boolean cinema, int fall, int after,
		ThreadLocalRandom rnd) {
		double gy = target.y;
		if (w == 0) {
			Props.Prop flash = props.item("minecraft:firework_star", target.add(0, 3, 0), 3.0);
			props.resize(flash, cinema ? 64.0 : 40.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 1, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.SONIC_BOOM, target.x, target.y + 2, target.z, 1, 0, 0, 0, 0);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 120, 2.4, 0.02);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, ground, 90, 1.6, 0.04);
			ExplosionArt.shockwave(level, viewers, HOT, ground, 90, 1.2, 0.03);
			ExplosionArt.blast(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.add(0, 1.2, 0), 90, 1.4, true);
			ExplosionArt.blast(level, viewers, ParticleTypes.FLAME, target.add(0, 1.2, 0), 50, 0.9, true);
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 8.0f, 0.5f);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 8.0f, 0.6f);
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 8.0f, 0.6f);
			ExplosionArt.sound(level, target, SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.6f);
			// the crater: dark ground with a glowing middle
			String[] blocks = {"minecraft:black_concrete", "minecraft:magma_block", "minecraft:orange_concrete"};
			for (int i = 0; i < 3; i++) {
				for (int k = 0; k < 4; k++) {
					props.lifetime(props.name("crater" + i + "_" + k, props.block(blocks[i], new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(0.3, 0.05, 0.3),
						Q.yaw(k * 22.5), true)), t + after);
				}
			}
			// the lightning cracks that run out of the crater
			int cracks = cinema ? 14 : 9;
			for (int c = 0; c < cracks; c++) {
				double a = c * Math.PI * 2 / cracks + rnd.nextDouble() * 0.3;
				double to = 6.0 + rnd.nextDouble() * (cinema ? 10 : 6);
				Vec3 p1 = new Vec3(target.x + Math.cos(a) * 2.0, gy + 0.14, target.z + Math.sin(a) * 2.0);
				Vec3 p2 = new Vec3(target.x + Math.cos(a) * to, gy + 0.14, target.z + Math.sin(a) * to);
				props.lifetime(props.name("crack" + c, Shapes.lineGrowing(props, c % 2 == 0 ? "minecraft:light_blue_concrete" : "minecraft:white_concrete", p1, p2, 0.3, true)), t + after);
			}
			// the shock ring: panes that run outward on the ground
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				Vec3 tangent = new Vec3(-Math.sin(a), 0, Math.cos(a));
				Props.Prop pane = props.block("minecraft:white_stained_glass", new Vec3(target.x + Math.cos(a) * 2.0, gy + 1.4, target.z + Math.sin(a) * 2.0),
					new Vec3(0.25, 3.0, 2.8), Shapes.facing(tangent), true);
				props.name("ring" + k, pane);
				props.lifetime(pane, t + 14);
			}
			ExplosionArt.debris(props, target.add(0, 1, 0), target.y, new String[] {"minecraft:magma_block", "minecraft:blackstone", "minecraft:obsidian", "minecraft:iron_block"},
				cinema ? 18 : 8, 1.6, 1.9, t, 60);
			int[] fire = {0xFFFFFF, 0xFFD060, 0x7FD0FF};
			ExplosionArt.firework(level, target.add(0, 14, 0), FireworkExplosion.Shape.LARGE_BALL, fire, new int[] {0x1A2A5A});
			ExplosionArt.firework(level, target.add(0, 22, 0), FireworkExplosion.Shape.STAR, new int[] {0xFF3030, 0xFFFFFF}, new int[] {0xFFB020});
		}
		if (w == 1) {
			double[] radii = {7.0, 5.0, 1.5};
			for (int i = 0; i < 3; i++) {
				double side = Shapes.Disk.sideFor(radii[i], 4);
				for (int k = 0; k < 4; k++) {
					props.reshape(props.named("crater" + i + "_" + k), new Vec3(target.x, gy + 0.05 + i * 0.012, target.z), new Vec3(side, 0.05, side), Q.yaw(k * 22.5), 8);
				}
			}
			int cracks = cinema ? 14 : 9;
			for (int c = 0; c < cracks; c++) {
				Props.Prop crack = props.named("crack" + c);
				if (crack == null) continue;
				// the crack is reshaped to the line it was made for (its middle tells where it points)
				Vec3 away = new Vec3(crack.pos().x - target.x, 0, crack.pos().z - target.z);
				if (away.lengthSqr() < 1e-6) continue;
				Vec3 dir = away.normalize();
				double length = 2 * away.length() - 4.0;
				Vec3 p1 = new Vec3(target.x + dir.x * 2.0, gy + 0.14, target.z + dir.z * 2.0);
				Vec3 p2 = new Vec3(target.x + dir.x * (2.0 + Math.max(0.5, length)), gy + 0.14, target.z + dir.z * (2.0 + Math.max(0.5, length)));
				Shapes.growLine(props, crack, p1, p2, 0.3, 8);
			}
		}
		if (w >= 1 && w <= 14 && w % 2 == 0) {
			double r = 2.0 + w * 2.8;
			for (int k = 0; k < 28; k++) {
				double a = k * Math.PI * 2 / 28;
				props.move(props.named("ring" + k), new Vec3(target.x + Math.cos(a) * r, gy + 1.4 * (1 - w / 18.0), target.z + Math.sin(a) * r));
			}
		}
		if (w < 56) {
			ExplosionArt.emit(level, viewers, ParticleTypes.FLAME, target.x, target.y + 0.5, target.z, 3, 2.2, 0.4, 2.2, 0.04);
			ExplosionArt.emit(level, viewers, ParticleTypes.LARGE_SMOKE, target.x, target.y + 1, target.z, 2, 2.0, 0.6, 2.0, 0.03);
			if (w % 2 == 0) ExplosionArt.emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, target.x, target.y + 1.0, target.z, 4, 3.5, 0.8, 3.5, 0.25);
		}
	}
}
