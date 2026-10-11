package com.pvpduels.duel;

/** Everything chosen in the /duel menus. The kit belongs to kitOwner (the player who sent the challenge). */
public record DuelConfig(String kit, boolean walls, WinMode mode, int limit) {

	public String arenaName() {
		return walls ? "Flat arena (with walls)" : "Flat arena (no walls)";
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
