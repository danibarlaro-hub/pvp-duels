package com.pvpduels.effects;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * What happens when you win a duel: the three fireworks you chose go up (behind you, one block higher, to your left and to your
 * right), your particles play under your feet and around the arena for five seconds, and your explosion (if you have one) goes off.
 * It is only light and sound: nothing is damaged and nothing is broken. It starts after the duel is decided, so it never gets in the way
 * of a fight.
 */
public final class Victory {
	/** How long the particles and the explosion last. */
	public static final int SHOW_TICKS = 100;
	/** How far to the side and behind the fireworks start. */
	private static final double SIDE = 2.5;
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
		int age;

		Show(UUID id, ServerLevel level, Vec3 target, String explosion) {
			this.id = id;
			this.level = level;
			this.target = target;
			this.explosion = explosion;
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
		SHOWS.clear();
		launches = 0;
		started = 0;
	}

	/** The winner celebrates; the target of the explosion is where the loser is. */
	public static void play(ServerPlayer winner, ServerPlayer loser) {
		start(winner, loser == null ? null : loser.position());
	}

	/** Shows a player their own effects (from the menu). The explosion lands a few blocks in front of them. */
	public static void preview(ServerPlayer p) {
		double yaw = Math.toRadians(p.getYRot());
		start(p, p.position().add(-Math.sin(yaw) * 7, 0, Math.cos(yaw) * 7));
	}

	private static void start(ServerPlayer winner, Vec3 target) {
		UUID id = winner.getUUID();
		ServerLevel level = (ServerLevel) winner.level();
		started++;
		launchFireworks(level, winner);

		Vec3 where = target != null ? target : winner.position();
		Show show = new Show(id, level, where, Effects.explosionSlot(id));
		for (int i = 0; i < Effects.FLOOR_SLOTS; i++) {
			String p = Effects.particleSlot(id, false, i);
			if (!p.isEmpty() && Particles.find(p) != null) show.floor.add(p);
		}
		for (int i = 0; i < Effects.AREA_SLOTS; i++) {
			String p = Effects.particleSlot(id, true, i);
			if (!p.isEmpty() && Particles.find(p) != null) show.area.add(p);
		}
		if (show.floor.isEmpty() && show.area.isEmpty() && show.explosion.isEmpty()) return;
		SHOWS.add(show);
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

	private static void launchFireworks(ServerLevel level, ServerPlayer winner) {
		UUID id = winner.getUUID();
		double yaw = Math.toRadians(winner.getYRot());
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
			double x = winner.getX();
			double y = winner.getY();
			double z = winner.getZ();
			if (slot == 0) {
				x -= fx * SIDE;
				z -= fz * SIDE;
				y += 1; // the one behind you starts a block higher
			} else if (slot == 1) {
				x -= rx * SIDE;
				z -= rz * SIDE;
			} else {
				x += rx * SIDE;
				z += rz * SIDE;
			}
			level.addFreshEntity(new FireworkRocketEntity(level, x, y, z, rocket));
			launches++;
		}
	}

