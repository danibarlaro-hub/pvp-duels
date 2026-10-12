package com.pvpduels.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.duel.Duel;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.kit.KitStore;
import com.pvpduels.stats.StatsStore;
import com.pvpduels.util.Msg;
import com.pvpduels.util.Prefs;
import com.pvpduels.workshop.Workshop;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The scoreboard on the right of the screen. It is made of packets sent to one player only, so every player
 * sees their own lines and can switch it off with /sidebar. Outside duels it shows their statistics, inside
 * a duel the score.
 */
public final class Sidebar {
	private static final String OBJECTIVE = "pvpduels";
	private static final int MAX_LINES = 15;

	private static final class State {
		boolean shown;
		List<String> lines = new ArrayList<>();
	}

	private static final Map<UUID, State> STATE = new HashMap<>();
	private static Objective objective;

	private Sidebar() {}

	// ------------------------------------------------------------------ lines

	private static List<String> linesFor(ServerPlayer p) {
		Duel duel = DuelManager.duelOf(p.getUUID());
		if (duel != null) {
			List<String> lines = duel.sidebarFor(p.getUUID());
			if (!lines.isEmpty()) return lines;
		}
		List<String> lines = new ArrayList<>();
		if (Workshop.isIn(p.getUUID())) {
			lines.add("Kit workshop");
			lines.add("");
			lines.add("Kits: " + KitStore.list(p.getUUID()).size());
			lines.add("");
			lines.add("/kit save <name>");
			lines.add("/leave to exit");
			return lines;
		}
		StatsStore.Stats s = StatsStore.get(p.getUUID());
		lines.add("Wins: " + s.wins);
		lines.add("Losses: " + s.losses);
		lines.add("Win rate: " + s.winRate());
		lines.add("Win streak: " + s.streak + " (best " + s.bestStreak + ")");
		lines.add("Rounds: " + s.roundsWon + " - " + s.roundsLost);
		lines.add("Duels played: " + s.duels);
		lines.add("");
		lines.add("/sidebar to hide");
		return lines;
	}

	private static Component styled(String line) {
		if (line.isEmpty()) return Component.literal(" ");
		if (line.startsWith("vs ") || line.equals("Kit workshop")) {
			return Component.literal(line).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		}
		if (line.startsWith("/")) return Component.literal(line).withStyle(ChatFormatting.DARK_GRAY);
		return Component.literal(line).withStyle(ChatFormatting.WHITE);
	}

	// ------------------------------------------------------------------ sending

	private static Objective objective(MinecraftServer server) {
		if (objective == null || objective.getScoreboard() != server.getScoreboard()) {
			objective = new Objective(server.getScoreboard(), OBJECTIVE, ObjectiveCriteria.DUMMY,
				Component.literal("PvP Duels").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
		}
		return objective;
	}

	private static String owner(int index) {
		return "pvpd_" + index;
	}

	/** Brings the sidebar of this player up to date (shows, changes or hides it). */
	public static void update(ServerPlayer p) {
		if (p.connection == null) return;
		MinecraftServer server = p.level().getServer();
		State st = STATE.computeIfAbsent(p.getUUID(), k -> new State());

		if (!Prefs.sidebarOn(p.getUUID())) {
			hide(p, st);
			return;
		}

		Objective obj = objective(server);
		List<String> lines = linesFor(p);
		if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);

		boolean first = !st.shown;
		if (first) {
			p.connection.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_ADD));
			p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
			st.shown = true;
			st.lines = new ArrayList<>();
		}
		boolean sameSize = st.lines.size() == lines.size();
		for (int i = 0; i < lines.size(); i++) {
			if (!first && sameSize && st.lines.get(i).equals(lines.get(i))) continue;
			p.connection.send(new ClientboundSetScorePacket(owner(i), OBJECTIVE, lines.size() - i,
				Optional.of(styled(lines.get(i))), Optional.of(BlankFormat.INSTANCE)));
		}
		for (int i = lines.size(); i < st.lines.size(); i++) {
			p.connection.send(new ClientboundResetScorePacket(owner(i), OBJECTIVE));
		}
		st.lines = new ArrayList<>(lines);
	}

	private static void hide(ServerPlayer p, State st) {
		if (!st.shown) return;
		p.connection.send(new ClientboundSetObjectivePacket(objective(p.level().getServer()), ClientboundSetObjectivePacket.METHOD_REMOVE));
		st.shown = false;
		st.lines = new ArrayList<>();
	}

	// ------------------------------------------------------------------ events and command

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			STATE.remove(handler.player.getUUID()); // a new connection knows nothing yet
			update(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATE.remove(handler.player.getUUID()));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 != 0) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) update(p);
		});
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("sidebar")
			.executes(ctx -> set(ctx.getSource(), null))
			.then(Commands.argument("state", StringArgumentType.word())
				.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
				.executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "state")))));
	}

	private static int set(CommandSourceStack source, String state) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		boolean on;
		if (state == null) on = !Prefs.sidebarOn(p.getUUID());
		else if (state.equalsIgnoreCase("on")) on = true;
		else if (state.equalsIgnoreCase("off")) on = false;
		else {
			source.sendFailure(Msg.err("Use /sidebar, /sidebar on or /sidebar off."));
			return 0;
		}
		Prefs.setSidebar(p.getUUID(), on);
		update(p);
		source.sendSuccess(() -> Msg.ok("Sidebar " + (on ? "on" : "off") + "."), false);
		return 1;
	}
}
