package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.achievements.Achievements;
import com.pvpduels.kit.KitStore;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.layout.LayoutStore;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A 2v2 duel: two teams of two (a party each) fight in rounds with the same kit. A player who dies is out for the rest of the
 * round (they watch in spectator mode); a round is won when both players of the other team are out. There is no friendly fire.
 * The score counts rounds per team and the win modes are the same as in a 1v1. It does not touch the Elo.
 * Players 0 and 1 are team 0, players 2 and 3 are team 1.
 */
public final class TeamDuel {
	static final int PLAYERS = 4;

	private final MinecraftServer server;
	final int slot;
	final Space space;
	final DuelConfig config;
	final UUID kitOwner;
	private final KitStore.Kit kit;
	final UUID[] ids = new UUID[PLAYERS];
	final String[] names = new String[PLAYERS];
	/** Rounds won by each team. */
	final int[] points = new int[2];
	private final boolean[] restored = new boolean[PLAYERS];
	/** Out for the round. */
	private final boolean[] dead = new boolean[PLAYERS];

	Duel.Phase phase = Duel.Phase.STARTING;
	private int phaseTicks;
	private int countdownTotal = Duel.FIRST_COUNTDOWN;
	int round = 1;
	int elapsed;
	boolean timedOut;
	boolean tiebreak;
	private boolean finishing;
	private boolean recorded;
	/** -2 undecided, -1 draw, 0 or 1 the winning team. */
	int winner = -2;
	boolean closed;

	TeamDuel(MinecraftServer server, int slot, DuelConfig config, UUID kitOwner, KitStore.Kit kit, ServerPlayer[] players) {
		this.server = server;
		this.slot = slot;
		this.space = Space.of(config, slot);
		this.config = config;
		this.kitOwner = kitOwner;
		this.kit = kit;
		for (int i = 0; i < PLAYERS; i++) {
			ids[i] = players[i].getUUID();
			names[i] = players[i].getName().getString();
		}
	}

	// ------------------------------------------------------------------ helpers

	static int teamOf(int index) {
		return index / 2;
	}

	private static int mate(int index) {
		return index ^ 1;
	}

	ServerPlayer player(int i) {
		return server.getPlayerList().getPlayer(ids[i]);
	}

	int indexOf(UUID id) {
		for (int i = 0; i < PLAYERS; i++) if (ids[i].equals(id)) return i;
		return -1;
	}

	String teamName(int team) {
		return names[2 * team] + " & " + names[2 * team + 1];
	}

	private int alive(int team) {
		int n = 0;
		for (int i = 2 * team; i < 2 * team + 2; i++) if (!dead[i]) n++;
		return n;
	}

