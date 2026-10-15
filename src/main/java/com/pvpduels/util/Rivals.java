package com.pvpduels.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.stats.StatsStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rivals: a short list of players you want to beat. /rival add &lt;player&gt;, /rival remove &lt;player&gt;, /rival (the list). When a rival
 * joins the game you get a message with a [CHALLENGE] button, and when you join you are told which of your rivals are already online.
 * The list is saved in the pvpduels folder of the game (rivals.json) and only changes what you are told: it never touches a duel.
 */
public final class Rivals {
	/** How many rivals one player can have. */
	public static final int MAX = 10;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Player id -> the ids of their rivals, in the order they were added. */
	private static Map<String, List<String>> rivals = new LinkedHashMap<>();
	/** Player id -> the last name seen (so an offline rival still has a name in the list). */
	private static Map<String, String> names = new HashMap<>();
	private static boolean loaded;

	private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, builder) -> SharedSuggestionProvider.suggest(
		ctx.getSource().getServer().getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList(), builder);

	private Rivals() {}

	// ------------------------------------------------------------------ the file

	private static final class Saved {
		Map<String, List<String>> rivals = new LinkedHashMap<>();
		Map<String, String> names = new HashMap<>();
	}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("rivals.json");
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Saved read = GSON.fromJson(Files.readString(file()), Saved.class);
			if (read == null) return;
			if (read.rivals != null) rivals = read.rivals;
			if (read.names != null) names = read.names;
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the rivals file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Saved out = new Saved();
			out.rivals = rivals;
			out.names = names;
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(out));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the rivals file: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------ the list

	/** The ids of the rivals of this player, oldest first. */
	public static synchronized List<UUID> of(UUID me) {
		ensureLoaded();
		List<UUID> out = new ArrayList<>();
		for (String id : rivals.getOrDefault(me.toString(), List.of())) {
			try {
				out.add(UUID.fromString(id));
			} catch (IllegalArgumentException ignored) {
				// a broken line in the file
			}
		}
		return out;
	}

	public static synchronized boolean isRival(UUID me, UUID other) {
		ensureLoaded();
		return rivals.getOrDefault(me.toString(), List.of()).contains(other.toString());
	}

	/** The last name seen for this id, or "someone". */
	public static synchronized String nameOf(UUID id) {
		ensureLoaded();
		return names.getOrDefault(id.toString(), "someone");
	}

	/** Finds the id of a player by name: somebody online, or somebody who has played a duel. Null when nobody has that name. */
	private static UUID find(MinecraftServer server, String name) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if (online != null) return online.getUUID();
		for (StatsStore.Entry e : StatsStore.ranking("elo")) {
			if (e.name().equalsIgnoreCase(name)) return e.id();
		}
		return null;
	}

	/** Adds a rival. Returns an error text, or null when it worked. */
	public static synchronized String add(ServerPlayer me, String name) {
		ensureLoaded();
		UUID id = find(me.level().getServer(), name);
		if (id == null) return "I don't know a player called '" + name + "'. They need to be online now, or to have played a duel before.";
		if (id.equals(me.getUUID())) return "You can't be your own rival.";
		List<String> mine = rivals.computeIfAbsent(me.getUUID().toString(), k -> new ArrayList<>());
		if (mine.contains(id.toString())) return nameOf(id) + " is already on your list.";
		if (mine.size() >= MAX) return "You can have " + MAX + " rivals at most. Remove one with /rival remove <player>.";
		ServerPlayer online = me.level().getServer().getPlayerList().getPlayer(id);
		String shown = online != null ? online.getName().getString() : name;
		mine.add(id.toString());
		names.put(id.toString(), shown);
		save();
		return null;
	}

	/** Removes a rival by name. Returns an error text, or null when it worked. */
	public static synchronized String remove(ServerPlayer me, String name) {
		ensureLoaded();
		List<String> mine = rivals.get(me.getUUID().toString());
		if (mine != null) {
			for (String id : new ArrayList<>(mine)) {
				if (names.getOrDefault(id, "").equalsIgnoreCase(name)) {
					mine.remove(id);
					if (mine.isEmpty()) rivals.remove(me.getUUID().toString());
					save();
					return null;
				}
			}
		}
		return name + " is not on your list. /rival shows it.";
	}

	// ------------------------------------------------------------------ messages

	private static Component challenge(String name) {
		return Component.literal("  ").append(Msg.button("[CHALLENGE]", ChatFormatting.GREEN, "/duel " + name, "Open the duel menus against " + name));
	}

	/** Somebody joined the game: tell their fans, and tell them which of their rivals are here. */
	public static void joined(MinecraftServer server, ServerPlayer joined) {
		synchronized (Rivals.class) {
			ensureLoaded();
			String id = joined.getUUID().toString();
			String name = joined.getName().getString();
			if (!name.equals(names.get(id))) {
				if (names.containsKey(id)) {
					names.put(id, name);
					save();
				}
			}
		}
		String joinedName = joined.getName().getString();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == joined) continue;
			if (isRival(p.getUUID(), joined.getUUID())) {
				p.sendSystemMessage(Msg.ok("Your rival " + joinedName + " is online.").copy().append(challenge(joinedName)));
			}
			if (isRival(joined.getUUID(), p.getUUID())) {
				String other = p.getName().getString();
				joined.sendSystemMessage(Msg.ok("Your rival " + other + " is online.").copy().append(challenge(other)));
			}
		}
	}

	private static int list(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer me = source.getPlayerOrException();
		List<UUID> mine = of(me.getUUID());
		if (mine.isEmpty()) {
			source.sendSuccess(() -> Msg.info("You have no rivals yet. /rival add <player> puts one on your list: you are told when they join."), false);
			return 0;
		}
		source.sendSuccess(() -> Msg.title("Your rivals (" + mine.size() + "/" + MAX + ")"), false);
		for (UUID id : mine) {
			ServerPlayer online = me.level().getServer().getPlayerList().getPlayer(id);
			String name = online != null ? online.getName().getString() : nameOf(id);
			if (online == null) {
				source.sendSuccess(() -> Msg.info("  " + name + " - offline"), false);
			} else if (DuelManager.isBusy(id)) {
				source.sendSuccess(() -> Msg.info("  " + name + " - online, busy"), false);
			} else {
				source.sendSuccess(() -> Msg.info("  " + name + " - online").copy().append(challenge(name)), false);
			}
		}
		return mine.size();
	}

	// ------------------------------------------------------------------ commands and events

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("rival")
			.executes(ctx -> list(ctx.getSource()))
			.then(Commands.literal("add")
				.then(Commands.argument("player", StringArgumentType.word()).suggests(ONLINE)
					.executes(ctx -> {
						ServerPlayer me = ctx.getSource().getPlayerOrException();
						String name = StringArgumentType.getString(ctx, "player");
						String problem = add(me, name);
						if (problem != null) {
							ctx.getSource().sendFailure(Msg.err(problem));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Msg.ok(nameOf(find(ctx.getSource().getServer(), name)) + " is on your list now. You are told when they join."), false);
						return 1;
					})))
			.then(Commands.literal("remove")
				.then(Commands.argument("player", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						List<String> out = new ArrayList<>();
						try {
							for (UUID id : of(ctx.getSource().getPlayerOrException().getUUID())) out.add(nameOf(id));
						} catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
							// not a player
						}
						return SharedSuggestionProvider.suggest(out, builder);
					})
					.executes(ctx -> {
						ServerPlayer me = ctx.getSource().getPlayerOrException();
						String typed = StringArgumentType.getString(ctx, "player");
						String name = typed;
						for (UUID id : of(me.getUUID())) if (nameOf(id).equalsIgnoreCase(typed)) name = nameOf(id); // the name as it is written in the list
						String problem = remove(me, typed);
						if (problem != null) {
							ctx.getSource().sendFailure(Msg.err(problem));
							return 0;
						}
						String shown = name;
						ctx.getSource().sendSuccess(() -> Msg.ok(shown + " is not on your list any more."), false);
						return 1;
					}))));
	}

	public static void registerEvents() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined(server, handler.getPlayer()));
	}

	// ------------------------------------------------------------------ for the tests

	public static synchronized void resetForTest() {
		rivals = new LinkedHashMap<>();
		names = new HashMap<>();
		loaded = true;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	public static synchronized void reloadForTest() {
		rivals = new LinkedHashMap<>();
		names = new HashMap<>();
		loaded = false;
		ensureLoaded();
	}

	/** Puts a rival on the list without any check (to fill the list in a test). */
	public static synchronized void addForTest(UUID me, UUID rival, String name) {
		ensureLoaded();
		rivals.computeIfAbsent(me.toString(), k -> new ArrayList<>()).add(rival.toString());
		names.put(rival.toString(), name);
	}
}
