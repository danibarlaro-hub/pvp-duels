package com.pvpduels.effects;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The solid things of an explosion: giant beams, spheres made of rotated blocks, fireballs, flying debris and real firework bursts.
 * They are display entities (block displays and item displays) that are shown full-bright, so they are the "body" of a big effect while
 * the particles are the fire and smoke around it. One display costs the server a few bytes when it is made and when it changes, much
 * less than the particles that would be needed to fill the same volume; the client moves them smoothly by itself (interpolation).
 * Every prop is tagged {@code pvpduels_fx...} and is discarded when its show ends, when the server stops and when the server starts.
 */
public final class Props {
	public static final String TAG = "pvpduels_fx";
	private static int serial;
	private static int alive;
	private static int created;
	private static int bursts;

	/** A rotation as a quaternion (x, y, z, w). */
	public record Q(double x, double y, double z, double w) {
		public static final Q ID = new Q(0, 0, 0, 1);

		public static Q axis(double ax, double ay, double az, double radians) {
			double len = Math.sqrt(ax * ax + ay * ay + az * az);
			if (len < 1e-9) return ID;
			double s = Math.sin(radians / 2) / len;
			return new Q(ax * s, ay * s, az * s, Math.cos(radians / 2));
		}

		public static Q yaw(double degrees) {
			return axis(0, 1, 0, Math.toRadians(degrees));
		}

		public static Q pitch(double degrees) {
			return axis(1, 0, 0, Math.toRadians(degrees));
		}

		public static Q roll(double degrees) {
			return axis(0, 0, 1, Math.toRadians(degrees));
		}

		/** The rotation that takes the +Z axis to the direction {@code d} (a block 'length' along Z then points that way). */
		public static Q fromZ(Vec3 d) {
			Vec3 n = d.lengthSqr() < 1e-9 ? new Vec3(0, 1, 0) : d.normalize();
			double ax = -n.y;
			double ay = n.x;
			double len = Math.sqrt(ax * ax + ay * ay);
			if (len < 1e-6) return n.z > 0 ? ID : axis(1, 0, 0, Math.PI);
			return axis(ax, ay, 0, Math.acos(Math.max(-1, Math.min(1, n.z))));
		}

		/** This rotation applied after {@code o}. */
		public Q mul(Q o) {
			return new Q(
				w * o.x + x * o.w + y * o.z - z * o.y,
				w * o.y - x * o.z + y * o.w + z * o.x,
				w * o.z + x * o.y - y * o.x + z * o.w,
				w * o.w - x * o.x - y * o.y - z * o.z);
		}

		public Vec3 rotate(Vec3 v) {
			double dot = x * v.x + y * v.y + z * v.z;
			double uu = x * x + y * y + z * z;
			double cx = y * v.z - z * v.y;
			double cy = z * v.x - x * v.z;
			double cz = x * v.y - y * v.x;
			return new Vec3(
				2 * dot * x + (w * w - uu) * v.x + 2 * w * cx,
				2 * dot * y + (w * w - uu) * v.y + 2 * w * cy,
				2 * dot * z + (w * w - uu) * v.z + 2 * w * cz);
		}
	}

	/** One display entity of a show. */
	public static final class Prop {
		final Entity entity;
		final UUID id;
		Vec3 pos;
		boolean dead;
		/** The show age at which it disappears by itself, or -1. */
		int dieAt = -1;

		Prop(Entity entity) {
			this.entity = entity;
			this.id = entity.getUUID();
			this.pos = entity.position();
		}

		public Vec3 pos() {
			return pos;
		}
	}

	/** A prop that flies with gravity until it hits the ground (the debris). */
	private static final class Ball {
		final Prop prop;
		Vec3 vel;
		final double floorY;

		Ball(Prop prop, Vec3 vel, double floorY) {
			this.prop = prop;
			this.vel = vel;
			this.floorY = floorY;
		}
	}

