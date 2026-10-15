package com.pvpduels.effects;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Finishing blows: a small show, chosen by the player who lands the hit that ends a round, that plays on the player who was knocked out.
 * It is only particles and sounds (even the lightning is drawn): nothing burns, nobody is hurt and no entity is created. Everybody who
 * can see the spot gets it, except the players who hid the effects of others in /effects (the one who chose it always sees it).
 */
public final class LastHit {
	/** The finishing blows. "none" is the default: nothing plays until a player picks one. */
	public enum Style {
		NONE("none", "None", Items.BARRIER, "Nothing plays when you end a round."),
		LIGHTNING("lightning", "Lightning", Items.BLAZE_ROD, "A bolt falls on the player you hit.", "It is only drawn: nothing burns."),
		SHATTER("shatter", "Shatter", Items.GLASS, "The player you hit breaks like glass."),
		FIREWORK("firework", "Firework", Items.FIREWORK_ROCKET, "A burst of sparks and a bang."),
		SOUL("soul", "Soul", Items.SOUL_LANTERN, "Souls rise from the player you hit."),
		TOTEM("totem", "Totem pop", Items.TOTEM_OF_UNDYING, "The golden totem pop of the other player."),
		POOF("poof", "Poof", Items.GUNPOWDER, "A puff of smoke and a small boom."),
		SLIME("slime", "Splat", Items.SLIME_BALL, "A green splat with a squish."),
		SURPRISE("surprise", "Surprise", Items.ENDER_CHEST, "A different one every time.");

		public final String id;
		public final String label;
		public final Item icon;
		public final String[] lore;

		Style(String id, String label, Item icon, String... lore) {
			this.id = id;
			this.label = label;
			this.icon = icon;
			this.lore = lore;
		}
	}

	/** How far from the spot the players still see it (the same as the victory show: 96 blocks). */
	private static final double VIEW_DISTANCE_SQR = 96.0 * 96.0;

	private static final Random RANDOM = new Random();
	private static int played;
	private static String last = "";
	private static int lastViewers;

	private LastHit() {}

	/** The style with this id (or this name, any case), or null when there is none. The empty text is "none". */
	public static Style of(String text) {
		if (text == null) return null;
		String t = text.trim().toLowerCase(Locale.ROOT);
		if (t.isEmpty()) return Style.NONE;
		for (Style s : Style.values()) {
			if (s.id.equals(t) || s.label.toLowerCase(Locale.ROOT).equals(t)) return s;
		}
		return null;
	}

	/** What this player has chosen ({@link Style#NONE} when nothing). */
	public static Style styleOf(UUID id) {
		Style s = of(Effects.lastHit(id));
		return s == null ? Style.NONE : s;
	}

	/** Saves the choice. Returns an error text, or null when it worked. */
	public static String choose(UUID id, String text) {
		Style s = of(text);
		if (s == null) return "There is no finishing blow called '" + text + "'.";
		Effects.setLastHit(id, s == Style.NONE ? "" : s.id);
		return null;
	}

	/** The names of the ones a player can pick, for the command. */
	public static List<String> names() {
		List<String> out = new ArrayList<>();
		for (Style s : Style.values()) out.add(s.id);
		return out;
	}

	// ------------------------------------------------------------------ playing

	/**
	 * Plays the finishing blow of {@code killer} on {@code victim}, if the killer picked one. Returns true when something played.
	 */
	public static boolean play(ServerPlayer killer, ServerPlayer victim) {
		Style style = styleOf(killer.getUUID());
		if (style == Style.NONE) return false;
		ServerLevel level = (ServerLevel) victim.level();
		Vec3 center = victim.getBoundingBox().getCenter();
		List<ServerPlayer> viewers = viewers(level, killer, center);
		show(level, viewers, style, center);
		return true;
	}

	/** Shows one style to this player only, in front of them. */
	public static void preview(ServerPlayer p, Style style) {
		if (style == Style.NONE) return;
		ServerLevel level = (ServerLevel) p.level();
		Vec3 look = p.getLookAngle();
		Vec3 center = new Vec3(p.getX() + look.x * 3.0, p.getY() + 0.9, p.getZ() + look.z * 3.0);
		show(level, List.of(p), style, center);
	}

