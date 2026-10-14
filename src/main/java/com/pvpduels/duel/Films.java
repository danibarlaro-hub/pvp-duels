package com.pvpduels.duel;

import com.pvpduels.effects.BeamArt;
import com.pvpduels.effects.ClockArt;
import com.pvpduels.effects.DomainArt;
import com.pvpduels.effects.DomeArt;
import com.pvpduels.effects.ExplosionArt;
import com.pvpduels.effects.GridArt;
import com.pvpduels.effects.Props;
import com.pvpduels.effects.Quality;
import com.pvpduels.effects.SkyArt;
import com.pvpduels.effects.StormArt;
import com.pvpduels.effects.StrikeArt;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The films of the 1v1 finale: for each explosion that has one, where the camera stands and looks at every tick, and what the world does
 * (the explosion itself is drawn by {@code ExplosionArt}, {@code DomeArt} and {@code StrikeArt}; the film only decides when). A film
 * keeps no state: everything it needs is in the {@link Cinema.Scene}.
 * <p>
 * {@code a} is the number of ticks since the viewers got the camera.
 */
final class Films {
	/** One film. */
	interface Film {
		String id();

		/** True for the Domain Expansions (the finale starts with the hands joined). */
		boolean domain();

		/** How long the camera moves. */
		int camTicks();

		/** How long it stays after that, so the last light can be seen. */
		int tail();

		Vec3 camera(Cinema.Scene s, int a);

		Vec3 look(Cinema.Scene s, Vec3 cam, int a);

		void play(Cinema.Scene s, int a);
	}

	// ------------------------------------------------------------------ constants the self-test reads

	/** The Shooting Star: a calm wish first (the camera looks up), then the flight of the star, then the aftermath. */
	static final int STAR_WISH = 40;
	static final int STAR_IMPACT = STAR_WISH + ExplosionArt.CINEMATIC_FLIGHT;
	static final int STAR_TICKS = STAR_IMPACT + ExplosionArt.CINEMATIC_AFTER;
	/** The hands of a Domain Expansion stay joined this long. */
	static final int HANDS = Cinema.HANDS;
	/** Gungnir: the sky is quiet this long before the needle is let go. */
	static final int GUNGNIR_WAIT = 24;
	/** The Seven Stars: the camera is on the arena this long before the first star is born. */
	static final int SEVEN_LEAD = 24;

	static final Film STAR = new StarFilm();
	static final Film GUNGNIR = new GungnirFilm();
	static final Film SEVEN = new SevenFilm();
	static final Film RAIL = new RailFilm();
	static final Film TESSERACT = new TesseractFilm();
	static final Film ECLIPSE = new EclipseFilm();
	static final Film MOON = new MoonFilm();
	static final Film CRONOS = new CronosFilm();
	static final Film ZEUS = new ZeusFilm();
	static final Film ORBITAL = new OrbitalFilm(false);
	static final Film WITHER = new OrbitalFilm(true);
	static final Film HOLE = new HoleFilm();
	static final Film METEORS = new MeteorFilm();
	static final Film SHRINE = new DomainFilm(DomeArt.Variant.SHRINE);
	static final Film VOID = new DomainFilm(DomeArt.Variant.VOID);
	static final Film SWORDS = new DomainFilm(DomeArt.Variant.SWORDS);
	static final Film FROST = new DomainFilm(DomeArt.Variant.FROST);
	static final Film INFERNO = new DomainFilm(DomeArt.Variant.INFERNO);
	static final Film GARDEN = new DomainFilm(DomeArt.Variant.GARDEN);
	static final Film OCEAN = new DomainFilm(DomeArt.Variant.OCEAN);

	private Films() {}

	/** The film that goes with this explosion, or null when it has none (it plays the usual finale). */
	static Film of(String explosion) {
		return switch (explosion) {
			case "shooting_star" -> STAR;
			case "gungnir" -> GUNGNIR;
			case "seven_stars" -> SEVEN;
			case "railgun" -> RAIL;
			case "tesseract" -> TESSERACT;
			case "eclipse" -> ECLIPSE;
			case "moonfall" -> MOON;
			case "cronos" -> CRONOS;
			case "zeus" -> ZEUS;
			case "orbital_strike" -> ORBITAL;
			case "orbital_wither" -> WITHER;
			case "black_hole" -> HOLE;
			case "meteor_shower" -> METEORS;
			case "domain_expansion" -> SHRINE;
			case "domain_void" -> VOID;
			case "domain_swords" -> SWORDS;
			case "domain_frost" -> FROST;
			case "domain_inferno" -> INFERNO;
			case "domain_garden" -> GARDEN;
			case "domain_ocean" -> OCEAN;
			default -> null;
		};
	}

	// ------------------------------------------------------------------ small helpers

	private static Vec3 lerp(Vec3 a, Vec3 b, double k) {
		return a.add(b.subtract(a).scale(k));
	}

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private static Vec3 safe(Vec3 dir) {
		return dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : dir.normalize();
	}

	/** The flat offset {@code rel} from {@code focus}, turned by {@code turn} radians and scaled by {@code grow}, at height {@code y}. */
	private static Vec3 swing(Vec3 focus, Vec3 rel, double turn, double grow, double y) {
		double cos = Math.cos(turn);
		double sin = Math.sin(turn);
		return new Vec3(focus.x + (rel.x * cos - rel.z * sin) * grow, y, focus.z + (rel.x * sin + rel.z * cos) * grow);
	}

	/** A point on a circle around {@code focus}. */
	private static Vec3 around(Vec3 focus, double rho, double phi, double y) {
		return new Vec3(focus.x + Math.cos(phi) * rho, y, focus.z + Math.sin(phi) * rho);
	}

	// ------------------------------------------------------------------ the Shooting Star

