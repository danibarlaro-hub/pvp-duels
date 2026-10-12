package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.layout.LayoutStore;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

/** One running duel between two players: the round / points state machine. Driven by DuelManager every tick. */
public final class Duel {
	public enum Phase { STARTING, FIGHTING, ROUND_END, ENDED }

	static final int FIRST_COUNTDOWN = 100; // 5 s
	static final int ROUND_COUNTDOWN = 60;  // 3 s
	static final int ROUND_END_TICKS = 60;  // 3 s
	static final int END_TICKS = 100;       // 5 s
	/** Resistance after being put back in the world (see restore). */
	static final int PROTECTION_TICKS = 100;

	/** 5 minutes without any damage between the two players. Public so the self-test can shorten it. */
	public static int noContactLimit = 5 * 60 * 20;
	/** The warning starts this many ticks before the teleport. */
	public static int noContactWarning = 5 * 20;
	/** Unlimited arena: farther than this from your rival (in blocks) for farCountdown ticks costs half your health. */
	public static double farLimit = 100;
	public static int farCountdown = 5 * 20;
	/** Server ticks that count as one minute of a timed duel (1200 normally; the self-test shortens it). */
	public static int ticksPerMinute = 60 * 20;

	private final MinecraftServer server;
	final int slot;
	final Space space;
	final DuelConfig config;
	final UUID kitOwner;
	/** A practice duel against the /botduel bot: no stats, no 5-minute rule, and it closes as soon as you leave. */
	final boolean practice;
	private final KitStore.Kit kit;
	final UUID[] ids = new UUID[2];
	final String[] names = new String[2];
	final int[] points = new int[2];
	private final boolean[] restored = new boolean[2];

	Phase phase = Phase.STARTING;
	private int phaseTicks;
	private int countdownTotal = FIRST_COUNTDOWN;
	int round = 1;
	int elapsed;
	boolean timedOut;
	boolean tiebreak;
	private int noContact;
	private int farTicks;
	private boolean warnedTeleport;
	private boolean finishing;
	private boolean recorded;
	/** Practice duels: hits you landed on the bot, the damage they did, your current and best combo, and what the bot did to you. */
	int hits;
	int hitsTaken;
	int bestCombo;
	float damage;
	float damageTaken;
	private int combo;
	private int lastHitTick = -1000;
	private int roundHits;
	private int roundBest;
	private float roundDamage;
	private int lastCountedTick = -1;
	private int lastCountedVictim = -1;
	/** Two hits count as a combo when they are at most this many ticks apart (1 second). */
	static final int COMBO_GAP = 20;
	/** Elo before and after for both players (set when a real duel is recorded). */
	private int[] eloInfo;
	/** -2 undecided, -1 draw, 0 or 1 the winning index. */
	int winner = -2;
	boolean closed;

	Duel(MinecraftServer server, int slot, DuelConfig config, UUID kitOwner, KitStore.Kit kit, ServerPlayer a, ServerPlayer b) {
		this.server = server;
		this.slot = slot;
		this.space = Space.of(config, slot);
		this.config = config;
		this.kitOwner = kitOwner;
		this.kit = kit;
		ids[0] = a.getUUID();
		ids[1] = b.getUUID();
		this.practice = BotDuel.isBot(ids[0]) || BotDuel.isBot(ids[1]);
		names[0] = a.getName().getString();
		names[1] = b.getName().getString();
	}

	// ------------------------------------------------------------------ helpers

	MinecraftServer server() {
		return server;
	}

	ServerPlayer player(int i) {
		return server.getPlayerList().getPlayer(ids[i]);
	}

	int indexOf(UUID id) {
		if (ids[0].equals(id)) return 0;
		if (ids[1].equals(id)) return 1;
		return -1;
	}

