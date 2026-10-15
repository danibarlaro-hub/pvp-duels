package com.pvpduels.duel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reactions of the people who watch a duel: /react (a menu) or /react gg, fire, wow, love, ouch, clap. The two duelists and the other
 * watchers see "Carl: GG!" on their screen and a few particles rise over both duelists. It is only for spectators (the duelists have the
 * chat), with a short wait between two reactions so nobody can flood the screen.
 */
public final class Reactions {
	/** One reaction: the word of the command, what is shown, the icon of the menu and the particles over the duelists. */
	public record Reaction(String id, String text, Item icon, ParticleOptions particle, int count) {}

	public static final List<Reaction> ALL = List.of(
		new Reaction("gg", "GG!", Items.GOLDEN_APPLE, ParticleTypes.HAPPY_VILLAGER, 8),
		new Reaction("fire", "On fire!", Items.BLAZE_POWDER, ParticleTypes.FLAME, 10),
		new Reaction("wow", "Wow!", Items.ENDER_EYE, ParticleTypes.END_ROD, 10),
		new Reaction("love", "Love it!", Items.POPPY, ParticleTypes.HEART, 6),
		new Reaction("ouch", "Ouch!", Items.REDSTONE, ParticleTypes.CRIT, 12),
		new Reaction("clap", "Clap clap!", Items.GOLD_NUGGET, ParticleTypes.NOTE, 6));

	/** Ticks between two reactions of the same watcher: 3 seconds. */
	public static int waitTicks = 60;

	private static final Map<UUID, Integer> LAST = new HashMap<>();
	private static int shown;
	private static String lastText = "";

	private Reactions() {}

	public static Reaction find(String id) {
		String t = id.toLowerCase(Locale.ROOT);
		for (Reaction r : ALL) if (r.id().equals(t)) return r;
		return null;
	}

	public static List<String> names() {
		List<String> out = new ArrayList<>();
		for (Reaction r : ALL) out.add(r.id());
		return out;
	}

	/** Sends a reaction. Returns an error text, or null when it was shown. */
	public static String react(ServerPlayer p, String id) {
		Reaction r = find(id);
		if (r == null) return "There is no reaction called '" + id + "'. Use one of: " + String.join(", ", names()) + ".";
		Duel duel = Spectators.watching(p.getUUID());
		if (duel == null || duel.closed || duel.phase == Duel.Phase.ENDED) return "Only people who are watching a duel can react. /spectate <player> first.";
		int now = p.level().getServer().getTickCount();
		Integer last = LAST.get(p.getUUID());
		if (last != null && now - last < waitTicks) return "Wait a moment before the next reaction.";
		LAST.put(p.getUUID(), now);

		Component overlay = Component.literal(p.getName().getString() + ": " + r.text()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		List<ServerPlayer> everybody = new ArrayList<>(Spectators.watchers(duel));
		for (int i = 0; i < 2; i++) {
			ServerPlayer d = duel.player(i);
			if (d != null) everybody.add(d);
		}
		for (ServerPlayer v : everybody) v.sendOverlayMessage(overlay);
		for (int i = 0; i < 2; i++) {
			ServerPlayer d = duel.player(i);
			if (d == null || !(d.level() instanceof ServerLevel level)) continue;
			level.sendParticles(r.particle(), d.getX(), d.getY() + 2.3, d.getZ(), r.count(), 0.35, 0.25, 0.35, 0.02);
		}
		shown++;
		lastText = p.getName().getString() + ": " + r.text();
		return null;
	}

	static void openMenu(ServerPlayer p) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		int[] slots = {10, 11, 12, 14, 15, 16};
		for (int i = 0; i < ALL.size(); i++) {
			Reaction r = ALL.get(i);
			b.set(slots[i], DuelMenus.icon(r.icon(), r.text(), ChatFormatting.GOLD, "Click to send it to the duelists", "and the other watchers."), who -> {
				who.closeContainer();
				String problem = react(who, r.id());
				if (problem != null) who.sendSystemMessage(Msg.err(problem));
			});
		}
		ChoiceMenu.open(p, Component.literal("React to the duel"), 3, b);
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("react")
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				if (Spectators.watching(p.getUUID()) == null) {
					ctx.getSource().sendFailure(Msg.err("Only people who are watching a duel can react. /spectate <player> first."));
					return 0;
				}
				openMenu(p);
				return 1;
			})
			.then(Commands.argument("reaction", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(names(), builder))
				.executes(ctx -> {
					String problem = react(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "reaction"));
					if (problem != null) {
						ctx.getSource().sendFailure(Msg.err(problem));
						return 0;
					}
					return 1;
				})));
	}

	// ------------------------------------------------------------------ for the tests

	public static int shown() {
		return shown;
	}

	public static String lastText() {
		return lastText;
	}

	public static void resetForTest() {
		LAST.clear();
		shown = 0;
		lastText = "";
		waitTicks = 60;
	}
}
