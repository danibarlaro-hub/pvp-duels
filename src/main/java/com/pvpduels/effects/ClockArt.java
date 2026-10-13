package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Cronos Clock: a giant golden clock with Roman numerals draws itself in the air over the target, two rings of bars turn around it
 * faster and faster while the hands speed up, and at twelve o'clock time stops: a pale shell sweeps over the arena, the rings lock around
 * the target, who is frozen in a cage of ice-blue glass. When time starts again the clock falls apart.
 * Only light and sound: every solid part is a display entity, and nothing in the real world is touched.
 * {@code t} is the number of ticks since the explosion started; {@code cinema} is the long version of the finale.
 */
public final class ClockArt {
	private static final ParticleOptions GOLD = new DustParticleOptions(0xFFD060, 1.6f);
	private static final ParticleOptions ICE = new DustParticleOptions(0xBFE8FF, 1.6f);
	private static final Q[] TURNS = {Q.ID, Q.yaw(45)};
	private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"};

	private ClockArt() {}

	// ------------------------------------------------------------------ the numbers

	/** How long the clock takes to draw itself. */
	public static int build(boolean cinema) {
		return cinema ? 28 : 16;
	}

	/** How long the hands and the rings speed up. */
	public static int run(boolean cinema) {
		return cinema ? 84 : 44;
	}

	/** How long time stays stopped. */
	public static int hold(boolean cinema) {
		return cinema ? 52 : 28;
	}

	/** How long the aftermath lasts once time starts again. */
	public static int finish(boolean cinema) {
		return cinema ? 28 : 20;
	}

	/** The tick time stops. */
	public static int stop(boolean cinema) {
		return build(cinema) + run(cinema);
	}

	/** The tick time starts again and the clock falls apart. */
	public static int resume(boolean cinema) {
		return stop(cinema) + hold(cinema);
	}

	public static int ticks(boolean cinema) {
		return resume(cinema) + finish(cinema);
	}

	public static double radius(boolean cinema) {
		return cinema ? 10 : 7.5;
	}

	/** The middle of the clock face. */
	public static Vec3 center(Vec3 target, boolean cinema) {
		return target.add(0, cinema ? 14 : 11, 0);
	}

	private static double ease(double x) {
		double u = Math.max(0, Math.min(1, x));
		return u * u * (3 - 2 * u);
	}

	private static Vec3 cube(double size) {
		return new Vec3(size, size, size);
	}

	// ------------------------------------------------------------------ the shape

	/** Gets every straight piece of the clock face: its name, its block, its two ends and its thickness. */
	private interface Pieces {
		void line(String name, String block, Vec3 a, Vec3 b, double thick);
	}

	private static double advance(char ch) {
		return ch == 'I' ? 0.5 : 0.85;
	}

	/** The strokes of a numeral in its own units: x to the right, y up, about one unit tall, centered. */
	private static List<double[]> numeral(int hour) {
		String text = ROMAN[hour - 1];
		double width = 0;
		for (char ch : text.toCharArray()) width += advance(ch);
		double x = -width / 2;
		List<double[]> out = new ArrayList<>();
		for (char ch : text.toCharArray()) {
			double cx = x + advance(ch) / 2;
			switch (ch) {
				case 'I' -> out.add(new double[] {cx, -0.5, cx, 0.5});
				case 'V' -> {
					out.add(new double[] {cx - 0.32, 0.5, cx, -0.5});
					out.add(new double[] {cx + 0.32, 0.5, cx, -0.5});
				}
				default -> {
					out.add(new double[] {cx - 0.32, 0.5, cx + 0.32, -0.5});
					out.add(new double[] {cx + 0.32, 0.5, cx - 0.32, -0.5});
				}
			}
			x += advance(ch);
		}
		return out;
	}

	/** A point of the clock face: {@code u} to the right of the middle as the viewers see it, {@code v} up. */
	private static Vec3 at(Vec3 c, Vec3 along, double u, double v) {
		return new Vec3(c.x + along.x * u, c.y + v, c.z + along.z * u);
	}

