package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * /replay: every 1v1 duel is recorded, but a player only watches a replay after an admin sent it to them. A player asks with
 * /replay ask; the request waits in a file (it is not lost if somebody logs off) and every admin sees it until it is answered.
 * Admins watch every replay without asking.
 */
public final class ReplayCommands {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);
	private static final int LIST_LIMIT = 8;
	/** Players and admins hear about their requests this many ticks after joining (the join messages are over by then). */
	private static final int JOIN_DELAY = 140;

	private static final Map<UUID, Integer> DUE = new HashMap<>();
	private static int ticks;

	private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		boolean admin = IS_ADMIN.test(ctx.getSource());
		List<String> ids = new ArrayList<>();
		for (ReplayStore.Entry e : ReplayStore.recent(admin || p == null ? null : p.getUUID(), 20)) ids.add(e.id);
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	private static final SuggestionProvider<CommandSourceStack> ANYBODY = (ctx, builder) ->
		SharedSuggestionProvider.suggest(java.util.Arrays.asList(ctx.getSource().getServer().getPlayerList().getPlayerNamesArray()), builder);

	private ReplayCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("replay")
			.executes(ctx -> list(ctx.getSource().getPlayerOrException(), IS_ADMIN.test(ctx.getSource())))
			.then(Commands.literal("list")
				.executes(ctx -> list(ctx.getSource().getPlayerOrException(), IS_ADMIN.test(ctx.getSource()))))
			.then(Commands.literal("ask")
				.executes(ctx -> ask(ctx.getSource().getPlayerOrException(), null, IS_ADMIN.test(ctx.getSource())))
				.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
					.executes(ctx -> ask(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"), IS_ADMIN.test(ctx.getSource())))))
			.then(Commands.literal("watch")
				.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
					.executes(ctx -> watch(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"), IS_ADMIN.test(ctx.getSource())))))
			.then(Commands.literal("requests")
				.requires(IS_ADMIN)
				.executes(ctx -> requests(ctx.getSource())))
			.then(Commands.literal("send")
				.requires(IS_ADMIN)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
					.executes(ctx -> answer(ctx.getSource(), StringArgumentType.getString(ctx, "id"), null, true))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(ANYBODY)
						.executes(ctx -> answer(ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "player"), true)))))
			.then(Commands.literal("deny")
				.requires(IS_ADMIN)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
					.executes(ctx -> answer(ctx.getSource(), StringArgumentType.getString(ctx, "id"), null, false))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(ANYBODY)
						.executes(ctx -> answer(ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "player"), false)))))
			.then(Commands.literal("delete")
				.requires(IS_ADMIN)
				.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
					.executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
			.then(Commands.literal("stop")
				.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "stop", 0)))
			.then(Commands.literal("pause")
				.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "pause", 0)))
			.then(Commands.literal("speed")
				.then(Commands.argument("times", DoubleArgumentType.doubleArg(0.1, 8.0))
					.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "speed", DoubleArgumentType.getDouble(ctx, "times")))))
			.then(Commands.literal("view")
				.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "view", 0)))
			.then(Commands.literal("camera")
				.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "camera", 0)))
			.then(Commands.literal("restart")
				.executes(ctx -> control(ctx.getSource().getPlayerOrException(), "restart", 0))));
	}

	// ------------------------------------------------------------------ the list

	private static String resultOf(ReplayStore.Entry e) {
		if (e.winner < 0) return "draw";
		return e.names[e.winner] + " won";
	}

	private static String length(int seconds) {
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static String when(long millis) {
		return WHEN.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
	}

	static Component row(ReplayStore.Entry e, UUID viewer, boolean admin) {
		MutableComponent line = Component.literal("  #" + e.id + " ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
			.append(Component.literal(e.names[0] + " " + e.points[0] + " - " + e.points[1] + " " + e.names[1]).withStyle(ChatFormatting.WHITE))
			.append(Component.literal("  " + resultOf(e) + ", " + length(e.seconds) + ", " + e.kit + ", " + when(e.created) + "  ").withStyle(ChatFormatting.GRAY));
		if (ReplayStore.mayWatch(e, viewer, admin)) {
			line.append(Msg.button("[WATCH]", ChatFormatting.GREEN, "/replay watch " + e.id, "Watch this duel again"));
		} else if (ReplayStore.pendingOf(e.id, viewer) != null) {
			line.append(Component.literal("(asked, waiting for an admin)").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			line.append(Msg.button("[ASK ADMIN]", ChatFormatting.AQUA, "/replay ask " + e.id, "Ask an admin to send you this replay"));
		}
		return line;
	}

	static int list(ServerPlayer p, boolean admin) {
		List<ReplayStore.Entry> mine = ReplayStore.recent(admin ? null : p.getUUID(), LIST_LIMIT);
		p.sendSystemMessage(Msg.title(admin ? "Replays (every duel)" : "Your replays"));
		if (mine.isEmpty()) {
			p.sendSystemMessage(Msg.info("  Nothing recorded yet. Every 1v1 duel is recorded from now on."));
		} else {
			for (ReplayStore.Entry e : mine) p.sendSystemMessage(row(e, p.getUUID(), admin));
		}
		if (admin) {
			int waiting = ReplayStore.pending().size();
			p.sendSystemMessage(Component.literal("  ").append(Msg.button("[REQUESTS" + (waiting > 0 ? " (" + waiting + ")" : "") + "]", ChatFormatting.GOLD, "/replay requests", "Replays the players asked for")));
		} else {
			p.sendSystemMessage(Msg.info("  An admin has to send you a replay before you can watch it."));
		}
		return mine.size();
	}

	// ------------------------------------------------------------------ asking

	static int ask(ServerPlayer p, String id, boolean admin) {
		ReplayStore.Entry e;
		if (id == null) {
			List<ReplayStore.Entry> mine = ReplayStore.recent(p.getUUID(), 1);
			e = mine.isEmpty() ? null : mine.get(0);
			if (e == null) {
				p.sendSystemMessage(Msg.err("You have no replays yet."));
				return 0;
			}
		} else {
			e = ReplayStore.get(id);
			if (e == null) {
				p.sendSystemMessage(Msg.err("There is no replay #" + id + ". /replay shows yours."));
				return 0;
			}
		}
		if (!admin && !ReplayStore.tookPart(e, p.getUUID())) {
			p.sendSystemMessage(Msg.err("You didn't take part in that duel."));
			return 0;
		}
		if (ReplayStore.mayWatch(e, p.getUUID(), admin)) {
			p.sendSystemMessage(Component.literal("You can already watch #" + e.id + " ").withStyle(ChatFormatting.GREEN)
				.append(Msg.button("[WATCH]", ChatFormatting.GREEN, "/replay watch " + e.id, "Watch this duel again")));
			return 1;
		}
		ReplayStore.Request r = ReplayStore.ask(e.id, p.getUUID(), p.getName().getString());
		if (r == null) {
			p.sendSystemMessage(Msg.info("Your request for #" + e.id + " is already waiting for an admin."));
			return 0;
		}
		p.sendSystemMessage(Msg.ok("Request sent for #" + e.id + " (" + e.names[0] + " vs " + e.names[1] + "). When an admin answers you get a message, even if you log off."));
		MinecraftServer server = p.level().getServer();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (IS_ADMIN.test(other.createCommandSourceStack())) tellRequest(other, r, e);
		}
		PvpDuelsMod.LOGGER.info("{} asked for the replay {}", r.name, e.id);
		return 1;
	}

	/** The message the admins get; it has the buttons to answer. */
	private static void tellRequest(ServerPlayer admin, ReplayStore.Request r, ReplayStore.Entry e) {
		admin.sendSystemMessage(Component.literal("[Replay] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(r.name + " asks for the replay #" + e.id + " (" + e.names[0] + " " + e.points[0] + " - " + e.points[1] + " " + e.names[1] + ")  ").withStyle(ChatFormatting.WHITE))
			.append(Msg.button("[SEND]", ChatFormatting.GREEN, "/replay send " + e.id + " " + r.name, "Let " + r.name + " watch it"))
			.append(Component.literal(" "))
			.append(Msg.button("[DENY]", ChatFormatting.RED, "/replay deny " + e.id + " " + r.name, "Say no"))
			.append(Component.literal(" "))
			.append(Msg.button("[WATCH]", ChatFormatting.AQUA, "/replay watch " + e.id, "Watch it yourself first")));
	}

	static int requests(CommandSourceStack source) {
		List<ReplayStore.Request> all = ReplayStore.pending();
		source.sendSuccess(() -> Msg.title("Replay requests (" + all.size() + ")"), false);
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("  Nobody is waiting for a replay."), false);
			return 0;
		}
		for (ReplayStore.Request r : all) {
			ReplayStore.Entry e = ReplayStore.get(r.replay);
			if (e == null) continue;
			MutableComponent line = Component.literal("  " + r.name + " asks for #" + e.id + " (" + e.names[0] + " " + e.points[0] + " - " + e.points[1] + " " + e.names[1] + ")  ").withStyle(ChatFormatting.WHITE)
				.append(Msg.button("[SEND]", ChatFormatting.GREEN, "/replay send " + e.id + " " + r.name, "Let " + r.name + " watch it"))
				.append(Component.literal(" "))
				.append(Msg.button("[DENY]", ChatFormatting.RED, "/replay deny " + e.id + " " + r.name, "Say no"));
			source.sendSuccess(() -> line, false);
		}
		return all.size();
	}

	/**
	 * An admin answers. {@code who} null = everybody who is waiting for that replay. A name that did not ask also works with "send":
	 * the admin can give the replay to any of the two players of the duel without a request.
	 */
	static int answer(CommandSourceStack source, String id, String who, boolean send) {
		ReplayStore.Entry e = ReplayStore.get(id);
		if (e == null) {
			source.sendFailure(Msg.err("There is no replay #" + id + "."));
			return 0;
		}
		List<ReplayStore.Request> targets = new ArrayList<>();
		for (ReplayStore.Request r : ReplayStore.pendingFor(e.id)) {
			if (who == null || r.name.equalsIgnoreCase(who)) targets.add(r);
		}
		int done = 0;
		for (ReplayStore.Request r : targets) {
			UUID player = UUID.fromString(r.player);
			if (send) ReplayStore.grant(e, player);
			ReplayStore.answer(r, send ? "sent" : "denied");
			done++;
			notifyAnswer(source.getServer(), r);
		}
		if (done == 0 && send && who != null) {
			// no request: still allowed for the two players of the duel
			for (int i = 0; i < 2; i++) {
				if (e.names[i].equalsIgnoreCase(who)) {
					UUID player = UUID.fromString(e.uuids[i]);
					ReplayStore.grant(e, player);
					ReplayStore.Request r = ReplayStore.ask(e.id, player, e.names[i]);
					if (r != null) {
						ReplayStore.answer(r, "sent");
						notifyAnswer(source.getServer(), r);
					}
					done++;
				}
			}
		}
		if (done == 0) {
			source.sendFailure(Msg.err(who == null ? "Nobody is waiting for #" + e.id + "." : who + " is not waiting for #" + e.id + " and didn't play it."));
			return 0;
		}
		int count = done;
		source.sendSuccess(() -> Msg.ok((send ? "Sent #" + e.id + " to " : "Denied #" + e.id + " for ") + count + (count == 1 ? " player." : " players.")), true);
		return done;
	}

	/** Tells the player the answer if they are online; if not, they hear it the next time they join. */
	private static void notifyAnswer(MinecraftServer server, ReplayStore.Request r) {
		ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(r.player));
		if (p != null) tellAnswer(p, r);
	}

	private static void tellAnswer(ServerPlayer p, ReplayStore.Request r) {
		ReplayStore.Entry e = ReplayStore.get(r.replay);
		String title = e == null ? "#" + r.replay : "#" + e.id + " (" + e.names[0] + " vs " + e.names[1] + ")";
		if (r.status.equals("sent")) {
			p.sendSystemMessage(Component.literal("[Replay] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("An admin sent you the replay " + title + ". ").withStyle(ChatFormatting.GREEN))
				.append(Msg.button("[WATCH]", ChatFormatting.GREEN, "/replay watch " + r.replay, "Watch it now")));
		} else {
			p.sendSystemMessage(Component.literal("[Replay] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal("An admin said no to the replay " + title + ".").withStyle(ChatFormatting.RED)));
		}
		ReplayStore.told(r);
	}

	static int delete(CommandSourceStack source, String id) {
		if (!ReplayStore.delete(id)) {
			source.sendFailure(Msg.err("There is no replay #" + id + "."));
			return 0;
		}
		source.sendSuccess(() -> Msg.ok("Replay #" + id + " deleted."), true);
		return 1;
	}

	// ------------------------------------------------------------------ watching

	/** Returns 1 when the replay started. {@code admin} is passed in so the self-test can try both. */
	static int watch(ServerPlayer p, String id, boolean admin) {
		ReplayStore.Entry e = ReplayStore.get(id);
		if (e == null) {
			p.sendSystemMessage(Msg.err("There is no replay #" + id + "."));
			return 0;
		}
		if (!ReplayStore.mayWatch(e, p.getUUID(), admin)) {
			if (ReplayStore.tookPart(e, p.getUUID())) {
				p.sendSystemMessage(Component.literal("An admin has to send you that replay first. ").withStyle(ChatFormatting.RED)
					.append(Msg.button("[ASK ADMIN]", ChatFormatting.AQUA, "/replay ask " + e.id, "Ask an admin to send you this replay")));
			} else {
				p.sendSystemMessage(Msg.err("You can't watch that replay."));
			}
			return 0;
		}
		ReplayData data;
		try {
			data = ReplayStore.load(e.id);
		} catch (IOException | RuntimeException ex) {
			PvpDuelsMod.LOGGER.warn("Could not read the replay {}: {}", e.id, ex.toString());
			p.sendSystemMessage(Msg.err("That replay file can't be read (" + ex.getMessage() + ")."));
			return 0;
		}
		String problem = ReplayPlayer.start(p, data);
		if (problem != null) {
			p.sendSystemMessage(Msg.err(problem));
			return 0;
		}
		return 1;
	}

	static int control(ServerPlayer p, String action, double value) {
		ReplayPlayer session = ReplayPlayer.of(p.getUUID());
		if (session == null) {
			p.sendSystemMessage(Msg.err("You are not watching a replay. /replay shows what you can watch."));
			return 0;
		}
		if (action.equals("stop")) {
			ReplayPlayer.stop(p);
			return 1;
		}
		String text = session.control(action, value);
		if (text != null) p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
		return 1;
	}

	// ------------------------------------------------------------------ telling people when they join

	/** What a player hears: answers to their requests, and for admins the requests that wait. */
	static void tellWaiting(ServerPlayer p) {
		for (ReplayStore.Request r : ReplayStore.untold(p.getUUID())) tellAnswer(p, r);
		if (!IS_ADMIN.test(p.createCommandSourceStack())) return;
		List<ReplayStore.Request> all = ReplayStore.pending();
		if (all.isEmpty()) return;
		p.sendSystemMessage(Component.literal("[Replay] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal(all.size() + (all.size() == 1 ? " player is" : " players are") + " waiting for a replay.  ").withStyle(ChatFormatting.WHITE))
			.append(Msg.button("[SEE REQUESTS]", ChatFormatting.GOLD, "/replay requests", "Send or deny them")));
	}

	/** For the self-test: tell a player right now. */
	public static void tellNow(ServerPlayer p) {
		tellWaiting(p);
	}

	public static void resetForTest() {
		DUE.clear();
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			DUE.put(p.getUUID(), ticks + JOIN_DELAY);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticks++;
			if (ticks % 10 != 0 || DUE.isEmpty()) return;
			for (Map.Entry<UUID, Integer> entry : new ArrayList<>(DUE.entrySet())) {
				if (entry.getValue() > ticks) continue;
				DUE.remove(entry.getKey());
				ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
				if (p != null) tellWaiting(p);
			}
		});
		ReplayPlayer.register();
	}
}
