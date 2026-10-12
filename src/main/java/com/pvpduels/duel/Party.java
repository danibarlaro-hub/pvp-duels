package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A party is two friends who play 2v2 together. When both players of a party are in a full party, a /duel against a member of
 * another full party is a 2v2. Parties live in memory only: they end when somebody leaves, disconnects or the server stops.
 */
public final class Party {
	public static final int SIZE = 2;
	/** A party invitation expires after this many milliseconds. */
	private static final long INVITE_MILLIS = 60_000;

	private static final class Group {
		final UUID leader;
		final List<UUID> members = new ArrayList<>();

		Group(UUID leader) {
			this.leader = leader;
			members.add(leader);
		}
	}

	private record Pending(UUID inviter, String inviterName, long expires) {}

	/** Member -> their party. */
	private static final Map<UUID, Group> GROUPS = new HashMap<>();
	/** Invited player -> the invitation. */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/** For the self-test: a fake clock (0 = the real one). */
	private static long fakeNow;

	private Party() {}

	private static long now() {
		return fakeNow != 0 ? fakeNow : System.currentTimeMillis();
	}

	public static void setClockForTest(long millis) {
		fakeNow = millis;
	}

	public static void resetForTest() {
		GROUPS.clear();
		PENDING.clear();
		fakeNow = 0;
	}

	// ------------------------------------------------------------------ questions

	public static boolean inParty(UUID id) {
		return GROUPS.containsKey(id);
	}

	/** In a party with another player (a party of one does not exist). */
	public static boolean isFull(UUID id) {
		Group g = GROUPS.get(id);
		return g != null && g.members.size() >= SIZE;
	}

	/** The other member of the party, or null. */
	public static UUID partnerOf(UUID id) {
		Group g = GROUPS.get(id);
		if (g == null) return null;
		for (UUID member : g.members) if (!member.equals(id)) return member;
		return null;
	}

	public static boolean isLeader(UUID id) {
		Group g = GROUPS.get(id);
		return g != null && g.leader.equals(id);
	}

	// ------------------------------------------------------------------ changes

	/** Invites a player to be your partner in 2v2 duels. Returns an error text or null. */
	public static String invite(ServerPlayer from, ServerPlayer to) {
		if (from.getUUID().equals(to.getUUID())) return "You can't be your own partner.";
		if (isFull(from.getUUID())) return "Your party is full already. /party leave if you want to change partner.";
		if (inParty(to.getUUID())) return to.getName().getString() + " is in a party already.";
		if (DuelManager.isInDuel(from.getUUID())) return "Finish your duel first.";
		PENDING.put(to.getUUID(), new Pending(from.getUUID(), from.getName().getString(), now() + INVITE_MILLIS));
		to.sendSystemMessage(Component.literal(from.getName().getString()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(" wants you as a partner for 2v2 duels. ").withStyle(ChatFormatting.YELLOW))
			.append(Msg.button("[ACCEPT]", ChatFormatting.GREEN, "/party accept", "Be their partner"))
			.append(Component.literal(" "))
			.append(Msg.button("[DENY]", ChatFormatting.RED, "/party deny", "No, thanks")));
		from.sendSystemMessage(Msg.ok("Party invitation sent to " + to.getName().getString() + ". It expires in 60 seconds."));
		return null;
	}

	/** Accepts the invitation. Returns an error text or null. */
	public static String accept(ServerPlayer p) {
		Pending pending = PENDING.remove(p.getUUID());
		if (pending == null || pending.expires() < now()) return "You have no party invitation (or it expired).";
		if (inParty(p.getUUID())) return "You are in a party already. /party leave first.";
		MinecraftServer server = p.level().getServer();
		ServerPlayer inviter = server.getPlayerList().getPlayer(pending.inviter());
		if (inviter == null) return pending.inviterName() + " is not online any more.";
		if (isFull(inviter.getUUID())) return pending.inviterName() + " has a partner already.";
		Group g = GROUPS.get(inviter.getUUID());
		if (g == null) {
			g = new Group(inviter.getUUID());
			GROUPS.put(inviter.getUUID(), g);
		}
		g.members.add(p.getUUID());
		GROUPS.put(p.getUUID(), g);
		PENDING.remove(inviter.getUUID());
		Queue.leave(p, false); // the queue is 1v1 only
		Queue.leave(inviter, false);
		p.sendSystemMessage(Msg.ok("You are in a party with " + inviter.getName().getString() + ". Challenge another pair with /duel <player>."));
		inviter.sendSystemMessage(Msg.ok(p.getName().getString() + " is your partner now. Challenge another pair with /duel <player>."));
		return null;
	}

