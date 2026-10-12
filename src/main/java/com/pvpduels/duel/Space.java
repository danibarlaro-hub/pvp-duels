package com.pvpduels.duel;

import com.pvpduels.arena.ArenaData;
import com.pvpduels.arena.ArenaPaster;
import com.pvpduels.arena.ArenaStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Set;

/** The place one duel is played in: knows its dimension, spawn points, how to clean it and when you left it. */
abstract class Space {
	static Space of(DuelConfig config, int slot) {
		return switch (config.arena()) {
			case FLAT -> new Flat(slot, config.walls(), false);
			case BOT -> new Flat(slot, true, true);
			case CIRCLE, UNLIMITED -> new Normal(config.arena(), slot, config.radius());
			case CUSTOM -> new Custom(slot, config.custom());
		};
	}

	abstract ResourceKey<Level> dimension();

	/** x, y, z and yaw of the spawn point of player 0 or 1. */
	abstract double[] spawn(int index);

	/** Center of the arena (the unlimited rule measures from here). */
	abstract double centerX();

	abstract double centerZ();

	/** Cleans the arena for a round (also the first one). */
	abstract void begin(ServerLevel level);

	/** The duel is over: nothing has to be kept in order any more. */
	abstract void end(ServerLevel level);

	/** Null while the player is inside, otherwise how they lost the round ("fell into the void."). */
	abstract String outOfBounds(ServerPlayer p);

	/** Called every tick while fighting. */
	void follow(ServerPlayer a, ServerPlayer b) {}

	final ServerLevel level(MinecraftServer server) {
		return server.getLevel(dimension());
	}

	final void teleportToSpawn(ServerPlayer player, ServerLevel level, int index) {
		double[] s = spawn(index);
		player.teleportTo(level, s[0], s[1], s[2], Set.of(), (float) s[3], 0f, true);
		player.setDeltaMovement(0, 0, 0);
		player.resetFallDistance();
	}

	// ------------------------------------------------------------------ the flat void arena

	private static final class Flat extends Space {
		private final int slot;
		private final boolean walls;
		/** The big practice arena of /botduel instead of the small 21x21 one. */
		private final boolean practice;

		Flat(int slot, boolean walls, boolean practice) {
			this.slot = slot;
			this.walls = walls;
			this.practice = practice;
		}

		private int cx() { return practice ? Arena.botCenterX(slot) : Arena.centerX(slot); }
		private int radius() { return practice ? Arena.BOT_RADIUS : Arena.RADIUS; }

		@Override ResourceKey<Level> dimension() { return Arena.DIMENSION; }
		@Override double[] spawn(int index) { return Arena.spawnAt(cx(), index); }
		@Override double centerX() { return cx(); }
		@Override double centerZ() { return Arena.CENTER_Z; }
		@Override void begin(ServerLevel level) { Arena.reset(level, cx(), radius(), walls); }
		@Override void end(ServerLevel level) { if (level != null) Arena.clearEntitiesAround(level, cx(), radius()); }

		@Override
		String outOfBounds(ServerPlayer p) {
			return Arena.fellOut(p, cx(), radius()) ? "fell into the void." : null;
		}
	}

	// ------------------------------------------------------------------ circular and unlimited arenas

	private static final class Normal extends Space {
		private static final int SPAWN_OFFSET = 16;

		private final ArenaKind kind;
		private final int slot;
		private final int radius;
		private Terrain.Region region;

		Normal(ArenaKind kind, int slot, int radius) {
			this.kind = kind;
			this.slot = slot;
			this.radius = radius;
		}

		@Override ResourceKey<Level> dimension() { return Terrain.DIMENSION; }
		@Override double centerX() { return Terrain.centerX(kind, slot); }
		@Override double centerZ() { return 0; }

		@Override
		double[] spawn(int index) {
			double x = centerX() + (index == 0 ? -SPAWN_OFFSET + 0.5 : SPAWN_OFFSET + 0.5);
			return new double[] {x, Terrain.SPAWN_Y, 0.5, index == 0 ? -90f : 90f};
		}

		@Override
		void begin(ServerLevel level) {
			if (region == null) region = Terrain.open(kind, slot, radius);
			Terrain.reset(level, region);
		}

		@Override
		void end(ServerLevel level) {
			if (region != null) Terrain.close(level, region);
			region = null;
		}

		@Override
		String outOfBounds(ServerPlayer p) {
			if (p.getY() < Terrain.BEDROCK_Y - 3) return "fell out of the world.";
			if (kind == ArenaKind.CIRCLE) {
				double dx = p.getX() - centerX();
				double dz = p.getZ() - centerZ();
				double limit = radius + 0.5;
				if (dx * dx + dz * dz > limit * limit) return "left the arena.";
			}
			return null;
		}

		@Override
		void follow(ServerPlayer a, ServerPlayer b) {
			if (region == null) return;
			Terrain.follow(region, a.getX(), a.getZ());
			Terrain.follow(region, b.getX(), b.getZ());
		}
	}

	// ------------------------------------------------------------------ an arena a Builder made

	private static final class Custom extends Space {
		private final int slot;
		private final String key;
		private ArenaData data;

		Custom(int slot, String key) {
			this.slot = slot;
			this.key = key;
		}

		private ArenaData data() {
			if (data == null) {
				try {
					data = ArenaStore.load(key).orElseThrow(() -> new IllegalStateException("The arena '" + key + "' no longer exists."));
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}
			return data;
		}

		private int cx() {
			return Arena.centerX(slot);
		}

		@Override ResourceKey<Level> dimension() { return Arena.DIMENSION; }
		@Override double centerX() { return cx(); }
		@Override double centerZ() { return 0; }

		@Override
		double[] spawn(int index) {
			double[] s = index == 0 ? data().spawn0 : data().spawn1;
			if (s == null) s = new double[] {index == 0 ? -8.5 : 9.5, 1, 0.5, index == 0 ? -90 : 90};
			return new double[] {cx() + s[0], Arena.FLOOR_Y + s[1], s[2], s[3]};
		}

		@Override void begin(ServerLevel level) { ArenaPaster.apply(level, cx(), data()); }

		@Override
		void end(ServerLevel level) {
			if (level != null) ArenaPaster.apply(level, cx(), null);
		}

		@Override
		String outOfBounds(ServerPlayer p) {
			if (p.getY() < ArenaPaster.MIN_Y - 8) return "fell into the void.";
			double reach = ArenaPaster.HALF + 24;
			if (Math.abs(p.getX() - cx()) > reach || Math.abs(p.getZ()) > reach) return "left the arena.";
			return null;
		}
	}
}