	/** The fixed parts: the two rims, the hour marks and the Roman numerals. */
	private static void face(Pieces out, Vec3 c, Vec3 along, double radius) {
		int segments = 24;
		for (int k = 0; k < segments; k++) {
			double a1 = k * Math.PI * 2 / segments;
			double a2 = (k + 1.15) * Math.PI * 2 / segments;
			out.line("o" + k, "minecraft:gold_block", at(c, along, Math.sin(a1) * radius, Math.cos(a1) * radius), at(c, along, Math.sin(a2) * radius, Math.cos(a2) * radius), 0.55);
			out.line("i" + k, "minecraft:orange_concrete", at(c, along, Math.sin(a1) * radius * 0.86, Math.cos(a1) * radius * 0.86),
				at(c, along, Math.sin(a2) * radius * 0.86, Math.cos(a2) * radius * 0.86), 0.3);
		}
		double scale = radius * 0.14;
		for (int h = 1; h <= 12; h++) {
			double a = h * Math.PI / 6;
			double sin = Math.sin(a);
			double cos = Math.cos(a);
			out.line("t" + h, "minecraft:yellow_concrete", at(c, along, sin * radius * 0.88, cos * radius * 0.88), at(c, along, sin * radius * 0.98, cos * radius * 0.98), 0.45);
			double cu = sin * radius * 0.66;
			double cv = cos * radius * 0.66;
			List<double[]> strokes = numeral(h);
			for (int i = 0; i < strokes.size(); i++) {
				double[] s = strokes.get(i);
				out.line("n" + h + "_" + i, "minecraft:yellow_concrete", at(c, along, cu + s[0] * scale, cv + s[1] * scale), at(c, along, cu + s[2] * scale, cv + s[3] * scale), 0.28);
			}
		}
	}

	private static final double[] HAND_LENGTH = {0.45, 0.7, 0.8};
	private static final double[] HAND_THICK = {0.55, 0.38, 0.16};
	private static final double[] HAND_TURNS = {1, 3, 6};
	private static final String[] HAND_BLOCK = {"minecraft:gold_block", "minecraft:gold_block", "minecraft:red_concrete"};

	private static Vec3 handTip(int hand, Vec3 c, Vec3 along, double radius, double angle) {
		double l = radius * HAND_LENGTH[hand];
		return at(c, along, Math.sin(angle) * l, Math.cos(angle) * l);
	}

	/** The angle of a hand: it turns a whole number of times, faster and faster, so it ends pointing at twelve. */
	private static double handAngle(int hand, int t, boolean cinema) {
		double u = Math.max(0, Math.min(1, (t - build(cinema)) / (double) run(cinema)));
		return Math.PI * 2 * HAND_TURNS[hand] * u * u;
	}

	private static int barsA(boolean cinema) {
		return cinema ? 16 : 12;
	}

	private static int barsB(boolean cinema) {
		return cinema ? 20 : 14;
	}

	/** How far a ring has turned at tick {@code t}. */
	private static double ringTurn(int t, boolean cinema, double dir) {
		double tau = Math.max(0, t - build(cinema));
		return dir * (0.012 * tau + 0.0007 * tau * tau);
	}

	/** The two ends of bar {@code k} of a ring on the clock plane. */
	private static Vec3[] barEnds(int k, int n, double radius, double turn, Vec3 c, Vec3 along) {
		double step = Math.PI * 2 / n;
		double a1 = k * step + turn;
		double a2 = a1 + step * 0.55;
		return new Vec3[] {at(c, along, Math.sin(a1) * radius, Math.cos(a1) * radius), at(c, along, Math.sin(a2) * radius, Math.cos(a2) * radius)};
	}

