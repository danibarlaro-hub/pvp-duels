package com.pvpduels.worldgen;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.Lobby;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Builds the lobby island into the overworld of a world. It only runs when the environment variable PVPDUELS_BUILDWORLD
 * is set (never in the game): GitHub starts a server on a fresh void world, this class places the island, sets the
 * lobby and its protection, waits for the light and the waterfall to settle, saves and stops the server.
 */
public final class IslandBuilder {
	/** Where the village grass ends up in the world. */
	static final int GRASS_Y = 100;
	private static final int FLAGS = 2 | 16; // update clients, no neighbour shape updates
	private static final int SETTLE_TICKS = 1500;

	private static MinecraftServer server;
	private static int settle = -1;
	private static final List<int[]> FORCED = new ArrayList<>();
	private static long startNanos;

	private IslandBuilder() {}

	public static void register() {
		String mode = System.getenv("PVPDUELS_BUILDWORLD");
		if (mode == null || mode.isEmpty()) return;
		PvpDuelsMod.LOGGER.warn("BUILDWORLD mode is on (this must never happen in the game).");
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			try {
				build();
			} catch (Throwable t) {
				PvpDuelsMod.LOGGER.error("The island could not be built", t);
				report("RESULT: FAILED (" + t + ")");
				s.halt(false);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(IslandBuilder::tick);
	}

	// ------------------------------------------------------------------ the report

	private static final StringBuilder REPORT = new StringBuilder();
	private static boolean failed;

	private static void note(String line) {
		PvpDuelsMod.LOGGER.info("[worldgen] {}", line);
		REPORT.append(line).append('\n');
	}

	private static void fail(String line) {
		failed = true;
		PvpDuelsMod.LOGGER.error("[worldgen] {}", line);
		REPORT.append("PROBLEM: ").append(line).append('\n');
	}

	private static void report(String result) {
		try {
			REPORT.append(result).append('\n');
			Files.writeString(Path.of("worldgen-report.txt"), REPORT.toString());
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.error("Could not write the report", e);
		}
	}

	// ------------------------------------------------------------------ building

	private static void build() throws Exception {
		startNanos = System.nanoTime();
		ServerLevel level = server.overworld();
		note("Building the island into " + level.dimension().identifier());
		for (int y : new int[] {-60, 0, 64, 100, 200}) {
			BlockState there = level.getBlockState(new BlockPos(0, y, 0));
			if (!there.isAir()) fail("the world is not a void world: " + there + " at 0," + y + ",0");
		}
		if (failed) {
			report("RESULT: FAILED");
			server.halt(false);
			return;
		}

		long seed = 1234L;
		String envSeed = System.getenv("PVPDUELS_BUILDSEED");
		if (envSeed != null && !envSeed.isEmpty()) seed = Long.parseLong(envSeed.trim());
		IslandGen gen = new IslandGen(seed).build();
		BlockGrid grid = gen.grid;
		note("Island generated: " + grid.count() + " blocks, " + grid.paletteSize() + " kinds, seed " + seed);

		// keep every chunk of the island loaded while it is built and while the water falls
		int cmin = Math.floorDiv(grid.minX, 16), cmax = Math.floorDiv(grid.minX + grid.sizeX - 1, 16);
		int zmin = Math.floorDiv(grid.minZ, 16), zmax = Math.floorDiv(grid.minZ + grid.sizeZ - 1, 16);
		for (int cx = cmin; cx <= cmax; cx++) {
			for (int cz = zmin; cz <= zmax; cz++) {
				level.setChunkForced(cx, cz, true);
				FORCED.add(new int[] {cx, cz});
			}
		}

		TreeSet<String> unknown = new TreeSet<>();
		TreeSet<String> badProps = new TreeSet<>();
		BlockState[] states = new BlockState[grid.paletteSize()];
		for (int i = 1; i < states.length; i++) states[i] = parse(grid.paletteAt(i), unknown, badProps);
		if (!unknown.isEmpty()) fail("unknown blocks: " + unknown);
		if (!badProps.isEmpty()) fail("unknown block properties: " + badProps);

		int dy = GRASS_Y - IslandGen.GY;
		long placed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = grid.minY; y < grid.minY + grid.sizeY; y++) {
			for (int z = grid.minZ; z < grid.minZ + grid.sizeZ; z++) {
				for (int x = grid.minX; x < grid.minX + grid.sizeX; x++) {
					int raw = grid.raw(x, y, z);
					if (raw == 0) continue;
					pos.set(x, y + dy, z);
					level.setBlock(pos, states[raw], FLAGS);
					placed++;
				}
			}
		}
		note("Placed " + placed + " blocks in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");

		// wake up the water of the stream so that it pours over the edge
		int woke = 0;
		for (int y = grid.minY; y < grid.minY + grid.sizeY; y++) {
			for (int z = grid.minZ; z < grid.minZ + grid.sizeZ; z++) {
				for (int x = grid.minX; x < grid.minX + grid.sizeX; x++) {
					if (!grid.is(x, y, z, IslandGen.WATER)) continue;
					boolean edge = false;
					for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						if (grid.isAir(x + d[0], y, z + d[1])) edge = true;
					}
					if (!edge) continue;
					pos.set(x, y + dy, z);
					BlockState water = level.getBlockState(pos);
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
					level.setBlock(pos, water, 3);
					woke++;
				}
			}
		}
		note("Woke " + woke + " water blocks");

