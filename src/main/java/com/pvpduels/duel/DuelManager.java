package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import com.pvpduels.workshop.Workshop;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Keeps track of invitations, running duels and the arena slots, and hooks the game events duels need. */
public final class DuelManager {
	public static final int MAX_DUELS = 3;
	private static final int INVITE_TICKS = 60 * 20;

	private static final Duel[] SLOTS = new Duel[MAX_DUELS];
	private static final Map<UUID, Duel> BY_PLAYER = new HashMap<>();
	/** The 2v2 duels use the same arena slots as the 1v1 ones. */
	private static final TeamDuel[] TEAM_SLOTS = new TeamDuel[MAX_DUELS];
	/** Arena slots a replay is being played in (see {@link ReplayPlayer}): no duel can use them meanwhile. */
	private static final boolean[] REPLAY_SLOTS = new boolean[MAX_DUELS];
	private static final Map<UUID, TeamDuel> TEAM_BY_PLAYER = new HashMap<>();
	private static final List<Invite> INVITES = new ArrayList<>();
	private static final Map<UUID, Last> LAST = new HashMap<>();
	private static int tickCounter;

	record Invite(UUID from, String fromName, UUID to, String toName, UUID kitOwner, DuelConfig config, boolean rematch, int createdTick) {}

	record Last(UUID opponent, String opponentName, UUID kitOwner, DuelConfig config) {}

	private DuelManager() {}

	// ------------------------------------------------------------------ setup

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(DuelManager::onTick);

		ServerLivingEntityEvents.ALLOW_DAMAGE.register(DuelManager::allowDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(DuelManager::afterDamage);
		ServerLivingEntityEvents.ALLOW_DEATH.register(DuelManager::allowDeath);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
		ServerLifecycleEvents.SERVER_STOPPING.register(DuelManager::onStopping);

		ArenaRules.register();
		Lobby.register();
		LobbyGuard.register();
	}

	// ------------------------------------------------------------------ queries

	public static boolean isInDuel(UUID id) {
		return BY_PLAYER.containsKey(id) || TEAM_BY_PLAYER.containsKey(id);
	}

	/** True while the player can't take a new duel: they are in one that has not finished yet. */
	public static boolean isBusy(UUID id) {
		if (Workshop.isIn(id) || Spectators.isIn(id) || com.pvpduels.arena.ArenaStudio.isIn(id) || ReplayPlayer.isIn(id) || Cinema.isTryingOut(id)) return true;
		Duel d = BY_PLAYER.get(id);
		if (d != null && d.phase != Duel.Phase.ENDED) return true;
		TeamDuel t = TEAM_BY_PLAYER.get(id);
		return t != null && t.phase != Duel.Phase.ENDED;
	}

	public static Duel duelOf(UUID id) {
		return BY_PLAYER.get(id);
	}

	public static TeamDuel teamDuelOf(UUID id) {
		return TEAM_BY_PLAYER.get(id);
	}

	/** The settings of the duel (1v1 or 2v2) this player is in, or null. */
	static DuelConfig configOf(UUID id) {
		Duel d = BY_PLAYER.get(id);
		if (d != null) return d.config;
		TeamDuel t = TEAM_BY_PLAYER.get(id);
		return t == null ? null : t.config;
	}
	public static int activeDuels() {
		int n = 0;
		for (Duel d : SLOTS) if (d != null) n++;
		for (TeamDuel t : TEAM_SLOTS) if (t != null) n++;
		for (boolean r : REPLAY_SLOTS) if (r) n++;
		return n;
	}

	/** Takes a free arena slot for a replay. Returns its number, or -1 when all of them are busy. */
	static int reserveSlot() {
		for (int i = 0; i < SLOTS.length; i++) {
			if (SLOTS[i] == null && TEAM_SLOTS[i] == null && !REPLAY_SLOTS[i]) {
				REPLAY_SLOTS[i] = true;
				return i;
			}
		}
		return -1;
	}

