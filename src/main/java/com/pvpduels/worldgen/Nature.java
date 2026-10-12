package com.pvpduels.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Ponds, the river and the waterfall, the path up the mountain, trees, bushes and flowers. */
final class Nature {
	private final IslandGen ig;
	private final BlockGrid g;
	private final Random rnd;
	private static final int GY = IslandGen.GY;
	private final boolean[][] taken = new boolean[2 * IslandGen.HALF + 1][2 * IslandGen.HALF + 1];

	Nature(IslandGen ig) {
		this.ig = ig;
		this.g = ig.grid;
		this.rnd = new Random(ig.seed ^ 0xBEEF1234L);
	}

	void build() {
		pond(28, 27, 5.5);
		pond(-27, 41, 4.2);
		river(28, 27);
		mountainPath();
		trees();
		groundCover();
	}

	private int top(int x, int z) {
		return ig.surface(x, z);
	}

	private boolean reserved(int x, int z) {
		return Math.abs(x) <= IslandGen.HALF && Math.abs(z) <= IslandGen.HALF && ig.reserved[ig.ix(x)][ig.ix(z)];
	}

	private void reserve(int x, int z) {
		if (Math.abs(x) <= IslandGen.HALF && Math.abs(z) <= IslandGen.HALF) ig.reserved[ig.ix(x)][ig.ix(z)] = true;
	}

	/** Makes the column end exactly at height h (digging or filling with dirt) and puts this block on top. */
	private void level(int x, int z, int h, String topBlock) {
		int t = top(x, z);
		if (t < 0) return;
		for (int y = t; y > h; y--) g.set(x, y, z, null);
		for (int y = t + 1; y < h; y++) g.set(x, y, z, IslandGen.DIRT);
		g.set(x, h, z, topBlock);
		ig.top[ig.ix(x)][ig.ix(z)] = h;
		ig.hillAmount[ig.ix(x)][ig.ix(z)] = 0;
	}

	// ---------------------------------------------------------------- water
	private void pond(int cx, int cz, double radius) {
		for (int x = cx - 8; x <= cx + 8; x++) {
			for (int z = cz - 8; z <= cz + 8; z++) {
				if (top(x, z) < 0) continue;
				double d = Math.hypot(x - cx, z - cz);
				double wobble = radius * (1 + 0.25 * (ig.noise.fbm(x * 0.3 + 7, z * 0.3 + 3, 2) - 0.5));
				if (d <= wobble + 1.5) {
					// flat ground at village height around the pond
					level(x, z, GY, IslandGen.GRASS);
					reserve(x, z);
				}
				if (d <= wobble) {
					g.set(x, GY, z, IslandGen.WATER);
					g.set(x, GY - 1, z, IslandGen.WATER);
					g.set(x, GY - 2, z, IslandGen.CLAY);
					if (rnd.nextDouble() < 0.22 && d < wobble - 0.8) g.set(x, GY + 1, z, "minecraft:lily_pad");
				} else if (d <= wobble + 1.5 && rnd.nextDouble() < 0.35) {
					// reeds on the shore
					int h = 1 + rnd.nextInt(3);
					for (int i = 1; i <= h; i++) g.set(x, GY + i, z, "minecraft:sugar_cane[age=0]");
				}
			}
		}
	}

	/** Cells (x,z) of a straight stream from the pond to the rim of the island. */
	private void river(int px, int pz) {
		double ang = Math.atan2(pz, px);
		double dx = Math.cos(ang), dz = Math.sin(ang);
		List<int[]> cells = new ArrayList<>();
		int lastX = px, lastZ = pz;
		for (double s = 4; s < 60; s += 0.5) {
			int x = (int) Math.round(px + dx * s), z = (int) Math.round(pz + dz * s);
			if (x == lastX && z == lastZ) continue;
			if (top(x, z) < 0) break;
			cells.add(new int[] {x, z});
			lastX = x;
			lastZ = z;
		}
		if (cells.size() < 4) return;
		// bank first (so the water is held), then the water
		for (int[] c : cells) {
			for (int ox = -3; ox <= 3; ox++) {
				for (int oz = -3; oz <= 3; oz++) {
					double dd = Math.hypot(ox, oz);
					int x = c[0] + ox, z = c[1] + oz;
					if (top(x, z) < 0 || dd > 3.2) continue;
					if (dd > 1.6 && top(x, z) < GY) level(x, z, GY, IslandGen.GRASS);
					if (dd > 1.6 && top(x, z) > GY + 2) continue;
					if (dd > 1.6 && top(x, z) > GY) level(x, z, GY, IslandGen.GRASS);
				}
			}
		}
		for (int[] c : cells) {
			for (int ox = -2; ox <= 2; ox++) {
				for (int oz = -2; oz <= 2; oz++) {
					if (Math.hypot(ox, oz) > 1.5) continue;
					int x = c[0] + ox, z = c[1] + oz;
					if (top(x, z) < 0) continue;
					level(x, z, GY, IslandGen.GRASS);
					g.set(x, GY, z, IslandGen.WATER);
					g.set(x, GY - 1, z, IslandGen.CLAY);
					reserve(x, z);
				}
			}
		}
		int[] last = cells.get(cells.size() - 1);
		ig.fallX = last[0];
		ig.fallY = GY;
		ig.fallZ = last[1];
		ig.hasFall = true;
	}

