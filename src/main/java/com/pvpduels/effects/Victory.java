package com.pvpduels.effects;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * What happens when you win a duel: the three fireworks you chose go up in a row behind you (the middle one a block higher), your
 * particles play under your feet and around the arena for five seconds, or your explosion goes off instead (you can have effects OR an
 * explosion, not both). It is only light and sound: nothing is damaged and nothing is broken. It starts after the duel is decided, so
 * it never gets in the way of a fight. In a 1v1 the finale ({@code Cinema}) plays it from the winner's place while the camera rises.
 */
public final class Victory {
	/** How long the particles last. */
	public static final int SHOW_TICKS = 100;
	/** How long an explosion lasts: the big ones need more time to build up, hit and fade. */
	public static final int EXPLOSION_SHOW_TICKS = 125;
	/** How far apart the fireworks of the row are, and how far behind the winner the row is. */
	private static final double SIDE = 2.5;
	private static final double BEHIND = 2.5;
	/** Fireworks fly this long (3 = about 20 blocks up). */
	private static final int FLIGHT = 3;
	/** The explosion starts this many ticks after the win. */
	private static final int EXPLOSION_DELAY = 10;
	private static final double VIEW_DISTANCE_SQR = 96.0 * 96.0;

	private static final class Show {
		final UUID id;
		final ServerLevel level;
		final Vec3 target;
		final List<String> floor = new ArrayList<>();
		final List<String> area = new ArrayList<>();
		final String explosion;
		/** Where the show is played when it does not follow the winner (the finale puts the winner's double there), else null. */
		final Vec3 fixed;
		final boolean skipDomain;
		/** The solid parts of the explosion (display entities); null when there is no explosion. */
		Props props;
		/** The meteors of a Meteor Shower that are still flying. */
		final List<ExplosionArt.Meteor> meteors = new ArrayList<>();
		/** Where the winner stands when the explosion starts (the Shooting Star and the meteors come from behind them). */
		Vec3 from;
		/** Set for a try-out on the dummy of the lobby: the Domain Expansion grows around the dummy and not around the player. */
		boolean tryOut;
		int age;

		Show(UUID id, ServerLevel level, Vec3 target, String explosion, Vec3 fixed, boolean skipDomain) {
			this.id = id;
			this.level = level;
			this.target = target;
			this.explosion = explosion;
			this.fixed = fixed;
			this.skipDomain = skipDomain;
		}

		Vec3 origin(ServerPlayer winner) {
			return fixed != null ? fixed : winner.position();
		}

		int limit() {
			return explosion.isEmpty() ? SHOW_TICKS : EXPLOSION_SHOW_TICKS;
		}

		/** The show is over: its display entities go away. */
		void close() {
			if (props != null) props.clear();
		}
	}

	private static final List<Show> SHOWS = new ArrayList<>();
	/** For the self-test: what was launched and played. */
	private static int launches;
	private static int started;

	private Victory() {}

	public static int fireworkLaunches() {
		return launches;
	}

	public static int showsStarted() {
		return started;
	}

	public static int activeShows() {
		return SHOWS.size();
	}

	public static void resetForTest() {
		for (Show s : SHOWS) s.close();
		SHOWS.clear();
		launches = 0;
		started = 0;
	}

	/** The winner celebrates where they stand; the target of the explosion is where the loser is. */
	public static void play(ServerPlayer winner, ServerPlayer loser) {
		start(winner, loser == null ? null : loser.position(), null, winner.getYRot(), false);
	}

	/**
	 * The winner celebrates at a fixed place (the winner's double in the finale), looking the way {@code yaw} says. The Domain Expansion
	 * is not drawn here: the finale does it.
	 */
	public static void playAt(ServerPlayer winner, Vec3 origin, float yaw, Vec3 loserAt) {
		start(winner, loserAt, origin, yaw, true);
	}

	/** Shows a player their own effects (from the menu). The explosion lands a few blocks in front of them. */
	public static void preview(ServerPlayer p) {
		double yaw = Math.toRadians(p.getYRot());
		start(p, p.position().add(-Math.sin(yaw) * 7, 0, Math.cos(yaw) * 7), null, p.getYRot(), false);
	}

	/** True when this player has an explosion equipped: then the fireworks and the particles are not played. */
	public static boolean explosionMode(UUID id) {
		return !Effects.explosionSlot(id).isEmpty();
	}

	private static void start(ServerPlayer winner, Vec3 target, Vec3 fixed, float yaw, boolean skipDomain) {
		UUID id = winner.getUUID();
		ServerLevel level = (ServerLevel) winner.level();
		started++;
		boolean explosionOnly = explosionMode(id);
		Vec3 origin = fixed != null ? fixed : winner.position();
		if (!explosionOnly) launchFireworks(level, id, origin, yaw);

		Vec3 where = target != null ? target : origin;
		Show show = new Show(id, level, where, Effects.explosionSlot(id), fixed, skipDomain);
		if (!explosionOnly) {
			for (int i = 0; i < Effects.FLOOR_SLOTS; i++) {
				String p = Effects.particleSlot(id, false, i);
				if (!p.isEmpty() && Particles.find(p) != null) show.floor.add(p);
			}
			for (int i = 0; i < Effects.AREA_SLOTS; i++) {
				String p = Effects.particleSlot(id, true, i);
				if (!p.isEmpty() && Particles.find(p) != null) show.area.add(p);
			}
		}
		if (show.floor.isEmpty() && show.area.isEmpty() && show.explosion.isEmpty()) return;
		if (!show.explosion.isEmpty()) show.props = propsFor(level, winner);
		SHOWS.add(show);
	}

