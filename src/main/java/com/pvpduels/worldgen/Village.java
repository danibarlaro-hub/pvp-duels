package com.pvpduels.worldgen;

import java.util.Random;

/** The plaza, the roads, the big "PVP DUELS" sign and the little houses of the island. */
final class Village {
	private final IslandGen ig;
	private final BlockGrid g;
	private final Random rnd;
	private static final int GY = IslandGen.GY;
	final boolean[][] road = new boolean[2 * IslandGen.HALF + 1][2 * IslandGen.HALF + 1];

	// ---------------------------------------------------------------- house styles
	private record Style(String wall, String post, String foundation, String floor, String stair, String fill, String ridge, String slab) {}

	private static final Style OAK = new Style("minecraft:oak_planks", "minecraft:oak_log[axis=y]", "minecraft:cobblestone",
		"minecraft:oak_planks", "minecraft:spruce_stairs[facing=%s,half=bottom]", "minecraft:spruce_planks", "minecraft:spruce_planks", "minecraft:spruce_slab[type=bottom]");
	private static final Style BIRCH = new Style("minecraft:birch_planks", "minecraft:stripped_birch_log[axis=y]", "minecraft:mossy_cobblestone",
		"minecraft:birch_planks", "minecraft:oxidized_cut_copper_stairs[facing=%s,half=bottom]", "minecraft:oxidized_cut_copper", "minecraft:oxidized_cut_copper", "minecraft:oxidized_cut_copper_slab[type=bottom]");
	private static final Style SPRUCE = new Style("minecraft:spruce_planks", "minecraft:dark_oak_log[axis=y]", "minecraft:stone_bricks",
		"minecraft:spruce_planks", "minecraft:mossy_stone_brick_stairs[facing=%s,half=bottom]", "minecraft:mossy_stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:mossy_stone_brick_slab[type=bottom]");
	private static final Style STONE = new Style("minecraft:stone_bricks", "minecraft:stripped_spruce_log[axis=y]", "minecraft:stone_bricks",
		"minecraft:spruce_planks", "minecraft:dark_oak_stairs[facing=%s,half=bottom]", "minecraft:dark_oak_planks", "minecraft:dark_oak_planks", "minecraft:dark_oak_slab[type=bottom]");
	private static final Style HALL = new Style("minecraft:mossy_stone_bricks", "minecraft:stripped_dark_oak_log[axis=y]", "minecraft:stone_bricks",
		"minecraft:polished_andesite", "minecraft:oxidized_cut_copper_stairs[facing=%s,half=bottom]", "minecraft:oxidized_cut_copper", "minecraft:oxidized_cut_copper", "minecraft:oxidized_cut_copper_slab[type=bottom]");

	Village(IslandGen ig) {
		this.ig = ig;
		this.g = ig.grid;
		this.rnd = new Random(ig.seed ^ 0x5DEECE66DL);
	}

	void build() {
		plaza();
		roads();
		monument();
		houses();
		stalls();
		well();
		farm();
		lampPosts();
	}

	// ---------------------------------------------------------------- helpers
	private int top(int x, int z) {
		return ig.surface(x, z);
	}

	private void setTop(int x, int z, String block) {
		int t = top(x, z);
		if (t >= 0) g.set(x, t, z, block);
	}