	private static List<ServerPlayer> viewers(ServerLevel level, ServerPlayer killer, Vec3 at) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (p != killer) {
				if (Effects.hideOthers(p.getUUID())) continue;
				if (p.getCamera() == p && p.position().distanceToSqr(at) > VIEW_DISTANCE_SQR) continue;
			}
			out.add(p);
		}
		return out;
	}

	private static void show(ServerLevel level, List<ServerPlayer> viewers, Style style, Vec3 c) {
		if (style == Style.SURPRISE) {
			Style[] all = Style.values();
			do {
				style = all[RANDOM.nextInt(all.length)];
			} while (style == Style.NONE || style == Style.SURPRISE);
		}
		played++;
		last = style.id;
		lastViewers = viewers.size();
		switch (style) {
			case LIGHTNING -> lightning(level, viewers, c);
			case SHATTER -> shatter(level, viewers, c);
			case FIREWORK -> firework(level, viewers, c);
			case SOUL -> soul(level, viewers, c);
			case TOTEM -> totem(level, viewers, c);
			case POOF -> poof(level, viewers, c);
			case SLIME -> slime(level, viewers, c);
			default -> { }
		}
	}

	// ------------------------------------------------------------------ the styles

	private static void lightning(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		double feet = c.y - 0.9;
		double x = c.x;
		double z = c.z;
		double top = 16.0;
		// the bolt zigzags down and ends right on the player
		for (double h = top; h > 0.0; h -= 0.6) {
			double share = h / top;
			x = c.x + (RANDOM.nextDouble() - 0.5) * 1.6 * share;
			z = c.z + (RANDOM.nextDouble() - 0.5) * 1.6 * share;
			emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, x, feet + h, z, 3, 0.05, 0.05, 0.05, 0.01);
			emit(level, viewers, ParticleTypes.END_ROD, x, feet + h, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
		emit(level, viewers, ParticleTypes.ELECTRIC_SPARK, c.x, feet + 0.2, c.z, 40, 0.6, 0.2, 0.6, 0.35);
		emit(level, viewers, ParticleTypes.CLOUD, c.x, feet + 0.2, c.z, 8, 0.4, 0.1, 0.4, 0.05);
		sound(level, viewers, SoundEvents.LIGHTNING_BOLT_IMPACT, c, 1.0f, 1.0f);
		sound(level, viewers, SoundEvents.LIGHTNING_BOLT_THUNDER, c, 2.5f, 0.9f);
	}

	private static void shatter(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		emit(level, viewers, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState()), c.x, c.y, c.z, 70, 0.45, 0.8, 0.45, 0.2);
		emit(level, viewers, ParticleTypes.CRIT, c.x, c.y, c.z, 25, 0.4, 0.7, 0.4, 0.3);
		sound(level, viewers, SoundEvents.GLASS_BREAK, c, 1.2f, 0.9f);
		sound(level, viewers, SoundEvents.GLASS_BREAK, c, 1.0f, 1.4f);
	}

	private static void firework(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		for (int i = 0; i < 48; i++) {
			// a ball: a random direction, the same speed for all
			double dx = RANDOM.nextGaussian();
			double dy = RANDOM.nextGaussian();
			double dz = RANDOM.nextGaussian();
			double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (len < 1.0E-4) continue;
			ParticleOptions kind = i % 3 == 0 ? ParticleTypes.END_ROD : ParticleTypes.FIREWORK;
			for (ServerPlayer v : viewers) Quality.sendFlying(level, v, kind, c.x, c.y, c.z, dx / len, dy / len, dz / len, 0.35);
		}
		sound(level, viewers, SoundEvents.FIREWORK_ROCKET_BLAST, c, 1.5f, 1.0f);
		sound(level, viewers, SoundEvents.FIREWORK_ROCKET_TWINKLE, c, 1.5f, 1.0f);
	}

	private static void soul(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		emit(level, viewers, ParticleTypes.SOUL, c.x, c.y - 0.5, c.z, 28, 0.35, 0.4, 0.35, 0.06);
		emit(level, viewers, ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y - 0.6, c.z, 18, 0.3, 0.3, 0.3, 0.05);
		emit(level, viewers, ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 10, 0.3, 0.5, 0.3, 0.04);
		sound(level, viewers, SoundEvents.SOUL_ESCAPE, c, 1.5f, 1.0f);
	}

	private static void totem(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		emit(level, viewers, ParticleTypes.TOTEM_OF_UNDYING, c.x, c.y, c.z, 60, 0.4, 0.8, 0.4, 0.5);
		sound(level, viewers, SoundEvents.TOTEM_USE, c, 0.8f, 1.0f);
	}

	private static void poof(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		emit(level, viewers, ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0.0, 0.0, 0.0, 0.0);
		emit(level, viewers, ParticleTypes.POOF, c.x, c.y, c.z, 30, 0.4, 0.6, 0.4, 0.1);
		emit(level, viewers, ParticleTypes.CLOUD, c.x, c.y, c.z, 14, 0.4, 0.6, 0.4, 0.08);
		sound(level, viewers, SoundEvents.GENERIC_EXPLODE, c, 0.6f, 1.4f);
	}

	private static void slime(ServerLevel level, List<ServerPlayer> viewers, Vec3 c) {
		emit(level, viewers, ParticleTypes.ITEM_SLIME, c.x, c.y, c.z, 50, 0.4, 0.6, 0.4, 0.2);
		emit(level, viewers, ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 8, 0.4, 0.6, 0.4, 0.0);
		sound(level, viewers, SoundEvents.SLIME_SQUISH, c, 1.5f, 0.8f);
		sound(level, viewers, SoundEvents.SLIME_SQUISH, c, 1.2f, 1.2f);
	}

	// ------------------------------------------------------------------ sending

	private static void emit(ServerLevel level, List<ServerPlayer> viewers, ParticleOptions options, double x, double y, double z, int count,
		double dx, double dy, double dz, double speed) {
		for (ServerPlayer v : viewers) Quality.send(level, v, options, x, y, z, count, dx, dy, dz, speed);
	}

	private static void sound(ServerLevel level, List<ServerPlayer> viewers, SoundEvent event, Vec3 at, float volume, float pitch) {
		sound(level, viewers, Holder.direct(event), at, volume, pitch);
	}

	private static void sound(ServerLevel level, List<ServerPlayer> viewers, Holder<SoundEvent> event, Vec3 at, float volume, float pitch) {
		for (ServerPlayer v : viewers) {
			v.connection.send(new ClientboundSoundPacket(event, SoundSource.MASTER, at.x, at.y, at.z, volume, pitch, level.getRandom().nextLong()));
		}
	}

	// ------------------------------------------------------------------ for the tests

	public static int played() {
		return played;
	}

	public static String last() {
		return last;
	}

	public static int lastViewers() {
		return lastViewers;
	}

	public static void resetForTest() {
		played = 0;
		last = "";
		lastViewers = 0;
	}
}
