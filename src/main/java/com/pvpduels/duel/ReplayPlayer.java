package com.pvpduels.duel;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plays a recorded duel for one viewer. It takes a free arena slot, builds the arena of the duel there and puts two "doubles" (mannequins
 * with the skin, armor and items of the duelists) in it that repeat everything that was recorded: walking, looking, swinging, getting hit.
 * The viewer watches through a camera that follows the fight, with the controls pause, speed, view and restart. Everything is undone when
 * the replay ends, with /leave, when the viewer logs off or when the server stops.
 */
public final class ReplayPlayer {
	private static final String TAG = "pvpduels_replay";
	private static final Map<UUID, ReplayPlayer> SESSIONS = new HashMap<>();
	/** The doubles and the camera exist this many ticks before the viewer gets the camera. */
	private static final int PRE = 3;
	/** After the last picture the replay stays this long, then it ends. */
	private static final int END_HOLD = 60;
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
	static final String[] VIEWS = {"Side", "Behind the first player", "Behind the second player", "From above", "Free flight"};
	/** The index of the view where the viewer flies around on their own. */
	static final int FREE = 4;

	/** For the self-test. */
	private static int started;

	private final MinecraftServer server;
	private final UUID viewerId;
	final ReplayData data;
	private final int slot;
	private final Space space;
	private final ServerLevel level;
	private final String code = UUID.randomUUID().toString().substring(0, 8);
	private Entity camera;
	final Entity[] doubles = new Entity[2];
	private final RegistryOps<JsonElement> ops;
	private final ItemStack[] parsed;
	private final boolean[] parsedDone;

	/** Where in the recording we are (in ticks; a fraction is between two pictures). */
	double pos;
	double speed = 1.0;
	boolean paused;
	int view;
	private int age;
	private int endTimer;
	private boolean riding;
	private boolean ended;
	private boolean spawned;
	private int waiting;
	private int processed = -1;
	private int eventCursor;
	/** How many events were played (for the self-test: a jump must not play what it skips). */
	int played;
	private final boolean[] swingBefore = new boolean[2];
	private final boolean[] hurtBefore = new boolean[2];
	private final int[][] shownEquip = new int[2][ReplayData.SLOTS];
	private Vec3 camPos;
	private Vec3 camLook;
	private Vec3 perp = new Vec3(0, 0, 1);
	private float lastYaw = Float.NaN;
	private float lastPitch = 30f;
	private Vec3 spot0;
	private Vec3 spot1;

	private ReplayPlayer(MinecraftServer server, ServerPlayer viewer, ReplayData data, int slot, Space space, ServerLevel level) {
		this.server = server;
		this.viewerId = viewer.getUUID();
		this.data = data;
		this.slot = slot;
		this.space = space;
		this.level = level;
		this.ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		this.parsed = new ItemStack[data.palette.size()];
		this.parsedDone = new boolean[data.palette.size()];
		for (int[] row : shownEquip) java.util.Arrays.fill(row, -1);
	}

	// ------------------------------------------------------------------ queries

	public static boolean isIn(UUID id) {
		return SESSIONS.containsKey(id);
	}

	static ReplayPlayer of(UUID id) {
		return SESSIONS.get(id);
	}

	public static int sessions() {
		return SESSIONS.size();
	}

	public static int startedCount() {
		return started;
	}

	public static void resetForTest() {
		started = 0;
	}

	// ------------------------------------------------------------------ starting

