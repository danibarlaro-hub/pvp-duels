package com.pvpduels.arena;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.Arena;
import com.pvpduels.duel.ArenaMenus;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.duel.PlayerBackup;
import com.pvpduels.duel.Spectators;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The arena studio: /createarena sends a Builder to a creative plot (a 64x64x64 box floating in the duel world) with a
 * template to start from. They build, mark the two spawn points with /setspawn 1 and /setspawn 2 and keep the result with
 * /savearena &lt;name&gt;. Leaving with /leave keeps a draft of the plot, so nothing is lost.
 * Their own inventory is saved to disk first and comes back when they leave, like in the kit workshop.
 */
public final class ArenaStudio {
	private static final int BASE_X = 16384;
	private static final int SPACING = 256;
	/** The name the draft is saved under; nobody can save an arena with it. */
	public static final String DRAFT = "draft";
	/** Most arenas one player can keep (not counting the draft). */
	public static final int MAX_ARENAS = 30;
	/** How far outside the box a builder can walk before being brought back. */
	private static final int MARGIN = 6;

	/** STUDIO: a Builder making an arena. CONTEST: anybody building their entry of the building contest. VIEW: looking at somebody's entry (nothing can be touched). */
	public enum Mode { STUDIO, CONTEST, VIEW }

	private static final class Session {
		final int slot;
		final Mode mode;
		/** The arena being edited (null when starting from a template or a draft). */
		final String editing;
		String template;
		double[] spawn0;
		double[] spawn1;
		/** The owner of the entry being looked at (VIEW only). */
		UUID viewing;

