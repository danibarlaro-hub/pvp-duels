package com.pvpduels.duel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;

/**
 * The flat void arena: a 21x21 bedrock floor floating over the void in its own dimension (pvpduels:void_arena),
 * optionally with bedrock walls. Several arenas live side by side in the same dimension, one per "slot".
 */
public final class Arena {
	public static final ResourceKey<Level> DIMENSION =
		ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("pvpduels", "void_arena"));

	public static final int FLOOR_Y = 100;
	/** The floor reaches this many blocks from the center in every direction (21x21 total). */
	public static final int RADIUS = 10;
	/** The practice arena (/botduel) is much bigger: 100 blocks from the center in every direction (201x201). */
	public static final int BOT_RADIUS = 100;
	/** Practice arenas live in their own part of the dimension, so a slot can switch between small and big arenas. */
	private static final int BOT_BASE = 4096;
	public static final int WALL_HEIGHT = 40;
	/** The scan for leftover blocks goes this high above the floor. */
	private static final int SCAN_HEIGHT = 200;
	private static final int SPACING = 256;
	/** Falling below this height, or flying farther than this from the center, counts as falling into the void. */
	public static final int FALL_Y = FLOOR_Y - 12;
	public static final int MAX_DISTANCE = 45;

	private static final int FLAGS = 2 | 16; // update clients, no neighbour shape updates

	private Arena() {}

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(DIMENSION);
	}

	public static int centerX(int slot) {
		return slot * SPACING;
	}

	public static int botCenterX(int slot) {
		return BOT_BASE + slot * SPACING;
	}

	public static final int CENTER_Z = 0;

	/** Spawn point of player 0 (west side) or 1 (east side), facing each other. */
	public static double[] spawn(int slot, int index) {
		return spawnAt(centerX(slot), index);
	}

	public static double[] spawnAt(int cx, int index) {
		double x = cx + (index == 0 ? -7.5 : 8.5);
		double z = CENTER_Z + 0.5;
		float yaw = index == 0 ? -90f : 90f;
		return new double[] {x, FLOOR_Y + 1, z, yaw};
	}

	public static void teleportToSpawn(ServerPlayer player, ServerLevel level, int slot, int index) {
		double[] s = spawn(slot, index);
		player.teleportTo(level, s[0], s[1], s[2], Set.of(), (float) s[3], 0f, true);
		player.setDeltaMovement(0, 0, 0);
		player.resetFallDistance();
	}

	/** Teleports "who" right next to "target" (used by the 5-minute no-contact rule). */
	public static void teleportNextTo(ServerPlayer who, ServerPlayer target) {
		who.teleportTo((ServerLevel) target.level(), target.getX() + 1.5, target.getY(), target.getZ(), Set.of(), who.getYRot(), 0f, true);
		who.resetFallDistance();
	}

	public static boolean fellOut(ServerPlayer p, int slot) {
		return fellOut(p, centerX(slot), RADIUS);
	}

	public static boolean fellOut(ServerPlayer p, int cx, int radius) {
		if (p.getY() < FALL_Y) return true;
		double dx = p.getX() - cx;
		double dz = p.getZ() - CENTER_Z;
		// 45 blocks for the small arena (the old rule); big arenas get more room for their corners.
		double max = radius * 1.5 + (MAX_DISTANCE - RADIUS * 1.5);
		return dx * dx + dz * dz > max * max;
	}

	/** True if this block position belongs to the arena of the given slot (for the build rules). */
	public static boolean inside(int slot, double x, double z) {
		return Math.abs(x - centerX(slot)) <= RADIUS + 1.5 && Math.abs(z - CENTER_Z) <= RADIUS + 1.5;
	}

	public static int slotAt(double x) {
		return Math.round((float) x / SPACING);
	}

	private static BlockState wanted(int dx, int dz, int y, boolean walls, int radius) {
		boolean floorArea = Math.abs(dx) <= radius && Math.abs(dz) <= radius;
		if (y == FLOOR_Y && floorArea) return Blocks.BEDROCK.defaultBlockState();
		boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == radius + 1;
		if (walls && ring && y >= FLOOR_Y && y <= FLOOR_Y + WALL_HEIGHT) return Blocks.BEDROCK.defaultBlockState();
		return Blocks.AIR.defaultBlockState();
	}

	/**
	 * Puts the arena of this slot back to its clean state: removes every block that should not be there
	 * (shulkers, chests...), rebuilds the floor and walls, and deletes every non-player entity around it.
	 */
	public static void reset(ServerLevel level, int slot, boolean walls) {
		reset(level, centerX(slot), RADIUS, walls);
	}

	/**
	 * The same for an arena of any size. It walks the chunks section by section: sections above the walls that hold
	 * nothing but air are skipped after one look at their palette, so even the 201x201 practice arena is cheap
	 * once it has been built.
	 */
	public static void reset(ServerLevel level, int cx, int radius, boolean walls) {
		long started = System.nanoTime();
		int r = radius + 1;
		int yLow = FLOOR_Y - 1; // anything dug below the floor
		int yHigh = FLOOR_Y + SCAN_HEIGHT;
		int bandTop = FLOOR_Y + WALL_HEIGHT; // the floor and the walls are somewhere in here
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
			for (int chunkZ = (CENTER_Z - r) >> 4; chunkZ <= (CENTER_Z + r) >> 4; chunkZ++) {
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				LevelChunkSection[] sections = chunk.getSections();
				int minY = chunk.getMinY();
				int x0 = Math.max(chunkX << 4, cx - r);
				int x1 = Math.min((chunkX << 4) + 15, cx + r);
				int z0 = Math.max(chunkZ << 4, CENTER_Z - r);
				int z1 = Math.min((chunkZ << 4) + 15, CENTER_Z + r);
				for (int index = 0; index < sections.length; index++) {
					int sectionY = minY + index * 16;
					if (sectionY + 15 < yLow || sectionY > yHigh) continue;
					LevelChunkSection section = sections[index];
					if (sectionY > bandTop && !section.maybeHas(s -> !s.isAir())) continue;
					for (int x = x0; x <= x1; x++) {
						int dx = x - cx;
						for (int z = z0; z <= z1; z++) {
							int dz = z - CENTER_Z;
							for (int ly = 0; ly < 16; ly++) {
								int y = sectionY + ly;
								if (y < yLow || y > yHigh) continue;
								BlockState want = wanted(dx, dz, y, walls, radius);
								if (section.getBlockState(x & 15, ly, z & 15) != want) {
									pos.set(x, y, z);
									level.setBlock(pos, want, FLAGS);
									changed++;
								}
							}
						}
					}
				}
			}
		}
		clearEntitiesAround(level, cx, radius);
		if (radius > RADIUS) {
			com.pvpduels.PvpDuelsMod.LOGGER.info("Practice arena at x={} (radius {}) reset: {} blocks changed in {} ms",
				cx, radius, changed, (System.nanoTime() - started) / 1_000_000);
		}
	}

	public static void clearEntities(ServerLevel level, int slot) {
		clearEntitiesAround(level, centerX(slot));
	}

	/** Deletes every non-player entity near this x coordinate (items dropped, crystals, arrows...). */
	public static void clearEntitiesAround(ServerLevel level, int cx) {
		clearEntitiesAround(level, cx, RADIUS);
	}

	public static void clearEntitiesAround(ServerLevel level, int cx, int radius) {
		int reach = radius + 50;
		AABB box = new AABB(cx - reach, FLOOR_Y - 60, CENTER_Z - reach, cx + reach, FLOOR_Y + SCAN_HEIGHT + 20, CENTER_Z + reach);
		List<Entity> found = level.getEntities((Entity) null, box, e -> !(e instanceof Player));
		for (Entity e : found) {
			e.discard();
		}
	}
}