	/** Returns an error text, or null when the replay started. */
	static String start(ServerPlayer viewer, ReplayData data) {
		MinecraftServer server = viewer.level().getServer();
		if (DuelManager.isBusy(viewer.getUUID())) return "You are busy: use /leave to stop what you are doing first.";
		if (data.config.arena() == ArenaKind.CUSTOM && !com.pvpduels.arena.ArenaStore.exists(data.config.custom())) {
			return "The arena '" + data.config.customName() + "' of that duel no longer exists, so the replay can't be played.";
		}
		int slot = DuelManager.reserveSlot();
		if (slot < 0) return "All " + DuelManager.MAX_DUELS + " arenas are busy right now. Try again in a minute.";
		Space space = Space.of(data.config, slot);
		ServerLevel level = space.level(server);
		if (level == null) {
			DuelManager.releaseSlot(slot);
			return "The duel world is not loaded.";
		}
		// A backup still on disk means the player was never given their stuff back: do that first.
		if (PlayerBackup.exists(viewer.getUUID())) PlayerBackup.restore(server, viewer);
		try {
			PlayerBackup.save(server, viewer);
		} catch (IOException e) {
			DuelManager.releaseSlot(slot);
			return "Could not save your inventory, so you were not sent to the replay: " + e.getMessage();
		}
		ReplayPlayer session = new ReplayPlayer(server, viewer, data, slot, space, level);
		try {
			if (data.frames == 0) throw new IllegalStateException("the replay has no pictures");
			space.begin(level);
			session.prepare();
		} catch (RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Could not start the replay {}: {}", data.id, e.toString());
			space.end(level);
			DuelManager.releaseSlot(slot);
			PlayerBackup.restore(server, viewer);
			return "Could not build the replay: " + e.getMessage();
		}
		SESSIONS.put(viewer.getUUID(), session);
		started++;
		viewer.closeContainer();
		viewer.removeAllEffects();
		viewer.setGameMode(GameType.SPECTATOR);
		Vec3 at = session.camPos;
		viewer.teleportTo(level, at.x, at.y, at.z, Set.of(), 0f, 30f, true);
		viewer.setDeltaMovement(0, 0, 0);
		session.welcome(viewer);
		PvpDuelsMod.LOGGER.info("{} is watching the replay {} ({} vs {})", viewer.getName().getString(), data.id, data.names[0], data.names[1]);
		return null;
	}

	private Vec3 absolute(int p, int frame) {
		return new Vec3(data.refX + data.x[p][frame], data.refY + data.y[p][frame], data.refZ + data.z[p][frame]);
	}

	/** Works out where everything goes. The doubles and the camera are created a few ticks later, when the arena has loaded. */
	private void prepare() {
		spot0 = absoluteAt(0, 0);
		spot1 = absoluteAt(1, 0);
		camPos = directorTarget(0.0, 0, true);
		camLook = spot0.add(spot1).scale(0.5).add(0, 1.2, 0);
	}

	/** Creates the camera and the two doubles. Returns false (and leaves nothing behind) when the arena is not ready yet. */
	private boolean trySpawn() {
		// the viewer waits next to the first player, so the chunks around the arena get loaded
		camera = summon("minecraft:text_display", TAG + "_cam_" + code, camPos, 0f, "");
		if (camera == null) return false;
		if (camera instanceof net.minecraft.world.entity.Display display) display.setPosRotInterpolationDuration(2);
		for (int i = 0; i < 2; i++) {
			Vec3 at = i == 0 ? spot0 : spot1;
			doubles[i] = summon("minecraft:mannequin", TAG + "_dbl_" + code + "_" + i, at, ReplayData.angle(data.yaw[i][0]), doubleNbt(i));
			if (doubles[i] == null) {
				discardEntities();
				camera = null;
				doubles[0] = null;
				doubles[1] = null;
				return false;
			}
		}
		return true;
	}

	/** Where the double of player p is at this (possibly fractional) tick, in the arena of the replay. */
	private Vec3 absoluteAt(int p, double tick) {
		int last = data.frames - 1;
		double t = Math.max(0, Math.min(last, tick));
		int a = (int) Math.floor(t);
		int b = Math.min(last, a + 1);
		double f = t - a;
		Vec3 va = absolute(p, a);
		Vec3 vb = absolute(p, b);
		// a jump (a new round put them back on their spots) is not smoothed
		if (va.distanceToSqr(vb) > 36) return f < 0.5 ? va : vb;
		return va.add(vb.subtract(va).scale(f));
	}

