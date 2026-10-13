package com.pvpduels.duel;

import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /draw: in any real duel (1v1 or 2v2) one player offers a tie and the rival (or the other team) accepts with a click. An accepted
 * draw ends the duel as a tie: the same ending as a tie by score, so it breaks the win streaks, and when both players have the
 * Domain Expansion equipped their domains clash.
 */
final class Draws {
	private record Offer(int side, int expiresAt) {}

	/** The offer of each duel (the duel object is the key). */
	private static final Map<Object, Offer> OFFERS = new HashMap<>();
	/** An offer lasts one minute (the self-test makes it shorter). */
	static int expires = 20 * 60;

	private Draws() {}

	/** {@code action}: null = offer (or accept if the rival already offered), "accept" or "deny". */
	static void handle(ServerPlayer p, String action) {
		MinecraftServer server = p.level().getServer();
		Duel d = DuelManager.duelOf(p.getUUID());
		TeamDuel t = DuelManager.teamDuelOf(p.getUUID());
		if (d == null && t == null) {
			p.sendSystemMessage(Msg.err("You are not in a duel."));
			return;
		}
		boolean team = d == null;
		Object duel = team ? t : d;
		if (!team && d.practice) {
			p.sendSystemMessage(Msg.err("A practice duel has no draw: use /leave to stop."));
			return;
		}
		if (team ? t.closed : d.closed) return;
		Duel.Phase phase = team ? t.phase : d.phase;
		if (phase != Duel.Phase.FIGHTING) {
			p.sendSystemMessage(Msg.err(phase == Duel.Phase.ENDED ? "The duel is already over." : "You can offer a draw while a round is being fought."));
			return;
		}
		int index = team ? t.indexOf(p.getUUID()) : d.indexOf(p.getUUID());
		int side = team ? TeamDuel.teamOf(index) : index;

		Offer offer = OFFERS.get(duel);
		if (offer != null && offer.expiresAt() < server.getTickCount()) {
			OFFERS.remove(duel);
			offer = null;
		}
		boolean rivalOffered = offer != null && offer.side() != side;

		if ("deny".equals(action)) {
			if (!rivalOffered) {
				p.sendSystemMessage(Msg.err("Nobody offered you a draw."));
				return;
			}
			OFFERS.remove(duel);
			tell(duel, team, 1 - side, Msg.info(p.getName().getString() + " denied the draw."));
			p.sendSystemMessage(Msg.info("You denied the draw."));
			return;
		}
		if ("accept".equals(action) && !rivalOffered) {
			p.sendSystemMessage(Msg.err("There is no draw offer to accept."));
			return;
		}
		if (rivalOffered) {
			agree(duel, team, d, t);
			return;
		}
		if (offer != null) {
			p.sendSystemMessage(Msg.info("You already offered a draw. Wait for the answer."));
			return;
		}
		OFFERS.put(duel, new Offer(side, server.getTickCount() + expires));
		String name = p.getName().getString();
		tell(duel, team, 1 - side, Component.literal(name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(team ? "'s team offers a draw.  " : " offers a draw.  ").withStyle(ChatFormatting.YELLOW))
			.append(Msg.button("[ACCEPT]", ChatFormatting.GREEN, "/draw accept", "Accept the draw: the duel ends in a tie"))
			.append(Component.literal("  "))
			.append(Msg.button("[DENY]", ChatFormatting.RED, "/draw deny", "Keep fighting"))
			.append(Msg.info("  (expires in 60 seconds)")));
		p.sendSystemMessage(Msg.ok("Draw offer sent. It expires in 60 seconds."));
		// A test bot accepts everything right away.
		for (UUID id : idsOf(duel, team, 1 - side)) {
			if (TestDummy.accepts(id)) {
				agree(duel, team, d, t);
				return;
			}
		}
	}

	private static void agree(Object duel, boolean team, Duel d, TeamDuel t) {
		OFFERS.remove(duel);
		for (int side = 0; side < 2; side++) tell(duel, team, side, Msg.ok("Draw agreed: the duel ends in a tie."));
		if (team) t.agreeDraw(); else d.agreeDraw();
	}

	private static UUID[] idsOf(Object duel, boolean team, int side) {
		if (team) {
			TeamDuel t = (TeamDuel) duel;
			return new UUID[] {t.ids[2 * side], t.ids[2 * side + 1]};
		}
		return new UUID[] {((Duel) duel).ids[side]};
	}

	private static void tell(Object duel, boolean team, int side, Component message) {
		MinecraftServer server = team ? ((TeamDuel) duel).server() : ((Duel) duel).server();
		for (UUID id : idsOf(duel, team, side)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) p.sendSystemMessage(message);
		}
	}

	/** The duel is over: its offer is forgotten. */
	static void forget(Object duel) {
		OFFERS.remove(duel);
	}

	/** For the self-test. */
	static boolean hasOffer(Object duel) {
		return OFFERS.containsKey(duel);
	}

	static void clearAll() {
		OFFERS.clear();
	}
}
