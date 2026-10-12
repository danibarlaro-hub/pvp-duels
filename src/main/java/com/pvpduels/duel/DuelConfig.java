package com.pvpduels.duel;

/** Everything chosen in the /duel menus. The kit belongs to kitOwner (the player who sent the challenge). */
public record DuelConfig(String kit, ArenaKind arena, boolean walls, int radius, WinMode mode, int limit, Rules rules) {

	/**
	 * Rules the challenger can switch off for one duel.
	 * regen: natural health regeneration. fallDamage: damage from falling. hunger: the food bar goes down.
	 * building: placing and breaking blocks in the circular and unlimited arenas (the flat arena has its own rules).
	 */
	public record Rules(boolean regen, boolean fallDamage, boolean hunger, boolean building) {
		public static final Rules DEFAULT = new Rules(true, true, true, true);

		public boolean isDefault() {
			return equals(DEFAULT);
		}

		/** Text for chat: only what was changed. */
		public String describe(ArenaKind arena) {
			java.util.List<String> parts = new java.util.ArrayList<>();
			if (!regen) parts.add("no natural regeneration");
			if (!fallDamage) parts.add("no fall damage");
			if (!hunger && regen) parts.add("no hunger loss");
			if (!building && (arena == ArenaKind.CIRCLE || arena == ArenaKind.UNLIMITED)) parts.add("no building");
			if (parts.isEmpty()) return "default rules";
			String text = String.join(", ", parts);
			return Character.toUpperCase(text.charAt(0)) + text.substring(1);
		}
	}

	public DuelConfig {
		if (rules == null) rules = Rules.DEFAULT;
	}

	/** Same as above with the default rules. */
	public DuelConfig(String kit, ArenaKind arena, boolean walls, int radius, WinMode mode, int limit) {
		this(kit, arena, walls, radius, mode, limit, Rules.DEFAULT);
	}

	/** The flat void arena (the first arena). */
	public DuelConfig(String kit, boolean walls, WinMode mode, int limit) {
		this(kit, ArenaKind.FLAT, walls, 0, mode, limit, Rules.DEFAULT);
	}

	public String arenaName() {
		return switch (arena) {
			case FLAT -> walls ? "Flat arena (with walls)" : "Flat arena (no walls)";
			case CIRCLE -> "Circular arena (radius " + radius + ")";
			case UNLIMITED -> "Unlimited arena";
			case BOT -> "Bot arena (radius " + Arena.BOT_RADIUS + ")";
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