	private static final class StarFilm implements Film {
		@Override public String id() { return "shooting_star"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return STAR_TICKS; }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low wide shot from the side that follows the star across the sky, then circles the landing. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 fwd = s.forward();
			Vec3 loser = s.stage[1 - s.win];
			double e = Cinema.ease(Math.min(1.0, a / (double) STAR_IMPACT));
			double lateral = s.half + 9 - 6 * e;
			double along = -s.half * 0.9 + s.half * 1.15 * e;
			double height = 2.2 + 3.8 * e;
			Vec3 at = new Vec3(s.center.x + s.side.x * lateral + fwd.x * along, s.center.y + height, s.center.z + s.side.z * lateral + fwd.z * along);
			if (a <= STAR_IMPACT) return at;
			// after the landing it circles the pentagram and rises
			double p = Cinema.ease(Math.min(1.0, (a - STAR_IMPACT) / (double) ExplosionArt.CINEMATIC_AFTER));
			Vec3 rel = new Vec3(at.x - loser.x, 0, at.z - loser.z);
			double turn = Math.toRadians(70) * p;
			double cos = Math.cos(turn);
			double sin = Math.sin(turn);
			double grow = 1.0 + 0.35 * p;
			Vec3 rotated = new Vec3((rel.x * cos - rel.z * sin) * grow, 0, (rel.x * sin + rel.z * cos) * grow);
			Vec3 pos = new Vec3(loser.x + rotated.x, at.y + 9.0 * p, loser.z + rotated.z);
			int since = a - STAR_IMPACT;
			// the ground shakes when the star lands
			if (since < 16) pos = Cinema.Scene.shake(pos, 0.8 * (1.0 - since / 16.0));
			return pos;
		}

