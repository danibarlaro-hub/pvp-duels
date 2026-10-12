package com.pvpduels.duel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "normal" arenas (circular and unlimited) live in the flat dimension pvpduels:normal_arena. Its terrain is
 * always the same stack of layers (bedrock at y-60, deepslate up to y-1, stone up to y65, 5 dirt blocks up to y70),
 * so the world generator makes it and this class only has to put things back the way they were:
 * <ul>
 *   <li>before every round every loaded chunk of the arena is compared with the layers and fixed;</li>
 *   <li>chunks that were saved to disk dirty are fixed when they load (unless they were unloaded during the same
 *       round, because then the changes are the ones the players made);</li>
 *   <li>the barrier ring of the circular arena is drawn in the same way, chunk by chunk, as they load.</li>
 * </ul>
 */
public final class Terrain {
	public static final ResourceKey<Level> DIMENSION =
		ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("pvpduels", "normal_arena"));

	public static final int BEDROCK_Y = -60;
	public static final int STONE_TOP_Y = 65;
	public static final int SURFACE_Y = 70;
	/** Barriers go up to the build limit of the overworld type. */
	public static final int MAX_Y = 319;
	/** Players stand on the dirt, so they appear one block above it. */
	public static final int SPAWN_Y = SURFACE_Y + 1;

	public static final int CIRCLE_SPACING = 1024;
	public static final int UNLIMITED_BASE = 1_000_000;
	public static final int UNLIMITED_SPACING = 1_000_000;

	private static final int FLAGS = 2 | 16; // update clients, no neighbour shape updates
	private static final int QUEUE_BUDGET_NANOS = 12_000_000;

	private Terrain() {}

	// ------------------------------------------------------------------ the layers

	private static BlockState air() {
		return Blocks.AIR.defaultBlockState();
	}

	/** What the terrain is made of at this height when nothing has been changed. */
	public static BlockState layer(int y) {
		if (y < BEDROCK_Y) return air();
		if (y == BEDROCK_Y) return Blocks.BEDROCK.defaultBlockState();
		if (y < 0) return Blocks.DEEPSLATE.defaultBlockState();
		if (y <= STONE_TOP_Y) return Blocks.STONE.defaultBlockState();
		if (y <= SURFACE_Y) return Blocks.DIRT.defaultBlockState();
		return air();
	}

	/** The single block a whole 16-high section should contain, or null when the section crosses a layer border. */
	private static BlockState uniform(int minY) {
		BlockState first = layer(minY);
		for (int dy = 1; dy < 16; dy++) {
			if (layer(minY + dy) != first) return null;
		}
		return first;
	}

	// ------------------------------------------------------------------ regions

	/** One arena that is being played in right now. */
	public static final class Region {
		public final ArenaKind kind;
		public final int cx;
		public final int cz;
		public final int radius;
		int minX;
		int maxX;
		int minZ;
		int maxZ;
		private final int margin;

		Region(ArenaKind kind, int cx, int cz, int radius) {
			this.kind = kind;
			this.cx = cx;
			this.cz = cz;
			this.radius = radius;
			int reach = kind == ArenaKind.CIRCLE ? radius + 2 : 48;
			this.minX = cx - reach;
			this.maxX = cx + reach;
			this.minZ = cz - reach;
			this.maxZ = cz + reach;
			// Unlimited arenas follow the players around, so chunks far from them can load too.
			this.margin = kind == ArenaKind.CIRCLE ? 16 : 16 * 34;
		}

		/** The unlimited arena grows with the players. */
		void include(double x, double z) {
			if (kind != ArenaKind.UNLIMITED) return;
			minX = Math.min(minX, (int) Math.floor(x));
			maxX = Math.max(maxX, (int) Math.floor(x));
			minZ = Math.min(minZ, (int) Math.floor(z));
			maxZ = Math.max(maxZ, (int) Math.floor(z));
		}

		boolean covers(int chunkX, int chunkZ) {
			int x0 = chunkX << 4;
			int z0 = chunkZ << 4;
			return x0 + 15 >= minX - margin && x0 <= maxX + margin && z0 + 15 >= minZ - margin && z0 <= maxZ + margin;
		}

		/** Barrier cells: the ring of the circular arena, 1.5 blocks thick so nothing slips through diagonally. */
		boolean isRing(int x, int z) {
			double dx = x - cx;
			double dz = z - cz;
			double d2 = dx * dx + dz * dz;
			double outer = radius + 1.5;
			return d2 >= (double) radius * radius && d2 < outer * outer;
		}

		boolean ringTouches(int chunkX, int chunkZ) {
			if (kind != ArenaKind.CIRCLE) return false;
			int x0 = chunkX << 4;
			int z0 = chunkZ << 4;
			double nearX = Math.max(x0, Math.min(cx, x0 + 15)) - cx;
			double nearZ = Math.max(z0, Math.min(cz, z0 + 15)) - cz;
			double farX = Math.max(Math.abs(x0 - cx), Math.abs(x0 + 15 - cx));
			double farZ = Math.max(Math.abs(z0 - cz), Math.abs(z0 + 15 - cz));
			double outer = radius + 1.5;
			return nearX * nearX + nearZ * nearZ < outer * outer && farX * farX + farZ * farZ >= (double) radius * radius;
		}
	}

	private static final List<Region> REGIONS = new ArrayList<>();
	/** Chunks that were unloaded during the current rounds: whatever is saved in them is what the players built. */
	private static final Set<Long> KEEP = new HashSet<>();
	private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
	private static final Set<Long> QUEUED = new HashSet<>();

	public static int centerX(ArenaKind kind, int slot) {
		return kind == ArenaKind.CIRCLE ? slot * CIRCLE_SPACING : UNLIMITED_BASE + slot * UNLIMITED_SPACING;
	}

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(DIMENSION);
	}

	private static Region regionFor(int chunkX, int chunkZ) {
		for (Region r : REGIONS) {
			if (r.covers(chunkX, chunkZ)) return r;
		}
		return null;
	}

	/** Number of arenas being maintained (used by the self-test). */
	public static int activeRegions() {
		return REGIONS.size();
	}

	public static int queued() {
		return QUEUE.size();
	}

	// ------------------------------------------------------------------ events

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
			if (!level.dimension().equals(DIMENSION) || REGIONS.isEmpty()) return;
			ChunkPos pos = chunk.getPos();
			long key = pos.pack();
			if (regionFor(pos.x(), pos.z()) == null || KEEP.contains(key)) return;
			if (QUEUED.add(key)) QUEUE.add(key);
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (!level.dimension().equals(DIMENSION) || REGIONS.isEmpty()) return;
			ChunkPos pos = chunk.getPos();
			if (regionFor(pos.x(), pos.z()) != null) KEEP.add(pos.pack());
		});
		ServerTickEvents.END_SERVER_TICK.register(Terrain::processQueue);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	private static void clear() {
		REGIONS.clear();
		KEEP.clear();
		QUEUE.clear();
		QUEUED.clear();
	}

	/** Fixes the chunks that loaded since the last tick, a few at a time. */
	private static void processQueue(MinecraftServer server) {
		if (QUEUE.isEmpty()) return;
		ServerLevel level = level(server);
		if (level == null) {
			QUEUE.clear();
			QUEUED.clear();
			return;
		}
		long end = System.nanoTime() + QUEUE_BUDGET_NANOS;
		while (!QUEUE.isEmpty() && System.nanoTime() < end) {
			long key = QUEUE.poll();
			QUEUED.remove(key);
			int x = ChunkPos.getX(key);
			int z = ChunkPos.getZ(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
			if (chunk == null) continue;
			Region region = regionFor(x, z);
			if (region != null) repair(level, chunk, region);
		}
	}

	// ------------------------------------------------------------------ arenas coming and going

	/** Starts maintaining an arena. Returns the region the duel keeps using. */
	public static Region open(ArenaKind kind, int slot, int radius) {
		Region region = new Region(kind, centerX(kind, slot), 0, radius);
		REGIONS.add(region);
		return region;
	}

	/** Puts every loaded chunk of the arena back to the clean terrain (and the ring), and sweeps the entities. */
	public static void reset(ServerLevel level, Region region) {
		KEEP.removeIf(key -> region.covers(ChunkPos.getX(key), ChunkPos.getZ(key)));
		repairLoaded(level, region, region);
		sweepEntities(level, region);
	}

	/** The duel is over: the ring goes away and the terrain is clean again. */
	public static void close(ServerLevel level, Region region) {
		REGIONS.remove(region);
		KEEP.removeIf(key -> region.covers(ChunkPos.getX(key), ChunkPos.getZ(key)));
		if (level != null) {
			repairLoaded(level, region, null);
			sweepEntities(level, region);
		}
	}

	/** Makes sure the area is tracked while players walk around in it. */
	public static void follow(Region region, double x, double z) {
		region.include(x, z);
	}

	private static void repairLoaded(ServerLevel level, Region bounds, Region apply) {
		int x0 = (bounds.minX - bounds.margin) >> 4;
		int x1 = (bounds.maxX + bounds.margin) >> 4;
		int z0 = (bounds.minZ - bounds.margin) >> 4;
		int z1 = (bounds.maxZ + bounds.margin) >> 4;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk != null) repair(level, chunk, apply);
			}
		}
	}

	private static void sweepEntities(ServerLevel level, Region region) {
		AABB box = new AABB(region.minX - 64, -80, region.minZ - 64, region.maxX + 64, 400, region.maxZ + 64);
		List<Entity> found = level.getEntities((Entity) null, box, e -> !(e instanceof Player));
		for (Entity e : found) e.discard();
	}

	// ------------------------------------------------------------------ repairing one chunk

	/** The block that belongs at this place. region is null when no ring should be there. */
	static BlockState wanted(Region region, int x, int y, int z) {
		if (region != null && y >= BEDROCK_Y && y <= MAX_Y && region.isRing(x, z)) {
			return Blocks.BARRIER.defaultBlockState();
		}
		return layer(y);
	}

	/**
	 * Compares the chunk with what it should be. Sections that hold a single block type and need nothing else are
	 * skipped after looking at their palette, so a clean chunk costs almost nothing.
	 * Returns the number of blocks that were changed.
	 */
	static int repair(ServerLevel level, LevelChunk chunk, Region region) {
		ChunkPos cp = chunk.getPos();
		boolean ring = region != null && region.ringTouches(cp.x(), cp.z());
		Region ringRegion = ring ? region : null;
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			int y0 = minY + index * 16;
			BlockState same = uniform(y0);
			if (same != null && !ring && !section.maybeHas(s -> s != same)) continue;
			for (int lx = 0; lx < 16; lx++) {
				int x = cp.getMinBlockX() + lx;
				for (int lz = 0; lz < 16; lz++) {
					int z = cp.getMinBlockZ() + lz;
					for (int ly = 0; ly < 16; ly++) {
						int y = y0 + ly;
						BlockState want = wanted(ringRegion, x, y, z);
						if (section.getBlockState(lx, ly, lz) != want) {
							pos.set(x, y, z);
							level.setBlock(pos, want, FLAGS);
							changed++;
						}
					}
				}
			}
		}
		return changed;
	}
}
