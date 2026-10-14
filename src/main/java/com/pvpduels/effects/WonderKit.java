package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Small pieces the new explosions ({@link EarthWonders} and {@link SkyWonders}) are built from: a column that rises from the ground, a
 * beam that grows between two points, a sphere of rotated blocks and a few colors. Everything is stateless: a piece is found again by its
 * name, and what to do on tick {@code t} follows from {@code t} alone.
 */
final class WonderKit {
	static final ParticleOptions FIRE = new DustParticleOptions(0xFF6A1F, 1.8f);
	static final ParticleOptions SKY = new DustParticleOptions(0xBFE9FF, 1.6f);
	static final ParticleOptions PURPLE = new DustParticleOptions(0xB04CFF, 1.7f);
	static final ParticleOptions MAGENTA = new DustParticleOptions(0xFF3BE0, 1.7f);
	static final ParticleOptions SOUL = new DustParticleOptions(0x5FFFE6, 1.6f);
	static final ParticleOptions SUN = new DustParticleOptions(0xFFC83B, 1.9f);
	static final ParticleOptions SPORE = new DustParticleOptions(0xE0476B, 1.6f);
	static final ParticleOptions SCULK = new DustParticleOptions(0x19D3C5, 1.6f);
	static final int[] RAINBOW = {0xFF3B3B, 0xFF9A2E, 0xFFE23B, 0x5BE35B, 0x3BD9FF, 0x3B6BFF, 0xB04CFF};
	static final ParticleOptions[] RAIN_DUST = new ParticleOptions[RAINBOW.length];
	/** The seven colors of the Prism Beams: glass for the beams, concrete for the rings on the ground. */
	static final String[] RAIN_GLASS = {"minecraft:red_stained_glass", "minecraft:orange_stained_glass", "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
		"minecraft:light_blue_stained_glass", "minecraft:blue_stained_glass", "minecraft:purple_stained_glass"};
	static final String[] RAIN_SOLID = {"minecraft:red_concrete", "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
		"minecraft:light_blue_concrete", "minecraft:blue_concrete", "minecraft:purple_concrete"};

	static {
		for (int i = 0; i < RAINBOW.length; i++) RAIN_DUST[i] = new DustParticleOptions(RAINBOW[i], 1.5f);
	}

	private WonderKit() {}

	static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	static double ease(double x) {
		double u = clamp01(x);
		return u * u * (3 - 2 * u);
	}

	static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	/** The point at distance {@code r} and angle {@code a} (radians) from the middle of {@code c}, {@code y} above its height. */
	static Vec3 polar(Vec3 c, double r, double a, double y) {
		return new Vec3(c.x + Math.cos(a) * r, c.y + y, c.z + Math.sin(a) * r);
	}

	static void fx(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, Vec3 at, int count, double spread, double speed) {
		ExplosionArt.emit(level, viewers, options, at, count, spread, speed);
	}

	/**
	 * A column that grows up from {@code base}: made on tick {@code t0}, it reaches its height over {@code grow} ticks (the reshape has to
	 * come a tick after the block was made) and disappears on tick {@code die}.
	 */
	static void rise(Props props, int t, int t0, int grow, int die, String key, String state, Vec3 base, double width, double height, boolean bright) {
		Vec3 mid = base.add(0, height / 2, 0);
		if (t == t0) {
			props.lifetime(props.name(key, props.growing(state, mid, new Vec3(width, height, width), Q.ID, bright, 1)), die);
		} else if (t == t0 + 1) {
			props.reshape(props.named(key), mid, new Vec3(width, height, width), Q.ID, grow);
		}
	}

	/** The named column sinks into the ground at {@code base} over {@code ticks}. */
	static void sink(Props props, String key, Vec3 base, double width, int ticks) {
		props.reshape(props.named(key), base.add(0, 0.05, 0), new Vec3(width, 0.1, width), Q.ID, ticks);
	}

	/** A beam that is made on tick {@code t0} at {@code a} and stretches to {@code b} over {@code grow} ticks; gone on tick {@code die}. */
	static void beam(Props props, int t, int t0, int grow, int die, String key, String state, Vec3 a, Vec3 b, double thick) {
		if (t == t0) {
			props.lifetime(props.name(key, Shapes.lineGrowing(props, state, a, b, thick, true)), die);
		} else if (t == t0 + 1) {
			Shapes.growLine(props, props.named(key), a, b, thick, grow);
		}
	}

	/** The named beam gets thin (it keeps its ends) over {@code ticks}. */
	static void thin(Props props, String key, Vec3 a, Vec3 b, int ticks) {
		Shapes.growLine(props, props.named(key), a, b, 0.04, ticks);
	}

	private static Q orbRot(int i, double spin) {
		return switch (i) {
			case 0 -> Q.yaw(spin);
			case 1 -> Q.yaw(45 + spin).mul(Q.pitch(54.7));
			default -> Q.yaw(spin * 0.5).mul(Q.roll(54.7)).mul(Q.pitch(45));
		};
	}

	/** A sphere of three crossing cubes (named {@code key0..2}), tiny at first; {@link #orbTo} changes it a tick later. */
	static void orbMake(Props props, String key, String state, Vec3 at, boolean bright, int die) {
		for (int i = 0; i < 3; i++) props.lifetime(props.name(key + i, props.block(state, at, cube(0.2), orbRot(i, 0), bright)), die);
	}

	static void orbTo(Props props, String key, Vec3 at, double side, double spin, int ticks) {
		for (int i = 0; i < 3; i++) props.reshape(props.named(key + i), at, cube(side), orbRot(i, spin), ticks);
	}

	static void orbGone(Props props, String key) {
		for (int i = 0; i < 3; i++) props.remove(props.named(key + i));
	}
}
