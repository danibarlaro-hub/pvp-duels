package com.pvpduels.duel;

import com.pvpduels.kit.KitStore;
import com.pvpduels.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The chest menus of /duel: kit, arena, version, win mode, amount, confirm. All text is English. */
public final class DuelMenus {
	private static final int[] ROUND_OPTIONS = {1, 2, 3, 5, 7, 10, 15, 20, 25, 50, 100};
	private static final int[] MINUTE_OPTIONS = {3, 5, 10, 15, 20, 30};

	private DuelMenus() {}

	/** What the challenger has picked so far. */
	private static final class Setup {
		final UUID opponent;
		final String opponentName;
		String kit;
		ArenaKind arena = ArenaKind.FLAT;
		String custom = "";
		int radius = 100;
		boolean walls;
		boolean regen = true;
		boolean fallDamage = true;
		boolean hunger = true;
		boolean building = true;
		WinMode mode = WinMode.ROUNDS;
		int limit = 3;

		Setup(ServerPlayer opponent) {
			this.opponent = opponent.getUUID();
			this.opponentName = opponent.getName().getString();
		}
	}

	static ItemStack icon(Item item, String name, ChatFormatting color, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withColor(color).withItalic(false)));
		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();
			for (String line : lore) {
				lines.add(Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
			}
			stack.set(DataComponents.LORE, new ItemLore(lines));
		}
		return stack;
	}

	static ItemStack withName(ItemStack base, String name, ChatFormatting color, String... lore) {
		ItemStack stack = base.copy();
		stack.setCount(1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withColor(color).withItalic(false)));
		List<Component> lines = new ArrayList<>();
		for (String line : lore) {
			lines.add(Component.literal(line).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false)));
		}
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	// ------------------------------------------------------------------ entry point

	public static void open(ServerPlayer challenger, ServerPlayer opponent) {
		if (KitStore.list(challenger.getUUID()).isEmpty()) {
			challenger.sendSystemMessage(Msg.err("You have no kits yet. Set up your inventory and run /kit save <name> first."));
			return;
		}
		kits(challenger, new Setup(opponent), 0);
	}

	// ------------------------------------------------------------------ 1. kit

	private static ItemStack kitIcon(MinecraftServer server, UUID owner, String name) {
		try {
			Optional<KitStore.Kit> kit = KitStore.read(server, owner, name);
			if (kit.isPresent()) {
				ItemStack[] slots = kit.get().slots();
				for (int i = 0; i < 36; i++) {
					if (!slots[i].isEmpty()) return withName(slots[i], name, ChatFormatting.AQUA, "Click to choose this kit");
				}
				for (int i = 36; i < slots.length; i++) {
					if (!slots[i].isEmpty()) return withName(slots[i], name, ChatFormatting.AQUA, "Click to choose this kit");
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// fall through to the plain chest
		}
		return icon(Items.CHEST, name, ChatFormatting.AQUA, "Click to choose this kit");
	}

	private static void kits(ServerPlayer p, Setup setup, int page) {
		pickKit(p, "Duel vs " + setup.opponentName + ": choose a kit", page, (who, name) -> {
			setup.kit = name;
			arena(who, setup);
		});
	}

	/** A paged list of the player's kits; clicking one runs onPick. */
	static void pickKit(ServerPlayer p, String title, int page, java.util.function.BiConsumer<ServerPlayer, String> onPick) {
		MinecraftServer server = p.level().getServer();
		List<String> names = KitStore.list(p.getUUID());
		int pages = Math.max(1, (names.size() + 44) / 45);
		int current = Math.max(0, Math.min(page, pages - 1));

		ChoiceMenu.Builder b = new ChoiceMenu.Builder(6);
		for (int i = 0; i < 45; i++) {
			int index = current * 45 + i;
			if (index >= names.size()) break;
			String name = names.get(index);
			b.set(i, kitIcon(server, p.getUUID(), name), who -> onPick.accept(who, name));
		}
		if (current > 0) b.set(45, icon(Items.ARROW, "Previous page", ChatFormatting.YELLOW), who -> pickKit(who, title, current - 1, onPick));
		b.set(49, icon(Items.BARRIER, "Cancel", ChatFormatting.RED), ServerPlayer::closeContainer);
		if (current < pages - 1) b.set(53, icon(Items.ARROW, "Next page", ChatFormatting.YELLOW), who -> pickKit(who, title, current + 1, onPick));
		ChoiceMenu.open(p, Component.literal(title + " (" + (current + 1) + "/" + pages + ")"), 6, b);
	}

	// ------------------------------------------------------------------ /botduel

	/** /botduel: choose a kit (unless one was given), then the Yes / No question. */
	public static void openBot(ServerPlayer p, String kit) {
		if (KitStore.list(p.getUUID()).isEmpty()) {
			p.sendSystemMessage(Msg.err("You have no kits yet. Set up your inventory and run /kit save <name> first."));
			return;
		}
		if (kit != null) botQuestion(p, kit, false);
		else botKits(p);
	}

	private static void botKits(ServerPlayer p) {
		pickKit(p, "Bot duel: choose a kit", 0, (who, name) -> botQuestion(who, name, true));
	}

	/** The Yes / No question: Yes = a static bot, No = a bot that can be pushed (then you choose how it behaves). */
	private static void botQuestion(ServerPlayer p, String kit, boolean canGoBack) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(13, icon(Items.BOOK, "Practice duel vs a bot", ChatFormatting.GOLD,
			"Kit: " + kit,
			"Arena: bedrock platform, " + Arena.BOT_RADIUS + " blocks each way, with walls.",
			"It goes on until you use /leave. A knocked-out bot gets up again.",
			"Practice duels don't count for your stats.",
			"",
			"Yes: a static bot. It can't be moved and never fights back.",
			"No: a bot that takes knockback. You pick how it behaves."));
		b.set(11, icon(Items.EMERALD, "Yes", ChatFormatting.GREEN, "Static bot: it can't be moved."), who -> startBot(who, kit, BotLevel.STATIC));
		b.set(15, icon(Items.BARRIER, "No", ChatFormatting.RED, "The bot can be pushed (knockback)."), who -> botLevels(who, kit, canGoBack));
		b.set(22, icon(Items.CLOCK, "Combo drill", ChatFormatting.AQUA, Duel.drillTicks / 20 + " seconds against a bot that never hits back.",
			"It counts your hits, best combo and accuracy,", "and keeps your records (/drill records).", "Click to start"), who -> {
			who.closeContainer();
			BotDuel.startLater(who, kit, BotLevel.PASSIVE, true);
		});
		if (canGoBack) b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), DuelMenus::botKits);
		ChoiceMenu.open(p, Component.literal("Static bot?"), 3, b);
	}

	private static void botLevels(ServerPlayer p, String kit, boolean canGoBack) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		Item[] items = {Items.FEATHER, Items.PAPER, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
		BotLevel[] levels = {BotLevel.PASSIVE, BotLevel.EASY, BotLevel.NORMAL, BotLevel.HARD};
		int[] slots = {10, 12, 14, 16};
		for (int i = 0; i < levels.length; i++) {
			BotLevel level = levels[i];
			b.set(slots[i], icon(items[i], level.label, ChatFormatting.GREEN, level.description, "Click to start"), who -> startBot(who, kit, level));
		}
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> botQuestion(who, kit, canGoBack));
		ChoiceMenu.open(p, Component.literal("How should the bot behave?"), 3, b);
	}

	/** /drill without a kit: choose one, then the drill starts. */
	static void openDrill(ServerPlayer p) {
		pickKit(p, "Combo drill: choose a kit", 0, (who, name) -> {
			who.closeContainer();
			BotDuel.startLater(who, name, BotLevel.PASSIVE, true);
		});
	}

	private static void startBot(ServerPlayer who, String kit, BotLevel level) {
		who.closeContainer();
		BotDuel.startLater(who, kit, level);
	}

	// ------------------------------------------------------------------ 2. arena

	private static void arena(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, icon(Items.END_CRYSTAL, "Flat void arena", ChatFormatting.GREEN,
			"A 21x21 bedrock platform over the void.", "You can only place entities and block entities.", "Falling off loses the round."),
			who -> {
				setup.arena = ArenaKind.FLAT;
				version(who, setup);
			});
		b.set(13, icon(Items.GRASS_BLOCK, "Circular arena", ChatFormatting.GREEN,
			"Flat normal terrain inside a circle of invisible barriers.", "You can build and dig anywhere.",
			"The terrain is reset after every round.", "Leaving the circle loses the round."),
			who -> {
				setup.arena = ArenaKind.CIRCLE;
				radius(who, setup);
			});
		b.set(15, icon(Items.ELYTRA, "Unlimited arena", ChatFormatting.GREEN,
			"Flat normal terrain with no border.", "You can build and dig anywhere.",
			"If you stay more than 100 blocks away from your rival for 5 seconds,",
			"you lose half of your health and get teleported to them."),
			who -> {
				setup.arena = ArenaKind.UNLIMITED;
				mode(who, setup);
			});
		b.set(22, icon(Items.CRAFTING_TABLE, "Custom arena", ChatFormatting.GREEN,
			"An arena a Builder made: yours or a public one.", "You can build and dig unless you switch building off.",
			"The arena is reset after every round."),
			who -> ArenaMenus.pickArena(who, "Duel: choose a custom arena", 0, () -> arena(who, setup), (w, pick) -> {
				setup.arena = ArenaKind.CUSTOM;
				setup.custom = pick[0];
				mode(w, setup);
			}));
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> kits(who, setup, 0));
		ChoiceMenu.open(p, Component.literal("Duel: choose an arena"), 3, b);
	}

	// ------------------------------------------------------------------ 2b. circle radius

	private static void radius(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		int[] options = {100, 150, 200};
		int slot = 11;
		for (int value : options) {
			b.set(slot, icon(Items.GRASS_BLOCK, "Radius " + value, ChatFormatting.GREEN,
				"The circle is " + (value * 2) + " blocks wide."), who -> {
				setup.radius = value;
				mode(who, setup);
			});
			slot += 2;
		}
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> arena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: how big is the circle?"), 3, b);
	}

	/** The "Back" button of the win-mode menu returns to the last menu of the chosen arena. */
	private static void backToArena(ServerPlayer p, Setup setup) {
		switch (setup.arena) {
			case FLAT -> version(p, setup);
			case CIRCLE -> radius(p, setup);
			default -> arena(p, setup);
		}
	}

	// ------------------------------------------------------------------ 3. version

	private static void version(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(11, icon(Items.BEDROCK, "No walls", ChatFormatting.GREEN, "Knockback can push you off the edge."), who -> {
			setup.walls = false;
			mode(who, setup);
		});
		b.set(15, icon(Items.OBSIDIAN, "With walls", ChatFormatting.GREEN, "Bedrock walls around the platform."), who -> {
			setup.walls = true;
			mode(who, setup);
		});
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> arena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: choose a version"), 3, b);
	}

	// ------------------------------------------------------------------ 4. win mode

	private static void mode(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(10, icon(Items.PAPER, "Fixed rounds", ChatFormatting.GREEN,
			"Play a set number of rounds.", "Most points at the end wins (ties play an extra round).", "Pick \"Unlimited\" to play until someone uses /leave."),
			who -> {
				setup.mode = WinMode.ROUNDS;
				amount(who, setup);
			});
		b.set(13, icon(Items.NETHER_STAR, "First to N points", ChatFormatting.GREEN,
			"The first player to reach N points wins.", "Pick \"Unlimited\" to play until someone uses /leave."),
			who -> {
				setup.mode = WinMode.FIRST_TO;
				amount(who, setup);
			});
		b.set(16, icon(Items.CLOCK, "Timed", ChatFormatting.GREEN,
			"Play until the time runs out (max 30 minutes).", "The running round is finished, then most points wins."),
			who -> {
				setup.mode = WinMode.TIMED;
				amount(who, setup);
			});
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> backToArena(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: how is it decided?"), 3, b);
	}

	// ------------------------------------------------------------------ 5. amount

	private static void amount(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(4);
		int[] options = setup.mode == WinMode.TIMED ? MINUTE_OPTIONS : ROUND_OPTIONS;
		int slot = 9;
		for (int value : options) {
			String label = switch (setup.mode) {
				case ROUNDS -> value + (value == 1 ? " round" : " rounds");
				case FIRST_TO -> "First to " + value;
				case TIMED -> value + " minutes";
			};
			ItemStack stack = icon(Items.PAPER, label, ChatFormatting.YELLOW);
			stack.setCount(Math.min(value, 64));
			b.set(slot, stack, who -> {
				setup.limit = value;
				rules(who, setup);
			});
			slot++;
		}
		if (setup.mode != WinMode.TIMED) {
			b.set(22, icon(Items.BOOK, "Unlimited", ChatFormatting.LIGHT_PURPLE, "No limit: the duel lasts until someone uses /leave.",
				"Only a small score is shown on screen."), who -> {
				setup.limit = 0;
				rules(who, setup);
			});
		}
		b.set(27, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> mode(who, setup));
		String what = switch (setup.mode) {
			case ROUNDS -> "how many rounds?";
			case FIRST_TO -> "how many points?";
			case TIMED -> "how long?";
		};
		ChoiceMenu.open(p, Component.literal("Duel: " + what), 4, b);
	}

	// ------------------------------------------------------------------ 6. rules

	private static ItemStack toggle(Item item, String label, boolean on, String... lore) {
		return icon(item, label + ": " + (on ? "ON" : "OFF"), on ? ChatFormatting.GREEN : ChatFormatting.RED, lore);
	}

	private static void rules(ServerPlayer p, Setup setup) {
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(10, toggle(Items.GOLDEN_APPLE, "Natural regeneration", setup.regen,
			"Off: health only comes back from items and effects.", "Click to switch"), who -> {
			setup.regen = !setup.regen;
			rules(who, setup);
		});
		b.set(12, toggle(Items.FEATHER, "Fall damage", setup.fallDamage, "Click to switch"), who -> {
			setup.fallDamage = !setup.fallDamage;
			rules(who, setup);
		});
		b.set(14, toggle(Items.COOKED_BEEF, "Hunger", setup.hunger,
			"Off: the food bar stays full.", setup.regen ? "Click to switch" : "(Not used while natural regeneration is off.)"), who -> {
			setup.hunger = !setup.hunger;
			rules(who, setup);
		});
		if (setup.arena == ArenaKind.FLAT) {
			b.set(16, icon(Items.BEDROCK, "Building", ChatFormatting.GRAY, "The flat arena has its own building rules."));
		} else {
			b.set(16, toggle(Items.GRASS_BLOCK, "Building", setup.building,
				"Off: nobody can place or break blocks.", "Click to switch"), who -> {
				setup.building = !setup.building;
				rules(who, setup);
			});
		}
		b.set(22, icon(Items.EMERALD, "Continue", ChatFormatting.GREEN), who -> confirm(who, setup));
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> amount(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: rules"), 3, b);
	}

	// ------------------------------------------------------------------ 7. confirm

	private static DuelConfig config(Setup setup) {
		if (setup.arena == ArenaKind.CUSTOM) {
			return DuelConfig.inCustomArena(setup.kit, setup.custom, setup.mode, setup.limit,
				new DuelConfig.Rules(setup.regen, setup.fallDamage, setup.hunger, setup.building));
		}
		return new DuelConfig(setup.kit, setup.arena, setup.walls, setup.arena == ArenaKind.CIRCLE ? setup.radius : 0, setup.mode, setup.limit,
			new DuelConfig.Rules(setup.regen, setup.fallDamage, setup.hunger, setup.building));
	}

	private static void confirm(ServerPlayer p, Setup setup) {
		DuelConfig config = config(setup);
		ChoiceMenu.Builder b = new ChoiceMenu.Builder(3);
		b.set(13, icon(Items.BOOK, "Duel vs " + setup.opponentName, ChatFormatting.GOLD,
			"Kit: " + config.kit(), "Arena: " + config.arenaName(), "Mode: " + config.modeName(), "Rules: " + config.rules().describe(config.arena())));
		b.set(11, icon(Items.EMERALD, "Send the challenge", ChatFormatting.GREEN), who -> {
			who.closeContainer();
			ServerPlayer target = who.level().getServer().getPlayerList().getPlayer(setup.opponent);
			if (target == null) {
				who.sendSystemMessage(Msg.err(setup.opponentName + " is no longer online."));
				return;
			}
			DuelManager.sendInvite(who, target, who.getUUID(), config, false);
		});
		b.set(15, icon(Items.BARRIER, "Cancel", ChatFormatting.RED), ServerPlayer::closeContainer);
		b.set(18, icon(Items.ARROW, "Back", ChatFormatting.YELLOW), who -> rules(who, setup));
		ChoiceMenu.open(p, Component.literal("Duel: confirm"), 3, b);
	}
}
