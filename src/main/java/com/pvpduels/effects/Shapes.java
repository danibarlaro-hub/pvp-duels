package com.pvpduels.effects;

import com.pvpduels.effects.Props.Q;
import net.minecraft.world.phys.Vec3;

/**
 * Flat and thin shapes made of display entities: disks lying on the ground (floors, craters, rings) and lines between two points
 * (cracks, constellation lines, slashes). A disk is a pile of slightly turned square slabs, so it has no corners worth seeing.
 */
public final class Shapes {
	private Shapes() {}

	/** A flat disk: several square slabs turned a little from each other, lying at the same height. */
	public static final class Disk {
		private final Props.Prop[] slabs;
		private final Vec3 center;
		private final double thickness;
		private final Q tilt;
		private final double step;

		/**
		 * @param radius the radius that is always covered (the corners of the slabs reach a bit farther)
		 * @param tilt   turns the whole disk (the identity keeps it lying flat)
		 */
		public Disk(Props props, String state, Vec3 center, double radius, double thickness, int count, Q tilt, boolean bright) {
			this.center = center;
			this.thickness = thickness;
			this.tilt = tilt;
			this.slabs = new Props.Prop[count];
			this.step = 90.0 / count;
			double side = sideFor(radius, count);
			for (int k = 0; k < count; k++) {
				slabs[k] = props.block(state, center, new Vec3(side, thickness, side), tilt.mul(Q.yaw(k * step)), bright);
			}
		}

		/** The side of a square slab so that {@code count} of them cover a disk of this radius. */
		static double sideFor(double radius, int count) {
			double worst = Math.toRadians(45.0 - 45.0 / count);
			return Math.max(0.05, 2 * radius * Math.cos(worst));
		}

		/** The disk grows or shrinks to this radius over {@code ticks}. */
		public void resize(Props props, double radius, int ticks) {
			double side = sideFor(Math.max(0.03, radius), slabs.length);
			for (int k = 0; k < slabs.length; k++) {
				props.reshape(slabs[k], center, new Vec3(side, thickness, side), tilt.mul(Q.yaw(k * step)), ticks);
			}
		}

		public void lifetime(Props props, int age) {
			for (Props.Prop p : slabs) props.lifetime(p, age);
		}

		public void remove(Props props) {
			for (Props.Prop p : slabs) props.remove(p);
		}
	}

	/**
	 * A flat disk whose slabs have names ({@code key + k}), so it can be found again later with {@link #diskTo}. It starts tiny and is
	 * reshaped next tick (the client only draws growth that starts a tick after the block was made). Gone when the show is {@code dieAt} ticks old.
	 */
	public static void diskMake(Props props, String key, String state, Vec3 center, double thickness, int count, boolean bright, int dieAt) {
		double step = 90.0 / count;
		for (int k = 0; k < count; k++) {
			Props.Prop p = props.block(state, center, new Vec3(0.3, thickness, 0.3), Q.yaw(k * step), bright);
			props.lifetime(props.name(key + k, p), dieAt);
		}
	}

	/** The named disk moves to this middle and takes this radius and thickness over {@code ticks}. */
	public static void diskTo(Props props, String key, Vec3 center, double radius, double thickness, int count, int ticks) {
		double step = 90.0 / count;
		double side = Disk.sideFor(Math.max(0.03, radius), count);
		for (int k = 0; k < count; k++) {
			props.reshape(props.named(key + k), center, new Vec3(side, thickness, side), Q.yaw(k * step), ticks);
		}
	}

	/** A thin beam from {@code a} to {@code b}. */
	public static Props.Prop line(Props props, String state, Vec3 a, Vec3 b, double thick, boolean bright) {
		Vec3 mid = a.add(b).scale(0.5);
		return props.block(state, mid, new Vec3(thick, thick, Math.max(0.05, a.distanceTo(b))), Q.fromZ(b.subtract(a)), bright);
	}

	/** A thin beam that is only a point at {@code a} until {@link #growLine} stretches it to {@code b}. */
	public static Props.Prop lineGrowing(Props props, String state, Vec3 a, Vec3 b, double thick, boolean bright) {
		Vec3 mid = a.add(b).scale(0.5);
		return props.growing(state, mid, new Vec3(thick, thick, Math.max(0.05, a.distanceTo(b))), Q.fromZ(b.subtract(a)), bright, 2);
	}

	public static void growLine(Props props, Props.Prop p, Vec3 a, Vec3 b, double thick, int ticks) {
		Vec3 mid = a.add(b).scale(0.5);
		props.reshape(p, mid, new Vec3(thick, thick, Math.max(0.05, a.distanceTo(b))), Q.fromZ(b.subtract(a)), ticks);
	}

	/** A beam from {@code a} to {@code b} changes its thickness (and keeps its ends). */
	public static void thicken(Props props, Props.Prop p, Vec3 a, Vec3 b, double thick, int ticks) {
		growLine(props, p, a, b, thick, ticks);
	}

	/** The point at {@code lx} to the right, {@code ly} up and {@code lz} forward of {@code origin} (forward and right are flat unit vectors). */
	public static Vec3 at(Vec3 origin, Vec3 forward, Vec3 right, double lx, double ly, double lz) {
		return new Vec3(origin.x + right.x * lx + forward.x * lz, origin.y + ly, origin.z + right.z * lx + forward.z * lz);
	}

	/** The flat unit vector that points from {@code from} to {@code to} (any direction if they are in the same place). */
	public static Vec3 flatDirection(Vec3 from, Vec3 to) {
		Vec3 flat = new Vec3(to.x - from.x, 0, to.z - from.z);
		return flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
	}

	/** The right-hand side of a flat forward direction. */
	public static Vec3 rightOf(Vec3 forward) {
		return new Vec3(forward.z, 0, -forward.x);
	}

	/** The turn about the vertical that makes a block's own +Z point along {@code forward}. */
	public static Q facing(Vec3 forward) {
		return Q.yaw(Math.toDegrees(Math.atan2(forward.x, forward.z)));
	}

	/** An even spread of {@code n} directions over a sphere, or only over its upper half. */
	public static Vec3 sphereDir(int i, int n, boolean upward) {
		double y = upward ? (i + 0.5) / n : 1 - 2 * (i + 0.5) / n;
		double r = Math.sqrt(Math.max(0, 1 - y * y));
		double a = i * 2.399963229728653;
		return new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
	}
}