	private void sendTitle(ServerPlayer p, Component title, Component subtitle, int in, int stay, int out) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	private void tell(Component message) {
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p != null && !restored[i]) p.sendSystemMessage(message);
		}
	}

	private String score(int team) {
		return points[team] + " - " + points[1 - team];
	}

	private String roundLabel() {
		String label = "Round " + round;
		if (config.mode() == WinMode.ROUNDS && config.limit() > 0 && !tiebreak) label += " of " + config.limit();
		if (tiebreak) label = "Tiebreak round";
		return label;
	}

	/** Where player i starts: their team's spawn point, the two teammates side by side. */
	double[] spawnFor(int i) {
		double[] s = space.spawn(teamOf(i));
		double yaw = Math.toRadians(s[3]);
		double sideX = -Math.cos(yaw);
		double sideZ = -Math.sin(yaw);
		double side = (i % 2 == 0 ? -1.2 : 1.2);
		return new double[] {s[0] + side * sideX, s[1], s[2] + side * sideZ, s[3]};
	}

	private LayoutStore.Profile profileFor(ServerPlayer p) {
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		LayoutStore.Profile profile = kit.profile() == null ? null : data.profiles.get(kit.profile());
		if (profile == null) profile = data.profiles.get(data.active);
		return profile != null && LayoutStore.hasRules(profile) ? profile : null;
	}

	// ------------------------------------------------------------------ lifecycle

	/** Saves the four inventories to disk, builds the arena and puts the four players in it. */
	void begin(ServerLevel arena) throws IOException {
		ServerPlayer[] ps = new ServerPlayer[PLAYERS];
		for (int i = 0; i < PLAYERS; i++) {
			ps[i] = player(i);
			if (PlayerBackup.exists(ids[i])) PlayerBackup.restore(server, ps[i]);
		}
		try {
			for (ServerPlayer p : ps) PlayerBackup.save(server, p);
		} catch (IOException e) {
			for (UUID id : ids) PlayerBackup.discard(id);
			throw e;
		}
		space.begin(arena);
		for (int i = 0; i < PLAYERS; i++) setupPlayer(i, arena);
		phase = Duel.Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = Duel.FIRST_COUNTDOWN;

		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = ps[i];
			int t = teamOf(i);
			p.sendSystemMessage(Msg.title("2v2: " + teamName(t) + " vs " + teamName(1 - t)));
			p.sendSystemMessage(Msg.info("  Kit: " + config.kit() + "   Arena: " + config.arenaName()));
			p.sendSystemMessage(Msg.info("  Mode: " + config.modeName() + "   Use /leave to quit (your team forfeits)."));
			p.sendSystemMessage(Msg.info("  A round is won when both players of the other team are out. No friendly fire."));
			if (!config.rules().isDefault()) p.sendSystemMessage(Msg.info("  Rules: " + config.rules().describe(config.arena())));
			if (kit.stacks() == 0) p.sendSystemMessage(Msg.err("  The kit '" + config.kit() + "' is EMPTY, so you start with nothing."));
		}
	}

	private void setupPlayer(int i, ServerLevel arena) {
		ServerPlayer p = player(i);
		if (p == null) return;
		dead[i] = false;
		p.closeContainer();
		p.setGameMode(GameType.SURVIVAL);
		p.removeAllEffects();
		p.clearFire();
		p.setHealth(p.getMaxHealth());
		p.setAbsorptionAmount(0f);
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(20f);
		KitStore.give(p, kit);
		LayoutStore.Profile profile = profileFor(p);
		if (profile != null) InventorySorter.sort(p, profile);
		p.getInventory().setSelectedSlot(0);
		resync(p);
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 72000, 0, false, false));
		double[] s = spawnFor(i);
		p.teleportTo(arena, s[0], s[1], s[2], Set.of(), (float) s[3], 0f, true);
		p.setDeltaMovement(0, 0, 0);
		p.resetFallDistance();
	}

	private static void resync(ServerPlayer p) {
		p.inventoryMenu.sendAllDataToRemote();
		if (p.containerMenu != p.inventoryMenu) p.containerMenu.sendAllDataToRemote();
		if (p.connection != null) p.connection.send(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
	}

	// ------------------------------------------------------------------ per tick

	void tick() {
		if (closed) return;
		if (phase != Duel.Phase.ENDED) {
			for (int i = 0; i < PLAYERS; i++) {
				if (player(i) == null && !restored[i]) {
					forfeit(i);
					return;
				}
			}
			elapsed++;
			applyFoodRules();
		}
		switch (phase) {
			case STARTING -> tickStarting();
			case FIGHTING -> tickFighting();
			case ROUND_END -> {
				phaseTicks++;
				if (phaseTicks >= Duel.ROUND_END_TICKS) {
					if (finishing) finish(); else nextRound();
				}
			}
			case ENDED -> {
				phaseTicks++;
				if (phaseTicks >= Duel.END_TICKS) close();
			}
		}
	}

	private void applyFoodRules() {
		DuelConfig.Rules rules = config.rules();
		if (rules.regen() && rules.hunger()) return;
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null || restored[i]) continue;
			if (!rules.regen()) {
				p.getFoodData().setFoodLevel(17);
				p.getFoodData().setSaturation(0f);
			} else {
				p.getFoodData().setFoodLevel(20);
				p.getFoodData().setSaturation(20f);
			}
		}
	}

	private void tickStarting() {
		phaseTicks++;
		ServerLevel arena = space.level(server);
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null) continue;
			double[] s = spawnFor(i);
			if (arena != null && p.distanceToSqr(s[0], s[1], s[2]) > 0.3) {
				p.teleportTo(arena, s[0], s[1], s[2], Set.of(), p.getYRot(), p.getXRot(), true);
				p.setDeltaMovement(0, 0, 0);
			}
		}
		int left = countdownTotal - phaseTicks;
		if (left > 0 && left % 20 == 0) {
			Component sub = Component.literal(roundLabel()).withStyle(ChatFormatting.GRAY);
			Component number = Component.literal(String.valueOf(left / 20)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
			for (int i = 0; i < PLAYERS; i++) {
				ServerPlayer p = player(i);
				if (p != null) sendTitle(p, number, sub, 0, 25, 5);
			}
		}
		if (phaseTicks == 2) {
			for (int i = 0; i < PLAYERS; i++) {
				ServerPlayer p = player(i);
				if (p != null) resync(p);
			}
		}
		if (left <= 0) {
			phase = Duel.Phase.FIGHTING;
			phaseTicks = 0;
			Component fight = Component.literal("FIGHT!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			for (int i = 0; i < PLAYERS; i++) {
				ServerPlayer p = player(i);
				if (p != null) sendTitle(p, fight, Component.empty(), 0, 15, 10);
			}
		}
	}

	private void tickFighting() {
		phaseTicks++;
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null || dead[i]) continue;
			boolean wrongWorld = !p.level().dimension().equals(space.dimension());
			String out = wrongWorld ? "left the arena." : space.outOfBounds(p);
			if (out != null) {
				knockOut(i, names[i] + " " + out);
				if (phase != Duel.Phase.FIGHTING) return;
			}
		}
		for (int t = 0; t < 2; t++) {
			ServerPlayer a = player(2 * t);
			ServerPlayer b = player(2 * t + 1);
			if (a != null && b != null) space.follow(a, b);
		}
		if (config.mode() == WinMode.TIMED && !timedOut && elapsed >= config.limit() * Duel.ticksPerMinute) {
			timedOut = true;
			tell(Component.literal("Time is up!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD).append(Msg.info(" This round decides the duel.")));
		}
		if (phaseTicks % 20 == 0) showScoreboard();
	}

	private void showScoreboard() {
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null) continue;
			int t = teamOf(i);
			String text = "Your team " + score(t) + "   |   " + roundLabel() + "   |   " + alive(t) + " vs " + alive(1 - t) + " alive";
			if (config.mode() == WinMode.TIMED && !timedOut) {
				int left = Math.max(0, (config.limit() * Duel.ticksPerMinute - elapsed) / 20);
				text += String.format("   |   %d:%02d", left / 60, left % 60);
			}
			p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.WHITE));
		}
	}

	// ------------------------------------------------------------------ events from DuelManager

	/** Whether this blow may hurt: only while fighting and never between teammates. */
	boolean allowDamage(ServerPlayer victim, DamageSource source) {
		if (phase != Duel.Phase.FIGHTING) return false;
		int vi = indexOf(victim.getUUID());
		if (vi < 0 || dead[vi]) return false;
		if (!config.rules().fallDamage() && source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) return false;
		Entity attacker = source.getEntity();
		if (attacker != null && attacker.getUUID().equals(ids[mate(vi)])) return false; // no friendly fire
		return true;
	}

	/** A duelist would have died. The caller cancels the real death. */
	void onDeath(ServerPlayer p, DamageSource source) {
		int i = indexOf(p.getUUID());
		if (i < 0) return;
		p.setHealth(p.getMaxHealth());
		if (phase != Duel.Phase.FIGHTING || dead[i]) return;
		Entity killer = source == null ? null : source.getEntity();
		String reason = killer != null && indexOf(killer.getUUID()) >= 0 && teamOf(indexOf(killer.getUUID())) != teamOf(i)
			? names[indexOf(killer.getUUID())] + " defeated " + names[i] + "."
			: names[i] + " died.";
		knockOut(i, reason);
	}

	/** Player i is out for the rest of the round; when their team is out, the round is over. */
	private void knockOut(int i, String reason) {
		if (phase != Duel.Phase.FIGHTING || dead[i]) return;
		dead[i] = true;
		ServerPlayer p = player(i);
		if (p != null) {
			p.setHealth(p.getMaxHealth());
			p.clearFire();
			p.setGameMode(GameType.SPECTATOR);
			if (p.level().dimension().equals(space.dimension()) && space.outOfBounds(p) != null) {
				// they fell out: send the ghost back over the middle of the arena
				double[] s = space.spawn(teamOf(i));
				p.teleportTo(space.level(server), s[0], s[1] + 3, s[2], Set.of(), p.getYRot(), 0f, true);
			}
		}
		tell(Msg.info(reason + "  (" + alive(0) + " vs " + alive(1) + " alive)"));
		int t = teamOf(i);
		if (alive(t) == 0) endRound(1 - t, reason);
	}

	private void endRound(int winnerTeam, String reason) {
		if (phase != Duel.Phase.FIGHTING) return;
		phase = Duel.Phase.ROUND_END;
		phaseTicks = 0;
		points[winnerTeam]++;
		decide();
		for (int i = 2 * winnerTeam; i < 2 * winnerTeam + 2; i++) {
			// the last one standing of a team that lost their mate won it alone: a clutch
			if (!dead[i] && dead[mate(i)]) Achievements.add(ids[i], names[i], "team_clutches", 1);
		}
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null) continue;
			int t = teamOf(i);
			Component title = t == winnerTeam
				? Component.literal("Round won").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
				: Component.literal("Round lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(p, title, Component.literal(score(t)).withStyle(ChatFormatting.WHITE), 0, 40, 10);
			p.sendSystemMessage(Msg.info("Round " + round + " goes to " + teamName(winnerTeam) + ".  Score: " + score(t)));
		}
	}

	/** Works out whether the duel is over after the round that just ended. */
	private void decide() {
		finishing = false;
		boolean reached = switch (config.mode()) {
			case ROUNDS -> config.limit() > 0 && round >= config.limit();
			case TIMED -> timedOut;
			case FIRST_TO -> false;
		};
		if (config.mode() == WinMode.FIRST_TO && config.limit() > 0) {
			for (int t = 0; t < 2; t++) {
				if (points[t] >= config.limit()) {
					finishing = true;
					winner = t;
				}
			}
		} else if (reached) {
			if (points[0] != points[1]) {
				finishing = true;
				winner = points[0] > points[1] ? 0 : 1;
			} else {
				tiebreak = true;
			}
		}
	}

	private void nextRound() {
		round++;
		ServerLevel arena = space.level(server);
		if (arena == null) {
			forfeit(0);
			return;
		}
		space.begin(arena);
		for (int i = 0; i < PLAYERS; i++) setupPlayer(i, arena);
		phase = Duel.Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = Duel.ROUND_COUNTDOWN;
	}

	/** Someone left, disconnected or typed /leave: their team forfeits. */
	void forfeit(int idx) {
		if (phase == Duel.Phase.ENDED || closed) return;
		PvpDuelsMod.LOGGER.info("2v2 {} vs {}: {} left (round {}, phase {})", teamName(0), teamName(1), names[idx], round, phase);
		int team = teamOf(idx);
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p != null && i != idx) p.sendSystemMessage(Msg.info(names[idx] + " left the duel, so " + teamName(team) + " forfeit."));
		}
		if (config.unlimited()) {
			winner = points[0] == points[1] ? -1 : (points[0] > points[1] ? 0 : 1);
		} else {
			winner = 1 - team;
		}
		finish();
	}

	private void finish() {
		if (!recorded) {
			recorded = true;
			for (int i = 0; i < PLAYERS; i++) {
				if (winner >= 0 && teamOf(i) == winner) Achievements.add(ids[i], names[i], "team_wins", 1);
			}
		}
		DuelManager.onTeamFinished(this);
		phase = Duel.Phase.ENDED;
		phaseTicks = 0;
		finishing = true;
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = player(i);
			if (p == null || restored[i]) continue;
			int t = teamOf(i);
			Component title;
			if (winner == -1) title = Component.literal("DRAW").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
			else if (winner == t) title = Component.literal("VICTORY").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
			else title = Component.literal("DEFEAT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(p, title, Component.literal("Final score " + score(t)).withStyle(ChatFormatting.WHITE), 5, 80, 15);
			p.sendSystemMessage(Msg.title("2v2 finished: " + teamName(0) + " " + points[0] + " - " + points[1] + " " + teamName(1)));
		}
	}

	/** Gives a player their inventory and position back (when they /leave, or when the duel closes). */
	void restore(int i) {
		if (restored[i]) return;
		restored[i] = true;
		DuelManager.onLeft(ids[i]);
		ServerPlayer p = player(i);
		if (p != null) {
			boolean lobby = Lobby.appliesTo(server, p);
			PlayerBackup.restore(server, p, true);
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, Duel.PROTECTION_TICKS, 4, false, false));
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, Duel.PROTECTION_TICKS, 0, false, false));
			p.sendSystemMessage(Msg.ok("Your inventory has been restored." + (lobby ? " Welcome to the lobby." : "")
				+ " You are protected for " + Duel.PROTECTION_TICKS / 20 + " seconds."));
		}
	}

	private void close() {
		if (closed) return;
		closed = true;
		for (int i = 0; i < PLAYERS; i++) restore(i);
		space.end(space.level(server));
		DuelManager.onTeamClosed(this);
		PvpDuelsMod.LOGGER.info("2v2 {} vs {} closed ({} - {}).", teamName(0), teamName(1), points[0], points[1]);
	}

	/** Server is stopping: put everybody back immediately. */
	void abort() {
		if (closed) return;
		closed = true;
		for (int i = 0; i < PLAYERS; i++) restore(i);
		DuelManager.onTeamClosed(this);
	}

	/** Closes the duel right now (used by the self-test). */
	void closeNow() {
		close();
	}

	// ------------------------------------------------------------------ sidebar

	public List<String> sidebarFor(UUID viewer) {
		int i = indexOf(viewer);
		List<String> out = new ArrayList<>();
		if (i < 0) return out;
		int t = teamOf(i);
		out.add("2v2 with " + names[mate(i)]);
		out.add("vs " + teamName(1 - t));
		out.add("");
		out.add("Your team: " + points[t]);
		out.add("Them: " + points[1 - t]);
		out.add("");
		out.add(roundLabel());
		out.add("Alive: " + alive(t) + " vs " + alive(1 - t));
		switch (config.mode()) {
			case FIRST_TO -> out.add(config.limit() > 0 ? "First to " + config.limit() : "Unlimited");
			case ROUNDS -> { if (config.limit() == 0) out.add("Unlimited"); }
			case TIMED -> {
				if (timedOut) out.add("Time is up!");
				else {
					int left = Math.max(0, (config.limit() * Duel.ticksPerMinute - elapsed) / 20);
					out.add(String.format("Time left %d:%02d", left / 60, left % 60));
				}
			}
		}
		out.add("Kit: " + config.kit());
		if (phase == Duel.Phase.ENDED) out.add(winner == -1 ? "Draw" : (winner == t ? "You won!" : "You lost"));
		return out;
	}
}