	private float yawAt(int p, double tick) {
		int last = data.frames - 1;
		double t = Math.max(0, Math.min(last, tick));
		int a = (int) Math.floor(t);
		int b = Math.min(last, a + 1);
		float ya = ReplayData.angle(data.yaw[p][a]);
		float yb = ReplayData.angle(data.yaw[p][b]);
		float d = ((yb - ya + 540f) % 360f) - 180f;
		return ya + d * (float) (t - a);
	}

	private float pitchAt(int p, double tick) {
		int a = (int) Math.max(0, Math.min(data.frames - 1, Math.floor(tick)));
		float pitch = ReplayData.angle(data.pitch[p][a]);
		return pitch > 180f ? pitch - 360f : pitch;
	}

	private String doubleNbt(int i) {
		String name = data.names[i].replace("\\", "").replace("\"", "");
		UUID u = data.uuids[i];
		long m = u.getMostSignificantBits();
		long l = u.getLeastSignificantBits();
		return ",Invulnerable:1b,Silent:1b,NoGravity:1b,immovable:1b,hide_description:1b,description:{text:\"\"}"
			+ ",CustomName:{text:\"" + name + "\"},CustomNameVisible:1b"
			+ ",profile:{name:\"" + name + "\",id:[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]}";
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
		for (Entity e : doubles) if (e != null) e.discard();
	}

	private void welcome(ServerPlayer viewer) {
		viewer.sendSystemMessage(Msg.title("Replay: " + data.names[0] + " vs " + data.names[1] + "  (" + data.config.kit() + ", " + data.config.arenaName() + ")"));
		viewer.sendSystemMessage(Msg.info("  Score " + data.points[0] + " - " + data.points[1] + ", " + data.seconds() + " seconds. Blocks, pearls and crystals are not repeated, only the players."));
		viewer.sendSystemMessage(controls());
		viewer.sendSystemMessage(controls2());
	}

	/** The first line of buttons: what plays and how fast. */
	static Component controls() {
		return Component.literal("  ")
			.append(Msg.button("[PAUSE]", ChatFormatting.YELLOW, "/replay pause", "Pause or resume"))
			.append(Component.literal(" "))
			.append(Msg.button("[<5s]", ChatFormatting.GRAY, "/replay seek -5", "Jump 5 seconds back"))
			.append(Component.literal(" "))
			.append(Msg.button("[5s>]", ChatFormatting.GRAY, "/replay seek 5", "Jump 5 seconds forward"))
			.append(Component.literal(" "))
			.append(Msg.button("[<F]", ChatFormatting.GRAY, "/replay step back", "Pause and go back one picture"))
			.append(Component.literal(" "))
			.append(Msg.button("[F>]", ChatFormatting.GRAY, "/replay step", "Pause and go forward one picture"))
			.append(Component.literal(" "))
			.append(Msg.button("[0.25x]", ChatFormatting.GRAY, "/replay speed 0.25", "Very slow motion"))
			.append(Component.literal(" "))
			.append(Msg.button("[0.5x]", ChatFormatting.GRAY, "/replay speed 0.5", "Slow motion"))
			.append(Component.literal(" "))
			.append(Msg.button("[1x]", ChatFormatting.GREEN, "/replay speed 1", "Normal speed"))
			.append(Component.literal(" "))
			.append(Msg.button("[2x]", ChatFormatting.GRAY, "/replay speed 2", "Twice as fast"));
	}

	/** The second line: where you look from, and the ways out. */
	static Component controls2() {
		return Component.literal("  ")
			.append(Msg.button("[VIEW]", ChatFormatting.AQUA, "/replay view", "Change the camera angle"))
			.append(Component.literal(" "))
			.append(Msg.button("[FREE]", ChatFormatting.AQUA, "/replay free", "Fly around on your own (click again to go back to the camera)"))
			.append(Component.literal(" "))
			.append(Msg.button("[CAMERA]", ChatFormatting.AQUA, "/replay camera", "Get the camera back if you left it by sneaking"))
			.append(Component.literal(" "))
			.append(Msg.button("[RESTART]", ChatFormatting.GOLD, "/replay restart", "Start again"))
			.append(Component.literal(" "))
			.append(Msg.button("[STOP]", ChatFormatting.RED, "/leave", "Stop watching and get your things back"));
	}

