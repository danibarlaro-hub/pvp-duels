package com.pvpduels.duel;

/** The places a duel can be played in. */
public enum ArenaKind {
	/** 21x21 bedrock platform over the void. */
	FLAT,
	/** Flat normal terrain inside a ring of invisible barriers (radius 100, 150 or 200). */
	CIRCLE,
	/** Flat normal terrain without a border; you can't get more than 100 blocks away from your rival. */
	UNLIMITED,
	/** The practice arena of /botduel: a bedrock platform over the void, 100 blocks each way, with walls. */
	BOT,
	/** An arena a Builder made: it is pasted into the duel world before every round. */
	CUSTOM
}
