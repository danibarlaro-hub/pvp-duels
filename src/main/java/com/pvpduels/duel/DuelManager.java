package com.pvpduels.duel;

import com.pvpduels.PvpDuelsMod;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import com.pvpduels.workshop.Workshop;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
		ServerLivingEntityEvents.ALLOW_DEATH.register(DuelManager::allowDeath);
		ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> allowDeath(player, source, amount));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
		ServerLifecycleEvents.SERVER_STOPPING.register(DuelManager::onStopping);

		ArenaRules.register();
	}

	// ------------------------------------------------------------------ queries

	public static boolean isInDuel(UUID id) {
		return BY_PLAYER.containsKey(id);
	}

	/** True while the player can't take a new duel: they are in one that has not finished yet. */
	public static boolean isBusy(UUID id) {
		if (Workshop.isIn(id)) return true;
		Duel d = BY_PLAYER.get(id);
		return d != null && d.phase != Duel.Phase.ENDED;
	}

	public static Duel duelOf(UUID id) {
		return BY_PLAYER.get(id);
	}

	public static int activeDuels() {
		int n = 0;
		for (Duel d : SLOTS) if (d != null) n++;
		return n;
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
		if (tickCounter % 20 == 0) expireInvites(server);
	}

	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim)) return true;
		Duel duel = BY_PLAYER.get(victim.getUUID());
		if (duel == null) return true;
		if (!duel.canTakeDamage()) return false;

		Entity attacker = source.getEntity();
		if (attacker != null) {
			int vi = duel.indexOf(victim.getUUID());
			if (vi >= 0 && attacker.getUUID().equals(duel.ids[1 - vi])) duel.onContact();
		}
		return true;
	}

	/** Duelists never really die: the round ends instead. */
	private static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer p)) return true;
		Duel duel = BY_PLAYER.get(p.getUUID());
		if (duel == null) return true;
		duel.onDeath(p, source);
		return false;
	}

	private static void onDisconnect(ServerPlayer p) {
		INVITES.removeIf(i -> i.from().equals(p.getUUID()) || i.to().equals(p.getUUID()));
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
	}

	static void onClosed(Duel d) {
		for (int i = 0; i < SLOTS.length; i++) {
			if (SLOTS[i] == d) SLOTS[i] = null;
		}
		BY_PLAYER.remove(d.ids[0], d);
		BY_PLAYER.remove(d.ids[1], d);
	}

	/** The duel is decided: remember it so /again works right away. */
	static void onFinished(Duel d) {
		LAST.put(d.ids[0], new Last(d.ids[1], d.names[1], d.kitOwner, d.config));
		LAST.put(d.ids[1], new Last(d.ids[0], d.names[0], d.kitOwner, d.config));
	}

	/** The player got their inventory back, so they are not part of the duel any more. */
	static void onLeft(UUID id) {
		BY_PLAYER.remove(id);
	}

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
		return Component.literal(label).withStyle(style -> style.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Why this player can't take part in a new duel right now, or null. */
	public static String busyReason(ServerPlayer p) {
		if (BY_PLAYER.containsKey(p.getUUID())) return "is already in a duel";
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
			.append(Component.literal(rematch ? " wants a rematch!" : " challenged you to a duel!").withStyle(ChatFormatting.YELLOW)));
		to.sendSystemMessage(Msg.info("  Kit: " + config.kit() + "   Arena: " + config.arenaName()));
		to.sendSystemMessage(Msg.info("  Mode: " + config.modeName()));
		MutableComponent buttons = Component.literal("  ")
			.append(button("[ACCEPT]", ChatFormatting.GREEN, "/accept " + fromName, "Click to accept the duel"))
			.append(Component.literal("   "))
			.append(button("[DENY]", ChatFormatting.RED, "/deny " + fromName, "Click to deny the duel"))
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
		ServerPlayer a = server.getPlayerList().getPlayer(invite.from());
		ServerPlayer b = server.getPlayerList().getPlayer(invite.to());
		if (a == null || b == null) {
			ServerPlayer who = a != null ? a : b;
			if (who != null) who.sendSystemMessage(Msg.err("The other player is no longer online."));
			return;
		}
		String problem = null;
		if (isBusy(a.getUUID())) problem = a.getName().getString() + " is busy right now (in a duel or the kit workshop).";
		else if (isBusy(b.getUUID())) problem = b.getName().getString() + " is busy right now (in a duel or the kit workshop).";
		if (problem == null) {
			// Somebody whose last duel just ended but who has not been put back yet: do it now.
			for (ServerPlayer p : new ServerPlayer[] {a, b}) {
				Duel old = BY_PLAYER.get(p.getUUID());
				if (old != null) old.restore(old.indexOf(p.getUUID()));
			}
		}

		int slot = -1;
		for (int i = 0; i < SLOTS.length && slot < 0; i++) if (SLOTS[i] == null) slot = i;
		if (problem == null && slot < 0) problem = "All " + MAX_DUELS + " arenas are busy right now. Try again in a minute.";

		ServerLevel arena = Arena.level(server);
		if (problem == null && arena == null) {
			problem = "The duel world is not loaded. Make sure the mod is up to date, then restart the game.";
		}

		KitStore.Kit kit = null;
		if (problem == null) {
			try {
				Optional<KitStore.Kit> read = KitStore.read(server, invite.kitOwner(), invite.config().kit());
				if (read.isEmpty()) problem = "The kit '" + invite.config().kit() + "' no longer exists.";
				else kit = read.get();
			} catch (IOException | RuntimeException e) {
				problem = "Could not read the kit: " + e.getMessage();
			}
		}
		if (problem != null) {
			a.sendSystemMessage(Msg.err(problem));
			b.sendSystemMessage(Msg.err(problem));
			return;
		}

		// Any other pending requests of these two players are no longer valid.
		INVITES.removeIf(i -> i.from().equals(a.getUUID()) || i.to().equals(a.getUUID())
			|| i.from().equals(b.getUUID()) || i.to().equals(b.getUUID()));

		Duel duel = new Duel(server, slot, invite.config(), invite.kitOwner(), kit, a, b);
		SLOTS[slot] = duel;
		BY_PLAYER.put(a.getUUID(), duel);
		BY_PLAYER.put(b.getUUID(), duel);
		try {
			duel.begin(arena);
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
		Duel duel = BY_PLAYER.get(p.getUUID());
		if (duel == null) {
			p.sendSystemMessage(Msg.err("You are not in a duel."));
			return;
		}
		int i = duel.indexOf(p.getUUID());
		if (duel.phase == Duel.Phase.ENDED) {
			// The duel is already decided: just leave now instead of waiting.
			duel.restore(i);
			return;
		}
		duel.forfeit(i);
		duel.restore(i);
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
