package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.achievements.Achievements;
import com.pvpduels.effects.Missions;
import com.pvpduels.effects.Victory;
import com.pvpduels.kit.KitStore;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.layout.LayoutStore;
import com.pvpduels.stats.Seasons;
import com.pvpduels.stats.Leaderboards;
import com.pvpduels.stats.StatsStore;
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
	/** For the self-test: whether this duel has been closed already. */
	boolean isClosedForTest() {
		return closed;
	}

	/** The self-test sets this: every end of a duel then logs where it was decided from. */
	public static boolean traceEnd = false;

	private final MinecraftServer server;
	final int slot;
	final Space space;
	final DuelConfig config;
	final UUID kitOwner;
	/** A practice duel against the /botduel bot: no stats, no 5-minute rule, and it closes as soon as you leave. */
	final boolean practice;
	private final KitStore.Kit kit;
	final UUID[] ids = new UUID[2];
	/** The twist of the round that is being played (never RANDOM: that one is drawn again in every round). */
	private Mutator mutator = Mutator.NONE;
	/** The last blow each duelist took (it explains a round that ends by a death). */
	final LastBlow[] blows = {new LastBlow(), new LastBlow()};
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
	/** The tick of the last KO of the practice bot (the death event can be reported twice for one blow). */
	private int lastKoTick = -1;
	int bestCombo;
	/** The combo drill (/drill): ticks left of it (counted only while fighting), or -1 when this is not a drill. */
	int drillLeft = -1;
	private boolean drillDone;
	private int drillKos;
	/** How long a drill lasts, in ticks. Public so the self-test can shorten it. */
	public static int drillTicks = 60 * 20;
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
	/** The summary of a real duel: hits landed, damage, combo, per duelist (index = who hit). */
	private final int[] recapHits = new int[2];
	private final float[] recapDamage = new float[2];
	private final int[] recapBest = new int[2];
	/** Swings of a melee weapon, melee hits that did damage, and critical hits, for each duelist. */
	private final int[] recapSwings = new int[2];
	private final int[] recapMelee = new int[2];
	private final int[] recapCrits = new int[2];
	private final int[] recapCombo = new int[2];
	private final int[] recapLast = {-1000, -1000};
	/** What each duelist had used (Stats.ITEM_USED) when the duel began, for the items listed in RecapItems. */
	private final int[][] usedAtStart = new int[2][RecapItems.COUNT];
	/** For the daily missions: foods eaten before the duel, and the health each duelist regained while fighting (-1 = not tracked). */
	private final int[] foodsAtStart = new int[2];
	private final float[] healed = new float[2];
	private final float[] lastHealth = {-1, -1};
	/** Elo before and after for both players (set when a real duel is recorded). */
	private int[] eloInfo;
	private int[] kitEloInfo;
	/** True when the duel came from the queue. */
	boolean queued;
	/** The day the duel started: its missions count for that day even if it ends after midnight. */
	private final java.time.LocalDate startedOn = Missions.today();
	/** The winner's victory effects were played. */
	private boolean celebrated;
	/** How long the ENDED phase lasts (the finale makes it longer). */
	private int endTicks = END_TICKS;
	/** Writes the duel down for /replay (null in practice duels). */
	private ReplayRecorder replay;
	/** Somebody left: there is no finale, the winner just celebrates where they stand. */
	private boolean forfeited;
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
		usedAtStart[0] = RecapItems.snapshot(a);
		usedAtStart[1] = RecapItems.snapshot(b);
		foodsAtStart[0] = RecapItems.foods(a);
		foodsAtStart[1] = RecapItems.foods(b);
	}

	// ------------------------------------------------------------------ helpers

	MinecraftServer server() {
		return server;
	}

	ServerPlayer player(int i) {
		return server.getPlayerList().getPlayer(ids[i]);
	}

	/** True once this duelist got their things back (they left, or the duel closed). */
	boolean isRestored(int i) {
		return restored[i];
	}

	/** Both agreed to a draw (/draw): the duel ends as a tie. */
	void agreeDraw() {
		if (phase == Phase.ENDED || closed) return;
		winner = -1;
		finish();
	}

	/** For the self-test: ends the duel as a tie, as if nobody had left. */
	void finishAsTieForTest() {
		winner = -1;
		finish();
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
		mutator = config.rules().mutator().forRound(Mutator.NONE);
		for (int i = 0; i < 2; i++) setupPlayer(i, arena);
		phase = Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = FIRST_COUNTDOWN;
		if (!practice) {
			replay = new ReplayRecorder(this);
			replay.roundStart(1);
		}

		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			p.sendSystemMessage(Msg.title("Duel vs " + names[1 - i]));
			p.sendSystemMessage(Msg.info("  Kit: " + config.kit() + "   Arena: " + config.arenaName()));
			p.sendSystemMessage(Msg.info("  Mode: " + config.modeName() + "   Use /leave to quit."));
			if (!config.rules().isDefault()) p.sendSystemMessage(Msg.info("  Rules: " + config.rules().describe(config.arena())));
			if (kit.stacks() == 0) {
				p.sendSystemMessage(Msg.err("  The kit '" + config.kit() + "' is EMPTY, so you start with nothing."
					+ (p.getUUID().equals(kitOwner) ? " Look inside it with /kit show " + config.kit() + ", and fix it in /kit create "
						+ config.kit() + " (then /kit save " + config.kit() + ")." : "")));
			}
		}
		announceMutator();
	}

	/** The twist of this round, told to both. */
	private void announceMutator() {
		if (mutator == Mutator.NONE) return;
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p != null) p.sendSystemMessage(Msg.info("  " + mutator.announce()));
		}
	}

	/** The mutator of the round that is being played (for the self-test). */
	Mutator roundMutator() {
		return mutator;
	}

	/** A totem takes every effect away: the twist of the round comes back. */
	void mutatorAgain(ServerPlayer p) {
		mutator.apply(p, false);
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
		resync(p);
		// The arena can be dark at night, so both players always see well.
		p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 72000, 0, false, false));
		mutator.apply(p, true);
		space.teleportToSpawn(p, arena, i);
	}

	/**
	 * Sends the whole inventory and the selected hotbar slot to the player again, so their game really shows the kit
	 * (and holds the first slot) at the start of every round, whatever the client thought it had.
	 */
	private static void resync(ServerPlayer p) {
		p.inventoryMenu.sendAllDataToRemote();
		if (p.containerMenu != p.inventoryMenu) p.containerMenu.sendAllDataToRemote();
		if (p.connection != null) p.connection.send(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
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
				if (phaseTicks >= endTicks) close();
			}
		}
		if (replay != null && phase != Phase.ENDED) replay.sample();
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
		lastHealth[0] = -1;
		lastHealth[1] = -1;
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
		if (phaseTicks == 2) {
			// after the teleport into the arena has settled
			resync(a);
			resync(b);
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
		if (drillLeft > 0 && --drillLeft == 0) {
			endDrill();
			return;
		}
		for (int i = 0; i < 2; i++) {
			float health = player(i).getHealth();
			if (lastHealth[i] >= 0 && health > lastHealth[i]) healed[i] += health - lastHealth[i];
			lastHealth[i] = health;
		}

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

	/** A hit between the two duelists. "victim" is the one who was hurt; amount is the damage it did. */
	void onHit(int victim, float amount) {
		onHit(victim, amount, null);
	}

	/** Same, with the damage source (to tell a melee hit from an arrow or an explosion, and to see a critical hit). */
	void onHit(int victim, float amount, net.minecraft.world.damagesource.DamageSource source) {
		if (practice) onPracticeHit(victim, amount, source);
		else onRealHit(victim, amount, source);
	}

	/** The duelist number {@code i} swung an arm: a swing of a weapon counts for the accuracy. */
	void onSwing(int i, ServerPlayer p) {
		if (phase != Phase.FIGHTING || i < 0 || i > 1 || !Accuracy.isMeleeHand(p.getMainHandItem())) return;
		recapSwings[i]++;
	}

	private void onRealHit(int victim, float amount, net.minecraft.world.damagesource.DamageSource source) {
		if (phase != Phase.FIGHTING || amount <= 0f) return;
		if (lastCountedTick == elapsed && lastCountedVictim == victim) return; // the same blow reported twice
		lastCountedTick = elapsed;
		lastCountedVictim = victim;
		int hitter = 1 - victim;
		recapHits[hitter]++;
		recapDamage[hitter] += amount;
		recapCombo[hitter] = elapsed - recapLast[hitter] <= COMBO_GAP ? recapCombo[hitter] + 1 : 1;
		recapLast[hitter] = elapsed;
		recapBest[hitter] = Math.max(recapBest[hitter], recapCombo[hitter]);
		if (source != null && source.getEntity() instanceof ServerPlayer attacker && source.getDirectEntity() == attacker) {
			recapMelee[hitter]++;
			if (Accuracy.isCrit(attacker)) recapCrits[hitter]++;
		}
		recapCombo[victim] = 0; // getting hit breaks your combo
		if (replay != null) replay.hit(victim, amount);
	}

	/** A hit between the player and the bot. "victim" is the duelist who was hurt; amount is the damage it did. */
	void onPracticeHit(int victim, float amount, net.minecraft.world.damagesource.DamageSource source) {
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
			if (attacker != null && source != null && source.getEntity() == attacker && source.getDirectEntity() == attacker) {
				recapMelee[1 - victim]++;
				if (Accuracy.isCrit(attacker)) recapCrits[1 - victim]++;
			}
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
		if (practice && BotDuel.isBot(p.getUUID())) {
			// Practice: the bot is knocked out and gets up again right away. Nothing is reset, you just keep going.
			if (lastKoTick == elapsed) return; // the same blow reported twice
			lastKoTick = elapsed;
			points[1 - i]++;
			drillKos++;
			Achievements.add(ids[1 - i], names[1 - i], "bot_kos", 1);
			p.setAbsorptionAmount(0f);
			p.invulnerableTime = 0;
			ServerPlayer you = player(1 - i);
			if (you != null) {
				you.sendOverlayMessage(Component.literal("KNOCKED OUT!  KO " + points[1 - i]).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			}
			printPracticeSummary("KO " + points[1 - i]);
			return;
		}
		Entity killer = source == null ? null : source.getEntity();
		String reason = killer != null && killer.getUUID().equals(ids[1 - i])
			? names[1 - i] + " defeated " + names[i] + "."
			: names[i] + " died.";
		endRound(1 - i, reason + blows[i].describe(p));
	}

	private void endRound(int winnerIdx, String reason) {
		if (phase != Phase.FIGHTING) return;
		phase = Phase.ROUND_END;
		phaseTicks = 0;
		if (winnerIdx >= 0) points[winnerIdx]++;
		if (replay != null) replay.roundEnd(winnerIdx, points[0], points[1]);
		decide();
		if (practice) printPracticeSummary("Round " + round);

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

	/** Tells the player what happened since the last summary (a round or a KO) and starts counting again. */
	private void printPracticeSummary(String label) {
		ServerPlayer you = player(0);
		if (you != null) {
			you.sendSystemMessage(Msg.info(label + ": " + roundHits + (roundHits == 1 ? " hit, " : " hits, ")
				+ oneDecimal(roundDamage) + " damage, best combo " + roundBest));
		}
		roundHits = 0;
		roundDamage = 0f;
		roundBest = 0;
		combo = 0;
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
		if (replay != null) replay.roundStart(round);
		space.begin(arena);
		mutator = config.rules().mutator().forRound(mutator);
		for (int i = 0; i < 2; i++) setupPlayer(i, arena);
		phase = Phase.STARTING;
		phaseTicks = 0;
		countdownTotal = ROUND_COUNTDOWN;
		noContact = 0;
		warnedTeleport = false;
		announceMutator();
	}

	/** /drill: this practice duel is a timed combo drill from now on (the timer runs while fighting). */
	void startDrill() {
		drillLeft = drillTicks;
	}

	/** The time of the drill is over: the numbers, the records, and the practice closes. */
	private void endDrill() {
		ServerPlayer you = player(0);
		drillDone = true;
		drillLeft = 0;
		if (you != null) {
			int seconds = Math.max(1, drillTicks / 20);
			Drill.Outcome result = Drill.submit(ids[0], names[0], hits, bestCombo, recapSwings[0], recapMelee[0]);
			you.sendSystemMessage(Msg.title("Combo drill: " + seconds + " seconds"));
			you.sendSystemMessage(Msg.info("  Hits: " + hits + "  (" + oneDecimal(hits / (float) seconds) + " per second)"));
			you.sendSystemMessage(Msg.info("  Best combo: " + bestCombo + (hitsTaken > 0 ? "   Hits taken: " + hitsTaken : "")));
			you.sendSystemMessage(Msg.info("  Damage: " + oneDecimal(damage) + "   Knockouts: " + drillKos));
			String aim = Accuracy.describe(recapSwings[0], recapMelee[0], recapCrits[0]);
			if (!aim.isEmpty()) you.sendSystemMessage(Msg.info("  Aim: " + aim));
			if (result.any()) {
				StringBuilder sb = new StringBuilder("  NEW RECORD: ");
				if (result.combo()) sb.append("best combo ").append(bestCombo).append(" (was ").append(result.wasCombo()).append(") ");
				if (result.hits()) sb.append("most hits ").append(hits).append(" (was ").append(result.wasHits()).append(") ");
				if (result.accuracy()) sb.append("best accuracy ").append(result.accuracyNow()).append("% (was ").append(result.wasAccuracy()).append("%) ");
				you.sendSystemMessage(Msg.ok(sb.toString().trim()));
			}
			Drill.Rec rec = Drill.get(ids[0]);
			you.sendSystemMessage(Msg.info("  Your records: best combo " + rec.combo + ", most hits " + rec.hits + ", best accuracy "
				+ (rec.accuracy > 0 ? rec.accuracy + "%" : "- (needs " + Drill.MIN_SWINGS + " swings)") + ", " + rec.drills + (rec.drills == 1 ? " drill" : " drills") + " done."));
		}
		forfeit(1);
		closeNow();
	}

	/** Someone left, disconnected, or typed /leave. */
	void forfeit(int idx) {
		if (phase == Phase.ENDED || closed) return;
		PvpDuelsMod.LOGGER.info("Duel {} vs {}: {} left (round {}, phase {}, practice {})", names[0], names[1], names[idx], round, phase, practice);
		// Always say why the duel is ending, so nobody is left guessing.
		ServerPlayer other = player(1 - idx);
		if (other != null && !drillDone) {
			other.sendSystemMessage(Msg.info(BotDuel.isBot(ids[idx])
				? names[idx] + " (the bot) is no longer in the game, so the practice is over."
				: names[idx] + " left the duel."));
		}
		if (config.unlimited()) {
			winner = points[0] == points[1] ? -1 : (points[0] > points[1] ? 0 : 1);
		} else {
			winner = 1 - idx;
		}
		forfeited = true;
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
			if (drillLeft >= 0) {
				int left = (drillLeft + 19) / 20;
				out.add(String.format(java.util.Locale.ROOT, "Drill: %d:%02d", left / 60, left % 60));
				if (recapSwings[0] > 0) out.add("Accuracy: " + Drill.percent(recapSwings[0], recapMelee[0]) + "%");
			}
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
		Seasons.record(ids[0], names[0], eloInfo[1] - eloInfo[0], ids[1], names[1], eloInfo[3] - eloInfo[2]);
		Leaderboards.markDirty();
		kitEloInfo = StatsStore.recordKitDuel(config.kit(), ids[0], names[0], ids[1], names[1], winner, points[0], points[1]);
		com.pvpduels.stats.HeadToHead.record(ids[0], names[0], ids[1], names[1], winner, points[0], points[1], config.kit());
		recordAchievements();
	}

	/** Feeds the achievements with what happened in this duel (the numbers of the stats are already updated). */
	private void recordAchievements() {
		if (practice) return;
		for (int i = 0; i < 2; i++) {
			UUID id = ids[i];
			String name = names[i];
			StatsStore.Stats s = StatsStore.get(id);
			boolean won = winner == i;
			Achievements.max(id, name, "duels", s.duels);
			Achievements.max(id, name, "wins", s.wins);
			Achievements.max(id, name, "streak", s.bestStreak);
			Achievements.max(id, name, "elo", s.elo);
			Achievements.max(id, name, "combo", recapBest[i]);
			Achievements.distinct(id, name, "arena_kinds", config.arena().name());
			if (won) {
				Achievements.distinct(id, name, "kit_wins", config.kit());
				if (queued) Achievements.add(id, name, "queue_wins", 1);
				if (points[1 - i] == 0 && points[i] >= 2) Achievements.add(id, name, "flawless", 1);
			}
			ServerPlayer p = player(i);
			Missions.Facts facts = new Missions.Facts();
			facts.day = startedOn;
			facts.won = won;
			facts.lost = winner == 1 - i;
			facts.kit = config.kit();
			facts.combo = recapBest[i];
			facts.hits = recapHits[i];
			facts.roundsWon = points[i];
			facts.hearts = healed[i] / 2.0;
			if (p != null) {
				int[] now = RecapItems.snapshot(p);
				String[] counters = {"totems", "pearls", "apples"};
				for (int g = 0; g < counters.length; g++) {
					int used = now[g] - usedAtStart[i][g];
					if (used > 0) Achievements.add(id, name, counters[g], used);
				}
				facts.pearls = now[1] - usedAtStart[i][1];
				facts.apples = now[2] - usedAtStart[i][2];
				facts.potions = now[3] - usedAtStart[i][3];
				facts.foods = RecapItems.foods(p) - foodsAtStart[i];
			}
			Missions.record(id, name, facts);
		}
	}

	private void finish() {
		if (traceEnd) PvpDuelsMod.LOGGER.info("Duel {} vs {} is finishing (round {}, phase {}, practice {})", names[0], names[1], round, phase, practice,
			new Throwable("who finished the duel"));
		recordStats();
		if (replay != null) {
			if (winner != -2) ReplayStore.add(replay.finish(winner, points));
			replay = null;
		}
		if (!celebrated && !practice) {
			celebrated = true;
			// A real 1v1 ends with the finale (camera, doubles, effects); a tie with two domains makes them clash. Without a
			// finale the winner just celebrates where they stand.
			int finale = forfeited ? 0 : Cinema.begin(this, winner);
			if (finale > 0) endTicks = Math.max(END_TICKS, finale);
			else if (winner >= 0 && player(winner) != null) Victory.play(player(winner), player(1 - winner));
		}
		DuelManager.onFinished(this);
		phase = Phase.ENDED;
		phaseTicks = 0;
		finishing = true;
		for (int i = 0; i < 2; i++) {
			ServerPlayer p = player(i);
			if (p == null || restored[i]) continue;
			if (drillDone) {
				if (i == 0) p.sendSystemMessage(Msg.info("Type /drill to try again."));
				continue;
			}
			Component title;
			if (winner == -1) title = Component.literal("DRAW").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
			else if (winner == i) title = Component.literal("VICTORY").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
			else title = Component.literal("DEFEAT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			sendTitle(p, title, Component.literal("Final score " + score(i)).withStyle(ChatFormatting.WHITE), 5, 80, 15);
			p.sendSystemMessage(Msg.title("Duel finished: " + names[0] + " " + points[0] + " - " + points[1] + " " + names[1]));
			if (!practice) sendRecap(p);
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
			if (kitEloInfo != null) {
				int before = kitEloInfo[2 * i];
				int after = kitEloInfo[2 * i + 1];
				p.sendSystemMessage(Msg.info("Elo with kit " + config.kit() + ": " + before + " -> " + after + " (" + (after >= before ? "+" : "") + (after - before)
					+ ")  /top kit " + config.kit()));
			}
			com.pvpduels.stats.HeadToHead.View h2h = com.pvpduels.stats.HeadToHead.view(ids[i], ids[1 - i]);
			if (h2h.total() > 0) {
				p.sendSystemMessage(Msg.info("Against " + names[1 - i] + ": you " + h2h.wins() + " - " + h2h.losses() + " " + names[1 - i]
					+ (h2h.draws() > 0 ? " (" + h2h.draws() + (h2h.draws() == 1 ? " tie)" : " ties)") : "") + "  /h2h " + names[1 - i]));
			}
			p.sendSystemMessage(Msg.info("Rematch? ").copy().append(Component.literal("[REMATCH]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/again"))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Ask for a rematch with the same settings"))))));
		}
	}

	/** How long it lasted, then one line per duelist: hits, damage, best combo and the items they used. */
	private void sendRecap(ServerPlayer to) {
		int seconds = elapsed / 20;
		to.sendSystemMessage(Msg.info("Summary (" + seconds / 60 + ":" + String.format("%02d", seconds % 60) + " long, "
			+ round + (round == 1 ? " round)" : " rounds)")));
		for (int i = 0; i < 2; i++) {
			ServerPlayer who = player(i);
			String used = who == null ? "" : RecapItems.describe(usedAtStart[i], RecapItems.snapshot(who));
			String aim = Accuracy.describe(recapSwings[i], recapMelee[i], recapCrits[i]);
			to.sendSystemMessage(Component.literal("  " + names[i] + ": ").withStyle(ChatFormatting.WHITE)
				.append(Component.literal(recapHits[i] + (recapHits[i] == 1 ? " hit, " : " hits, ") + oneDecimal(recapDamage[i])
					+ " damage, best combo " + recapBest[i] + (aim.isEmpty() ? "" : ", " + aim) + (used.isEmpty() ? "" : " | " + used)).withStyle(ChatFormatting.GRAY)));
		}
	}

	/** Gives a player their inventory and position back (when they /leave, or when the duel closes). */
	void restore(int i) {
		if (restored[i]) return;
		restored[i] = true;
		DuelManager.onLeft(ids[i]);
		ServerPlayer p = player(i);
		if (p != null) {
			Cinema.release(p);
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
		if (traceEnd) PvpDuelsMod.LOGGER.info("Duel {} vs {} closed right now", names[0], names[1], new Throwable("who closed the duel"));
		close();
	}

	private void close() {
		if (closed) return;
		closed = true;
		Cinema.stop(this);
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
		Cinema.stop(this);
		restore(0);
		restore(1);
		DuelManager.onClosed(this);
	}
}