	/** Returns an error text or null. */
	public static String deny(ServerPlayer p) {
		Pending pending = PENDING.remove(p.getUUID());
		if (pending == null) return "You have no party invitation.";
		ServerPlayer inviter = p.level().getServer().getPlayerList().getPlayer(pending.inviter());
		p.sendSystemMessage(Msg.info("You denied the invitation of " + pending.inviterName() + "."));
		if (inviter != null) inviter.sendSystemMessage(Msg.err(p.getName().getString() + " denied your party invitation."));
		return null;
	}

	/** Leaves the party; with a partner, the party ends for both. Returns an error text or null. */
	public static String leave(ServerPlayer p) {
		if (!inParty(p.getUUID())) return "You are not in a party.";
		if (DuelManager.isInDuel(p.getUUID())) return "You are in a duel: finish it or use /leave first.";
		MinecraftServer server = p.level().getServer();
		UUID partner = partnerOf(p.getUUID());
		if (partner != null && DuelManager.isInDuel(partner)) return "Your partner is in a duel: wait until it ends.";
		disband(p.getUUID());
		p.sendSystemMessage(Msg.ok("You left the party."));
		if (partner != null) {
			ServerPlayer other = server.getPlayerList().getPlayer(partner);
			if (other != null) other.sendSystemMessage(Msg.info(p.getName().getString() + " left the party, so it is over."));
		}
		return null;
	}

	private static void disband(UUID id) {
		Group g = GROUPS.get(id);
		if (g == null) return;
		for (UUID member : g.members) GROUPS.remove(member);
	}

	/** A player disconnected: their party and invitations end. */
	static void forget(MinecraftServer server, UUID id) {
		PENDING.remove(id);
		PENDING.values().removeIf(p -> p.inviter().equals(id));
		UUID partner = partnerOf(id);
		disband(id);
		if (partner != null) {
			ServerPlayer other = server.getPlayerList().getPlayer(partner);
			if (other != null) other.sendSystemMessage(Msg.info("Your partner left the game, so your party is over."));
		}
	}

	// ------------------------------------------------------------------ events and commands

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(server, handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GROUPS.clear();
			PENDING.clear();
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("party")
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				UUID partner = partnerOf(p.getUUID());
				if (partner == null) {
					ctx.getSource().sendSuccess(() -> Msg.info("You are not in a party. /party invite <player> asks a friend to be your 2v2 partner."), false);
					return 0;
				}
				ServerPlayer other = p.level().getServer().getPlayerList().getPlayer(partner);
				String name = other == null ? "someone offline" : other.getName().getString();
				ctx.getSource().sendSuccess(() -> Msg.info("Your party: you and " + name + ". Challenge another pair with /duel <player>. /party leave ends it."), false);
				return 1;
			})
			.then(Commands.literal("invite")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> answer(ctx.getSource(), invite(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"))))))
			.then(Commands.literal("accept")
				.executes(ctx -> answer(ctx.getSource(), accept(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("deny")
				.executes(ctx -> answer(ctx.getSource(), deny(ctx.getSource().getPlayerOrException()))))
			.then(Commands.literal("leave")
				.executes(ctx -> answer(ctx.getSource(), leave(ctx.getSource().getPlayerOrException())))));
	}

	private static int answer(CommandSourceStack source, String problem) {
		if (problem != null) {
			source.sendFailure(Msg.err(problem));
			return 0;
		}
		return 1;
	}
}