	/** The solid parts of an explosion: whoever hides other people's effects, and whoever plays on Potato, does not get them. */
	private static Props propsFor(ServerLevel level, ServerPlayer winner) {
		Props props = new Props(level.getServer(), level);
		props.hideFrom(p -> (p != winner && Effects.hideOthers(p.getUUID())) || Effects.quality(p.getUUID()).factor < 0.2);
		return props;
	}

	/** At most this many try-outs at the same time, so a crowd around the dummy cannot flood the server. */
	public static final int MAX_TRY_OUTS = 6;

	/**
	 * The player tries one of THEIR explosions on a spot (the dummy of the lobby). Everybody nearby can watch it, like a real win.
	 * Returns null when it started, or what is wrong (in plain words) when it did not.
	 */
	public static String tryOn(ServerPlayer p, String explosion, Vec3 target) {
		UUID id = p.getUUID();
		if (!Effects.ownsExplosion(id, explosion)) return "You do not own that explosion.";
		for (Show s : SHOWS) {
			if (s.id.equals(id)) return "Wait a moment: your effects are still playing.";
		}
		if (SHOWS.size() >= MAX_TRY_OUTS) return "Too many effects are playing right now. Try again in a few seconds.";
		ServerLevel level = (ServerLevel) p.level();
		started++;
		Show show = new Show(id, level, target, explosion, null, false);
		show.tryOut = true;
		// the star and the meteors come from the player's side of the dummy, high up behind them
		Vec3 side = new Vec3(p.getX() - target.x, 0, p.getZ() - target.z);
		side = side.lengthSqr() < 0.25 ? new Vec3(0, 0, 1) : side.normalize();
		show.from = new Vec3(target.x + side.x * 8, p.getY(), target.z + side.z * 8);
		show.props = propsFor(level, p);
		SHOWS.add(show);
		return null;
	}

	// ------------------------------------------------------------------ fireworks

	private static FireworkExplosion.Shape shapeOf(String id) {
		return switch (id) {
			case "large_ball" -> FireworkExplosion.Shape.LARGE_BALL;
			case "star" -> FireworkExplosion.Shape.STAR;
			case "creeper" -> FireworkExplosion.Shape.CREEPER;
			case "burst" -> FireworkExplosion.Shape.BURST;
			default -> FireworkExplosion.Shape.SMALL_BALL;
		};
	}

	/** The three fireworks stand in a row behind the winner, the middle one a block higher. */
	private static void launchFireworks(ServerLevel level, UUID id, Vec3 origin, float yawDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -Math.cos(yaw);
		double rz = -Math.sin(yaw);
		for (int slot = 0; slot < Effects.FIREWORK_SLOTS; slot++) {
			List<String> colors = Effects.fireworkColors(id, slot);
			if (colors.isEmpty()) continue;
			IntArrayList rgb = new IntArrayList();
			for (String c : colors) {
				int index = Effects.colorIndex(c);
				if (index >= 0) rgb.add(Effects.COLOR_RGB[index]);
			}
			if (rgb.isEmpty()) continue;
			FireworkExplosion explosion = new FireworkExplosion(shapeOf(Effects.fireworkShape(id, slot)), rgb, new IntArrayList(),
				Effects.fireworkTrail(id, slot), Effects.fireworkTwinkle(id, slot));
			ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
			rocket.set(DataComponents.FIREWORKS, new Fireworks(FLIGHT, List.of(explosion)));
			// slot 0 is the middle one, slot 1 the one on the left and slot 2 the one on the right
			int column = slot == 0 ? 0 : (slot == 1 ? -1 : 1);
			double x = origin.x - fx * BEHIND - rx * SIDE * column;
			double y = origin.y + (slot == 0 ? 1 : 0);
			double z = origin.z - fz * BEHIND - rz * SIDE * column;
			level.addFreshEntity(new FireworkRocketEntity(level, x, y, z, rocket));
			launches++;
		}
	}

