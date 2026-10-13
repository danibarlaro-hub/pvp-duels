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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Tesseract: a four-dimensional cube made of thin glowing lines turns above the target (its 32 edges are drawn again every other
 * tick), a rainbow grid is drawn on the floor, and the floor tiles lift off one by one and circle it. At the end the cube folds into a
 * point and the tiles fall. The real floor is never touched: the tiles are display entities that lie on top of it.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class GridArt {
	private static final String[] RAINBOW = {"minecraft:red_concrete", "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
		"minecraft:light_blue_concrete", "minecraft:blue_concrete", "minecraft:purple_concrete", "minecraft:magenta_concrete"};
	private static final int[] RAINBOW_RGB = {0xFF3030, 0xFF8A1F, 0xFFE030, 0x70E040, 0x40C8FF, 0x4060FF, 0xA050FF, 0xFF50C8};
	private static final ParticleOptions[] DUST = new ParticleOptions[RAINBOW_RGB.length];
	/** The block of the edges that run along x, y, z and w. */
	private static final String[] AXIS_BLOCK = {"minecraft:cyan_concrete", "minecraft:magenta_concrete", "minecraft:yellow_concrete", "minecraft:white_concrete"};
	/** The 32 edges: the two vertices and the axis that changes. */
	private static final int[][] EDGES = edges();

	static {
		for (int i = 0; i < DUST.length; i++) DUST[i] = new DustParticleOptions(RAINBOW_RGB[i], 1.5f);
	}

	private GridArt() {}

	private static int[][] edges() {
		List<int[]> list = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			for (int bit = 0; bit < 4; bit++) {
				int j = i ^ (1 << bit);
				if (i < j) list.add(new int[] {i, j, bit});
			}
		}
		return list.toArray(new int[0][]);
	}

	private static double ease(double x) {
		double u = Math.max(0, Math.min(1, x));
		return u * u * (3 - 2 * u);
	}

	// ------------------------------------------------------------------ the numbers the film needs

	/** Tiles per side of the square floor grid. */
	public static int cells(boolean cinema) {
		return cinema ? 5 : 4;
	}

	/** The side of one tile. */
	public static double tileSide(boolean cinema) {
		return cinema ? 4.0 : 3.2;
	}

	/** The tick the cube reaches its biggest and brightest (the flash). */
	public static int climax(boolean cinema) {
		return cinema ? 120 : 66;
	}

	/** How long the cube takes to fold into a point after the flash. */
	public static int collapse(boolean cinema) {
		return cinema ? 12 : 8;
	}

	public static int tesseractTicks(boolean cinema) {
		return climax(cinema) + collapse(cinema) + (cinema ? 60 : 34);
	}

	/** The tick the cube is born. */
	public static int birth(boolean cinema) {
		return cinema ? 12 : 8;
	}

	/** The middle of the cube. */
	public static Vec3 center(Vec3 target, boolean cinema) {
		return target.add(0, cinema ? 7.5 : 6.0, 0);
	}

	/** The half edge of the cube when it is calm. */
	public static double cubeScale(boolean cinema) {
		return cinema ? 2.4 : 1.9;
	}

	// ------------------------------------------------------------------ the cube

	/** The angles of the two turns of the cube at tick {@code t}; it spins faster and faster toward the flash. */
	private static double turn(int t, int c) {
		int speedUp = c - 40;
		double extra = t > speedUp ? 0.0009 * (t - speedUp) * (t - speedUp) : 0;
		return 0.04 * t + extra;
	}

	/** The cube's scale at tick {@code t}: it grows when it is born, swells before the flash, and folds into a point after it. */
	private static double scale(int t, boolean cinema) {
		int c = climax(cinema);
		double grow = ease((t - birth(cinema)) / 14.0);
		double flare = 1 + 0.5 * ease((t - (c - 14)) / 14.0);
		double shrink = t >= c ? 1 - ease((t - c) / (double) collapse(cinema)) : 1;
		return cubeScale(cinema) * grow * flare * shrink;
	}

	/** One vertex of the tesseract (the bits of {@code i} say which side of x, y, z and w it is on), turned and projected into 3D. */
	private static Vec3 vertex(int i, double a, double b, double yaw, double scale, Vec3 center) {
		double x = (i & 1) == 0 ? -1 : 1;
		double y = (i & 2) == 0 ? -1 : 1;
		double z = (i & 4) == 0 ? -1 : 1;
		double w = (i & 8) == 0 ? -1 : 1;
		double x1 = x * Math.cos(a) - w * Math.sin(a);
		double w1 = x * Math.sin(a) + w * Math.cos(a);
		double z1 = z * Math.cos(b) - w1 * Math.sin(b);
		double w2 = z * Math.sin(b) + w1 * Math.cos(b);
		double k = 3.4 / (3.4 - w2);
		double px = x1 * k;
		double pz = z1 * k;
		double rx = px * Math.cos(yaw) - pz * Math.sin(yaw);
		double rz = px * Math.sin(yaw) + pz * Math.cos(yaw);
		return new Vec3(center.x + rx * scale, center.y + y * k * scale, center.z + rz * scale);
	}

	private static Vec3[] vertices(int t, boolean cinema, Vec3 center) {
		int c = climax(cinema);
		double a = turn(t, c);
		double b = turn(t, c) * 0.62 + 0.4;
		double yaw = 0.017 * t;
		double scale = scale(t, cinema);
		Vec3[] v = new Vec3[16];
		for (int i = 0; i < 16; i++) v[i] = vertex(i, a, b, yaw, scale, center);
		return v;
	}

	// ------------------------------------------------------------------ the floor

	private static Vec3 tileGround(int i, Vec3 target, boolean cinema) {
		int n = cells(cinema);
		double side = tileSide(cinema);
		double mid = (n - 1) / 2.0;
		return new Vec3(target.x + (i % n - mid) * side, target.y + 0.04, target.z + (i / n - mid) * side);
	}

	/** How far a tile is from the middle of the grid, in tiles. */
	private static double tileDistance(int i, boolean cinema) {
		int n = cells(cinema);
		double mid = (n - 1) / 2.0;
		return Math.hypot(i % n - mid, i / n - mid);
	}

	private static int tileBorn(int i, boolean cinema) {
		return 1 + (int) (tileDistance(i, cinema) * (cinema ? 4 : 2.5));
	}

	private static int tileStart(int i, boolean cinema) {
		return (cinema ? 34 : 18) + (int) (tileDistance(i, cinema) * (cinema ? 6 : 4)) + (i % 3) * 2;
	}

	public static void tesseract(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int c = climax(cinema);
		int col = collapse(cinema);
		int end = tesseractTicks(cinema);
		int ta = birth(cinema);
		int n = cells(cinema);
		double side = tileSide(cinema);
		Vec3 center = center(target, cinema);
		Vec3 ground = target.add(0, 0.3, 0);
		int tiles = n * n;
		if (t >= end) return;

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 3.0f, 1.3f);
			// the grid: rainbow lines that run out of the middle in both directions
			double half = n * side / 2.0;
			for (int j = 0; j <= n; j++) {
				double off = (j - n / 2.0) * side;
				String block = RAINBOW[j % RAINBOW.length];
				Vec3 midX = new Vec3(target.x, target.y + 0.1, target.z + off);
				Vec3 midZ = new Vec3(target.x + off, target.y + 0.1, target.z);
				Vec3[][] ends = {{midX, new Vec3(target.x - half, target.y + 0.1, target.z + off)}, {midX, new Vec3(target.x + half, target.y + 0.1, target.z + off)},
					{midZ, new Vec3(target.x + off, target.y + 0.1, target.z - half)}, {midZ, new Vec3(target.x + off, target.y + 0.1, target.z + half)}};
				for (int k = 0; k < 4; k++) {
					props.lifetime(props.name("line" + j + "_" + k, Shapes.lineGrowing(props, block, ends[k][0], ends[k][1], 0.16, true)), end);
				}
			}
		}
		if (t == 1) {
			double half = n * side / 2.0;
			for (int j = 0; j <= n; j++) {
				double off = (j - n / 2.0) * side;
				Vec3 midX = new Vec3(target.x, target.y + 0.1, target.z + off);
				Vec3 midZ = new Vec3(target.x + off, target.y + 0.1, target.z);
				Vec3[][] ends = {{midX, new Vec3(target.x - half, target.y + 0.1, target.z + off)}, {midX, new Vec3(target.x + half, target.y + 0.1, target.z + off)},
					{midZ, new Vec3(target.x + off, target.y + 0.1, target.z - half)}, {midZ, new Vec3(target.x + off, target.y + 0.1, target.z + half)}};
				for (int k = 0; k < 4; k++) Shapes.growLine(props, props.named("line" + j + "_" + k), ends[k][0], ends[k][1], 0.16, cinema ? 18 : 10);
			}
		}
		// the tiles appear from the middle outward
		for (int i = 0; i < tiles; i++) {
			if (t == tileBorn(i, cinema)) {
				String block = (i % n + i / n) % 2 == 0 ? "minecraft:white_concrete" : "minecraft:light_gray_concrete";
				props.lifetime(props.name("tile" + i, props.block(block, tileGround(i, target, cinema), new Vec3(side - 0.16, 0.12, side - 0.16), Q.ID, true)), c + col + 40);
			}
		}
		if (t == ta) {
			ExplosionArt.sound(level, center, SoundEvents.PORTAL_TRAVEL, 2.0f, 1.6f);
			ExplosionArt.sound(level, center, SoundEvents.END_PORTAL_SPAWN, 2.0f, 1.5f);
			Vec3[] v = vertices(t, cinema, center);
			for (int e = 0; e < EDGES.length; e++) {
				int[] edge = EDGES[e];
				props.lifetime(props.name("edge" + e, Shapes.line(props, AXIS_BLOCK[edge[2]], v[edge[0]], v[edge[1]], 0.03, true)), c + col);
			}
		}
		// the cube is drawn again every other tick, from the tick after it is born until it folds
		if (t > ta && (t - ta) % 2 == 1 && t <= c + col) {
			Vec3[] v = vertices(t, cinema, center);
			double thick = Math.max(0.03, 0.16 * Math.min(1.0, ease((t - ta) / 14.0) * 3) * (t >= c ? 1 - ease((t - c) / (double) col) : 1));
			for (int e = 0; e < EDGES.length; e++) {
				int[] edge = EDGES[e];
				Shapes.growLine(props, props.named("edge" + e), v[edge[0]], v[edge[1]], thick, 2);
			}
			if (t % 3 == 0) {
				for (int k = 0; k < 5; k++) {
					Vec3 at = v[rnd.nextInt(16)];
					ExplosionArt.emit(level, viewers, DUST[rnd.nextInt(DUST.length)], at, 2, 0.15, 0.0);
					ExplosionArt.emit(level, viewers, ParticleTypes.END_ROD, at, 1, 0.1, 0.02);
				}
			}
		}
		// the tiles lift off one by one and circle the cube
		for (int i = 0; i < tiles; i++) {
			int start = tileStart(i, cinema);
			Props.Prop tile = props.named("tile" + i);
			if (tile == null) continue;
			Vec3 g = tileGround(i, target, cinema);
			if (t == start) {
				Props.Prop tilted = tile;
				Q tilt = Q.axis(rnd.nextDouble() - 0.5, rnd.nextDouble() * 0.4, rnd.nextDouble() - 0.5, Math.toRadians(20 + rnd.nextDouble() * 25));
				props.reshape(tilted, g, new Vec3(side - 0.16, 0.12, side - 0.16), tilt, 24);
				if (i % 2 == 0) ExplosionArt.sound(level, g, SoundEvents.AMETHYST_BLOCK_CHIME, 2.0f, 0.6f + (i % 8) * 0.15f);
				ExplosionArt.emit(level, viewers, DUST[i % DUST.length], g.x, g.y + 0.3, g.z, 6, side * 0.35, 0.1, side * 0.35, 0.02);
			}
			if (t > start && t % 2 == 0 && t < c + 6) {
				double u = ease((t - start) / 24.0);
				double base = Math.atan2(g.z - target.z, g.x - target.x);
				double omega = (0.04 + 0.007 * (i % 4)) * (i % 2 == 0 ? 1 : -1);
				double phi = base + (t - start) * omega;
				double rho = (cinema ? 7.5 : 6.0) + (i % 5) * (cinema ? 1.6 : 1.3);
				double height = 2.5 + (i % 6) * 1.15 + 0.7 * Math.sin(t * 0.09 + i);
				Vec3 orbit = new Vec3(target.x + Math.cos(phi) * rho, target.y + height, target.z + Math.sin(phi) * rho);
				props.move(tile, g.add(orbit.subtract(g).scale(u)));
			}
		}
		if (t > ta + 10 && t < c && t % 4 == 0) {
			// the floor glows in the colors of the grid
			ExplosionArt.ring(level, viewers, DUST[(t / 4) % DUST.length], ground, 3.0 + (t % 16) * 0.6, 24, t * 0.1, 0);
		}
		if (t == c) {
			Props.Prop flash = props.item("minecraft:firework_star", center, 3.0);
			props.resize(flash, cinema ? 56.0 : 34.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.sound(level, center, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.7f);
			ExplosionArt.sound(level, center, SoundEvents.WARDEN_SONIC_BOOM, 5.0f, 1.2f);
			ExplosionArt.sound(level, center, SoundEvents.END_PORTAL_SPAWN, 4.0f, 0.5f);
			ExplosionArt.emit(level, viewers, ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
			ExplosionArt.emit(level, viewers, ParticleTypes.SONIC_BOOM, center.x, center.y, center.z, 1, 0, 0, 0, 0);
			for (int k = 0; k < DUST.length; k++) {
				ExplosionArt.shockwave(level, viewers, DUST[k], ground, 40, 1.0 + 0.16 * k, 0.03);
				ExplosionArt.blast(level, viewers, DUST[k], center, 22, 0.9 + 0.1 * k, false);
			}
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, center, 60, 1.2, false);
			int[] colors = {0xFF3030, 0xFFE030, 0x40C8FF, 0xFF50C8};
			ExplosionArt.firework(level, center.add(0, 6, 0), FireworkExplosion.Shape.LARGE_BALL, colors, new int[] {0xFFFFFF});
			ExplosionArt.firework(level, center.add(0, 12, 0), FireworkExplosion.Shape.STAR, new int[] {0x70E040, 0xA050FF}, new int[] {0xFFFFFF});
		}
		if (t == c + 6) {
			// the tiles fall, a little outward
			for (int i = 0; i < tiles; i++) {
				Props.Prop tile = props.named("tile" + i);
				if (tile == null) continue;
				Vec3 out = new Vec3(tile.pos().x - target.x, 0, tile.pos().z - target.z);
				Vec3 dir = out.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : out.normalize();
				double sp = 0.1 + 0.25 * rnd.nextDouble();
				props.throwPiece(tile, new Vec3(dir.x * sp, 0.1, dir.z * sp), target.y + 0.15);
			}
		}
		if (t == end - 20) {
			// the grid on the floor goes out
			double half = n * side / 2.0;
			for (int j = 0; j <= n; j++) {
				double off = (j - n / 2.0) * side;
				Vec3 midX = new Vec3(target.x, target.y + 0.1, target.z + off);
				Vec3 midZ = new Vec3(target.x + off, target.y + 0.1, target.z);
				Vec3[][] ends = {{midX, new Vec3(target.x - half, target.y + 0.1, target.z + off)}, {midX, new Vec3(target.x + half, target.y + 0.1, target.z + off)},
					{midZ, new Vec3(target.x + off, target.y + 0.1, target.z - half)}, {midZ, new Vec3(target.x + off, target.y + 0.1, target.z + half)}};
				for (int k = 0; k < 4; k++) Shapes.growLine(props, props.named("line" + j + "_" + k), ends[k][0], ends[k][1], 0.03, 16);
			}
		}
		if (t > c && t < c + 40 && t % 3 == 0) {
			ExplosionArt.emit(level, viewers, DUST[(t / 3) % DUST.length], center.x, center.y, center.z, 8, 2.5, 2.5, 2.5, 0.05);
		}
	}
}
