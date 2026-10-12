package com.pvpduels.arena;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Locale;

/** The starting points offered by /createarena. */
public final class ArenaTemplates {
	public enum Kind {
		VOID("void", "Void", "Nothing but a small bedrock pad to stand on."),
		FLAT("flat", "Flat grass", "A grass field the size of the whole plot."),
		BOX("box", "Bedrock box", "A bedrock floor with bedrock walls, open at the top.");

		public final String id;
		public final String title;
		public final String description;

		Kind(String id, String title, String description) {
			this.id = id;
			this.title = title;
			this.description = description;
		}

		public static Kind byId(String id) {
			for (Kind k : values()) if (k.id.equals(id.toLowerCase(Locale.ROOT))) return k;
			return null;
		}
	}

	/** Where the floor is inside the box: layer index of the floor line. */
	private static final int FLOOR = -ArenaData.MIN_Y_OFFSET;
	private static final int WALL_HEIGHT = 24;

	private ArenaTemplates() {}

	public static BlockState[] build(Kind kind) {
		BlockState[] blocks = new BlockState[ArenaData.VOLUME];
		Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
		BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
		int size = ArenaData.SIZE;
		switch (kind) {
			case VOID -> {
				for (int x = size / 2 - 2; x <= size / 2 + 2; x++) {
					for (int z = size / 2 - 2; z <= size / 2 + 2; z++) blocks[ArenaData.index(x, FLOOR, z)] = bedrock;
				}
			}
			case FLAT -> {
				for (int x = 0; x < size; x++) {
					for (int z = 0; z < size; z++) {
						blocks[ArenaData.index(x, FLOOR, z)] = Blocks.GRASS_BLOCK.defaultBlockState();
						blocks[ArenaData.index(x, FLOOR - 1, z)] = Blocks.DIRT.defaultBlockState();
						blocks[ArenaData.index(x, FLOOR - 2, z)] = Blocks.DIRT.defaultBlockState();
						blocks[ArenaData.index(x, FLOOR - 3, z)] = bedrock;
					}
				}
			}
			case BOX -> {
				for (int x = 0; x < size; x++) {
					for (int z = 0; z < size; z++) {
						blocks[ArenaData.index(x, FLOOR, z)] = bedrock;
						boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
						if (edge) {
							for (int y = FLOOR + 1; y <= FLOOR + WALL_HEIGHT; y++) blocks[ArenaData.index(x, y, z)] = bedrock;
						}
					}
				}
			}
		}
		return blocks;
	}

	/** Spawn points a template starts with, so a builder can save right away: {x, y, z, yaw} relative to the plot center, facing each other. */
	public static double[][] defaultSpawns(Kind kind) {
		if (kind == Kind.VOID) return new double[][] {{-1.5, 1, 0.5, -90}, {2.5, 1, 0.5, 90}};
		return new double[][] {{-8.5, 1, 0.5, -90}, {9.5, 1, 0.5, 90}};
	}
}
