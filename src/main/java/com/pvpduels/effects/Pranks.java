package com.pvpduels.effects;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.duel.Lobby;
import com.pvpduels.util.Homes;
import com.pvpduels.util.Msg;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The annoying commands, for admins (and the owner, dibitss) to bother friends with: /annoy, /spam, /haunt, /jumpscare, /fakeop and
 * /annoystop. They aim at one player, at everybody ("all") or at the lobby spawn ("spawn"). Nobody in a duel is ever bothered, and every
 * player can turn all of it off for themselves with /pranks off.
 */
public final class Pranks {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	/** The longest a spam or a haunting can go on. */
	static final int MAX_SECONDS = 60;
	private static final int MAX_JOBS = 8;

	private static final String[] SPOOKY = {
		"entity.creeper.primed", "ambient.cave", "entity.zombie.ambient", "entity.skeleton.ambient", "entity.enderman.stare", "entity.ghast.scream",
		"block.wooden_door.open", "entity.zombie.attack_wooden_door", "block.gravel.step", "entity.warden.nearby_close", "entity.phantom.ambient",
		"block.chest.open", "entity.witch.ambient", "entity.cat.hiss", "entity.enderman.teleport", "block.stone.step"
	};

	private enum Kind { SPAM, HAUNT }

	/** Something that keeps going for a while: who it bothers, what it plays, until when. */
	private static final class Job {
		final Kind kind;
		final String who; // a player name, "all" or "spawn"
		final MemeSounds.Meme meme; // null = a different one each time
		final long endTick;
		long nextTick;

		Job(Kind kind, String who, MemeSounds.Meme meme, long endTick) {
			this.kind = kind;
			this.who = who;
			this.meme = meme;
			this.endTick = endTick;
		}
	}

	private static final List<Job> JOBS = new ArrayList<>();
	private static long now;
	private static int sent;