	/** The two ends of bar {@code k} when the ring has locked flat around the target. */
	private static Vec3[] lockedEnds(int k, int n, double radius, double height, Vec3 target, double turn) {
		double step = Math.PI * 2 / n;
		double a1 = k * step + turn;
		double a2 = a1 + step * 0.55;
		return new Vec3[] {new Vec3(target.x + Math.cos(a1) * radius, target.y + height, target.z + Math.sin(a1) * radius),
			new Vec3(target.x + Math.cos(a2) * radius, target.y + height, target.z + Math.sin(a2) * radius)};
	}

	// ------------------------------------------------------------------ the clock

	/**
	 * @param face the flat unit vector that points from the clock toward the people who look at it
	 */
	public static void cronos(ServerLevel level, List<ServerPlayer> viewers, Props props, Vec3 target, Vec3 face, int t, boolean cinema) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int build = build(cinema);
		int run = run(cinema);
		int stop = stop(cinema);
		int resume = resume(cinema);
		int end = ticks(cinema);
		double radius = radius(cinema);
		double domeR = cinema ? 40 : 28;
		Vec3 c = center(target, cinema);
		Vec3 along = Shapes.rightOf(face);
		Vec3 ground = target.add(0, 0.3, 0);
		int na = barsA(cinema);
		int nb = barsB(cinema);
		if (t >= end) return;

