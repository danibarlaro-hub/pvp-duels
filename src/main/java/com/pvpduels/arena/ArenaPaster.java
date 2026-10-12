package com.pvpduels.arena;

import com.pvpduels.duel.Arena;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Copies an arena into the world and back. The box of an arena is 64x64x64 blocks around the center x of a "slot"
 * (see {@link Arena#centerX(int)}), z from -32 to 31, and starts {@link ArenaData#MIN_Y_OFFSET} blocks under the floor line.
 * Only blocks that differ are written, and sections that are plain air are skipped, so a reset is cheap.
 */
public final class ArenaPaster {
	private static final int FLAGS = 2 | 16; // update clients, no neighbour shape updates
	/** Lowest layer of the box in the world. */
	public static final int MIN_Y = Arena.FLOOR_Y + ArenaData.MIN_Y_OFFSET;
	public static final int HALF = ArenaData.SIZE / 2;
	/** Blocks left over this far above and below the box are cleaned too. */
	private static final int CLEAN_BELOW = 8;
	private static final int CLEAN_ABOVE = 140;

	private ArenaPaster() {}

	public static boolean inBox(int cx, double x, double y, double z) {
		return x >= cx - HALF && x < cx + HALF && z >= -HALF && z < HALF && y >= MIN_Y && y < MIN_Y + ArenaData.SIZE;
	}

	/**
	 * Makes the box (and the air around it) look like the arena; with null data everything becomes air.
	 * Returns how many blocks were changed.
	 */
	public static int apply(ServerLevel level, int cx, ArenaData data) {
		int x0 = cx - HALF;
		int z0 = -HALF;
		int yLow = MIN_Y - CLEAN_BELOW;
		int yHigh = MIN_Y + ArenaData.SIZE + CLEAN_ABOVE;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		for (int chunkX = x0 >> 4; chunkX <= (x0 + ArenaData.SIZE - 1) >> 4; chunkX++) {
			for (int chunkZ = z0 >> 4; chunkZ <= (z0 + ArenaData.SIZE - 1) >> 4; chunkZ++) {
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				LevelChunkSection[] sections = chunk.getSections();
				int minY = chunk.getMinY();
				int xa = Math.max(chunkX << 4, x0);
				int xb = Math.min((chunkX << 4) + 15, x0 + ArenaData.SIZE - 1);
				int za = Math.max(chunkZ << 4, z0);
				int zb = Math.min((chunkZ << 4) + 15, z0 + ArenaData.SIZE - 1);
				for (int index = 0; index < sections.length; index++) {
					int sectionY = minY + index * 16;
					if (sectionY + 15 < yLow || sectionY > yHigh) continue;
					LevelChunkSection section = sections[index];
					boolean touchesBox = data != null && sectionY + 15 >= MIN_Y && sectionY < MIN_Y + ArenaData.SIZE;
					if (!touchesBox && !section.maybeHas(s -> !s.isAir())) continue;
					for (int x = xa; x <= xb; x++) {
						for (int z = za; z <= zb; z++) {
							for (int ly = 0; ly < 16; ly++) {
								int y = sectionY + ly;
								if (y < yLow || y > yHigh) continue;
								BlockState want = air;
								if (data != null && y >= MIN_Y && y < MIN_Y + ArenaData.SIZE) want = data.blocks[ArenaData.index(x - x0, y - MIN_Y, z - z0)];
								if (section.getBlockState(x & 15, ly, z & 15) != want) {
									level.setBlock(pos.set(x, y, z), want, FLAGS);
									changed++;
								}
							}
						}
					}
				}
			}
		}
		Arena.clearEntitiesAround(level, cx, HALF);
		return changed;
	}

	/** Reads the box of the plot at this center x. */
	public static BlockState[] capture(ServerLevel level, int cx) {
		BlockState[] out = new BlockState[ArenaData.VOLUME];
		Arrays.fill(out, Blocks.AIR.defaultBlockState());
		int x0 = cx - HALF;
		int z0 = -HALF;
		for (int chunkX = x0 >> 4; chunkX <= (x0 + ArenaData.SIZE - 1) >> 4; chunkX++) {
			for (int chunkZ = z0 >> 4; chunkZ <= (z0 + ArenaData.SIZE - 1) >> 4; chunkZ++) {
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				LevelChunkSection[] sections = chunk.getSections();
				int minY = chunk.getMinY();
				int xa = Math.max(chunkX << 4, x0);
				int xb = Math.min((chunkX << 4) + 15, x0 + ArenaData.SIZE - 1);
				int za = Math.max(chunkZ << 4, z0);
				int zb = Math.min((chunkZ << 4) + 15, z0 + ArenaData.SIZE - 1);
				for (int index = 0; index < sections.length; index++) {
					int sectionY = minY + index * 16;
					if (sectionY + 15 < MIN_Y || sectionY >= MIN_Y + ArenaData.SIZE) continue;
					LevelChunkSection section = sections[index];
					if (!section.maybeHas(s -> !s.isAir())) continue;
					for (int x = xa; x <= xb; x++) {
						for (int z = za; z <= zb; z++) {
							for (int ly = 0; ly < 16; ly++) {
								int y = sectionY + ly;
								if (y < MIN_Y || y >= MIN_Y + ArenaData.SIZE) continue;
								out[ArenaData.index(x - x0, y - MIN_Y, z - z0)] = section.getBlockState(x & 15, ly, z & 15);
							}
						}
					}
				}
			}
		}
		return out;
	}
}
