package com.pvpduels.kit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.duel.DuelManager;
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

	private static final SuggestionProvider<CommandSourceStack> LAYOUTS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(LayoutStore.profileNames(p.getUUID()), builder);
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
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(ctx -> save(ctx, false, null, 0))
					// /kit save <name> layout [layout] [priority]: the kit and its layout in one go
					.then(Commands.literal("layout").executes(ctx -> save(ctx, true, null, 0))
						.then(Commands.argument("layout", StringArgumentType.word()).suggests(LAYOUTS)
							.executes(ctx -> save(ctx, true, StringArgumentType.getString(ctx, "layout"), 0))
							.then(Commands.argument("priority", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, LayoutStore.MAX_LEVELS))
								.executes(ctx -> save(ctx, true, StringArgumentType.getString(ctx, "layout"),
									com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "priority"))))))))
			.then(Commands.literal("load")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::load)))
			.then(Commands.literal("show")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::show)))
			.then(Commands.literal("delete")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS).executes(KitCommands::delete)))
			.then(Commands.literal("list").executes(KitCommands::list))
			.then(Commands.literal("key")
				.then(Commands.argument("key", StringArgumentType.word()).suggests(KEY_LETTERS)
					.then(Commands.argument("kit", StringArgumentType.word()).suggests(KITS_OR_NONE)
						.executes(KitCommands::setKey))))
			.then(Commands.literal("keys").executes(KitCommands::showKeys))
			// /kit layout <kit> [layout] [priority]: a layout straight from a saved kit, nothing needs to be in your inventory
			.then(Commands.literal("layout")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS)
					.executes(ctx -> com.pvpduels.layout.LayoutCommands.fromKit(ctx, "name", null, 0))
					.then(Commands.argument("layout", StringArgumentType.word()).suggests(LAYOUTS)
						.executes(ctx -> com.pvpduels.layout.LayoutCommands.fromKit(ctx, "name", StringArgumentType.getString(ctx, "layout"), 0))
						.then(Commands.argument("priority", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, LayoutStore.MAX_LEVELS))
							.executes(ctx -> com.pvpduels.layout.LayoutCommands.fromKit(ctx, "name", StringArgumentType.getString(ctx, "layout"),
								com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "priority")))))))
			.then(Commands.literal("profile")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(KITS)
					.then(Commands.argument("profile", StringArgumentType.word()).suggests(PROFILES)
						.executes(KitCommands::setProfile)))));
	}

	/** "You don't have a kit named X" plus the kits you do have, or how to save the first one. */
	private static net.minecraft.network.chat.Component missingKit(ServerPlayer p, String name) {
		List<String> names = KitStore.list(p.getUUID());
		if (names.isEmpty()) {
			return Msg.err("You don't have a kit named '" + name + "' - you have no saved kits yet. Set up your inventory and run /kit save "
				+ name + " first. (Kits folder: " + KitStore.folder(p.getUUID()) + ")");
		}
		return Msg.err("You don't have a kit named '" + name + "'. Your kits: " + String.join(", ", names));
	}

	private static String kitName(CommandContext<CommandSourceStack> ctx) {
		return StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
	}

	/** /kit save <name> [layout [layout] [priority]]: with "layout" the same inventory is also copied into a layout and the kit is linked to it. */
	private static int save(CommandContext<CommandSourceStack> ctx, boolean withLayout, String layoutArg, int priorityArg) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		if (!KitStore.validName(name)) {
			ctx.getSource().sendFailure(Msg.err("Kit names can only use a-z, 0-9, _ and - (max 32)."));
			return 0;
		}
		if (KitStore.isLocked(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("Kit '" + name + "' was given to you and is locked: save your inventory under another name."));
			return 0;
		}
		com.pvpduels.duel.LobbyKit.strip(p); // the lobby hotbar items are not part of a kit
		if (KitStore.stacksInInventory(p.getInventory()) == 0) {
			// Never write a blank kit (and never replace a good one with a blank one). Show the player what the server has.
			KitStore.resync(p);
			ctx.getSource().sendFailure(Msg.err("Your inventory is empty (that is what the server sees: " + KitStore.describe(p.getInventory()) + "), so nothing was saved"
				+ (KitStore.exists(p.getUUID(), name) ? " and kit '" + name + "' stays as it was" : "") + ". Put your items in first, then run /kit save " + name + " again."
				+ " (To make a layout from a kit you already saved, without loading it: /kit layout <kit> [layout] [priority].)"));
			return 0;
		}
		try {
			boolean existed = KitStore.exists(p.getUUID(), name);
			KitStore.SaveReport report = KitStore.save(ctx.getSource().getServer(), p, name);
			com.pvpduels.achievements.Achievements.max(p.getUUID(), p.getName().getString(), "kits_saved", KitStore.list(p.getUUID()).size());
			ctx.getSource().sendSuccess(() -> Msg.ok((existed ? "Overwrote kit '" : "Saved kit '") + name + "' from your inventory: "
				+ report.stacks() + " item stack" + (report.stacks() == 1 ? "" : "s") + " in the file. See it with /kit show " + name + "."), false);
			if (!report.warnings().isEmpty()) {
				ctx.getSource().sendSuccess(() -> Msg.err("Careful, " + report.warnings().size() + " slot" + (report.warnings().size() == 1 ? " was" : "s were")
					+ " not saved exactly:"), false);
				for (String w : report.warnings()) ctx.getSource().sendSuccess(() -> Msg.err("  " + w), false);
			}
			if (withLayout) {
				// the second bird: the same inventory goes into a layout, and the kit remembers it
				com.pvpduels.layout.LayoutCommands.CopyResult copy = com.pvpduels.layout.LayoutCommands.copyInventory(p, layoutArg, priorityArg);
				if (copy.ok()) {
					KitStore.setProfile(p.getUUID(), name, copy.layout());
					ctx.getSource().sendSuccess(() -> Msg.ok("And the same inventory went into " + (copy.created() ? "new layout '" : "layout '") + copy.layout()
						+ "' at priority " + copy.priority() + " (" + copy.items() + " items; armor and offhand are not in layouts). Kit '" + name
						+ "' will be sorted with it when loaded."), false);
				} else {
					ctx.getSource().sendSuccess(() -> Msg.err("The kit is saved, but no layout was made: " + copy.error()), false);
				}
			} else {
				ctx.getSource().sendSuccess(() -> Msg.button("[+ Also make a layout from this]", net.minecraft.ChatFormatting.AQUA,
					"/kit save " + name + " layout", "Saves this kit again and copies the same inventory into your active layout (next free priority)"), false);
			}
			return 1;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Msg.err("Could not save the kit: " + e));
			return 0;
		}
	}

	/** /kit show <name>: opens the kit as a read-only chest, so you can see what is really saved in it. */
	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		try {
			Optional<KitStore.Kit> kit = KitStore.read(ctx.getSource().getServer(), p.getUUID(), name);
			if (kit.isEmpty()) {
				ctx.getSource().sendFailure(missingKit(p, name));
				return 0;
			}
			com.pvpduels.duel.KitView.open(p, name, kit.get().slots());
			int stacks = kit.get().stacks();
			ctx.getSource().sendSuccess(() -> Msg.info("Kit '" + name + "' has " + stacks + " item stack" + (stacks == 1 ? "" : "s")
				+ (stacks == 0 ? " - it is EMPTY." : ".")), false);
			for (String problem : kit.get().problems()) ctx.getSource().sendSuccess(() -> Msg.err("  " + problem), false);
			return 1;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Msg.err("Could not read the kit: " + e));
			return 0;
		}
	}

	private static int load(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		return loadKit(ctx.getSource().getServer(), p, kitName(ctx)) ? 1 : 0;
	}

	/** Loads a kit into the player's inventory (used by /kit load and the Quick kit keys). */
	public static boolean loadKit(MinecraftServer server, ServerPlayer p, String name) {
		if (DuelManager.isInDuel(p.getUUID())) {
			p.sendSystemMessage(Msg.err("You can't change kits during a duel."));
			return false;
		}
		try {
			Optional<KitStore.Kit> kit = KitStore.read(server, p.getUUID(), name);
			if (kit.isEmpty()) {
				p.sendSystemMessage(missingKit(p, name));
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
			int stacks = kit.get().stacks();
			if (stacks == 0) {
				p.sendSystemMessage(Msg.err("Loaded kit '" + name + "', but it is EMPTY: it was saved with nothing in the inventory. "
					+ "Set your items up and run /kit save " + name + " again."));
			} else {
				p.sendSystemMessage(Msg.ok("Loaded kit '" + name + "' (" + stacks + " item stack" + (stacks == 1 ? "" : "s")
					+ "). Your old inventory was replaced."));
			}
			if (!kit.get().problems().isEmpty()) {
				p.sendSystemMessage(Msg.err("Some slots of this kit could not be read exactly:"));
				for (String problem : kit.get().problems()) p.sendSystemMessage(Msg.err("  " + problem));
			}
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
			ctx.getSource().sendFailure(missingKit(p, kit));
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
			String folder = KitStore.folder(p.getUUID()).toString();
			ctx.getSource().sendSuccess(() -> Msg.info("You have no kits yet. Set up your inventory and use /kit save <name>. (Kits folder: " + folder + ")"), false);
		} else {
			List<String> shown = new java.util.ArrayList<>();
			for (String n : names) shown.add(KitStore.isLocked(p.getUUID(), n) ? n + " (given, locked)" : n);
			String text = String.join(", ", shown);
			ctx.getSource().sendSuccess(() -> Msg.info("Your kits (" + names.size() + "): " + text), false);
		}
		return 1;
	}

	private static int setProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = kitName(ctx);
		String profile = StringArgumentType.getString(ctx, "profile").toLowerCase(Locale.ROOT);
		if (!KitStore.exists(p.getUUID(), name)) {
			ctx.getSource().sendFailure(missingKit(p, name));
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