		if (t == 0) {
			ExplosionArt.sound(level, target, SoundEvents.BEACON_ACTIVATE, 4.0f, 0.6f);
			ExplosionArt.sound(level, target, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.8f);
			// everything is born collapsed along its length and drawn by the reshape of the next tick
			face((name, block, a, b, thick) -> props.lifetime(props.name(name, Shapes.lineGrowing(props, block, a, b, thick, true)), end - 1), c, along, radius);
			for (int hand = 0; hand < 3; hand++) {
				props.lifetime(props.name("hand" + hand, Shapes.lineGrowing(props, HAND_BLOCK[hand], c, handTip(hand, c, along, radius, 0), HAND_THICK[hand], true)), end - 1);
			}
			for (int k = 0; k < na; k++) {
				Vec3[] e = barEnds(k, na, radius * 1.12, 0, c, along);
				props.lifetime(props.name("ra" + k, Shapes.lineGrowing(props, "minecraft:yellow_concrete", e[0], e[1], 0.5, true)), end - 1);
			}
			for (int k = 0; k < nb; k++) {
				Vec3[] e = barEnds(k, nb, radius * 1.28, 0, c, along);
				props.lifetime(props.name("rb" + k, Shapes.lineGrowing(props, "minecraft:orange_concrete", e[0], e[1], 0.4, true)), end - 1);
			}
		}
		if (t == 1) {
			int draw = Math.max(6, build - 6);
			face((name, block, a, b, thick) -> Shapes.growLine(props, props.named(name), a, b, thick, draw), c, along, radius);
			for (int hand = 0; hand < 3; hand++) Shapes.growLine(props, props.named("hand" + hand), c, handTip(hand, c, along, radius, 0), HAND_THICK[hand], draw);
			for (int k = 0; k < na; k++) {
				Vec3[] e = barEnds(k, na, radius * 1.12, 0, c, along);
				Shapes.growLine(props, props.named("ra" + k), e[0], e[1], 0.5, draw);
			}
			for (int k = 0; k < nb; k++) {
				Vec3[] e = barEnds(k, nb, radius * 1.28, 0, c, along);
				Shapes.growLine(props, props.named("rb" + k), e[0], e[1], 0.4, draw);
			}
		}
		if (t < build && t % 3 == 0) {
			// gold sparks as the clock is drawn
			double a = rnd.nextDouble() * Math.PI * 2;
			Vec3 spark = at(c, along, Math.sin(a) * radius, Math.cos(a) * radius);
			ExplosionArt.emit(level, viewers, GOLD, spark, 6, 0.5, 0.02);
		}
		if (t >= build && t < stop) {
			int tau = t - build;
			double u = tau / (double) run;
			// tick, tock: faster and faster
			double phase = tau / 10.0 + 0.2 * tau * tau / run;
			double before = (tau - 1) / 10.0 + 0.2 * (tau - 1) * (tau - 1) / run;
			if (tau > 0 && (int) Math.floor(phase) != (int) Math.floor(before)) {
				boolean tock = (int) Math.floor(phase) % 2 == 1;
				ExplosionArt.sound(level, c, SoundEvents.NOTE_BLOCK_CHIME, 2.0f, (tock ? 0.8f : 1.2f) + (float) (0.6 * u));
			}
			if (t % 2 == 0) {
				for (int hand = 0; hand < 3; hand++) {
					Shapes.growLine(props, props.named("hand" + hand), c, handTip(hand, c, along, radius, handAngle(hand, t + 2, cinema)), HAND_THICK[hand], 2);
				}
				double ta = ringTurn(t + 2, cinema, 1);
				double tb = ringTurn(t + 2, cinema, -1);
				for (int k = 0; k < na; k++) {
					Vec3[] e = barEnds(k, na, radius * 1.12, ta, c, along);
					Shapes.growLine(props, props.named("ra" + k), e[0], e[1], 0.5, 2);
				}
				for (int k = 0; k < nb; k++) {
					Vec3[] e = barEnds(k, nb, radius * 1.28, tb, c, along);
					Shapes.growLine(props, props.named("rb" + k), e[0], e[1], 0.4, 2);
				}
			}
			if (t % 4 == 0) {
				double a = rnd.nextDouble() * Math.PI * 2;
				ExplosionArt.emit(level, viewers, GOLD, at(c, along, Math.sin(a) * radius * 1.2, Math.cos(a) * radius * 1.2), 4, 0.6, 0.03);
			}
		}
		if (t == stop) {
			// twelve o'clock: time stops
			ExplosionArt.sound(level, target, SoundEvents.END_PORTAL_SPAWN, 6.0f, 0.4f);
			ExplosionArt.sound(level, target, SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 1.6f);
			ExplosionArt.sound(level, c, SoundEvents.AMETHYST_BLOCK_CHIME, 6.0f, 0.5f);
			Props.Prop flash = props.item("minecraft:firework_star", c, 3.0);
			props.resize(flash, cinema ? 40.0 : 26.0, 3);
			props.lifetime(flash, t + 5);
			// the pale shell sweeps over the arena
			for (int i = 0; i < TURNS.length; i++) {
				props.lifetime(props.name("still" + i, props.block("minecraft:light_gray_stained_glass", target, cube(-2.0), TURNS[i], true)), resume + 4);
			}
			// the target is caught in a cage of ice-blue glass, and the rings lock around it
			props.lifetime(props.name("cage", props.block("minecraft:light_blue_stained_glass", target.add(0, 1.15, 0), new Vec3(1.5, 2.4, 1.5), Q.ID, true)), resume);
			double lockA = ringTurn(stop, cinema, 1);
			double lockB = ringTurn(stop, cinema, -1);
			for (int k = 0; k < na; k++) {
				Vec3[] e = lockedEnds(k, na, 2.4, 0.5, target, lockA);
				props.reshape(props.named("ra" + k), e[0].add(e[1]).scale(0.5), new Vec3(0.4, 0.4, Math.max(0.2, e[0].distanceTo(e[1]))), Q.fromZ(e[1].subtract(e[0])), 8);
			}
			for (int k = 0; k < nb; k++) {
				Vec3[] e = lockedEnds(k, nb, 3.2, 1.6, target, lockB);
				props.reshape(props.named("rb" + k), e[0].add(e[1]).scale(0.5), new Vec3(0.3, 0.3, Math.max(0.2, e[0].distanceTo(e[1]))), Q.fromZ(e[1].subtract(e[0])), 8);
			}
			ExplosionArt.shockwave(level, viewers, ICE, ground, 120, 2.6, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.END_ROD, ground, 90, 2.2, 0.02);
			ExplosionArt.blast(level, viewers, ParticleTypes.SNOWFLAKE, target.add(0, 1.2, 0), 80, 0.8, false);
			ExplosionArt.blast(level, viewers, GOLD, c, 60, 1.0, false);
		}
		if (t == stop + 1) {
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("still" + i), target, cube(-2.0 * domeR), TURNS[i], 10);
		}
		if (t > stop && t < resume) {
			// frozen: snow hangs in the air around the target
			if (t % 2 == 0) {
				ExplosionArt.emit(level, viewers, ParticleTypes.SNOWFLAKE, target.x, target.y + 1.5, target.z, 6, 4.0, 2.5, 4.0, 0.0);
				ExplosionArt.emit(level, viewers, ICE, target.x, target.y + 1.2, target.z, 3, 0.6, 1.0, 0.6, 0.0);
			}
			if (t == stop + hold(cinema) / 2) ExplosionArt.sound(level, target, SoundEvents.AMETHYST_BLOCK_RESONATE, 4.0f, 0.6f);
		}
		if (t == resume) {
			// time starts again with a crack, and the clock falls apart
			ExplosionArt.sound(level, target, SoundEvents.GENERIC_EXPLODE, 6.0f, 0.7f);
			ExplosionArt.sound(level, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 6.0f, 1.0f);
			for (int i = 0; i < TURNS.length; i++) props.reshape(props.named("still" + i), target, cube(-2.0), TURNS[i], 6);
			Props.Prop flash = props.item("minecraft:firework_star", target.add(0, 2, 0), 3.0);
			props.resize(flash, cinema ? 44.0 : 28.0, 3);
			props.lifetime(flash, t + 5);
			ExplosionArt.shockwave(level, viewers, GOLD, ground, 100, 2.4, 0.03);
			ExplosionArt.shockwave(level, viewers, ParticleTypes.CLOUD, ground, 80, 1.6, 0.04);
			ExplosionArt.blast(level, viewers, ParticleTypes.END_ROD, c, 90, 1.4, false);
			ExplosionArt.firework(level, c.add(0, 4, 0), FireworkExplosion.Shape.LARGE_BALL, new int[] {0xFFD060, 0xFFFFFF}, new int[] {0x6A4A00});
			ExplosionArt.firework(level, c.add(0, 10, 0), FireworkExplosion.Shape.STAR, new int[] {0xBFE8FF, 0xFFFFFF}, new int[] {0x4060C0});
			List<String> names = new ArrayList<>();
			face((name, block, a, b, thick) -> names.add(name), c, along, radius);
			for (int hand = 0; hand < 3; hand++) names.add("hand" + hand);
			for (int k = 0; k < na; k++) names.add("ra" + k);
			for (int k = 0; k < nb; k++) names.add("rb" + k);
			for (String name : names) {
				Props.Prop piece = props.named(name);
				if (piece == null) continue;
				Vec3 out = new Vec3(piece.pos().x - c.x, 0, piece.pos().z - c.z);
				Vec3 dir = out.lengthSqr() < 1e-4 ? new Vec3(rnd.nextDouble() - 0.5, 0, rnd.nextDouble() - 0.5) : out.normalize();
				double sp = 0.1 + 0.3 * rnd.nextDouble();
				props.throwPiece(piece, new Vec3(dir.x * sp + (rnd.nextDouble() - 0.5) * 0.2, 0.1 + 0.25 * rnd.nextDouble(), dir.z * sp + (rnd.nextDouble() - 0.5) * 0.2), target.y + 0.15);
			}
		}
		if (t > resume && t < resume + 20 && t % 2 == 0) {
			ExplosionArt.emit(level, viewers, GOLD, c.x, c.y - 3, c.z, 8, radius * 0.6, radius * 0.5, radius * 0.6, 0.02);
		}
	}
}