	// ------------------------------------------------------------------ the controls

	/** Applies a control and returns what to tell the viewer (null = nothing). */
	String control(String action, double value) {
		switch (action) {
			case "pause" -> {
				paused = !paused;
				return paused ? "Paused." : "Playing.";
			}
			case "speed" -> {
				speed = Math.max(0.1, Math.min(8.0, value));
				paused = false;
				return "Speed " + trim(speed) + "x.";
			}
			case "view" -> {
				return setView((view + 1) % VIEWS.length);
			}
			case "free" -> {
				return setView(view == FREE ? 0 : FREE);
			}
			case "seek" -> {
				double seconds = Math.max(-3600, Math.min(3600, value));
				double before = pos;
				jump(pos + seconds * 20.0);
				if (pos == before) return seconds < 0 ? "Already at the start." : "Already at the end.";
				return (seconds < 0 ? "Back " : "Forward ") + trim(Math.abs(seconds)) + " s.";
			}
			case "step" -> {
				paused = true;
				int last = data.frames - 1;
				int now = (int) Math.floor(pos);
				if (value < 0) {
					if (now <= 0) return "This is the first picture.";
					jump(now - 1);
				} else {
					if (now >= last) return "This is the last picture.";
					pos = now + 1;
				}
				return "Picture " + (int) Math.floor(pos) + " of " + last + " (paused).";
			}
			case "restart" -> {
				restart();
				return "Starting again.";
			}
			case "camera" -> {
				ServerPlayer p = server.getPlayerList().getPlayer(viewerId);
				if (view == FREE) return setView(0);
				if (p != null && camera != null && riding) p.setCamera(camera);
				return "You are looking through the camera again.";
			}
			default -> {
				return null;
			}
		}
	}

	private static String trim(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
	}

	/** Changes the angle. The free view lets the viewer go: they fly where they want, and the camera waits. */
	private String setView(int next) {
		ServerPlayer p = viewer();
		int before = view;
		view = next;
		if (p != null && camera != null && riding) {
			if (next == FREE && before != FREE) {
				p.setCamera(p);
				p.teleportTo(level, camPos.x, camPos.y, camPos.z, Set.of(), Float.isNaN(lastYaw) ? 0f : lastYaw, lastPitch, true);
				return "Free flight: fly where you want. [VIEW] or [FREE] put the camera back.";
			}
			if (before == FREE && next != FREE) {
				camPos = directorTarget(pos, view, true);
				camLook = lookTarget(view);
				p.setCamera(camera);
			}
		}
		return "Camera: " + VIEWS[view] + ".";
	}

	/** Goes to another moment without repeating what happened in between (no sounds, no hits). */
	private void jump(double target) {
		int last = data.frames - 1;
		pos = Math.max(0, Math.min(last, target));
		int f = (int) Math.floor(pos);
		processed = f;
		eventCursor = 0;
		while (eventCursor < data.events.size() && data.events.get(eventCursor).tick() <= f) eventCursor++;
		for (int p = 0; p < 2; p++) {
			int flags = data.flags[p][f];
			swingBefore[p] = (flags & ReplayData.FLAG_SWING) != 0;
			hurtBefore[p] = (flags & ReplayData.FLAG_HURT) != 0;
		}
		for (int[] row : shownEquip) java.util.Arrays.fill(row, -1);
		endTimer = 0;
		if (view != FREE) {
			camPos = directorTarget(pos, view, true);
			camLook = lookTarget(view);
		}
	}

	private void restart() {
		pos = 0;
		processed = -1;
		eventCursor = 0;
		endTimer = 0;
		paused = false;
		java.util.Arrays.fill(swingBefore, false);
		java.util.Arrays.fill(hurtBefore, false);
		for (int[] row : shownEquip) java.util.Arrays.fill(row, -1);
	}

