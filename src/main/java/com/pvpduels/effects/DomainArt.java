package com.pvpduels.effects;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The particles of the Domain Expansion: a dark dome that grows over the arena, the dark gray line of its edge, and the two domes that
 * push each other when two domains meet. Only drawing: when and why is decided by the duel finale ({@code Cinema}).
 */
public final class DomainArt {
	/** The dark gray line that separates a domain from the rest of the world (and from the other domain in a clash). */
	public static final ParticleOptions LINE = new DustParticleOptions(0x2E2E2E, 2.2f);
	/** The look of a lone domain: black ink, purple portal and dark purple dust. */
	private static final ParticleOptions[] VOID = {
		ParticleTypes.SQUID_INK, ParticleTypes.PORTAL, ParticleTypes.REVERSE_PORTAL, new DustParticleOptions(0x3B0A5E, 1.8f),
		ParticleTypes.SQUID_INK, new DustParticleOptions(0x120018, 2.0f)};
	/** The two domains of a clash: red-black and blue-black. */
	private static final ParticleOptions[] RED = {
		new DustParticleOptions(0x9E0B1D, 1.8f), ParticleTypes.CRIMSON_SPORE, new DustParticleOptions(0x2A0005, 2.0f), ParticleTypes.SQUID_INK};
	private static final ParticleOptions[] BLUE = {
		new DustParticleOptions(0x0B6F8E, 1.8f), ParticleTypes.WARPED_SPORE, new DustParticleOptions(0x00202A, 2.0f), ParticleTypes.SQUID_INK};

	private DomainArt() {}

	public static ParticleOptions[] voidLook() {
		return VOID;
	}

	public static ParticleOptions[] redLook() {
		return RED;
	}

	public static ParticleOptions[] blueLook() {
		return BLUE;
	}

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) Quality.send(level, v, options, x, y, z, count, dx, dy, dz, speed);
	}

	/** A flat circle of particles (on the ground or higher up). */
	public static void ring(ServerLevel level, List<ServerPlayer> viewers, Vec3 center, double radius, int points, double height, ParticleOptions options, double turn) {
		if (radius < 0.3) return;
		for (int k = 0; k < points; k++) {
			double a = turn + k * Math.PI * 2 / points;
			emit(level, viewers, options, center.x + Math.cos(a) * radius, center.y + height, center.z + Math.sin(a) * radius, 1, 0.02, 0.02, 0.02, 0.0);
		}
	}

	/** Random points of the upper half of a sphere shell (the dome), with the look given. */
	public static void dome(ServerLevel level, List<ServerPlayer> viewers, Vec3 center, double radius, int count, ParticleOptions[] look) {
		if (radius < 0.5) return;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int k = 0; k < count; k++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double up = rnd.nextDouble(); // 0 = ground, 1 = top; uniform in the cosine so the shell looks even
			double sinB = Math.sqrt(1 - up * up);
			double x = center.x + radius * sinB * Math.cos(a);
			double y = center.y + 0.2 + radius * up;
			double z = center.z + radius * sinB * Math.sin(a);
			emit(level, viewers, look[k % look.length], x, y, z, 1, 0.06, 0.06, 0.06, 0.01);
		}
	}

	/** The part of a dome that is on one side of a plane (the clash): {@code axis} points away from the dome's own player, {@code planeU} is the plane. */
	public static void cap(ServerLevel level, List<ServerPlayer> viewers, Vec3 origin, double radius, int count, ParticleOptions[] look, Vec3 axis, Vec3 planeCenter) {
		if (radius < 0.5) return;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int made = 0;
		for (int tries = 0; tries < count * 4 && made < count; tries++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double up = rnd.nextDouble();
			double sinB = Math.sqrt(1 - up * up);
			Vec3 p = new Vec3(origin.x + radius * sinB * Math.cos(a), origin.y + 0.2 + radius * up, origin.z + radius * sinB * Math.sin(a));
			// keep it only while it is before the plane: (p - plane) . axis <= 0
			if (p.subtract(planeCenter).dot(axis) > 0) continue;
			emit(level, viewers, look[made % look.length], p.x, p.y, p.z, 1, 0.06, 0.06, 0.06, 0.01);
			made++;
		}
	}

	/**
	 * The wall where two domains touch: a vertical circle of radius {@code rho} standing on the ground, in the plane whose normal is
	 * {@code axis}, drawn with the dark gray line, plus sparks while they push.
	 */
	public static void wall(ServerLevel level, List<ServerPlayer> viewers, Vec3 planeCenter, Vec3 axis, double rho, double push) {
		if (rho < 0.5) return;
		Vec3 side = new Vec3(-axis.z, 0, axis.x).normalize();
		int points = 56;
		for (int k = 0; k <= points; k++) {
			double a = Math.PI * k / points; // the half of the circle that is above the ground
			Vec3 p = planeCenter.add(side.scale(Math.cos(a) * rho)).add(0, 0.15 + Math.sin(a) * rho, 0);
			emit(level, viewers, LINE, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
		// the line on the ground, from one end of the circle to the other
		for (int k = -8; k <= 8; k++) {
			Vec3 p = planeCenter.add(side.scale(rho * k / 8.0)).add(0, 0.15, 0);
			emit(level, viewers, LINE, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int sparks = 3 + (int) (push * 10);
		for (int k = 0; k < sparks; k++) {
			double a = rnd.nextDouble() * Math.PI;
			Vec3 p = planeCenter.add(side.scale(Math.cos(a) * rho * rnd.nextDouble())).add(0, 0.3 + Math.sin(a) * rho * rnd.nextDouble(), 0);
			emit(level, viewers, k % 2 == 0 ? ParticleTypes.CRIT : ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 2, 0.3, 0.3, 0.3, 0.15);
		}
	}

	/** A slow swirl of particles around a point (the joined hands). */
	public static void swirl(ServerLevel level, List<ServerPlayer> viewers, Vec3 at, ParticleOptions options, int age) {
		for (int k = 0; k < 3; k++) {
			double a = age * 0.5 + k * Math.PI * 2 / 3;
			double r = 0.45 + 0.1 * Math.sin(age * 0.3);
			emit(level, viewers, options, at.x + Math.cos(a) * r, at.y + (age % 10) * 0.05, at.z + Math.sin(a) * r, 1, 0.01, 0.02, 0.01, 0.0);
		}
	}

	/** Smoke that fills the inside of a dome a little. */
	public static void haze(ServerLevel level, List<ServerPlayer> viewers, Vec3 center, double radius, int count) {
		if (radius < 1) return;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int k = 0; k < count; k++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double r = radius * Math.sqrt(rnd.nextDouble());
			emit(level, viewers, k % 2 == 0 ? ParticleTypes.ASH : ParticleTypes.SMOKE, center.x + Math.cos(a) * r, center.y + 0.3 + rnd.nextDouble() * radius * 0.7,
				center.z + Math.sin(a) * r, 1, 0.1, 0.1, 0.1, 0.01);
		}
	}

	/** The "exhale" at the end of a clash: a ring of clouds that rushes outward. */
	public static void exhale(ServerLevel level, List<ServerPlayer> viewers, Vec3 center, double radius) {
		for (int k = 0; k < 72; k++) {
			double a = k * Math.PI * 2 / 72;
			emit(level, viewers, k % 3 == 0 ? ParticleTypes.POOF : ParticleTypes.CLOUD, center.x + Math.cos(a) * radius, center.y + 0.5, center.z + Math.sin(a) * radius,
				2, 0.2, 0.3, 0.2, 0.25);
		}
	}
}