	// ------------------------------------------------------------------ the show

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Victory::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> SHOWS.clear());
	}

	private static void tick(MinecraftServer server) {
		if (SHOWS.isEmpty()) return;
		Iterator<Show> it = SHOWS.iterator();
		while (it.hasNext()) {
			Show s = it.next();
			ServerPlayer winner = server.getPlayerList().getPlayer(s.id);
			if (winner == null || winner.level() != s.level || s.age >= SHOW_TICKS) {
				it.remove();
				continue;
			}
			s.age++;
			List<ServerPlayer> viewers = viewers(s.level, winner);
			floor(s, winner, viewers);
			area(s, winner, viewers);
			if (!s.explosion.isEmpty() && s.age >= EXPLOSION_DELAY) explosion(s, winner, viewers, s.age - EXPLOSION_DELAY);
		}
	}

	/** Players near the winner who want to see it (the winner always does). */
	private static List<ServerPlayer> viewers(ServerLevel level, ServerPlayer winner) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(winner) > VIEW_DISTANCE_SQR) continue;
			if (p != winner && Effects.hideOthers(p.getUUID())) continue;
			out.add(p);
		}
		return out;
	}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) level.sendParticles(v, options, false, false, x, y, z, count, dx, dy, dz, speed);
	}

	/** Particles on the ground: a ring that turns around the winner. */
	private static void floor(Show s, ServerPlayer winner, List<ServerPlayer> viewers) {
		if (s.floor.isEmpty() || s.age % 2 != 0) return;
		for (int j = 0; j < s.floor.size(); j++) {
			Particles.Def def = Particles.find(s.floor.get(j));
			if (def == null) continue;
			double radius = 1.1 + 0.5 * j;
			int points = 8;
			for (int k = 0; k < points; k++) {
				double angle = s.age * 0.22 * (j % 2 == 0 ? 1 : -1) + k * Math.PI * 2 / points;
				emit(s.level, viewers, def.options(), winner.getX() + Math.cos(angle) * radius, winner.getY() + 0.1, winner.getZ() + Math.sin(angle) * radius,
					1, 0.03, 0.05, 0.03, 0.01);
			}
		}
	}

	/** Particles around the arena: waves that start at the winner and spread out quickly. */
	private static void area(Show s, ServerPlayer winner, List<ServerPlayer> viewers) {
		if (s.area.isEmpty()) return;
		for (int j = 0; j < s.area.size(); j++) {
			if ((s.age + j) % 2 != 0) continue;
			Particles.Def def = Particles.find(s.area.get(j));
			if (def == null) continue;
			double radius = 1.5 + ((s.age + j * 6) % 24) * 0.9;
			int points = Math.min(26, 8 + (int) (radius * 0.8));
			double height = 0.4 + (j % 2) * 1.0;
			for (int k = 0; k < points; k++) {
				double angle = s.age * 0.1 + j * 0.4 + k * Math.PI * 2 / points;
				emit(s.level, viewers, def.options(), winner.getX() + Math.cos(angle) * radius, winner.getY() + height, winner.getZ() + Math.sin(angle) * radius,
					1, 0.05, 0.2, 0.05, 0.02);
			}
		}
	}

	// ------------------------------------------------------------------ explosions (light and sound only)

	private static void explosion(Show s, ServerPlayer winner, List<ServerPlayer> viewers, int t) {
		switch (s.explosion) {
			case "orbital_strike" -> orbital(s, viewers, t, ParticleTypes.END_ROD, ParticleTypes.FLAME, ParticleTypes.LARGE_SMOKE, false);
			case "orbital_wither" -> orbital(s, viewers, t, ParticleTypes.SOUL_FIRE_FLAME, ParticleTypes.SOUL, ParticleTypes.SQUID_INK, true);
			case "domain_expansion" -> domain(s, winner, viewers, t);
			default -> { }
		}
	}

	private static void sound(ServerLevel level, double x, double y, double z, boolean thunder, boolean wither) {
		if (wither) level.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 2.0f, 1.2f);
		else level.playSound(null, x, y, z, SoundEvents.BEACON_ACTIVATE, SoundSource.MASTER, 2.0f, 1.5f);
		if (thunder) level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.MASTER, 4.0f, 0.8f);
	}

	/** A beam from the sky lands on the target, and a column of fire (or souls) rises. The provisional version of the two cannons. */
	private static void orbital(Show s, List<ServerPlayer> viewers, int t, ParticleOptions beam, ParticleOptions flame, ParticleOptions smoke, boolean wither) {
		Vec3 c = s.target;
		if (t == 0) sound(s.level, c.x, c.y + 20, c.z, false, wither);
		if (t < 20) {
			double head = c.y + 60 - t * 3.0;
			for (int k = 0; k < 8; k++) emit(s.level, viewers, beam, c.x, head + k, c.z, 1, 0.1, 0.0, 0.1, 0.0);
			return;
		}
		if (t == 20) {
			sound(s.level, c.x, c.y, c.z, true, wither);
			emit(s.level, viewers, ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
			for (int k = 0; k < 24; k++) {
				double angle = k * Math.PI * 2 / 24;
				for (int r = 1; r <= 5; r += 2) emit(s.level, viewers, flame, c.x + Math.cos(angle) * r, c.y + 0.2, c.z + Math.sin(angle) * r, 2, 0.1, 0.3, 0.1, 0.05);
			}
		}
		if (t > 20 && t < 50) {
			ThreadLocalRandom rnd = ThreadLocalRandom.current();
			double height = (t - 20) * 0.4;
			emit(s.level, viewers, flame, c.x, c.y + height, c.z, 6, 0.6, 0.4, 0.6, 0.03);
			emit(s.level, viewers, smoke, c.x + rnd.nextGaussian() * 0.3, c.y + height + 1, c.z + rnd.nextGaussian() * 0.3, 3, 0.8, 0.4, 0.8, 0.02);
		}
	}

	/** A dome of dark particles grows around the winner and stays for a moment. The provisional version of Domain Expansion. */
	private static void domain(Show s, ServerPlayer winner, List<ServerPlayer> viewers, int t) {
		if (t == 0) s.level.playSound(null, winner.getX(), winner.getY(), winner.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 3.0f, 0.7f);
		if (t > 70) return;
		double radius = Math.min(12.0, 1 + t * 0.6);
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int k = 0; k < 14; k++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double b = Math.acos(2 * rnd.nextDouble() - 1);
			double x = winner.getX() + radius * Math.sin(b) * Math.cos(a);
			double y = winner.getY() + radius * Math.cos(b) * 0.6 + 1;
			double z = winner.getZ() + radius * Math.sin(b) * Math.sin(a);
			emit(s.level, viewers, k % 3 == 0 ? ParticleTypes.SQUID_INK : (k % 3 == 1 ? ParticleTypes.PORTAL : ParticleTypes.REVERSE_PORTAL), x, y, z, 1, 0.05, 0.05, 0.05, 0.02);
		}
	}
}