	// ------------------------------------------------------------------ every tick

	private static void tickAll(MinecraftServer server) {
		if (SESSIONS.isEmpty()) return;
		for (ReplayPlayer s : new ArrayList<>(SESSIONS.values())) {
			try {
				s.tick();
			} catch (RuntimeException e) {
				PvpDuelsMod.LOGGER.warn("The replay {} failed: {}", s.data.id, e.toString());
				s.end(true);
			}
		}
	}

	private ServerPlayer viewer() {
		return server.getPlayerList().getPlayer(viewerId);
	}

	private void tick() {
		if (ended) return;
		ServerPlayer viewer = viewer();
		if (viewer == null) {
			end(false);
			return;
		}
		if (!spawned) {
			// the arena has to be loaded before anything can be created in it
			waiting++;
			if (waiting >= 3 && trySpawn()) spawned = true;
			else if (waiting > 200) {
				viewer.sendSystemMessage(Msg.err("The replay arena did not load, so it stopped."));
				end(true);
			}
			return;
		}
		if (camera == null || !camera.isAlive() || doubles[0] == null || !doubles[0].isAlive() || doubles[1] == null || !doubles[1].isAlive()) {
			viewer.sendSystemMessage(Msg.err("The replay lost its actors, so it stopped."));
			end(true);
			return;
		}
		age++;
		if (age == PRE) {
			riding = true;
			viewer.setCamera(camera);
		}
		if (age < PRE) return;

		int last = data.frames - 1;
		boolean atEnd = pos >= last;
		if (!paused && !atEnd) pos = Math.min(last, pos + speed);
		if (atEnd) {
			endTimer++;
			if (endTimer == 1) finishTitle(viewer);
			if (endTimer >= END_HOLD + 40) {
				viewer.sendSystemMessage(Msg.ok("The replay is over."));
				end(true);
				return;
			}
		}
		advanceEvents(viewer, (int) Math.floor(pos));
		moveDoubles(viewer);
		if (view != FREE) moveCamera(viewer);
		if (age % 4 == 0) hud(viewer);
	}

