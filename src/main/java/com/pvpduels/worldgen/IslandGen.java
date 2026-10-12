package com.pvpduels.worldgen;

import java.util.Random;

/**
 * The lobby island: a big floating island of grass over the void with a village in the middle, a plaza with a
 * big green "PVP DUELS" sign, a few small mountains, ponds, a waterfall, forests and flowers. Everything is written
 * into a {@link BlockGrid} as text, so it can be previewed as a picture and then built into a real world.
 */
public final class IslandGen {
	/** Half size of the grid on x and z. */
	public static final int HALF = 84;
	/** Local height of the grass of the village. */
	public static final int GY = 76;
	public static final int HEIGHT = 150;

	// ---------------------------------------------------------------- the blocks (one place to fix a wrong name)
	static final String GRASS = "minecraft:grass_block[snowy=false]";
	static final String DIRT = "minecraft:dirt";
	static final String COARSE = "minecraft:coarse_dirt";
	static final String ROOTED = "minecraft:rooted_dirt";
	static final String PODZOL = "minecraft:podzol[snowy=false]";
	static final String MOSS = "minecraft:moss_block";
	static final String STONE = "minecraft:stone";
	static final String ANDESITE = "minecraft:andesite";
	static final String TUFF = "minecraft:tuff";
	static final String COBBLE = "minecraft:cobblestone";
	static final String MOSSY_COBBLE = "minecraft:mossy_cobblestone";
	static final String DEEPSLATE = "minecraft:deepslate[axis=y]";
	static final String COBBLED_DEEPSLATE = "minecraft:cobbled_deepslate";
	static final String WATER = "minecraft:water[level=0]";
	static final String CLAY = "minecraft:clay";
	static final String GRAVEL = "minecraft:gravel";

	public final BlockGrid grid = new BlockGrid(-HALF, 0, -HALF, 2 * HALF + 1, HEIGHT, 2 * HALF + 1);
	final Noise noise;
	final Random rnd;
	final long seed;

	/** Top block height of every column of the island (or -1 outside of it), index [x+HALF][z+HALF]. */
	final int[][] top = new int[2 * HALF + 1][2 * HALF + 1];
	final int[][] bottom = new int[2 * HALF + 1][2 * HALF + 1];
	/** Columns where nothing may grow (roads, plaza, buildings, water). */
	final boolean[][] reserved = new boolean[2 * HALF + 1][2 * HALF + 1];
	/** How much of a column belongs to a hill (0 = none). */
	final double[][] hillAmount = new double[2 * HALF + 1][2 * HALF + 1];

	/** Where players should appear: the plaza in front of the sign. */
	public int spawnX = 0, spawnY = GY + 1, spawnZ = 8;
	/** The rim cell where the waterfall starts, for the builder (local coordinates). */
	public int fallX, fallY, fallZ;
	public boolean hasFall;

	private double[][] hills; // cx, cz, radius, height

	public IslandGen(long seed) {
		this.seed = seed;
		this.noise = new Noise(seed);
		this.rnd = new Random(seed);
	}

	public static BlockGrid generate(long seed) {
		return new IslandGen(seed).build().grid;
	}

	public IslandGen build() {
		planHills();
		terrain();
		fillColumns();
		new Village(this).build();
		new Nature(this).build();
		return this;
	}

	// ---------------------------------------------------------------- shape
	double coast(double angle) {
		return 63 * (1 + 0.10 * Math.sin(3 * angle + 1.3) + 0.065 * Math.sin(5 * angle + 4.1)
			+ 0.035 * Math.sin(9 * angle + 0.7) + 0.015 * Math.sin(13 * angle + 2.0));
	}

