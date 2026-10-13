package com.pvpduels.effects;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The particles a player can win: every particle of the game that needs no extra data, except the ones that cover the screen or lag, and
 * colored dust in the 16 dye colors. Found once, from the game's own list.
 */
public final class Particles {
	public record Def(String id, String name, ParticleOptions options) {}

	/** Particles that cover the screen of whoever is near, or that are huge. */
	private static final Set<String> DENIED = Set.of("elder_guardian", "explosion_emitter", "damage_indicator", "sonic_boom");

	private static List<Def> all;
	private static final Map<String, Def> BY_ID = new HashMap<>();

	private Particles() {}

	public static synchronized List<Def> all() {
		if (all == null) build();
		return all;
	}

	public static synchronized Def find(String id) {
		if (all == null) build();
		return BY_ID.get(id);
	}

	private static void build() {
		List<Def> list = new ArrayList<>();
		for (Identifier key : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
			if (!Identifier.DEFAULT_NAMESPACE.equals(key.getNamespace())) continue;
			ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(key);
			if (!(type instanceof SimpleParticleType simple)) continue;
			String path = key.getPath();
			if (DENIED.contains(path) || path.endsWith("_emitter")) continue;
			list.add(new Def(path, Effects.pretty(path), simple));
		}
		list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		for (int i = 0; i < Effects.COLORS.length; i++) {
			list.add(new Def("dust_" + Effects.COLORS[i], "Dust (" + Effects.pretty(Effects.COLORS[i]) + ")", new DustParticleOptions(Effects.COLOR_RGB[i], 1.3f)));
		}
		all = List.copyOf(list);
		BY_ID.clear();
		for (Def d : all) BY_ID.put(d.id(), d);
	}
}
