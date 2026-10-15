package com.pvpduels.duel;

/** Everything chosen in the /duel menus. The kit belongs to kitOwner (the player who sent the challenge). */
public record DuelConfig(String kit, ArenaKind arena, boolean walls, int radius, WinMode mode, int limit, Rules rules, String custom) {

	/**
	 * Rules the challenger can switch off for one duel.
	 * regen: natural health regeneration. fallDamage: damage from falling. hunger: the food bar goes down.
	 * building: placing and breaking blocks in the circular and unlimited arenas (the flat arena has its own rules).
	 * mutator: a twist both duelists get in every round (see Mutator).
	 * swapKit: the name of a second kit of the challenger ("" = no swap). The challenger starts with the kit of the duel and the
	 * rival with this one, and they trade them at the start of every round.
	 */
	public record Rules(boolean regen, boolean fallDamage, boolean hunger, boolean building, Mutator mutator, String swapKit) {
		public static final Rules DEFAULT = new Rules(true, true, true, true, Mutator.NONE, "");

		public Rules {
			if (mutator == null) mutator = Mutator.NONE;
			if (swapKit == null) swapKit = "";
		}

		/** Without a mutator and without a kit swap. */
		public Rules(boolean regen, boolean fallDamage, boolean hunger, boolean building) {
			this(regen, fallDamage, hunger, building, Mutator.NONE, "");
		}

		/** With a mutator, without a kit swap. */
		public Rules(boolean regen, boolean fallDamage, boolean hunger, boolean building, Mutator mutator) {
			this(regen, fallDamage, hunger, building, mutator, "");
		}

		/** With a kit swap, without a mutator. */
		public Rules(boolean regen, boolean fallDamage, boolean hunger, boolean building, String swapKit) {
			this(regen, fallDamage, hunger, building, Mutator.NONE, swapKit);
		}

		public boolean swaps() {
			return !swapKit.isEmpty();
		}

		public boolean isDefault() {
			return equals(DEFAULT);
		}

		/** Text for chat: only what was changed. */
		public String describe(ArenaKind arena) {
			java.util.List<String> parts = new java.util.ArrayList<>();
			if (!regen) parts.add("no natural regeneration");
			if (!fallDamage) parts.add("no fall damage");
			if (!hunger && regen) parts.add("no hunger loss");
			if (!building && (arena == ArenaKind.CIRCLE || arena == ArenaKind.UNLIMITED || arena == ArenaKind.CUSTOM)) parts.add("no building");
			if (mutator == Mutator.RANDOM) parts.add("a random mutator every round");
			else if (mutator != Mutator.NONE) parts.add(mutator.label().toLowerCase() + " mutator");
			if (swaps()) parts.add("kit swap with '" + swapKit + "'");
			if (parts.isEmpty()) return "default rules";
			String text = String.join(", ", parts);
			return Character.toUpperCase(text.charAt(0)) + text.substring(1);
		}
	}

	public DuelConfig {
		if (rules == null) rules = Rules.DEFAULT;
		if (custom == null) custom = "";
	}

	/** Without a custom arena. */
	public DuelConfig(String kit, ArenaKind arena, boolean walls, int radius, WinMode mode, int limit, Rules rules) {
		this(kit, arena, walls, radius, mode, limit, rules, "");
	}

	/** In a custom arena: key is "player id/name" or "public/name" (see ArenaStore). */
	public static DuelConfig inCustomArena(String kit, String key, WinMode mode, int limit, Rules rules) {
		return new DuelConfig(kit, ArenaKind.CUSTOM, false, 0, mode, limit, rules, key);
	}

	/** The name of the custom arena (the part of the key after the slash), or "". */
	public String customName() {
		int slash = custom.indexOf('/');
		return slash < 0 ? custom : custom.substring(slash + 1);
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
			case CUSTOM -> "Custom arena '" + customName() + "'";
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
