package com.pvpduels.kit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.layout.InventorySorter;
import com.pvpduels.layout.LayoutStore;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class KitCommands {
	private static final SuggestionProvider<CommandSourceStack> KITS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(KitStore.list(p.getUUID()), builder);
	};

	private static final SuggestionProvider<CommandSourceStack> PROFILES = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		List<String> names = new java.util.ArrayList<>(LayoutStore.profileNames(p.getUUID()));
		names.add("none");
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private static final SuggestionProvider<CommandSourceStack> KEY_LETTERS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(List.of("a", "b", "c"), builder);

	private static final SuggestionProvider<CommandSourceStack> KITS_OR_NONE = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		List<String> names = new java.util.ArrayList<>(KitStore.list(p.getUUID()));
		names.add("none");
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private KitCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("kit")
			.then(Commands.literal("save")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::save)))
			.then(Commands.literal("load")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::load)))
			.then(Commands.literal("delete")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::delete)))
			.then(Commands.literal("list").executes(KitCommands::list))
			.then(Commands.literal("key")
				.then(Commands.argument("key", StringArgumentType.word()).suggests(KEY_LETTERS)
					.then(Commands.argument("kit", StringArgumentType.word()).suggests(KITS_OR_NONE)
						.executes(KitCommands::setKey))))
			.then(Commands.literal("keys").executes(KitCommands::showKeys))
			.then(Commands.literal("profile")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS)
					.then(Commands.argument("profile", StringArgumentType.word()).suggests(PROFILES)
						.executes(KitCommands::setProfile)))));
	}

	private static String kitName(CommandContext<CommandSourceStack> ctx) {
		return StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
	}

	private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		if (!KitStore.validName(name)) {
			ctx.getSource().sendFailure(Msg.err("Kit names can only use a-z, 0-9, _ and - (max 32)."));
			return 0;
		}
		try {
			boolean existed = KitStore.exists(p.getUUID(), name);
			KitStore.save(ctx.getSource().getServer(), p, name);
			ctx.getSource().sendSuccess(() -> Msg.ok((existed ? "Overwrote kit '" : "Saved kit '") + name + "' from your inventory."), false);
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not save the kit: " + e.getMessage()));
			return 0;
		}
	}

	private static int load(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		return loadKit(ctx.getSource().getServer(), p, kitName(ctx)) ? 1 : 0;
	}

	/** Loads a kit into the player's inventory (used by /kit load and the Quick kit keys). */
	public static boolean loadKit(MinecraftServer server, ServerPlayer p, String name) {
		try {
			Optional<KitStore.Kit> kit = KitStore.read(server, p.getUUID(), name);
			if (kit.isEmpty()) {
				p.sendSystemMessage(Msg.err("You don't have a kit named '" + name + "'. Use /kit list."));
				return false;
			}
			KitStore.give(p, kit.get());

			String profileName = kit.get().profile();
			if (profileName != null) {
				LayoutStore.Profile profile = LayoutStore.get(p.getUUID()).profiles.get(profileName);
				if (profile != null) {
					InventorySorter.sort(p, profile);
				}
			}
			p.sendSystemMessage(Msg.ok("Loaded kit '" + name + "'. Your old inventory was replaced."));
			return true;
		} catch (IOException | RuntimeException e) {
			p.sendSystemMessage(Msg.err("Could not load the kit: " + e.getMessage()));
			return false;
		}
	}

	/** Called by the Quick kit A/B/C keys. */
	public static void quick(MinecraftServer server, ServerPlayer p, int index) {
		if (index < 0 || index >= KitKeys.COUNT) return;
		String name = KitKeys.get(p.getUUID()).get(index);
		if (name.isEmpty()) {
			char letter = KitKeys.letter(index);
			p.sendSystemMessage(Msg.err("Quick kit " + letter + " has no kit yet. Use /kit key "
				+ Character.toLowerCase(letter) + " <kit>."));
			return;
		}
		loadKit(server, p, name);
	}

	private static int setKey(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int index = KitKeys.indexOf(StringArgumentType.getString(ctx, "key"));
		if (index < 0) {
			ctx.getSource().sendFailure(Msg.err("The key must be a, b or c."));
			return 0;
		}
		String kit = StringArgumentType.getString(ctx, "kit").toLowerCase(Locale.ROOT);
		boolean none = kit.equals("none");
		if (!none && !KitStore.exists(p.getUUID(), kit)) {
			ctx.getSource().sendFailure(Msg.err("You don't have a kit named '" + kit + "'. Use /kit list."));
			return 0;
		}
		char letter = KitKeys.letter(index);
		try {
			KitKeys.set(p.getUUID(), index, none ? "" : kit);
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not save the key: " + e.getMessage()));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Msg.ok(none
			? "Quick kit " + letter + " is now empty."
			: "Quick kit " + letter + " will load kit '" + kit + "'."), false);
		return 1;
	}

	private static int showKeys(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		List<String> keys = KitKeys.get(p.getUUID());
		ctx.getSource().sendSuccess(() -> Msg.title("Quick kit keys"), false);
		for (int i = 0; i < KitKeys.COUNT; i++) {
			String kit = keys.get(i);
			String line = "  Quick kit " + KitKeys.letter(i) + " -> " + (kit.isEmpty() ? "(empty)" : "'" + kit + "'");
			ctx.getSource().sendSuccess(() -> Msg.info(line), false);
		}
		ctx.getSource().sendSuccess(() -> Msg.info("Bind the keys in Options > Controls > Key Binds > PvP Duels. Assign kits with /kit key <a|b|c> <kit>."), false);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		try {
			if (KitStore.delete(p.getUUID(), name)) {
				ctx.getSource().sendSuccess(() -> Msg.ok("Deleted kit '" + name + "'."), false);
				return 1;
			}
			ctx.getSource().sendFailure(Msg.err("You don't have a kit named '" + name + "'."));
			return 0;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not delete the kit: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		List<String> names = KitStore.list(p.getUUID());
		if (names.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Msg.info("You have no kits yet. Set up your inventory and use /kit save <name>."), false);
		} else {
			String text = String.join(", ", names);
			ctx.getSource().sendSuccess(() -> Msg.info("Your kits (" + names.size() + "): " + text), false);
		}
		return 1;
	}

	private static int setProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		String profile = StringArgumentType.getString(ctx, "profile").toLowerCase(Locale.ROOT);
		if (!KitStore.exists(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("You don't have a kit named '" + name + "'."));
			return 0;
		}
		boolean none = profile.equals("none");
		if (!none && !LayoutStore.get(p.getUUID()).profiles.containsKey(profile)) {
			ctx.getSource().sendFailure(Msg.err("No layout profile named '" + profile + "'. Use /layout list."));
			return 0;
		}
		try {
			KitStore.setProfile(p.getUUID(), name, none ? null : profile);
			ctx.getSource().sendSuccess(() -> Msg.ok(none
				? "Kit '" + name + "' no longer uses a layout profile."
				: "Kit '" + name + "' will be sorted with profile '" + profile + "' when loaded."), false);
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not update the kit: " + e.getMessage()));
			return 0;
		}
	}
}