	private void finishTitle(ServerPlayer viewer) {
		String text = data.winner < 0 ? "DRAW" : data.names[data.winner] + " WINS";
		viewer.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
		viewer.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(data.names[0] + " " + data.points[0] + " - " + data.points[1] + " " + data.names[1]).withStyle(ChatFormatting.WHITE)));
		viewer.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
	}

	private void hud(ServerPlayer viewer) {
		int frame = (int) Math.max(0, Math.min(data.frames - 1, Math.floor(pos)));
		int hp0 = Math.round((data.health[0][frame] & 0xFF) / 2f);
		int hp1 = Math.round((data.health[1][frame] & 0xFF) / 2f);
		int now = frame / 20;
		int total = data.seconds();
		String state = paused ? "PAUSED" : trim(speed) + "x";
		viewer.sendOverlayMessage(Component.literal("REPLAY  ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(data.names[0] + " " + hp0 + " HP").withStyle(ChatFormatting.AQUA))
			.append(Component.literal("  vs  ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(data.names[1] + " " + hp1 + " HP").withStyle(ChatFormatting.RED))
			.append(Component.literal(String.format(Locale.ROOT, "   %d:%02d / %d:%02d   %s   %s", now / 60, now % 60, total / 60, total % 60, state, VIEWS[view]))
				.withStyle(ChatFormatting.WHITE)));
	}

	// ------------------------------------------------------------------ the events

	private void advanceEvents(ServerPlayer viewer, int upTo) {
		for (int f = processed + 1; f <= upTo; f++) {
			for (int p = 0; p < 2; p++) {
				int flags = data.flags[p][f];
				boolean swing = (flags & ReplayData.FLAG_SWING) != 0;
				boolean hurt = (flags & ReplayData.FLAG_HURT) != 0;
				if (swing && !swingBefore[p] && doubles[p] instanceof LivingEntity living) living.swing(InteractionHand.MAIN_HAND);
				if (hurt && !hurtBefore[p] && doubles[p] != null) viewer.connection.send(new ClientboundHurtAnimationPacket(doubles[p].getId(), 0f));
				swingBefore[p] = swing;
				hurtBefore[p] = hurt;
			}
			while (eventCursor < data.events.size() && data.events.get(eventCursor).tick() <= f) {
				event(viewer, data.events.get(eventCursor), f);
				played++;
				eventCursor++;
			}
		}
		if (upTo > processed) processed = upTo;
	}

	private void event(ServerPlayer viewer, ReplayData.Event e, int frame) {
		switch (e.type()) {
			case ReplayData.EV_HIT -> {
				Entity victim = doubles[e.a() == 1 ? 1 : 0];
				if (victim == null) return;
				Vec3 at = victim.position().add(0, 1.0, 0);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0f, 1.0f);
				level.sendParticles(viewer, ParticleTypes.CRIT, false, true, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.2);
			}
			case ReplayData.EV_ROUND_START -> {
				if (e.a() > 1) viewer.sendSystemMessage(Msg.info("  Round " + e.a() + " begins."));
			}
			case ReplayData.EV_ROUND_END -> {
				int winner = e.a();
				int p0 = e.b() / 1000;
				int p1 = e.b() % 1000;
				viewer.sendSystemMessage(Msg.info("  " + (winner < 0 ? "Round without a winner" : data.names[winner] + " wins the round") + "  (" + p0 + " - " + p1 + ")"));
			}
			default -> { }
		}
	}

	// ------------------------------------------------------------------ the doubles

	private ItemStack stackOf(int index) {
		if (index <= 0 || index >= parsed.length) return ItemStack.EMPTY;
		if (!parsedDone[index]) {
			parsedDone[index] = true;
			try {
				JsonElement json = JsonParser.parseString(data.palette.get(index));
				parsed[index] = ItemStack.OPTIONAL_CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
			} catch (RuntimeException ex) {
				parsed[index] = ItemStack.EMPTY;
			}
		}
		return parsed[index] == null ? ItemStack.EMPTY : parsed[index].copy();
	}

	private static Pose poseOf(byte stored) {
		Pose[] all = Pose.values();
		int o = stored & 0xFF;
		if (o >= all.length) return Pose.STANDING;
		Pose pose = all[o];
		return pose == Pose.CROUCHING || pose == Pose.SWIMMING ? pose : Pose.STANDING;
	}

	private void moveDoubles(ServerPlayer viewer) {
		int frame = (int) Math.max(0, Math.min(data.frames - 1, Math.floor(pos)));
		for (int p = 0; p < 2; p++) {
			Entity e = doubles[p];
			Vec3 at = absoluteAt(p, pos);
			float yaw = yawAt(p, pos);
			float pitch = pitchAt(p, pos);
			e.snapTo(at.x, at.y, at.z, yaw, pitch);
			e.setYHeadRot(yaw);
			e.setYBodyRot(yaw);
			e.setPose(poseOf(data.pose[p][frame]));
			Packet<?> move = new ClientboundTeleportEntityPacket(e.getId(), new PositionMoveRotation(at, Vec3.ZERO, yaw, pitch), Set.of(), true);
			viewer.connection.send(move);
			viewer.connection.send(new ClientboundRotateHeadPacket(e, ReplayData.toByte(yaw)));
			if (e instanceof LivingEntity living) {
				for (int s = 0; s < SLOTS.length; s++) {
					int idx = data.equip[p][s][frame];
					if (idx != shownEquip[p][s]) {
						shownEquip[p][s] = idx;
						living.setItemSlot(SLOTS[s], stackOf(idx));
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ the camera

	/** Where the camera wants to be, for the angle chosen. */
	private Vec3 directorTarget(double tick, int mode, boolean first) {
		Vec3 a = absoluteAt(0, tick);
		Vec3 b = absoluteAt(1, tick);
		Vec3 mid = a.add(b).scale(0.5);
		double sep = a.distanceTo(b);
		Vec3 flat = new Vec3(b.x - a.x, 0, b.z - a.z);
		Vec3 along = flat.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : flat.normalize();
		switch (mode) {
			case 1 -> {
				return a.subtract(along.scale(4.5)).add(0, 2.6, 0);
			}
			case 2 -> {
				return b.add(along.scale(4.5)).add(0, 2.6, 0);
			}
			case 3 -> {
				return mid.add(0.01, 13 + sep * 0.6, 0);
			}
			default -> {
				Vec3 candidate = new Vec3(-along.z, 0, along.x);
				if (first) perp = candidate;
				else {
					if (candidate.dot(perp) < 0) candidate = candidate.scale(-1);
					perp = perp.scale(0.92).add(candidate.scale(0.08));
					if (perp.lengthSqr() > 1e-6) perp = perp.normalize();
				}
				double distance = Math.max(7.0, Math.min(24.0, sep * 0.9 + 5.5));
				return mid.add(perp.scale(distance)).add(0, 3.2 + sep * 0.12, 0);
			}
		}
	}

	private Vec3 lookTarget(int mode) {
		Vec3 a = absoluteAt(0, pos);
		Vec3 b = absoluteAt(1, pos);
		return switch (mode) {
			case 1 -> b.add(0, 1.2, 0);
			case 2 -> a.add(0, 1.2, 0);
			default -> a.add(b).scale(0.5).add(0, 1.2, 0);
		};
	}

	private void moveCamera(ServerPlayer viewer) {
		Vec3 want = directorTarget(pos, view, false);
		camPos = camPos.add(want.subtract(camPos).scale(0.16));
		Vec3 look = lookTarget(view);
		camLook = camLook.add(look.subtract(camLook).scale(0.22));
		Vec3 d = camLook.subtract(camPos);
		if (d.lengthSqr() < 1e-6) d = new Vec3(0, -1, 0);
		d = d.normalize();
		float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
		if (!Float.isNaN(lastYaw)) {
			while (yaw - lastYaw > 180) yaw -= 360;
			while (yaw - lastYaw < -180) yaw += 360;
		}
		lastYaw = yaw;
		float pitch = (float) Math.max(-89, Math.min(89, -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))));
		lastPitch = pitch;
		camera.snapTo(camPos.x, camPos.y, camPos.z, yaw, pitch);
		if (riding) viewer.connection.send(new ClientboundTeleportEntityPacket(camera.getId(), new PositionMoveRotation(camPos, Vec3.ZERO, yaw, pitch), Set.of(), false));
	}

	// ------------------------------------------------------------------ ending

	/** The viewer leaves (/leave): they get their things back. */
	static void stop(ServerPlayer p) {
		ReplayPlayer s = SESSIONS.get(p.getUUID());
		if (s != null) s.end(true);
	}

	/** {@code restore}: give the viewer their things back (false when they are not online). */
	private void end(boolean restore) {
		if (ended) return;
		ended = true;
		SESSIONS.remove(viewerId);
		ServerPlayer viewer = viewer();
		if (viewer != null) {
			if (viewer.getCamera() != viewer) viewer.setCamera(null);
			if (restore) {
				PlayerBackup.restore(server, viewer);
				viewer.sendSystemMessage(Msg.ok("You stopped watching the replay. Your inventory was restored."));
			}
		}
		discardEntities();
		space.end(level);
		DuelManager.releaseSlot(slot);
	}

	// ------------------------------------------------------------------ setup

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ReplayPlayer::tickAll);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// Their inventory backup stays on disk: it is given back the next time they join.
			ReplayPlayer s = SESSIONS.get(handler.player.getUUID());
			if (s != null) s.end(false);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ReplayPlayer s : new ArrayList<>(SESSIONS.values())) s.end(true);
			SESSIONS.clear();
		});
	}
}
