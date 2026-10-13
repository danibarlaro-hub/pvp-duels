package com.pvpduels.duel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One recorded 1v1 duel: where both players were and what they looked like on every tick, plus the hits and the rounds. Positions are
 * stored relative to the middle of the arena, so the replay can be played in any arena slot. It is only data: {@link ReplayRecorder} fills
 * it, {@link ReplayStore} keeps it on disk and {@link ReplayPlayer} plays it.
 */
final class ReplayData {
	static final int MAGIC = 0x50565250; // "PVRP"
	static final int VERSION = 1;
	/** Twenty minutes: a longer duel is cut there. */
	static final int MAX_FRAMES = 20 * 60 * 20;
	/** head, chest, legs, feet, main hand, off hand. */
	static final int SLOTS = 6;

	static final int EV_ROUND_START = 0;
	static final int EV_HIT = 1;
	static final int EV_ROUND_END = 2;

	static final int FLAG_SWING = 1;
	static final int FLAG_SPRINT = 2;
	static final int FLAG_USING = 4;
	static final int FLAG_HURT = 8;

	/** Something that happened on one tick: a round started (a = round), a hit (a = who was hurt, f = damage) or a round ended. */
	record Event(int tick, int type, int a, int b, float f) {}

	String id = "";
	long created;
	final String[] names = {"", ""};
	final UUID[] uuids = new UUID[2];
	DuelConfig config;
	/** -2 undecided (the recording was cut), -1 draw, 0 or 1. */
	int winner = -2;
	final int[] points = new int[2];
	boolean truncated;
	/** The middle of the arena where it was recorded: x and z of the center, y of the floor the players start on. */
	double refX;
	double refY;
	double refZ;
	/** The items worn or held, as text. Number 0 is "nothing". */
	final List<String> palette = new ArrayList<>();
	final List<Event> events = new ArrayList<>();

	int frames;
	float[][] x = new float[2][0];
	float[][] y = new float[2][0];
	float[][] z = new float[2][0];
	byte[][] yaw = new byte[2][0];
	byte[][] pitch = new byte[2][0];
	byte[][] pose = new byte[2][0];
	byte[][] flags = new byte[2][0];
	/** Health plus absorption, times two. */
	byte[][] health = new byte[2][0];
	/** equip[player][slot][frame] is a number of the palette. */
	short[][][] equip = new short[2][SLOTS][0];

	ReplayData() {
		palette.add("");
	}

	/** Makes room for at least this many frames. */
	void ensure(int capacity) {
		if (x[0].length >= capacity) return;
		int size = Math.max(capacity, Math.max(256, x[0].length * 2));
		for (int p = 0; p < 2; p++) {
			x[p] = java.util.Arrays.copyOf(x[p], size);
			y[p] = java.util.Arrays.copyOf(y[p], size);
			z[p] = java.util.Arrays.copyOf(z[p], size);
			yaw[p] = java.util.Arrays.copyOf(yaw[p], size);
			pitch[p] = java.util.Arrays.copyOf(pitch[p], size);
			pose[p] = java.util.Arrays.copyOf(pose[p], size);
			flags[p] = java.util.Arrays.copyOf(flags[p], size);
			health[p] = java.util.Arrays.copyOf(health[p], size);
			for (int s = 0; s < SLOTS; s++) equip[p][s] = java.util.Arrays.copyOf(equip[p][s], size);
		}
	}

	static float angle(byte b) {
		return (b & 0xFF) * 360f / 256f;
	}

	static byte toByte(float degrees) {
		return (byte) Math.round(degrees * 256f / 360f);
	}

	int seconds() {
		return frames / 20;
	}

	// ------------------------------------------------------------------ file

