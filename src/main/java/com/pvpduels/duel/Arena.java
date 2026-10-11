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

	public static final int CENTER_Z = 0;

	/** Spawn point of player 0 (west side) or 1 (east side), facing each other. */
	public static double[] spawn(int slot, int index) {
		double x = centerX(slot) + (index == 0 ? -7.5 : 8.5);
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
		if (p.getY() < FALL_Y) return true;
		double dx = p.getX() - centerX(slot);
		double dz = p.getZ() - CENTER_Z;
		return dx * dx + dz * dz > (double) MAX_DISTANCE * MAX_DISTANCE;
	}

	/** True if this block position belongs to the arena of the given slot (for the build rules). */
	public static boolean inside(int slot, double x, double z) {
		return Math.abs(x - centerX(slot)) <= RADIUS + 1.5 && Math.abs(z - CENTER_Z) <= RADIUS + 1.5;
	}

	public static int slotAt(double x) {
		return Math.round((float) x / SPACING);
	}

	private static BlockState wanted(int dx, int dz, int y, boolean walls) {
		boolean floorArea = Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS;
		if (y == FLOOR_Y && floorArea) return Blocks.BEDROCK.defaultBlockState();
		boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == RADIUS + 1;
		if (walls && ring && y >= FLOOR_Y && y <= FLOOR_Y + WALL_HEIGHT) return Blocks.BEDROCK.defaultBlockState();
		return Blocks.AIR.defaultBlockState();
	}

	/**
	 * Puts the arena of this slot back to its clean state: removes every block that should not be there
	 * (shulkers, chests...), rebuilds the floor and walls, and deletes every non-player entity around it.
	 */
	public static void reset(ServerLevel level, int slot, boolean walls) {
		int cx = centerX(slot);
		int r = RADIUS + 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int y = FLOOR_Y; y <= FLOOR_Y + SCAN_HEIGHT; y++) {
					pos.set(cx + dx, y, CENTER_Z + dz);
					BlockState want = wanted(dx, dz, y, walls);
					BlockState have = level.getBlockState(pos);
					if (have != want) {
						level.setBlock(pos, want, FLAGS);
					}
				}
				// Anything dug below the floor
				pos.set(cx + dx, FLOOR_Y - 1, CENTER_Z + dz);
				if (!level.getBlockState(pos).isAir()) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
				}
			}
		}
		clearEntities(level, slot);
	}

	public static void clearEntities(ServerLevel level, int slot) {
		int cx = centerX(slot);
		AABB box = new AABB(cx - 60, FLOOR_Y - 60, CENTER_Z - 60, cx + 60, FLOOR_Y + SCAN_HEIGHT + 20, CENTER_Z + 60);
		List<Entity> found = level.getEntities((Entity) null, box, e -> !(e instanceof Player));
		for (Entity e : found) {
			e.discard();
		}
	}
}