	/** A prop that is sucked in along a spiral toward a point (the ground pieces of the Black Hole). */
	private static final class Spiral {
		final Prop prop;
		final Vec3 ground;
		final Vec3 hole;
		final double radius;
		final double angle;
		final int born;
		final int life;

		Spiral(Prop prop, Vec3 ground, Vec3 hole, double radius, double angle, int born, int life) {
			this.prop = prop;
			this.ground = ground;
			this.hole = hole;
			this.radius = radius;
			this.angle = angle;
			this.born = born;
			this.life = life;
		}
	}

	private final MinecraftServer server;
	private final ServerLevel level;
	private final String prefix;
	private int next;
	private final List<Prop> props = new ArrayList<>();
	private final List<Ball> balls = new ArrayList<>();
	private final List<Spiral> spirals = new ArrayList<>();
	private final Map<String, Prop> named = new HashMap<>();
	/** Players who do not want to see the solid props (they hide other people's effects, or play on Potato). */
	private Predicate<ServerPlayer> hides;

	public Props(MinecraftServer server, ServerLevel level) {
		this.server = server;
		this.level = level;
		this.prefix = TAG + "_" + (++serial);
	}

	// ------------------------------------------------------------------ for the self-test

	public static int live() {
		return alive;
	}

	public static int createdCount() {
		return created;
	}

	public static int burstCount() {
		return bursts;
	}

	public static void resetForTest() {
		created = 0;
		bursts = 0;
	}

	/** How many tagged entities are in the world right now (all dimensions). */
	public static int tagged(MinecraftServer server) {
		int n = 0;
		for (ServerLevel l : server.getAllLevels()) {
			for (Entity e : l.getAllEntities()) {
				for (String tag : e.entityTags()) {
					if (tag.startsWith(TAG)) {
						n++;
						break;
					}
				}
			}
		}
		return n;
	}

	/** Discards every prop that is left in the world (a show that was cut by a stop). Returns how many. */
	public static int sweep(MinecraftServer server) {
		List<Entity> found = new ArrayList<>();
		for (ServerLevel l : server.getAllLevels()) {
			for (Entity e : l.getAllEntities()) {
				for (String tag : e.entityTags()) {
					if (tag.startsWith(TAG)) {
						found.add(e);
						break;
					}
				}
			}
		}
		for (Entity e : found) e.discard();
		alive = 0;
		return found.size();
	}

	// ------------------------------------------------------------------ making props

	private void run(String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
	}

	private static String transform(Vec3 size, Q rot, Vec3 translation) {
		return String.format(Locale.ROOT,
			"{left_rotation:[%.5ff,%.5ff,%.5ff,%.5ff],right_rotation:[0f,0f,0f,1f],scale:[%.4ff,%.4ff,%.4ff],translation:[%.4ff,%.4ff,%.4ff]}",
			rot.x(), rot.y(), rot.z(), rot.w(), size.x, size.y, size.z, translation.x, translation.y, translation.z);
	}

	/** The translation that keeps a block of this size and rotation centered on the entity. */
	private static Vec3 centering(Vec3 size, Q rot) {
		Vec3 half = rot.rotate(size.scale(0.5));
		return new Vec3(-half.x, -half.y, -half.z);
	}

