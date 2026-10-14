package com.pvpduels.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.PvpDuelsMod;
import com.pvpduels.duel.DuelManager;
import com.pvpduels.duel.Queue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Homes for one player only: /sethome [name], /home [name], /delhome &lt;name&gt;, /homes. Nobody else has these commands (they do not even
 * show up for them). Going to a home says nothing in the chat, to nobody. The homes are saved in the world folder (pvpduels/homes.json).
 */
public final class Homes {
	/** The only player who has homes. */
	public static final String OWNER = "dibitss";
	static final int MAX = 50;
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Home(String dim, double x, double y, double z, float yaw, float pitch) {}

	private static final Map<String, Home> HOMES = new TreeMap<>();
	private static Path file;

	private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> SharedSuggestionProvider.suggest(HOMES.keySet(), builder);

	private Homes() {}

	/** Reads the homes of each world when its server starts. */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(Homes::load);
	}

	public static boolean allowed(ServerPlayer p) {
		return p != null && p.getName().getString().equalsIgnoreCase(OWNER);
	}

	private static boolean allowed(CommandSourceStack source) {
		return source.getEntity() instanceof ServerPlayer p && allowed(p);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("sethome").requires(Homes::allowed)
			.executes(ctx -> set(ctx, "home"))
			.then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "name")))));
		dispatcher.register(Commands.literal("home").requires(Homes::allowed)
			.executes(ctx -> go(ctx, null))
			.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(ctx -> go(ctx, StringArgumentType.getString(ctx, "name")))));
		dispatcher.register(Commands.literal("delhome").requires(Homes::allowed)
			.then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(Homes::delete)));
		dispatcher.register(Commands.literal("homes").requires(Homes::allowed).executes(Homes::list));
	}

	// ---------------------------------------------------------------- the file

	/** Reads the homes of the world the server just opened (called when the server starts). */
	public static void load(MinecraftServer server) {
		HOMES.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("pvpduels").resolve("homes.json");
		if (!Files.exists(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (String name : root.keySet()) {
				JsonObject h = root.getAsJsonObject(name);
				HOMES.put(name, new Home(h.get("dim").getAsString(), h.get("x").getAsDouble(), h.get("y").getAsDouble(), h.get("z").getAsDouble(),
					h.get("yaw").getAsFloat(), h.get("pitch").getAsFloat()));
			}
		} catch (IOException | RuntimeException e) {
			PvpDuelsMod.LOGGER.warn("Could not read the homes file {}: {}", file, e.toString());
		}
	}

	private static void save() throws IOException {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, Home> e : HOMES.entrySet()) {
			Home h = e.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("dim", h.dim());
			o.addProperty("x", h.x());
			o.addProperty("y", h.y());
			o.addProperty("z", h.z());
			o.addProperty("yaw", h.yaw());
			o.addProperty("pitch", h.pitch());
			root.add(e.getKey(), o);
		}
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
	}

	/** The homes by name, for the self-test. */
	public static List<String> names() {
		return new ArrayList<>(HOMES.keySet());
	}

	// ---------------------------------------------------------------- the commands

	/** Why the player can't use homes right now, or null. Homes are not for the middle of a duel, a queue, the workshop or the arena worlds. */
	private static String blocked(ServerPlayer p) {
		if (DuelManager.isBusy(p.getUUID()) || DuelManager.isInDuel(p.getUUID()) || Queue.isQueued(p.getUUID())) {
			return "Not now: you are in a duel, a queue, the workshop or a film. Use /leave first.";
		}
		if (p.level().dimension().identifier().getNamespace().equals(PvpDuelsMod.MOD_ID)) {
			return "Homes don't work in the arena worlds.";
		}
		return null;
	}

	private static int set(CommandContext<CommandSourceStack> ctx, String raw) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = raw.toLowerCase(Locale.ROOT);
		String problem = blocked(p);
		if (problem == null && !NAME.matcher(name).matches()) problem = "Home names can only use a-z, 0-9, _ and - (max 24).";
		if (problem == null && !HOMES.containsKey(name) && HOMES.size() >= MAX) problem = "You already have " + MAX + " homes (the maximum). /delhome one first.";
		if (problem != null) {
			p.sendSystemMessage(Msg.err(problem));
			return 0;
		}
		HOMES.put(name, new Home(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
		try {
			save();
		} catch (IOException e) {
			p.sendSystemMessage(Msg.err("The home is set but the file could not be saved: " + e.getMessage()));
			return 0;
		}
		p.sendSystemMessage(Msg.ok("Home '" + name + "' set here."));
		return 1;
	}

	/** /home [name]: goes there. No message to anybody; a message only appears when something is wrong, and only to you. */
	private static int go(CommandContext<CommandSourceStack> ctx, String raw) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String problem = blocked(p);
		String name = raw == null ? null : raw.toLowerCase(Locale.ROOT);
		if (problem == null && name == null) {
			if (HOMES.containsKey("home")) name = "home";
			else if (HOMES.size() == 1) name = HOMES.keySet().iterator().next();
			else problem = HOMES.isEmpty() ? "You have no homes yet: /sethome [name]." : "Which one? /home <name>. Your homes: " + String.join(", ", HOMES.keySet());
		}
		Home home = problem == null ? HOMES.get(name) : null;
		if (problem == null && home == null) problem = "No home named '" + name + "'. Your homes: " + (HOMES.isEmpty() ? "none" : String.join(", ", HOMES.keySet()));
		ServerLevel level = null;
		if (problem == null) {
			level = p.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(home.dim())));
			if (level == null) problem = "That home is in a world that is not loaded.";
		}
		if (problem != null) {
			p.sendSystemMessage(Msg.err(problem));
			return 0;
		}
		p.stopRiding();
		p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		p.fallDistance = 0;
		p.teleportTo(level, home.x(), home.y(), home.z(), java.util.Set.of(), home.yaw(), home.pitch(), true);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		if (HOMES.remove(name) == null) {
			p.sendSystemMessage(Msg.err("No home named '" + name + "'."));
			return 0;
		}
		try {
			save();
		} catch (IOException e) {
			p.sendSystemMessage(Msg.err("The file could not be saved: " + e.getMessage()));
			return 0;
		}
		p.sendSystemMessage(Msg.ok("Home '" + name + "' deleted."));
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		p.sendSystemMessage(Msg.info(HOMES.isEmpty() ? "You have no homes yet: /sethome [name]." : "Your homes (" + HOMES.size() + "): " + String.join(", ", HOMES.keySet())));
		return 1;
	}
}
