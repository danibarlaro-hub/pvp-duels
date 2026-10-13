package com.pvpduels.effects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.pvpduels.PvpDuelsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * What a player owns to celebrate a victory (firework colors and shapes, particles, explosions) and what they have equipped, plus the
 * progress of their daily and weekly missions. You can have effects (fireworks and particles) OR an explosion equipped, not both. Missions give rewards ({@link #roll}); the equipped things are played when they win a duel
 * ({@link Victory}). Saved in pvpduels/effects.json.
 */
public final class Effects {
	/** Three fireworks go up in a row behind you when you win: the middle one (a block higher), the left one and the right one. */
	public static final int FIREWORK_SLOTS = 3;
	public static final String[] SLOT_NAMES = {"Middle", "Left", "Right"};
	public static final int MAX_COLORS = 5;
	public static final int FLOOR_SLOTS = 2;
	public static final int AREA_SLOTS = 4;
	/** You can own (and equip) the same particle this many times. */
	public static final int MAX_COPIES = 6;

	public static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
	/** The colors vanilla fireworks get from the dyes. */
	public static final int[] COLOR_RGB = {15790320, 15435844, 12801229, 6719955, 14602026, 4312372, 14188952, 4408131,
		11250603, 2651799, 8073150, 2437522, 5320730, 3887386, 11743532, 1973019};
	public static final String[] SHAPES = {"small_ball", "large_ball", "star", "creeper", "burst"};
	public static final String[] SHAPE_NAMES = {"Small ball", "Large ball", "Star", "Creeper", "Burst"};
	public static final String[] EXPLOSIONS = {"orbital_strike", "orbital_wither", "domain_expansion"};
	public static final String[] EXPLOSION_NAMES = {"Orbital Strike Cannon", "Orbital Wither Cannon", "Domain Expansion"};

	/** The chances of the rewards of a mission (each mission rolls them). Easy to change here. */
	public static double fireworkChance = 0.70;
	public static double particleChance = 0.40;
	/** Chance of an explosion: 0.8% for a daily mission, 1.5% for a weekly one. */
	public static double explosionChance = 0.008;
	public static double weeklyExplosionChance = 0.015;
	/** After this many missions without an explosion the next one gives it. */
	public static int explosionPity = 100;

	/** Everything about one player. Arrays use "" for an empty slot (so the file has no nulls). */
	static final class Data {
		String name = "";
		/** Firework things: "color:red", "shape:star", "trail", "twinkle". */
		List<String> items = new ArrayList<>();
		/** Particle id -> how many copies. */
		Map<String, Integer> particles = new LinkedHashMap<>();
		List<String> explosions = new ArrayList<>();
		/** The equipped fireworks: colors, shape, trail and twinkle for each of the three. */
		List<List<String>> fwColors = new ArrayList<>();
		String[] fwShape = {"small_ball", "small_ball", "small_ball"};
		boolean[] fwTrail = new boolean[FIREWORK_SLOTS];
		boolean[] fwTwinkle = new boolean[FIREWORK_SLOTS];
		String[] floor = {"", ""};
		String[] area = {"", "", "", ""};
		String explosion = "";
		/** Missions of the day: the date, the progress of each, the ones done, the current win run and the kits won today. */
		String day = "";
		Map<String, Double> progress = new HashMap<>();
		List<String> done = new ArrayList<>();
		int winRun;
		List<String> kitsWon = new ArrayList<>();
		/** Missions completed since the last explosion reward. */
		int sinceExplosion;
		int completedTotal;
		/** The weekly missions: the Monday of the week, the progress of each, the ones done, the win run and the kits won this week. */
		String week = "";
		Map<String, Double> weekProgress = new HashMap<>();
		List<String> weekDone = new ArrayList<>();
		int weekRun;
		List<String> weekKits = new ArrayList<>();
		/** Messages about rewards the player has not seen yet. */
		List<String> unannounced = new ArrayList<>();
		/** The player does not want to see other players' victory effects. */
		boolean hideOthers;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Data> PLAYERS = new HashMap<>();
	private static boolean loaded;
	private static Random random = new Random();

	private Effects() {}

	// ------------------------------------------------------------------ the file

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("effects.json");
	}

	private static void fix(Data d) {
		if (d.name == null) d.name = "";
		if (d.items == null) d.items = new ArrayList<>();
		if (d.particles == null) d.particles = new LinkedHashMap<>();
		if (d.explosions == null) d.explosions = new ArrayList<>();
		if (d.fwColors == null) d.fwColors = new ArrayList<>();
		while (d.fwColors.size() < FIREWORK_SLOTS) d.fwColors.add(new ArrayList<>());
		if (d.fwShape == null || d.fwShape.length != FIREWORK_SLOTS) d.fwShape = new String[] {"small_ball", "small_ball", "small_ball"};
		if (d.fwTrail == null || d.fwTrail.length != FIREWORK_SLOTS) d.fwTrail = new boolean[FIREWORK_SLOTS];
		if (d.fwTwinkle == null || d.fwTwinkle.length != FIREWORK_SLOTS) d.fwTwinkle = new boolean[FIREWORK_SLOTS];
		if (d.floor == null || d.floor.length != FLOOR_SLOTS) d.floor = new String[] {"", ""};
		if (d.area == null || d.area.length != AREA_SLOTS) d.area = new String[] {"", "", "", ""};
		for (int i = 0; i < d.floor.length; i++) if (d.floor[i] == null) d.floor[i] = "";
		for (int i = 0; i < d.area.length; i++) if (d.area[i] == null) d.area[i] = "";
		for (int i = 0; i < d.fwShape.length; i++) if (d.fwShape[i] == null || d.fwShape[i].isEmpty()) d.fwShape[i] = "small_ball";
		if (d.explosion == null) d.explosion = "";
		if (d.day == null) d.day = "";
		if (d.progress == null) d.progress = new HashMap<>();
		if (d.done == null) d.done = new ArrayList<>();
		if (d.kitsWon == null) d.kitsWon = new ArrayList<>();
		if (d.week == null) d.week = "";
		if (d.weekProgress == null) d.weekProgress = new HashMap<>();
		if (d.weekDone == null) d.weekDone = new ArrayList<>();
		if (d.weekKits == null) d.weekKits = new ArrayList<>();
		if (d.unannounced == null) d.unannounced = new ArrayList<>();
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Data> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Data>>() {}.getType());
			if (read != null) {
				for (Data d : read.values()) fix(d);
				PLAYERS.putAll(read);
			}
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the effects file: {}", e.toString());
		}
	}

	static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(PLAYERS));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the effects file: {}", e.toString());
		}
	}

	/** For the self-test: forget everybody. */
	public static synchronized void resetForTest() {
		PLAYERS.clear();
		loaded = true;
		random = new Random();
		fireworkChance = 0.70;
		particleChance = 0.40;
		explosionChance = 0.008;
		weeklyExplosionChance = 0.015;
		explosionPity = 100;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	/** For the self-test: throw away what is in memory and read the file again. */
	public static synchronized void reloadForTest() {
		PLAYERS.clear();
		loaded = false;
		ensureLoaded();
	}

	public static synchronized void useRandom(Random r) {
		random = r;
	}

	public static synchronized void forgetAll() {
		PLAYERS.clear();
		loaded = false; // the next server reads the file again
	}

	static synchronized Data data(UUID id, String name) {
		ensureLoaded();
		Data d = PLAYERS.get(id.toString());
		if (d == null) {
			d = new Data();
			fix(d);
			d.items.add("color:white");
			d.items.add("shape:small_ball");
			d.fwColors.get(0).add("white"); // a first firework behind you, so the first win already has something
			PLAYERS.put(id.toString(), d);
		}
		if (name != null && !name.isEmpty()) d.name = name;
		return d;
	}

	// ------------------------------------------------------------------ what exists

	/** Every firework thing a mission can give: 16 colors, 5 shapes, trail and twinkle. */
	public static List<String> allFireworkItems() {
		List<String> all = new ArrayList<>();
		for (String c : COLORS) all.add("color:" + c);
		for (String s : SHAPES) all.add("shape:" + s);
		all.add("trail");
		all.add("twinkle");
		return all;
	}

	public static int colorIndex(String color) {
		for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) return i;
		return -1;
	}

	public static int shapeIndex(String shape) {
		for (int i = 0; i < SHAPES.length; i++) if (SHAPES[i].equals(shape)) return i;
		return -1;
	}

	public static int explosionIndex(String ex) {
		for (int i = 0; i < EXPLOSIONS.length; i++) if (EXPLOSIONS[i].equals(ex)) return i;
		return -1;
	}

	public static String prettyColor(String color) {
		return pretty(color);
	}

	/** "light_blue" -> "Light Blue". */
	public static String pretty(String id) {
		StringBuilder out = new StringBuilder();
		for (String word : id.split("[_:]")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	public static String itemName(String item) {
		if (item.startsWith("color:")) return "Firework color " + pretty(item.substring(6));
		if (item.startsWith("shape:")) {
			int i = shapeIndex(item.substring(6));
			return "Firework shape " + (i < 0 ? item : SHAPE_NAMES[i]);
		}
		if (item.equals("trail")) return "Firework trail";
		if (item.equals("twinkle")) return "Firework twinkle";
		return item;
	}

	// ------------------------------------------------------------------ owned things

	public static synchronized boolean ownsItem(UUID id, String item) {
		return data(id, null).items.contains(item);
	}

	public static synchronized int ownedItemCount(UUID id) {
		return data(id, null).items.size();
	}

	public static synchronized int particleCount(UUID id, String particle) {
		return data(id, null).particles.getOrDefault(particle, 0);
	}

	public static synchronized Map<String, Integer> ownedParticles(UUID id) {
		return new LinkedHashMap<>(data(id, null).particles);
	}

	public static synchronized boolean ownsExplosion(UUID id, String ex) {
		return data(id, null).explosions.contains(ex);
	}

	public static synchronized List<String> ownedExplosions(UUID id) {
		return new ArrayList<>(data(id, null).explosions);
	}

	public static synchronized void grantItem(UUID id, String item) {
		Data d = data(id, null);
		if (!d.items.contains(item)) d.items.add(item);
		save();
	}

	public static synchronized void grantParticle(UUID id, String particle) {
		Data d = data(id, null);
		d.particles.merge(particle, 1, (a, b) -> Math.min(MAX_COPIES, a + b));
		save();
	}

	/** Admins and tests: everything unlocked (3 copies of every particle). */
	public static synchronized void unlockAll(UUID id) {
		Data d = data(id, null);
		for (String item : allFireworkItems()) if (!d.items.contains(item)) d.items.add(item);
		for (Particles.Def p : Particles.all()) d.particles.put(p.id(), Math.max(3, d.particles.getOrDefault(p.id(), 0)));
		for (String ex : EXPLOSIONS) if (!d.explosions.contains(ex)) d.explosions.add(ex);
		save();
	}

	public static synchronized void grantExplosion(UUID id, String ex) {
		Data d = data(id, null);
		if (!d.explosions.contains(ex)) d.explosions.add(ex);
		save();
	}

	// ------------------------------------------------------------------ the equipped fireworks

	public static synchronized List<String> fireworkColors(UUID id, int slot) {
		return new ArrayList<>(data(id, null).fwColors.get(slot));
	}

	public static synchronized String fireworkShape(UUID id, int slot) {
		return data(id, null).fwShape[slot];
	}

	public static synchronized boolean fireworkTrail(UUID id, int slot) {
		return data(id, null).fwTrail[slot];
	}

	public static synchronized boolean fireworkTwinkle(UUID id, int slot) {
		return data(id, null).fwTwinkle[slot];
	}

	/** Puts a color on a firework or takes it off. Returns an error text or null. */
	public static synchronized String toggleColor(UUID id, int slot, String color) {
		Data d = data(id, null);
		if (colorIndex(color) < 0) return "That is not a firework color.";
		if (!d.items.contains("color:" + color)) return "You haven't unlocked that color yet.";
		List<String> colors = d.fwColors.get(slot);
		if (colors.remove(color)) {
			save();
			return null;
		}
		if (colors.size() >= MAX_COLORS) return "A firework has up to " + MAX_COLORS + " colors. Take one off first.";
		colors.add(color);
		d.explosion = ""; // effects OR an explosion
		save();
		return null;
	}

	public static synchronized String setShape(UUID id, int slot, String shape) {
		Data d = data(id, null);
		if (shapeIndex(shape) < 0) return "That is not a firework shape.";
		if (!d.items.contains("shape:" + shape)) return "You haven't unlocked that shape yet.";
		d.fwShape[slot] = shape;
		save();
		return null;
	}

	public static synchronized String toggleTrail(UUID id, int slot) {
		Data d = data(id, null);
		if (!d.items.contains("trail")) return "You haven't unlocked the trail yet.";
		d.fwTrail[slot] = !d.fwTrail[slot];
		save();
		return null;
	}

	public static synchronized String toggleTwinkle(UUID id, int slot) {
		Data d = data(id, null);
		if (!d.items.contains("twinkle")) return "You haven't unlocked the twinkle yet.";
		d.fwTwinkle[slot] = !d.fwTwinkle[slot];
		save();
		return null;
	}

	// ------------------------------------------------------------------ the equipped particles

	/** Particle ids under the feet (index 0 and 1) or around the arena (0 to 3); "" is an empty slot. */
	public static synchronized String particleSlot(UUID id, boolean area, int index) {
		Data d = data(id, null);
		return area ? d.area[index] : d.floor[index];
	}

	public static synchronized int equippedCount(UUID id, String particle) {
		Data d = data(id, null);
		int n = 0;
		for (String s : d.floor) if (s.equals(particle)) n++;
		for (String s : d.area) if (s.equals(particle)) n++;
		return n;
	}

	/** Puts a particle in a slot. You can use as many slots as copies you own. Returns an error text or null. */
	public static synchronized String setParticle(UUID id, boolean area, int index, String particle) {
		Data d = data(id, null);
		String[] slots = area ? d.area : d.floor;
		if (index < 0 || index >= slots.length) return "That slot does not exist.";
		if (particle.isEmpty()) {
			slots[index] = "";
			save();
			return null;
		}
		int owned = d.particles.getOrDefault(particle, 0);
		if (owned == 0) return "You haven't unlocked that particle yet.";
		int used = 0;
		for (String s : d.floor) if (s.equals(particle)) used++;
		for (String s : d.area) if (s.equals(particle)) used++;
		if (slots[index].equals(particle)) used--; // it is already in this slot
		if (used >= owned) return "You only have " + owned + (owned == 1 ? " copy" : " copies") + " of that particle and all of them are equipped.";
		slots[index] = particle;
		d.explosion = ""; // effects OR an explosion
		save();
		return null;
	}

	public static synchronized String explosionSlot(UUID id) {
		return data(id, null).explosion;
	}

	/** True when any firework color or particle is equipped. */
	public static synchronized boolean hasEffects(UUID id) {
		Data d = data(id, null);
		for (List<String> colors : d.fwColors) if (!colors.isEmpty()) return true;
		for (String s : d.floor) if (!s.isEmpty()) return true;
		for (String s : d.area) if (!s.isEmpty()) return true;
		return false;
	}

	/**
	 * Equips an explosion ("" takes it off). Returns an error text or null. You can have effects OR an explosion: equipping an
	 * explosion takes every firework color and every particle off (everything you own stays yours).
	 */
	public static synchronized String setExplosion(UUID id, String ex) {
		Data d = data(id, null);
		if (!ex.isEmpty() && explosionIndex(ex) < 0) return "That is not an explosion.";
		if (!ex.isEmpty() && !d.explosions.contains(ex)) return "You haven't unlocked that explosion yet.";
		if (!ex.isEmpty()) {
			for (List<String> colors : d.fwColors) colors.clear();
			for (int i = 0; i < d.floor.length; i++) d.floor[i] = "";
			for (int i = 0; i < d.area.length; i++) d.area[i] = "";
		}
		d.explosion = ex;
		save();
		return null;
	}

	public static synchronized boolean hideOthers(UUID id) {
		return data(id, null).hideOthers;
	}

	public static synchronized void setHideOthers(UUID id, boolean hide) {
		data(id, null).hideOthers = hide;
		save();
	}

	// ------------------------------------------------------------------ rewards

	/** One thing a mission gave. */
	public record Gained(String kind, String id, String label, boolean duplicate) {}

	/**
	 * The reward of one completed mission: maybe an explosion (rare, with a guarantee after many missions), maybe a firework thing, maybe a
	 * particle. If nothing comes out, a particle is given anyway.
	 */
	public static synchronized List<Gained> roll(UUID id, String name) {
		return roll(id, name, false);
	}

	/** The reward of a weekly mission has a better chance of an explosion (1.5% instead of 0.8%). */
	public static synchronized List<Gained> roll(UUID id, String name, boolean weekly) {
		Data d = data(id, name);
		List<Gained> out = new ArrayList<>();
		d.completedTotal++;
		d.sinceExplosion++;

		List<String> lockedExplosions = new ArrayList<>();
		for (String ex : EXPLOSIONS) if (!d.explosions.contains(ex)) lockedExplosions.add(ex);
		if (!lockedExplosions.isEmpty() && (d.sinceExplosion >= explosionPity || random.nextDouble() < (weekly ? weeklyExplosionChance : explosionChance))) {
			String ex = lockedExplosions.get(random.nextInt(lockedExplosions.size()));
			d.explosions.add(ex);
			d.sinceExplosion = 0;
			out.add(new Gained("explosion", ex, "Explosion: " + EXPLOSION_NAMES[explosionIndex(ex)], false));
		}

		boolean wantParticle = false;
		if (random.nextDouble() < fireworkChance) {
			List<String> locked = new ArrayList<>();
			for (String item : allFireworkItems()) if (!d.items.contains(item)) locked.add(item);
			if (locked.isEmpty()) {
				wantParticle = true; // everything is unlocked: the roll turns into a particle
			} else {
				String item = locked.get(random.nextInt(locked.size()));
				d.items.add(item);
				out.add(new Gained("firework", item, itemName(item), false));
			}
		}
		if (random.nextDouble() < particleChance) wantParticle = true;
		if (out.isEmpty()) wantParticle = true;
		if (wantParticle) {
			Gained g = rollParticle(d);
			if (g != null) out.add(g);
		}
		save();
		return out;
	}

	private static Gained rollParticle(Data d) {
		List<Particles.Def> all = Particles.all();
		List<Particles.Def> open = new ArrayList<>();
		for (Particles.Def p : all) if (d.particles.getOrDefault(p.id(), 0) < MAX_COPIES) open.add(p);
		if (open.isEmpty()) return null;
		Particles.Def pick = open.get(random.nextInt(open.size()));
		int before = d.particles.getOrDefault(pick.id(), 0);
		d.particles.put(pick.id(), before + 1);
		return new Gained("particle", pick.id(), "Particle: " + pick.name() + (before > 0 ? " (now x" + (before + 1) + ")" : ""), before > 0);
	}
}