	private Prop spawn(String type, Vec3 at, String nbt) {
		// a prop in a chunk that is not loaded could not be found again and would stay in the world
		if (!level.hasChunkAt(BlockPos.containing(at))) return null;
		String tag = prefix + "_" + (next++);
		String command = String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f {Tags:[\"%s\"]%s}", type, at.x, at.y, at.z, tag, nbt);
		run(command);
		AABB box = new AABB(at.x - 2, at.y - 2, at.z - 2, at.x + 2, at.y + 2, at.z + 2);
		List<Entity> found = level.getEntities((Entity) null, box, e -> e.entityTags().contains(tag));
		if (found.isEmpty()) return null;
		Entity e = found.get(0);
		if (e instanceof Display display) display.setPosRotInterpolationDuration(3);
		if (hides != null) {
			for (ServerPlayer v : level.players()) {
				if (hides.test(v)) v.connection.send(new ClientboundRemoveEntitiesPacket(e.getId()));
			}
		}
		Prop p = new Prop(e);
		props.add(p);
		alive++;
		created++;
		return p;
	}

	/** A block (or glass) of this size, turned by {@code rot}, with its middle at {@code center}. Full-bright when {@code bright}. */
	public Prop block(String state, Vec3 center, Vec3 size, Q rot, boolean bright) {
		String nbt = ",block_state:{Name:\"" + state + "\"},transformation:" + transform(size, rot, centering(size, rot))
			+ ",view_range:4f,shadow_radius:0f" + (bright ? ",brightness:{sky:15,block:15}" : "");
		return spawn("minecraft:block_display", center, nbt);
	}

	/**
	 * A block that starts flat along one of its own axes (0 = x, 1 = y, 2 = z) and becomes {@code full} when it is reshaped to that size with
	 * {@link #reshape}: it grows from the end of that axis that points the wrong way (a column from its foot, a line from its start). Do the
	 * reshape in a later tick than the one that made it, or the client never sees the growth.
	 */
	public Prop growing(String state, Vec3 center, Vec3 full, Q rot, boolean bright, int axis) {
		Vec3 flat = new Vec3(axis == 0 ? 0.02 : full.x, axis == 1 ? 0.02 : full.y, axis == 2 ? 0.02 : full.z);
		String nbt = ",block_state:{Name:\"" + state + "\"},transformation:" + transform(flat, rot, centering(full, rot))
			+ ",view_range:4f,shadow_radius:0f" + (bright ? ",brightness:{sky:15,block:15}" : "");
		return spawn("minecraft:block_display", center, nbt);
	}

	/** An item drawn as a flat picture that always faces the viewer ({@code size} blocks wide), full-bright. */
	public Prop item(String item, Vec3 center, double size) {
		String nbt = ",item:{id:\"" + item + "\",count:1},item_display:\"fixed\",billboard:\"center\",transformation:"
			+ transform(new Vec3(size, size, size), Q.ID, Vec3.ZERO) + ",view_range:4f,shadow_radius:0f,brightness:{sky:15,block:15}";
		return spawn("minecraft:item_display", center, nbt);
	}

	/** These players get the props removed from their screen the moment they appear. */
	public void hideFrom(Predicate<ServerPlayer> test) {
		this.hides = test;
	}

	public Prop name(String key, Prop prop) {
		if (prop != null) named.put(key, prop);
		return prop;
	}

	public Prop named(String key) {
		return named.get(key);
	}

	// ------------------------------------------------------------------ changing and moving them

	/** The block grows or shrinks (and turns) to this over {@code ticks}; the client draws every step in between. */
	public void reshape(Prop p, Vec3 center, Vec3 size, Q rot, int ticks) {
		if (p == null || p.dead) return;
		p.pos = center;
		p.entity.teleportTo(level, center.x, center.y, center.z, Set.of(), p.entity.getYRot(), p.entity.getXRot(), false);
		run(String.format(Locale.ROOT, "data merge entity %s {start_interpolation:0,interpolation_duration:%d,transformation:%s}", p.id, ticks,
			transform(size, rot, centering(size, rot))));
	}

	/** The picture of an item grows or shrinks to this width over {@code ticks}. */
	public void resize(Prop p, double size, int ticks) {
		if (p == null || p.dead) return;
		run(String.format(Locale.ROOT, "data merge entity %s {start_interpolation:0,interpolation_duration:%d,transformation:%s}", p.id, ticks,
			transform(new Vec3(size, size, size), Q.ID, Vec3.ZERO)));
	}

	/** Moves the prop; the client slides it there over the next few ticks. */
	public void move(Prop p, Vec3 to) {
		if (p == null || p.dead) return;
		p.pos = to;
		p.entity.teleportTo(level, to.x, to.y, to.z, Set.of(), p.entity.getYRot(), p.entity.getXRot(), false);
	}

	/** The prop disappears when the show is this old. */
	public void lifetime(Prop p, int age) {
		if (p != null) p.dieAt = age;
	}

	public void remove(Prop p) {
		if (p == null || p.dead) return;
		p.dead = true;
		p.entity.discard();
		alive--;
	}

	/** A piece that is thrown with this speed and falls until it reaches {@code floorY}. */
	public void throwPiece(Prop p, Vec3 velocity, double floorY) {
		if (p != null) balls.add(new Ball(p, velocity, floorY));
	}

	/** The piece spirals from the ground around {@code ground} up into {@code hole} in {@code life} ticks, starting at tick {@code born}. */
	public void spiral(Prop p, Vec3 ground, Vec3 hole, double radius, double angle, int born, int life) {
		if (p != null) spirals.add(new Spiral(p, ground, hole, radius, angle, born, life));
	}

	/**
	 * Called once per tick of the show. Removes the props whose time is up and moves the flying pieces (every other tick).
	 * Returns the places where pieces hit the ground this tick.
	 */
	public List<Vec3> tick(int age) {
		List<Vec3> landed = new ArrayList<>();
		for (Prop p : props) {
			if (!p.dead && p.dieAt >= 0 && age >= p.dieAt) remove(p);
		}
		if (age % 2 == 0) {
			Iterator<Ball> it = balls.iterator();
			while (it.hasNext()) {
				Ball b = it.next();
				if (b.prop.dead) {
					it.remove();
					continue;
				}
				Vec3 at = b.prop.pos.add(b.vel.scale(2));
				b.vel = new Vec3(b.vel.x * 0.99, b.vel.y - 0.16, b.vel.z * 0.99);
				if (at.y <= b.floorY) {
					landed.add(new Vec3(at.x, b.floorY, at.z));
					remove(b.prop);
					it.remove();
				} else {
					move(b.prop, at);
				}
			}
		}
		if (age % 2 == 0) {
			Iterator<Spiral> it = spirals.iterator();
			while (it.hasNext()) {
				Spiral sp = it.next();
				if (sp.prop.dead) {
					it.remove();
					continue;
				}
				double u = (age - sp.born) / (double) sp.life;
				if (u < 0) continue;
				if (u >= 1) {
					remove(sp.prop);
					it.remove();
					continue;
				}
				double r = sp.radius * Math.pow(1 - u, 1.3) + 0.4;
				double a = sp.angle + u * 6.0;
				double h = sp.ground.y + 0.5 + (sp.hole.y - sp.ground.y - 0.5) * u * u;
				move(sp.prop, new Vec3(sp.ground.x + Math.cos(a) * r, h, sp.ground.z + Math.sin(a) * r));
			}
		}
		props.removeIf(p -> p.dead);
		return landed;
	}

	/** The props still there. */
	public int count() {
		int n = 0;
		for (Prop p : props) if (!p.dead) n++;
		return n;
	}

	/** Discards every prop of this show. */
	public void clear() {
		for (Prop p : props) remove(p);
		props.clear();
		balls.clear();
		spirals.clear();
		named.clear();
	}

	// ------------------------------------------------------------------ fireworks

	/**
	 * A real firework bursts almost at once at this place (the client draws it and the game plays its sounds). Keep it high: a burst
	 * hurts whoever is within 5 blocks of it.
	 */
	public static void burst(ServerLevel level, Vec3 at, FireworkExplosion.Shape shape, int[] colors, int[] fades, boolean trail, boolean twinkle) {
		IntArrayList rgb = new IntArrayList();
		for (int c : colors) rgb.add(c);
		IntArrayList fade = new IntArrayList();
		for (int c : fades) fade.add(c);
		FireworkExplosion explosion = new FireworkExplosion(shape, rgb, fade, trail, twinkle);
		ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
		rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(explosion)));
		level.addFreshEntity(new FireworkRocketEntity(level, at.x, at.y, at.z, rocket));
		bursts++;
	}
}