		Session(int slot, Mode mode, String editing, String template) {
			this.slot = slot;
			this.mode = mode;
			this.editing = editing;
			this.template = template;
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private ArenaStudio() {}

	public static boolean isIn(UUID id) {
		return SESSIONS.containsKey(id);
	}

	/** The mode the player is in, or null. */
	public static Mode modeOf(UUID id) {
		Session s = SESSIONS.get(id);
		return s == null ? null : s.mode;
	}

	/** Whose entry the player is looking at, or null. */
	public static UUID viewing(UUID id) {
		Session s = SESSIONS.get(id);
		return s == null || s.mode != Mode.VIEW ? null : s.viewing;
	}

	static int centerX(int slot) {
		return BASE_X + slot * SPACING;
	}

	/** Center x of the plot a player is building in, or -1. */
	public static int plotOf(UUID id) {
		Session s = SESSIONS.get(id);
		return s == null ? -1 : centerX(s.slot);
	}

	// ------------------------------------------------------------------ entering and leaving

	/**
	 * Takes the player to a plot. kind: the template to start from; editKey: the arena (a key of {@link ArenaStore}) or
	 * the draft to load instead. Returns an error text or null.
	 */
	public static String enter(ServerPlayer p, ArenaTemplates.Kind kind, String editName) {
		MinecraftServer server = p.level().getServer();
		if (!BuilderRank.can(p)) return "Only Builders can make arenas. The Builder rank is won by placing 1st, 2nd or 3rd in the weekly building contest. /builder tells you more.";
		String problem = check(p);
		if (problem != null) return problem;
		ServerLevel level = Arena.level(server);

		ArenaData loaded = null;
		if (editName != null) {
			try {
				Optional<ArenaData> read = ArenaStore.load(ArenaStore.key(p.getUUID(), editName));
				if (read.isEmpty()) return "You have no arena named '" + editName + "'. See /arena list.";
				loaded = read.get();
			} catch (IOException e) {
				return "Could not read the arena: " + e.getMessage();
			}
		}

		Session session;
		try {
			session = begin(p, Mode.STUDIO, editName != null && !DRAFT.equals(editName) ? editName : null, loaded != null ? loaded.template : kind.id);
		} catch (IOException e) {
			return "Could not save your inventory, so the studio was not opened: " + e.getMessage();
		}

		int cx = centerX(session.slot);
		if (loaded != null) {
			ArenaPaster.apply(level, cx, loaded);
			session.spawn0 = loaded.spawn0;
			session.spawn1 = loaded.spawn1;
		} else {
			ArenaData fresh = new ArenaData("new", p.getUUID(), p.getName().getString(), kind.id, ArenaTemplates.build(kind));
			ArenaPaster.apply(level, cx, fresh);
			double[][] spawns = ArenaTemplates.defaultSpawns(kind);
			session.spawn0 = spawns[0];
			session.spawn1 = spawns[1];
		}
		teleportIn(p, level, cx + 0.5, Arena.FLOOR_Y + 1);

		p.sendSystemMessage(Msg.title("Arena studio"));
		p.sendSystemMessage(Msg.info("  " + (editName == null ? "Template: " + kind.title + "." : DRAFT.equals(editName) ? "Your draft." : "Editing '" + editName + "'.")
			+ " You can build inside a 64x64x64 box around you."));
		p.sendSystemMessage(Msg.info("  Green sparkles mark the spawn of player 1, flames the spawn of player 2. Stand where you want them and use /setspawn 1 or /setspawn 2."));
		p.sendSystemMessage(Msg.info("  /savearena <name> keeps the arena. /leave goes back and keeps a draft. ")
			.copy().append(Msg.button("[LEAVE]", ChatFormatting.RED, "/leave", "Leave the studio and get your inventory back")));
		return null;
	}

	/** The checks every kind of session needs. Returns an error text or null. */
	private static String check(ServerPlayer p) {
		if (isIn(p.getUUID())) return "You are already building. Use /leave to go back.";
		if (DuelManager.isBusy(p.getUUID()) || DuelManager.isInDuel(p.getUUID())) return "You are busy: use /leave first.";
		if (Spectators.isIn(p.getUUID())) return "You are watching a duel: use /leave first.";
		if (Arena.level(p.level().getServer()) == null) return "The duel world is not loaded. Make sure the mod is up to date, then restart the game.";
		return null;
	}

	/** Saves the player's things and gives them a free plot. */
	private static Session begin(ServerPlayer p, Mode mode, String editing, String template) throws IOException {
		MinecraftServer server = p.level().getServer();
		if (PlayerBackup.exists(p.getUUID())) PlayerBackup.restore(server, p);
		PlayerBackup.save(server, p);
		int slot = 0;
		while (inUse(slot)) slot++;
		Session session = new Session(slot, mode, editing, template);
		SESSIONS.put(p.getUUID(), session);
		return session;
	}

	/** Empty hands, creative mode, standing at x, y in the middle (z = 0) of the plot. */
	private static void teleportIn(ServerPlayer p, ServerLevel level, double x, double y) {
		p.closeContainer();
		p.removeAllEffects();
		p.getInventory().clearContent();
		p.setGameMode(GameType.CREATIVE);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.teleportTo(level, x, y, 0.5, Set.of(), 0f, 0f, true);
		p.setDeltaMovement(0, 0, 0);
		p.resetFallDistance();
	}

	// ------------------------------------------------------------------ the building contest

	/** Takes anybody (no Builder rank needed) to a plot to build their entry. Returns an error text or null. */
	public static String enterContest(ServerPlayer p) {
		if (!Contest.building()) return "There is no building contest to build for right now. /contest tells you more.";
		String problem = check(p);
		if (problem != null) return problem;
		ServerLevel level = Arena.level(p.level().getServer());
		ArenaData existing = Contest.loadEntry(p.getUUID());
		Session session;
		try {
			session = begin(p, Mode.CONTEST, null, "flat");
		} catch (IOException e) {
			return "Could not save your inventory, so the plot was not opened: " + e.getMessage();
		}
		int cx = centerX(session.slot);
		ArenaPaster.apply(level, cx, existing != null ? existing : new ArenaData("entry", p.getUUID(), p.getName().getString(), "flat", ArenaTemplates.build(ArenaTemplates.Kind.FLAT)));
		teleportIn(p, level, cx + 0.5, Arena.FLOOR_Y + 1);
		p.sendSystemMessage(Msg.title("Building contest: " + Contest.category()));
		p.sendSystemMessage(Msg.info("  " + (existing != null ? "Your entry is here: keep building." : "Build \"" + Contest.category() + "\" in the box around you.")
			+ " " + Contest.timeLeft() + " to finish."));
		p.sendSystemMessage(Msg.info("  Your build is kept as your entry when you leave (change at least " + Contest.MIN_BLOCKS + " blocks). /contest save keeps it without leaving. ")
			.copy().append(Msg.button("[LEAVE]", ChatFormatting.RED, "/leave", "Leave the plot, keep your entry and get your inventory back")));
		return null;
	}

	/** Keeps the entry of somebody who is building it, without leaving. Returns an error text or null. */
	public static String saveContest(ServerPlayer p) {
		Session session = SESSIONS.get(p.getUUID());
		if (session == null || session.mode != Mode.CONTEST) return "You are not building your entry. /contest enter takes you to your plot.";
		ServerLevel level = Arena.level(p.level().getServer());
		if (level == null) return "The duel world is not loaded.";
		ArenaData data = snapshot(level, p, session, "entry");
		int changed = Contest.changed(data);
		if (changed < Contest.MIN_BLOCKS) return "Your build is too small: change at least " + Contest.MIN_BLOCKS + " blocks of the plot (you changed " + changed + ").";
		try {
			Contest.saveEntry(p.getUUID(), p.getName().getString(), data);
		} catch (IOException e) {
			return "Could not save your entry: " + e.getMessage();
		}
		p.sendSystemMessage(Msg.ok("Your entry was saved (" + changed + " blocks built)."));
		return null;
	}

	/** Takes the player to look at an entry of the contest (the vote). Returns an error text or null. */
	public static String visit(ServerPlayer p, UUID owner) {
		if (!Contest.voting()) return "Nobody can look at the builds now: the voting is not open.";
		Session session = SESSIONS.get(p.getUUID());
		if (session != null && session.mode != Mode.VIEW) return "You are building: use /leave first.";
		int number = Contest.numberOf(owner);
		ArenaData data = Contest.loadEntry(owner);
		if (number == 0 || data == null) return "That build is not in the contest.";
		ServerLevel level = Arena.level(p.level().getServer());
		boolean first = session == null;
		if (first) {
			String problem = check(p);
			if (problem != null) return problem;
			try {
				session = begin(p, Mode.VIEW, null, "flat");
			} catch (IOException e) {
				return "Could not save your inventory, so the gallery was not opened: " + e.getMessage();
			}
		}
		session.viewing = owner;
		int cx = centerX(session.slot);
		ArenaPaster.apply(level, cx, data);
		double y = topOf(data) + 1.0;
		if (first) {
			teleportIn(p, level, cx + 0.5, y);
		} else {
			p.teleportTo(level, cx + 0.5, y, 0.5, Set.of(), 0f, 0f, true);
			p.setDeltaMovement(0, 0, 0);
			p.resetFallDistance();
		}
		Contest.describeView(p, number, owner.equals(p.getUUID()));
		return null;
	}

	/** World y of the highest block in the middle of the box (the floor line when there is none). */
	private static int topOf(ArenaData data) {
		for (int y = ArenaData.SIZE - 1; y >= 0; y--) {
			if (!data.blocks[ArenaData.index(ArenaData.SIZE / 2, y, ArenaData.SIZE / 2)].isAir()) return Math.min(ArenaPaster.MIN_Y + y, ArenaPaster.MIN_Y + ArenaData.SIZE - 3);
		}
		return Arena.FLOOR_Y;
	}

	/** Gets everybody in a session of one of these kinds out (the phase of the contest changed). */
	public static void exitAll(MinecraftServer server, Mode... modes) {
		for (Map.Entry<UUID, Session> entry : Map.copyOf(SESSIONS).entrySet()) {
			boolean match = false;
			for (Mode m : modes) if (m == entry.getValue().mode) match = true;
			if (!match) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
			if (p != null) exit(p);
			else SESSIONS.remove(entry.getKey());
		}
	}

	private static boolean inUse(int slot) {
		for (Session s : SESSIONS.values()) if (s.slot == slot) return true;
		return false;
	}

	/** Leaves the studio: keeps a draft (or the contest entry), cleans the plot and gives the player their things back. */
	public static void exit(ServerPlayer p) {
		Session session = SESSIONS.get(p.getUUID());
		if (session == null) return;
		MinecraftServer server = p.level().getServer();
		ServerLevel level = Arena.level(server);
		String note = "";
		if (level != null && session.mode == Mode.STUDIO) {
			try {
				ArenaData draft = snapshot(level, p, session, DRAFT);
				ArenaStore.save(draft, ArenaStore.key(p.getUUID(), DRAFT));
				note = " A draft of your build was kept: /createarena > Continue my draft.";
			} catch (IOException | RuntimeException e) {
				PvpDuelsMod.LOGGER.warn("Could not keep the draft of {}: {}", p.getName().getString(), e.toString());
				note = " The draft could not be saved.";
			}
		} else if (level != null && session.mode == Mode.CONTEST) {
			try {
				ArenaData entry = snapshot(level, p, session, "entry");
				if (Contest.changed(entry) >= Contest.MIN_BLOCKS) {
					Contest.saveEntry(p.getUUID(), p.getName().getString(), entry);
					note = " Your build is your entry in the contest.";
				} else {
					Contest.removeEntry(p.getUUID());
					note = " Your build is too small to be an entry (change at least " + Contest.MIN_BLOCKS + " blocks).";
				}
			} catch (IOException | RuntimeException e) {
				PvpDuelsMod.LOGGER.warn("Could not keep the contest entry of {}: {}", p.getName().getString(), e.toString());
				note = " Your entry could not be saved.";
			}
		}
		SESSIONS.remove(p.getUUID());
		if (level != null) ArenaPaster.apply(level, centerX(session.slot), null);
		PlayerBackup.restore(server, p);
		p.sendSystemMessage(Msg.ok((session.mode == Mode.STUDIO ? "You left the studio." : session.mode == Mode.CONTEST ? "You left your plot." : "You left the gallery.")
			+ " Your inventory was restored." + note));
	}

	private static ArenaData snapshot(ServerLevel level, ServerPlayer p, Session session, String name) {
		ArenaData data = new ArenaData(name, p.getUUID(), p.getName().getString(), session.template, ArenaPaster.capture(level, centerX(session.slot)));
		data.spawn0 = session.spawn0;
		data.spawn1 = session.spawn1;
		return data;
	}

	// ------------------------------------------------------------------ saving and spawns

	/** Returns an error text or null. */
	public static String save(ServerPlayer p, String rawName) {
		Session session = SESSIONS.get(p.getUUID());
		if (session == null || session.mode != Mode.STUDIO) return "You are not in the arena studio. /createarena takes you there.";
		String name = ArenaStore.clean(rawName);
		if (!ArenaStore.validName(name)) return "Arena names use lower case letters, numbers, - and _ (up to 32).";
		if (DRAFT.equals(name)) return "'" + DRAFT + "' is the name of your draft. Pick another one.";
		if (session.spawn0 == null || session.spawn1 == null) return "Set both spawn points first: /setspawn 1 and /setspawn 2.";
		boolean isNew = !ArenaStore.exists(p.getUUID(), name);
		if (isNew && ArenaStore.list(p.getUUID()).stream().filter(n -> !DRAFT.equals(n)).count() >= MAX_ARENAS) {
			return "You already have " + MAX_ARENAS + " arenas. Delete one with /arena delete <name>.";
		}
		ServerLevel level = Arena.level(p.level().getServer());
		if (level == null) return "The duel world is not loaded.";
		ArenaData data = snapshot(level, p, session, name);
		if (data.solidBlocks() < 4) return "The arena is empty: build something to stand on first.";
		try {
			ArenaStore.save(data, ArenaStore.key(p.getUUID(), name));
		} catch (IOException e) {
			return "Could not save the arena: " + e.getMessage();
		}
		p.sendSystemMessage(Msg.ok((isNew ? "Saved" : "Updated") + " the arena '" + name + "' (" + data.solidBlocks() + " blocks)."));
		com.pvpduels.achievements.Achievements.max(p.getUUID(), p.getName().getString(), "arenas_saved", ArenaStore.list(p.getUUID()).stream().filter(n -> !DRAFT.equals(n)).count());
		p.sendSystemMessage(Msg.info("  Private by default: only you can pick it. Share it with the others using ")
			.copy().append(Msg.button("[PUBLISH]", ChatFormatting.GREEN, "/arena publish " + name, "Put the arena on the public shelf"))
			.append(net.minecraft.network.chat.Component.literal(" or /arena publish " + name + ".").withStyle(ChatFormatting.GRAY)));
		return null;
	}

	/** Returns an error text or null. index is 1 or 2. */
	public static String setSpawn(ServerPlayer p, int index) {
		Session session = SESSIONS.get(p.getUUID());
		if (session == null || session.mode != Mode.STUDIO) return "You are not in the arena studio. /createarena takes you there.";
		int cx = centerX(session.slot);
		if (!ArenaPaster.inBox(cx, p.getX(), p.getY(), p.getZ())) return "Stand inside the building box to set a spawn point.";
		double[] spawn = {p.getX() - cx, p.getY() - Arena.FLOOR_Y, p.getZ(), p.getYRot()};
		if (index == 1) session.spawn0 = spawn;
		else session.spawn1 = spawn;
		p.sendSystemMessage(Msg.ok("Spawn of player " + index + " set here, facing where you look."));
		return null;
	}

	// ------------------------------------------------------------------ events

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!inside(player)) return InteractionResult.PASS;
			Session s = SESSIONS.get(player.getUUID());
			if (s.mode == Mode.VIEW) return InteractionResult.FAIL;
			int cx = centerX(s.slot);
			BlockPos target = hit.getBlockPos();
			BlockPos placed = target.relative(hit.getDirection());
			return fits(cx, target) && fits(cx, placed) ? InteractionResult.PASS : InteractionResult.FAIL;
		});
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!inside(player)) return InteractionResult.PASS;
			Session s = SESSIONS.get(player.getUUID());
			return s.mode != Mode.VIEW && fits(centerX(s.slot), pos) ? InteractionResult.PASS : InteractionResult.FAIL;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			!inside(player) || (SESSIONS.get(player.getUUID()).mode != Mode.VIEW && fits(centerX(SESSIONS.get(player.getUUID()).slot), pos)));

		ServerTickEvents.END_SERVER_TICK.register(ArenaStudio::onTick);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer p = handler.player;
			if (SESSIONS.containsKey(p.getUUID())) exit(p); // keeps the draft and puts the backup back
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID id : Set.copyOf(SESSIONS.keySet())) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) exit(p);
			}
		});
	}

	private static boolean inside(Player player) {
		return player instanceof ServerPlayer sp && SESSIONS.containsKey(sp.getUUID());
	}

	private static boolean fits(int cx, BlockPos pos) {
		return ArenaPaster.inBox(cx, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	private static void onTick(MinecraftServer server) {
		if (SESSIONS.isEmpty() || server.getTickCount() % 10 != 0) return;
		ServerLevel level = Arena.level(server);
		for (Map.Entry<UUID, Session> entry : Map.copyOf(SESSIONS).entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
			if (p == null) {
				SESSIONS.remove(entry.getKey());
				continue;
			}
			if (!p.level().dimension().equals(Arena.DIMENSION)) {
				exit(p); // taken out of the studio world some other way
				continue;
			}
			Session session = entry.getValue();
			int cx = centerX(session.slot);
			boolean away = p.getX() < cx - ArenaPaster.HALF - MARGIN || p.getX() > cx + ArenaPaster.HALF + MARGIN
				|| p.getZ() < -ArenaPaster.HALF - MARGIN || p.getZ() > ArenaPaster.HALF + MARGIN
				|| p.getY() < ArenaPaster.MIN_Y - 10 || p.getY() > ArenaPaster.MIN_Y + ArenaData.SIZE + MARGIN;
			if (away) {
				p.teleportTo((ServerLevel) p.level(), cx + 0.5, Arena.FLOOR_Y + 1, 0.5, Set.of(), p.getYRot(), 0f, true);
				p.setDeltaMovement(0, 0, 0);
				p.resetFallDistance();
			}
			if (level != null) {
				mark(level, cx, session.spawn0, ParticleTypes.HAPPY_VILLAGER);
				mark(level, cx, session.spawn1, ParticleTypes.FLAME);
			}
		}
	}

	private static void mark(ServerLevel level, int cx, double[] spawn, net.minecraft.core.particles.SimpleParticleType type) {
		if (spawn == null) return;
		level.sendParticles(type, cx + spawn[0], Arena.FLOOR_Y + spawn[1] + 1.0, spawn[2], 3, 0.15, 0.6, 0.15, 0.0);
	}

	// ------------------------------------------------------------------ commands

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("createarena")
			.executes(ctx -> {
				ArenaMenus.openStudio(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.argument("template", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(java.util.List.of("void", "flat", "box"), builder))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					ArenaTemplates.Kind kind = ArenaTemplates.Kind.byId(StringArgumentType.getString(ctx, "template"));
					if (kind == null) {
						ctx.getSource().sendFailure(Msg.err("The template can be: void, flat or box."));
						return 0;
					}
					return answer(ctx.getSource(), enter(p, kind, null));
				})));

		dispatcher.register(Commands.literal("savearena")
			.then(Commands.argument("name", StringArgumentType.word())
				.executes(ctx -> answer(ctx.getSource(), save(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"))))));

		dispatcher.register(Commands.literal("setspawn")
			.then(Commands.argument("player", IntegerArgumentType.integer(1, 2))
				.executes(ctx -> answer(ctx.getSource(), setSpawn(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "player"))))));
	}

	static int answer(CommandSourceStack source, String problem) {
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}
}