	private void sendTitle(ServerPlayer p, Component title, Component subtitle, int in, int stay, int out) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	private void tell(Component message) {
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p != null && !restored[i]) p.sendSystemMessage(message);
		}
	}

	private String score(int i) {
		return points[i] + " - " + points[1 - i];
	}

	private LayoutStore.Profile profileFor(ServerPlayer p) {
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		LayoutStore.Profile profile = kit.profile() == null ? null : data.profiles.get(kit.profile());
		if (profile == null) profile = data.profiles.get(data.active);
		return profile != null && LayoutStore.hasRules(profile) ? profile : null;
	}

	// ------------------------------------------------------------------ lifecycle

	/** Saves both inventories to disk, builds the arena, and puts both players in it. */
	void begin(ServerLevel arena) throws IOException {
		ServerPlayer a = player(0);
		ServerPlayer b = player(1);
		// A backup still on disk means the player was never given their stuff back: do that first.
		for (ServerPlayer p : new ServerPlayer[] {a, b}) {
			if (PlayerBackup.exists(p.getUUID())) PlayerBackup.restore(server, p);
		}
		try {
			PlayerBackup.save(server, a);
			PlayerBackup.save(server, b);
		} catch (IOException e) {
			PlayerBackup.discard(ids[0]);
			PlayerBackup.discard(ids[1]);
			throw e;
		}
		space.begin(arena);
		for (int i = 0; i < 2; i++) setupPlayer(i, arena);
		phase = Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = FIRST_COUNTDOWN;

		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			p.sendSystemMessage(Msg.title("Duel vs " + names[1 - i]));
			p.sendSystemMessage(Msg.info("  Kit: " + config.kit() + "   Arena: " + config.arenaName()));
			p.sendSystemMessage(Msg.info("  Mode: " + config.modeName() + "   Use /leave to quit."));
			if (!config.rules().isDefault()) p.sendSystemMessage(Msg.info("  Rules: " + config.rules().describe(config.arena())));
		}
	}

	private void setupPlayer(int i, ServerLevel arena) {
		ServerPlayer p = player(i);
		if (p == null) return;
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
		// The arena can be dark at night, so both players always see well.
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 72000, 0, false, false));
		space.teleportToSpawn(p, arena, i);
	}

	// ------------------------------------------------------------------ per tick

	void tick() {
		if (closed) return;
		ServerPlayer a = player(0);
		ServerPlayer b = player(1);
		if (phase != Phase.ENDED) {
			if ((a == null || b == null)) {
				forfeit(a == null ? 0 : 1);
				return;
			}
			elapsed++;
			applyFoodRules();
		}
		switch (phase) {
			case STARTING -> tickStarting(a, b);
			case FIGHTING -> tickFighting(a, b);
			case ROUND_END -> {
				phaseTicks++;
				if (phaseTicks >= ROUND_END_TICKS) {
					if (finishing) finish(); else nextRound();
				}
			}
			case ENDED -> {
				phaseTicks++;
				if (phaseTicks >= END_TICKS) close();
			}
		}
	}

	/** Rules "no natural regeneration" (food stays at 17: no regeneration, you can still sprint) and "no hunger". */
	private void applyFoodRules() {
		DuelConfig.Rules rules = config.rules();
		if (rules.regen() && rules.hunger()) return;
		for (int i = 0; i < 2; i++) {
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

	private void tickStarting(ServerPlayer a, ServerPlayer b) {
		phaseTicks++;
		ServerLevel arena = space.level(server);
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			double[] s = space.spawn(i);
			if (arena != null && p.distanceToSqr(s[0], s[1], s[2]) > 0.3) {
				// Nobody walks during the countdown.
				p.teleportTo(arena, s[0], s[1], s[2], Set.of(), p.getYRot(), p.getXRot(), true);
				p.setDeltaMovement(0, 0, 0);
			}
		}
		int left = countdownTotal - phaseTicks;
		if (left > 0 && left % 20 == 0) {
			int seconds = left / 20;
			Component sub = Component.literal(roundLabel()).withStyle(ChatFormatting.GRAY);
			Component number = Component.literal(String.valueOf(seconds)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
			sendTitle(a, number, sub, 0, 25, 5);
			sendTitle(b, number, sub, 0, 25, 5);
		}
		if (left <= 0) {
			phase = Phase.FIGHTING;
			phaseTicks = 0;
			noContact = 0;
			warnedTeleport = false;
			Component fight = Component.literal("FIGHT!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(a, fight, Component.empty(), 0, 15, 10);
			sendTitle(b, fight, Component.empty(), 0, 15, 10);
		}
	}

	private void tickFighting(ServerPlayer a, ServerPlayer b) {
		phaseTicks++;
		noContact++;

		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			boolean wrongWorld = !p.level().dimension().equals(space.dimension());
			String out = wrongWorld ? "left the arena." : space.outOfBounds(p);
			if (out != null) {
				endRound(1 - i, names[i] + " " + out);
				return;
			}
		}
		space.follow(a, b);
		if (config.arena() == ArenaKind.UNLIMITED) tickFar(a, b);

		if (config.mode() == WinMode.TIMED && !timedOut && elapsed >= config.limit() * ticksPerMinute) {
			timedOut = true;
			tell(Component.literal("Time is up!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Msg.info(" This round decides the duel.")));
		}

		// Rule: no damage between the two for 5 minutes -> one random player is sent next to the other.
		if (practice) noContact = 0; // the bot never fights, so there is nothing to force
		if (!warnedTeleport && noContact >= noContactLimit - noContactWarning) {
			warnedTeleport = true;
			Component warn = Component.literal("No contact for a long time: one of you will be teleported next to the other in "
				+ Math.max(1, noContactWarning / 20) + "s.").withStyle(ChatFormatting.YELLOW);
			tell(warn);
		}
		if (noContact >= noContactLimit) {
			int who = server.overworld().getRandom().nextInt(2);
			Arena.teleportNextTo(player(who), player(1 - who));
			tell(Msg.info(names[who] + " was teleported next to " + names[1 - who] + "."));
			noContact = 0;
			warnedTeleport = false;
		}

		if (phaseTicks % 20 == 0) showScoreboard();
	}

	/**
	 * The 100-block rule of the unlimited arena: if the two are too far apart for 5 seconds, the one who is farther
	 * from the start point (the one who ran away) loses half of their health and is sent next to the other.
	 */
	private void tickFar(ServerPlayer a, ServerPlayer b) {
		if (a.distanceTo(b) <= farLimit) {
			if (farTicks > 0) {
				tell(Msg.ok("You are close enough again."));
				farTicks = 0;
			}
			return;
		}
		if (farTicks == 0) {
			tell(Component.literal("You are more than " + (int) farLimit + " blocks apart! Get closer within "
				+ Math.max(1, farCountdown / 20) + " seconds or the one who ran away loses half of their health.").withStyle(ChatFormatting.YELLOW));
		}
		farTicks++;
		int left = farCountdown - farTicks;
		if (left > 0 && left % 20 == 0) {
			Component text = Component.literal("Too far apart! " + (left / 20) + "s").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			a.sendOverlayMessage(text);
			b.sendOverlayMessage(text);
		}
		if (farTicks >= farCountdown) {
			farTicks = 0;
			double da = Math.pow(a.getX() - space.centerX(), 2) + Math.pow(a.getZ() - space.centerZ(), 2);
			double db = Math.pow(b.getX() - space.centerX(), 2) + Math.pow(b.getZ() - space.centerZ(), 2);
			int who = da >= db ? 0 : 1;
			ServerPlayer runner = player(who);
			ServerPlayer other = player(1 - who);
			runner.setHealth(Math.max(1f, runner.getHealth() / 2f));
			Arena.teleportNextTo(runner, other);
			tell(Msg.info(names[who] + " went too far away: lost half of their health and was teleported next to " + names[1 - who] + "."));
		}
	}

	private String roundLabel() {
		String label = "Round " + round;
		if (config.mode() == WinMode.ROUNDS && config.limit() > 0 && !tiebreak) label += " of " + config.limit();
		if (tiebreak) label = "Tiebreak round";
		return label;
	}

	private void showScoreboard() {
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p == null) continue;
			String text = names[i] + " " + score(i) + " " + names[1 - i] + "   |   " + roundLabel();
			if (config.mode() == WinMode.TIMED && !timedOut) {
				int left = Math.max(0, (config.limit() * ticksPerMinute - elapsed) / 20);
				text += String.format("   |   %d:%02d", left / 60, left % 60);
			}
			p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.WHITE));
		}
	}

	// ------------------------------------------------------------------ practice counters

	private static String oneDecimal(float value) {
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** The combo you have right now (it is over when more than a second passed since your last hit). */
	int currentCombo() {
		return elapsed - lastHitTick <= COMBO_GAP ? combo : 0;
	}

	/** A hit between the player and the bot. "victim" is the duelist who was hurt; amount is the damage it did. */
	void onPracticeHit(int victim, float amount) {
		if (!practice || phase != Phase.FIGHTING || amount <= 0f) return;
		if (lastCountedTick == elapsed && lastCountedVictim == victim) return; // the same blow reported twice
		lastCountedTick = elapsed;
		lastCountedVictim = victim;
		if (BotDuel.isBot(ids[victim])) {
			hits++;
			roundHits++;
			damage += amount;
			roundDamage += amount;
			combo = currentCombo() + 1;
			lastHitTick = elapsed;
			bestCombo = Math.max(bestCombo, combo);
			roundBest = Math.max(roundBest, combo);
			ServerPlayer attacker = player(1 - victim);
			if (attacker != null) {
				attacker.sendOverlayMessage(Component.literal("Combo " + combo + "  |  " + oneDecimal(amount) + " damage").withStyle(ChatFormatting.AQUA));
			}
		} else {
			hitsTaken++;
			damageTaken += amount;
			combo = 0; // getting hit breaks your combo
		}
	}

	// ------------------------------------------------------------------ events from DuelManager

	boolean canTakeDamage() {
		return phase == Phase.FIGHTING;
	}

	/** A hit between the two players resets the no-contact clock. */
	void onContact() {
		noContact = 0;
		warnedTeleport = false;
	}

	/** A duelist would have died. Returns normally; the caller cancels the real death. */
	void onDeath(ServerPlayer p, DamageSource source) {
		int i = indexOf(p.getUUID());
		if (i < 0) return;
		p.setHealth(p.getMaxHealth());
		if (phase != Phase.FIGHTING) return;
		Entity killer = source == null ? null : source.getEntity();
		String reason = killer != null && killer.getUUID().equals(ids[1 - i])
			? names[1 - i] + " defeated " + names[i] + "."
			: names[i] + " died.";
		endRound(1 - i, reason);
	}

	private void endRound(int winnerIdx, String reason) {
		if (phase != Phase.FIGHTING) return;
		phase = Phase.ROUND_END;
		phaseTicks = 0;
		if (winnerIdx >= 0) points[winnerIdx]++;
		decide();
		if (practice) {
			ServerPlayer you = player(0);
			if (you != null) {
				you.sendSystemMessage(Msg.info("Round " + round + ": " + roundHits + (roundHits == 1 ? " hit, " : " hits, ")
					+ oneDecimal(roundDamage) + " damage, best combo " + roundBest));
			}
			roundHits = 0;
			roundDamage = 0f;
			roundBest = 0;
			combo = 0;
		}

		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p == null) continue;
			Component title = i == winnerIdx
				? Component.literal("Round won").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
				: Component.literal("Round lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(p, title, Component.literal(score(i)).withStyle(ChatFormatting.WHITE), 0, 40, 10);
			p.sendSystemMessage(Msg.info(reason + "  Score: " + score(i)));
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
			for (int i = 0; i < 2; i++) {
				if (points[i] >= config.limit()) {
					finishing = true;
					winner = i;
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
		for (int i = 0; i < 2; i++) setupPlayer(i, arena);
		phase = Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = ROUND_COUNTDOWN;
		noContact = 0;
		warnedTeleport = false;
	}

	/** Someone left, disconnected, or typed /leave. */
	void forfeit(int idx) {
		if (phase == Phase.ENDED || closed) return;
		if (config.unlimited()) {
			winner = points[0] == points[1] ? -1 : (points[0] > points[1] ? 0 : 1);
		} else {
			winner = 1 - idx;
			ServerPlayer other = player(1 - idx);
			if (other != null) other.sendSystemMessage(Msg.info(names[idx] + " left the duel."));
		}
		finish();
	}

	/** The lines of the sidebar for one of the two players. */
	public List<String> sidebarFor(UUID viewer) {
		int i = indexOf(viewer);
		List<String> out = new ArrayList<>();
		if (i < 0) return out;
		out.add("vs " + names[1 - i]);
		out.add("");
		out.add(names[i] + ": " + points[i]);
		out.add(names[1 - i] + ": " + points[1 - i]);
		out.add("");
		out.add(roundLabel());
		switch (config.mode()) {
			case FIRST_TO -> out.add(config.limit() > 0 ? "First to " + config.limit() : "Unlimited");
			case ROUNDS -> { if (config.limit() == 0) out.add("Unlimited"); }
			case TIMED -> {
				if (timedOut) out.add("Time is up!");
				else {
					int left = Math.max(0, (config.limit() * ticksPerMinute - elapsed) / 20);
					out.add(String.format("Time left %d:%02d", left / 60, left % 60));
				}
			}
		}
		out.add("Kit: " + config.kit());
		if (practice && i == 0) {
			BotLevel level = BotDuel.levelOf(ids[1]);
			out.add("");
			out.add("Hits: " + hits);
			out.add("Damage: " + oneDecimal(damage));
			out.add("Combo: " + currentCombo() + " (best " + bestCombo + ")");
			if (level != null && level.fights) out.add("Hits taken: " + hitsTaken);
			if (level != null) out.add("Bot: " + level.label);
		}
		if (phase == Phase.ENDED) out.add(winner == -1 ? "Draw" : (winner == i ? "You won!" : "You lost"));
		return out;
	}

	/** The lines of the sidebar for somebody who is watching this duel. */
	public List<String> sidebarForSpectator() {
		List<String> out = new ArrayList<>();
		out.add("vs Spectating");
		out.add("");
		out.add(names[0] + ": " + points[0]);
		out.add(names[1] + ": " + points[1]);
		out.add("");
		out.add(roundLabel());
		out.add(config.arenaName());
		out.add("");
		out.add("/leave to stop");
		return out;
	}

	private void recordStats() {
		if (recorded || winner == -2) return;
		recorded = true;
		// Practice duels against a /test_dummy don't count.
		if (TestDummy.accepts(ids[0]) || TestDummy.accepts(ids[1])) return;
		eloInfo = StatsStore.recordDuel(ids[0], names[0], ids[1], names[1], winner, points[0], points[1]);
	}

	private void finish() {
		recordStats();
		DuelManager.onFinished(this);
		phase = Phase.ENDED;
		phaseTicks = 0;
		finishing = true;
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p == null || restored[i]) continue;
			Component title;
			if (winner == -1) title = Component.literal("DRAW").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
			else if (winner == i) title = Component.literal("VICTORY").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
			else title = Component.literal("DEFEAT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(p, title, Component.literal("Final score " + score(i)).withStyle(ChatFormatting.WHITE), 5, 80, 15);
			p.sendSystemMessage(Msg.title("Duel finished: " + names[0] + " " + points[0] + " - " + points[1] + " " + names[1]));
			if (practice) {
				if (i == 0) {
					p.sendSystemMessage(Msg.info("Practice total: " + hits + (hits == 1 ? " hit, " : " hits, ") + oneDecimal(damage)
						+ " damage, best combo " + bestCombo + (hitsTaken > 0 ? ", " + hitsTaken + " hits taken" : "")));
					p.sendSystemMessage(Msg.info("Type /botduel to practice again."));
				}
				continue;
			}
			if (eloInfo != null) {
				int before = eloInfo[2 * i];
				int after = eloInfo[2 * i + 1];
				p.sendSystemMessage(Msg.info("Elo: " + before + " -> " + after + " (" + (after >= before ? "+" : "") + (after - before) + ")"));
			}
			p.sendSystemMessage(Msg.info("Type /again to ask for a rematch."));
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
			// A few seconds of protection, so nobody is hurt right after being put back.
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, PROTECTION_TICKS, 4, false, false));
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, PROTECTION_TICKS, 0, false, false));
			p.sendSystemMessage(Msg.ok("Your inventory has been restored." + (lobby ? " Welcome to the lobby." : "")
				+ " You are protected for " + PROTECTION_TICKS / 20 + " seconds."));
		}
	}

	/** Ends the duel right now (used when you leave a practice duel: the bot has no reason to wait around). */
	void closeNow() {
		close();
	}

	private void close() {
		if (closed) return;
		closed = true;
		restore(0);
		restore(1);
		Spectators.release(this);
		space.end(space.level(server));
		DuelManager.onClosed(this);
		PvpDuelsMod.LOGGER.info("Duel {} vs {} closed ({} - {}).", names[0], names[1], points[0], points[1]);
	}

	/** Server is stopping: put everybody back immediately. */
	void abort() {
		if (closed) return;
		closed = true;
		restore(0);
		restore(1);
		DuelManager.onClosed(this);
	}
}
