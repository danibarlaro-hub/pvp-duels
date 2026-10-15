package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.effects.DomainArt;
import com.pvpduels.effects.DomeArt;
import com.pvpduels.effects.Effects;
import com.pvpduels.effects.Jingles;
import com.pvpduels.effects.Props;
import com.pvpduels.effects.Quality;
import com.pvpduels.effects.Victory;
import com.pvpduels.util.Msg;
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
	/** NORMAL: the camera rises and the usual effects play. DOMAIN and FILM: a film of the winner's explosion ({@link Films}). CLASH: two domains push each other. */
	public enum Kind { NORMAL, DOMAIN, CLASH, FILM }

	/** The self-test turns the finale off for its older tests and on for its own. */
	public static boolean enabled = true;

	/** How high the camera goes. */
	public static final double RISE = 7.0;
	/** The doubles and the camera exist this many ticks before the viewers get the camera (the clients must learn about them first). */
	static final int PRE = 3;
	/** 1.5 seconds with the hands joined. */
	static final int HANDS = 30;
	static final int CLASH_EXPAND = 30;
	static final int PUSH = 100;
	static final int EXHALE = 18;
	static final int TIE_TITLE = 60;
	static final int NORMAL_TICKS = 100;
	/** After the camera stops it stays a little while, so the last fireworks can be seen. */
	static final int TAIL = 25;
	/** The sad tune of the loser starts this many ticks after the happy one ends. */
	static final int DEFEAT_GAP = 6;

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
		return DomeArt.Variant.of(Effects.explosionSlot(id)) != null;
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
		Films.Film film = null;
		if (winnerIdx >= 0) {
			film = Films.of(Effects.explosionSlot(duel.ids[winnerIdx]));
			kind = film == null ? Kind.NORMAL : (film.domain() ? Kind.DOMAIN : Kind.FILM);
		} else {
			// A tie: nothing happens unless both players have a Domain Expansion.
			if (!(hasDomain(duel.ids[0]) && hasDomain(duel.ids[1]))) return 0;
			kind = Kind.CLASH;
		}
		ServerLevel level = duel.space.level(server);
		if (level == null) return 0;
		Scene scene = new Scene(duel, server, level, kind, winnerIdx, film);
		if (!scene.spawn()) return 0;
		SCENES.add(scene);
		started++;
		return scene.total + 20;
	}

	// ------------------------------------------------------------------ try-outs (the Explosion Dummy and /effects preview)

	/** At most this many try-out films at the same time. */
	static final int MAX_TRY_OUTS = 3;
	/** How far from the dummy the winner's side of the stage is (the same as the distance between the two starts of a duel arena). */
	static final double TRY_OUT_GAP = 26.0;

	/** True while this player is watching a try-out film (through its camera, or about to). */
	public static boolean isTryingOut(UUID id) {
		return tryOutOf(id) != null;
	}

	/** The try-out film this player is watching, or null. */
	static Scene tryOutOf(UUID id) {
		for (Scene s : SCENES) if (s.duel == null && !s.ended && id.equals(s.ids[0]) && s.watching(id)) return s;
		return null;
	}

	/** True when the film of this explosion can be played for this player now. Otherwise the plain effects are shown. */
	public static boolean canTryOut(ServerPlayer p, String explosion) {
		return enabled && Films.of(explosion) != null && (isTryingOut(p.getUUID()) || !DuelManager.isBusy(p.getUUID()));
	}

	/**
	 * The player watches the film of one of THEIR explosions land on {@code target} (the Explosion Dummy), with the camera of a finale: the
	 * winner is a double of the player on this side of the dummy. Returns null when it started, or what is wrong in plain words.
	 */
	public static String tryOut(ServerPlayer p, String explosion, Vec3 target) {
		UUID me = p.getUUID();
		if (!Effects.ownsExplosion(me, explosion)) return "You do not own that explosion.";
		Films.Film film = Films.of(explosion);
		if (film == null || !enabled) return "That explosion has no film.";
		if (isTryingOut(me)) return "Wait a moment: your film is still playing.";
		int playing = 0;
		for (Scene s : SCENES) if (s.duel == null) playing++;
		if (playing >= MAX_TRY_OUTS) return "Too many films are playing right now. Try again in a few seconds.";
		double dx = p.getX() - target.x;
		double dz = p.getZ() - target.z;
		double length = Math.hypot(dx, dz);
		if (length < 0.5) {
			double yaw = Math.toRadians(p.getYRot());
			dx = Math.sin(yaw);
			dz = -Math.cos(yaw);
			length = 1;
		}
		Vec3 winnerAt = new Vec3(target.x + dx / length * TRY_OUT_GAP, target.y, target.z + dz / length * TRY_OUT_GAP);
		Scene scene = Scene.tryOut(p, film, winnerAt, target);
		if (!scene.spawn()) return "The film could not start. Try again.";
		SCENES.add(scene);
		started++;
		return null;
	}

	/** Players whose /effects preview waits for the next tick. */
	private static final List<UUID> PREVIEWS = new ArrayList<>();

	/** What the last refused spawn of a film looked like (for the self-test and the log). */
	public static String lastSpawnFailure = "";

	/** /effects preview and the Preview button: the film of the equipped explosion if there is one, else the plain preview. */
	public static void previewOwn(ServerPlayer p) {
		String explosion = Effects.explosionSlot(p.getUUID());
		if (explosion.isEmpty() || !canTryOut(p, explosion)) {
			Victory.preview(p);
			return;
		}
		// the command that called this is still running, and the game queues a summon made inside a command until the command is over:
		// the film starts on the next tick instead
		if (!PREVIEWS.contains(p.getUUID())) PREVIEWS.add(p.getUUID());
	}

	private static void startPreviews(MinecraftServer server) {
		List<UUID> asked = new ArrayList<>(PREVIEWS);
		PREVIEWS.clear();
		for (UUID id : asked) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			String explosion = Effects.explosionSlot(id);
			String problem = explosion.isEmpty() ? "You have no explosion equipped." : null;
			if (problem == null) {
				double yaw = Math.toRadians(p.getYRot());
				Vec3 target = new Vec3(p.getX() - Math.sin(yaw) * TRY_OUT_GAP, p.getY(), p.getZ() + Math.cos(yaw) * TRY_OUT_GAP);
				problem = tryOut(p, explosion, target);
			}
			if (problem != null) {
				p.sendSystemMessage(Msg.err(problem));
				Victory.preview(p);
			}
		}
	}

	/** How far from the middle of the scene a free camera may fly (blocks). */
	static final double FREE_RADIUS = 70.0;

	/** /freecam: the viewer of a film switches between the camera of the film and flying around by themselves. */
	public static boolean toggleFreeCam(ServerPlayer p) {
		for (Scene s : SCENES) if (s.toggleFree(p)) return true;
		p.sendSystemMessage(Msg.err("There is no film to look around in right now."));
		return false;
	}

	/** A key of the films was pressed (the client sends it): 0 skips the film, 1 switches the free camera. Nothing happens outside of a film. */
	public static void filmKey(ServerPlayer p, int action) {
		for (Scene s : SCENES) {
			if (action == com.pvpduels.net.FilmKeyPayload.SKIP && s.skipFor(p)) return;
			if (action == com.pvpduels.net.FilmKeyPayload.FREE_CAM && s.toggleFree(p)) return;
		}
	}

	/** True while this player flies around by themselves in a film (the self-test reads it). */
	public static boolean isFree(UUID id) {
		for (Scene s : SCENES) for (Rider r : s.riders) if (r.riding && r.free && r.id.equals(id)) return true;
		return false;
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
		if (!PREVIEWS.isEmpty()) startPreviews(server);
		if (SCENES.isEmpty()) return;
		Iterator<Scene> it = SCENES.iterator();
		List<Scene> done = new ArrayList<>();
		while (it.hasNext()) {
			Scene s = it.next();
			try {
				if (!s.tick()) done.add(s);
			} catch (RuntimeException e) {
				PvpDuelsMod.LOGGER.warn("The finale of {} vs {} failed: {}", s.names[0], s.names[1], e.toString());
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
		/** Where the viewer stood when the camera took them (a try-out puts them back there: the camera drags the body along). */
		Vec3 home;
		float homeYaw;
		float homePitch;
		/** True once the viewer has been seen NOT sneaking, so a key that was already down when the camera began does not skip the finale. */
		boolean sneakReady;
		/** True while the viewer flies around by themselves (free camera) instead of riding the camera of the film. */
		boolean free;

		Rider(UUID id, int index) {
			this.id = id;
			this.index = index;
		}
	}

	/** What a finale looks like from outside (the self-test reads it). */
	static final class Scene {
		/** The duel this finale belongs to, or null for a try-out in the lobby (the Explosion Dummy and /effects preview). */
		final Duel duel;
		/** The two people of the film: the winner and the loser (null for the dummy of a try-out). */
		final UUID[] ids = new UUID[2];
		final String[] names = new String[2];
		final MinecraftServer server;
		final ServerLevel level;
		final Kind kind;
		/** The film of the winner's explosion (a Domain Expansion, the Shooting Star...), or null for the other finales. */
		final Films.Film film;
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
		/** The solid parts of a film (null until the film needs them). */
		Props props;
		/** The meteors of a Meteor Shower film (the film keeps no state, so the scene holds them). */
		final List<com.pvpduels.effects.ExplosionArt.Meteor> meteors = new ArrayList<>();
		/** The tune of the loser (chosen when the finale starts). */
		final Jingles.Tune sad = Jingles.randomDefeat();
		/** The silly sound of the loser (the one the loser chose with /losesound, or a random one), for the viewers who turned them on (the others get the sad tune). */
		final com.pvpduels.effects.MemeSounds.Meme meme;
		private float lastYaw = Float.NaN;
		/** The last position and look of the camera. */
		Vec3 cameraAt;
		float cameraYaw;
		float cameraPitch;

		Scene(Duel duel, MinecraftServer server, ServerLevel level, Kind kind, int win, Films.Film film) {
			this(duel, server, level, kind, win, film, stageOf(duel, 0), stageOf(duel, 1), duel.ids, duel.names);
		}

		private static Vec3 stageOf(Duel duel, int i) {
			double[] s = duel.space.spawn(i);
			return new Vec3(s[0], s[1], s[2]);
		}

		/** A try-out: the film of {@code viewer}'s explosion with the winner at {@code winnerAt} and the target (the dummy) at {@code loserAt}. */
		static Scene tryOut(ServerPlayer viewer, Films.Film film, Vec3 winnerAt, Vec3 loserAt) {
			Kind k = film.domain() ? Kind.DOMAIN : Kind.FILM;
			return new Scene(null, viewer.level().getServer(), (ServerLevel) viewer.level(), k, 0, film, winnerAt, loserAt,
				new UUID[] {viewer.getUUID(), null}, new String[] {viewer.getName().getString(), "the dummy"});
		}

		private Scene(Duel duel, MinecraftServer server, ServerLevel level, Kind kind, int win, Films.Film film, Vec3 stage0, Vec3 stage1, UUID[] who, String[] called) {
			this.duel = duel;
			this.server = server;
			this.level = level;
			this.kind = kind;
			this.film = film;
			this.win = win;
			for (int i = 0; i < 2; i++) {
				ids[i] = who[i];
				names[i] = called[i];
			}
			this.meme = com.pvpduels.effects.MemeSounds.forLoser(win == 0 || win == 1 ? ids[1 - win] : null); // a tie has no loser
			stage[0] = stage0;
			stage[1] = stage1;
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
				case CLASH -> HANDS + CLASH_EXPAND + PUSH;
				case DOMAIN, FILM -> film.camTicks();
			};
			camTicks = cam;
			int length2 = switch (kind) {
				case NORMAL -> NORMAL_TICKS + TAIL;
				case CLASH -> HANDS + CLASH_EXPAND + PUSH + EXHALE + TIE_TITLE;
				case DOMAIN, FILM -> film.camTicks() + film.tail();
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
				if (duel == null && i == 1) continue; // the dummy of a try-out is the real NPC
				ServerPlayer p = duelist(i);
				if (p == null) {
					discardEntities();
					return false;
				}
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

		/**
	 * Summons an entity with the command (the same way the NPCs are made) and finds it again by its tag. Inside another command the
	 * game only queues the summon until that command ends, so a film is never started from a command directly (see {@link #previewOwn}).
	 */
		private Entity summon(String type, String tag, Vec3 at, float yawDegrees, String extra) {
			String command = String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f {Tags:[\"%s\"],Rotation:[%.2ff,0f]%s}", type, at.x, at.y, at.z, tag, yawDegrees, extra);
			net.minecraft.core.BlockPos block = net.minecraft.core.BlockPos.containing(at);
			boolean wasLoaded = level.hasChunkAt(block);
			if (!wasLoaded) level.getChunkAt(block); // a position outside the loaded chunks would refuse the entity
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
			AABB box = new AABB(at.x - 3, at.y - 3, at.z - 3, at.x + 3, at.y + 4, at.z + 3);
			List<Entity> found = level.getEntities((Entity) null, box, e -> e.entityTags().contains(tag));
			if (!found.isEmpty()) return found.get(0);
			// it did not appear: say where, for the log and the self-test
			lastSpawnFailure = type + " at " + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", at.x, at.y, at.z) + " chunkWasLoaded=" + wasLoaded;
			PvpDuelsMod.LOGGER.warn("Finale spawn failed: {}", lastSpawnFailure);
			return null;
		}

		private void discardEntities() {
			if (camera != null) camera.discard();
			for (int i = 0; i < 2; i++) if (doubles[i] != null) doubles[i].discard();
		}

		// -------------------------------------------------------------- the camera

		/** Where the camera is {@code a} ticks after the viewers got it. */
		Vec3 cameraPosition(int a) {
			if (film != null) return film.camera(this, a);
			return rise(a, camTicks);
		}

		/** The usual rise: from the floor in the middle of the arena, sideways first, then bending toward the winner's end, 7 blocks up. */
		Vec3 rise(int a, int ticks) {
			double p = Math.min(1.0, a / (double) Math.max(1, ticks));
			double e = ease(p);
			double theta = e * Math.PI / 2;
			double out = half * Math.sin(theta);
			double toward = half * 0.45 * (1 - Math.cos(theta));
			Vec3 flat = center.add(side.scale(out)).add(toWinner.scale(toward));
			return new Vec3(flat.x, center.y + 0.3 + RISE * e, flat.z);
		}

		/** The way the camera looks in the usual finale: at the loser, turned toward the winner as much as needed to keep both in the picture. */
		Vec3 standardLook(Vec3 cam) {
			if (kind == Kind.CLASH) return center.add(0, 1.5, 0).subtract(cam).normalize();
			int l = 1 - win;
			Vec3 dl = stage[l].add(0, 1.0, 0).subtract(cam).normalize();
			Vec3 dw = stage[win].add(0, 1.0, 0).subtract(cam).normalize();
			double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dl.dot(dw)))));
			double k = Math.max(0, Math.min(0.9, (angle - 30) / 60.0));
			Vec3 dir = dl.add(dw.scale(k));
			return dir.lengthSqr() < 1e-6 ? dl : dir.normalize();
		}

		private Vec3 look(Vec3 cam, int a) {
			return film != null ? film.look(this, cam, a) : standardLook(cam);
		}

		private void moveCamera(int a) {
			Vec3 pos = cameraPosition(a);
			Vec3 dir = look(pos, a);
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
				ServerPlayer p = r.riding && !r.free ? server.getPlayerList().getPlayer(r.id) : null;
				if (p == null) continue;
				p.connection.send(packet);
				cameraMoves++;
			}
		}

		/** The position moved a little at random (the ground shakes). */
		static Vec3 shake(Vec3 pos, double amplitude) {
			if (amplitude <= 0.001) return pos;
			java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
			return pos.add((rnd.nextDouble() - 0.5) * amplitude, (rnd.nextDouble() - 0.5) * amplitude * 0.7, (rnd.nextDouble() - 0.5) * amplitude);
		}

		// -------------------------------------------------------------- viewers

		private void startRiding() {
			for (int i = 0; i < 2; i++) {
				ServerPlayer p = duelist(i);
				if (p != null && (duel == null || !duel.isRestored(i))) riders.add(new Rider(p.getUUID(), i));
			}
			if (duel != null) for (ServerPlayer p : Spectators.watchers(duel)) riders.add(new Rider(p.getUUID(), -1));
			moveCameraEntityOnly();
			for (Rider r : riders) {
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p == null) continue;
				r.previous = p.gameMode();
				r.home = p.position();
				r.homeYaw = p.getYRot();
				r.homePitch = p.getXRot();
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

		/**
		 * A viewer who sneaks (or gets out of the camera in any other way) skips the rest of the finale: they get their body back. The keys of
		 * the films do the same (skip) or free the camera (see {@link #goFree}); a free viewer is only kept near the scene.
		 */
		private void skippers() {
			for (Rider r : new ArrayList<>(riders)) {
				if (!r.riding) continue;
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p == null) continue;
				if (r.free) {
					keepNear(p);
					continue;
				}
				if (p.getCamera() != camera) {
					releaseOne(r.id, true);
					continue;
				}
				if (!p.isShiftKeyDown()) r.sneakReady = true;
				else if (r.sneakReady) {
					releaseOne(r.id, true);
					continue;
				}
			}
		}

		/** The skip key: the viewer gets their body back (false when this player is not part of this film). */
		boolean skipFor(ServerPlayer p) {
			for (Rider r : riders) {
				if (!r.riding || !r.id.equals(p.getUUID())) continue;
				releaseOne(r.id, true);
				return true;
			}
			return false;
		}

		/** /freecam: false when this player is not part of this film. */
		boolean toggleFree(ServerPlayer p) {
			for (Rider r : riders) {
				if (!r.riding || !r.id.equals(p.getUUID())) continue;
				if (r.free) backToCamera(r, p);
				else goFree(r, p);
				return true;
			}
			return false;
		}

		/** The viewer leaves the camera of the film and flies from where it was, like a spectator (the film goes on without them). */
		private void goFree(Rider r, ServerPlayer p) {
			r.free = true;
			p.setCamera(null);
			Vec3 at = cameraAt != null ? cameraAt : p.position();
			p.teleportTo(level, at.x, at.y, at.z, Set.of(), cameraYaw, cameraPitch, true);
			p.setDeltaMovement(Vec3.ZERO);
			p.sendSystemMessage(Component.literal("Free camera: fly around. ").append(Component.keybind("key.pvpduels.free_cam")).append(" (or /freecam) goes back to the camera of the film.")
				.withStyle(ChatFormatting.AQUA), true);
		}

		/** The viewer gets the camera of the film again (sneak skips the film from there, as always). */
		private void backToCamera(Rider r, ServerPlayer p) {
			r.free = false;
			r.sneakReady = false;
			p.setCamera(camera);
			p.sendSystemMessage(hintLine(), true);
		}

		/** A free viewer cannot fly off into the dark: too far from the middle (or too high or too low) and they are put back at the edge. */
		private void keepNear(ServerPlayer p) {
			Vec3 at = p.position();
			double dx = at.x - center.x;
			double dz = at.z - center.z;
			double flat = Math.hypot(dx, dz);
			double ny = Math.max(center.y - 4, Math.min(center.y + FREE_RADIUS, at.y));
			if (flat <= FREE_RADIUS && ny == at.y) return;
			double k = flat > FREE_RADIUS ? FREE_RADIUS / flat : 1.0;
			p.teleportTo(level, center.x + dx * k, ny, center.z + dz * k, Set.of(), p.getYRot(), p.getXRot(), true);
			p.setDeltaMovement(Vec3.ZERO);
		}

		/** A line above the hotbar that says what the keys do (twice: it fades). */
		private void hint() {
			for (Rider r : riders) {
				if (!r.riding || r.free) continue;
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p != null) p.sendSystemMessage(hintLine(), true);
			}
		}

		/** The keys of the films, with the name of the key each player really has for them. */
		private static Component hintLine() {
			return Component.empty().append(Component.keybind("key.pvpduels.skip_scene")).append(": skip the film   |   ")
				.append(Component.keybind("key.pvpduels.free_cam")).append(": free camera").withStyle(ChatFormatting.GRAY);
		}

		/** A viewer gets their own eyes back. {@code toStage}: the duelists also go back to where they started. */
		void releaseOne(UUID id, boolean toStage) {
			for (Rider r : riders) {
				if (!r.id.equals(id) || !r.riding) continue;
				r.riding = false;
				r.free = false;
				ServerPlayer p = server.getPlayerList().getPlayer(r.id);
				if (p == null) continue;
				if (p.getCamera() != p) p.setCamera(null);
				p.setGameMode(r.previous);
				if (duel == null && r.home != null) {
					p.teleportTo(level, r.home.x, r.home.y, r.home.z, Set.of(), r.homeYaw, r.homePitch, true);
					p.setDeltaMovement(0, 0, 0);
				} else if (toStage && duel != null) {
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
			if (props != null) props.clear();
			discardEntities();
		}

		List<ServerPlayer> audience() {
			List<ServerPlayer> out = new ArrayList<>();
			for (ServerPlayer p : level.players()) {
				boolean owner = p.getUUID().equals(ids[0]) || p.getUUID().equals(ids[1]);
				if (!owner && Effects.hideOthers(p.getUUID())) continue;
				out.add(p);
			}
			return out;
		}

		/** The solid parts of the film, made the first time they are needed. */
		Props props() {
			if (props == null) props = Victory.propsFor(level, winnerPlayer());
			return props;
		}

		void title(Component title, Component subtitle, int in, int stay, int out) {
			for (ServerPlayer p : level.players()) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
				p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
				p.connection.send(new ClientboundSetTitleTextPacket(title));
			}
		}

		void sound(Vec3 at, net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
			level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
		}

		void sound(Vec3 at, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> event, float volume, float pitch) {
			level.playSound(null, at.x, at.y, at.z, event, SoundSource.MASTER, volume, pitch);
		}

		void pose(int i, Pose pose) {
			if (doubles[i] != null && doubles[i].isAlive()) doubles[i].setPose(pose);
		}

		/** The viewers are blinded for a moment by a burst of light where the camera is. */
		void flash() {
			for (Rider r : riders) {
				ServerPlayer p = r.riding ? server.getPlayerList().getPlayer(r.id) : null;
				if (p == null) continue;
				Quality.send(level, p, ParticleTypes.END_ROD, cameraAt.x, cameraAt.y, cameraAt.z, 260, 2.5, 2.5, 2.5, 0.5);
				Quality.send(level, p, ParticleTypes.FIREWORK, cameraAt.x, cameraAt.y, cameraAt.z, 120, 3.0, 3.0, 3.0, 0.4);
			}
		}

		/** From the winner's end to the loser's end, flat. */
		Vec3 forward() {
			return toWinner.scale(-1);
		}

		// -------------------------------------------------------------- every tick

		/** Returns false when the finale is over. */
		boolean tick() {
			if (ended) return false;
			if (duel != null && duel.closed) return false;
			if (duel == null && duelist(0) == null) return false;
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
			if (a == 2 || a == 70) hint();
			skippers();
			moveCamera(a);
			switch (kind) {
				case NORMAL -> normal(a);
				case CLASH -> clash(a);
				case DOMAIN, FILM -> {
					film.play(this, a);
					sounds(a);
				}
			}
			return age < total;
		}

		ServerPlayer winnerPlayer() {
			return win < 0 ? null : server.getPlayerList().getPlayer(ids[win]);
		}

		/** True while this player has the camera of the film (or is about to get it). */
		boolean watching(UUID who) {
			if (age < PRE) return true;
			for (Rider r : riders) if (r.riding && r.id.equals(who)) return true;
			return false;
		}

		/** The player behind the winner (0) or the loser (1), or null when they are gone (or it is the dummy of a try-out). */
		ServerPlayer duelist(int i) {
			if (duel != null) return duel.player(i);
			return ids[i] == null ? null : server.getPlayerList().getPlayer(ids[i]);
		}

		/** The victory tune of the film, and right after it the sad one of the loser. */
		private void sounds(int a) {
			Jingles.Tune happy = Jingles.of(film.id());
			int from = film.victoryAt(this);
			int t = a - from;
			if (t >= 0 && t < happy.length()) Jingles.tick(level, audience(), cameraAt, happy, t);
			// the sad tune ends with the film (after the happy one when there is room for both)
			int sadFrom = Math.max(0, camTicks + film.tail() - sad.length() - 1);
			int s = a - sadFrom;
			if (s >= 0 && s < sad.length()) {
				List<ServerPlayer> all = audience();
				Jingles.tick(level, com.pvpduels.effects.MemeSounds.quiet(all), cameraAt, sad, s);
				if (s == 0) com.pvpduels.effects.MemeSounds.play(com.pvpduels.effects.MemeSounds.loud(all), meme);
			}
		}

		private void normal(int a) {
			if (a == 1) {
				ServerPlayer winner = winnerPlayer();
				if (winner != null) Victory.playAt(winner, stage[win], yaw[win], stage[1 - win]);
			}
			// the sad tune of the loser comes after the cheerful one of the fireworks
			int s = a - (6 + Jingles.common().length() + DEFEAT_GAP);
			if (s >= 0 && s < sad.length()) {
				List<ServerPlayer> all = audience();
				Jingles.tick(level, com.pvpduels.effects.MemeSounds.quiet(all), cameraAt, sad, s);
				if (s == 0) com.pvpduels.effects.MemeSounds.play(com.pvpduels.effects.MemeSounds.loud(all), meme);
			}
		}

		/** Where the hands of a duelist join. */
		Vec3 hands(int i) {
			double r = Math.toRadians(yaw[i]);
			return stage[i].add(-Math.sin(r) * 0.4, 0.95, Math.cos(r) * 0.4);
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
					Component.literal(names[0] + " vs " + names[1]).withStyle(ChatFormatting.GRAY), 5, 50, 10);
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
					Component.literal(names[0] + " and " + names[1] + " are too tired to go on").withStyle(ChatFormatting.DARK_GRAY), 5, 45, 15);
			}
		}
	}
}
