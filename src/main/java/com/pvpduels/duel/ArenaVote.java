package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.pvpduels.arena.ArenaStore;
import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The arena vote of the queue. When two players (or two parties) are matched, three arenas are picked at random and everybody votes
 * for one with the buttons in the chat, /arenavote or its menu. The arena with the most votes is played (a tie, or nobody voting, is
 * decided at random). It lasts {@link #voteTicks} ticks, or a couple of seconds after the last player voted.
 * <p>
 * It only changes where the queue match is played: the kit, the mode (first to 3) and everything else stay as before.
 */
public final class ArenaVote {
	/** Off = the queue starts the match at once in the flat arena with walls, like before the vote existed. */
	public static boolean enabled = true;
	/** How long the vote lasts: 15 seconds. */
	public static int voteTicks = 300;
	/** After the last player voted the match starts in this many ticks (so they can still change their mind). */
	static final int AFTER_ALL_VOTED = 40;
	static final int OPTIONS = 3;

	/** One arena that can be voted for. */
	record Option(ArenaKind kind, boolean walls, int radius, String custom) {
		DuelConfig config(String kit) {
			if (kind == ArenaKind.CUSTOM) return DuelConfig.inCustomArena(kit, custom, WinMode.FIRST_TO, 3, DuelConfig.Rules.DEFAULT);
			return new DuelConfig(kit, kind, walls, radius, WinMode.FIRST_TO, 3);
		}

		String name() {
			return config("x").arenaName();
		}

		Item icon() {
			return switch (kind) {
				case FLAT -> Items.BEDROCK;
				case CIRCLE -> Items.GRASS_BLOCK;
				case UNLIMITED -> Items.COMPASS;
				default -> Items.PAPER;
			};
		}
	}

	/** One vote in progress. */
	static final class Vote {
		final List<UUID> voters = new ArrayList<>();
		final Map<UUID, Integer> choice = new LinkedHashMap<>();
		final List<Option> options = new ArrayList<>();
		UUID first;
		UUID second;
		UUID kitOwner;
		String kitName;
		KitStore.Kit kit;
		int left;
		int age;
	}

	private static final List<Vote> VOTES = new ArrayList<>();
	private static Random random = new Random();
	private static String lastPicked = "";
	/** For the self-test: these options instead of random ones. */
	static List<Option> forced;

	private ArenaVote() {}

	// ------------------------------------------------------------------ the arenas

	/** Every arena a queue match can be played in. Custom arenas are for 1v1 only (a 2v2 is played in the ones the builders made for it). */
	static List<Option> pool(boolean team) {
		List<Option> out = new ArrayList<>();
		out.add(new Option(ArenaKind.FLAT, true, 0, ""));
		out.add(new Option(ArenaKind.FLAT, false, 0, ""));
		out.add(new Option(ArenaKind.CIRCLE, false, 100, ""));
		out.add(new Option(ArenaKind.CIRCLE, false, 150, ""));
		out.add(new Option(ArenaKind.UNLIMITED, false, 0, ""));
		if (!team) {
			for (ArenaStore.Entry e : ArenaStore.publicList()) out.add(new Option(ArenaKind.CUSTOM, false, 0, ArenaStore.publicKey(e.name())));
		}
		return out;
	}

	/** Three different arenas at random. */
	static List<Option> pick(boolean team) {
		if (forced != null) return new ArrayList<>(forced);
		List<Option> all = pool(team);
		Collections.shuffle(all, random);
		return new ArrayList<>(all.subList(0, Math.min(OPTIONS, all.size())));
	}

	// ------------------------------------------------------------------ starting a vote

	public static boolean isVoting(UUID id) {
		return voteOf(id) != null;
	}

	static Vote voteOf(UUID id) {
		for (Vote v : VOTES) if (v.voters.contains(id)) return v;
		return null;
	}

	static int size() {
		return VOTES.size();
	}

	/**
	 * The queue found a match: ask the players where to play. {@code mates} is empty for a 1v1 (otherwise the partner of first and of second).
	 */
	static void begin(MinecraftServer server, ServerPlayer first, ServerPlayer second, List<ServerPlayer> mates, UUID kitOwner, String kitName, KitStore.Kit kit) {
		Vote v = new Vote();
		v.voters.add(first.getUUID());
		v.voters.add(second.getUUID());
		for (ServerPlayer m : mates) v.voters.add(m.getUUID());
		v.first = first.getUUID();
		v.second = second.getUUID();
		v.kitOwner = kitOwner;
		v.kitName = kitName;
		v.kit = kit;
		v.left = voteTicks;
		v.options.addAll(pick(!mates.isEmpty()));
		VOTES.add(v);
		for (UUID id : v.voters) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			p.sendSystemMessage(Msg.title("Vote for the arena (" + voteTicks / 20 + " seconds)"));
			Component line = Component.literal("  ");
			for (int i = 0; i < v.options.size(); i++) {
				line = line.copy().append(Msg.button("[" + (i + 1) + "] " + v.options.get(i).name(), ChatFormatting.AQUA, "/arenavote " + (i + 1), "Vote for this arena"))
					.append(Component.literal("  "));
			}
			p.sendSystemMessage(line);
			p.sendSystemMessage(Msg.info("  /arenavote opens a menu. If nobody votes, or it is a tie, it is picked at random."));
		}
	}

	// ------------------------------------------------------------------ voting

	/** Votes (or changes the vote) for option number {@code number} (1 to 3). Returns an error text, or null when it worked. */
	public static String vote(ServerPlayer p, int number) {
		Vote v = voteOf(p.getUUID());
		if (v == null) return "There is no arena vote for you right now. It starts when the queue finds you a match.";
		if (number < 1 || number > v.options.size()) return "Vote for 1 to " + v.options.size() + ".";
		v.choice.put(p.getUUID(), number - 1);
		p.sendSystemMessage(Msg.ok("You voted for " + v.options.get(number - 1).name() + "."));
		if (v.choice.size() == v.voters.size()) v.left = Math.min(v.left, AFTER_ALL_VOTED);
		return null;
	}

	static int votesFor(Vote v, int index) {
		int n = 0;
		for (int c : v.choice.values()) if (c == index) n++;
		return n;
	}

	static void openMenu(ServerPlayer p) {
		Vote v = voteOf(p.getUUID());
		if (v == null) {
			p.sendSystemMessage(Msg.err("There is no arena vote for you right now. It starts when the queue finds you a match."));
			return;
		}
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		int[] slots = {11, 13, 15};
		Integer mine = v.choice.get(p.getUUID());
		for (int i = 0; i < v.options.size(); i++) {
			int number = i + 1;
			Option o = v.options.get(i);
			int n = votesFor(v, i);
			boolean yours = mine != null && mine == i;
			b.set(slots[i], DuelMenus.icon(o.icon(), number + ". " + o.name(), yours ? ChatFormatting.GREEN : ChatFormatting.WHITE,
				"Votes: " + n, yours ? "Your vote." : "Click to vote for it."), who -> {
				String problem = vote(who, number);
				if (problem != null) who.sendSystemMessage(Msg.err(problem));
				else openMenu(who);
			});
		}
		ChoiceMenu.open(p, Component.literal("Vote for the arena (" + Math.max(0, v.left) / 20 + "s)"), 3, b);
	}

	// ------------------------------------------------------------------ the end

	/** The option that won, at random among the best (all of them when nobody voted). */
	static int winner(Vote v) {
		int best = 0;
		for (int i = 0; i < v.options.size(); i++) best = Math.max(best, votesFor(v, i));
		List<Integer> top = new ArrayList<>();
		for (int i = 0; i < v.options.size(); i++) if (votesFor(v, i) == best) top.add(i);
		return top.get(top.size() == 1 ? 0 : random.nextInt(top.size()));
	}

	private static void cancel(MinecraftServer server, Vote v, String reason) {
		VOTES.remove(v);
		for (UUID id : v.voters) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) p.sendSystemMessage(Msg.err("The match was cancelled: " + reason));
		}
	}

	private static void finish(MinecraftServer server, Vote v) {
		VOTES.remove(v);
		int win = winner(v);
		Option option = v.options.get(win);
		int n = votesFor(v, win);
		boolean tie = false;
		for (int i = 0; i < v.options.size(); i++) if (i != win && votesFor(v, i) == n) tie = true;
		String how = v.choice.isEmpty() ? "nobody voted, picked at random" : tie ? n + (n == 1 ? " vote" : " votes") + ", a tie picked at random" : n + (n == 1 ? " vote" : " votes");
		lastPicked = option.name();
		List<ServerPlayer> everybody = new ArrayList<>();
		for (UUID id : v.voters) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) {
				cancel(server, v, "a player left.");
				return;
			}
			if (DuelManager.isBusy(id)) {
				cancel(server, v, p.getName().getString() + " is busy.");
				return;
			}
			everybody.add(p);
		}
		for (ServerPlayer p : everybody) p.sendSystemMessage(Msg.ok("Arena: " + option.name() + " (" + how + ")."));
		ServerPlayer first = server.getPlayerList().getPlayer(v.first);
		ServerPlayer second = server.getPlayerList().getPlayer(v.second);
		Queue.launch(server, everybody, first, second, v.kitOwner, option.config(v.kitName), v.kit);
	}

	// ------------------------------------------------------------------ events and commands

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArenaVote::onTick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Vote v = voteOf(handler.player.getUUID());
			if (v != null) cancel(server, v, handler.player.getName().getString() + " left the game.");
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VOTES.clear());
	}

	private static void onTick(MinecraftServer server) {
		if (VOTES.isEmpty()) return;
		for (Vote v : new ArrayList<>(VOTES)) {
			v.age++;
			v.left--;
			if (v.left <= 0) {
				finish(server, v);
				continue;
			}
			if (v.age % 20 != 0) continue;
			for (UUID id : v.voters) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p == null) {
					cancel(server, v, "a player left.");
					break;
				}
			}
			if (!VOTES.contains(v)) continue;
			StringBuilder text = new StringBuilder("Arena vote  " + (v.left + 19) / 20 + "s   ");
			for (int i = 0; i < v.options.size(); i++) text.append(i + 1).append(": ").append(votesFor(v, i)).append("   ");
			Component overlay = Component.literal(text.toString().trim()).withStyle(ChatFormatting.AQUA);
			for (UUID id : v.voters) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) p.sendOverlayMessage(overlay);
			}
		}
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arenavote")
			.executes(ctx -> {
				openMenu(ctx.getSource().getPlayerOrException());
				return 1;
			})
			.then(Commands.argument("number", IntegerArgumentType.integer(1, OPTIONS))
				.executes(ctx -> {
					String problem = vote(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "number"));
					if (problem != null) {
						ctx.getSource().sendFailure(Msg.err(problem));
						return 0;
					}
					return 1;
				})));
	}

	// ------------------------------------------------------------------ for the tests

	static void useRandom(Random r) {
		random = r;
	}

	static void clearAll() {
		VOTES.clear();
		lastPicked = "";
	}

	static String lastPicked() {
		return lastPicked;
	}
}