	// ------------------------------------------------------------------ the show

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Victory::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Show s : SHOWS) s.close();
			SHOWS.clear();
			Props.sweep(server);
		});
		// a show that was cut by a crash must not leave its display entities in the world
		ServerLifecycleEvents.SERVER_STARTED.register(Props::sweep);
	}

	private static void tick(MinecraftServer server) {
		if (SHOWS.isEmpty()) return;
		Iterator<Show> it = SHOWS.iterator();
		while (it.hasNext()) {
			Show s = it.next();
			ServerPlayer winner = server.getPlayerList().getPlayer(s.id);
			if (winner == null || winner.level() != s.level || s.age >= s.limit()) {
				it.remove();
				s.close();
				continue;
			}
			s.age++;
			List<ServerPlayer> viewers = viewers(s, winner);
			floor(s, winner, viewers);
			area(s, winner, viewers);
			if (!s.explosion.isEmpty() && s.age >= EXPLOSION_DELAY) {
				int t = s.age - EXPLOSION_DELAY;
				explosion(s, winner, viewers, t);
				// the display entities: flying pieces move, the ones whose time is up go away
				if (s.props != null) {
					for (Vec3 at : s.props.tick(t)) ExplosionArt.landing(s.level, viewers, at);
				}
			}
		}
	}

	/** Players near the show who want to see it (the winner always does). */
	private static List<ServerPlayer> viewers(Show s, ServerPlayer winner) {
		Vec3 origin = s.origin(winner);
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : s.level.players()) {
			if (p != winner && p.getCamera() == p && p.distanceToSqr(origin) > VIEW_DISTANCE_SQR) continue;
			if (p != winner && Effects.hideOthers(p.getUUID())) continue;
			out.add(p);
		}
		return out;
	}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) Quality.send(level, v, options, x, y, z, count, dx, dy, dz, speed);
	}

	/** Particles on the ground: a ring that turns around the winner. */
	private static void floor(Show s, ServerPlayer winner, List<ServerPlayer> viewers) {
		if (s.floor.isEmpty() || s.age % 2 != 0) return;
		Vec3 o = s.origin(winner);
		for (int j = 0; j < s.floor.size(); j++) {
			Particles.Def def = Particles.find(s.floor.get(j));
			if (def == null) continue;
			double radius = 1.1 + 0.5 * j;
			int points = 8;
			for (int k = 0; k < points; k++) {
				double angle = s.age * 0.22 * (j % 2 == 0 ? 1 : -1) + k * Math.PI * 2 / points;
				emit(s.level, viewers, def.options(), o.x + Math.cos(angle) * radius, o.y + 0.1, o.z + Math.sin(angle) * radius,
					1, 0.03, 0.05, 0.03, 0.01);
			}
		}
	}

	/** Particles around the arena: waves that start at the winner and spread out quickly. */
	private static void area(Show s, ServerPlayer winner, List<ServerPlayer> viewers) {
		if (s.area.isEmpty()) return;
		Vec3 o = s.origin(winner);
		for (int j = 0; j < s.area.size(); j++) {
			if ((s.age + j) % 2 != 0) continue;
			Particles.Def def = Particles.find(s.area.get(j));
			if (def == null) continue;
			double radius = 1.5 + ((s.age + j * 6) % 24) * 0.9;
			int points = Math.min(26, 8 + (int) (radius * 0.8));
			double height = 0.4 + (j % 2) * 1.0;
			for (int k = 0; k < points; k++) {
				double angle = s.age * 0.1 + j * 0.4 + k * Math.PI * 2 / points;
				emit(s.level, viewers, def.options(), o.x + Math.cos(angle) * radius, o.y + height, o.z + Math.sin(angle) * radius,
					1, 0.05, 0.2, 0.05, 0.02);
			}
		}
	}

	// ------------------------------------------------------------------ explosions (light and sound only)

	private static void explosion(Show s, ServerPlayer winner, List<ServerPlayer> viewers, int t) {
		switch (s.explosion) {
			case "orbital_strike" -> ExplosionArt.orbital(s.level, viewers, s.props, s.target, t, false);
			case "orbital_wither" -> ExplosionArt.orbital(s.level, viewers, s.props, s.target, t, true);
			case "domain_expansion" -> {
				if (!s.skipDomain) domain(s, winner, viewers, t);
			}
			case "shooting_star" -> ExplosionArt.shootingStar(s.level, viewers, s.props, from(s, winner), s.target, t);
			case "black_hole" -> ExplosionArt.blackHole(s.level, viewers, s.props, s.target, t);
			case "meteor_shower" -> ExplosionArt.meteorShower(s.level, viewers, s.props, from(s, winner), s.target, t, s.meteors);
			default -> { }
		}
	}

	/** Where the winner stood when the explosion began (kept the first time it is asked). */
	private static Vec3 from(Show s, ServerPlayer winner) {
		if (s.from == null) s.from = s.origin(winner);
		return s.from;
	}

	/** A dome of dark particles grows around the winner, with its edge drawn as a line, and stays for a while (the lone Domain Expansion). */
	private static void domain(Show s, ServerPlayer winner, List<ServerPlayer> viewers, int t) {
		Vec3 o = s.tryOut ? s.target : s.origin(winner);
		if (t == 0) s.level.playSound(null, o.x, o.y, o.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 4.0f, 0.7f);
		if (t > 100) return;
		double radius = Math.min(14.0, 1 + t * 0.4);
		DomainArt.dome(s.level, viewers, o, radius, 80, DomainArt.voidLook());
		DomainArt.ring(s.level, viewers, o, radius, 44, 0.15, DomainArt.LINE, t * 0.05);
	}
}
