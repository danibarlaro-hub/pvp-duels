package com.pvpduels.effects;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The twelve newest explosions, all built the same way: a lead-up, a blast on one tick, and an aftermath. Each one has a short version
 * (what everybody sees when the finale has no film: the quick explosion) and a long one (the film of a 1v1 finale and the Explosion Dummy).
 * This class only knows their names, their length and how the camera should treat them; the drawing is done by {@link EarthWonders}
 * and {@link SkyWonders}.
 */
public final class Wonders {
	/** How the camera moves in the film: around the target, low and looking up, wide and high, or rising on a crane. */
	public enum Cam { ORBIT, LOW, WIDE, CRANE }

	/** Draws tick {@code t} of one explosion. */
	@FunctionalInterface
	public interface Show {
		void play(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema);
	}

	/**
	 * One explosion.
	 *
	 * @param id        the id that is stored for the people who own it
	 * @param title     the big title of its film
	 * @param focusPre  how high above the target's feet the camera looks before the blast
	 * @param focusPost how high it looks after the blast
	 */
	public record Wonder(String id, String name, String title, ChatFormatting color, Cam cam, double focusPre, double focusPost, int impactQuick, int impactCinema,
		int endQuick, int endCinema, Show show) {
		/** The tick of the blast. */
		public int impact(boolean cinema) {
			return cinema ? impactCinema : impactQuick;
		}

		/** The tick when everything is over. */
		public int ticks(boolean cinema) {
			return cinema ? endCinema : endQuick;
		}

		public void play(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, int t, boolean cinema) {
			if (t >= 0 && t < ticks(cinema)) show.play(level, viewers, props, target, t, cinema);
		}
	}

	private static final Map<String, Wonder> ALL = new LinkedHashMap<>();

	private static void add(String id, String name, String title, ChatFormatting color, Cam cam, double focusPre, double focusPost, int impactQuick, int impactCinema,
		int endQuick, int endCinema, Show show) {
		ALL.put(id, new Wonder(id, name, title, color, cam, focusPre, focusPost, impactQuick, impactCinema, endQuick, endCinema, show));
	}

	static {
		add("fire_pillars", "Pillars of Fire", "PILLARS OF FIRE", ChatFormatting.GOLD, Cam.LOW, 9, 12, 52, 100, 100, 176, EarthWonders::firePillars);
		add("tornado", "Tornado", "TORNADO", ChatFormatting.WHITE, Cam.LOW, 10, 5, 56, 108, 104, 180, EarthWonders::tornado);
		add("crystal_burst", "Crystal Burst", "CRYSTAL BURST", ChatFormatting.LIGHT_PURPLE, Cam.ORBIT, 3.2, 5, 40, 76, 96, 164, EarthWonders::crystalBurst);
		add("rift_storm", "Rift Storm", "RIFT STORM", ChatFormatting.DARK_PURPLE, Cam.LOW, 9, 3, 50, 96, 104, 180, SkyWonders::riftStorm);
		add("dragon_strike", "Dragon Strike", "DRAGON STRIKE", ChatFormatting.DARK_PURPLE, Cam.WIDE, 14, 3, 58, 110, 104, 180, SkyWonders::dragonStrike);
		add("titan_fist", "Titan Fist", "TITAN FIST", ChatFormatting.GRAY, Cam.ORBIT, 18, 3, 40, 80, 100, 170, EarthWonders::titanFist);
		add("soul_reaper", "Soul Reaper", "SOUL REAPER", ChatFormatting.AQUA, Cam.WIDE, 8, 4, 52, 100, 100, 176, SkyWonders::soulReaper);
		add("solar_flare", "Solar Flare", "SOLAR FLARE", ChatFormatting.YELLOW, Cam.CRANE, 14, 4, 56, 110, 104, 184, SkyWonders::solarFlare);
		add("supernova", "Supernova", "SUPERNOVA", ChatFormatting.WHITE, Cam.LOW, 15, 8, 56, 108, 108, 190, SkyWonders::supernova);
		add("prism_beams", "Prism Beams", "PRISM BEAMS", ChatFormatting.GREEN, Cam.ORBIT, 5, 8, 56, 108, 104, 180, SkyWonders::prismBeams);
		add("spore_bloom", "Spore Bloom", "SPORE BLOOM", ChatFormatting.RED, Cam.ORBIT, 5, 6, 56, 108, 104, 180, EarthWonders::sporeBloom);
		add("sculk_awakening", "Sculk Awakening", "SCULK AWAKENING", ChatFormatting.DARK_AQUA, Cam.LOW, 3, 10, 56, 108, 104, 180, EarthWonders::sculkAwakening);
	}

	private Wonders() {}

	/** The explosion with this id, or null when it is not one of these twelve. */
	public static Wonder of(String id) {
		return id == null ? null : ALL.get(id);
	}

	public static List<Wonder> all() {
		return new ArrayList<>(ALL.values());
	}
}