	// ---------------------------------------------------------------- the mountain
	/** A stairway of stone from the foot of the tallest hill to the top, and a little lookout up there. */
	private void mountainPath() {
		double[][] hills = ig.hills();
		double[] h = hills[0];
		// the way up starts behind the gate in the sign (x = -6), at the foot of the hill, and winds to the top
		double ux = -6 - h[0], uz = -24 - h[1];
		double len = Math.hypot(ux, uz);
		ux /= len;
		uz /= len;
		// start at the foot on the village side and walk to the middle
		double r0 = h[2] * 0.92;
		int prevH = -1;
		List<int[]> path = new ArrayList<>();
		for (double s = 0; s <= r0; s += 0.7) {
			int x = (int) Math.round(h[0] + ux * (r0 - s) + Math.sin(s * 0.35) * 2.2 * -uz);
			int z = (int) Math.round(h[1] + uz * (r0 - s) + Math.sin(s * 0.35) * 2.2 * ux);
			int t = top(x, z);
			if (t < 0) continue;
			int ph = prevH < 0 ? t : Math.max(prevH, Math.min(t, prevH + 1));
			path.add(new int[] {x, z, ph});
			prevH = ph;
		}
		for (int[] p : path) {
			for (int side = 0; side < 2; side++) {
				int x = p[0] + (Math.abs(uz) > Math.abs(ux) ? side : 0);
				int z = p[1] + (Math.abs(uz) > Math.abs(ux) ? 0 : side);
				if (top(x, z) < 0) continue;
				level(x, z, p[2], rnd.nextInt(4) == 0 ? "minecraft:mossy_stone_bricks" : "minecraft:stone_bricks");
				for (int y = p[2] + 1; y <= p[2] + 3; y++) g.set(x, y, z, null);
				reserve(x, z);
			}
		}
		if (path.isEmpty()) return;
		int[] end = path.get(path.size() - 1);
		lookout(end[0], end[1], end[2]);
	}

