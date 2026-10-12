package com.pvpduels.worldgen;

/** Small deterministic value noise (the same seed always makes the same island). */
final class Noise {
	private final long seed;

	Noise(long seed) {
		this.seed = seed;
	}

	private double cell(int ix, int iz) {
		long h = ix * 374761393L + iz * 668265263L + seed * 1442695040888963407L;
		h = (h ^ (h >>> 13)) * 1274126177L;
		h ^= h >>> 16;
		h *= 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		return (h & 0xFFFFFFL) / (double) 0x1000000;
	}

	/** Smooth noise between 0 and 1. */
	double n(double x, double z) {
		int x0 = (int) Math.floor(x);
		int z0 = (int) Math.floor(z);
		double fx = x - x0;
		double fz = z - z0;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		double a = cell(x0, z0);
		double b = cell(x0 + 1, z0);
		double c = cell(x0, z0 + 1);
		double d = cell(x0 + 1, z0 + 1);
		return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
	}

	/** Fractal noise between 0 and 1. */
	double fbm(double x, double z, int octaves) {
		double sum = 0, amp = 0.5, norm = 0;
		for (int i = 0; i < octaves; i++) {
			sum += amp * n(x, z);
			norm += amp;
			x *= 2.03;
			z *= 2.03;
			amp *= 0.5;
		}
		return sum / norm;
	}

	/** Sharp ridges between 0 and 1 (1 on the ridge lines). */
	double ridged(double x, double z) {
		return 1 - Math.abs(2 * fbm(x, z, 3) - 1) * 2 > 0 ? 1 - Math.abs(2 * fbm(x, z, 3) - 1) * 2 : 0;
	}
}
