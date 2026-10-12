package com.pvpduels.kit;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Sharing kits: /publicate puts a kit on the public shelf (/kit public lists it, /kit take copies it),
 * /private gives a locked copy to one player, /unpublish takes a kit off the shelf.
 */
public final class ShareCommands {
	private static final Predicate<CommandSourceStack> IS_ADMIN = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private static final SuggestionProvider<CommandSourceStack> OWN_KITS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		return SharedSuggestionProvider.suggest(KitStore.list(p.getUUID()), builder);
	};
	private static final SuggestionProvider<CommandSourceStack> PUBLIC_KITS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(PublicKits.names(), builder);
	private static final SuggestionProvider<CommandSourceStack> MY_PUBLIC_KITS = (ctx, builder) -> {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) return builder.buildFuture();
		List<String> mine = PublicKits.list().stream()
			.filter(e -> e.owner().equals(p.getUUID()) || IS_ADMIN.test(ctx.getSource())).map(PublicKits.Entry::name).toList();
		return SharedSuggestionProvider.suggest(mine, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> ONLINE = (ctx, builder) ->
		SharedSuggestionProvider.suggest(Arrays.asList(ctx.getSource().getServer().getPlayerList().getPlayerNamesArray()), builder);

	private ShareCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("publicate")
			.then(Commands.argument("kit", StringArgumentType.word()).suggests(OWN_KITS).executes(ShareCommands::publicate)));
		dispatcher.register(Commands.literal("unpublish")
			.then(Commands.argument("kit", StringArgumentType.word()).suggests(MY_PUBLIC_KITS).executes(ShareCommands::unpublish)));
		dispatcher.register(Commands.literal("private")
			.then(Commands.argument("kit", StringArgumentType.word()).suggests(OWN_KITS)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(ONLINE).executes(ShareCommands::givePrivate))));
		dispatcher.register(Commands.literal("kit")
			.then(Commands.literal("public").executes(ShareCommands::listPublic))
			.then(Commands.literal("take")
				.then(Commands.argument("kit", StringArgumentType.word()).suggests(PUBLIC_KITS).executes(ShareCommands::take))));
	}

	private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
		return StringArgumentType.getString(ctx, name).toLowerCase(Locale.ROOT);
	}

	private static Component button(String label, ChatFormatting color, String command, String hover) {
		return Component.literal(label).withStyle(style -> style.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	// ------------------------------------------------------------------ /publicate

	private static int publicate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		String name = arg(ctx, "kit");
		if (!KitStore.exists(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("You don't have a kit named '" + name + "'. Your kits: " + String.join(", ", KitStore.list(p.getUUID()))));
			return 0;
		}
		if (KitStore.isLocked(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("Kit '" + name + "' was given to you and is locked: you can't share it."));
			return 0;
		}
		var existing = PublicKits.find(name);
		if (existing.isPresent() && !existing.get().owner().equals(p.getUUID())) {
			ctx.getSource().sendFailure(Msg.err("There is already a public kit named '" + name + "' by " + existing.get().ownerName()
				+ ". Save yours under another name first."));
			return 0;
		}
		try {
			PublicKits.publish(p.getUUID(), p.getName().getString(), name, KitStore.readRaw(p.getUUID(), name));
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Msg.err("Could not publish the kit: " + e.getMessage()));
			return 0;
		}
		boolean update = existing.isPresent();
		ctx.getSource().sendSuccess(() -> Msg.ok((update ? "Updated the public kit '" : "Kit '") + name + (update ? "'." : "' is now public.")
			+ " Everybody can copy it with /kit take " + name + ". Use /unpublish " + name + " to take it back."), false);
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(p.getUUID())) continue;
			MutableComponent line = Component.literal(p.getName().getString()).withStyle(ChatFormatting.GOLD)
				.append(Component.literal(" shared the kit '" + name + "'  ").withStyle(ChatFormatting.YELLOW))
				.append(button("[TAKE]", ChatFormatting.GREEN, "/kit take " + name, "Copy this kit to your own kits"));
			other.sendSystemMessage(line);
		}
		return 1;
	}

	// ------------------------------------------------------------------ /unpublish

	private static int unpublish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = arg(ctx, "kit");
		var entry = PublicKits.find(name);
		if (entry.isEmpty()) {
			ctx.getSource().sendFailure(Msg.err("There is no public kit named '" + name + "'. See /kit public."));
			return 0;
		}
		if (!entry.get().owner().equals(p.getUUID()) && !IS_ADMIN.test(ctx.getSource())) {
			ctx.getSource().sendFailure(Msg.err("Only " + entry.get().ownerName() + " can unpublish '" + name + "'."));
			return 0;
		}
		try {
			PublicKits.unpublish(name);
		} catch (IOException e) {
			ctx.getSource().sendFailure(Msg.err("Could not unpublish the kit: " + e.getMessage()));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Msg.ok("Kit '" + name + "' is not public any more. Copies people already took stay with them."), false);
		return 1;
	}

	// ------------------------------------------------------------------ /private

	private static int givePrivate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = arg(ctx, "kit");
		String targetName = StringArgumentType.getString(ctx, "player");
		ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(targetName);
		if (target == null) {
			ctx.getSource().sendFailure(Msg.err("No player named '" + targetName + "' is online."));
			return 0;
		}
		if (target.getUUID().equals(p.getUUID())) {
			ctx.getSource().sendFailure(Msg.err("That is you."));
			return 0;
		}
		if (!KitStore.exists(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("You don't have a kit named '" + name + "'. Your kits: " + String.join(", ", KitStore.list(p.getUUID()))));
			return 0;
		}
		if (KitStore.isLocked(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("Kit '" + name + "' was given to you and is locked: you can't give it away."));
			return 0;
		}
		try {
			JsonObject root = KitStore.readRaw(p.getUUID(), name);
			root.remove("profile");
			root.addProperty("locked", true);
			root.addProperty("from", p.getName().getString());
			String saveAs = name;
			int n = 2;
			while (KitStore.exists(target.getUUID(), saveAs)) {
				// Never overwrite one of their own kits.
				saveAs = name + "-" + n++;
				if (saveAs.length() > 32) saveAs = saveAs.substring(saveAs.length() - 32);
			}
			root.addProperty("name", saveAs);
			KitStore.writeRaw(target.getUUID(), saveAs, root);
			String finalName = saveAs;
			String targetShown = target.getName().getString();
			ctx.getSource().sendSuccess(() -> Msg.ok("Gave " + targetShown + " a locked copy of '" + name + "' (saved as '" + finalName
				+ "' for them). They can use it but not share or edit it."), false);
			target.sendSystemMessage(Msg.ok(p.getName().getString() + " gave you the kit '" + finalName + "'. Use /kit load " + finalName
				+ " or pick it in /duel. It is locked: you can use it but not share or edit it."));
			return 1;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Msg.err("Could not give the kit: " + e.getMessage()));
			return 0;
		}
	}

	// ------------------------------------------------------------------ /kit public, /kit take

	private static int listPublic(CommandContext<CommandSourceStack> ctx) {
		List<PublicKits.Entry> entries = PublicKits.list();
		if (entries.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Msg.info("There are no public kits yet. Share one with /publicate <kit>."), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> Msg.title("Public kits (" + entries.size() + ")"), false);
		for (PublicKits.Entry e : entries) {
			MutableComponent line = Component.literal("  " + e.name()).withStyle(ChatFormatting.AQUA)
				.append(Component.literal("  by " + e.ownerName() + "  ").withStyle(ChatFormatting.GRAY))
				.append(button("[TAKE]", ChatFormatting.GREEN, "/kit take " + e.name(), "Copy this kit to your own kits"));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return 1;
	}

	private static int take(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = arg(ctx, "kit");
		var entry = PublicKits.find(name);
		if (entry.isEmpty()) {
			ctx.getSource().sendFailure(Msg.err("There is no public kit named '" + name + "'. See /kit public."));
			return 0;
		}
		if (KitStore.exists(p.getUUID(), name)) {
			ctx.getSource().sendFailure(Msg.err("You already have a kit named '" + name + "'. Rename or delete yours first."));
			return 0;
		}
		try {
			JsonObject root = PublicKits.raw(name);
			root.remove("owner");
			root.remove("ownerName");
			KitStore.writeRaw(p.getUUID(), name, root);
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Msg.err("Could not copy the kit: " + e.getMessage()));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Msg.ok("Copied the public kit '" + name + "' by " + entry.get().ownerName()
			+ " to your kits. It is yours now: /kit load " + name + "."), false);
		return 1;
	}
}