	private static void writeText(DataOutputStream out, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readText(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > 4_000_000) throw new IOException("bad text length " + length);
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	void write(OutputStream raw) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(raw), 1 << 16))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			writeText(out, id);
			out.writeLong(created);
			for (int i = 0; i < 2; i++) {
				writeText(out, names[i]);
				out.writeLong(uuids[i].getMostSignificantBits());
				out.writeLong(uuids[i].getLeastSignificantBits());
			}
			writeText(out, config.kit());
			writeText(out, config.arena().name());
			out.writeBoolean(config.walls());
			out.writeInt(config.radius());
			writeText(out, config.mode().name());
			out.writeInt(config.limit());
			DuelConfig.Rules rules = config.rules();
			out.writeBoolean(rules.regen());
			out.writeBoolean(rules.fallDamage());
			out.writeBoolean(rules.hunger());
			out.writeBoolean(rules.building());
			writeText(out, config.custom());
			out.writeInt(winner);
			out.writeInt(points[0]);
			out.writeInt(points[1]);
			out.writeBoolean(truncated);
			out.writeDouble(refX);
			out.writeDouble(refY);
			out.writeDouble(refZ);
			out.writeInt(palette.size());
			for (String item : palette) writeText(out, item);
			out.writeInt(frames);
			for (int f = 0; f < frames; f++) {
				for (int p = 0; p < 2; p++) {
					out.writeFloat(x[p][f]);
					out.writeFloat(y[p][f]);
					out.writeFloat(z[p][f]);
					out.writeByte(yaw[p][f]);
					out.writeByte(pitch[p][f]);
					out.writeByte(pose[p][f]);
					out.writeByte(flags[p][f]);
					out.writeByte(health[p][f]);
					for (int s = 0; s < SLOTS; s++) out.writeShort(equip[p][s][f]);
				}
			}
			out.writeInt(events.size());
			for (Event e : events) {
				out.writeInt(e.tick());
				out.writeByte(e.type());
				out.writeInt(e.a());
				out.writeInt(e.b());
				out.writeFloat(e.f());
			}
		}
	}

	static ReplayData read(InputStream raw) throws IOException {
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw), 1 << 16))) {
			if (in.readInt() != MAGIC) throw new IOException("not a replay file");
			int version = in.readInt();
			if (version != VERSION) throw new IOException("replay version " + version + " is not supported");
			ReplayData d = new ReplayData();
			d.id = readText(in);
			d.created = in.readLong();
			for (int i = 0; i < 2; i++) {
				d.names[i] = readText(in);
				long msb = in.readLong();
				long lsb = in.readLong();
				d.uuids[i] = new UUID(msb, lsb);
			}
			String kit = readText(in);
			ArenaKind arena = ArenaKind.valueOf(readText(in));
			boolean walls = in.readBoolean();
			int radius = in.readInt();
			WinMode mode = WinMode.valueOf(readText(in));
			int limit = in.readInt();
			DuelConfig.Rules rules = new DuelConfig.Rules(in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean());
			String custom = readText(in);
			d.config = new DuelConfig(kit, arena, walls, radius, mode, limit, rules, custom);
			d.winner = in.readInt();
			d.points[0] = in.readInt();
			d.points[1] = in.readInt();
			d.truncated = in.readBoolean();
			d.refX = in.readDouble();
			d.refY = in.readDouble();
			d.refZ = in.readDouble();
			int items = in.readInt();
			d.palette.clear();
			for (int i = 0; i < items; i++) d.palette.add(readText(in));
			if (d.palette.isEmpty()) d.palette.add("");
			int frames = in.readInt();
			if (frames < 0 || frames > MAX_FRAMES + 10) throw new IOException("bad frame count " + frames);
			d.ensure(Math.max(1, frames));
			for (int f = 0; f < frames; f++) {
				for (int p = 0; p < 2; p++) {
					d.x[p][f] = in.readFloat();
					d.y[p][f] = in.readFloat();
					d.z[p][f] = in.readFloat();
					d.yaw[p][f] = in.readByte();
					d.pitch[p][f] = in.readByte();
					d.pose[p][f] = in.readByte();
					d.flags[p][f] = in.readByte();
					d.health[p][f] = in.readByte();
					for (int s = 0; s < SLOTS; s++) d.equip[p][s][f] = in.readShort();
				}
			}
			d.frames = frames;
			int events = in.readInt();
			for (int i = 0; i < events; i++) d.events.add(new Event(in.readInt(), in.readByte(), in.readInt(), in.readInt(), in.readFloat()));
			return d;
		}
	}
}