	static void releaseSlot(int slot) {
		if (slot >= 0 && slot < REPLAY_SLOTS.length) REPLAY_SLOTS[slot] = false;
	}

	public static List<String> pendingFrom(UUID to) {
		List<String> names = new ArrayList<>();
		for (Invite i : INVITES) if (i.to().equals(to)) names.add(i.fromName());
		return names;
	}

	// ------------------------------------------------------------------ events

	private static void onTick(MinecraftServer server) {
		tickCounter++;
		for (Duel d : SLOTS.clone()) {
			if (d != null) d.tick();
		}
		for (TeamDuel t : TEAM_SLOTS.clone()) {
			if (t != null) t.tick();
		}
		if (tickCounter % 20 == 0) expireInvites(server);
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim)) return true;
		TeamDuel team = TEAM_BY_PLAYER.get(victim.getUUID());
		if (team != null) return team.allowDamage(victim, source, amount);
		Duel duel = BY_PLAYER.get(victim.getUUID());
		if (duel == null) return true;
		if (!duel.canTakeDamage()) return false;
		if (!duel.config.rules().fallDamage() && source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) return false;

		int vi = duel.indexOf(victim.getUUID());
		if (vi >= 0) duel.blows[vi].note(victim, amount);
		Entity attacker = source.getEntity();
		if (attacker != null && vi >= 0 && attacker.getUUID().equals(duel.ids[1 - vi])) duel.onContact();
		return true;
	}

	/** Every hit between the two duelists is counted: practice shows it live, real duels sum it up at the end. */
	private static void afterDamage(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked) {
		if (blocked || !(entity instanceof ServerPlayer victim)) return;
		countHit(victim, source, damageTaken);
	}

	private static void countHit(ServerPlayer victim, DamageSource source, float amount) {
		Duel duel = BY_PLAYER.get(victim.getUUID());
		if (duel == null || source == null || source.getEntity() == null) return;
		int vi = duel.indexOf(victim.getUUID());
		if (vi >= 0 && source.getEntity().getUUID().equals(duel.ids[1 - vi])) duel.onHit(vi, amount);
	}

	/** Duelists never really die: the round ends instead. */
	private static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer p)) return true;
		TeamDuel team = TEAM_BY_PLAYER.get(p.getUUID());
		if (team != null) {
			// a totem in hand saves the duelist first, like in vanilla (Fabric asks us before the game looks for it)
			if (team.phase == Duel.Phase.FIGHTING && Totems.tryPop(p, source)) return false;
			team.onDeath(p, source);
			return false;
		}
		Duel duel = BY_PLAYER.get(p.getUUID());
		if (duel == null) return true;
		if (duel.canTakeDamage() && Totems.tryPop(p, source)) {
			countHit(p, source, Math.min(amount, p.getMaxHealth()));
			return false;
		}
		countHit(p, source, Math.min(amount, p.getMaxHealth()));
		duel.onDeath(p, source);
		return false;
	}

	private static void onDisconnect(ServerPlayer p) {
		INVITES.removeIf(i -> i.from().equals(p.getUUID()) || i.to().equals(p.getUUID()));
		TeamDuel team = TEAM_BY_PLAYER.get(p.getUUID());
		if (team != null) team.forfeit(team.indexOf(p.getUUID())); // the backup stays on disk, like in a 1v1
		Duel duel = BY_PLAYER.get(p.getUUID());
		if (duel != null) {
			int i = duel.indexOf(p.getUUID());
			duel.forfeit(i);
			// The backup stays on disk: they get everything back the next time they join.
		}
	}

	private static void onJoin(MinecraftServer server, ServerPlayer p) {
		if (!BY_PLAYER.containsKey(p.getUUID()) && PlayerBackup.exists(p.getUUID())) {
			if (PlayerBackup.restore(server, p)) {
				p.sendSystemMessage(Msg.ok("Your inventory and position from before the duel or workshop were restored."));
			}
		}
	}

	private static void onStopping(MinecraftServer server) {
		for (Duel d : SLOTS.clone()) {
			if (d != null) d.abort();
		}
		for (TeamDuel t : TEAM_SLOTS.clone()) {
			if (t != null) t.abort();
		}
	}

	static void onClosed(Duel d) {
		for (int i = 0; i < SLOTS.length; i++) {
			if (SLOTS[i] == d) SLOTS[i] = null;
		}
		BY_PLAYER.remove(d.ids[0], d);
		BY_PLAYER.remove(d.ids[1], d);
		BotDuel.release(d);
		TestDummy.duelEnded(d);
		Draws.forget(d);
	}

	/** The duel is decided: remember it so /again works right away. */
	static void onFinished(Duel d) {
		if (d.practice) return; // /again against a bot makes no sense: use /botduel
		LAST.put(d.ids[0], new Last(d.ids[1], d.names[1], d.kitOwner, d.config));
		LAST.put(d.ids[1], new Last(d.ids[0], d.names[0], d.kitOwner, d.config));
	}

	/** The player got their inventory back, so they are not part of the duel any more. */
	static void onLeft(UUID id) {
		BY_PLAYER.remove(id);
		TEAM_BY_PLAYER.remove(id);
	}

	static void onTeamClosed(TeamDuel t) {
		for (int i = 0; i < TEAM_SLOTS.length; i++) {
			if (TEAM_SLOTS[i] == t) TEAM_SLOTS[i] = null;
		}
		for (UUID id : t.ids) TEAM_BY_PLAYER.remove(id, t);
		Draws.forget(t);
	}

	/** A 2v2 is decided. There is no /again for it. */
	static void onTeamFinished(TeamDuel t) {}

	private static void expireInvites(MinecraftServer server) {
		Iterator<Invite> it = INVITES.iterator();
		while (it.hasNext()) {
			Invite i = it.next();
			if (tickCounter - i.createdTick() >= INVITE_TICKS) {
				it.remove();
				ServerPlayer from = server.getPlayerList().getPlayer(i.from());
				if (from != null) from.sendSystemMessage(Msg.info("Your duel request to " + i.toName() + " expired."));
				ServerPlayer to = server.getPlayerList().getPlayer(i.to());
				if (to != null) to.sendSystemMessage(Msg.info("The duel request from " + i.fromName() + " expired."));
			}
		}
	}

	// ------------------------------------------------------------------ invitations

	private static Component button(String label, ChatFormatting color, String command, String hover) {
		return Msg.button(label, color, command, hover);
	}

	/** Withdraws every duel request this player sent or got. Returns how many there were. */
	public static int cancelInvitesOf(UUID id) {
		int before = INVITES.size();
		INVITES.removeIf(i -> i.from().equals(id) || i.to().equals(id));
		return before - INVITES.size();
	}

	/** /duels: the duels that are running right now, each with a [WATCH] button. */
	public static void listDuels(ServerPlayer to) {
		List<Duel> running = new ArrayList<>();
		for (Duel d : SLOTS) if (d != null && !d.practice && !d.closed && d.phase != Duel.Phase.ENDED) running.add(d);
		List<TeamDuel> teams = new ArrayList<>();
		for (TeamDuel t : TEAM_SLOTS) if (t != null && !t.closed && t.phase != Duel.Phase.ENDED) teams.add(t);
		if (running.isEmpty() && teams.isEmpty()) {
			to.sendSystemMessage(Msg.info("Nobody is dueling right now. Challenge somebody with /duel <player>."));
			return;
		}
		to.sendSystemMessage(Msg.title("Duels right now (" + (running.size() + teams.size()) + ")"));
		for (TeamDuel t : teams) {
			to.sendSystemMessage(Component.literal("  2v2: " + t.teamName(0) + " " + t.points[0] + " - " + t.points[1] + " " + t.teamName(1)).withStyle(ChatFormatting.WHITE)
				.append(Component.literal("   " + t.config.kit() + ", " + t.config.arenaName() + "  (2v2 can't be watched yet)").withStyle(ChatFormatting.GRAY)));
		}
		for (Duel d : running) {
			Component line = Component.literal("  " + d.names[0] + " " + d.points[0] + " - " + d.points[1] + " " + d.names[1]).withStyle(ChatFormatting.WHITE)
				.append(Component.literal("   " + d.config.kit() + ", " + d.config.arenaName() + "  ").withStyle(ChatFormatting.GRAY))
				.append(button("[WATCH]", ChatFormatting.GREEN, "/spectate " + d.names[0], "Watch this duel"));
			to.sendSystemMessage(line);
		}
	}

	/** Why this player can't take part in a new duel right now, or null. */
	public static String busyReason(ServerPlayer p) {
		if (BY_PLAYER.containsKey(p.getUUID()) || TEAM_BY_PLAYER.containsKey(p.getUUID())) return "is already in a duel";
		return null;
	}

	/** The rules about parties: a party only duels another full party (2v2), and the four must be free. Returns an error text or null. */
	private static String partyProblem(MinecraftServer server, ServerPlayer from, ServerPlayer to) {
		boolean f = Party.inParty(from.getUUID());
		boolean t = Party.inParty(to.getUUID());
		if (!f && !t) return null;
		String toName = to.getName().getString();
		if (f && !t) return "You are in a party, so you can only duel another party (2v2). " + toName + " is not in one. /party leave if you want a 1v1.";
		if (!f) return toName + " is in a party, so they only play 2v2. Make a party first with /party invite <friend>.";
		if (from.getUUID().equals(Party.partnerOf(to.getUUID()))) return "That is your own partner!";
		for (UUID id : new UUID[] {Party.partnerOf(from.getUUID()), Party.partnerOf(to.getUUID())}) {
			ServerPlayer other = id == null ? null : server.getPlayerList().getPlayer(id);
			if (other == null) return "A partner is not online.";
			if (isBusy(id)) return other.getName().getString() + " is busy right now.";
		}
		return null;
	}

	public static void sendInvite(ServerPlayer from, ServerPlayer to, UUID kitOwner, DuelConfig config, boolean rematch) {
		MinecraftServer server = from.level().getServer();
		if (from.getUUID().equals(to.getUUID())) {
			from.sendSystemMessage(Msg.err("You can't duel yourself."));
			return;
		}
		if (isBusy(from.getUUID())) {
			from.sendSystemMessage(Msg.err("You are busy: finish your duel or leave the kit workshop (/leave) first."));
			return;
		}
		if (isBusy(to.getUUID())) {
			from.sendSystemMessage(Msg.err(to.getName().getString() + " is busy right now (in a duel or the kit workshop)."));
			return;
		}
		String partyProblem = partyProblem(server, from, to);
		if (partyProblem != null) {
			from.sendSystemMessage(Msg.err(partyProblem));
			return;
		}
		boolean team = Party.inParty(from.getUUID());

		// A rematch request that crosses another one: just start the duel.
		if (rematch) {
			for (Invite other : INVITES) {
				if (other.rematch() && other.from().equals(to.getUUID()) && other.to().equals(from.getUUID())) {
					INVITES.remove(other);
					start(server, other);
					return;
				}
			}
		}

		INVITES.removeIf(i -> i.from().equals(from.getUUID()) && i.to().equals(to.getUUID()));
		String fromName = from.getName().getString();
		String toName = to.getName().getString();
		Invite invite = new Invite(from.getUUID(), fromName, to.getUUID(), toName, kitOwner, config, rematch, tickCounter);
		INVITES.add(invite);

		to.sendSystemMessage(Component.literal(""));
		to.sendSystemMessage(Component.literal(fromName).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(team ? " and their partner challenged you and your partner to a 2v2 duel!"
				: rematch ? " wants a rematch!" : " challenged you to a duel!").withStyle(ChatFormatting.YELLOW)));
		to.sendSystemMessage(Msg.info("  Kit: " + config.kit() + "   Arena: " + config.arenaName()));
		to.sendSystemMessage(Msg.info("  Mode: " + config.modeName()));
		if (!config.rules().isDefault()) to.sendSystemMessage(Msg.info("  Rules: " + config.rules().describe(config.arena())));
		MutableComponent buttons = Component.literal("  ")
			.append(button("[ACCEPT]", ChatFormatting.GREEN, "/accept " + fromName, "Click to accept the duel"))
			.append(Component.literal("   "))
			.append(button("[DENY]", ChatFormatting.RED, "/deny " + fromName, "Click to deny the duel"))
			.append(Component.literal("   "))
			.append(button("[VIEW KIT]", ChatFormatting.AQUA, "/duel viewkit " + fromName, "Look at the kit before you decide"))
			.append(Msg.info("   (expires in 60 seconds)"));
		to.sendSystemMessage(buttons);

		from.sendSystemMessage(Msg.ok("Duel request sent to " + toName + ". It expires in 60 seconds."));

		// A test dummy (see /test_dummy) accepts everything right away.
		if (TestDummy.accepts(to.getUUID())) {
			from.sendSystemMessage(Msg.info(toName + " (test dummy) accepted."));
			accept(to, fromName);
		}
	}

	private static Optional<Invite> findInvite(UUID to, String fromName) {
		Invite best = null;
		for (Invite i : INVITES) {
			if (!i.to().equals(to)) continue;
			if (fromName != null && !i.fromName().equalsIgnoreCase(fromName)) continue;
			if (best == null || i.createdTick() >= best.createdTick()) best = i;
		}
		return Optional.ofNullable(best);
	}

	public static void accept(ServerPlayer to, String fromName) {
		Optional<Invite> found = findInvite(to.getUUID(), fromName);
		if (found.isEmpty()) {
			to.sendSystemMessage(Msg.err(fromName == null ? "You have no pending duel requests."
				: "You have no duel request from " + fromName + "."));
			return;
		}
		Invite invite = found.get();
		INVITES.remove(invite);
		start(to.level().getServer(), invite);
	}

	/** The [VIEW KIT] button of a request: opens the kit of the challenger as a look-only chest. The request stays. */
	public static void viewKit(ServerPlayer to, String fromName) {
		Optional<Invite> found = findInvite(to.getUUID(), fromName);
		if (found.isEmpty()) {
			to.sendSystemMessage(Msg.err(fromName == null ? "You have no pending duel requests." : "You have no duel request from " + fromName + "."));
			return;
		}
		Invite invite = found.get();
		try {
			Optional<KitStore.Kit> read = KitStore.read(to.level().getServer(), invite.kitOwner(), invite.config().kit());
			if (read.isEmpty()) {
				to.sendSystemMessage(Msg.err("The kit '" + invite.config().kit() + "' no longer exists."));
				return;
			}
			KitView.open(to, invite.config().kit(), read.get().slots());
		} catch (IOException | RuntimeException e) {
			to.sendSystemMessage(Msg.err("Could not read the kit: " + e.getMessage()));
		}
	}

	public static void deny(ServerPlayer to, String fromName) {
		Optional<Invite> found = findInvite(to.getUUID(), fromName);
		if (found.isEmpty()) {
			to.sendSystemMessage(Msg.err(fromName == null ? "You have no pending duel requests."
				: "You have no duel request from " + fromName + "."));
			return;
		}
		Invite invite = found.get();
		INVITES.remove(invite);
		to.sendSystemMessage(Msg.info("You denied the duel request from " + invite.fromName() + "."));
		ServerPlayer from = to.level().getServer().getPlayerList().getPlayer(invite.from());
		if (from != null) from.sendSystemMessage(Msg.err(invite.toName() + " denied your duel request."));
	}

	// ------------------------------------------------------------------ starting / leaving / again

	private static void start(MinecraftServer server, Invite invite) {
		start(server, invite, null);
	}

	/** Starts a duel between two players the queue matched, right away. The kit is already loaded. */
	static boolean startMatched(MinecraftServer server, ServerPlayer a, ServerPlayer b, UUID kitOwner, DuelConfig config, KitStore.Kit kit) {
		Invite invite = new Invite(a.getUUID(), a.getName().getString(), b.getUUID(), b.getName().getString(), kitOwner, config, false, tickCounter);
		start(server, invite, kit);
		Duel started = BY_PLAYER.get(a.getUUID());
		if (started != null) started.queued = true;
		return isInDuel(a.getUUID()) && isInDuel(b.getUUID());
	}

	private static void start(MinecraftServer server, Invite invite, KitStore.Kit preloaded) {
		ServerPlayer a = server.getPlayerList().getPlayer(invite.from());
		ServerPlayer b = server.getPlayerList().getPlayer(invite.to());
		if (a == null || b == null) {
			ServerPlayer who = a != null ? a : b;
			if (who != null) who.sendSystemMessage(Msg.err("The other player is no longer online."));
			return;
		}
		String problem = null;
		// Two full parties make a 2v2: team 0 is a and their partner, team 1 is b and theirs.
		boolean team = Party.isFull(a.getUUID()) && Party.isFull(b.getUUID());
		ServerPlayer a2 = team ? server.getPlayerList().getPlayer(Party.partnerOf(a.getUUID())) : null;
		ServerPlayer b2 = team ? server.getPlayerList().getPlayer(Party.partnerOf(b.getUUID())) : null;
		if (Party.inParty(a.getUUID()) != Party.inParty(b.getUUID())) problem = "One of you is in a party and the other one is not, so there is no duel. Ask again.";
		else if (team && (a2 == null || b2 == null)) problem = "A partner is no longer online.";
		ServerPlayer[] group = team && problem == null ? new ServerPlayer[] {a, a2, b, b2} : new ServerPlayer[] {a, b};
		if (problem == null) {
			for (ServerPlayer p : group) {
				if (isBusy(p.getUUID())) {
					problem = p.getName().getString() + " is busy right now (in a duel or the kit workshop).";
					break;
				}
			}
		}
		if (problem == null) {
			// Somebody whose last duel just ended but who has not been put back yet: do it now.
			for (ServerPlayer p : group) {
				Duel old = BY_PLAYER.get(p.getUUID());
				if (old != null) old.restore(old.indexOf(p.getUUID()));
				TeamDuel oldTeam = TEAM_BY_PLAYER.get(p.getUUID());
				if (oldTeam != null) oldTeam.restore(oldTeam.indexOf(p.getUUID()));
			}
		}

		int slot = -1;
		for (int i = 0; i < SLOTS.length && slot < 0; i++) if (SLOTS[i] == null && TEAM_SLOTS[i] == null && !REPLAY_SLOTS[i]) slot = i;
		if (problem == null && slot < 0) problem = "All " + MAX_DUELS + " arenas are busy right now. Try again in a minute.";

		ServerLevel arena = slot >= 0 ? Space.of(invite.config(), slot).level(server) : null;
		if (problem == null && arena == null) {
			problem = "The duel world is not loaded. Make sure the mod is up to date, then restart the game.";
		}

		if (problem == null && invite.config().arena() == ArenaKind.CUSTOM && !com.pvpduels.arena.ArenaStore.exists(invite.config().custom())) {
			problem = "The custom arena '" + invite.config().customName() + "' no longer exists.";
		}

		KitStore.Kit kit = preloaded;
		if (problem == null && kit == null) {
			try {
				Optional<KitStore.Kit> read = KitStore.read(server, invite.kitOwner(), invite.config().kit());
				if (read.isEmpty()) problem = "The kit '" + invite.config().kit() + "' no longer exists.";
				else kit = read.get();
			} catch (IOException | RuntimeException e) {
				problem = "Could not read the kit: " + e.getMessage();
			}
		}
		if (problem != null) {
			for (ServerPlayer p : group) p.sendSystemMessage(Msg.err(problem));
			return;
		}

		// Any other pending requests of these players are no longer valid.
		for (ServerPlayer p : group) {
			INVITES.removeIf(i -> i.from().equals(p.getUUID()) || i.to().equals(p.getUUID()));
			Queue.leave(p, false);
		}

		if (group.length == 4) {
			TeamDuel teamDuel = new TeamDuel(server, slot, invite.config(), invite.kitOwner(), kit, group);
			TEAM_SLOTS[slot] = teamDuel;
			for (ServerPlayer p : group) TEAM_BY_PLAYER.put(p.getUUID(), teamDuel);
			try {
				teamDuel.begin(arena);
				PvpDuelsMod.LOGGER.info("2v2 started: {} vs {} ({}, slot {})", teamDuel.teamName(0), teamDuel.teamName(1), invite.config(), slot);
			} catch (IOException | RuntimeException e) {
				PvpDuelsMod.LOGGER.error("Could not start the 2v2", e);
				TEAM_SLOTS[slot] = null;
				for (ServerPlayer p : group) {
					TEAM_BY_PLAYER.remove(p.getUUID());
					PlayerBackup.restore(server, p);
					p.sendSystemMessage(Msg.err("Could not start the duel: " + e.getMessage()));
				}
			}
			return;
		}

		Duel duel = new Duel(server, slot, invite.config(), invite.kitOwner(), kit, a, b);
		SLOTS[slot] = duel;
		BY_PLAYER.put(a.getUUID(), duel);
		BY_PLAYER.put(b.getUUID(), duel);
		try {
			duel.begin(arena);
			TestDummy.duelStarted(duel);
			PvpDuelsMod.LOGGER.info("Duel started: {} vs {} ({}, slot {})", duel.names[0], duel.names[1], invite.config(), slot);
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.error("Could not start the duel", e);
			SLOTS[slot] = null;
			BY_PLAYER.remove(a.getUUID());
			BY_PLAYER.remove(b.getUUID());
			PlayerBackup.restore(server, a);
			PlayerBackup.restore(server, b);
			a.sendSystemMessage(Msg.err("Could not start the duel: " + e.getMessage()));
			b.sendSystemMessage(Msg.err("Could not start the duel: " + e.getMessage()));
		}
	}

	public static void leave(ServerPlayer p) {
		TeamDuel team = TEAM_BY_PLAYER.get(p.getUUID());
		if (team != null) {
			int i = team.indexOf(p.getUUID());
			if (team.phase == Duel.Phase.ENDED) {
				team.restore(i);
			} else {
				team.forfeit(i);
				team.restore(i);
			}
			return;
		}
		Duel duel = BY_PLAYER.get(p.getUUID());
		if (duel == null) {
			p.sendSystemMessage(Msg.err("You are not in a duel."));
			return;
		}
		int i = duel.indexOf(p.getUUID());
		if (duel.phase == Duel.Phase.ENDED) {
			// The duel is already decided: just leave now instead of waiting.
			duel.restore(i);
		} else {
			duel.forfeit(i);
			duel.restore(i);
		}
		if (duel.practice) duel.closeNow();
	}

	/** Starts a /botduel right away (the bot "accepts" by existing). The caller checks afterwards whether it worked. */
	static void startPractice(ServerPlayer player, ServerPlayer bot, DuelConfig config) {
		Invite invite = new Invite(player.getUUID(), player.getName().getString(), bot.getUUID(), bot.getName().getString(),
			player.getUUID(), config, false, tickCounter);
		start(player.level().getServer(), invite);
	}

	public static void again(ServerPlayer p) {
		Last last = LAST.get(p.getUUID());
		if (last == null) {
			p.sendSystemMessage(Msg.err("You have no previous duel to repeat."));
			return;
		}
		if (isBusy(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You are busy: finish your duel or leave the kit workshop first."));
			return;
		}
		ServerPlayer opponent = p.level().getServer().getPlayerList().getPlayer(last.opponent());
		if (opponent == null) {
			p.sendSystemMessage(Msg.err(last.opponentName() + " is not online."));
			return;
		}
		sendInvite(p, opponent, last.kitOwner(), last.config(), true);
	}
}
