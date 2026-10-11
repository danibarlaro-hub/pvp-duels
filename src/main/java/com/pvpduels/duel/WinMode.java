package com.pvpduels.duel;

/** How a duel is decided. The "limit" of a DuelConfig means rounds, points or minutes depending on the mode. */
public enum WinMode {
	/** A fixed number of rounds; most points wins (a tie plays extra rounds). limit 0 = no limit (ends with /leave). */
	ROUNDS,
	/** First player to reach N points wins. limit 0 = no limit (ends with /leave). */
	FIRST_TO,
	/** Plays until the time (minutes) runs out, finishes the running round; most points wins. */
	TIMED
}