	private void lookout(int cx, int cz, int h) {
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				if (top(x, z) < 0) continue;
				level(x, z, h, "minecraft:stone_bricks");
				for (int y = h + 1; y <= h + 6; y++) g.set(x, y, z, null);
				reserve(x, z);
			}
		}
		for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) g.set(x, h, z, "minecraft:mossy_stone_bricks");
		for (int sx : new int[] {-2, 2}) {
			for (int sz : new int[] {-2, 2}) {
				for (int y = h + 1; y <= h + 4; y++) g.set(cx + sx, y, cz + sz, "minecraft:stripped_spruce_log[axis=y]");
			}
		}
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				int ring = Math.max(Math.abs(x - cx), Math.abs(z - cz));
				if (ring == 3) g.set(x, h + 5, z, "minecraft:mossy_stone_brick_slab[type=bottom]");
				else if (ring == 2) g.set(x, h + 5, z, "minecraft:mossy_stone_bricks");
				else if (ring == 1) g.set(x, h + 6, z, "minecraft:mossy_stone_brick_slab[type=bottom]");
				else g.set(x, h + 6, z, "minecraft:mossy_stone_bricks");
			}
		}
		g.set(cx, h + 7, cz, "minecraft:verdant_froglight[axis=y]");
		g.set(cx, h + 4, cz, "minecraft:lantern[hanging=true]");
		// railings
		for (int i = -1; i <= 1; i++) {
			g.set(cx + i, h + 1, cz - 2, "minecraft:oak_fence[east=true,north=false,south=false,west=true]");
			g.set(cx + i, h + 1, cz + 2, "minecraft:oak_fence[east=true,north=false,south=false,west=true]");
			g.set(cx - 2, h + 1, cz + i, "minecraft:oak_fence[east=false,north=true,south=true,west=false]");
			g.set(cx + 2, h + 1, cz + i, "minecraft:oak_fence[east=false,north=true,south=true,west=false]");
		}
	}

	// ---------------------------------------------------------------- trees
	private boolean spacingFree(int x, int z, int r) {
		for (int ox = -r; ox <= r; ox++)
			for (int oz = -r; oz <= r; oz++) {
				int xx = x + ox, zz = z + oz;
				if (Math.abs(xx) > IslandGen.HALF || Math.abs(zz) > IslandGen.HALF) continue;
				if (taken[ig.ix(xx)][ig.ix(zz)]) return false;
			}
		return true;
	}

	private void markTaken(int x, int z, int r) {
		for (int ox = -r; ox <= r; ox++)
			for (int oz = -r; oz <= r; oz++) {
				int xx = x + ox, zz = z + oz;
				if (Math.abs(xx) > IslandGen.HALF || Math.abs(zz) > IslandGen.HALF) continue;
				if (ox * ox + oz * oz <= r * r) taken[ig.ix(xx)][ig.ix(zz)] = true;
			}
	}

	private int slopeAt(int x, int z) {
		int t = top(x, z);
		int s = 0;
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			int n = top(x + d[0], z + d[1]);
			if (n < 0) return 9;
			s = Math.max(s, Math.abs(n - t));
		}
		return s;
	}

	private void trees() {
		List<int[]> order = new ArrayList<>();
		for (int x = -IslandGen.HALF; x <= IslandGen.HALF; x++)
			for (int z = -IslandGen.HALF; z <= IslandGen.HALF; z++)
				if (top(x, z) >= 0) order.add(new int[] {x, z});
		java.util.Collections.shuffle(order, rnd);
		int placed = 0, rReserved = 0, rSlope = 0, rDensity = 0, rSpace = 0, rEdge = 0;
		for (int[] c : order) {
			int x = c[0], z = c[1];
			if (reserved(x, z) || !g.isId(x, top(x, z), z, "minecraft:grass_block")) { rReserved++; continue; }
			if (slopeAt(x, z) > 1) { rSlope++; continue; }
			double r = Math.hypot(x, z);
			double forest = ig.noise.fbm(x * 0.05 + 80, z * 0.05 + 60, 3);
			double hill = ig.hillAmount[ig.ix(x)][ig.ix(z)];
			double density = 0.02 + 0.30 * IslandGen.smooth(0.40, 0.58, forest);
			if (r < 34) density *= 0.12; // the village area stays open
			if (hill > 3) density *= 1.6;
			if (rnd.nextDouble() > density) { rDensity++; continue; }
			if (!spacingFree(x, z, 2)) { rSpace++; continue; }
			// edge of the island: leave a little room
			if (top(x + 3, z) < 0 || top(x - 3, z) < 0 || top(x, z + 3) < 0 || top(x, z - 3) < 0) { rEdge++; continue; }
			tree(x, z, hill);
			markTaken(x, z, 2);
			placed++;
		}
		System.err.println("trees: " + placed + " (reserved/not grass " + rReserved + ", slope " + rSlope + ", density " + rDensity + ", spacing " + rSpace + ", edge " + rEdge + ")");
	}

	private void tree(int x, int z, double hill) {
		int t = top(x, z);
		double k = rnd.nextDouble();
		if (hill > 4) {
			if (k < 0.7) spruce(x, t, z);
			else oak(x, t, z, "oak", false);
		} else if (k < 0.46) oak(x, t, z, "oak", false);
		else if (k < 0.70) birch(x, t, z);
		else if (k < 0.82) oak(x, t, z, "azalea", true);
		else spruce(x, t, z);
		g.set(x, t, z, IslandGen.PODZOL);
	}

	private void leaf(int x, int y, int z, String leaves) {
		if (g.isAir(x, y, z)) g.set(x, y, z, leaves);
	}

	private void oak(int x, int t, int z, String kind, boolean flowering) {
		int h = 4 + rnd.nextInt(3);
		String log = "minecraft:oak_log[axis=y]";
		for (int y = 1; y <= h; y++) g.set(x, t + y, z, log);
		for (int dy = h - 2; dy <= h + 1; dy++) {
			int rad = dy >= h ? 1 : 2;
			for (int ox = -rad; ox <= rad; ox++) {
				for (int oz = -rad; oz <= rad; oz++) {
					if (Math.abs(ox) == rad && Math.abs(oz) == rad && (dy >= h || rnd.nextBoolean())) continue;
					String leaves = kind.equals("azalea")
						? (rnd.nextInt(4) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : "minecraft:azalea_leaves[persistent=true]")
						: "minecraft:oak_leaves[persistent=true]";
					leaf(x + ox, t + dy, z + oz, leaves);
				}
			}
		}
	}

	private void birch(int x, int t, int z) {
		int h = 5 + rnd.nextInt(3);
		for (int y = 1; y <= h; y++) g.set(x, t + y, z, "minecraft:birch_log[axis=y]");
		for (int dy = h - 2; dy <= h + 1; dy++) {
			int rad = dy >= h ? 1 : 2;
			for (int ox = -rad; ox <= rad; ox++) {
				for (int oz = -rad; oz <= rad; oz++) {
					if (Math.abs(ox) == rad && Math.abs(oz) == rad && (dy >= h || rnd.nextBoolean())) continue;
					leaf(x + ox, t + dy, z + oz, "minecraft:birch_leaves[persistent=true]");
				}
			}
		}
	}

	private void spruce(int x, int t, int z) {
		int h = 7 + rnd.nextInt(5);
		for (int y = 1; y <= h; y++) g.set(x, t + y, z, "minecraft:spruce_log[axis=y]");
		int top = t + h + 1;
		for (int yy = t + 2; yy <= top; yy++) {
			int kk = top - yy; // 0 at the very tip
			int rad = kk == 0 ? 0 : Math.min(3, (kk + 1) / 2);
			if (kk >= 2 && kk % 2 == 0) rad = Math.max(1, rad - 1);
			for (int ox = -rad; ox <= rad; ox++) {
				for (int oz = -rad; oz <= rad; oz++) {
					if (Math.abs(ox) + Math.abs(oz) > rad + (rad > 1 ? 1 : 0)) continue;
					leaf(x + ox, yy, z + oz, "minecraft:spruce_leaves[persistent=true]");
				}
			}
		}
	}

	// ---------------------------------------------------------------- flowers and grass
	private static final String[] FLOWERS = {"minecraft:dandelion", "minecraft:poppy", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
		"minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:allium"};

	private void groundCover() {
		for (int x = -IslandGen.HALF; x <= IslandGen.HALF; x++) {
			for (int z = -IslandGen.HALF; z <= IslandGen.HALF; z++) {
				int t = top(x, z);
				if (t < 0 || reserved(x, z)) continue;
				if (!g.isAir(x, t + 1, z)) continue;
				boolean grass = g.isId(x, t, z, "minecraft:grass_block");
				boolean podzol = g.isId(x, t, z, "minecraft:podzol");
				boolean moss = g.isId(x, t, z, "minecraft:moss_block");
				double r = rnd.nextDouble();
				if (moss) {
					if (r < 0.5) g.set(x, t + 1, z, "minecraft:moss_carpet");
					else if (r < 0.54) g.set(x, t + 1, z, "minecraft:fern");
					continue;
				}
				if (podzol) {
					if (r < 0.25) g.set(x, t + 1, z, "minecraft:fern");
					continue;
				}
				if (!grass) continue;
				double patch = ig.noise.fbm(x * 0.13 + 300, z * 0.13 + 500, 2);
				double flowerChance = patch > 0.64 ? 0.22 : 0.012;
				if (r < flowerChance) {
					String f = FLOWERS[rnd.nextInt(FLOWERS.length)];
					if (patch > 0.64) f = FLOWERS[(int) ((patch * 40) % 4)]; // one kind per patch
					g.set(x, t + 1, z, f);
				} else if (r < flowerChance + 0.17) {
					g.set(x, t + 1, z, "minecraft:short_grass");
				} else if (r < flowerChance + 0.195) {
					g.set(x, t + 1, z, "minecraft:tall_grass[half=lower]");
					g.set(x, t + 2, z, "minecraft:tall_grass[half=upper]");
				} else if (r < flowerChance + 0.215) {
					g.set(x, t + 1, z, "minecraft:fern");
				} else if (r < flowerChance + 0.222) {
					g.set(x, t + 1, z, "minecraft:large_fern[half=lower]");
					g.set(x, t + 2, z, "minecraft:large_fern[half=upper]");
				}
			}
		}
	}
}