	private static final SuggestionProvider<CommandSourceStack> WHO = (ctx, builder) -> {
		List<String> names = new ArrayList<>(List.of("all", "spawn"));
		names.addAll(ctx.getSource().getServer().getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList());
		return SharedSuggestionProvider.suggest(names, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerList().getPlayers().stream().map(p -> p.getName().getString()).toList(), builder);
	private static final SuggestionProvider<CommandSourceStack> SOUNDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>(List.of("random"));
		for (MemeSounds.Meme m : MemeSounds.all()) ids.add(m.id());
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	private Pranks() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Pranks::tick);
	}

	/** How many prank sounds and scares were sent since the game started (the self-test reads it). */
	public static int sent() {
		return sent;
	}

	public static int activeJobs() {
		return JOBS.size();
	}

	public static void resetForTest() {
		JOBS.clear();
		sent = 0;
	}

	private static boolean allowed(CommandSourceStack source) {
		return IS_ADMIN.test(source) || (source.getEntity() instanceof ServerPlayer p && Homes.allowed(p));
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("annoy").requires(Pranks::allowed)
			.then(Commands.argument("who", StringArgumentType.word()).suggests(WHO)
				.executes(ctx -> annoy(ctx, "random"))
				.then(Commands.argument("sound", StringArgumentType.word()).suggests(SOUNDS)
					.executes(ctx -> annoy(ctx, StringArgumentType.getString(ctx, "sound"))))));
		dispatcher.register(Commands.literal("spam").requires(Pranks::allowed)
			.then(Commands.argument("who", StringArgumentType.word()).suggests(WHO)
				.executes(ctx -> spam(ctx, 10, "random"))
				.then(Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
					.executes(ctx -> spam(ctx, IntegerArgumentType.getInteger(ctx, "seconds"), "random"))
					.then(Commands.argument("sound", StringArgumentType.word()).suggests(SOUNDS)
						.executes(ctx -> spam(ctx, IntegerArgumentType.getInteger(ctx, "seconds"), StringArgumentType.getString(ctx, "sound")))))));
		dispatcher.register(Commands.literal("haunt").requires(Pranks::allowed)
			.then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
				.executes(ctx -> haunt(ctx, 30))
				.then(Commands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
					.executes(ctx -> haunt(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))))));
		dispatcher.register(Commands.literal("jumpscare").requires(Pranks::allowed)
			.then(Commands.argument("who", StringArgumentType.word()).suggests(WHO).executes(Pranks::jumpscare)));
		dispatcher.register(Commands.literal("fakeop").requires(Pranks::allowed)
			.then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(Pranks::fakeop)));
		dispatcher.register(Commands.literal("annoystop").requires(Pranks::allowed)
			.executes(ctx -> stop(ctx, null))
			.then(Commands.argument("who", StringArgumentType.word()).suggests(WHO).executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "who")))));
		// everybody: turn the pranks off for yourself
		dispatcher.register(Commands.literal("pranks")
			.executes(ctx -> pranksSetting(ctx, null))
			.then(Commands.literal("on").executes(ctx -> pranksSetting(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> pranksSetting(ctx, false))));
	}

	// ---------------------------------------------------------------- who is bothered

	/** True when this player can be bothered: they did not turn pranks off and they are not in a duel. */
	static boolean fair(ServerPlayer p) {
		return Effects.pranks(p.getUUID()) && !DuelManager.isInDuel(p.getUUID());
	}

	/** The players a prank aimed at {@code who} reaches right now (the fair ones), or null when there is nobody with that name. */
	private static List<ServerPlayer> targets(MinecraftServer server, String who) {
		List<ServerPlayer> out = new ArrayList<>();
		String w = who.toLowerCase(Locale.ROOT);
		if (w.equals("all")) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (fair(p)) out.add(p);
		} else if (w.equals("spawn")) {
			Lobby.Place spot = Lobby.spotIn(server);
			if (spot != null) for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.level() == spot.level() && fair(p)) out.add(p);
		} else {
			ServerPlayer p = server.getPlayerList().getPlayerByName(who);
			if (p == null) return null;
			if (fair(p)) out.add(p);
		}
		return out;
	}

	private static String missing(MinecraftServer server, String who) {
		String w = who.toLowerCase(Locale.ROOT);
		if (w.equals("spawn") && Lobby.spotIn(server) == null) return "There is no lobby (spawn) yet: set it with the lobby command first.";
		if (!w.equals("all") && !w.equals("spawn") && server.getPlayerList().getPlayerByName(who) == null) return "No player called '" + who + "' is online. Use a name, all or spawn.";
		return null;
	}

	// ---------------------------------------------------------------- sending

	private static void send(ServerPlayer p, Holder<SoundEvent> sound, double x, double y, double z, float volume, float pitch) {
		p.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, x, y, z, volume, pitch, p.level().getRandom().nextLong()));
		sent++;
	}

	/** One silly sound to these players: at the spawn (loud, heard from far) or right on top of each of them. */
	private static void playMeme(MinecraftServer server, String who, List<ServerPlayer> to, MemeSounds.Meme meme, float pitch) {
		if (who.equalsIgnoreCase("spawn")) {
			Lobby.Place spot = Lobby.spotIn(server);
			if (spot == null) return;
			for (ServerPlayer p : to) send(p, meme.sound(), spot.x(), spot.y(), spot.z(), 16f, pitch);
		} else {
			for (ServerPlayer p : to) send(p, meme.sound(), p.getX(), p.getY(), p.getZ(), 1.0f, pitch);
		}
	}

	private static MemeSounds.Meme pick(String name) {
		if (name == null || name.equalsIgnoreCase("random")) return MemeSounds.random();
		return MemeSounds.of(name.toLowerCase(Locale.ROOT));
	}

	private static Holder<SoundEvent> vanilla(String id) {
		return Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:" + id)));
	}

	private static String said(List<ServerPlayer> to) {
		return to.size() + " player" + (to.size() == 1 ? "" : "s");
	}

	// ---------------------------------------------------------------- the commands

	private static int annoy(CommandContext<CommandSourceStack> ctx, String sound) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		String who = StringArgumentType.getString(ctx, "who");
		MemeSounds.Meme meme = pick(sound);
		if (meme == null) {
			ctx.getSource().sendFailure(Msg.err("There is no sound called '" + sound + "'. Use one of: random, " + ids()));
			return 0;
		}
		String problem = missing(server, who);
		if (problem != null) {
			ctx.getSource().sendFailure(Msg.err(problem));
			return 0;
		}
		List<ServerPlayer> to = targets(server, who);
		playMeme(server, who, to, meme, 1.0f);
		ctx.getSource().sendSuccess(() -> Msg.ok("Played '" + meme.name() + "' to " + said(to) + (who.equalsIgnoreCase("spawn") ? " at the spawn" : "")
			+ ". (People in a duel, or who used /pranks off, are left alone.)"), false);
		return to.size();
	}

	private static int spam(CommandContext<CommandSourceStack> ctx, int seconds, String sound) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		String who = StringArgumentType.getString(ctx, "who");
		MemeSounds.Meme meme = pick(sound);
		if (meme == null) {
			ctx.getSource().sendFailure(Msg.err("There is no sound called '" + sound + "'. Use one of: random, " + ids()));
			return 0;
		}
		String problem = missing(server, who);
		if (problem == null && JOBS.size() >= MAX_JOBS) problem = "Too many pranks are running already (" + MAX_JOBS + "). Use /annoystop.";
		if (problem != null) {
			ctx.getSource().sendFailure(Msg.err(problem));
			return 0;
		}
		// a fixed sound only when one was asked for; "random" changes at every beat
		MemeSounds.Meme fixed = sound.equalsIgnoreCase("random") ? null : meme;
		JOBS.add(new Job(Kind.SPAM, who, fixed, now + seconds * 20L));
		ctx.getSource().sendSuccess(() -> Msg.ok("Spamming " + (fixed == null ? "random silly sounds" : "'" + fixed.name() + "'") + " at " + who + " for " + seconds
			+ " seconds. Stop it with /annoystop."), false);
		return 1;
	}

	private static int haunt(CommandContext<CommandSourceStack> ctx, int seconds) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		String who = StringArgumentType.getString(ctx, "player");
		String problem = missing(server, who);
		if (problem == null && (who.equalsIgnoreCase("all") || who.equalsIgnoreCase("spawn"))) problem = "Haunt one player: /haunt <player> [seconds].";
		if (problem == null && JOBS.size() >= MAX_JOBS) problem = "Too many pranks are running already (" + MAX_JOBS + "). Use /annoystop.";
		if (problem != null) {
			ctx.getSource().sendFailure(Msg.err(problem));
			return 0;
		}
		JOBS.add(new Job(Kind.HAUNT, who, null, now + seconds * 20L));
		ctx.getSource().sendSuccess(() -> Msg.ok("Haunting " + who + " for " + seconds + " seconds: footsteps, creepers and doors where nobody is. Stop it with /annoystop."), false);
		return 1;
	}

	private static int jumpscare(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		String who = StringArgumentType.getString(ctx, "who");
		String problem = missing(server, who);
		if (problem != null) {
			ctx.getSource().sendFailure(Msg.err(problem));
			return 0;
		}
		List<ServerPlayer> to = targets(server, who);
		for (ServerPlayer p : to) {
			send(p, MemeSounds.of("airhorn").sound(), p.getX(), p.getY(), p.getZ(), 1.0f, 0.6f);
			send(p, vanilla("entity.ghast.scream"), p.getX(), p.getY(), p.getZ(), 1.0f, 0.5f);
			send(p, vanilla("entity.warden.sonic_boom"), p.getX(), p.getY(), p.getZ(), 1.0f, 0.8f);
			p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 10));
			p.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("BOO!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
		}
		ctx.getSource().sendSuccess(() -> Msg.ok("Scared " + said(to) + "."), false);
		return to.size();
	}

	/** A fake "you are an operator now" line, shown only to that player. */
	private static int fakeop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		String who = StringArgumentType.getString(ctx, "player");
		ServerPlayer p = server.getPlayerList().getPlayerByName(who);
		if (p == null) {
			ctx.getSource().sendFailure(Msg.err("No player called '" + who + "' is online."));
			return 0;
		}
		if (!fair(p)) {
			ctx.getSource().sendFailure(Msg.err(p.getName().getString() + " is in a duel or turned pranks off, so nothing was sent."));
			return 0;
		}
		p.sendSystemMessage(Component.literal("[Server: Made " + p.getName().getString() + " a server operator]").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		sent++;
		ctx.getSource().sendSuccess(() -> Msg.ok("Sent the fake operator message to " + p.getName().getString() + " only."), false);
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> ctx, String who) {
		int removed = 0;
		for (Iterator<Job> it = JOBS.iterator(); it.hasNext();) {
			Job j = it.next();
			if (who == null || j.who.equalsIgnoreCase(who)) {
				it.remove();
				removed++;
			}
		}
		int count = removed;
		ctx.getSource().sendSuccess(() -> Msg.ok(count == 0 ? "Nothing was running." : "Stopped " + count + " prank" + (count == 1 ? "" : "s") + "."), false);
		return removed;
	}

	private static int pranksSetting(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (on != null) Effects.setPranks(p.getUUID(), on);
		boolean now = Effects.pranks(p.getUUID());
		p.sendSystemMessage(Msg.info("Prank sounds and scares from the admins are " + (now ? "ON" : "OFF") + " for you. /pranks " + (now ? "off" : "on") + " to change it."));
		return 1;
	}

	private static String ids() {
		List<String> out = new ArrayList<>();
		for (MemeSounds.Meme m : MemeSounds.all()) out.add(m.id());
		return String.join(", ", out);
	}

	// ---------------------------------------------------------------- the things that keep going

	private static void tick(MinecraftServer server) {
		now++;
		if (JOBS.isEmpty()) return;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (Iterator<Job> it = JOBS.iterator(); it.hasNext();) {
			Job j = it.next();
			if (now >= j.endTick) {
				it.remove();
				continue;
			}
			if (now < j.nextTick) continue;
			List<ServerPlayer> to = targets(server, j.who);
			if (to == null) {
				it.remove(); // the player left
				continue;
			}
			if (j.kind == Kind.SPAM) {
				// a new silly sound every half second, each one a little higher or lower so it never gets comfortable
				playMeme(server, j.who, to, j.meme != null ? j.meme : MemeSounds.random(), 0.75f + rnd.nextFloat() * 0.6f);
				j.nextTick = now + 8 + rnd.nextInt(8);
			} else {
				for (ServerPlayer p : to) {
					// a spooky sound a few steps away, in a random direction (and a bit behind, more often than not)
					double angle = rnd.nextDouble() * Math.PI * 2;
					double dist = 3 + rnd.nextDouble() * 6;
					send(p, vanilla(SPOOKY[rnd.nextInt(SPOOKY.length)]), p.getX() + Math.cos(angle) * dist, p.getY(), p.getZ() + Math.sin(angle) * dist, 1.0f, 0.8f + rnd.nextFloat() * 0.4f);
				}
				j.nextTick = now + 20 + rnd.nextInt(30);
			}
		}
	}
}
