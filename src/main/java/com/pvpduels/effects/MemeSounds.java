package com.pvpduels.effects;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The twenty silly sounds that can play when you lose (instead of the sad tune). They are real recordings in the jar
 * (assets/pvpduels/sounds/loss), made for this mod. Each player can turn them off in /effects.
 */
public final class MemeSounds {
	/** One of the sounds: its id (the name of the file), what the player sees, and the sound event to send. */
	public record Meme(String id, String name, Holder<SoundEvent> sound) {}

	private static final String[][] LIST = {
		{"fahhh", "Fahhh"}, {"sad_trombone", "Sad trombone"}, {"record_scratch", "Record scratch"}, {"error_buzz", "Error buzz"},
		{"squelch", "Squelch"}, {"bonk", "Bonk"}, {"boing", "Boing"}, {"slide_whistle", "Slide whistle"}, {"boom", "Boom"},
		{"dial_up", "Dial-up"}, {"airhorn", "Air horn"}, {"duck_quack", "Duck quack"}, {"sad_violin", "Sad violin"}, {"ugh", "Ugh"},
		{"bike_horn", "Bike horn"}, {"glass_womp", "Glass womp"}, {"tire_screech", "Tire screech"}, {"rimshot", "Rimshot"},
		{"crowd_aww", "Crowd aww"}, {"game_over", "Game over"}
	};

	private static final List<Meme> ALL = build();
	private static int played;
	private static String last = "";

	private MemeSounds() {}

	private static List<Meme> build() {
		List<Meme> out = new ArrayList<>();
		for (String[] row : LIST) {
			// not registered: a sound event that is sent by its name works for everybody who has the jar and costs vanilla clients nothing
			Identifier id = Identifier.fromNamespaceAndPath("pvpduels", "loss." + row[0]);
			out.add(new Meme(row[0], row[1], Holder.direct(SoundEvent.createVariableRangeEvent(id))));
		}
		return List.copyOf(out);
	}

	public static List<Meme> all() {
		return ALL;
	}

	public static Meme random() {
		return ALL.get(ThreadLocalRandom.current().nextInt(ALL.size()));
	}

	public static Meme of(String id) {
		for (Meme m : ALL) if (m.id().equals(id)) return m;
		return null;
	}

	/** True when this player wants the silly sounds: they are on unless the player turned them off (and the win and lose sounds are on). */
	public static boolean wants(ServerPlayer p) {
		return Effects.jingles(p.getUUID()) && Effects.memes(p.getUUID());
	}

	/** The players of {@code listeners} who get the silly sound. */
	public static List<ServerPlayer> loud(List<ServerPlayer> listeners) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : listeners) if (wants(p)) out.add(p);
		return out;
	}

	/** The players of {@code listeners} who get the sad tune of the notes instead. */
	public static List<ServerPlayer> quiet(List<ServerPlayer> listeners) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : listeners) if (!wants(p)) out.add(p);
		return out;
	}

	/** Plays {@code meme} to each of these players (without asking them: use {@link #loud}), loud and centered on them. */
	public static void play(List<ServerPlayer> listeners, Meme meme) {
		if (meme == null) return;
		played++;
		last = meme.id();
		for (ServerPlayer p : listeners) {
			p.connection.send(new ClientboundSoundPacket(meme.sound(), SoundSource.MASTER, p.getX(), p.getY(), p.getZ(), 1.0f, 1.0f, p.level().getRandom().nextLong()));
		}
	}

	public static int played() {
		return played;
	}

	public static String last() {
		return last;
	}

	public static void resetForTest() {
		played = 0;
		last = "";
	}
}
