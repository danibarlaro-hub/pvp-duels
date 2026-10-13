package com.pvpduels.duel;

import com.pvpduels.effects.DomainArt;
import com.pvpduels.effects.DomeArt;
import com.pvpduels.effects.ExplosionArt;
import com.pvpduels.effects.Props;
import com.pvpduels.effects.Quality;
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
	static final Film SHRINE = new DomainFilm(DomeArt.Variant.SHRINE);
	static final Film VOID = new DomainFilm(DomeArt.Variant.VOID);
	static final Film SWORDS = new DomainFilm(DomeArt.Variant.SWORDS);

	private Films() {}

	/** The film that goes with this explosion, or null when it has none (it plays the usual finale). */
	static Film of(String explosion) {
		return switch (explosion) {
			case "shooting_star" -> STAR;
			case "gungnir" -> GUNGNIR;
			case "seven_stars" -> SEVEN;
			case "domain_expansion" -> SHRINE;
			case "domain_void" -> VOID;
			case "domain_swords" -> SWORDS;
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
			};
		}

		@Override
		public Vec3 look(Cinema.Scene s, Vec3 cam, int a) {
			int rise = HANDS + expand();
			Vec3 standard = s.standardLook(cam);
			if (a <= rise) return standard;
			int b = Math.min(a, camTicks()) - rise;
			Vec3 loser = s.stage[1 - s.win];
			Vec3 target = loser.add(0, 1.3, 0);
			if (variant == DomeArt.Variant.SWORDS) {
				// first the field, then the loser
				Vec3 field = s.center.add(0, 2.5, 0);
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
			int climax = switch (variant) {
				case SHRINE -> e + h - 24;
				case VOID -> e + h - 14;
				case SWORDS -> e + h - 26;
			};
			if (t == climax) s.flash();
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
}