	static double smooth(double a, double b, double v) {
		double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	int ix(int x) {
		return x + HALF;
	}

	boolean onIsland(int x, int z) {
		return Math.abs(x) <= HALF && Math.abs(z) <= HALF && top[ix(x)][ix(z)] >= 0;
	}

	/** The y of the top block of the column, or -1 when there is no island there. */
	int surface(int x, int z) {
		if (Math.abs(x) > HALF || Math.abs(z) > HALF) return -1;
		return top[ix(x)][ix(z)];
	}

	private void planHills() {
		// angle in degrees, distance as part of the coast, radius as part of the coast, height in blocks
		double[][] plan = {
			{268, 0.74, 0.34, 28},  // behind the sign: the tall one
			{158, 0.70, 0.27, 20},
			{30, 0.72, 0.23, 14},
		};
		hills = new double[plan.length][];
		for (int i = 0; i < plan.length; i++) {
			double a = Math.toRadians(plan[i][0]);
			double c = coast(a);
			hills[i] = new double[] {Math.cos(a) * plan[i][1] * c, Math.sin(a) * plan[i][1] * c, plan[i][2] * c, plan[i][3]};
		}
	}

	double[][] hills() {
		return hills;
	}

	private double hillHeight(int x, int z) {
		double best = 0;
		for (int i = 0; i < hills.length; i++) {
			double dx = x - hills[i][0];
			double dz = z - hills[i][1];
			double t = 1 - Math.sqrt(dx * dx + dz * dz) / hills[i][2];
			if (t <= 0) continue;
			double wobble = 0.85 + 0.3 * noise.fbm(x * 0.07 + i * 31, z * 0.07 - i * 17, 3);
			double detail = (noise.fbm(x * 0.2 + 5, z * 0.2 + 9, 2) - 0.5) * 2.2 * Math.min(1, t * 3);
			double h = hills[i][3] * Math.pow(t, 1.3) * wobble + detail * Math.min(1, t * 4);
			best = Math.max(best, h);
		}
		return best;
	}

	private void terrain() {
		for (int x = -HALF; x <= HALF; x++) {
			for (int z = -HALF; z <= HALF; z++) {
				int a = ix(x), b = ix(z);
				top[a][b] = -1;
				bottom[a][b] = -1;
				double r = Math.sqrt(x * x + (double) z * z);
				double angle = Math.atan2(z, x);
				double d = r / coast(angle);
				if (d >= 1) continue;

				double amp = smooth(30, 46, r) * 1.9;
				double und = (noise.fbm(x * 0.055 + 3, z * 0.055 + 7, 3) - 0.5) * 2 * amp;
				double bevel = -2.6 * smooth(0.84, 1.0, d);
				double hill = hillHeight(x, z);
				hillAmount[a][b] = hill;
				int t = GY + (int) Math.round(und + bevel + hill);
				top[a][b] = t;

				// a cone: deepest in the middle, the sides curve in towards the rim
				double base = 40 * Math.pow(Math.max(0, 1 - d), 0.85) + 9 * Math.max(0, 1 - 1.7 * d * d);
				base *= 0.7 + 0.6 * noise.fbm(x * 0.075 + 17, z * 0.075 - 9, 3);
				double spike = Math.pow(noise.ridged(x * 0.1 + 40, z * 0.1 + 12), 2.2) * 16 * Math.pow(1 - d, 0.8);
				int bot = (int) Math.round(GY - 3 - base - spike);
				bottom[a][b] = Math.max(6, Math.min(bot, t - 3));
			}
		}
	}

	private void fillColumns() {
		for (int x = -HALF; x <= HALF; x++) {
			for (int z = -HALF; z <= HALF; z++) {
				int t = top[ix(x)][ix(z)];
				if (t < 0) continue;
				int bot = bottom[ix(x)][ix(z)];
				double hill = hillAmount[ix(x)][ix(z)];
				int slope = slope(x, z);
				boolean rocky = hill > 2.0 && (slope >= 2 || hill > 0.8 * maxHill(x, z));
				for (int y = bot; y <= t; y++) {
					int k = t - y;
					grid.set(x, y, z, material(x, y, z, k, y - bot, rocky, hill));
				}
				// the underside: moss, roots and a few green lights hanging under the island
				decorateUnderside(x, z, bot);
			}
		}
	}

	private double maxHill(int x, int z) {
		double best = 1;
		for (double[] h : hills) {
			double dx = x - h[0], dz = z - h[1];
			if (Math.sqrt(dx * dx + dz * dz) < h[2]) best = Math.max(best, h[3]);
		}
		return best;
	}

	private int slope(int x, int z) {
		int t = top[ix(x)][ix(z)];
		int s = 0;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs) {
			int n = surface(x + d[0], z + d[1]);
			if (n >= 0) s = Math.max(s, Math.abs(n - t));
		}
		return s;
	}

	private String material(int x, int y, int z, int k, int u, boolean rocky, double hill) {
		double r = noise.n(x * 0.9 + 100, z * 0.9 + y * 0.7);
		if (k == 0) {
			if (rocky) return r < 0.5 ? STONE : (r < 0.75 ? ANDESITE : (r < 0.9 ? MOSSY_COBBLE : MOSS));
			if (noise.fbm(x * 0.12 + 55, z * 0.12 + 21, 2) > 0.74) return MOSS;
			return GRASS;
		}
		if (k <= 3) {
			if (rocky && k <= 2) return r < 0.6 ? STONE : ANDESITE;
			return r < 0.07 ? COARSE : (r < 0.1 ? ROOTED : DIRT);
		}
		double deep = IslandGen.smooth(14, 34, (GY - y) + (noise.fbm(x * 0.12, z * 0.12 + y * 0.05, 2) - 0.5) * 16);
		if (r < deep) return r < deep * 0.72 ? DEEPSLATE : (r < deep * 0.9 ? COBBLED_DEEPSLATE : TUFF);
		if (r < 0.62) return STONE;
		if (r < 0.78) return ANDESITE;
		if (r < 0.9) return TUFF;
		if (r < 0.97) return COBBLE;
		return MOSSY_COBBLE;
	}

	private void decorateUnderside(int x, int z, int bot) {
		Random local = new Random(seed * 31 + x * 7919L + z * 104729L);
		double mossy = noise.fbm(x * 0.09 + 200, z * 0.09 + 300, 2);
		if (mossy > 0.52 && local.nextDouble() < 0.55) grid.set(x, bot, z, MOSS);
		if (local.nextDouble() < 0.0065) grid.set(x, bot, z, "minecraft:verdant_froglight[axis=y]");
		else if (local.nextDouble() < 0.0045) grid.set(x, bot + 1, z, "minecraft:verdant_froglight[axis=y]");
		if (grid.isAir(x, bot - 1, z) && local.nextDouble() < 0.07 && mossy > 0.45) {
			grid.set(x, bot - 1, z, "minecraft:hanging_roots[waterlogged=false]");
			if (local.nextDouble() < 0.4) grid.set(x, bot - 2, z, "minecraft:hanging_roots[waterlogged=false]");
		}
	}
}
