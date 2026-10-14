package com.pvpduels.effects;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The victory sounds: a short melody played with note-block instruments when somebody wins. Fireworks and particles share one cheerful
 * tune; every explosion has its own. A tune is a few layers (an instrument, how many ticks pass between two notes and the notes); a
 * note is a number from 0 to 24 (the two octaves of a note block: 0 is F#, 12 is the F# an octave up, 6 is C, 10 is E, 13 is G).
 * "." is a rest and "6+10" is two notes at once.
 * <p>
 * Sounds are only sent to the people who have victory sounds on (/effects) and who can see the effects of the winner.
 */
public final class Jingles {
	/** One note: when (ticks after the start of the tune), with what, and how high and loud. */
	public record Note(int tick, Holder<SoundEvent> sound, float pitch, float volume) {}

	/** A melody. */
	public static final class Tune {
		public final String id;
		final List<Note> notes = new ArrayList<>();
		int length;

		Tune(String id) {
			this.id = id;
		}

		/** Adds a layer: the score is played with {@code instrument}, one entry every {@code step} ticks, from tick {@code start}. */
		Tune layer(Holder<SoundEvent> instrument, float volume, int step, int start, String score) {
			int tick = start;
			for (String token : score.trim().split("\\s+")) {
				if (!token.equals(".")) {
					for (String one : token.split("\\+")) {
						int n = Math.max(0, Math.min(24, Integer.parseInt(one)));
						notes.add(new Note(tick, instrument, (float) Math.pow(2.0, (n - 12) / 12.0), volume));
					}
				}
				length = Math.max(length, tick + 1);
				tick += step;
			}
			return this;
		}

		/** How many notes the melody has. */
		public int size() {
			return notes.size();
		}

		/** How many ticks it lasts. */
		public int length() {
			return length;
		}
	}

	private static final Map<String, Tune> TUNES = new HashMap<>();
	private static final Tune COMMON = new Tune("common");
	/** For the self-test: the notes played (once each, however many people heard them) and the tune of the last one. */
	private static int played;
	private static String lastTune = "";

	private static Tune tune(String id) {
		Tune t = new Tune(id);
		TUNES.put(id, t);
		return t;
	}

	static {
		Holder<SoundEvent> harp = SoundEvents.NOTE_BLOCK_HARP;
		Holder<SoundEvent> bell = SoundEvents.NOTE_BLOCK_BELL;
		Holder<SoundEvent> chime = SoundEvents.NOTE_BLOCK_CHIME;
		Holder<SoundEvent> flute = SoundEvents.NOTE_BLOCK_FLUTE;
		Holder<SoundEvent> bass = SoundEvents.NOTE_BLOCK_BASS;
		Holder<SoundEvent> pling = SoundEvents.NOTE_BLOCK_PLING;
		Holder<SoundEvent> xylophone = SoundEvents.NOTE_BLOCK_XYLOPHONE;
		Holder<SoundEvent> iron = SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE;
		Holder<SoundEvent> guitar = SoundEvents.NOTE_BLOCK_GUITAR;
		Holder<SoundEvent> bit = SoundEvents.NOTE_BLOCK_BIT;
		Holder<SoundEvent> banjo = SoundEvents.NOTE_BLOCK_BANJO;
		Holder<SoundEvent> didgeridoo = SoundEvents.NOTE_BLOCK_DIDGERIDOO;
		Holder<SoundEvent> cowBell = SoundEvents.NOTE_BLOCK_COW_BELL;
		Holder<SoundEvent> drum = SoundEvents.NOTE_BLOCK_BASEDRUM;
		Holder<SoundEvent> snare = SoundEvents.NOTE_BLOCK_SNARE;

		// fireworks and particles: a bright rising chord
		COMMON.layer(harp, 1.0f, 3, 0, "6 10 13 18").layer(chime, 0.8f, 4, 12, "22+18");

		tune("orbital_strike").layer(bass, 1.2f, 4, 0, "6 . 6 . 13 13 18").layer(pling, 0.8f, 4, 8, "13 . 18 . 22 . 24");
		tune("orbital_wither").layer(didgeridoo, 1.2f, 5, 0, "0 . 0 . 3 . 0").layer(bell, 0.7f, 5, 5, "9 . 12 . 9 . 6");
		tune("shooting_star").layer(bell, 0.9f, 3, 0, "10 13 17 22 24").layer(harp, 0.6f, 3, 1, "22 20 17 13 10 6");
		tune("black_hole").layer(bass, 1.3f, 5, 0, "13 11 8 6 3 1 0").layer(didgeridoo, 1.0f, 7, 0, "0 . 0 .").layer(bit, 0.5f, 2, 20, "24 18 12 6 0");
		tune("meteor_shower").layer(xylophone, 0.9f, 2, 0, "18 22 20 24 17 22 15 20").layer(drum, 1.0f, 6, 0, "6 . 6 . 6");
		tune("gungnir").layer(iron, 1.0f, 4, 0, "6 13 18 . 13 18 22").layer(drum, 1.0f, 8, 0, "3 . 3 .");
		tune("seven_stars").layer(bell, 0.9f, 3, 0, "6 8 10 13 15 18 20").layer(chime, 0.6f, 3, 21, "22+24");
		tune("railgun").layer(bit, 0.9f, 1, 0, "0 6 12 18 24 24").layer(snare, 0.8f, 3, 6, "10 . 10").layer(pling, 0.7f, 2, 8, "24 . 24");
		tune("tesseract").layer(bit, 0.8f, 2, 0, "6 13 18 13 6 13 18 22").layer(pling, 0.6f, 4, 1, "18 . 22 . 24");
		tune("eclipse").layer(flute, 1.0f, 6, 0, "3 6 10 6 3 . 1").layer(bell, 0.6f, 6, 3, "15 . 12 . 10 . 6").layer(didgeridoo, 0.8f, 12, 0, "0 . 0");
		tune("moonfall").layer(chime, 0.9f, 5, 0, "22 . 18 . 15 . 10 . 6").layer(harp, 0.6f, 5, 2, "13 . 10 . 6 . 3");
		tune("cronos").layer(cowBell, 0.8f, 4, 0, "12 . 12 . 12 . 6 . 18").layer(bell, 1.0f, 6, 12, "6 10 13 18");
		tune("zeus").layer(drum, 1.2f, 4, 0, "3 . 3").layer(guitar, 1.0f, 4, 2, "6+13 . 6+13").layer(banjo, 0.8f, 3, 12, "13 18 22 18 22 24");
		tune("domain_expansion").layer(flute, 0.9f, 4, 0, "10 11 15 17 18 17 15 11 10").layer(harp, 0.6f, 8, 0, "10+15 . 11+17 . 10+15").layer(bell, 0.6f, 4, 28, "22");
		tune("domain_void").layer(didgeridoo, 1.2f, 10, 0, "0 . 0").layer(pling, 0.7f, 6, 4, "12 . 11 . 12 . 6").layer(bell, 0.5f, 12, 10, "24 . 23");
		tune("domain_swords").layer(iron, 1.0f, 3, 0, "6 . 13 . 18 6 13 18").layer(snare, 0.7f, 3, 0, "8 . 8 . 8 8 . 8").layer(banjo, 0.7f, 3, 24, "22 24");
		tune("domain_frost").layer(chime, 0.8f, 3, 0, "24 22 19 17 22 24 19").layer(harp, 0.6f, 3, 2, "17 14 12 10 14 17").layer(bell, 0.6f, 4, 20, "24");
		tune("domain_inferno").layer(guitar, 1.1f, 3, 0, "0 3 5 0 3 6 5 3").layer(drum, 1.0f, 6, 0, "2 . 2 . 2 2").layer(bit, 0.6f, 3, 24, "12 15 18");
		tune("domain_garden").layer(flute, 0.9f, 4, 0, "13 15 17 20 17 15 13").layer(harp, 0.7f, 4, 2, "6+10 . 8+13 . 6+10").layer(chime, 0.6f, 4, 28, "22+24");
		tune("domain_ocean").layer(harp, 0.9f, 3, 0, "6 10 13 10 6 10 13 17").layer(bass, 0.9f, 6, 0, "6 . 3 . 5 .").layer(chime, 0.5f, 5, 6, "22 . 24 . 20");
	}

	private Jingles() {}

	/** The sad tunes of the loser. */
	private static final List<Tune> DEFEATS = new ArrayList<>();

	private static Tune sad(String id) {
		Tune t = new Tune(id);
		DEFEATS.add(t);
		return t;
	}

	static {
		Holder<SoundEvent> harp = SoundEvents.NOTE_BLOCK_HARP;
		Holder<SoundEvent> chime = SoundEvents.NOTE_BLOCK_CHIME;
		Holder<SoundEvent> bass = SoundEvents.NOTE_BLOCK_BASS;
		Holder<SoundEvent> pling = SoundEvents.NOTE_BLOCK_PLING;
		Holder<SoundEvent> bit = SoundEvents.NOTE_BLOCK_BIT;
		Holder<SoundEvent> didgeridoo = SoundEvents.NOTE_BLOCK_DIDGERIDOO;
		Holder<SoundEvent> cowBell = SoundEvents.NOTE_BLOCK_COW_BELL;
		Holder<SoundEvent> drum = SoundEvents.NOTE_BLOCK_BASEDRUM;
		Holder<SoundEvent> hat = SoundEvents.NOTE_BLOCK_HAT;

		// the old "wah wah wah waaah": three falling notes and a long slide
		sad("sad_trombone").layer(didgeridoo, 1.3f, 8, 0, "10 9 8").layer(didgeridoo, 1.3f, 2, 24, "7 6 5 4 3 2 1 0 0");
		// two deflated notes
		sad("womp_womp").layer(bass, 1.2f, 9, 0, "8 5").layer(didgeridoo, 1.0f, 9, 0, "8 5").layer(bass, 1.2f, 3, 18, "2 1 0 0");
		// a whistle that falls off a cliff and a thud
		sad("falling").layer(bit, 0.9f, 1, 0, "24 23 22 21 20 19 18 17 16 15 14 13 12 11 10 9 8 7 6 5 4 3 2 1 0").layer(drum, 1.2f, 1, 26, "2");
		// flat and unimpressed
		sad("bruh").layer(drum, 1.2f, 1, 0, "6").layer(bass, 1.2f, 7, 0, "5 3").layer(hat, 0.7f, 1, 16, "12");
		// a comedy horn
		sad("fail_horn").layer(cowBell, 1.0f, 4, 0, "6 6 6 .").layer(didgeridoo, 1.1f, 6, 12, "3 0 0");
		// a music box that runs down
		sad("music_box").layer(harp, 0.9f, 4, 0, "18 15 13 10 6 3").layer(chime, 0.5f, 4, 2, "24 20 17 14 10 6").layer(pling, 0.6f, 6, 24, "1");
	}

	/** A sad tune picked at random. */
	public static Tune randomDefeat() {
		return DEFEATS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(DEFEATS.size()));
	}

	public static List<Tune> defeats() {
		return DEFEATS;
	}

	/** The tune of an explosion; the common one for anything without its own. */
	public static Tune of(String explosion) {
		Tune t = explosion == null ? null : TUNES.get(explosion);
		return t == null ? COMMON : t;
	}

	public static Tune common() {
		return COMMON;
	}

	/** True when the explosion has a melody of its own. */
	public static boolean hasOwn(String explosion) {
		return explosion != null && TUNES.containsKey(explosion);
	}

	public static int notesPlayed() {
		return played;
	}

	public static String lastTune() {
		return lastTune;
	}

	public static void resetForTest() {
		played = 0;
		lastTune = "";
	}

	/**
	 * Plays the notes that start {@code t} ticks into the tune, around {@code at} (or around each listener when it is null), to the
	 * listeners who have victory sounds on.
	 */
	public static void tick(ServerLevel level, List<ServerPlayer> listeners, Vec3 at, Tune tune, int t) {
		if (tune == null || t < 0 || t >= tune.length) return;
		for (Note note : tune.notes) {
			if (note.tick() != t) continue;
			played++;
			lastTune = tune.id;
			for (ServerPlayer p : listeners) {
				if (!Effects.jingles(p.getUUID())) continue;
				Vec3 where = at != null ? at : p.position();
				p.connection.send(new ClientboundSoundPacket(note.sound(), SoundSource.MASTER, where.x, where.y, where.z, note.volume() * 2.0f, note.pitch(),
					level.getRandom().nextLong()));
			}
		}
	}
}
