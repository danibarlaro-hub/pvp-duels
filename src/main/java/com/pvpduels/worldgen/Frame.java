package com.pvpduels.worldgen;

/**
 * A little coordinate system for a building. "Local" x runs to the right when you stand in the doorway looking out,
 * z runs out of the door (the front). Block strings can use {F} {B} {R} {L} for the front, back, right and left side
 * and they become north/south/east/west for the real direction of this building.
 */
final class Frame {
	final int cx, cz, fx, fz, rx, rz;

	Frame(int cx, int cz, int fx, int fz) {
		this.cx = cx;
		this.cz = cz;
		this.fx = fx;
		this.fz = fz;
		this.rx = fz;
		this.rz = -fx;
	}

	int x(int lx, int lz) {
		return cx + lx * rx + lz * fx;
	}

	int z(int lx, int lz) {
		return cz + lx * rz + lz * fz;
	}

	static String name(int dx, int dz) {
		if (dx == 0 && dz < 0) return "north";
		if (dx == 0 && dz > 0) return "south";
		if (dx > 0) return "east";
		return "west";
	}

	String front() {
		return name(fx, fz);
	}

	String back() {
		return name(-fx, -fz);
	}

	String right() {
		return name(rx, rz);
	}

	String left() {
		return name(-rx, -rz);
	}

	String fix(String s) {
		if (s.indexOf('{') < 0) return s;
		return s.replace("{F}", front()).replace("{B}", back()).replace("{R}", right()).replace("{L}", left());
	}

	/** True when the building stretches along the world x axis in its local x direction. */
	boolean widthAlongX() {
		return rx != 0;
	}
}