		// the lobby is on the plaza, looking at the sign; everybody who joins appears there
		double lx = gen.spawnX + 0.5, ly = gen.spawnY + dy, lz = gen.spawnZ + 0.5;
		Lobby.setAt(level.dimension().identifier().toString(), lx, ly, lz, 180f, 0f);
		Lobby.setProtectRadius(105);
		note("Lobby at " + lx + " " + ly + " " + lz + ", protected within 105 blocks");

		for (String command : new String[] {
			"setworldspawn " + (int) Math.floor(lx) + " " + (int) Math.floor(ly) + " " + (int) Math.floor(lz) + " 180 0",
			"time set 6000", "weather clear",
			// the names of the rules changed between versions: the one that does not exist is just refused
			"gamerule spawn_radius 0", "gamerule spawnRadius 0",
			"gamerule advance_time false", "gamerule doDaylightCycle false",
			"gamerule advance_weather false", "gamerule doWeatherCycle false",
			"gamerule spawn_mobs false", "gamerule doMobSpawning false",
		}) {
			try {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			} catch (Exception e) {
				note("command '" + command + "' failed: " + e);
			}
		}

		Map<String, Integer> census = grid.census();
		census.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()).limit(30)
			.forEach(e -> note("  " + e.getKey() + " " + e.getValue()));

		settle = 0;
		note("Waiting " + SETTLE_TICKS + " ticks for the light and the water");
	}

	private static void tick(MinecraftServer s) {
		if (settle < 0) return;
		settle++;
		if (settle % 300 == 0) note("tick " + settle);
		if (settle < SETTLE_TICKS) return;
		settle = -1;
		try {
			ServerLevel level = s.overworld();
			for (int[] c : FORCED) level.setChunkForced(c[0], c[1], false);
			s.saveEverything(false, true, true);
			note("Saved after " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
		} catch (Throwable t) {
			fail("saving failed: " + t);
		}
		report(failed ? "RESULT: FAILED" : "RESULT: OK");
		s.halt(false);
	}

	// ------------------------------------------------------------------ text -> block state

	private static BlockState parse(String text, TreeSet<String> unknown, TreeSet<String> badProps) {
		int br = text.indexOf('[');
		String id = br < 0 ? text : text.substring(0, br);
		Identifier ident = Identifier.parse(id);
		if (!BuiltInRegistries.BLOCK.containsKey(ident)) {
			unknown.add(id);
			return Blocks.AIR.defaultBlockState();
		}
		Block block = BuiltInRegistries.BLOCK.getValue(ident);
		BlockState state = block.defaultBlockState();
		if (br >= 0) {
			String inside = text.substring(br + 1, text.lastIndexOf(']'));
			for (String pair : inside.split(",")) {
				int eq = pair.indexOf('=');
				if (eq < 0) continue;
				String key = pair.substring(0, eq).trim();
				String value = pair.substring(eq + 1).trim();
				Property<?> property = block.getStateDefinition().getProperty(key);
				if (property == null) {
					badProps.add(id + "[" + key + "]");
					continue;
				}
				state = with(state, property, value, id, badProps);
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value, String id, TreeSet<String> badProps) {
		Optional<T> parsed = property.getValue(value);
		if (parsed.isEmpty()) {
			badProps.add(id + "[" + property.getName() + "=" + value + "]");
			return state;
		}
		return state.setValue(property, parsed.get());
	}
}
