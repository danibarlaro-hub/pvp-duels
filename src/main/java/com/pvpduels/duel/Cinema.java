package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.effects.DomainArt;
import com.pvpduels.effects.Effects;
import com.pvpduels.effects.Victory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The finale of a 1v1 duel. Everybody (both players and the spectators) is taken out of their body and put in a camera that starts on
 * the floor in the middle of the arena and rises 7 blocks while it curves to the side and bends toward the winner's end, always
 * looking at the loser but keeping the winner in the picture. Meanwhile each player's "double" (a mannequin with their skin and armor)
 * stands where that player started, so nobody notices. The winner's effects play from there, or, if the winner has the Domain
 * Expansion equipped, a dark dome grows over the arena. A tie where BOTH players have a Domain Expansion makes the two domains
 * push each other until they get tired. Any other tie ends the duel without anything.
 * <p>
 * Everything is undone when the finale ends, when somebody leaves, or when the duel closes: viewers get their body, game mode and
 * (if the finale ended normally) their start place back, and the camera and the doubles disappear.
 */
public final class Cinema {
	public enum Kind { NORMAL, DOMAIN, CLASH }

	/** The self-test turns the finale off for its older tests and on for its own. */
	public static boolean enabled = true;

	/** How high the camera goes. */
	public static final double RISE = 7.0;
	/** The doubles and the camera exist this many ticks before the viewers get the camera (the clients must learn about them first). */
	static final int PRE = 3;
	/** 1.5 seconds with the hands joined. */
	static final int HANDS = 30;
	static final int EXPAND = 40;
	static final int HOLD = 60;
	static final int COLLAPSE = 20;
	static final int CLASH_EXPAND = 30;
	static final int PUSH = 100;
	static final int EXHALE = 18;
	static final int TIE_TITLE = 60;
	static final int NORMAL_TICKS = 100;
	/** After the camera stops it stays a little while, so the last fireworks can be seen. */
	static final int TAIL = 25;

	private static final String DOUBLE_TAG = "pvpduels_double";
	private static final String CAMERA_TAG = "pvpduels_camera";

	private static final List<Scene> SCENES = new ArrayList<>();
	private static int started;
	private static int cameraSets;
	private static int cameraMoves;

	private Cinema() {}

	// ------------------------------------------------------------------ for the self-test

	public static int scenesStarted() {
		return started;
	}

	public static int activeScenes() {
		return SCENES.size();
	}

	/** How many times a viewer was given the camera. */
	public static int cameraSets() {
		return cameraSets;
	}

	/** How many position packets the camera sent to viewers. */
	public static int cameraMoves() {
		return cameraMoves;
	}

	public static void resetForTest() {
		for (Scene scene : new ArrayList<>(SCENES)) scene.stop(false);
		SCENES.clear();
		started = 0;
		cameraSets = 0;
		cameraMoves = 0;
		enabled = true;
	}

	/** The kind and the state of the finale of this duel, for the self-test. Null when there is none. */
	static Scene sceneOf(Duel duel) {
		for (Scene s : SCENES) if (s.duel == duel) return s;
		return null;
	}

	// ------------------------------------------------------------------ starting and stopping

	private static boolean hasDomain(UUID id) {
		return Effects.explosionSlot(id).equals("domain_expansion");
	}

	/**
	 * Plans the finale of a duel that just ended. {@code winnerIdx} is the winner (0 or 1), or -1 for a tie. Returns how many ticks the
	 * duel must wait before it closes, or 0 when there is no finale (then the old, simple celebration is played by the caller).
	 */
	static int begin(Duel duel, int winnerIdx) {
		if (!enabled) return 0;
		MinecraftServer server = duel.server();
		for (int i = 0; i < 2; i++) {
			if (duel.player(i) == null || duel.isRestored(i)) return 0;
		}
		Kind kind;
		if (winnerIdx >= 0) {
			kind = hasDomain(duel.ids[winnerIdx]) ? Kind.DOMAIN : Kind.NORMAL;
		} else {
			// A tie: nothing happens unless both players have a Domain Expansion.
			if (!(hasDomain(duel.ids[0]) && hasDomain(duel.ids[1]))) return 0;
			kind = Kind.CLASH;
		}
		ServerLevel level = duel.space.level(server);
		if (level == null) return 0;
		Scene scene = new Scene(duel, server, level, kind, winnerIdx);
		if (!scene.spawn()) return 0;
		SCENES.add(scene);
		started++;
		return scene.total + 20;
	}