		/** At the winner, up to the birth of the star, along the star, and at the landing. */
		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			Vec3 target;
			if (a < STAR_WISH) {
				Vec3 born = ExplosionArt.cinematicStarStart(s.level, winner, loser);
				double k = Cinema.ease(a / (double) STAR_WISH) * 0.7;
				Vec3 low = winner.add(0, 1.3, 0);
				target = lerp(low, born, k);
			} else if (a <= STAR_IMPACT) {
				Vec3 head = ExplosionArt.cinematicStarHead(s.level, winner, loser, a - STAR_WISH);
				// in the last moments the camera lowers its eyes to the landing
				double b = clamp01((a - (STAR_IMPACT - 16)) / 16.0);
				target = lerp(head, loser.add(0, 2.0, 0), Cinema.ease(b));
			} else {
				double p = Cinema.ease(Math.min(1.0, (a - STAR_IMPACT) / (double) ExplosionArt.CINEMATIC_AFTER));
				target = loser.add(0, 3.0 + 4.0 * p, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0) {
				// the winner looks up and makes a wish
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(-40f);
				s.title(Component.literal("Make a wish").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
					Component.literal(s.duel.names[s.win]).withStyle(ChatFormatting.DARK_GRAY), 10, 30, 10);
			}
			if (a < STAR_WISH) {
				if (a == 6 || a == 18 || a == 30) s.sound(winner.add(0, 2, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 3.0f, 0.7f + a * 0.02f);
				ThreadLocalRandom rnd = ThreadLocalRandom.current();
				for (ServerPlayer v : viewers) {
					Quality.send(s.level, v, ParticleTypes.END_ROD, winner.x + (rnd.nextDouble() - 0.5) * 2.4, winner.y + 0.2, winner.z + (rnd.nextDouble() - 0.5) * 2.4, 2, 0.25, 0.9, 0.25, 0.04);
				}
				return;
			}
			int t = a - STAR_WISH;
			boolean first = s.props == null;
			Props props = s.props();
			if (first && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-70f);
			ExplosionArt.cinematicStar(s.level, viewers, props, winner, loser, t);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			if (t == ExplosionArt.CINEMATIC_FLIGHT) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				// the flash: the camera is swallowed by white sparks for a moment
				s.flash();
				s.title(Component.literal("SHOOTING STAR").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Domain Expansions

	private static final class DomainFilm implements Film {
		private final DomeArt.Variant variant;

		DomainFilm(DomeArt.Variant variant) {
			this.variant = variant;
		}

		@Override public String id() { return variant.id; }
		@Override public boolean domain() { return true; }

		private static int expand() { return DomeArt.expand(true); }
		private static int hold() { return DomeArt.hold(true); }

		/** The camera rises as usual during the hands and the growth of the dome, then each domain has its own shot. */
		@Override public int camTicks() { return HANDS + expand() + hold() + DomeArt.COLLAPSE; }
		@Override public int tail() { return 20; }

		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			int rise = HANDS + expand();
			if (a <= rise) return s.rise(a, rise);
			Vec3 base = s.rise(rise, rise);
			int b = Math.min(a, camTicks()) - rise;
			int h = hold();
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			double floor = s.center.y;
			return switch (variant) {
				case SHRINE -> {
					// it slowly closes in on the loser, from the side; the white cuts shake it a little, and the great cleave a lot
					Vec3 push = new Vec3(loser.x + s.side.x * 9 - fwd.x * 3, floor + 3.4, loser.z + s.side.z * 9 - fwd.z * 3);
					Vec3 pos = lerp(base, push, Cinema.ease(b / (h * 0.85)));
					double shake = b < h - 24 ? 0.04 : 0.0;
					int cleave = h - 24;
					if (b >= cleave && b < cleave + 18) shake = 0.9 * (1 - (b - cleave) / 18.0);
					yield Cinema.Scene.shake(pos, shake);
				}
				case VOID -> {
					// it circles the loser, closes in when the stars rush, and pulls back after the burst
					double rho0 = Math.hypot(base.x - loser.x, base.z - loser.z);
					double phi0 = Math.atan2(base.z - loser.z, base.x - loser.x);
					int rush = h - 44;
					double rho = rho0 + (9.0 - rho0) * Cinema.ease(b / 50.0);
					double y = (base.y - floor) + (4.2 - (base.y - floor)) * Cinema.ease(b / 50.0);
					if (b >= rush) rho = 9.0 + (5.2 - 9.0) * Cinema.ease((b - rush) / 28.0);
					if (b >= rush + 30) {
						double k = Cinema.ease((b - rush - 30) / 34.0);
						rho = 5.2 + (15.0 - 5.2) * k;
						y = 4.2 + (10.0 - 4.2) * k;
					}
					double phi = phi0 + 0.021 * b;
					Vec3 pos = around(loser, rho, phi, floor + y);
					if (b >= rush + 30 && b < rush + 46) pos = Cinema.Scene.shake(pos, 0.7 * (1 - (b - rush - 30) / 16.0));
					yield pos;
				}
				case SWORDS -> {
					// a slow turn around the field that rises a little, then it drops to the loser for the barrage and goes up again
					double rho = Math.hypot(base.x - s.center.x, base.z - s.center.z);
					double phi0 = Math.atan2(base.z - s.center.z, base.x - s.center.x);
					int bar = h - 40;
					Vec3 field = around(s.center, rho, phi0 + 0.012 * Math.min(b, bar), floor + (base.y - floor) + 4.0 * Cinema.ease(Math.min(b, bar) / 70.0));
					Vec3 near = new Vec3(loser.x + s.side.x * 9 - fwd.x * 2, floor + 3.2, loser.z + s.side.z * 9 - fwd.z * 2);
					Vec3 pos = field;
					if (b >= bar) pos = lerp(field, near, Cinema.ease((b - bar) / 30.0));
					if (b >= h - 6) {
						Vec3 wide = new Vec3(s.center.x + s.side.x * (s.half + 6), floor + 14, s.center.z + s.side.z * (s.half + 6));
						pos = lerp(near, wide, Cinema.ease((b - h + 6) / 26.0));
					}
					int wave = h - 26;
					if (b >= wave && b < wave + 14) pos = Cinema.Scene.shake(pos, 0.6 * (1 - (b - wave) / 14.0));
					yield pos;
				}
				// the newer domains: a tour around the arena, a drop to the loser for the finale and a pull back
				case FROST -> tour(s, base, b, h, 1, 0.011, 2.5, 56, 11.0, 4.0, 0.9);
				case INFERNO -> tour(s, base, b, h, -1, 0.010, -3.5, 44, 10.0, 2.6, 1.1);
				case GARDEN -> tour(s, base, b, h, 1, 0.008, 3.0, 44, 8.5, 3.4, 0.6);
				case OCEAN -> tour(s, base, b, h, -1, 0.009, 2.0, 50, 11.0, 3.6, 0.9);
			};
		}

		private DomeArt.Spec specOf(Cinema.Scene s) {
			return new DomeArt.Spec(s.center, s.half, s.stage[s.win], s.stage[1 - s.win], true);
		}

		/**
		 * Circles the arena (turning {@code dir} at {@code speed} radians a tick, rising {@code gain} blocks), drops to the side of the loser
		 * {@code dropBefore} ticks before the end of the hold, shakes at the great moment and goes wide when the dome falls.
		 */
		private Vec3 tour(Cinema.Scene s, Vec3 base, int b, int h, int dir, double speed, double gain, int dropBefore, double nearSide, double nearHeight, double shake) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			double floor = s.center.y;
			double rho = Math.hypot(base.x - s.center.x, base.z - s.center.z);
			double phi0 = Math.atan2(base.z - s.center.z, base.x - s.center.x);
			int bar = h - dropBefore;
			int bb = Math.min(b, bar);
			Vec3 field = around(s.center, rho, phi0 + dir * speed * bb, base.y + gain * Cinema.ease(bb / 70.0));
			Vec3 near = new Vec3(loser.x + s.side.x * nearSide - fwd.x * 2, floor + nearHeight, loser.z + s.side.z * nearSide - fwd.z * 2);
			Vec3 pos = field;
			if (b >= bar) pos = lerp(field, near, Cinema.ease((b - bar) / 30.0));
			if (b >= h - 6) {
				Vec3 wide = new Vec3(s.center.x + s.side.x * (s.half + 6), floor + 14, s.center.z + s.side.z * (s.half + 6));
				pos = lerp(near, wide, Cinema.ease((b - h + 6) / 26.0));
			}
			int great = DomeArt.climax(variant, specOf(s)) - expand();
			if (b >= great && b < great + 14) pos = Cinema.Scene.shake(pos, shake * (1 - (b - great) / 14.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			int rise = HANDS + expand();
			Vec3 standard = s.standardLook(cam);
			if (a <= rise) return standard;
			int b = Math.min(a, camTicks()) - rise;
			Vec3 loser = s.stage[1 - s.win];
			Vec3 target = loser.add(0, 1.3, 0);
			double fieldHeight = switch (variant) {
				case SWORDS -> 2.5;
				case FROST -> 4.0;
				case INFERNO -> 5.0;
				case GARDEN -> 7.0;
				case OCEAN -> 8.0;
				default -> -1.0;
			};
			if (fieldHeight >= 0) {
				// first the field, then the loser
				Vec3 field = s.center.add(0, fieldHeight, 0);
				target = lerp(field, loser.add(0, 1.3, 0), Cinema.ease((b - (hold() - 60)) / 40.0));
			}
			Vec3 toward = safe(target.subtract(cam));
			double k = Cinema.ease(b / 40.0);
			return safe(standard.add(toward.subtract(standard).scale(k)));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			int owner = s.win;
			if (a < HANDS) {
				if (a == 0) {
					s.pose(owner, Pose.CROUCHING);
					s.sound(s.stage[owner], SoundEvents.BEACON_ACTIVATE, 2.0f, 0.6f);
				}
				DomainArt.swirl(s.level, viewers, s.hands(owner), ParticleTypes.ENCHANT, a);
				DomainArt.swirl(s.level, viewers, s.hands(owner), ParticleTypes.SOUL_FIRE_FLAME, a + 7);
				return;
			}
			if (a == HANDS) {
				s.pose(owner, Pose.STANDING);
				s.title(Component.literal("Domain Expansion: ").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)
						.append(Component.literal(variant.title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)),
					Component.literal(s.duel.names[owner]).withStyle(ChatFormatting.GRAY), 5, 70, 15);
			}
			int t = a - HANDS;
			Props props = s.props();
			DomeArt.Spec spec = new DomeArt.Spec(s.center, s.half, s.stage[owner], s.stage[1 - owner], true);
			DomeArt.play(s.level, viewers, props, variant, spec, t);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			int e = expand();
			int h = hold();
			// the great moment of each domain blinds the camera for a moment
			if (t == DomeArt.climax(variant, spec)) s.flash();
		}
	}

	// ------------------------------------------------------------------ Gungnir

	private static final class GungnirFilm implements Film {
		@Override public String id() { return "gungnir"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return GUNGNIR_WAIT + StrikeArt.gungnirTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		private static Vec3 base(Cinema.Scene s) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			return new Vec3(loser.x + s.side.x * 18 - fwd.x * 9, s.center.y + 2.4, loser.z + s.side.z * 18 - fwd.z * 9);
		}

		/** A low shot from the side, still while the needle falls; after the landing it circles the crater and rises. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 base = base(s);
			int t = a - GUNGNIR_WAIT;
			int fall = StrikeArt.gungnirFall(true);
			Vec3 fwd = s.forward();
			Vec3 still = base.add(fwd.scale(0.03 * Math.min(a, GUNGNIR_WAIT + fall)));
			if (t <= fall) return still;
			double u = Cinema.ease(Math.min(1.0, (t - fall) / (double) StrikeArt.gungnirAfter(true)));
			Vec3 rel = new Vec3(still.x - loser.x, 0, still.z - loser.z);
			double turn = Math.toRadians(70) * u;
			double cos = Math.cos(turn);
			double sin = Math.sin(turn);
			double grow = 1.0 + 0.4 * u;
			Vec3 pos = new Vec3(loser.x + (rel.x * cos - rel.z * sin) * grow, still.y + 9.0 * u, loser.z + (rel.x * sin + rel.z * cos) * grow);
			int since = t - fall;
			double shake = since < 16 ? 1.0 * (1 - since / 16.0) : 0;
			int delay = StrikeArt.gungnirDelay(true);
			if (since >= delay && since < delay + 14) shake = 0.6 * (1 - (since - delay) / 14.0);
			return Cinema.Scene.shake(pos, shake);
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			int t = a - GUNGNIR_WAIT;
			int fall = StrikeArt.gungnirFall(true);
			Vec3 target;
			if (t < 0) {
				Vec3 sky = loser.add(0, StrikeArt.gungnirHeight(true), 0);
				target = lerp(winner.add(0, 1.3, 0), sky, Cinema.ease(a / (double) GUNGNIR_WAIT) * 0.6);
			} else if (t <= fall) {
				Vec3 head = StrikeArt.gungnirHead(loser, t, true);
				double b = clamp01((t - (fall - 14)) / 14.0);
				target = lerp(head, loser.add(0, 2.0, 0), Cinema.ease(b));
			} else {
				double u = Cinema.ease(Math.min(1.0, (t - fall) / (double) StrikeArt.gungnirAfter(true)));
				target = loser.add(0, 3.0 + 5.0 * u, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-45f);
			int t = a - GUNGNIR_WAIT;
			if (t < 0) return;
			Props props = s.props();
			StrikeArt.gungnir(s.level, viewers, props, loser, t, true);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			int fall = StrikeArt.gungnirFall(true);
			if (t == fall) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				s.flash();
			}
			if (t == fall + StrikeArt.gungnirDelay(true)) {
				// the thunder arrives, and the title with it
				s.title(Component.literal("GUNGNIR").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Seven Stars

	private static final class SevenFilm implements Film {
		@Override public String id() { return "seven_stars"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return SEVEN_LEAD + StrikeArt.sevenTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** The middle of the Big Dipper that is drawn on the floor (its x axis is the side of the arena, so it does not land on the winner). */
		private static Vec3 figure(Cinema.Scene s) {
			Vec3 bowl = s.stage[1 - s.win];
			return new Vec3(bowl.x - s.side.x * 5.0, bowl.y, bowl.z - s.side.z * 5.0);
		}

		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 focus = figure(s);
			int t = a - SEVEN_LEAD;
			int ignite = StrikeArt.sevenIgnite(true);
			double k = Cinema.ease(clamp01((t - 60) / (double) (ignite - 60)));
			double rho = 26 - 7 * k;
			double y = s.center.y + 12 + 22 * k;
			double phi0 = Math.atan2(s.side.z, s.side.x);
			double phi = phi0 + 0.0085 * Math.max(0, Math.min(a, camTicks()));
			return around(focus, rho, phi, y);
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 focus = figure(s);
			int t = a - SEVEN_LEAD;
			int ignite = StrikeArt.sevenIgnite(true);
			double k = Cinema.ease(clamp01((t - 60) / (double) (ignite - 60)));
			return safe(focus.add(0, 7.0 * (1 - k) + 0.5, 0).subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 bowl = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-45f);
			int t = a - SEVEN_LEAD;
			if (t < 0) return;
			Props props = s.props();
			StrikeArt.sevenStars(s.level, viewers, props, bowl, s.side, t, true);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			if (t == StrikeArt.sevenIgnite(true)) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				s.flash();
				s.title(Component.literal("SEVEN STARS").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 60, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Railgun

	private static final class RailFilm implements Film {
		/** The camera climbs this long, from the floor to above the clouds. */
		private static final int CLIMB = 70;
		/** The height of the camera above the clouds. */
		private static final double TOP = 78;

		@Override public String id() { return "railgun"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return BeamArt.railTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** Climbs from beside the winner to above the clouds, rides the head of the beam down, and pulls back from the crater. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			int sight = BeamArt.railSight(true);
			int impact = BeamArt.railImpact(true);
			double floor = s.center.y;
			Vec3 high = new Vec3(loser.x + s.side.x * 10 - fwd.x * 4, 0, loser.z + s.side.z * 10 - fwd.z * 4);
			Vec3 start = new Vec3(winner.x + s.side.x * 4, 0, winner.z + s.side.z * 4);
			if (a < sight) {
				double u = Cinema.ease(Math.min(1.0, a / (double) CLIMB));
				double y = floor + 1.8 + (TOP - 1.8) * u;
				Vec3 flat = lerp(start, high, Cinema.ease(clamp01(a / (double) sight)));
				return new Vec3(flat.x, y, flat.z);
			}
			Vec3 near = new Vec3(loser.x + s.side.x * 7, 0, loser.z + s.side.z * 7);
			if (a < impact) {
				// the camera falls with the head of the beam, a few blocks above it and off to the side
				double u = clamp01((a - sight) / (double) BeamArt.railFall(true));
				Vec3 head = BeamArt.railHead(loser, a, true);
				Vec3 flat = lerp(high, near, Cinema.ease(u));
				double y = Math.max(floor + 3.0, head.y + 6.0 - 3.0 * u);
				return new Vec3(flat.x, y, flat.z);
			}
			int b = a - impact;
			double e = Cinema.ease(Math.min(1.0, b / (double) BeamArt.railAfter(true)));
			Vec3 pos = new Vec3(loser.x + s.side.x * (7 + 15 * e) - fwd.x * 6 * e, floor + 3.0 + 7.0 * e, loser.z + s.side.z * (7 + 15 * e) - fwd.z * 6 * e);
			if (b < 16) pos = Cinema.Scene.shake(pos, 1.0 * (1 - b / 16.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			int sight = BeamArt.railSight(true);
			int impact = BeamArt.railImpact(true);
			Vec3 target;
			if (a < sight) {
				// first the camera looks up at the clouds it is about to break through, then down at the arena
				Vec3 sky = cam.add(0, 30, 0);
				target = lerp(sky, loser.add(0, 1.0, 0), Cinema.ease(clamp01((a - 24) / 46.0)));
			} else if (a < impact) {
				Vec3 head = BeamArt.railHead(loser, a, true);
				double b = clamp01((a - (impact - 8)) / 8.0);
				target = lerp(head, loser.add(0, 1.5, 0), Cinema.ease(b));
			} else {
				double e = Cinema.ease(Math.min(1.0, (a - impact) / (double) BeamArt.railAfter(true)));
				target = loser.add(0, 2.5 + 3.0 * e, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-30f);
			Props props = s.props();
			BeamArt.railgun(s.level, viewers, props, loser, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == BeamArt.railSight(true) && s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
			if (a == BeamArt.railImpact(true)) {
				s.flash();
				s.title(Component.literal("RAILGUN").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Tesseract

	private static final class TesseractFilm implements Film {
		@Override public String id() { return "tesseract"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return GridArt.tesseractTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** A slow turn around the cube that starts low and wide, closes in and rises until the flash, and then pulls back. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			int c = GridArt.climax(true);
			int total = camTicks();
			double phi = Math.atan2(s.side.z, s.side.x) + 0.012 * Math.min(a, total);
			double k = Cinema.ease(Math.min(1.0, a / (double) c));
			double rho = 24 - 9 * k;
			double y = 2.5 + 6.5 * k;
			if (a > c) {
				double p = Cinema.ease(Math.min(1.0, (a - c) / (double) (total - c)));
				rho = 15 + 11 * p;
				y = 9 + 5 * p;
			}
			Vec3 pos = around(loser, rho, phi, s.center.y + y);
			int since = a - c;
			if (since >= 0 && since < 14) pos = Cinema.Scene.shake(pos, 0.8 * (1 - since / 14.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 cube = GridArt.center(loser, true);
			int folded = GridArt.climax(true) + GridArt.collapse(true);
			double k = Cinema.ease(clamp01((a - folded) / 10.0));
			Vec3 target = lerp(cube, loser.add(0, 3.0, 0), k);
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			Props props = s.props();
			GridArt.tesseract(s.level, viewers, props, loser, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == GridArt.climax(true)) {
				s.flash();
				s.title(Component.literal("TESSERACT").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Eclipse

	private static final class EclipseFilm implements Film {
		@Override public String id() { return "eclipse"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return SkyArt.eclipseTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low shot that looks up at the black disk, then pulls out to see the beams come down, then circles the crater. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			double floor = s.center.y;
			int strike = SkyArt.eclipseStrike(true);
			int impact = SkyArt.eclipseImpact(true);
			Vec3 p0 = new Vec3(winner.x + s.side.x * 5 - fwd.x * 2, floor + 1.6, winner.z + s.side.z * 5 - fwd.z * 2);
			Vec3 p1 = new Vec3(loser.x + s.side.x * 16 - fwd.x * 8, floor + 2.2, loser.z + s.side.z * 16 - fwd.z * 8);
			Vec3 p2 = new Vec3(loser.x + s.side.x * 22 - fwd.x * 10, floor + 5.0, loser.z + s.side.z * 22 - fwd.z * 10);
			if (a < strike) return lerp(p0, p1, Cinema.ease(clamp01(a / (double) strike)));
			if (a < impact) return lerp(p1, p2, Cinema.ease(clamp01((a - strike) / (double) SkyArt.eclipseBeams(true))));
			int b = a - impact;
			double e = Cinema.ease(Math.min(1.0, b / (double) SkyArt.eclipseAfter(true)));
			Vec3 rel = new Vec3(p2.x - loser.x, 0, p2.z - loser.z);
			double turn = Math.toRadians(60) * e;
			double cos = Math.cos(turn);
			double sin = Math.sin(turn);
			double grow = 1.0 + 0.3 * e;
			Vec3 pos = new Vec3(loser.x + (rel.x * cos - rel.z * sin) * grow, p2.y + 9.0 * e, loser.z + (rel.x * sin + rel.z * cos) * grow);
			if (b < 14) pos = Cinema.Scene.shake(pos, 0.9 * (1 - b / 14.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 disk = SkyArt.eclipseCenter(loser, true);
			int sweep = SkyArt.eclipseSweep(true);
			int strike = SkyArt.eclipseStrike(true);
			int impact = SkyArt.eclipseImpact(true);
			Vec3 target;
			if (a < strike) {
				target = lerp(loser.add(0, 8, 0), disk, Cinema.ease(clamp01(a / (double) sweep)));
			} else if (a < impact) {
				target = lerp(disk, loser.add(0, 1.0, 0), Cinema.ease(clamp01((a - strike) / (double) SkyArt.eclipseBeams(true))));
			} else {
				double e = Cinema.ease(Math.min(1.0, (a - impact) / (double) SkyArt.eclipseAfter(true)));
				target = loser.add(0, 2.5 + 3.0 * e, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-35f);
			Props props = s.props();
			SkyArt.eclipse(s.level, viewers, props, loser, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == SkyArt.eclipseStrike(true) && s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
			if (a == SkyArt.eclipseImpact(true)) {
				s.flash();
				s.title(Component.literal("ECLIPSE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Moonfall

	private static final class MoonFilm implements Film {
		@Override public String id() { return "moonfall"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return SkyArt.moonTicks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low shot from the side that backs away a little while the moon comes down; after the landing it circles the crater and rises. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			int land = SkyArt.moonLand(true);
			double floor = s.center.y;
			double e = Cinema.ease(clamp01(a / (double) land));
			Vec3 still = new Vec3(loser.x + s.side.x * (26 + 5 * e) - fwd.x * 6, floor + 2.4 + 1.4 * e, loser.z + s.side.z * (26 + 5 * e) - fwd.z * 6);
			if (a <= land) return still;
			double u = Cinema.ease(Math.min(1.0, (a - land) / (double) SkyArt.moonAfter(true)));
			Vec3 rel = new Vec3(still.x - loser.x, 0, still.z - loser.z);
			double turn = Math.toRadians(70) * u;
			double cos = Math.cos(turn);
			double sin = Math.sin(turn);
			double grow = 1.0 + 0.4 * u;
			Vec3 pos = new Vec3(loser.x + (rel.x * cos - rel.z * sin) * grow, still.y + 9.0 * u, loser.z + (rel.x * sin + rel.z * cos) * grow);
			int since = a - land;
			if (since < 18) pos = Cinema.Scene.shake(pos, 1.2 * (1 - since / 18.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			int land = SkyArt.moonLand(true);
			Vec3 target;
			if (a <= land) {
				Vec3 moon = SkyArt.moonCenter(loser, a, true);
				double b = clamp01((a - (land - 16)) / 16.0);
				target = lerp(moon, loser.add(0, 4.0, 0), Cinema.ease(b));
			} else {
				double u = Cinema.ease(Math.min(1.0, (a - land) / (double) SkyArt.moonAfter(true)));
				target = loser.add(0, 3.0 + 5.0 * u, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-45f);
			Props props = s.props();
			SkyArt.moonfall(s.level, viewers, props, loser, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == SkyArt.moonLand(true)) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				s.flash();
				s.title(Component.literal("MOONFALL").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Cronos Clock

	private static final class CronosFilm implements Film {
		/** How long the camera takes to fall from the wide view of the clock to the frozen target. */
		private static final int DIVE = 14;

		@Override public String id() { return "cronos"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return ClockArt.ticks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		private static Vec3 flatAround(Vec3 loser, Vec3 side, double rho, double turn, double y) {
			double phi = Math.atan2(side.z, side.x) + turn;
			return around(loser, rho, phi, y);
		}

		/** Face to face with the clock while it draws itself and speeds up, then a fast fall to the frozen target and a slow turn around it. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			double floor = s.center.y;
			int stop = ClockArt.stop(true);
			int resume = ClockArt.resume(true);
			Vec3 wide0 = flatAround(loser, s.side, 30, 0, floor + 2.0);
			Vec3 wide1 = flatAround(loser, s.side, 24, 0, floor + 11.0);
			Vec3 close = flatAround(loser, s.side, 9, 0, floor + 2.6);
			if (a < stop) return lerp(wide0, wide1, Cinema.ease(clamp01(a / (double) stop)));
			if (a < stop + DIVE) return lerp(wide1, close, Cinema.ease((a - stop) / (double) DIVE));
			if (a < resume) {
				double k = clamp01((a - stop - DIVE) / (double) (resume - stop - DIVE));
				return flatAround(loser, s.side, 9 + 2 * k, 0.015 * (a - stop - DIVE), floor + 2.6 + 0.8 * k);
			}
			Vec3 last = flatAround(loser, s.side, 11, 0.015 * (resume - stop - DIVE), floor + 3.4);
			Vec3 far = flatAround(loser, s.side, 26, 0.015 * (resume - stop - DIVE) * 0.5, floor + 12.0);
			double e = Cinema.ease(clamp01((a - resume) / (double) ClockArt.finish(true)));
			Vec3 pos = lerp(last, far, e);
			if (a - resume < 12) pos = Cinema.Scene.shake(pos, 0.7 * (1 - (a - resume) / 12.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 clock = ClockArt.center(loser, true);
			int stop = ClockArt.stop(true);
			int resume = ClockArt.resume(true);
			Vec3 frozen = loser.add(0, 1.6, 0);
			Vec3 target;
			if (a < stop) target = lerp(loser.add(0, 6, 0), clock, Cinema.ease(clamp01(a / 28.0)));
			else if (a < stop + DIVE) target = lerp(clock, frozen, Cinema.ease((a - stop) / (double) DIVE));
			else if (a < resume) target = frozen;
			else target = lerp(frozen, clock.add(0, -4, 0), Cinema.ease(clamp01((a - resume) / 16.0)));
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			Props props = s.props();
			ClockArt.cronos(s.level, viewers, props, loser, s.side, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == ClockArt.stop(true)) {
				s.flash();
				s.title(Component.literal("CRONOS CLOCK").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " stops time").withStyle(ChatFormatting.WHITE), 0, 60, 20);
			}
		}
	}

	// ------------------------------------------------------------------ Zeus Storm

	private static final class ZeusFilm implements Film {
		@Override public String id() { return "zeus"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return StormArt.ticks(true); }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low wide shot that circles slowly under the black clouds and closes in during the storm; it shakes at the great bolt and then rises. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			double floor = s.center.y;
			int strike = StormArt.strike(true);
			double k = Cinema.ease(clamp01(a / (double) strike));
			double rho = 24 - 6 * k;
			double y = 2.2 + 2.0 * k;
			double phi = Math.atan2(s.side.z, s.side.x) + 0.006 * Math.min(a, strike);
			if (a > strike) {
				double p = Cinema.ease(Math.min(1.0, (a - strike) / (double) StormArt.after(true)));
				rho = 18 + 10 * p;
				y = 4.2 + 8.0 * p;
				phi += 0.004 * (a - strike);
			}
			Vec3 pos = around(loser, rho, phi, floor + y);
			int since = a - strike;
			if (since >= 0 && since < 16) pos = Cinema.Scene.shake(pos, 1.1 * (1 - since / 16.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 loser = s.stage[1 - s.win];
			int gather = StormArt.gather(true);
			int storm = StormArt.storm(true);
			int strike = StormArt.strike(true);
			Vec3 target;
			if (a < gather) {
				target = loser.add(0, 24, 0);
			} else if (a < gather + storm) {
				double k = Cinema.ease(clamp01((a - gather) / (double) storm));
				target = loser.add(0, 18 - 12 * k, 0);
			} else if (a < strike) {
				target = loser.add(0, 6 + 14 * Cinema.ease(clamp01((a - gather - storm) / (double) StormArt.charge(true))), 0);
			} else {
				double e = Cinema.ease(Math.min(1.0, (a - strike) / (double) StormArt.after(true)));
				target = loser.add(0, 10 - 5 * e, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-40f);
			Props props = s.props();
			StormArt.zeus(s.level, viewers, props, loser, a, true);
			for (Vec3 at : props.tick(a)) ExplosionArt.landing(s.level, viewers, at);
			if (a == StormArt.strike(true)) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				s.flash();
				s.title(Component.literal("ZEUS STORM").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Orbital cannons

	private static final class OrbitalFilm implements Film {
		/** The camera is on the winner and the sky this long before the cannon is aimed. */
		private static final int LEAD = 30;
		private static final int AFTER = 96;
		private final boolean wither;

		OrbitalFilm(boolean wither) {
			this.wither = wither;
		}

		@Override public String id() { return wither ? "orbital_wither" : "orbital_strike"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return LEAD + ExplosionArt.ORBITAL_SLAM + AFTER; }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low shot from the side, still while the cannon is aimed; after the blow it circles and rises with the smoke. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			Vec3 base = new Vec3(loser.x + s.side.x * 16 - fwd.x * 8, s.center.y + 2.2, loser.z + s.side.z * 16 - fwd.z * 8);
			int hit = LEAD + ExplosionArt.ORBITAL_SLAM;
			Vec3 still = base.add(fwd.scale(0.03 * Math.min(a, hit)));
			if (a <= hit) return still;
			int b = a - hit;
			double u = Cinema.ease(Math.min(1.0, b / (double) AFTER));
			Vec3 rel = new Vec3(still.x - loser.x, 0, still.z - loser.z);
			Vec3 pos = swing(loser, rel, Math.toRadians(55) * u, 1.0 + 0.5 * u, still.y + 8.0 * u);
			if (b < 14) pos = Cinema.Scene.shake(pos, 1.0 * (1 - b / 14.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			int hit = LEAD + ExplosionArt.ORBITAL_SLAM;
			Vec3 skyLook = lerp(winner.add(0, 1.4, 0), loser.add(0, 40, 0), 0.85);
			Vec3 target;
			if (a < LEAD) {
				target = lerp(winner.add(0, 1.4, 0), loser.add(0, 40, 0), Cinema.ease(a / (double) LEAD) * 0.85);
			} else if (a <= hit) {
				// the sight closes in: the eyes come down the red line to the target
				target = lerp(skyLook, loser.add(0, 2.0, 0), Cinema.ease((a - LEAD) / (double) ExplosionArt.ORBITAL_SLAM));
			} else {
				double u = Cinema.ease(Math.min(1.0, (a - hit) / (double) AFTER));
				target = loser.add(0, 3.0 + 14.0 * u, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-35f);
			if (a < LEAD) {
				if (a == 8 || a == 20) s.sound(loser.add(0, 6, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 3.0f, 0.5f + a * 0.03f);
				return;
			}
			int t = a - LEAD;
			Props props = s.props();
			ExplosionArt.orbital(s.level, viewers, props, loser, t, wither);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			if (t == ExplosionArt.ORBITAL_SLAM) {
				if (s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
				s.flash();
				s.title(Component.literal(wither ? "WITHER CANNON" : "ORBITAL STRIKE").withStyle(wither ? ChatFormatting.DARK_PURPLE : ChatFormatting.RED, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Black Hole

	private static final class HoleFilm implements Film {
		/** The winner raises a hand and the camera looks at the empty sky this long before the hole opens. */
		private static final int LEAD = 26;
		private static final int AFTER = 60;

		@Override public String id() { return "black_hole"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return LEAD + ExplosionArt.HOLE_BURST + AFTER; }
		@Override public int tail() { return Cinema.TAIL; }

		/** A wide shot from the side that turns slowly while the hole grows, trembles when it collapses and pulls back from the burst. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			Vec3 base = new Vec3(loser.x + s.side.x * 20 - fwd.x * 10, s.center.y + 3.0, loser.z + s.side.z * 20 - fwd.z * 10);
			Vec3 rel = new Vec3(base.x - loser.x, 0, base.z - loser.z);
			int t = a - LEAD;
			int collapse = ExplosionArt.HOLE_COLLAPSE;
			int burst = ExplosionArt.HOLE_BURST;
			double k = Cinema.ease(clamp01(t / (double) collapse));
			if (t <= burst) {
				Vec3 pos = swing(loser, rel, Math.toRadians(30) * k, 1.0 - 0.15 * k, base.y + 2.5 * k);
				if (t >= collapse) pos = Cinema.Scene.shake(pos, 0.15 + 0.5 * (t - collapse) / (double) (burst - collapse));
				return pos;
			}
			int b = t - burst;
			double u = Cinema.ease(Math.min(1.0, b / (double) AFTER));
			Vec3 pos = swing(loser, rel, Math.toRadians(30) + Math.toRadians(40) * u, 0.85 + 0.9 * u, base.y + 2.5 + 8.0 * u);
			if (b < 20) pos = Cinema.Scene.shake(pos, 1.3 * (1 - b / 20.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			Vec3 hole = loser.add(0, 6.5, 0);
			int t = a - LEAD;
			int burst = ExplosionArt.HOLE_BURST;
			Vec3 target;
			if (t < 0) {
				target = lerp(winner.add(0, 1.3, 0), hole, Cinema.ease(a / (double) LEAD) * 0.75);
			} else if (t <= burst) {
				Vec3 early = lerp(winner.add(0, 1.3, 0), hole, 0.75);
				target = lerp(early, hole, Cinema.ease(clamp01(t / 12.0)));
			} else {
				double u = Cinema.ease(Math.min(1.0, (t - burst) / (double) AFTER));
				target = lerp(hole, loser.add(0, 3.0, 0), u);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-25f);
			if (a < LEAD) {
				if (a == 6 || a == 16) s.sound(loser.add(0, 6.5, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 3.0f, 0.4f + a * 0.02f);
				return;
			}
			int t = a - LEAD;
			Props props = s.props();
			ExplosionArt.blackHole(s.level, viewers, props, loser, t);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			if (t == ExplosionArt.HOLE_COLLAPSE && s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
			if (t == ExplosionArt.HOLE_BURST) {
				s.flash();
				s.title(Component.literal("BLACK HOLE").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}

	// ------------------------------------------------------------------ the Meteor Shower

	private static final class MeteorFilm implements Film {
		/** The winner looks up and the camera follows his eyes this long before the first meteor is born. */
		private static final int LEAD = 24;
		private static final int AFTER = 70;

		@Override public String id() { return "meteor_shower"; }
		@Override public boolean domain() { return false; }
		@Override public int camTicks() { return LEAD + ExplosionArt.SHOWER_BIG + ExplosionArt.SHOWER_BIG_FLIGHT + AFTER; }
		@Override public int tail() { return Cinema.TAIL; }

		/** A low wide shot from the side that drifts toward the target while the sky burns, and circles the crater and rises after the big one lands. */
		@Override
		public Vec3 camera(Cinema.Scene s, int a) {
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			int t = a - LEAD;
			int land = ExplosionArt.SHOWER_BIG + ExplosionArt.SHOWER_BIG_FLIGHT;
			double k = Cinema.ease(clamp01(t / (double) land));
			Vec3 at = new Vec3(s.center.x + s.side.x * 22 + fwd.x * (-6 + 12 * k), s.center.y + 2.6 + 3.0 * k, s.center.z + s.side.z * 22 + fwd.z * (-6 + 12 * k));
			if (t <= land) return at;
			int b = t - land;
			double u = Cinema.ease(Math.min(1.0, b / (double) AFTER));
			Vec3 rel = new Vec3(at.x - loser.x, 0, at.z - loser.z);
			Vec3 pos = swing(loser, rel, Math.toRadians(60) * u, 1.0 + 0.4 * u, at.y + 9.0 * u);
			if (b < 16) pos = Cinema.Scene.shake(pos, 1.2 * (1 - b / 16.0));
			return pos;
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			Vec3 fwd = s.forward();
			int t = a - LEAD;
			int land = ExplosionArt.SHOWER_BIG + ExplosionArt.SHOWER_BIG_FLIGHT;
			// where the small meteors cross the sky
			Vec3 sky = loser.add(fwd.scale(-16)).add(0, 24, 0);
			Vec3 target;
			if (t < 0) {
				target = lerp(winner.add(0, 1.3, 0), sky, Cinema.ease(a / (double) LEAD) * 0.8);
			} else if (t <= land) {
				Vec3 head = ExplosionArt.bigMeteorHead(winner, loser, t);
				Vec3 follow = lerp(head, loser.add(0, 2.0, 0), Cinema.ease(clamp01((t - (land - 12)) / 12.0)));
				double w = Cinema.ease(clamp01((t - (ExplosionArt.SHOWER_BIG - 8)) / 14.0));
				Vec3 early = lerp(lerp(winner.add(0, 1.3, 0), sky, 0.8), sky, Cinema.ease(clamp01(t / 12.0)));
				target = lerp(early, follow, w);
			} else {
				double u = Cinema.ease(Math.min(1.0, (t - land) / (double) AFTER));
				target = loser.add(0, 3.0 + 8.0 * u, 0);
			}
			return safe(target.subtract(cam));
		}

		@Override
		public void play(Cinema.Scene s, int a) {
			List<ServerPlayer> viewers = s.audience();
			Vec3 winner = s.stage[s.win];
			Vec3 loser = s.stage[1 - s.win];
			if (a == 0 && s.doubles[s.win] != null) s.doubles[s.win].setXRot(-35f);
			if (a < LEAD) {
				if (a == 8 || a == 16) s.sound(loser.add(0, 20, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 3.0f, 0.5f + a * 0.03f);
				return;
			}
			int t = a - LEAD;
			Props props = s.props();
			ExplosionArt.meteorShower(s.level, viewers, props, winner, loser, t, s.meteors);
			for (Vec3 at : props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
			if (t == ExplosionArt.SHOWER_BIG && s.doubles[s.win] != null) s.doubles[s.win].setXRot(0f);
			if (t == ExplosionArt.SHOWER_BIG + ExplosionArt.SHOWER_BIG_FLIGHT) {
				s.flash();
				s.title(Component.literal("METEOR SHOWER").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
					Component.literal(s.duel.names[s.win] + " wins").withStyle(ChatFormatting.WHITE), 0, 50, 20);
			}
		}
	}
}