	private void reserve(int x1, int z1, int x2, int z2) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
				if (Math.abs(x) <= IslandGen.HALF && Math.abs(z) <= IslandGen.HALF) ig.reserved[ig.ix(x)][ig.ix(z)] = true;
	}

	/** Makes the ground level (at the height of the village) inside this rectangle. */
	private void flatten(int x1, int z1, int x2, int z2) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				int t = top(x, z);
				if (t < 0) continue;
				for (int y = t; y > GY; y--) g.set(x, y, z, null);
				for (int y = t + 1; y <= GY; y++) g.set(x, y, z, IslandGen.DIRT);
				g.set(x, GY, z, IslandGen.GRASS);
				if (GY - 1 > ig.bottom[ig.ix(x)][ig.ix(z)]) g.set(x, GY - 1, z, IslandGen.DIRT);
				ig.top[ig.ix(x)][ig.ix(z)] = GY;
				ig.hillAmount[ig.ix(x)][ig.ix(z)] = 0;
			}
		}
	}

	private boolean allOnIsland(int x1, int z1, int x2, int z2) {
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x += 1)
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z += 1)
				if (top(x, z) < 0) return false;
		return true;
	}

	// ---------------------------------------------------------------- plaza
	private void plaza() {
		int R = 15;
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r > 14.6) continue;
				ig.reserved[ig.ix(x)][ig.ix(z)] = true;
				double n = rnd.nextDouble();
				String b;
				if (r >= 13.4) b = "minecraft:polished_andesite";
				else if (r >= 8.4 && r < 9.5) b = "minecraft:lime_concrete";
				else if (r < 3.6) b = "minecraft:stone_bricks";
				else b = n < 0.78 ? "minecraft:stone_bricks" : (n < 0.92 ? "minecraft:mossy_stone_bricks" : "minecraft:cracked_stone_bricks");
				// four stripes of polished andesite along the roads
				if (r >= 3.6 && r < 13.4 && (Math.abs(x) <= 1 || Math.abs(z) <= 1) && !(r >= 8.4 && r < 9.5)) b = "minecraft:polished_andesite";
				g.set(x, GY, z, b);
			}
		}
		// the fountain
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				double r = Math.sqrt(x * x + z * z);
				if (r < 2.9) {
					g.set(x, GY - 1, z, "minecraft:stone_bricks");
					g.set(x, GY, z, IslandGen.WATER);
				} else if (r < 3.8) {
					g.set(x, GY, z, "minecraft:stone_bricks");
					g.set(x, GY + 1, z, "minecraft:mossy_stone_bricks");
				}
			}
		}
		g.box(0, GY, 0, 0, GY + 3, 0, "minecraft:stone_bricks");
		g.set(0, GY + 4, 0, "minecraft:verdant_froglight[axis=y]");
		g.set(0, GY + 3, 1, "minecraft:stone_brick_slab[type=top]");
		g.set(0, GY + 3, -1, "minecraft:stone_brick_slab[type=top]");
		g.set(1, GY + 3, 0, "minecraft:stone_brick_slab[type=top]");
		g.set(-1, GY + 3, 0, "minecraft:stone_brick_slab[type=top]");
		for (int[] c : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			g.set(c[0], GY + 1, c[1], "minecraft:stone_brick_slab[type=bottom]");
		}
	}

	// ---------------------------------------------------------------- roads
	private void roads() {
		int[][] axes = {{1, 0, 15, 44}, {-1, 0, 15, 44}, {0, 1, 15, 27}};
		for (int[] a : axes) {
			for (int s = a[2]; s <= a[3]; s++) {
				for (int w = -1; w <= 1; w++) {
					int x = a[0] != 0 ? a[0] * s : w;
					int z = a[0] != 0 ? w : a[1] * s;
					roadCell(x, z);
				}
			}
		}
		// to the north the road bends west to the gate in the sign (it is in the gap between the two words)
		for (int z = -15; z >= -22; z--) for (int x = -1; x <= 1; x++) roadCell(x, z);
		for (int z = -23; z <= -21; z++) for (int x = -7; x <= 1; x++) roadCell(x, z);
		for (int z = -22; z >= -30; z--) for (int x = -7; x <= -5; x++) roadCell(x, z);
	}

	void roadCell(int x, int z) {
		int t = top(x, z);
		if (t < 0) return;
		ig.reserved[ig.ix(x)][ig.ix(z)] = true;
		road[ig.ix(x)][ig.ix(z)] = true;
		double n = rnd.nextDouble();
		g.set(x, t, z, n < 0.78 ? "minecraft:dirt_path" : (n < 0.9 ? IslandGen.GRAVEL : IslandGen.COARSE));
	}

	// ---------------------------------------------------------------- the sign
	private static final String[][] FONT = {
		// P
		{"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."},
		// V
		{"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."},
		// D
		{"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."},
		// U
		{"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
		// E
		{"#####", "#....", "#....", "####.", "#....", "#....", "#####"},
		// L
		{"#....", "#....", "#....", "#....", "#....", "#....", "#####"},
		// S
		{".####", "#....", "#....", ".###.", "....#", "....#", "####."},
	};

	private int glyph(char c) {
		return "PVDUELS".indexOf(c);
	}

	private void monument() {
		int zFront = -25; // letters
		int zWall1 = -26, zWall2 = -27;
		flatten(-32, -30, 31, -22);
		reserve(-33, -31, 32, -21);
		String wall = "minecraft:polished_deepslate";
		for (int x = -27; x <= 26; x++) {
			g.box(x, GY + 1, zWall2, x, GY + 10, zWall1, wall);
			g.set(x, GY + 1, zFront, "minecraft:mossy_stone_bricks");
			g.set(x, GY + 11, zWall1, "minecraft:mossy_stone_brick_slab[type=bottom]");
			g.set(x, GY + 11, zWall2, "minecraft:mossy_stone_brick_slab[type=bottom]");
			g.set(x, GY + 10, zFront, "minecraft:mossy_stone_brick_slab[type=top]");
			if (Math.floorMod(x, 6) == 0) g.set(x, GY + 1, zFront, "minecraft:verdant_froglight[axis=y]");
			// grass and bushes on the top
			g.set(x, GY + 12, zWall1, rnd.nextInt(3) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : (rnd.nextInt(2) == 0 ? "minecraft:azalea_leaves[persistent=true]" : null));
		}
		String text = "PVP DUELS";
		int cursor = -25;
		for (char c : text.toCharArray()) {
			if (c == ' ') {
				cursor += 4;
				continue;
			}
			String[] rows = FONT[glyph(c)];
			for (int row = 0; row < 7; row++) {
				for (int col = 0; col < 5; col++) {
					if (rows[row].charAt(col) == '#') {
						g.set(cursor + col, GY + 8 - row, zFront, "minecraft:lime_concrete");
					}
				}
			}
			cursor += 6;
		}
		// the two towers at the ends
		for (int side : new int[] {-1, 1}) {
			int cx = side < 0 ? -29 : 28;
			g.box(cx - 1, GY + 1, -28, cx + 1, GY + 13, -26, "minecraft:mossy_stone_bricks");
			g.box(cx - 1, GY + 14, -28, cx + 1, GY + 14, -26, "minecraft:mossy_stone_brick_slab[type=bottom]");
			g.set(cx, GY + 14, -27, "minecraft:verdant_froglight[axis=y]");
			g.set(cx, GY + 15, -27, "minecraft:lantern[hanging=false]");
			for (int y = GY + 3; y <= GY + 11; y += 4) {
				g.set(cx, y, -25, "minecraft:verdant_froglight[axis=y]");
			}
		}
		// the gate: an opening in the sign, between "PVP" and "DUELS"
		for (int x = -7; x <= -5; x++) {
			for (int y = GY + 1; y <= GY + 3; y++) {
				for (int z = zWall2; z <= zFront; z++) g.set(x, y, z, null);
			}
			for (int z = zWall2; z <= zFront; z++) g.set(x, GY + 4, z, "minecraft:mossy_stone_bricks");
		}
		for (int x : new int[] {-8, -4}) {
			for (int y = GY + 1; y <= GY + 4; y++) {
				for (int z = zWall2; z <= zFront; z++) g.set(x, y, z, "minecraft:stripped_dark_oak_log[axis=y]");
			}
		}
		g.set(-6, GY + 3, zWall1, "minecraft:lantern[hanging=true]");
	}

	// ---------------------------------------------------------------- houses
	private boolean free(Frame f, int w, int d, int margin) {
		int hx = w / 2 + margin, hz = d / 2 + margin;
		for (int lx = -hx; lx <= hx; lx++) {
			for (int lz = -hz; lz <= hz + 1; lz++) {
				int x = f.x(lx, lz), z = f.z(lx, lz);
				if (top(x, z) < 0) return false;
				if (ig.reserved[ig.ix(x)][ig.ix(z)] && !road[ig.ix(x)][ig.ix(z)]) return false;
				if (road[ig.ix(x)][ig.ix(z)]) return false;
			}
		}
		return true;
	}

	private void put(Frame f, int lx, int y, int lz, String block) {
		g.set(f.x(lx, lz), y, f.z(lx, lz), block == null ? null : f.fix(block));
	}

	private void house(int cx, int cz, int fx, int fz, int w, int d, boolean ridgeZ, Style st) {
		Frame f = new Frame(cx, cz, fx, fz);
		if (!free(f, w, d, 1)) {
			System.err.println("house at " + cx + "," + cz + " does not fit, skipped");
			return;
		}
		int hx = w / 2, hz = d / 2;
		int x1 = Math.min(f.x(-hx - 2, -hz - 2), f.x(hx + 2, hz + 3)), x2 = Math.max(f.x(-hx - 2, -hz - 2), f.x(hx + 2, hz + 3));
		int z1 = Math.min(f.z(-hx - 2, -hz - 2), f.z(hx + 2, hz + 3)), z2 = Math.max(f.z(-hx - 2, -hz - 2), f.z(hx + 2, hz + 3));
		flatten(x1, z1, x2, z2);
		reserve(x1, z1, x2, z2);

		// floor and foundation
		for (int lx = -hx; lx <= hx; lx++) {
			for (int lz = -hz; lz <= hz; lz++) {
				boolean edge = Math.abs(lx) == hx || Math.abs(lz) == hz;
				put(f, lx, GY, lz, edge ? (rnd.nextInt(4) == 0 ? IslandGen.MOSSY_COBBLE : st.foundation) : st.floor);
				for (int y = GY + 1; y <= GY + 3; y++) put(f, lx, y, lz, null);
				put(f, lx, GY + 4, lz, edge ? st.wall : st.floor);
			}
		}
		// walls
		for (int y = GY + 1; y <= GY + 4; y++) {
			for (int lx = -hx; lx <= hx; lx++) {
				put(f, lx, y, -hz, Math.abs(lx) == hx ? st.post : st.wall);
				put(f, lx, y, hz, Math.abs(lx) == hx ? st.post : st.wall);
			}
			for (int lz = -hz + 1; lz <= hz - 1; lz++) {
				put(f, -hx, y, lz, st.wall);
				put(f, hx, y, lz, st.wall);
			}
		}
		// top plate: logs along the walls
		// door
		put(f, 0, GY + 1, hz, "minecraft:oak_door[facing={B},half=lower,hinge=left,open=false,powered=false]");
		put(f, 0, GY + 2, hz, "minecraft:oak_door[facing={B},half=upper,hinge=left,open=false,powered=false]");
		// windows
		String frontPane = "minecraft:glass_pane[{R}=true,{L}=true]";
		String sidePane = "minecraft:glass_pane[{F}=true,{B}=true]";
		if (hx >= 3) {
			put(f, hx - 1, GY + 2, hz, frontPane);
			put(f, -(hx - 1), GY + 2, hz, frontPane);
		}
		put(f, 0, GY + 2, -hz, frontPane);
		if (hx >= 4) {
			put(f, hx - 2, GY + 2, -hz, frontPane);
			put(f, -(hx - 2), GY + 2, -hz, frontPane);
		}
		put(f, hx, GY + 2, 0, sidePane);
		put(f, -hx, GY + 2, 0, sidePane);
		if (hz >= 4) {
			put(f, hx, GY + 2, hz - 2, sidePane);
			put(f, -hx, GY + 2, hz - 2, sidePane);
			put(f, hx, GY + 2, -(hz - 2), sidePane);
			put(f, -hx, GY + 2, -(hz - 2), sidePane);
		}
		roof(f, hx, hz, ridgeZ, st);
		furnish(f, hx, hz);
		// the lanterns at the door and a path from the door to the nearest road
		put(f, 1, GY + 4, hz + 1, "minecraft:lantern[hanging=true]");
		put(f, -1, GY + 4, hz + 1, "minecraft:lantern[hanging=true]");
		pathFromDoor(f, hz + 1);
	}

	private void roof(Frame f, int hx, int hz, boolean ridgeZ, Style st) {
		int half = ridgeZ ? hx : hz;
		int along = ridgeZ ? hz : hx;
		for (int i = 0; i <= half; i++) {
			int a = half + 1 - i; // distance of this row from the middle
			int y = GY + 5 + i;
			for (int b = -along - 1; b <= along + 1; b++) {
				if (!ridgeZ) {
					put(f, b, y, a, String.format(st.stair, "{B}"));
					put(f, b, y, -a, String.format(st.stair, "{F}"));
				} else {
					put(f, a, y, b, String.format(st.stair, "{L}"));
					put(f, -a, y, b, String.format(st.stair, "{R}"));
				}
			}
			if (i >= 1) {
				for (int yy = GY + 5; yy < y; yy++) {
					for (int b = -along; b <= along; b++) {
						if (!ridgeZ) {
							put(f, b, yy, a, st.fill);
							put(f, b, yy, -a, st.fill);
						} else {
							put(f, a, yy, b, st.fill);
							put(f, -a, yy, b, st.fill);
						}
					}
				}
			}
		}
		// the ridge
		for (int b = -along - 1; b <= along + 1; b++) {
			int yy = GY + 5 + half;
			if (!ridgeZ) put(f, b, yy, 0, st.ridge);
			else put(f, 0, yy, b, st.ridge);
			if (Math.abs(b) <= along) {
				for (int y2 = GY + 5; y2 < yy; y2++) {
					if (!ridgeZ) put(f, b, y2, 0, st.fill);
					else put(f, 0, y2, b, st.fill);
				}
			}
		}
	}

	private void furnish(Frame f, int hx, int hz) {
		int bz = -(hz - 1);
		// bed in the back left corner, head against the back wall
		put(f, -(hx - 1), GY + 1, bz, "minecraft:green_bed[facing={B},occupied=false,part=head]");
		put(f, -(hx - 1), GY + 1, bz + 1, "minecraft:green_bed[facing={B},occupied=false,part=foot]");
		// workbench, furnace and chest along the back wall on the right
		put(f, hx - 1, GY + 1, bz, "minecraft:crafting_table");
		if (hx >= 3) put(f, hx - 2, GY + 1, bz, "minecraft:furnace[facing={F},lit=false]");
		if (hx >= 4) put(f, hx - 3, GY + 1, bz, "minecraft:chest[facing={F},type=single,waterlogged=false]");
		// a table with two chairs
		if (hz >= 3 && hx >= 3) {
			put(f, hx - 2, GY + 1, 1, "minecraft:oak_fence[east=false,north=false,south=false,west=false]");
			put(f, hx - 2, GY + 2, 1, "minecraft:oak_pressure_plate[powered=false]");
		}
		// a rug and a hanging lantern
		for (int lx = -1; lx <= 1; lx++) put(f, lx, GY + 1, 0, "minecraft:green_carpet");
		put(f, 0, GY + 3, 0, "minecraft:lantern[hanging=true]");
		if (hx >= 3) {
			put(f, hx - 1, GY + 1, hz + 1, "minecraft:flowering_azalea");
			put(f, -(hx - 1), GY + 1, hz + 1, "minecraft:azalea");
		}
	}

	private void pathFromDoor(Frame f, int startLz) {
		for (int s = 0; s < 14; s++) {
			int lz = startLz + s;
			int x = f.x(0, lz), z = f.z(0, lz);
			int t = top(x, z);
			if (t < 0) return;
			boolean wasRoad = road[ig.ix(x)][ig.ix(z)];
			if (wasRoad) return;
			g.set(x, t, z, "minecraft:dirt_path");
			ig.reserved[ig.ix(x)][ig.ix(z)] = true;
			// the cell on each side, half of the time
			for (int side : new int[] {-1, 1}) {
				if (rnd.nextInt(3) != 0) continue;
				int sx = f.x(side, lz), sz = f.z(side, lz);
				int st = top(sx, sz);
				if (st < 0 || road[ig.ix(sx)][ig.ix(sz)]) continue;
				g.set(sx, st, sz, rnd.nextBoolean() ? "minecraft:dirt_path" : IslandGen.COARSE);
				ig.reserved[ig.ix(sx)][ig.ix(sz)] = true;
			}
		}
	}

	private void houses() {
		// east road
		house(23, -9, 0, 1, 9, 7, false, OAK);
		house(24, 9, 0, -1, 7, 7, false, BIRCH);
		house(35, -9, 0, 1, 7, 7, false, SPRUCE);
		house(36, 9, 0, -1, 9, 7, true, STONE);
		// west road
		house(-23, -9, 0, 1, 9, 7, false, STONE);
		house(-24, 9, 0, -1, 7, 9, true, OAK);
		house(-35, -9, 0, 1, 7, 7, false, BIRCH);
		// south side, next to the road
		house(-10, 21, 1, 0, 7, 7, false, SPRUCE);
		house(10, 21, -1, 0, 7, 7, false, OAK);
		// the big hall at the end of the south road
		hall();
	}

	private void hall() {
		int cz = 36;
		house(0, cz, 0, -1, 15, 11, false, HALL);
		Frame f = new Frame(0, cz, 0, -1);
		// a wide door: replace the single door by two
		put(f, 1, GY + 1, 5, "minecraft:oak_door[facing={B},half=lower,hinge=right,open=false,powered=false]");
		put(f, 1, GY + 2, 5, "minecraft:oak_door[facing={B},half=upper,hinge=right,open=false,powered=false]");
		put(f, 0, GY + 1, 5, "minecraft:oak_door[facing={B},half=lower,hinge=left,open=false,powered=false]");
		put(f, 0, GY + 2, 5, "minecraft:oak_door[facing={B},half=upper,hinge=left,open=false,powered=false]");
		// tall windows of green glass
		for (int lx : new int[] {-5, -3, 3, 5}) {
			for (int y = GY + 2; y <= GY + 3; y++) put(f, lx, y, 5, "minecraft:lime_stained_glass_pane[{R}=true,{L}=true]");
		}
	}

	// ---------------------------------------------------------------- small things
	private void stalls() {
		stall(-10, -13);
		stall(10, -13);
	}

	/** A market stall with a striped green and white roof, the customers stand on the south side. */
	private void stall(int cx, int cz) {
		Frame f = new Frame(cx, cz, 0, 1);
		if (!free(f, 5, 3, 0)) return;
		reserve(cx - 3, cz - 3, cx + 3, cz + 3);
		for (int lx = -2; lx <= 2; lx++) {
			for (int lz = -1; lz <= 1; lz++) {
				for (int y = GY + 1; y <= GY + 4; y++) put(f, lx, y, lz, null);
			}
		}
		for (int lx : new int[] {-2, 2}) {
			for (int lz : new int[] {-1, 1}) {
				for (int y = GY + 1; y <= GY + 3; y++) put(f, lx, y, lz, "minecraft:oak_fence[east=false,north=false,south=false,west=false]");
			}
		}
		for (int lx = -3; lx <= 3; lx++) {
			for (int lz = -2; lz <= 2; lz++) {
				put(f, lx, GY + 4, lz, Math.floorMod(lx, 2) == 0 ? "minecraft:lime_wool" : "minecraft:white_wool");
			}
		}
		for (int lx = -1; lx <= 1; lx++) {
			put(f, lx, GY + 1, 1, "minecraft:barrel[facing=up,open=false]");
			put(f, lx, GY + 2, 1, lx == 0 ? "minecraft:hay_block[axis=y]" : "minecraft:oak_slab[type=bottom]");
		}
		put(f, -1, GY + 1, -1, "minecraft:chest[facing={F},type=single,waterlogged=false]");
		put(f, 1, GY + 1, -1, "minecraft:composter[level=4]");
		put(f, 0, GY + 3, 0, "minecraft:lantern[hanging=true]");
	}

	private void well() {
		int cx = 17, cz = 15;
		Frame f = new Frame(cx, cz, 0, 1);
		if (!free(f, 3, 3, 1)) return;
		reserve(cx - 3, cz - 3, cx + 3, cz + 3);
		for (int lx = -1; lx <= 1; lx++) {
			for (int lz = -1; lz <= 1; lz++) {
				put(f, lx, GY, lz, "minecraft:stone_bricks");
				if (lx != 0 || lz != 0) put(f, lx, GY + 1, lz, "minecraft:stone_brick_slab[type=bottom]");
				else {
					put(f, 0, GY, 0, IslandGen.WATER);
					put(f, 0, GY - 1, 0, "minecraft:stone_bricks");
				}
			}
		}
		for (int lx : new int[] {-1, 1}) {
			for (int y = GY + 1; y <= GY + 3; y++) put(f, lx, y, 0, "minecraft:oak_fence[east=false,north=false,south=false,west=false]");
		}
		for (int lx = -2; lx <= 2; lx++) for (int lz = -1; lz <= 1; lz++) put(f, lx, GY + 4, lz, "minecraft:spruce_slab[type=bottom]");
		put(f, 0, GY + 3, 0, "minecraft:lantern[hanging=true]");
	}

	private void farm() {
		int cx = -27, cz = 27;
		Frame f = new Frame(cx, cz, 0, -1);
		if (!free(f, 9, 7, 0)) return;
		reserve(cx - 6, cz - 6, cx + 6, cz + 6);
		flatten(cx - 5, cz - 4, cx + 5, cz + 4);
		String[] crops = {"minecraft:wheat[age=7]", "minecraft:carrots[age=7]", "minecraft:potatoes[age=7]", "minecraft:wheat[age=7]"};
		for (int lx = -4; lx <= 4; lx++) {
			for (int lz = -3; lz <= 3; lz++) {
				boolean border = Math.abs(lx) == 4 || Math.abs(lz) == 3;
				if (border) {
					put(f, lx, GY + 1, lz, "minecraft:oak_fence[east=false,north=false,south=false,west=false]");
					continue;
				}
				if (lx == 0) {
					put(f, lx, GY, lz, IslandGen.WATER);
					put(f, lx, GY - 1, lz, IslandGen.CLAY);
				} else {
					put(f, lx, GY, lz, "minecraft:farmland[moisture=7]");
					put(f, lx, GY + 1, lz, crops[Math.floorMod(Math.abs(lx) + lz, 4)]);
				}
			}
		}
		put(f, 0, GY + 1, 3, null); // gate
		put(f, 0, GY + 1, 3, "minecraft:oak_fence_gate[facing={F},in_wall=false,open=false,powered=false]");
		put(f, 0, GY + 1, -3, "minecraft:hay_block[axis=y]");
	}

	private void lampPosts() {
		int[][] spots = {{-14, 3}, {14, 3}, {-14, -3}, {14, -3}, {3, 14}, {-3, 14},
			{22, 3}, {32, 3}, {-22, 3}, {-32, 3}, {22, -3}, {-22, -3}, {3, 19}, {-3, 19}};
		for (int[] s : spots) {
			int t = top(s[0], s[1]);
			if (t < 0) continue;
			if (ig.reserved[ig.ix(s[0])][ig.ix(s[1])] && !road[ig.ix(s[0])][ig.ix(s[1])] && Math.hypot(s[0], s[1]) > 15) continue;
			for (int y = t + 1; y <= t + 3; y++) g.set(s[0], y, s[1], "minecraft:oak_fence[east=false,north=false,south=false,west=false]");
			g.set(s[0], t + 4, s[1], "minecraft:lantern[hanging=false]");
			ig.reserved[ig.ix(s[0])][ig.ix(s[1])] = true;
		}
	}
}
