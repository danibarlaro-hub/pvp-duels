package com.pvpduels.layout;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.util.Msg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LayoutCommands {
	private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");

	private static final SuggestionProvider<CommandSourceStack> PROFILES = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(LayoutStore.profileNames(p.getUUID()), builder);
	};

	private LayoutCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("layout")
			.then(Commands.literal("slot")
				.then(Commands.argument("slot", IntegerArgumentType.integer(1, 9))
					.then(Commands.argument("rules", StringArgumentType.greedyString())
						.executes(LayoutCommands::setSlot))))
			.then(Commands.literal("clear")
				.then(Commands.literal("all").executes(LayoutCommands::clearAll))
				.then(Commands.argument("slot", IntegerArgumentType.integer(1, 9)).executes(LayoutCommands::clearSlot)))
			.then(Commands.literal("show").executes(LayoutCommands::show))
			.then(Commands.literal("list").executes(LayoutCommands::list))
			.then(Commands.literal("new")
				.then(Commands.argument("name", StringArgumentType.word()).executes(LayoutCommands::newProfile)))
			.then(Commands.literal("use")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(PROFILES).executes(LayoutCommands::use)))
			.then(Commands.literal("delete")
				.then(Commands.argument("name", StringArgumentType.word()).suggests(PROFILES).executes(LayoutCommands::delete)))
			.then(Commands.literal("sort").executes(ctx -> {
				HotbarSorter.sortAndReport(ctx.getSource().getPlayerOrException());
				return 1;
			})));
	}

	private static void saveQuietly(ServerPlayer p, CommandContext<CommandSourceStack> ctx) {
		try {
			LayoutStore.save(p.getUUID());
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not save your layout file: " + e.getMessage()));
		}
	}

	private static int setSlot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		String raw = StringArgumentType.getString(ctx, "rules").trim().toLowerCase(Locale.ROOT);

		List<String> rules = new ArrayList<>();
		for (String token : raw.split("\\s+")) {
			if (token.equals("hand")) {
				ItemStack held = p.getMainHandItem();
				if (held.isEmpty()) {
					ctx.getSource().sendFailure(Msg.err("Your hand is empty, so 'hand' can't be used."));
					return 0;
				}
				rules.add(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
				continue;
			}
			String rule = ItemMatcher.normalize(token);
			if (rule == null) {
				ctx.getSource().sendFailure(Msg.err("Unknown item or category: " + token
					+ ". Categories: " + String.join(", ", ItemMatcher.CATEGORIES.stream().sorted().toList())));
				return 0;
			}
			if (!rules.contains(rule)) rules.add(rule);
		}

		LayoutStore.active(p.getUUID()).slots.put(slot, rules);
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Slot " + slot + ": " + String.join(" > ", rules)), false);
		return 1;
	}

	private static int clearSlot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		LayoutStore.active(p.getUUID()).slots.remove(slot);
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Cleared slot " + slot + "."), false);
		return 1;
	}

	private static int clearAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.active(p.getUUID()).slots.clear();
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Cleared all slots of the active profile."), false);
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		LayoutStore.Profile profile = data.profiles.get(data.active);
		ctx.getSource().sendSuccess(() -> Msg.title("Layout profile: " + data.active), false);
		boolean any = false;
		for (int slot = 1; slot <= 9; slot++) {
			List<String> rules = profile.slots.get(slot);
			if (rules != null && !rules.isEmpty()) {
				any = true;
				String line = "  " + slot + ": " + String.join(" > ", rules);
				ctx.getSource().sendSuccess(() -> Msg.info(line), false);
			}
		}
		if (!any) {
			ctx.getSource().sendSuccess(() -> Msg.info("  (no rules yet)  Try: /layout slot 1 sword spear"), false);
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		StringBuilder sb = new StringBuilder();
		for (String name : data.profiles.keySet()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(name.equals(data.active) ? name + " (active)" : name);
		}
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Msg.info("Profiles: " + text), false);
		return 1;
	}

	private static int newProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			ctx.getSource().sendFailure(Msg.err("Profile names can only use a-z, 0-9, _ and - (max 24)."));
			return 0;
		}
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (data.profiles.containsKey(name)) {
			ctx.getSource().sendFailure(Msg.err("A profile named '" + name + "' already exists."));
			return 0;
		}
		data.profiles.put(name, new LayoutStore.Profile());
		data.active = name;
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Created profile '" + name + "' and made it active."), false);
		return 1;
	}

	private static int use(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (!data.profiles.containsKey(name)) {
			ctx.getSource().sendFailure(Msg.err("No profile named '" + name + "'. Use /layout list."));
			return 0;
		}
		data.active = name;
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Active profile: " + name), false);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
		LayoutStore.Data data = LayoutStore.get(p.getUUID());
		if (name.equals("default")) {
			ctx.getSource().sendFailure(Msg.err("The 'default' profile can't be deleted (use /layout clear all to empty it)."));
			return 0;
		}
		if (data.profiles.remove(name) == null) {
			ctx.getSource().sendFailure(Msg.err("No profile named '" + name + "'."));
			return 0;
		}
		if (data.active.equals(name)) data.active = "default";
		saveQuietly(p, ctx);
		ctx.getSource().sendSuccess(() -> Msg.ok("Deleted profile '" + name + "'."), false);
		return 1;
	}
}
