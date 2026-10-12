package com.pvpduels.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.util.Msg;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Builder rank: only Builders (and admins) can make custom arenas. The rank is earned by placing 1st, 2nd or 3rd in
 * the weekly building contest and lasts three weeks. An admin can also give or take it with /builder.
 * Saved in pvpduels/builders.json as player id -> the moment (milliseconds) the rank ends.
 */
public final class BuilderRank {
	/** How long the rank lasts when it is won. */
	public static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;
	public static final int DEFAULT_WEEKS = 3;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	private static final Map<String, Long> UNTIL = new HashMap<>();
	private static boolean loaded;
	/** For the self-test: a fake clock. 0 means the real one. */
	private static long fakeNow;

	private BuilderRank() {}

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("pvpduels").resolve("builders.json");
	}

	private static long now() {
		return fakeNow != 0 ? fakeNow : System.currentTimeMillis();
	}

	public static void setClockForTest(long millis) {
		fakeNow = millis;
	}

	private static synchronized void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		try {
			if (!Files.exists(file())) return;
			Map<String, Long> read = GSON.fromJson(Files.readString(file()), new TypeToken<Map<String, Long>>() {}.getType());
			if (read != null) UNTIL.putAll(read);
		} catch (Exception e) {
			PvpDuelsMod.LOGGER.warn("Could not read the Builder rank file: {}", e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(UNTIL));
		} catch (IOException e) {
			PvpDuelsMod.LOGGER.warn("Could not save the Builder rank file: {}", e.toString());
		}
	}

	/** For the self-test: forget everything. */
	public static synchronized void resetForTest() {
		UNTIL.clear();
		loaded = true;
		fakeNow = 0;
		try {
			Files.deleteIfExists(file());
		} catch (IOException ignored) {
			// the memory is what the test needs
		}
	}

	public static boolean isAdmin(ServerPlayer p) {
		return IS_ADMIN.test(p.createCommandSourceStack());
	}

	/** True while the rank is running. */
	public static synchronized boolean hasRank(UUID id) {
		ensureLoaded();
		Long until = UNTIL.get(id.toString());
		return until != null && until > now();
	}

	/** True for Builders and for admins. */
	public static boolean can(ServerPlayer p) {
		return isAdmin(p) || hasRank(p.getUUID());
	}

	/** When the rank ends (milliseconds), or 0 when the player never had it or it is over. */
	public static synchronized long until(UUID id) {
		ensureLoaded();
		Long until = UNTIL.get(id.toString());
		return until != null && until > now() ? until : 0;
	}

	/** Gives the rank for this many weeks from now; a rank that is still running is extended instead. */
	public static synchronized void grant(UUID id, int weeks) {
		ensureLoaded();
		long start = Math.max(now(), UNTIL.getOrDefault(id.toString(), 0L));
		UNTIL.put(id.toString(), start + weeks * WEEK_MILLIS);
		save();
	}

	public static synchronized void revoke(UUID id) {
		ensureLoaded();
		if (UNTIL.remove(id.toString()) != null) save();
	}

	/** "3 days left", "5 hours left" and so on. */
	public static String timeLeft(UUID id) {
		long left = until(id) - now();
		if (left <= 0) return "over";
		long days = left / (24L * 60 * 60 * 1000);
		if (days >= 1) return days + (days == 1 ? " day left" : " days left");
		long hours = Math.max(1, left / (60L * 60 * 1000));
		return hours + (hours == 1 ? " hour left" : " hours left");
	}

	// ------------------------------------------------------------------ commands

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("builder")
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				if (hasRank(p.getUUID())) {
					ctx.getSource().sendSuccess(() -> Msg.ok("You are a Builder (" + timeLeft(p.getUUID()) + "). /createarena opens the arena builder."), false);
				} else if (isAdmin(p)) {
					ctx.getSource().sendSuccess(() -> Msg.info("You are an admin, so you can build arenas without the Builder rank."), false);
				} else {
					ctx.getSource().sendSuccess(() -> Msg.info("You don't have the Builder rank. It is won by placing 1st, 2nd or 3rd in the weekly building contest and lasts "
						+ DEFAULT_WEEKS + " weeks."), false);
				}
				return 1;
			})
			.then(Commands.literal("give")
				.requires(IS_ADMIN)
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), DEFAULT_WEEKS))
					.then(Commands.argument("weeks", IntegerArgumentType.integer(1, 52))
						.executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "weeks"))))))
			.then(Commands.literal("remove")
				.requires(IS_ADMIN)
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> {
						ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
						revoke(target.getUUID());
						ctx.getSource().sendSuccess(() -> Msg.ok(target.getName().getString() + " is no longer a Builder."), true);
						target.sendSystemMessage(Msg.info("Your Builder rank was removed."));
						return 1;
					}))));
	}

	private static int give(CommandSourceStack source, ServerPlayer target, int weeks) {
		grant(target.getUUID(), weeks);
		source.sendSuccess(() -> Msg.ok(target.getName().getString() + " is a Builder for " + weeks + (weeks == 1 ? " more week." : " more weeks.")), true);
		target.sendSystemMessage(Msg.ok("You are a Builder now (" + timeLeft(target.getUUID()) + "). Type /createarena to make your own arenas."));
		return 1;
	}
}