	/** The duel is closing (or the server stops): the finale ends right now and everybody gets their body back. */
	static void stop(Duel duel) {
		for (Scene s : new ArrayList<>(SCENES)) if (s.duel == duel) s.stop(false);
	}

	/** A player is leaving the finale (/leave, or being sent back): they get their own eyes back. */
	public static void release(ServerPlayer p) {
		for (Scene s : new ArrayList<>(SCENES)) s.releaseOne(p.getUUID(), false);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Cinema::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Scene s : new ArrayList<>(SCENES)) s.stop(false);
			SCENES.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		if (SCENES.isEmpty()) return;
		Iterator<Scene> it = SCENES.iterator();
		List<Scene> done = new ArrayList<>();
		while (it.hasNext()) {
			Scene s = it.next();
			try {
				if (!s.tick()) done.add(s);
			} catch (RuntimeException e) {
				PvpDuelsMod.LOGGER.warn("The finale of {} vs {} failed: {}", s.duel.names[0], s.duel.names[1], e.toString());
				done.add(s);
			}
		}
		for (Scene s : done) s.stop(true);
		SCENES.removeAll(done);
	}

	// ------------------------------------------------------------------ small math

	static double ease(double p) {
		p = Math.max(0, Math.min(1, p));
		return p * p * (3 - 2 * p);
	}

	static float yawToward(Vec3 from, Vec3 to) {
		return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
	}

	// ------------------------------------------------------------------ one finale

	private static final class Rider {
		final UUID id;
		/** 0 or 1 for a duelist, -1 for a spectator. */
		final int index;
		GameType previous = GameType.SURVIVAL;
		boolean riding;

		Rider(UUID id, int index) {
			this.id = id;
			this.index = index;
		}
	}

	/** What a finale looks like from outside (the self-test reads it). */
	static final class Scene {
		final Duel duel;
		final MinecraftServer server;
		final ServerLevel level;
		final Kind kind;
		/** The winner, or -1 for the clash. */
		final int win;
		final String id = UUID.randomUUID().toString().substring(0, 8);
		final Vec3[] stage = new Vec3[2];
		final float[] yaw = new float[2];
		final Vec3 center;
		/** Half the distance between the two starts. */
		final double half;
		/** Unit vector from the start of player 0 to the start of player 1. */
		final Vec3 axis;
		/** Unit vectors from the center to the winner's end, and to the side. */
		final Vec3 toWinner;
		final Vec3 side;
		final int camTicks;
		final int total;
		Entity camera;
		final Entity[] doubles = new Entity[2];
		final List<Rider> riders = new ArrayList<>();
		int age;
		boolean ended;
		private Vec3 smooth;
		private float lastYaw = Float.NaN;
		/** The last position and look of the camera. */
		Vec3 cameraAt;
		float cameraYaw;
		float cameraPitch;

		Scene(Duel duel, MinecraftServer server, ServerLevel level, Kind kind, int win) {
			this.duel = duel;
			this.server = server;
			this.level = level;
			this.kind = kind;
			this.win = win;
			for (int i = 0; i < 2; i++) {
				double[] s = duel.space.spawn(i);
				stage[i] = new Vec3(s[0], s[1], s[2]);
			}
			center = new Vec3((stage[0].x + stage[1].x) / 2, Math.min(stage[0].y, stage[1].y), (stage[0].z + stage[1].z) / 2);
			Vec3 flat = new Vec3(stage[1].x - stage[0].x, 0, stage[1].z - stage[0].z);
			double length = flat.length();
			axis = length < 1e-3 ? new Vec3(1, 0, 0) : flat.scale(1.0 / length);
			half = Math.max(5.0, length / 2);
			for (int i = 0; i < 2; i++) yaw[i] = yawToward(stage[i], center);
			int w = win >= 0 ? win : 0;
			toWinner = w == 0 ? axis.scale(-1) : axis;
			side = new Vec3(-toWinner.z, 0, toWinner.x);
			int cam = switch (kind) {
				case NORMAL -> NORMAL_TICKS;
				case DOMAIN -> HANDS + EXPAND + HOLD;
				case CLASH -> HANDS + CLASH_EXPAND + PUSH;
			};
			camTicks = cam;
			int length2 = switch (kind) {
				case NORMAL -> NORMAL_TICKS + TAIL;
				case DOMAIN -> HANDS + EXPAND + HOLD + COLLAPSE + 20;
				case CLASH -> HANDS + CLASH_EXPAND + PUSH + EXHALE + TIE_TITLE;
			};
			total = PRE + length2;
		}

		// -------------------------------------------------------------- spawning

		boolean spawn() {
			Vec3 start = cameraPosition(0);
			camera = summon("minecraft:text_display", CAMERA_TAG + "_" + id, start, 0f, "");
			if (camera == null) return false;
			if (camera instanceof Display display) display.setPosRotInterpolationDuration(2);
			for (int i = 0; i < 2; i++) {
				ServerPlayer p = duel.player(i);
				doubles[i] = summon("minecraft:mannequin", DOUBLE_TAG + "_" + id + "_" + i, stage[i], yaw[i], doubleNbt(p));
				if (doubles[i] == null) {
					discardEntities();
					return false;
				}
				finishDouble(doubles[i], p, yaw[i]);
			}
			cameraAt = start;
			return true;
		}

		private static String doubleNbt(ServerPlayer p) {
			String name = p.getName().getString().replace("\\", "").replace("\"", "");
			UUID u = p.getUUID();
			long m = u.getMostSignificantBits();
			long l = u.getLeastSignificantBits();
			return ",Invulnerable:1b,Silent:1b,NoGravity:1b,immovable:1b,hide_description:1b,description:{text:\"\"}"
				+ ",CustomName:{text:\"" + name + "\"},CustomNameVisible:0b"
				+ ",profile:{name:\"" + name + "\",id:[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]}";
		}

		private static void finishDouble(Entity e, ServerPlayer p, float yaw) {
			e.setYHeadRot(yaw);
			e.setYBodyRot(yaw);
			if (e instanceof LivingEntity living) {
				EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
				for (EquipmentSlot slot : slots) living.setItemSlot(slot, p.getItemBySlot(slot).copy());
			}
		}

		/** Summons an entity with the command (the same way the NPCs are made) and finds it again by its tag. */
		private Entity summon(String type, String tag, Vec3 at, float yawDegrees, String extra) {
			String command = String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f {Tags:[\"%s\"],Rotation:[%.2ff,0f]%s}", type, at.x, at.y, at.z, tag, yawDegrees, extra);
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
			AABB box = new AABB(at.x - 3, at.y - 3, at.z - 3, at.x + 3, at.y + 4, at.z + 3);
			List<Entity> found = level.getEntities((Entity) null, box, e -> e.entityTags().contains(tag));
			return found.isEmpty() ? null : found.get(0);
		}

		private void discardEntities() {
			if (camera != null) camera.discard();
			for (int i = 0; i < 2; i++) if (doubles[i] != null) doubles[i].discard();
		}

		// -------------------------------------------------------------- the camera

		/** Where the camera is {@code a} ticks after the viewers got it. */
		Vec3 cameraPosition(int a) {
			double p = Math.min(1.0, a / (double) Math.max(1, camTicks));
			double e = ease(p);
			double theta = e * Math.PI / 2;
			// sideways first, then bending toward the winner's end
			double out = half * Math.sin(theta);
			double toward = half * 0.45 * (1 - Math.cos(theta));
			Vec3 flat = center.add(side.scale(out)).add(toWinner.scale(toward));
			return new Vec3(flat.x, center.y + 0.3 + RISE * e, flat.z);
		}

		/** The way the camera looks: at the loser, turned toward the winner as much as needed to keep both in the picture. */
		private Vec3 look(Vec3 cam) {
			if (kind == Kind.CLASH) return center.add(0, 1.5, 0).subtract(cam).normalize();
			int l = 1 - win;
			Vec3 dl = stage[l].add(0, 1.0, 0).subtract(cam).normalize();
			Vec3 dw = stage[win].add(0, 1.0, 0).subtract(cam).normalize();
			double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dl.dot(dw)))));
			double k = Math.max(0, Math.min(0.9, (angle - 30) / 60.0));
			Vec3 dir = dl.add(dw.scale(k));
			return dir.lengthSqr() < 1e-6 ? dl : dir.normalize();
		}

		private void moveCamera(int a) {
			Vec3 pos = cameraPosition(a);
			Vec3 dir = look(pos);
			smooth = smooth == null ? dir : smooth.scale(0.65).add(dir.scale(0.35));
			Vec3 d = smooth.lengthSqr() < 1e-6 ? dir : smooth.normalize();
			float yawNow = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
			if (!Float.isNaN(lastYaw)) {
				while (yawNow - lastYaw > 180) yawNow -= 360;
				while (yawNow - lastYaw < -180) yawNow += 360;
			}
			lastYaw = yawNow;
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
			cameraAt = pos;
			cameraYaw = yawNow;
			cameraPitch = pitch;
			camera.snapTo(pos.x, pos.y, pos.z, yawNow, pitch);
			Packet<?> packet = new ClientboundTeleportEntityPacket(camera.getId(), new PositionMoveRotation(pos, Vec3.ZERO, yawNow, pitch), Set.of(), false);
			for (Rider r : riders) {
				ServerPlayer p = r.riding ? server.getPlayerList().getPlayer(r.id) : null;
				if (p == null) continue;
				p.connection.send(packet);
				cameraMoves++;
			}
		}

		// -------------------------------------------------------------- viewers

		private void startRiding() {
			for (int i = 0; i < 2; i++) {
				ServerPlayer p = duel.player(i);
				if (p != null && !duel.isRestored(i)) riders.add(new Rider(p.getUUID(), i));
			}
			for (ServerPlayer p : Spectators.watchers(duel)) riders.add(new Rider(p.getUUID(), -1));
			moveCameraEntityOnly();
			for (Rider r : riders) {
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p == null) continue;
				r.previous = p.gameMode();
				p.closeContainer();
				p.setGameMode(GameType.SPECTATOR);
				p.setCamera(camera);
				r.riding = true;
				cameraSets++;
			}
		}

		private void moveCameraEntityOnly() {
			Vec3 pos = cameraPosition(0);
			camera.snapTo(pos.x, pos.y, pos.z, 0f, 0f);
		}

		/** A viewer gets their own eyes back. {@code toStage}: the duelists also go back to where they started. */
		void releaseOne(UUID id, boolean toStage) {
			for (Rider r : riders) {
				if (!r.id.equals(id) || !r.riding) continue;
				r.riding = false;
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p == null) continue;
				if (p.getCamera() != p) p.setCamera(null);
				p.setGameMode(r.previous);
				if (toStage) {
					Vec3 at = r.index >= 0 ? stage[r.index] : center.add(0, 6, 0);
					p.teleportTo(level, at.x, at.y, at.z, Set.of(), r.index >= 0 ? yaw[r.index] : p.getYRot(), 0f, true);
					p.setDeltaMovement(0, 0, 0);
				}
			}
		}

		void stop(boolean normal) {
			if (ended) return;
			ended = true;
			for (Rider r : riders) releaseOne(r.id, normal);
			discardEntities();
		}

		private List<ServerPlayer> audience() {
			List<ServerPlayer> out = new ArrayList<>();
			for (ServerPlayer p : level.players()) {
				boolean owner = p.getUUID().equals(duel.ids[0]) || p.getUUID().equals(duel.ids[1]);
				if (!owner && Effects.hideOthers(p.getUUID())) continue;
				out.add(p);
			}
			return out;
		}

		private void title(Component title, Component subtitle, int in, int stay, int out) {
			for (ServerPlayer p : level.players()) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
				p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
				p.connection.send(new ClientboundSetTitleTextPacket(title));
			}
		}

		private void sound(Vec3 at, net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
			level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
		}

		private void sound(Vec3 at, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> event, float volume, float pitch) {
			level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
		}

		private void pose(int i, Pose pose) {
			if (doubles[i] != null && doubles[i].isAlive()) doubles[i].setPose(pose);
		}

		// -------------------------------------------------------------- every tick

		/** Returns false when the finale is over. */
		boolean tick() {
			if (ended) return false;
			if (duel.closed) return false;
			if (camera == null || !camera.isAlive()) return false;
			age++;
			if (age < PRE) return true;
			if (age == PRE) {
				startRiding();
				boolean anyDuelist = false;
				for (Rider r : riders) if (r.index >= 0) anyDuelist = true;
				if (!anyDuelist) {
					// Both duelists are gone: nothing to film, but the winner (if still here) still celebrates.
					ServerPlayer winner = winnerPlayer();
					if (winner != null) Victory.play(winner, null);
					return false;
				}
			}
			int a = age - PRE;
			moveCamera(a);
			switch (kind) {
				case NORMAL -> normal(a);
				case DOMAIN -> domain(a);
				case CLASH -> clash(a);
			}
			return age < total;
		}

		private ServerPlayer winnerPlayer() {
			return win < 0 ? null : server.getPlayerList().getPlayer(duel.ids[win]);
		}

		private void normal(int a) {
			if (a == 1) {
				ServerPlayer winner = winnerPlayer();
				if (winner != null) Victory.playAt(winner, stage[win], yaw[win], stage[1 - win]);
			}
		}

		// -------------------------------------------------------------- the Domain Expansion

		private Vec3 hands(int i) {
			double r = Math.toRadians(yaw[i]);
			return stage[i].add(-Math.sin(r) * 0.4, 0.95, Math.cos(r) * 0.4);
		}

		private void domain(int a) {
			List<ServerPlayer> viewers = audience();
			int owner = win;
			if (a < HANDS) {
				if (a == 0) {
					pose(owner, Pose.CROUCHING);
					sound(stage[owner], SoundEvents.BEACON_ACTIVATE, 2.0f, 0.6f);
				}
				DomainArt.swirl(level, viewers, hands(owner), ParticleTypes.ENCHANT, a);
				DomainArt.swirl(level, viewers, hands(owner), ParticleTypes.SOUL_FIRE_FLAME, a + 7);
				return;
			}
			if (a == HANDS) {
				pose(owner, Pose.STANDING);
				sound(center, SoundEvents.ENDER_DRAGON_GROWL, 4.0f, 0.6f);
				title(Component.literal("Domain Expansion: ").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)
						.append(Component.literal("I win!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)),
					Component.literal(duel.names[owner]).withStyle(ChatFormatting.GRAY), 5, 60, 15);
			}
			int t = a - HANDS;
			double big = half + 8;
			double radius;
			if (t < EXPAND) radius = big * ease(t / (double) EXPAND);
			else if (t < EXPAND + HOLD) radius = big;
			else radius = big * (1 - ease((t - EXPAND - HOLD) / (double) COLLAPSE));
			if (t >= EXPAND + HOLD + COLLAPSE) return;
			DomainArt.dome(level, viewers, center, radius, 70, DomainArt.voidLook());
			DomainArt.ring(level, viewers, center, radius, 72, 0.15, DomainArt.LINE, a * 0.05);
			DomainArt.ring(level, viewers, center, radius, 72, 1.0, DomainArt.LINE, -a * 0.05);
			DomainArt.haze(level, viewers, center, radius, 6);
			if (t == EXPAND + HOLD) sound(center, SoundEvents.GENERIC_EXPLODE, 3.0f, 0.5f);
		}

		// -------------------------------------------------------------- the clash

		private void clash(int a) {
			List<ServerPlayer> viewers = audience();
			if (a < HANDS) {
				if (a == 0) {
					pose(0, Pose.CROUCHING);
					pose(1, Pose.CROUCHING);
					sound(center, SoundEvents.BEACON_ACTIVATE, 2.0f, 0.6f);
				}
				for (int i = 0; i < 2; i++) DomainArt.swirl(level, viewers, hands(i), i == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME, a);
				return;
			}
			if (a == HANDS) {
				pose(0, Pose.STANDING);
				pose(1, Pose.STANDING);
				sound(center, SoundEvents.ENDER_DRAGON_GROWL, 4.0f, 0.6f);
				title(Component.literal("Domain Expansion!").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD),
					Component.literal(duel.names[0] + " vs " + duel.names[1]).withStyle(ChatFormatting.GRAY), 5, 50, 10);
			}
			int t = a - HANDS;
			double rho0 = half * 0.85;
			if (t < CLASH_EXPAND + PUSH) {
				double grow = t < CLASH_EXPAND ? ease(t / (double) CLASH_EXPAND) : 1.0;
				double s = 0;
				double push = 0.2;
				if (t >= CLASH_EXPAND) {
					int p = t - CLASH_EXPAND;
					double amplitude = half * 0.5 * (0.35 + 0.65 * Math.sin(Math.PI * p / (double) PUSH));
					s = amplitude * Math.sin(p * Math.PI * 2 / 26.0);
					push = 0.3 + 0.7 * Math.sin(Math.PI * p / (double) PUSH);
				}
				double rho = rho0 * grow;
				Vec3 plane = center.add(axis.scale(s * grow));
				double r0 = Math.sqrt((half + s) * (half + s) + rho0 * rho0) * grow;
				double r1 = Math.sqrt((half - s) * (half - s) + rho0 * rho0) * grow;
				DomainArt.cap(level, viewers, stage[0], r0, 45, DomainArt.redLook(), axis, plane);
				DomainArt.cap(level, viewers, stage[1], r1, 45, DomainArt.blueLook(), axis.scale(-1), plane);
				DomainArt.wall(level, viewers, plane, axis, rho, push);
				if (t == CLASH_EXPAND) sound(center, SoundEvents.GENERIC_EXPLODE, 3.0f, 0.5f);
				return;
			}
			int e = t - CLASH_EXPAND - PUSH;
			if (e == 0) {
				pose(0, Pose.CROUCHING);
				pose(1, Pose.CROUCHING);
				sound(center, SoundEvents.PLAYER_BREATH, 3.0f, 0.7f);
			}
			if (e < EXHALE) {
				DomainArt.exhale(level, viewers, center, 2 + e * 1.6);
				return;
			}
			if (e == EXHALE) {
				pose(0, Pose.STANDING);
				pose(1, Pose.STANDING);
				title(Component.literal("I guess it's a tie").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
					Component.literal(duel.names[0] + " and " + duel.names[1] + " are too tired to go on").withStyle(ChatFormatting.DARK_GRAY), 5, 45, 15);
			}
		}
	}
}
