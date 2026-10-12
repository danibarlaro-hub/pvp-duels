package com.pvpduels.arena;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** /arena: look at your arenas and the public ones, share them, edit them and delete them. */
public final class ArenaCommands {
	private ArenaCommands() {}

	private static List<String> own(UUID id) {
		List<String> names = new ArrayList<>(ArenaStore.list(id));
		names.remove(ArenaStudio.DRAFT);
		return names;
	}

	private static boolean sharedByMe(UUID id, String name) {
		Optional<ArenaStore.Entry> entry = ArenaStore.findPublic(name);
		return entry.isPresent() && id.equals(entry.get().owner());
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("arena")
			.executes(ctx -> list(ctx.getSource().getPlayerOrException()))
			.then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getPlayerOrException())))
			.then(Commands.literal("public").executes(ctx -> shelf(ctx.getSource())))
			.then(Commands.literal("publish")
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(own(ctx.getSource().getPlayerOrException().getUUID()), builder))
					.executes(ctx -> publish(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
			.then(Commands.literal("unpublish")
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ArenaStore.publicNames(), builder))
					.executes(ctx -> unpublish(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
			.then(Commands.literal("edit")
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(own(ctx.getSource().getPlayerOrException().getUUID()), builder))
					.executes(ctx -> ArenaStudio.answer(ctx.getSource(), ArenaStudio.enter(ctx.getSource().getPlayerOrException(), null,
						ArenaStore.clean(StringArgumentType.getString(ctx, "name")))))))
			.then(Commands.literal("delete")
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(own(ctx.getSource().getPlayerOrException().getUUID()), builder))
					.executes(ctx -> delete(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
	}

	private static int list(ServerPlayer p) {
		List<String> names = own(p.getUUID());
		if (names.isEmpty()) {
			p.sendSystemMessage(Msg.info("You have no arenas yet. Builders make them with /createarena. /arena public lists the shared ones."));
		} else {
			p.sendSystemMessage(Msg.title("Your arenas (" + names.size() + ")"));
			for (String name : names) {
				boolean shared = sharedByMe(p.getUUID(), name);
				Component line = Component.literal("  " + name + (shared ? "  (public)  " : "  (private)  ")).withStyle(ChatFormatting.WHITE)
					.copy().append(Msg.button("[EDIT]", ChatFormatting.AQUA, "/arena edit " + name, "Open it in the arena studio"))
					.append(Component.literal(" "))
					.append(shared ? Msg.button("[UNPUBLISH]", ChatFormatting.GOLD, "/arena unpublish " + name, "Take it off the public shelf")
						: Msg.button("[PUBLISH]", ChatFormatting.GREEN, "/arena publish " + name, "Share it with everybody"));
				p.sendSystemMessage(line);
			}
		}
		if (ArenaStore.exists(p.getUUID(), ArenaStudio.DRAFT)) p.sendSystemMessage(Msg.info("  You also have a draft: /createarena > Continue my draft."));
		return names.size();
	}

	private static int shelf(CommandSourceStack source) {
		List<ArenaStore.Entry> all = ArenaStore.publicList();
		if (all.isEmpty()) {
			source.sendSuccess(() -> Msg.info("No arena is public yet. A Builder shares one with /arena publish <name>."), false);
			return 0;
		}
		source.sendSuccess(() -> Msg.title("Public arenas (" + all.size() + ")"), false);
		for (ArenaStore.Entry e : all) {
			source.sendSuccess(() -> Msg.info("  " + e.name() + (e.ownerName().isEmpty() ? "" : " by " + e.ownerName())), false);
		}
		return all.size();
	}

	private static int publish(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		try {
			String problem = ArenaStore.publish(p.getUUID(), name);
			if (problem != null) return ArenaStudio.answer(source, problem);
		} catch (IOException e) {
			return ArenaStudio.answer(source, "Could not share the arena: " + e.getMessage());
		}
		source.sendSuccess(() -> Msg.ok("'" + ArenaStore.clean(name) + "' is public now: everybody can pick it when they challenge somebody. /arena unpublish "
			+ ArenaStore.clean(name) + " takes it back."), false);
		return 1;
	}

	private static int unpublish(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		try {
			String problem = ArenaStore.unpublish(p.getUUID(), name, BuilderRank.isAdmin(p));
			if (problem != null) return ArenaStudio.answer(source, problem);
		} catch (IOException e) {
			return ArenaStudio.answer(source, "Could not change the arena: " + e.getMessage());
		}
		source.sendSuccess(() -> Msg.ok("'" + ArenaStore.clean(name) + "' is private again."), false);
		return 1;
	}

	private static int delete(CommandSourceStack source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = source.getPlayerOrException();
		String clean = ArenaStore.clean(name);
		try {
			if (!ArenaStore.exists(p.getUUID(), clean) || ArenaStudio.DRAFT.equals(clean)) return ArenaStudio.answer(source, "You have no arena named '" + clean + "'. See /arena list.");
			if (sharedByMe(p.getUUID(), clean)) ArenaStore.unpublish(p.getUUID(), clean, false);
			ArenaStore.delete(ArenaStore.key(p.getUUID(), clean));
		} catch (IOException e) {
			return ArenaStudio.answer(source, "Could not delete the arena: " + e.getMessage());
		}
		source.sendSuccess(() -> Msg.ok("Deleted the arena '" + clean + "'."), false);
		return 1;
	}
}
