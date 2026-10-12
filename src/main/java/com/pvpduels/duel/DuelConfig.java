package com.pvpduels.duel;

/** Everything chosen in the /duel menus. The kit belongs to kitOwner (the player who sent the challenge). */
public record DuelConfig(String kit, ArenaKind arena, boolean walls, int radius, WinMode mode, int limit) {

	/** The flat void arena (the first arena). */
	public DuelConfig(String kit, boolean walls, WinMode mode, int limit) {
		this(kit, ArenaKind.FLAT, walls, 0, mode, limit);
	}

	public String arenaName() {
		return switch (arena) {
			case FLAT -> walls ? "Flat arena (with walls)" : "Flat arena (no walls)";
			case CIRCLE -> "Circular arena (radius " + radius + ")";
			case UNLIMITED -> "Unlimited arena";
		};
	}

	public String modeName() {
		return switch (mode) {
			case ROUNDS -> limit == 0 ? "Unlimited rounds" : limit + (limit == 1 ? " round" : " rounds");
			case FIRST_TO -> limit == 0 ? "Unlimited points" : "First to " + limit + (limit == 1 ? " point" : " points");
			case TIMED -> limit + " minutes, most points wins";
		};
	}

	public boolean unlimited() {
		return mode != WinMode.TIMED && limit == 0;
	}
}
